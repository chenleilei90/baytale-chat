package com.baytale.baychat

import android.app.Application
import com.baytale.baychat.service.TransferForegroundService

// BayChat 应用入口：仅初始化本地通知渠道，严格在用户同意隐私政策前不发起任何网络请求
class BayChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TransferForegroundService.ensureChannel(this)
    }
}
