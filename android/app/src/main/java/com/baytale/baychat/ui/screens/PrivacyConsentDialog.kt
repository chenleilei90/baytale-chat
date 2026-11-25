package com.baytale.baychat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 首次启动隐私政策与最小化权限告知弹窗（使用 [BayDialog] 构建，零描边）。
 */
@Composable
fun PrivacyConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = BayTheme.colors

    BayDialog(
        title = "欢迎使用 湾聊 (BayChat)",
        message = "在您点击「同意并继续」之前，应用不会发起任何网络连接或读取设备信息。",
        confirmText = "同意并继续",
        onConfirm = onAccept,
        dismissText = "不同意并退出",
        onDismiss = onDecline,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
        ) {
            Text(
                text = "核心隐私承诺：",
                style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(
                text = "1. 免注册账号：不收集手机号、通讯录、定位或广告标识符，昵称与头像仅保存在本机。\n" +
                    "2. P2P 加密直传：文件、图片与消息优先通过 WebRTC DTLS-SRTP 端到端直连传输；仅当双方网络无法直连时经 TURN 加密中转。\n" +
                    "3. 可控缓存保鲜期：默认支持「阅后即焚（服务器零留存）」或 1~7 天自动清理，房间内随时支持一键清空与销毁。\n" +
                    "4. 最小化系统权限：相机权限仅在主动点击「扫码进房」时申请；麦克风权限仅在语音录制时申请；通知权限仅用于后台传输进度与来件提醒；选图与保存文件走系统安全接口。",
                style = BayType.footnote,
                color = colors.textSecondary,
            )
            Text(
                text = "点击「同意并继续」即表示您理解并同意上述隐私说明与使用规范。",
                style = BayType.caption,
                color = colors.textTertiary,
            )
        }
    }
}
