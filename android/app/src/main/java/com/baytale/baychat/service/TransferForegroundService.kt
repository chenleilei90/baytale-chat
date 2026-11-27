package com.baytale.baychat.service

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.baytale.baychat.BayChatApp
import com.baytale.baychat.MainActivity
import com.baytale.baychat.R
import com.baytale.baychat.core.storage.AppPrefsStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 后台常驻远程消息与文件传输前台服务：
 * - Android 14/15+ 使用 remoteMessaging 前台类型，规避 dataSync 单日 6 小时系统配额强杀；
 * - 使用 START_STICKY 与看门狗闹钟，在系统回收或划掉卡片后自动静默重建连接；
 * - 仅在进行文件传输时按需持有 WakeLock + WifiLock，待机时零持锁避免国产 ROM 误判异常耗电清理。
 */
class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统异常重启（intent == null）或看门狗唤醒（ACTION_REVIVE）：检查是否有未退出的房间并静默恢复
        if (intent == null || intent.action == ACTION_REVIVE) {
            val store = AppPrefsStore(applicationContext)
            val saved = store.loadLastActiveRoom()
            if (store.hasAcceptedPrivacy() && saved != null && saved.code.isNotBlank()) {
                isServiceRunning.set(true)
                activeInstance = this
                lastHoldLocks.set(false)
                releaseLocks()
                val notification = buildNotification(
                    title = "湾聊房间 #${saved.code}",
                    text = "后台消息通道已连接",
                    progress = -1,
                )
                if (!startForegroundCompat(notification)) {
                    stopForegroundSafely()
                    return START_NOT_STICKY
                }
                scheduleWatchdog(applicationContext)
                (applicationContext as? BayChatApp)?.sharedViewModel?.ensureActiveRoomConnectedFromService()
                return START_STICKY
            }
            cancelWatchdog(applicationContext)
            stopForegroundSafely()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                cancelWatchdog(applicationContext)
                stopForegroundSafely()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                isServiceRunning.set(true)
                activeInstance = this
                val holdLocks = intent.getBooleanExtra(EXTRA_HOLD_LOCKS, false)
                lastHoldLocks.set(holdLocks)
                if (holdLocks) {
                    acquireLocks()
                } else {
                    releaseLocks()
                }
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "湾聊 P2P 通道连接中"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "正在维持后台消息与直连通道"
                val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                val notification = buildNotification(title, text, progress)
                if (!startForegroundCompat(notification)) {
                    stopForegroundSafely()
                    return START_NOT_STICKY
                }
                scheduleWatchdog(applicationContext)
                return START_STICKY
            }

            else -> {
                cancelWatchdog(applicationContext)
                stopForegroundSafely()
                return START_NOT_STICKY
            }
        }
    }

    // 兼容 Android 14/15 remoteMessaging 与旧版 dataSync 前台服务启动类型
    private fun startForegroundCompat(notification: Notification): Boolean {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                }
                else -> {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            true
        }.getOrDefault(false)
    }

    // 最近任务划掉卡片时若尚未退出房间，在 2 秒后触发看门狗重新拉起常驻连接
    override fun onTaskRemoved(rootIntent: Intent?) {
        val store = AppPrefsStore(applicationContext)
        val saved = store.loadLastActiveRoom()
        if (store.hasAcceptedPrivacy() && saved != null && saved.code.isNotBlank()) {
            scheduleWatchdog(applicationContext, delayMs = 2_000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    // 兜底处理系统触发的配额超时回调（兼容旧版系统降级场景）
    override fun onTimeout(startId: Int, fgsType: Int) {
        releaseLocks()
        isServiceRunning.set(false)
        if (activeInstance === this) {
            activeInstance = null
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        val store = AppPrefsStore(applicationContext)
        val saved = store.loadLastActiveRoom()
        if (store.hasAcceptedPrivacy() && saved != null && saved.code.isNotBlank()) {
            scheduleWatchdog(applicationContext, delayMs = 2_000L)
        } else {
            cancelWatchdog(applicationContext)
        }
        stopSelf()
    }

    override fun onDestroy() {
        isServiceRunning.set(false)
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseLocks()
        super.onDestroy()
    }

    private fun stopForegroundSafely() {
        isServiceRunning.set(false)
        lastHoldLocks.set(false)
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // 仅在大文件收发等重负载传输期间申请锁（单次最长 30 分钟，随进度刷新续期）
    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayChat::TransferWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30L * 60L * 1000L)
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
                wifiLock = wm.createWifiLock(mode, "BayChat::TransferWifiLock").apply {
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

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        return createNotification(this, title, text, progress)
    }

    companion object {
        const val CHANNEL_ID = "baychat_transfer_channel_v3"
        private const val LEGACY_CHANNEL_ID_V1 = "baychat_transfer_channel"
        private const val LEGACY_CHANNEL_ID_V2 = "baychat_transfer_channel_v2"
        const val ACTION_RESTORE_ACTIVE_ROOM = IncomingNotificationHelper.ACTION_RESTORE_ACTIVE_ROOM
        const val ACTION_RESIDENT_HEARTBEAT = "com.baytale.baychat.action.RESIDENT_HEARTBEAT"
        private const val NOTIFICATION_ID = 10086
        private const val WATCHDOG_REQUEST_CODE = 10088
        private const val WATCHDOG_INTERVAL_MS = 3L * 60L * 1000L
        private const val ACTION_UPDATE = "com.baytale.baychat.action.UPDATE"
        private const val ACTION_REVIVE = "com.baytale.baychat.action.REVIVE"
        private const val ACTION_STOP = "com.baytale.baychat.action.STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_HOLD_LOCKS = "extra_hold_locks"

        private val isServiceRunning = AtomicBoolean(false)
        private val lastHoldLocks = AtomicBoolean(false)

        @Volatile
        private var activeInstance: TransferForegroundService? = null

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 清理旧版通知渠道，统一升级为静音 LOW 优先级，兼顾无声不打扰与国产 ROM 后台权重
            runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_V1) }
            runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_V2) }
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "P2P 文件传输与消息连接",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "在后台显示消息连接状态与文件传输进度"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun createNotification(context: Context, title: String, text: String, progress: Int): Notification {
            val appCtx = context.applicationContext
            ensureChannel(appCtx)
            val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                action = ACTION_RESTORE_ACTIVE_ROOM
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                appCtx,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val builder = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_baychat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (progress in 0..100) {
                builder.setProgress(100, progress, false)
            }
            return builder.build()
        }

        fun updateStatus(
            context: Context,
            title: String,
            text: String,
            progress: Int = -1,
            allowStartService: Boolean = true,
            holdLocks: Boolean = false,
        ) {
            val appCtx = context.applicationContext
            // 服务已在运行时直接刷新通知与锁状态，避免大文件传输触发高频 startForegroundService IPC
            if (isServiceRunning.get()) {
                val inst = activeInstance
                if (inst != null && lastHoldLocks.getAndSet(holdLocks) != holdLocks) {
                    if (holdLocks) inst.acquireLocks() else inst.releaseLocks()
                }
                runCatching {
                    val nm = appCtx.getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, createNotification(appCtx, title, text, progress))
                    return
                }
            }
            if (!allowStartService) return
            runCatching {
                val intent = Intent(appCtx, TransferForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_HOLD_LOCKS, holdLocks)
                }
                appCtx.startForegroundService(intent)
            }
        }

        // 由看门狗或开机广播触发，在服务未运行时静默拉起前台常驻服务
        fun reviveIfNeeded(context: Context) {
            if (isServiceRunning.get()) return
            val appCtx = context.applicationContext
            val store = AppPrefsStore(appCtx)
            val saved = store.loadLastActiveRoom() ?: return
            if (!store.hasAcceptedPrivacy() || saved.code.isBlank()) return
            runCatching {
                val intent = Intent(appCtx, TransferForegroundService::class.java).apply {
                    action = ACTION_REVIVE
                }
                appCtx.startForegroundService(intent)
            }
        }

        private fun buildWatchdogPendingIntent(context: Context): PendingIntent {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, RoomResidentReceiver::class.java).apply {
                action = ACTION_RESIDENT_HEARTBEAT
            }
            return PendingIntent.getBroadcast(
                appCtx,
                WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        // 使用 Doze 可唤醒的 AlarmManager 维持 3 分钟轻量心跳，防止息屏断连或系统误杀
        fun scheduleWatchdog(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = buildWatchdogPendingIntent(appCtx)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_500L)
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancelWatchdog(context: Context) {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching {
                am.cancel(buildWatchdogPendingIntent(appCtx))
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            cancelWatchdog(appCtx)
            isServiceRunning.set(false)
            lastHoldLocks.set(false)
            activeInstance = null
            runCatching {
                appCtx.stopService(Intent(appCtx, TransferForegroundService::class.java))
            }
        }
    }
}
