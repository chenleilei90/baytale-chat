package com.baytale.baychat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.baytale.baychat.MainActivity
import com.baytale.baychat.R
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.network.AppUpdateManager
import com.baytale.baychat.core.network.ResumableApkDownloader
import com.baytale.baychat.core.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// 应用自动更新状态机枚举
enum class OtaUpdateStatus {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    READY_TO_INSTALL,
    ERROR,
}

// 全局 OTA 更新实时状态快照（支持 Activity 重建或切后台回归时零延迟恢复进度）
data class OtaUpdateState(
    val status: OtaUpdateStatus = OtaUpdateStatus.IDLE,
    val release: AppReleaseInfo? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val apkFilePath: String? = null,
    val isSilentBackground: Boolean = false,
    val isForceUpdate: Boolean = false,
    val attempt: Int = 0,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val hasPartialBreakpoint: Boolean
        get() = downloadedBytes > 0L && status != OtaUpdateStatus.READY_TO_INSTALL
}

// OTA 一次性 UI 通知事件（权限引导、安装器拉起、轻提示）
sealed class OtaUiEvent {
    data class Toast(val message: String) : OtaUiEvent()
    data object NeedInstallPermission : OtaUiEvent()
    data object InstallerLaunched : OtaUiEvent()
}

// 进程级单例 OTA 状态仓库：解耦后台前台服务与 UI 生命周期，保证切后台/重建 Activity 进度零丢失
object AppUpdateCenter {
    private val _state = MutableStateFlow(OtaUpdateState())
    val state: StateFlow<OtaUpdateState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OtaUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<OtaUiEvent> = _events.asSharedFlow()

    fun updateState(transform: (OtaUpdateState) -> OtaUpdateState) {
        _state.update(transform)
    }

    fun emitEvent(event: OtaUiEvent) {
        _events.tryEmit(event)
    }
}

// 应用后台自动更新前台服务：
// 1. 持有 PARTIAL_WAKE_LOCK（30 分钟超时保护）+ 高性能 WifiLock 防止锁屏或切后台断网
// 2. 通知栏实时显示下载百分比、已下/总大小、实时网速以及「暂停 / 继续」快捷控制按钮
// 3. 下载完成并全文件 SHA-256 校验通过后释放锁，发出高优先级可点击完成通知；主动更新时立刻拉起系统安装器
class AppUpdateService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pauseRequested = AtomicBoolean(false)
    private val silentBackgroundFlag = AtomicBoolean(false)
    private val runGeneration = AtomicLong(0L)
    private val latestStartId = AtomicInteger(0)
    // 串行化下载任务生命周期，严禁新旧协程并发写入同一个 .part 文件
    private val downloadMutex = Mutex()

    @Volatile
    private var downloadJob: Job? = null

    @Volatile
    private var activeVersionCode: Int = 0

    @Volatile
    private var lastNotificationUpdateAt: Long = 0L

    @Volatile
    private var lastNotifiedPercent: Int = -1

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        latestStartId.set(startId)
        when (intent?.action) {
            ACTION_PAUSE_DOWNLOAD -> {
                handlePauseRequest(startId)
                return START_NOT_STICKY
            }

            ACTION_CANCEL_DOWNLOAD -> {
                handleCancelRequest(startId)
                return START_NOT_STICKY
            }

            ACTION_START_DOWNLOAD, ACTION_RESUME_DOWNLOAD -> {
                val release = extractRelease(intent) ?: AppUpdateCenter.state.value.release
                if (release == null) {
                    stopForegroundAndSelf(startId)
                    return START_NOT_STICKY
                }
                val isResume = intent.action == ACTION_RESUME_DOWNLOAD
                val silent = if (isResume) false else intent.getBooleanExtra(EXTRA_SILENT, false)
                val forceUpdate = intent.getBooleanExtra(
                    EXTRA_FORCE_UPDATE,
                    AppUpdateCenter.state.value.isForceUpdate || release.forceUpdate,
                )
                handleStartOrResumeDownload(
                    release = release,
                    silent = silent,
                    forceUpdate = forceUpdate,
                    startId = startId,
                )
                return START_NOT_STICKY
            }

            else -> {
                stopForegroundAndSelf(startId)
                return START_NOT_STICKY
            }
        }
    }

    // Android 15 (API 35) dataSync 前台服务超时安全回调：优雅暂停并保留 .part 断点，绝不崩溃
    override fun onTimeout(startId: Int, fgsType: Int) {
        handlePauseRequest(startId)
    }

    override fun onDestroy() {
        pauseRequested.set(true)
        downloadJob?.cancel()
        downloadJob = null
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStartOrResumeDownload(
        release: AppReleaseInfo,
        silent: Boolean,
        forceUpdate: Boolean,
        startId: Int,
    ) {
        // 若当前已经在下载同一目标版本且未处于暂停中：不重启任务；若由静默切换为主动更新，同步升级标记
        val existingJob = downloadJob
        if (existingJob?.isActive == true && activeVersionCode == release.versionCode && !pauseRequested.get()) {
            if (!silent && silentBackgroundFlag.get()) {
                silentBackgroundFlag.set(false)
                AppUpdateCenter.updateState {
                    it.copy(isSilentBackground = false, isForceUpdate = it.isForceUpdate || forceUpdate)
                }
            }
            val snapshot = AppUpdateCenter.state.value
            val currentNotification = buildProgressNotification(
                release = release,
                downloadedBytes = snapshot.downloadedBytes,
                totalBytes = snapshot.totalBytes.coerceAtLeast(release.sizeBytes),
                speedBps = snapshot.speedBytesPerSec,
                verifying = snapshot.status == OtaUpdateStatus.VERIFYING,
            )
            startForegroundSafely(currentNotification)
            return
        }

        // 递增世代号并先立即拉起前台通知，严格满足 Android 12-15 startForegroundService 5 秒内约束
        val myGeneration = runGeneration.incrementAndGet()
        pauseRequested.set(false)
        silentBackgroundFlag.set(silent)
        activeVersionCode = release.versionCode
        lastNotificationUpdateAt = 0L
        lastNotifiedPercent = -1

        val currentState = AppUpdateCenter.state.value
        val initDownloaded = if (currentState.release?.versionCode == release.versionCode) {
            currentState.downloadedBytes
        } else {
            ResumableApkDownloader.targetPartFile(AppUpdateManager.updatesDir(this), release)
                .takeIf { it.exists() && it.isFile }?.length() ?: 0L
        }
        val initTotal = release.sizeBytes.coerceAtLeast(currentState.totalBytes)

        AppUpdateCenter.updateState {
            it.copy(
                status = OtaUpdateStatus.DOWNLOADING,
                release = release,
                downloadedBytes = initDownloaded,
                totalBytes = initTotal,
                speedBytesPerSec = 0L,
                errorMessage = null,
                apkFilePath = null,
                isSilentBackground = silent,
                isForceUpdate = forceUpdate,
                attempt = 1,
            )
        }

        val initialNotification = buildProgressNotification(
            release = release,
            downloadedBytes = initDownloaded,
            totalBytes = initTotal,
            speedBps = 0L,
            verifying = false,
        )
        if (!startForegroundSafely(initialNotification)) {
            // 极端情况下系统拒绝后台前台服务拉起时，优雅退回 AVAILABLE 状态不崩溃
            AppUpdateCenter.updateState {
                it.copy(
                    status = OtaUpdateStatus.AVAILABLE,
                    speedBytesPerSec = 0L,
                )
            }
            stopSelfResult(startId)
            return
        }

        // 取消之前可能残留的就绪通知，避免旧状态干扰
        notificationManager()?.cancel(NOTIFICATION_ID_READY)
        acquireLocks()

        val previousJob = downloadJob
        val updateManager = AppUpdateManager(applicationContext, otaHttpClient)

        downloadJob = serviceScope.launch {
            // 等待上一个任务彻底释放文件句柄后再拿锁进入下载，杜绝并发写坏 .part 引发 SHA-256 失败二次重下
            if (previousJob != null && previousJob.isActive) {
                runCatching { previousJob.cancelAndJoin() }
            }
            downloadMutex.withLock {
                if (runGeneration.get() != myGeneration) return@withLock

                try {
                    val result = updateManager.downloadRelease(
                        release = release,
                        shouldPause = { pauseRequested.get() || runGeneration.get() != myGeneration },
                        onVerifying = {
                            if (runGeneration.get() == myGeneration) {
                                AppUpdateCenter.updateState {
                                    it.copy(
                                        status = OtaUpdateStatus.VERIFYING,
                                        speedBytesPerSec = 0L,
                                    )
                                }
                                notifySafely(
                                    NOTIFICATION_ID_PROGRESS,
                                    buildProgressNotification(
                                        release = release,
                                        downloadedBytes = AppUpdateCenter.state.value.downloadedBytes,
                                        totalBytes = AppUpdateCenter.state.value.totalBytes.coerceAtLeast(release.sizeBytes),
                                        speedBps = 0L,
                                        verifying = true,
                                    ),
                                )
                            }
                        },
                        onProgress = { downloaded, total, speedBps, attempt ->
                            if (runGeneration.get() == myGeneration) {
                                val resolvedTotal = total.takeIf { it > 0L } ?: release.sizeBytes
                                AppUpdateCenter.updateState {
                                    it.copy(
                                        status = OtaUpdateStatus.DOWNLOADING,
                                        release = release,
                                        downloadedBytes = downloaded,
                                        totalBytes = resolvedTotal,
                                        speedBytesPerSec = speedBps,
                                        attempt = attempt,
                                        errorMessage = null,
                                    )
                                }
                                maybeUpdateProgressNotification(release, downloaded, resolvedTotal, speedBps)
                            }
                        },
                    )

                    // 若期间已触发新的暂停/重启，旧协程立即静默退出，绝不覆盖新状态
                    if (runGeneration.get() != myGeneration) return@withLock

                    when (result) {
                        is ResumableApkDownloader.DownloadResult.Success -> {
                            val finalSize = result.apkFile.length()
                            AppUpdateCenter.updateState {
                                it.copy(
                                    status = OtaUpdateStatus.READY_TO_INSTALL,
                                    release = release,
                                    downloadedBytes = finalSize,
                                    totalBytes = finalSize,
                                    speedBytesPerSec = 0L,
                                    errorMessage = null,
                                    apkFilePath = result.apkFile.absolutePath,
                                )
                            }

                            // 停止前台锁与进度通知，发送高优先级可点击完成通知
                            releaseLocks()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            notificationManager()?.cancel(NOTIFICATION_ID_PROGRESS)
                            notifySafely(NOTIFICATION_ID_READY, buildReadyToInstallNotification(release, result.apkFile))

                            // 主动点击更新（非静默后台）时，校验通过立刻拉起系统包安装器
                            if (!silentBackgroundFlag.get()) {
                                val outcome = withContext(Dispatchers.Main) {
                                    AppUpdateManager.launchInstaller(applicationContext, result.apkFile)
                                }
                                when (outcome) {
                                    is AppUpdateManager.UpdateInstallOutcome.InstallerLaunched -> {
                                        AppUpdateCenter.emitEvent(OtaUiEvent.InstallerLaunched)
                                    }
                                    is AppUpdateManager.UpdateInstallOutcome.NeedInstallPermission -> {
                                        AppUpdateCenter.emitEvent(OtaUiEvent.NeedInstallPermission)
                                    }
                                    is AppUpdateManager.UpdateInstallOutcome.Error -> {
                                        AppUpdateCenter.emitEvent(OtaUiEvent.Toast(outcome.message))
                                    }
                                }
                            }
                            if (runGeneration.get() == myGeneration) {
                                stopSelfResult(startId)
                            }
                        }

                        is ResumableApkDownloader.DownloadResult.Paused -> {
                            onDownloadPaused(
                                release = release,
                                downloadedBytes = result.downloadedBytes,
                                totalBytes = result.totalBytes.coerceAtLeast(release.sizeBytes),
                                expectedGen = myGeneration,
                                stopStartId = startId,
                            )
                        }

                        is ResumableApkDownloader.DownloadResult.Error -> {
                            releaseLocks()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            notificationManager()?.cancel(NOTIFICATION_ID_PROGRESS)

                            val resolvedTotal = result.totalBytes.coerceAtLeast(release.sizeBytes)
                            AppUpdateCenter.updateState {
                                it.copy(
                                    status = OtaUpdateStatus.ERROR,
                                    release = release,
                                    downloadedBytes = result.downloadedBytes,
                                    totalBytes = resolvedTotal,
                                    speedBytesPerSec = 0L,
                                    errorMessage = result.message,
                                    apkFilePath = null,
                                )
                            }

                            // 静默后台下载出错时不打扰用户通知栏；主动更新则发出可一键续传的提示通知
                            if (!silentBackgroundFlag.get()) {
                                notifySafely(
                                    NOTIFICATION_ID_PROGRESS,
                                    buildErrorNotification(release, result.message),
                                )
                            }
                            if (runGeneration.get() == myGeneration) {
                                stopSelfResult(startId)
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    // 协程取消时绝不删除 .part 断点；暂停状态由 handlePauseRequest 统一收敛，避免重复写通知
                } catch (e: Exception) {
                    if (runGeneration.get() != myGeneration) return@withLock
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val msg = e.message ?: "后台更新服务异常中断"
                    AppUpdateCenter.updateState {
                        it.copy(
                            status = OtaUpdateStatus.ERROR,
                            speedBytesPerSec = 0L,
                            errorMessage = msg,
                        )
                    }
                    if (runGeneration.get() == myGeneration) {
                        stopSelfResult(startId)
                    }
                }
            }
        }
    }

    private fun handlePauseRequest(startId: Int) {
        val pauseGen = runGeneration.incrementAndGet()
        pauseRequested.set(true)
        val activeJob = downloadJob
        // 先触发取消并立即将 UI 与通知切换为暂停态（不阻塞等待旧流退出）
        activeJob?.cancel()
        val immediateSnap = AppUpdateCenter.state.value
        val immediateRelease = immediateSnap.release
        if (immediateRelease != null) {
            AppUpdateCenter.updateState {
                it.copy(
                    status = OtaUpdateStatus.PAUSED,
                    release = immediateRelease,
                    speedBytesPerSec = 0L,
                    errorMessage = null,
                )
            }
            notifySafely(
                NOTIFICATION_ID_PROGRESS,
                buildPausedNotification(
                    release = immediateRelease,
                    downloadedBytes = immediateSnap.downloadedBytes,
                    totalBytes = immediateSnap.totalBytes.coerceAtLeast(immediateRelease.sizeBytes),
                ),
            )
        }
        serviceScope.launch {
            if (activeJob != null) {
                runCatching { activeJob.join() }
            }
            if (runGeneration.get() != pauseGen) return@launch

            val snapshot = AppUpdateCenter.state.value
            val release = snapshot.release
            if (release != null) {
                val diskPartLen = ResumableApkDownloader.targetPartFile(
                    AppUpdateManager.updatesDir(this@AppUpdateService),
                    release,
                ).takeIf { it.exists() && it.isFile }?.length() ?: 0L
                val finalDownloaded = maxOf(snapshot.downloadedBytes, diskPartLen)
                onDownloadPaused(
                    release = release,
                    downloadedBytes = finalDownloaded,
                    totalBytes = snapshot.totalBytes.coerceAtLeast(release.sizeBytes),
                    expectedGen = pauseGen,
                    stopStartId = startId,
                )
            } else {
                stopForegroundAndSelf(startId, expectedGen = pauseGen)
            }
        }
    }

    private fun handleCancelRequest(startId: Int) {
        val cancelGen = runGeneration.incrementAndGet()
        pauseRequested.set(true)
        val activeJob = downloadJob
        activeJob?.cancel()
        notificationManager()?.cancel(NOTIFICATION_ID_PROGRESS)
        serviceScope.launch {
            if (activeJob != null) {
                runCatching { activeJob.join() }
            }
            if (runGeneration.get() == cancelGen) {
                stopForegroundAndSelf(startId, expectedGen = cancelGen)
            }
        }
    }

    private fun onDownloadPaused(
        release: AppReleaseInfo,
        downloadedBytes: Long,
        totalBytes: Long,
        expectedGen: Long? = null,
        stopStartId: Int? = null,
    ) {
        if (expectedGen != null && runGeneration.get() != expectedGen) return
        releaseLocks()
        // 使用 STOP_FOREGROUND_DETACH 脱离前台锁定而非删除通知，避免抹掉随后刷新的「继续」按钮通知
        runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
        AppUpdateCenter.updateState {
            it.copy(
                status = OtaUpdateStatus.PAUSED,
                release = release,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                speedBytesPerSec = 0L,
                errorMessage = null,
            )
        }
        // 停止前台耗电锁后保留一条带「继续」按钮的可恢复通知，用户可随时在通知栏一键续传
        notifySafely(
            NOTIFICATION_ID_PROGRESS,
            buildPausedNotification(release, downloadedBytes, totalBytes),
        )
        if (stopStartId != null) {
            if (expectedGen == null || runGeneration.get() == expectedGen) {
                stopSelfResult(stopStartId)
            }
        } else {
            stopSelf()
        }
    }

    private fun stopForegroundAndSelf(startId: Int? = null, expectedGen: Long? = null) {
        if (expectedGen != null && runGeneration.get() != expectedGen) return
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (startId != null) {
            stopSelfResult(startId)
        } else {
            stopSelf()
        }
    }

    private fun startForegroundSafely(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID_PROGRESS, notification)
            }
            true
        }.getOrDefault(false)
    }

    private fun maybeUpdateProgressNotification(
        release: AppReleaseInfo,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
    ) {
        val now = System.currentTimeMillis()
        val pct = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
        val shouldNotify = (now - lastNotificationUpdateAt >= 500L) ||
            (pct >= 0 && (pct - lastNotifiedPercent >= 2 || pct == 100))
        if (!shouldNotify) return
        lastNotificationUpdateAt = now
        lastNotifiedPercent = pct
        notifySafely(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(release, downloadedBytes, totalBytes, speedBps, verifying = false),
        )
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayChat::OtaUpdateWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // 严格 30 分钟超时保护，防止异常场景耗电
                wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wm != null) {
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(mode, "BayChat::OtaUpdateWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun buildProgressNotification(
        release: AppReleaseInfo,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBps: Long,
        verifying: Boolean,
    ): Notification {
        val openPi = buildOpenAppPendingIntent()
        val pct = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0

        val title = if (verifying) {
            "湾聊 v${release.versionName} 校验中..."
        } else if (speedBps > 0L) {
            "湾聊 v${release.versionName} 更新中 · ${pct}% · ${FormatUtils.formatBytes(speedBps)}/s"
        } else {
            "湾聊 v${release.versionName} 更新中 · ${pct}%"
        }

        val text = when {
            verifying -> "正在校验安装包 SHA-256 完整性，请稍候"
            totalBytes > 0L -> "已下载 ${FormatUtils.formatBytes(downloadedBytes)} / ${FormatUtils.formatBytes(totalBytes)}"
            downloadedBytes > 0L -> "已下载 ${FormatUtils.formatBytes(downloadedBytes)}"
            else -> "正在连接更新节点..."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (verifying) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, pct, totalBytes <= 0L)
            // 通知栏快捷「暂停」按钮
            val pauseIntent = Intent(this, AppUpdateService::class.java).apply {
                action = ACTION_PAUSE_DOWNLOAD
            }
            val pausePi = PendingIntent.getService(
                this,
                REQ_PAUSE,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_media_pause, "暂停", pausePi)
        }
        return builder.build()
    }

    private fun buildPausedNotification(
        release: AppReleaseInfo,
        downloadedBytes: Long,
        totalBytes: Long,
    ): Notification {
        val pct = if (totalBytes > 0L) ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
        val title = "湾聊 v${release.versionName} 更新已暂停 · ${pct}%"
        val text = if (totalBytes > 0L) {
            "已保留断点 ${FormatUtils.formatBytes(downloadedBytes)} / ${FormatUtils.formatBytes(totalBytes)}，点击继续"
        } else {
            "已保留本地断点，点击继续断点下载"
        }

        val resumePi = buildResumePendingIntent(release)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildOpenAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, pct, false)
            .addAction(android.R.drawable.ic_media_play, "继续", resumePi)
            .build()
    }

    private fun buildErrorNotification(
        release: AppReleaseInfo,
        errorMessage: String,
    ): Notification {
        val resumePi = buildResumePendingIntent(release)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle("湾聊 v${release.versionName} 更新中断")
            .setContentText(errorMessage)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(buildOpenAppPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(android.R.drawable.ic_media_play, "继续", resumePi)
            .build()
    }

    // 高优先级可点击完成通知：已授予安装权限时直接通过 FileProvider 拉起系统包安装器
    private fun buildReadyToInstallNotification(
        release: AppReleaseInfo,
        apkFile: File,
    ): Notification {
        val tapIntent = if (AppUpdateManager.canInstallPackages(this)) {
            runCatching { AppUpdateManager.createInstallIntent(this, apkFile) }
                .getOrElse { buildOpenInstallActivityIntent() }
        } else {
            buildOpenInstallActivityIntent()
        }
        val installPi = PendingIntent.getActivity(
            this,
            REQ_INSTALL,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notesText = release.releaseNotes.trim().ifBlank { "已完成 SHA-256 完整性校验，点击立即覆盖升级" }
        return NotificationCompat.Builder(this, READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle("湾聊 v${release.versionName} 已下载就绪，点击一键安装")
            .setContentText(notesText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notesText))
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(installPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_OTA_DIALOG
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildOpenInstallActivityIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_OTA_INSTALL
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun buildResumePendingIntent(release: AppReleaseInfo): PendingIntent {
        // 统一经 MainActivity 恢复续传，规避 Android 12-15 后台通知直接 getForegroundService 被拦截的问题
        val resumeIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_RESUME_OTA_DOWNLOAD
            putExtra(EXTRA_RELEASE_JSON, otaJson.encodeToString(AppReleaseInfo.serializer(), release))
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQ_RESUME,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun extractRelease(intent: Intent): AppReleaseInfo? = extractReleaseFromIntent(intent)

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun notifySafely(notificationId: Int, notification: Notification) {
        runCatching {
            notificationManager()?.notify(notificationId, notification)
        }
    }

    companion object {
        // 独立 OTA 更新通知渠道（名称：应用自动更新）
        const val CHANNEL_ID = "baychat_ota_update_channel"
        const val READY_CHANNEL_ID = "baychat_ota_update_ready_channel"
        const val ACTION_OPEN_OTA_INSTALL = "com.baytale.baychat.action.OPEN_OTA_INSTALL"
        const val ACTION_OPEN_OTA_DIALOG = "com.baytale.baychat.ACTION_OPEN_OTA_DIALOG"
        const val ACTION_RESUME_OTA_DOWNLOAD = "com.baytale.baychat.action.RESUME_OTA_DOWNLOAD"

        private const val ACTION_START_DOWNLOAD = "com.baytale.baychat.action.OTA_START"
        private const val ACTION_RESUME_DOWNLOAD = "com.baytale.baychat.action.OTA_RESUME"
        private const val ACTION_PAUSE_DOWNLOAD = "com.baytale.baychat.action.OTA_PAUSE"
        private const val ACTION_CANCEL_DOWNLOAD = "com.baytale.baychat.action.OTA_CANCEL"

        private const val EXTRA_RELEASE_JSON = "extra_release_json"
        private const val EXTRA_SILENT = "extra_silent"
        private const val EXTRA_FORCE_UPDATE = "extra_force_update"

        private const val NOTIFICATION_ID_PROGRESS = 10091
        private const val NOTIFICATION_ID_READY = 10092

        private const val REQ_OPEN_APP = 2001
        private const val REQ_PAUSE = 2002
        private const val REQ_RESUME = 2003
        private const val REQ_INSTALL = 2004

        // WakeLock 30 分钟超时保护
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L

        private val otaJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun extractReleaseFromIntent(intent: Intent?): AppReleaseInfo? {
            val raw = intent?.getStringExtra(EXTRA_RELEASE_JSON) ?: return null
            return runCatching {
                otaJson.decodeFromString(AppReleaseInfo.serializer(), raw)
            }.getOrNull()
        }

        // 专用于大体积 APK 断点续传的 OkHttpClient（支持 GitHub Release 跨域名 302 重定向）
        private val otaHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun ensureChannel(context: Context) {
            val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val progressChannel = NotificationChannel(
                    CHANNEL_ID,
                    "应用自动更新",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "在后台静默或前台下载湾聊新版本并显示实时网速与断点进度"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(progressChannel)
            }
            if (nm.getNotificationChannel(READY_CHANNEL_ID) == null) {
                val readyChannel = NotificationChannel(
                    READY_CHANNEL_ID,
                    "应用更新就绪提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "新版本下载并完成 SHA-256 校验后提醒一键安装"
                    setShowBadge(true)
                }
                nm.createNotificationChannel(readyChannel)
            }
        }

        // 启动或恢复后台断点续传下载服务
        fun startDownload(
            context: Context,
            release: AppReleaseInfo,
            silent: Boolean = false,
            forceUpdate: Boolean = false,
        ): Boolean {
            ensureChannel(context)
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, AppUpdateService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_RELEASE_JSON, otaJson.encodeToString(AppReleaseInfo.serializer(), release))
                putExtra(EXTRA_SILENT, silent)
                putExtra(EXTRA_FORCE_UPDATE, forceUpdate)
            }
            return runCatching {
                appCtx.startForegroundService(intent)
                true
            }.getOrDefault(false)
        }

        // 暂停当前下载并保留 .part 断点文件
        fun pauseDownload(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.startService(
                    Intent(appCtx, AppUpdateService::class.java).apply {
                        action = ACTION_PAUSE_DOWNLOAD
                    }
                )
            }
        }

        // 取消后台下载服务并清理通知
        fun cancelDownload(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.startService(
                    Intent(appCtx, AppUpdateService::class.java).apply {
                        action = ACTION_CANCEL_DOWNLOAD
                    }
                )
            }
        }
    }
}
