package com.baytale.baychat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baytale.baychat.ui.theme.LocalBayColors

// 首次启动隐私政策与最小化权限告知弹窗（满足 Google Play 与国内应用商店合规要求）
@Composable
fun PrivacyConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = LocalBayColors.current
    AlertDialog(
        onDismissRequest = { /* 强制显式同意或退出 */ },
        shape = RoundedCornerShape(24.dp),
        containerColor = colors.surface,
        title = {
            Text(
                text = "欢迎使用 湾聊 (BayChat)",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 19.sp,
                color = colors.textMain,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "为了保障您的隐私与数据安全，在您点击「同意并继续」之前，应用不会发起任何网络连接或读取设备信息。",
                    fontSize = 13.5.sp,
                    color = colors.textSub,
                    lineHeight = 20.sp,
                )
                Text(
                    text = "核心隐私承诺：",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = colors.textMain,
                )
                Text(
                    text = "• 免注册账号：不收集手机号、通讯录、定位或广告标识符，昵称与头像仅保存在本机。\n" +
                        "• P2P 加密直传：文件、图片与消息优先通过 WebRTC DTLS-SRTP 端到端直连传输；仅当双方网络无法打洞直连时经 TURN 加密中转。\n" +
                        "• 可控缓存保鲜期：默认支持「阅后即焚（服务器零留存）」或 1~7 天自动清理，房间内随时支持一键清空与销毁。\n" +
                        "• 最小化系统权限：相机权限仅在您主动点击「扫码进房」时申请；通知权限仅用于大文件后台传输保活进度显示；选图与保存文件走系统安全接口，无需全局存储权限。",
                    fontSize = 13.sp,
                    color = colors.textSub,
                    lineHeight = 19.5.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "点击「同意并继续」即表示您理解并同意上述隐私说明与使用规范。",
                    fontSize = 12.sp,
                    color = colors.textMuted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("同意并继续", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("不同意并退出", color = colors.textMuted)
            }
        },
    )
}
