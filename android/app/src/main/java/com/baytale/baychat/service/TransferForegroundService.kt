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

// 前台保活传输服务：持有 CPU Partial WakeLock + 高性能 WifiLock，切到其他 App 或锁屏时防止系统冻结 P2P/WebSocket 传输
class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                acquireLocks()
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "湾聊 P2P 通道保持中"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在维持后台高速直连通道"
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, -1) ?: -1
                val notification = buildNotification(title, text, progress)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
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
        ensureChannel(this)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "baychat_transfer_channel"
        private const val NOTIFICATION_ID = 10086
        private const val ACTION_UPDATE = "com.baytale.baychat.action.UPDATE"
        private const val ACTION_STOP = "com.baytale.baychat.action.STOP"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "P2P 文件传输与房间保活",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "在切后台或锁屏时保持房间直连并显示文件传输进度"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }

        fun updateStatus(context: Context, title: String, text: String, progress: Int = -1) {
            runCatching {
                val intent = Intent(context, TransferForegroundService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_PROGRESS, progress)
                }
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, TransferForegroundService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            }
        }
    }
}
