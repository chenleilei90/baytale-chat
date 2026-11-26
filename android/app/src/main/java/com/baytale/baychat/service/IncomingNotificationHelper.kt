package com.baytale.baychat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.baytale.baychat.MainActivity
import com.baytale.baychat.R
import com.baytale.baychat.core.util.FormatUtils

// 后台高优先级来件与消息提醒通知管理器（渠道：baychat_incoming）
object IncomingNotificationHelper {
    const val CHANNEL_ID = "baychat_incoming"
    const val ACTION_RESTORE_ACTIVE_ROOM = "com.baytale.baychat.ACTION_RESTORE_ACTIVE_ROOM"
    const val EXTRA_ROOM_CODE = "extra_room_code"
    private const val NOTIFICATION_ID_MESSAGES = 10110
    private const val OFFER_ID_BASE = 10200
    private const val RESULT_ID_BASE = 10900
    private const val MAX_MESSAGING_STYLE_LINES = 6

    private data class MessageLine(
        val senderName: String,
        val text: String,
        val timestamp: Long,
    )

    private val lock = Any()
    private val recentLines = mutableListOf<MessageLine>()
    private var lastRoomCode: String = ""

    fun ensureChannel(context: Context) {
        val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "新消息与文件提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "切到后台或挂起时提醒新消息、@提及与待确认文件"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
            action = ACTION_RESTORE_ACTIVE_ROOM
            putExtra(EXTRA_ROOM_CODE, roomCode)
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
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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
        mentionedMe: Boolean = false,
    ) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return

        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            action = ACTION_RESTORE_ACTIVE_ROOM
            putExtra(EXTRA_ROOM_CODE, roomCode)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            appCtx,
            NOTIFICATION_ID_MESSAGES,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snapshotLines: List<MessageLine>
        synchronized(lock) {
            if (lastRoomCode.isNotBlank() && !lastRoomCode.equals(roomCode, ignoreCase = true)) {
                recentLines.clear()
            }
            lastRoomCode = roomCode
            val safeSender = senderName.ifBlank { "伙伴" }
            val safeText = previewText.ifBlank { "发来一条消息" }
            recentLines.add(MessageLine(safeSender, safeText, System.currentTimeMillis()))
            while (recentLines.size > MAX_MESSAGING_STYLE_LINES) {
                recentLines.removeAt(0)
            }
            snapshotLines = recentLines.toList()
        }

        val selfPerson = Person.Builder().setName("我").build()
        val style = NotificationCompat.MessagingStyle(selfPerson)
            .setGroupConversation(true)
            .setConversationTitle(
                if (mentionedMe) "[有人@我] 湾聊 #$roomCode"
                else if (unreadCount > 1) "湾聊 #$roomCode ($unreadCount 条未读)"
                else "湾聊 #$roomCode"
            )
        snapshotLines.forEach { line ->
            val person = Person.Builder().setName(line.senderName).build()
            style.addMessage(line.text, line.timestamp, person)
        }

        val fallbackTitle = if (mentionedMe) {
            "[有人@我] $senderName · 湾聊 #$roomCode"
        } else if (unreadCount > 1) {
            "湾聊 #$roomCode · $unreadCount 条新消息"
        } else {
            "$senderName · 湾聊 #$roomCode"
        }

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle(fallbackTitle)
            .setContentText(previewText)
            .setStyle(style)
            .setNumber(unreadCount.coerceAtLeast(1))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openPi)
            .build()

        runCatching { nm.notify(NOTIFICATION_ID_MESSAGES, notification) }
    }

    // 后台文件接收成功或快捷操作失败结果通知
    fun showFileTransferResultNotification(
        context: Context,
        roomCode: String,
        fileName: String,
        success: Boolean,
        detailText: String,
    ) {
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
        val notifId = RESULT_ID_BASE + (((roomCode + fileName).hashCode() and 0x7FFFFFFF) % 800)

        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            action = ACTION_RESTORE_ACTIVE_ROOM
            putExtra(EXTRA_ROOM_CODE, roomCode)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            appCtx,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (success) "文件已接收保存：$fileName" else "文件接收未成功"
        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_baychat)
            .setContentTitle(title)
            .setContentText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()

        runCatching { nm.notify(notifId, notification) }
    }

    fun cancelOfferNotification(context: Context, transferId: String) {
        cancelById(context, offerNotificationId(transferId))
    }

    fun cancelById(context: Context, notificationId: Int) {
        runCatching {
            context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }
    }

    // 仅清除普通聊天消息通知及堆叠队列，不误删仍待确认的文件 Offer 通知
    fun cancelMessageNotification(context: Context) {
        synchronized(lock) {
            recentLines.clear()
            lastRoomCode = ""
        }
        cancelById(context, NOTIFICATION_ID_MESSAGES)
    }

    fun cancelAllIncomingNotifications(context: Context) {
        synchronized(lock) {
            recentLines.clear()
            lastRoomCode = ""
        }
        runCatching {
            val nm = context.applicationContext.getSystemService(NotificationManager::class.java) ?: return
            nm.cancel(NOTIFICATION_ID_MESSAGES)
            nm.activeNotifications.forEach { sbn ->
                val isOffer = sbn.id >= OFFER_ID_BASE && sbn.id < OFFER_ID_BASE + 10000
                val isResult = sbn.id >= RESULT_ID_BASE && sbn.id < RESULT_ID_BASE + 1000
                if (isOffer || isResult) {
                    nm.cancel(sbn.id)
                }
            }
        }
    }

    private fun offerNotificationId(transferId: String): Int {
        return OFFER_ID_BASE + ((transferId.hashCode() and 0x7FFFFFFF) % 9000)
    }
}
