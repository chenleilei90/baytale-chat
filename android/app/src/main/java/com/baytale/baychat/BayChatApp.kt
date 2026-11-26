package com.baytale.baychat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.IncomingNotificationHelper
import com.baytale.baychat.service.TransferForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 级前后台状态追踪器（基于 ActivityLifecycleCallbacks 统计真实活跃可见 Activity 数，
 * 避免进入扫码、系统文件选择器或相机拍照页时被单 Activity onStop 误判为切后台）。
 */
object AppForegroundTracker : Application.ActivityLifecycleCallbacks {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private var startedCount = 0
    private var isChangingConfig = false

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (++startedCount == 1 && !isChangingConfig) {
            _isForeground.value = true
        }
        isChangingConfig = false
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        isChangingConfig = activity.isChangingConfigurations
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0 && !isChangingConfig) {
            _isForeground.value = false
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}

// BayChat 应用入口：仅初始化本地通知渠道与前后台追踪，严格在用户同意隐私政策前不发起任何网络请求
class BayChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppForegroundTracker)
        TransferForegroundService.ensureChannel(this)
        AppUpdateService.ensureChannel(this)
        IncomingNotificationHelper.ensureChannel(this)
    }
}
