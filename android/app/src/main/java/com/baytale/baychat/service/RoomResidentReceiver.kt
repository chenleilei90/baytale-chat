package com.baytale.baychat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.baytale.baychat.BayChatApp
import com.baytale.baychat.core.storage.AppPrefsStore

/**
 * 后台常驻看门狗接收器：响应开机完成、应用覆盖升级及 Doze 心跳闹钟，
 * 在用户未主动退房时静默唤醒连接并续订心跳；若已主动退房则立即撤消闹钟，零后台开销。
 */
class RoomResidentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != TransferForegroundService.ACTION_RESIDENT_HEARTBEAT
        ) {
            return
        }

        val appCtx = context.applicationContext
        val store = AppPrefsStore(appCtx)
        val saved = store.loadLastActiveRoom()
        if (!store.hasAcceptedPrivacy() || saved == null || saved.code.isBlank()) {
            TransferForegroundService.cancelWatchdog(appCtx)
            return
        }

        // 申请 3 秒极短脉冲唤醒锁，防止 Doze 刚唤醒即回睡导致重连尚未发出
        runCatching {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val pulse = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BayChat::WatchdogPulse")
            pulse?.setReferenceCounted(false)
            pulse?.acquire(3_000L)
        }

        // 续订下一次 3 分钟看门狗探测，保证服务持续在线
        TransferForegroundService.scheduleWatchdog(appCtx)
        TransferForegroundService.reviveIfNeeded(appCtx)
        (appCtx as? BayChatApp)?.sharedViewModel?.ensureActiveRoomConnectedFromService()
    }
}
