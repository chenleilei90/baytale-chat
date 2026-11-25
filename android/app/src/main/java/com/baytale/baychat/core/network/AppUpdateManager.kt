package com.baytale.baychat.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// 应用内直连更新引擎：流式下载 APK、SHA-256 校验并拉起系统包安装器覆盖升级
class AppUpdateManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    sealed class UpdateInstallOutcome {
        data object InstallerLaunched : UpdateInstallOutcome()
        data object NeedInstallPermission : UpdateInstallOutcome()
        data class Error(val message: String) : UpdateInstallOutcome()
    }

    suspend fun downloadAndInstall(
        release: AppReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): UpdateInstallOutcome = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updateDir, "BayChat-v${release.versionName}.apk")
            val tmpFile = File(updateDir, "BayChat-v${release.versionName}.apk.tmp")

            // 若本地已下载且 SHA-256 校验通过，直接复用免重复下载
            val alreadyValid = apkFile.exists() &&
                release.sha256.isNotBlank() &&
                computeSha256(apkFile).equals(release.sha256, ignoreCase = true)

            if (!alreadyValid) {
                if (tmpFile.exists()) tmpFile.delete()
                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "BayChat-Android-Updater/1.1")
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext UpdateInstallOutcome.Error("安装包下载失败 (HTTP ${resp.code})")
                    }
                    val body = resp.body ?: return@withContext UpdateInstallOutcome.Error("服务器未返回安装包数据")
                    val total = body.contentLength().takeIf { it > 0L } ?: release.sizeBytes
                    val sha = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    var lastReport = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                sha.update(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 150L || (total > 0 && downloaded >= total)) {
                                    onProgress(downloaded, total)
                                    lastReport = now
                                }
                            }
                            output.flush()
                        }
                    }

                    if (release.sha256.isNotBlank()) {
                        val actualHash = FormatUtils.bytesToHex(sha.digest())
                        if (!actualHash.equals(release.sha256, ignoreCase = true)) {
                            tmpFile.delete()
                            return@withContext UpdateInstallOutcome.Error("安装包 SHA-256 完整性校验失败，请重试")
                        }
                    }

                    if (apkFile.exists()) apkFile.delete()
                    if (!tmpFile.renameTo(apkFile)) {
                        tmpFile.copyTo(apkFile, overwrite = true)
                        tmpFile.delete()
                    }
                }
            } else {
                onProgress(apkFile.length(), apkFile.length())
            }

            withContext(Dispatchers.Main) {
                launchInstaller(apkFile)
            }
        } catch (e: Exception) {
            UpdateInstallOutcome.Error(e.message ?: "更新下载中断")
        }
    }

    private fun launchInstaller(apkFile: File): UpdateInstallOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permIntent)
            }
            return UpdateInstallOutcome.NeedInstallPermission
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            UpdateInstallOutcome.InstallerLaunched
        }.getOrElse {
            UpdateInstallOutcome.Error("拉起系统安装器失败：${it.message}")
        }
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        return FormatUtils.bytesToHex(md.digest())
    }
}
