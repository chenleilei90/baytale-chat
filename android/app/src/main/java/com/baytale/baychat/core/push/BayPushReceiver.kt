package com.baytale.baychat.core.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JPushMessageReceiver
import com.baytale.baychat.MainActivity
import org.json.JSONObject

/**
 * 极光系统级推送消息回调接收器：
 * 1. 收到设备 RegistrationID 后立即同步至 PushManager，触发房间推送登记；
 * 2. 用户在后台/锁屏点击系统通知后解析 roomCode 并通过深链唤醒 MainActivity 直达对应房间。
 */
class BayPushReceiver : JPushMessageReceiver() {
    override fun onRegister(context: Context, registrationId: String) {
        super.onRegister(context, registrationId)
        PushManager.onRegistrationIdReceived(registrationId)
    }

    override fun onNotifyMessageOpened(context: Context, message: NotificationMessage) {
        super.onNotifyMessageOpened(context, message)
        val roomCode = runCatching {
            val extraJson = message.notificationExtras.orEmpty()
            if (extraJson.isNotBlank()) {
                JSONObject(extraJson).optString("roomCode", "").trim()
            } else {
                ""
            }
        }.getOrDefault("")

        val intent = if (roomCode.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("baychat://room/$roomCode"), context, MainActivity::class.java)
        } else {
            Intent(context, MainActivity::class.java)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
    }
}
