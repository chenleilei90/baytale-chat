package com.baytale.baychat.core.push

import android.content.Context
import cn.jpush.android.api.JPushInterface
import com.baytale.baychat.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全机型系统级离线推送管理器：
 * 封装极光 JPush（并由极光桥接小米/华为/OPPO/vivo/荣耀厂商系统通道与 FCM）。
 * 未配置 JPUSH_APPKEY 时自动保持静默未激活状态，不影响应用正常启动与直连聊天。
 */
object PushManager {
    private val initialized = AtomicBoolean(false)
    private val _registrationId = MutableStateFlow("")
    val registrationId: StateFlow<String> = _registrationId.asStateFlow()

    fun isConfigured(): Boolean = BuildConfig.JPUSH_APPKEY.isNotBlank()

    fun initIfConfigured(context: Context) {
        if (!isConfigured()) return
        if (!initialized.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        runCatching {
            JPushInterface.setDebugMode(BuildConfig.DEBUG)
            JPushInterface.init(appCtx)
            val existing = JPushInterface.getRegistrationID(appCtx).orEmpty().trim()
            if (existing.isNotEmpty()) {
                _registrationId.value = existing
            }
        }
    }

    fun onRegistrationIdReceived(regId: String) {
        val clean = regId.trim()
        if (clean.isNotEmpty()) {
            _registrationId.value = clean
        }
    }

    fun getRegistrationId(context: Context): String {
        val cached = _registrationId.value
        if (cached.isNotBlank()) return cached
        if (!isConfigured()) return ""
        return runCatching {
            JPushInterface.getRegistrationID(context.applicationContext).orEmpty().trim().also { id ->
                if (id.isNotEmpty()) _registrationId.value = id
            }
        }.getOrDefault("")
    }
}
