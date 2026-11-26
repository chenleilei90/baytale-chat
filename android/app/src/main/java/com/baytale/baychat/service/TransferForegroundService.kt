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

// 前台保活传输服务：持有 CPU Partial WakeLock + 高性能 WifiLock，切后台或锁屏时防止系统冻结 P2P/WebSocket 传输
class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // B11：系统异常重启传入 null intent 时不显示僵尸通知，直接安全退出
        if (intent == null) {
            stopForegroundSafely()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopForegroundSafely()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                isServiceRunning.set(true)
                acquireLocks()
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "湾聊 P2P 通道保持中"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "正在维持后台直连通道"
                val progress = intent.getIntExtra(EXTRA_PROGRESS, -1)
                val notification = buildNotification(title, text, progress)
                val started = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    true
                }.getOrDefault(false)
                if (!started) {
                    stopForegroundSafely()
                }
            }
            else -> {
                stopForegroundSafely()
            }
        }
        return START_NOT_STICKY
    }

    // B11：Android 15 (API 35) dataSync 配额超时安全回调：立即释放唤醒锁、发提示通知并优雅停止
    override fun onTimeout(startId: Int, fgsType: Int) {
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching {
            IncomingNotificationHelper.ensureChannel(this)
            val openIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_RESTORE_ACTIVE_ROOM
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                this,
                10087,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val nm = getSystemService(NotificationManager::class.java)
            val note = NotificationCompat.Builder(this, IncomingNotificationHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_baychat)
                .setContentTitle("湾聊消息与传输服务已暂停")
                .setContentText("系统限制单日后台直连时长，点击回到湾聊继续传输")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm?.notify(NOTIFICATION_ID_TIMEOUT, note)
        }
        stopSelf()
    }

    override fun onDestroy() {
        isServiceRunning.set(false)
        releaseLocks()
        super.onDestroy()
    }

    private fun stopForegroundSafely() {
        isServiceRunning.set(false)
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayChat::TransferWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // 最长 6 小时超时保护，退出房间时立即释放
                wakeLock?.acquire(6L * 60L * 60L * 1000L)
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
        const val CHANNEL_ID = "baychat_transfer_channel_v2"
        private const val LEGACY_CHANNEL_ID = "baychat_transfer_channel"
        const val ACTION_RESTORE_ACTIVE_ROOM = IncomingNotificationHelper.ACTION_RESTORE_ACTIVE_ROOM
        private const val NOTIFICATION_ID = 10086
        private const val NOTIFICATION_ID_TIMEOUT = 10087
        private const val ACTION_UPDATE = "com.baytale.baychat.action.UPDATE"
        private const val ACTION_STOP = "com.baytale.baychat.action.STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"
        private val isServiceRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // 清理旧版 LOW 渠道并切换至 MIN 静默通道：隐藏状态栏图标并折叠沉底
            runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "P2P 文件传输与消息提醒",
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = "在后台显示文件传输进度与消息连接状态"
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
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_MIN)

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
        ) {
            val appCtx = context.applicationContext
            // 服务已在运行时直接刷新通知栏，避免大文件传输产生高频 startForegroundService IPC 风暴
            if (isServiceRunning.get()) {
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
                }
                appCtx.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            isServiceRunning.set(false)
            runCatching {
                val appCtx = context.applicationContext
                appCtx.stopService(Intent(appCtx, TransferForegroundService::class.java))
            }
        }
    }
}
