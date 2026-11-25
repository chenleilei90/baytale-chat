package com.baytale.baychat.core.network

import com.baytale.baychat.core.model.AppReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

// 企业级纯 Kotlin 可单测断点续传 APK 下载引擎（零 Android 依赖，可在纯 JVM JUnit 直接运行）
class ResumableApkDownloader(
    private val okHttpClient: OkHttpClient,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    // 本地更新目录状态检测结果（用于发现新版本后瞬间判断命中缓存或恢复断点进度）
    data class LocalUpdateInspection(
        val readyApkFile: File?,
        val partFile: File,
        val partialBytes: Long,
        val totalBytes: Long,
    )

    // HTTP Content-Range 头解析结果
    data class ContentRangeInfo(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    // 下载最终结果
    sealed class DownloadResult {
        data class Success(
            val apkFile: File,
            val fromCache: Boolean,
        ) : DownloadResult()

        data class Paused(
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadResult()

        data class Error(
            val message: String,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val sha256Mismatch: Boolean = false,
        ) : DownloadResult()
    }

    /**
     * 执行支持 HTTP Range 断点续传、指数退避重试、主备源自动切换与全文件 SHA-256 校验的安装包下载。
     *
     * @param release 目标版本信息
     * @param updatesDir 更新文件持久化目录（Android 端应传入 context.filesDir/updates）
     * @param shouldPause 外部暂停检查回调；返回 true 时保留 .part 断点文件并优雅退出
     * @param onVerifying 进入 SHA-256 完整性校验阶段前回调
     * @param onProgress 每 200ms 节流回调实时进度、总大小、实时网速（字节/秒）及当前重试序号
     */
    suspend fun download(
        release: AppReleaseInfo,
        updatesDir: File,
        shouldPause: () -> Boolean = { false },
        onVerifying: () -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, attempt: Int) -> Unit = { _, _, _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (release.apkUrl.isBlank()) {
            return@withContext DownloadResult.Error("服务器未提供有效的安装包下载地址")
        }

        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        val apkFile = targetApkFile(updatesDir, release)
        val partFile = targetPartFile(updatesDir, release)

        // 第一步：自动垃圾回收，清理非当前目标版本的旧 .apk / .part / .tmp 文件
        cleanupObsoleteFiles(updatesDir, release)

        // 第二步：本地完整包秒命中（校验 SHA-256 通过则 0 字节直接返回就绪）
        if (apkFile.exists() && apkFile.isFile && apkFile.length() > 0L) {
            val sizeValid = release.sizeBytes <= 0L || apkFile.length() == release.sizeBytes
            if (sizeValid) {
                if (release.sha256.isNotBlank()) {
                    onVerifying()
                }
                if (verifyIntegrity(apkFile, release)) {
                    if (partFile.exists()) runCatching { partFile.delete() }
                    val finalLen = apkFile.length()
                    onProgress(finalLen, finalLen, 0L, 0)
                    return@withContext DownloadResult.Success(apkFile = apkFile, fromCache = true)
                }
            }
            // 本地缓存包长度异常或校验失败，删掉后重新下载
            runCatching { apkFile.delete() }
        }

        val totalAttempts = maxRetries.coerceAtLeast(0) + 1
        val primaryUrl = release.apkUrl.trim()
        val fallbackUrl = release.fallbackUrl.trim()
        val hasFallback = fallbackUrl.isNotBlank() && fallbackUrl != primaryUrl

        var primaryConsecutiveFailures = 0
        var lastErrorMessage = "安装包下载失败，请检查网络后重试"
        var knownTotalBytes = release.sizeBytes.coerceAtLeast(0L)

        for (attempt in 1..totalAttempts) {
            currentCoroutineContext().ensureActive()
            if (shouldPause()) {
                return@withContext DownloadResult.Paused(partFile.safeLength(), knownTotalBytes)
            }

            // 每次尝试前重新检测 .part 断点状态
            var existingBytes = partFile.safeLength()
            if (existingBytes > 0L && release.sizeBytes > 0L && existingBytes > release.sizeBytes) {
                // 断点文件长度大于预期总大小，说明已损坏，清理后从 0 开始
                runCatching { partFile.delete() }
                existingBytes = 0L
            } else if (existingBytes > 0L && release.sizeBytes > 0L && existingBytes == release.sizeBytes) {
                // 断点文件长度已恰好等于总大小：先校验 SHA-256，通过直接 rename 秒完成，否则删掉重下
                onVerifying()
                if (verifyIntegrity(partFile, release)) {
                    val promoted = promotePartToApk(partFile, apkFile)
                    val finalLen = promoted.length()
                    onProgress(finalLen, finalLen, 0L, attempt)
                    return@withContext DownloadResult.Success(apkFile = promoted, fromCache = false)
                } else {
                    runCatching { partFile.delete() }
                    existingBytes = 0L
                }
            }

            // 若主节点连续 2 次失败且配置了备用节点，自动切换至 fallbackUrl 继续断点续传
            val usingFallback = hasFallback && primaryConsecutiveFailures >= PRIMARY_FAILURES_BEFORE_FALLBACK
            val requestUrl = if (usingFallback) fallbackUrl else primaryUrl

            var attemptSucceeded = false
            var immediateRetryWithoutBackoff = false

            val reqBuilder = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", USER_AGENT)
                // 显式关闭透明 gzip 压缩，防止 Content-Length 与 Range 字节偏移失真
                .header("Accept-Encoding", "identity")
                .get()
            if (existingBytes > 0L) {
                reqBuilder.header("Range", "bytes=$existingBytes-")
            }

            val call = okHttpClient.newCall(reqBuilder.build())
            // 绑定协程取消句柄：协程取消时立即中断阻塞中的网络读写，同时保留 .part 断点
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    runCatching { call.cancel() }
                }
            }

            try {
                call.execute().use { resp ->
                    when (resp.code) {
                        206 -> {
                            // Partial Content：必须校验 Content-Range 起始偏移与本地 existingBytes 严格一致
                            val contentRange = parseContentRange(resp.header("Content-Range"))
                            if (existingBytes > 0L && (contentRange == null || contentRange.start != existingBytes)) {
                                // 服务端返回偏移不匹配，删除损坏断点，下次从 0 重下
                                runCatching { partFile.delete() }
                                lastErrorMessage = "服务端断点偏移不匹配，正在重新同步"
                                if (!usingFallback) primaryConsecutiveFailures++
                            } else {
                                val body = resp.body
                                if (body == null) {
                                    lastErrorMessage = "服务器未返回分片数据"
                                    if (!usingFallback) primaryConsecutiveFailures++
                                } else {
                                    val bodyLen = body.contentLength()
                                    val expectedTotal = (
                                        contentRange?.total?.takeIf { it > 0L }
                                            ?: bodyLen.takeIf { it > 0L }?.let { existingBytes + it }
                                            ?: release.sizeBytes
                                        ).coerceAtLeast(0L)
                                    if (expectedTotal > 0L) knownTotalBytes = expectedTotal

                                    val streamOutcome = streamResponseToPart(
                                        input = body.byteStream(),
                                        partFile = partFile,
                                        append = existingBytes > 0L,
                                        startBytes = existingBytes,
                                        totalBytes = expectedTotal,
                                        expectedChunkBytes = bodyLen,
                                        attempt = attempt,
                                        shouldPause = shouldPause,
                                        onProgress = onProgress,
                                    )

                                    when (streamOutcome) {
                                        is StreamOutcome.Paused -> {
                                            return@withContext DownloadResult.Paused(
                                                downloadedBytes = streamOutcome.downloadedBytes,
                                                totalBytes = expectedTotal,
                                            )
                                        }
                                        is StreamOutcome.Incomplete -> {
                                            lastErrorMessage = streamOutcome.reason
                                            if (!usingFallback) primaryConsecutiveFailures++
                                        }
                                        is StreamOutcome.Finished -> {
                                            attemptSucceeded = true
                                        }
                                    }
                                }
                            }
                        }

                        200 -> {
                            // 服务端返回全量 200 OK（不支持 Range 或从 0 下载）：安全覆写 .part 从头开始
                            val body = resp.body
                            if (body == null) {
                                lastErrorMessage = "服务器未返回安装包数据"
                                if (!usingFallback) primaryConsecutiveFailures++
                            } else {
                                val bodyLen = body.contentLength()
                                val expectedTotal = bodyLen.takeIf { it > 0L } ?: release.sizeBytes
                                if (expectedTotal > 0L) knownTotalBytes = expectedTotal

                                val streamOutcome = streamResponseToPart(
                                    input = body.byteStream(),
                                    partFile = partFile,
                                    append = false,
                                    startBytes = 0L,
                                    totalBytes = expectedTotal,
                                    expectedChunkBytes = bodyLen,
                                    attempt = attempt,
                                    shouldPause = shouldPause,
                                    onProgress = onProgress,
                                )

                                when (streamOutcome) {
                                    is StreamOutcome.Paused -> {
                                        return@withContext DownloadResult.Paused(
                                            downloadedBytes = streamOutcome.downloadedBytes,
                                            totalBytes = expectedTotal,
                                        )
                                    }
                                    is StreamOutcome.Incomplete -> {
                                        lastErrorMessage = streamOutcome.reason
                                        if (!usingFallback) primaryConsecutiveFailures++
                                    }
                                    is StreamOutcome.Finished -> {
                                        attemptSucceeded = true
                                    }
                                }
                            }
                        }

                        416 -> {
                            // Range Not Satisfiable：先判断本地 .part 是否已完整，若完整直接校验 SHA-256，否则清理重下
                            val totalFrom416 = parseTotalFrom416ContentRange(resp.header("Content-Range"))
                            val lengthMatches = existingBytes > 0L && (
                                (release.sizeBytes > 0L && existingBytes == release.sizeBytes) ||
                                    (totalFrom416 != null && totalFrom416 > 0L && existingBytes == totalFrom416)
                                )
                            if (lengthMatches) {
                                onVerifying()
                                if (verifyIntegrity(partFile, release)) {
                                    val promoted = promotePartToApk(partFile, apkFile)
                                    val finalLen = promoted.length()
                                    onProgress(finalLen, finalLen, 0L, attempt)
                                    return@withContext DownloadResult.Success(apkFile = promoted, fromCache = false)
                                }
                            }
                            // 本地断点已失效或长度不符，清理后从 0 重下
                            runCatching { partFile.delete() }
                            lastErrorMessage = "断点范围已失效 (HTTP 416)，正在重新下载"
                            immediateRetryWithoutBackoff = true
                        }

                        else -> {
                            lastErrorMessage = "安装包下载失败 (HTTP ${resp.code})"
                            if (!usingFallback) primaryConsecutiveFailures++
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // 协程取消时绝不删除 .part 断点，保证下次秒级续传
                throw ce
            } catch (e: IOException) {
                if (shouldPause()) {
                    return@withContext DownloadResult.Paused(partFile.safeLength(), knownTotalBytes)
                }
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("更新下载协程已取消", e)
                }
                lastErrorMessage = "网络波动中断：${e.message ?: "连接已断开"}"
                if (!usingFallback) primaryConsecutiveFailures++
            } catch (e: Exception) {
                if (shouldPause()) {
                    return@withContext DownloadResult.Paused(partFile.safeLength(), knownTotalBytes)
                }
                lastErrorMessage = e.message ?: "更新下载异常中断"
                if (!usingFallback) primaryConsecutiveFailures++
            } finally {
                cancelHandle?.dispose()
            }

            // 下载流完整结束：对整个 .part 文件重新流式计算 SHA-256（断点续传严禁只校验后半段）
            if (attemptSucceeded) {
                onVerifying()
                val actualLen = partFile.safeLength()
                val sizeOk = release.sizeBytes <= 0L || actualLen == release.sizeBytes
                if (!sizeOk) {
                    // 文件大小不符（且可能被截断或污染），删除损坏文件
                    runCatching { partFile.delete() }
                    lastErrorMessage = "安装包大小不完整（实际 $actualLen 字节，预期 ${release.sizeBytes} 字节）"
                    if (!usingFallback) primaryConsecutiveFailures++
                } else if (release.sha256.isNotBlank()) {
                    val actualHash = computeSha256(partFile)
                    if (!actualHash.equals(release.sha256.trim(), ignoreCase = true)) {
                        // SHA-256 校验不匹配：立即删除被篡改或损坏的 .part 文件
                        runCatching { partFile.delete() }
                        if (hasFallback && !usingFallback && attempt < totalAttempts) {
                            // 若有备用节点且尚未尝试过备用节点，立即切换到备用节点重下
                            primaryConsecutiveFailures = PRIMARY_FAILURES_BEFORE_FALLBACK
                            lastErrorMessage = "主节点安装包校验异常，已切换备用节点重新下载"
                        } else {
                            return@withContext DownloadResult.Error(
                                message = "安装包 SHA-256 完整性校验失败，已自动清理损坏文件，请重试",
                                downloadedBytes = 0L,
                                totalBytes = knownTotalBytes,
                                sha256Mismatch = true,
                            )
                        }
                    } else {
                        // 全文件 SHA-256 校验 100% 通过：原子重命名为最终 .apk
                        val promoted = promotePartToApk(partFile, apkFile)
                        val finalLen = promoted.length()
                        onProgress(finalLen, finalLen, 0L, attempt)
                        return@withContext DownloadResult.Success(apkFile = promoted, fromCache = false)
                    }
                } else {
                    // 未配置 SHA-256 时直接原子重命名
                    val promoted = promotePartToApk(partFile, apkFile)
                    val finalLen = promoted.length()
                    onProgress(finalLen, finalLen, 0L, attempt)
                    return@withContext DownloadResult.Success(apkFile = promoted, fromCache = false)
                }
            }

            // 仍有剩余重试次数：执行指数退避等待（保留 .part 断点），等待期间同样响应暂停
            if (attempt < totalAttempts) {
                if (!immediateRetryWithoutBackoff) {
                    val delayMs = retryDelaysMs.getOrElse(attempt - 1) {
                        retryDelaysMs.lastOrNull() ?: 6000L
                    }.coerceAtLeast(0L)
                    if (!sleepInterruptibly(delayMs, shouldPause)) {
                        return@withContext DownloadResult.Paused(partFile.safeLength(), knownTotalBytes)
                    }
                }
            }
        }

        DownloadResult.Error(
            message = lastErrorMessage,
            downloadedBytes = partFile.safeLength(),
            totalBytes = knownTotalBytes,
            sha256Mismatch = false,
        )
    }

    private sealed class StreamOutcome {
        data class Finished(val downloadedBytes: Long) : StreamOutcome()
        data class Paused(val downloadedBytes: Long) : StreamOutcome()
        data class Incomplete(val downloadedBytes: Long, val reason: String) : StreamOutcome()
    }

    private suspend fun streamResponseToPart(
        input: java.io.InputStream,
        partFile: File,
        append: Boolean,
        startBytes: Long,
        totalBytes: Long,
        expectedChunkBytes: Long,
        attempt: Int,
        shouldPause: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, attempt: Int) -> Unit,
    ): StreamOutcome {
        var downloaded = startBytes
        var readInThisCall = 0L
        var lastReportTime = clock()
        var lastSpeedSampleTime = lastReportTime
        var lastSpeedSampleBytes = downloaded
        var smoothedSpeedBps = 0L

        // 立刻上报一次起始进度，让断点续传瞬间展示已下载百分比
        onProgress(downloaded, totalBytes, 0L, attempt)

        input.use { stream ->
            FileOutputStream(partFile, append).use { output ->
                val buf = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    if (shouldPause()) {
                        output.flush()
                        return StreamOutcome.Paused(downloaded)
                    }
                    currentCoroutineContext().ensureActive()

                    val read = stream.read(buf)
                    if (read == -1) break
                    if (read == 0) continue

                    output.write(buf, 0, read)
                    downloaded += read
                    readInThisCall += read

                    val now = clock()
                    val reportElapsed = now - lastReportTime
                    if (reportElapsed >= progressIntervalMs || (totalBytes > 0L && downloaded >= totalBytes)) {
                        val speedElapsed = (now - lastSpeedSampleTime).coerceAtLeast(1L)
                        val deltaBytes = (downloaded - lastSpeedSampleBytes).coerceAtLeast(0L)
                        val instantSpeed = (deltaBytes * 1000L) / speedElapsed
                        smoothedSpeedBps = if (smoothedSpeedBps == 0L) {
                            instantSpeed
                        } else {
                            (smoothedSpeedBps * 3L + instantSpeed * 7L) / 10L
                        }
                        lastSpeedSampleTime = now
                        lastSpeedSampleBytes = downloaded
                        lastReportTime = now
                        onProgress(downloaded, totalBytes, smoothedSpeedBps, attempt)
                    }
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
        }

        // 检查服务端是否提前断流（保留已落盘的 .part 断点，由外层重试循环续传）
        if (expectedChunkBytes > 0L && readInThisCall < expectedChunkBytes) {
            return StreamOutcome.Incomplete(
                downloadedBytes = downloaded,
                reason = "下载连接提前断开，已保留断点 (${downloaded}/${totalBytes})",
            )
        }
        if (totalBytes > 0L && downloaded < totalBytes) {
            return StreamOutcome.Incomplete(
                downloadedBytes = downloaded,
                reason = "安装包未完整接收，已保留断点 (${downloaded}/${totalBytes})",
            )
        }

        onProgress(downloaded, totalBytes.coerceAtLeast(downloaded), smoothedSpeedBps, attempt)
        return StreamOutcome.Finished(downloaded)
    }

    private suspend fun sleepInterruptibly(delayMs: Long, shouldPause: () -> Boolean): Boolean {
        if (delayMs <= 0L) return !shouldPause()
        var remaining = delayMs
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            if (shouldPause()) return false
            val step = remaining.coerceAtMost(100L)
            delay(step)
            remaining -= step
        }
        return !shouldPause()
    }

    private fun verifyIntegrity(file: File, release: AppReleaseInfo): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        if (release.sizeBytes > 0L && file.length() != release.sizeBytes) return false
        if (release.sha256.isNotBlank()) {
            val actual = computeSha256(file)
            return actual.equals(release.sha256.trim(), ignoreCase = true)
        }
        return true
    }

    private fun promotePartToApk(partFile: File, apkFile: File): File {
        if (apkFile.exists()) {
            runCatching { apkFile.delete() }
        }
        if (!partFile.renameTo(apkFile)) {
            partFile.copyTo(apkFile, overwrite = true)
            runCatching { partFile.delete() }
        }
        return apkFile
    }

    private fun File.safeLength(): Long = if (exists() && isFile) length().coerceAtLeast(0L) else 0L

    companion object {
        const val DEFAULT_MAX_RETRIES = 4
        const val PRIMARY_FAILURES_BEFORE_FALLBACK = 2
        const val DEFAULT_PROGRESS_INTERVAL_MS = 200L
        private const val IO_BUFFER_SIZE = 32 * 1024
        private const val USER_AGENT = "BayChat-Android-OTA/1.1.4"

        // 指数退避等待间隔：1.5s -> 3s -> 6s（后续重试沿用 6s 上限）
        val DEFAULT_RETRY_DELAYS_MS: List<Long> = listOf(1500L, 3000L, 6000L)

        private val CONTENT_RANGE_REGEX =
            Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
        private val CONTENT_RANGE_416_REGEX =
            Regex("""^bytes\s+\*/(\d+)$""", RegexOption.IGNORE_CASE)

        // 生成标准目标文件名：BayChat-v{versionName}-{sha8}.apk
        fun targetApkFile(updatesDir: File, release: AppReleaseInfo): File {
            val safeVersion = release.versionName.trim().ifBlank { release.versionCode.toString() }
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            val shaTag = release.sha256.trim().lowercase(Locale.ROOT)
                .take(8)
                .ifBlank { "c${release.versionCode}" }
            return File(updatesDir, "BayChat-v${safeVersion}-${shaTag}.apk")
        }

        // 生成断点临时文件名：BayChat-v{versionName}-{sha8}.apk.part
        fun targetPartFile(updatesDir: File, release: AppReleaseInfo): File {
            val apk = targetApkFile(updatesDir, release)
            return File(updatesDir, "${apk.name}.part")
        }

        // 自动垃圾回收：清理 updates/ 目录下非当前目标版本的旧 .apk / .part / .tmp 文件
        fun cleanupObsoleteFiles(updatesDir: File, release: AppReleaseInfo) {
            if (!updatesDir.exists() || !updatesDir.isDirectory) return
            val keepApkName = targetApkFile(updatesDir, release).name
            val keepPartName = targetPartFile(updatesDir, release).name
            updatesDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name
                val isUpdateArtifact = name.endsWith(".apk") || name.endsWith(".part") || name.endsWith(".tmp")
                if (isUpdateArtifact && name != keepApkName && name != keepPartName) {
                    runCatching { file.delete() }
                }
            }
        }

        // 检测本地是否已存在校验通过的完整 APK 或可续传的 .part 断点，同时顺手清理历史垃圾文件
        fun inspectLocalState(updatesDir: File, release: AppReleaseInfo): LocalUpdateInspection {
            if (!updatesDir.exists()) {
                updatesDir.mkdirs()
            }
            cleanupObsoleteFiles(updatesDir, release)

            val apkFile = targetApkFile(updatesDir, release)
            val partFile = targetPartFile(updatesDir, release)

            if (apkFile.exists() && apkFile.isFile && apkFile.length() > 0L) {
                val sizeMatches = release.sizeBytes <= 0L || apkFile.length() == release.sizeBytes
                val shaMatches = release.sha256.isBlank() ||
                    computeSha256(apkFile).equals(release.sha256.trim(), ignoreCase = true)
                if (sizeMatches && shaMatches) {
                    if (partFile.exists()) runCatching { partFile.delete() }
                    return LocalUpdateInspection(
                        readyApkFile = apkFile,
                        partFile = partFile,
                        partialBytes = apkFile.length(),
                        totalBytes = apkFile.length(),
                    )
                } else {
                    runCatching { apkFile.delete() }
                }
            }

            var partialBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
            if (partialBytes > 0L && release.sizeBytes > 0L && partialBytes > release.sizeBytes) {
                runCatching { partFile.delete() }
                partialBytes = 0L
            } else if (partialBytes > 0L && release.sizeBytes > 0L && partialBytes == release.sizeBytes) {
                val shaMatches = release.sha256.isBlank() ||
                    computeSha256(partFile).equals(release.sha256.trim(), ignoreCase = true)
                if (shaMatches) {
                    if (apkFile.exists()) runCatching { apkFile.delete() }
                    if (!partFile.renameTo(apkFile)) {
                        partFile.copyTo(apkFile, overwrite = true)
                        runCatching { partFile.delete() }
                    }
                    return LocalUpdateInspection(
                        readyApkFile = apkFile,
                        partFile = partFile,
                        partialBytes = apkFile.length(),
                        totalBytes = apkFile.length(),
                    )
                } else {
                    runCatching { partFile.delete() }
                    partialBytes = 0L
                }
            }

            return LocalUpdateInspection(
                readyApkFile = null,
                partFile = partFile,
                partialBytes = partialBytes,
                totalBytes = release.sizeBytes.coerceAtLeast(0L),
            )
        }

        // 解析标准 HTTP Content-Range: bytes <start>-<end>/<total|*>
        fun parseContentRange(header: String?): ContentRangeInfo? {
            if (header.isNullOrBlank()) return null
            val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val totalStr = match.groupValues[3]
            val total = if (totalStr == "*") null else totalStr.toLongOrNull()
            if (start < 0L || end < start) return null
            return ContentRangeInfo(start = start, end = end, total = total)
        }

        // 解析 HTTP 416 返回的 Content-Range: bytes */<total>
        fun parseTotalFrom416ContentRange(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            val match = CONTENT_RANGE_416_REGEX.matchEntire(header.trim()) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        // 流式计算完整文件的 SHA-256 十六进制摘要（零大内存占用）
        fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(IO_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            return bytesToHex(md.digest())
        }

        fun bytesToHex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)
            val hex = "0123456789abcdef"
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                chars[i * 2] = hex[v ushr 4]
                chars[i * 2 + 1] = hex[v and 0x0F]
            }
            return String(chars)
        }
    }
}
