package com.baytale.baychat

import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.network.ResumableApkDownloader
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// 企业级 OTA 断点续传下载引擎纯 JVM 单元测试（基于 JDK 内置 HttpServer 真实本地端口回归验证）
class AppUpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private fun generatePayload(sizeBytes: Int): ByteArray =
        ByteArray(sizeBytes) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return ResumableApkDownloader.bytesToHex(digest)
    }

    @Test
    fun testResumableDownloadWithRange206() = runBlocking {
        val payload = generatePayload(256 * 1024)
        val expectedSha = sha256Hex(payload)
        val firstCut = (payload.size * 40) / 100
        val updatesDir = tempFolder.newFolder("updates_range206")

        val requestCount = AtomicInteger(0)
        val receivedRangeHeader = AtomicReference<String?>(null)
        val firstCutReceivedLatch = CountDownLatch(1)

        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/apk/BayChat-v1.1.4.apk") { exchange ->
            val seq = requestCount.incrementAndGet()
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (seq == 1) {
                // 首次请求只发送前 40% 后主动断开连接，模拟弱网/切后台断流
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                val out = exchange.responseBody
                out.write(payload, 0, firstCut)
                out.flush()
                firstCutReceivedLatch.await(3, TimeUnit.SECONDS)
                runCatching { exchange.close() }
            } else {
                // 二次请求校验 Range 头并返回 HTTP 206 Partial Content 剩余 60%
                receivedRangeHeader.set(rangeHeader)
                val start = rangeHeader
                    ?.removePrefix("bytes=")
                    ?.substringBefore("-")
                    ?.toIntOrNull()
                    ?: 0
                val remaining = payload.size - start
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, remaining.toLong())
                exchange.responseBody.use { out ->
                    out.write(payload, start, remaining)
                    out.flush()
                }
            }
        }
        server.start()

        try {
            val url = "http://127.0.0.1:${server.address.port}/apk/BayChat-v1.1.4.apk"
            val release = AppReleaseInfo(
                versionCode = 8,
                versionName = "1.1.5",
                apkUrl = url,
                sha256 = expectedSha,
                sizeBytes = payload.size.toLong(),
                releaseNotes = "断点续传回归测试",
            )
            val partFile = ResumableApkDownloader.targetPartFile(updatesDir, release)

            // 第一次调用（关闭自动重试以便精确断言中断后的 .part 断点保留）
            val firstDownloader = ResumableApkDownloader(
                okHttpClient = createTestClient(),
                maxRetries = 0,
                progressIntervalMs = 0L,
            )
            val firstResult = firstDownloader.download(
                release = release,
                updatesDir = updatesDir,
                onProgress = { downloaded, _, _, _ ->
                    if (downloaded >= firstCut.toLong()) {
                        firstCutReceivedLatch.countDown()
                    }
                },
            )
            assertTrue("首次断流应返回 Error 结果", firstResult is ResumableApkDownloader.DownloadResult.Error)
            assertTrue("断流后必须完整保留 .part 断点文件", partFile.exists())
            assertEquals(firstCut.toLong(), partFile.length())

            // 第二次调用：携带 Range: bytes=<firstCut>- 从 40% 位置无缝续传剩余 60%
            val resumeDownloader = ResumableApkDownloader(
                okHttpClient = createTestClient(),
                maxRetries = 1,
                retryDelaysMs = listOf(10L),
            )
            val secondResult = resumeDownloader.download(
                release = release,
                updatesDir = updatesDir,
            )

            assertEquals("bytes=$firstCut-", receivedRangeHeader.get())
            assertTrue("二次续传应成功完成", secondResult is ResumableApkDownloader.DownloadResult.Success)
            val success = secondResult as ResumableApkDownloader.DownloadResult.Success
            assertFalse(success.fromCache)
            assertTrue(success.apkFile.exists())
            assertEquals(payload.size.toLong(), success.apkFile.length())
            assertFalse("重命名完成后 .part 临时文件应已移除", partFile.exists())
            assertEquals(expectedSha, ResumableApkDownloader.computeSha256(success.apkFile))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun testAutoRetryAndFallbackUrl() = runBlocking {
        val payload = generatePayload(96 * 1024)
        val expectedSha = sha256Hex(payload)
        val updatesDir = tempFolder.newFolder("updates_fallback")

        val primaryHits = AtomicInteger(0)
        val fallbackHits = AtomicInteger(0)

        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/primary/BayChat.apk") { exchange ->
            primaryHits.incrementAndGet()
            val msg = "Service Unavailable".toByteArray()
            exchange.sendResponseHeaders(503, msg.size.toLong())
            exchange.responseBody.use { it.write(msg) }
        }
        server.createContext("/fallback/BayChat.apk") { exchange ->
            fallbackHits.incrementAndGet()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { out ->
                out.write(payload)
                out.flush()
            }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val release = AppReleaseInfo(
                versionCode = 9,
                versionName = "1.1.6",
                apkUrl = "$baseUrl/primary/BayChat.apk",
                fallbackUrl = "$baseUrl/fallback/BayChat.apk",
                sha256 = expectedSha,
                sizeBytes = payload.size.toLong(),
            )
            val downloader = ResumableApkDownloader(
                okHttpClient = createTestClient(),
                maxRetries = 4,
                retryDelaysMs = listOf(10L, 15L, 20L),
            )
            val result = downloader.download(release = release, updatesDir = updatesDir)

            assertTrue("主节点失败后应自动切至备用节点并成功", result is ResumableApkDownloader.DownloadResult.Success)
            val success = result as ResumableApkDownloader.DownloadResult.Success
            assertEquals("主节点应连续重试 2 次后才切换备用节点", 2, primaryHits.get())
            assertEquals("备用节点应成功命中 1 次", 1, fallbackHits.get())
            assertTrue(success.apkFile.exists())
            assertEquals(expectedSha, ResumableApkDownloader.computeSha256(success.apkFile))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun testSha256MismatchRejectsAndDeletesCorruptFile() = runBlocking {
        val originalPayload = generatePayload(64 * 1024)
        val expectedSha = sha256Hex(originalPayload)
        // 篡改其中 1 个字节模拟传输污染或中间人篡改
        val tamperedPayload = originalPayload.copyOf().apply {
            this[size / 2] = (this[size / 2].toInt() xor 0xFF).toByte()
        }
        val updatesDir = tempFolder.newFolder("updates_corrupt")

        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/corrupt/BayChat.apk") { exchange ->
            exchange.sendResponseHeaders(200, tamperedPayload.size.toLong())
            exchange.responseBody.use { out ->
                out.write(tamperedPayload)
                out.flush()
            }
        }
        server.start()

        try {
            val release = AppReleaseInfo(
                versionCode = 10,
                versionName = "1.1.7",
                apkUrl = "http://127.0.0.1:${server.address.port}/corrupt/BayChat.apk",
                sha256 = expectedSha,
                sizeBytes = originalPayload.size.toLong(),
            )
            val downloader = ResumableApkDownloader(
                okHttpClient = createTestClient(),
                maxRetries = 2,
                retryDelaysMs = listOf(10L, 10L),
            )
            val result = downloader.download(release = release, updatesDir = updatesDir)

            assertTrue("篡改数据必须被 SHA-256 校验拦截", result is ResumableApkDownloader.DownloadResult.Error)
            val error = result as ResumableApkDownloader.DownloadResult.Error
            assertTrue("应标记 sha256Mismatch = true", error.sha256Mismatch)
            assertFalse(
                "校验失败绝不能产出最终 .apk 文件",
                ResumableApkDownloader.targetApkFile(updatesDir, release).exists(),
            )
            assertFalse(
                "损坏的 .part 临时文件必须立即删除",
                ResumableApkDownloader.targetPartFile(updatesDir, release).exists(),
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun testCachedApkSkipsNetworkDownload() = runBlocking {
        val payload = generatePayload(48 * 1024)
        val expectedSha = sha256Hex(payload)
        val updatesDir = tempFolder.newFolder("updates_cached")

        val networkHits = AtomicInteger(0)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/cached/BayChat.apk") { exchange ->
            networkHits.incrementAndGet()
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()

        try {
            val release = AppReleaseInfo(
                versionCode = 11,
                versionName = "1.1.8",
                apkUrl = "http://127.0.0.1:${server.address.port}/cached/BayChat.apk",
                sha256 = expectedSha,
                sizeBytes = payload.size.toLong(),
            )

            // 预置目标版本完整 APK 以及两个旧版本垃圾文件
            val cachedApk = ResumableApkDownloader.targetApkFile(updatesDir, release).apply {
                writeBytes(payload)
            }
            val obsoleteOldApk = File(updatesDir, "BayChat-v1.1.0-deadbeef.apk").apply {
                writeText("obsolete apk")
            }
            val obsoleteOldPart = File(updatesDir, "BayChat-v1.1.2-11223344.apk.part").apply {
                writeText("obsolete part")
            }

            val downloader = ResumableApkDownloader(okHttpClient = createTestClient())
            val result = downloader.download(release = release, updatesDir = updatesDir)

            assertTrue(result is ResumableApkDownloader.DownloadResult.Success)
            val success = result as ResumableApkDownloader.DownloadResult.Success
            assertTrue("本地校验通过的安装包应直接秒命中缓存", success.fromCache)
            assertEquals("命中缓存时网络请求次数必须为 0", 0, networkHits.get())
            assertEquals(cachedApk.absolutePath, success.apkFile.absolutePath)
            assertFalse("旧版本 .apk 垃圾文件必须被自动清理", obsoleteOldApk.exists())
            assertFalse("旧版本 .part 垃圾文件必须被自动清理", obsoleteOldPart.exists())
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun testPausePreservesBreakpointAndResumesCleanly() = runBlocking {
        val payload = generatePayload(192 * 1024)
        val expectedSha = sha256Hex(payload)
        val updatesDir = tempFolder.newFolder("updates_pause")
        val pausedFlag = AtomicBoolean(false)
        val receivedRange = AtomicReference<String?>(null)

        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/pause/BayChat.apk") { exchange ->
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            if (rangeHeader != null) {
                receivedRange.set(rangeHeader)
                val start = rangeHeader.removePrefix("bytes=").substringBefore("-").toIntOrNull() ?: 0
                val rem = payload.size - start
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                exchange.sendResponseHeaders(206, rem.toLong())
                exchange.responseBody.use { out ->
                    out.write(payload, start, rem)
                    out.flush()
                }
            } else {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                runCatching {
                    exchange.responseBody.use { out ->
                        var offset = 0
                        while (offset < payload.size) {
                            val chunk = minOf(16 * 1024, payload.size - offset)
                            out.write(payload, offset, chunk)
                            out.flush()
                            offset += chunk
                        }
                    }
                }
            }
        }
        server.start()

        try {
            val release = AppReleaseInfo(
                versionCode = 12,
                versionName = "1.1.9",
                apkUrl = "http://127.0.0.1:${server.address.port}/pause/BayChat.apk",
                sha256 = expectedSha,
                sizeBytes = payload.size.toLong(),
            )
            val partFile = ResumableApkDownloader.targetPartFile(updatesDir, release)
            val downloader = ResumableApkDownloader(
                okHttpClient = createTestClient(),
                progressIntervalMs = 0L,
            )

            // 收到至少 32KB 后触发暂停，验证保留断点
            val pauseResult = downloader.download(
                release = release,
                updatesDir = updatesDir,
                shouldPause = { pausedFlag.get() },
                onProgress = { downloaded, _, _, _ ->
                    if (downloaded >= 32 * 1024L) {
                        pausedFlag.set(true)
                    }
                },
            )
            assertTrue("触发暂停后应返回 Paused 状态", pauseResult is ResumableApkDownloader.DownloadResult.Paused)
            assertTrue("暂停后必须保留 .part 断点", partFile.exists())
            val savedLen = partFile.length()
            assertTrue("断点字节应大于 0 且小于总长度", savedLen in 1L until payload.size.toLong())

            // 恢复下载并完成校验
            val resumeResult = downloader.download(
                release = release,
                updatesDir = updatesDir,
                shouldPause = { false },
            )
            assertEquals("bytes=$savedLen-", receivedRange.get())
            assertTrue(resumeResult is ResumableApkDownloader.DownloadResult.Success)
            val success = resumeResult as ResumableApkDownloader.DownloadResult.Success
            assertEquals(expectedSha, ResumableApkDownloader.computeSha256(success.apkFile))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
