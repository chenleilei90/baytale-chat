package com.baytale.baychat.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.baytale.baychat.core.model.AppReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

// Android 平台级 OTA 更新管理器：负责持久化更新目录、未计费 Wi-Fi 检测、FileProvider 安装器拉起与权限引导
class AppUpdateManager(
    private val context: Context,
    okHttpClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val downloader = ResumableApkDownloader(okHttpClient = okHttpClient)

    sealed class UpdateInstallOutcome {
        data object InstallerLaunched : UpdateInstallOutcome()
        data object NeedInstallPermission : UpdateInstallOutcome()
        data class Error(val message: String) : UpdateInstallOutcome()
    }

    // 扫描本地是否已存在校验通过的安装包或可续传 .part 断点，同时清理过期旧版垃圾文件
    suspend fun inspectLocalRelease(release: AppReleaseInfo): ResumableApkDownloader.LocalUpdateInspection =
        withContext(Dispatchers.IO) {
            cleanupLegacyCacheUpdates(appContext)
            ResumableApkDownloader.inspectLocalState(updatesDir(appContext), release)
        }

    // 执行企业级断点续传下载（供 AppUpdateService 后台前台服务调用）
    suspend fun downloadRelease(
        release: AppReleaseInfo,
        shouldPause: () -> Boolean = { false },
        onVerifying: () -> Unit = {},
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, attempt: Int) -> Unit = { _, _, _, _ -> },
    ): ResumableApkDownloader.DownloadResult {
        cleanupLegacyCacheUpdates(appContext)
        return downloader.download(
            release = release,
            updatesDir = updatesDir(appContext),
            shouldPause = shouldPause,
            onVerifying = onVerifying,
            onProgress = onProgress,
        )
    }

    // 拉起系统安装器（如未授予「允许安装未知应用」则自动跳转系统授权页）
    fun launchInstaller(apkFile: File): UpdateInstallOutcome = launchInstaller(appContext, apkFile)

    companion object {
        private const val UPDATES_DIR_NAME = "updates"

        // 持久化更新目录位于 context.filesDir/updates/（绝不放易被系统回收的 cacheDir）
        // file_paths.xml 已声明 <files-path name="received_files" path="." />，与 FileProvider 100% 兼容
        fun updatesDir(context: Context): File =
            File(context.applicationContext.filesDir, UPDATES_DIR_NAME).apply { mkdirs() }

        // 顺手清理旧版 v1.1.3 遗留在 cacheDir/updates 的安装包缓存，释放存储空间
        fun cleanupLegacyCacheUpdates(context: Context) {
            runCatching {
                val legacyDir = File(context.applicationContext.cacheDir, UPDATES_DIR_NAME)
                if (legacyDir.exists() && legacyDir.isDirectory) {
                    legacyDir.listFiles()?.forEach { runCatching { it.delete() } }
                    legacyDir.delete()
                }
            }
        }

        // 严格检测当前是否处于未计费 Wi-Fi / 有线网络（移动网络与计费热点绝不静默下载偷跑流量）
        fun isUnmeteredWifi(context: Context): Boolean {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            return hasInternet && isNotMetered && isWifiOrEthernet
        }

        // 检查是否已授予「安装未知应用」权限（Android 8.0+）
        fun canInstallPackages(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        }

        // 构造通过 FileProvider 授权的系统包安装器 Intent（兼容通知栏 PendingIntent 与前台直接拉起）
        fun createInstallIntent(context: Context, apkFile: File): Intent {
            val appCtx = context.applicationContext
            val uri = FileProvider.getUriForFile(appCtx, "${appCtx.packageName}.fileprovider", apkFile)
            return Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // 构造跳转到「安装未知应用」系统设置页的 Intent
        fun createUnknownSourcesSettingsIntent(context: Context): Intent {
            val appCtx = context.applicationContext
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appCtx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // 安全拉起系统包安装器覆盖升级
        fun launchInstaller(context: Context, apkFile: File): UpdateInstallOutcome {
            val appCtx = context.applicationContext
            if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
                return UpdateInstallOutcome.Error("本地安装包不存在或已被移除，请重新下载")
            }

            if (!canInstallPackages(appCtx)) {
                runCatching {
                    appCtx.startActivity(createUnknownSourcesSettingsIntent(appCtx))
                }
                return UpdateInstallOutcome.NeedInstallPermission
            }

            return runCatching {
                appCtx.startActivity(createInstallIntent(appCtx, apkFile))
                UpdateInstallOutcome.InstallerLaunched
            }.getOrElse {
                UpdateInstallOutcome.Error("拉起系统安装器失败：${it.message}")
            }
        }
    }
}
