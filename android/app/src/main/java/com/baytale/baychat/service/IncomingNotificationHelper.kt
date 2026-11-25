package com.baytale.baychat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.baytale.baychat.MainActivity
import com.baytale.baychat.R
import com.baytale.baychat.core.util.FormatUtils

// 后台高优先级来件与消息提醒通知管理器（渠道：baychat_incoming）
object IncomingNotificationHelper {
    const val CHANNEL_ID = "baychat_incoming"
    private const val NOTIFICATION_ID_MESSAGES = 10110
    private const val OFFER_ID_BASE = 10200

    fun ensureChannel(context: Context) {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "新消息与文件提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "切到后台时提醒新收到的文字消息与待确认文件"
                setShowBadge(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun showIncomingOfferNotification(
        context: Context,
        roomCode: String,
        peerId: String,
        peerName: String,
        transferId: String,
        fileName: String,
        fileSize: Long,
    ) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
        val notifId = offerNotificationId(transferId)

        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            appCtx,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val acceptIntent = Intent(appCtx, IncomingActionReceiver::class.java).apply {
            action = IncomingActionReceiver.ACTION_ACCEPT_FILE
            putExtra(IncomingActionReceiver.EXTRA_PEER_ID, peerId)
            putExtra(IncomingActionReceiver.EXTRA_TRANSFER_ID, transferId)
            putExtra(IncomingActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val acceptPi = PendingIntent.getBroadcast(
            appCtx,
            notifId * 2 + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rejectIntent = Intent(appCtx, IncomingActionReceiver::class.java).apply {
            action = IncomingActionReceiver.ACTION_REJECT_FILE
            putExtra(IncomingActionReceiver.EXTRA_PEER_ID, peerId)
            putExtra(IncomingActionReceiver.EXTRA_TRANSFER_ID, transferId)
            putExtra(IncomingActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val rejectPi = PendingIntent.getBroadcast(
            appCtx,
            notifId * 2 + 2,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "$peerName 发来文件，点击接收"
        val body = "$fileName (${FormatUtils.formatBytes(fileSize)}) · 房间 #$roomCode"
        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(0, "接收", acceptPi)
            .addAction(0, "拒绝", rejectPi)
            .build()

        runCatching { nm.notify(notifId, notification) }
    }

    fun showBackgroundMessageNotification(
        context: Context,
        roomCode: String,
        unreadCount: Int,
        senderName: String,
        previewText: String,
    ) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return

        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            appCtx,
            NOTIFICATION_ID_MESSAGES,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (unreadCount > 1) {
            "湾聊 #$roomCode · $unreadCount 条新消息"
        } else {
            "$senderName · 湾聊 #$roomCode"
        }
        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(previewText)
            .setNumber(unreadCount.coerceAtLeast(1))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openPi)
            .build()

        runCatching { nm.notify(NOTIFICATION_ID_MESSAGES, notification) }
    }

    fun cancelOfferNotification(context: Context, transferId: String) {
        cancelById(context, offerNotificationId(transferId))
    }

    fun cancelById(context: Context, notificationId: Int) {
        runCatching {
            context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }
    }

    fun cancelAllIncomingNotifications(context: Context) {
        runCatching {
            val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTIFICATION_ID_MESSAGES)
            nm.activeNotifications.forEach { sbn ->
                if (sbn.id >= OFFER_ID_BASE && sbn.id < OFFER_ID_BASE + 10000) {
                    nm.cancel(sbn.id)
                }
            }
        }
    }

    private fun offerNotificationId(transferId: String): Int {
        return OFFER_ID_BASE + ((transferId.hashCode() and 0x7FFFFFFF) % 9000)
    }
}
