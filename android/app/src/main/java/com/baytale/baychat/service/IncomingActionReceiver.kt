package com.baytale.baychat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 处理后台高优先级来件通知上的「接收 / 拒绝」快捷操作按钮
class IncomingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID).orEmpty()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId > 0) {
            IncomingNotificationHelper.cancelById(context, notificationId)
        } else if (transferId.isNotBlank()) {
            IncomingNotificationHelper.cancelOfferNotification(context, transferId)
        }
        if (peerId.isBlank() || transferId.isBlank()) return

        when (intent.action) {
            ACTION_ACCEPT_FILE -> IncomingActionBus.emit(IncomingActionEvent.AcceptFile(peerId, transferId))
            ACTION_REJECT_FILE -> IncomingActionBus.emit(IncomingActionEvent.RejectFile(peerId, transferId))
        }
    }

    companion object {
        const val ACTION_ACCEPT_FILE = "com.baytale.baychat.action.ACCEPT_INCOMING_FILE"
        const val ACTION_REJECT_FILE = "com.baytale.baychat.action.REJECT_INCOMING_FILE"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
