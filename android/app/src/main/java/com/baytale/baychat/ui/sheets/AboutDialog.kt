package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 关于软件与隐私安全说明弹窗：展示版本信息、端到端直传机制并支持主动检查更新。
 */
@Composable
fun AboutDialog(
    state: BayChatUiState,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val ota = state.otaState
    val readyRelease = ota.release?.takeIf { ota.status == OtaUpdateStatus.READY_TO_INSTALL }

    BayDialog(
        title = "关于湾聊 BayChat",
        confirmText = if (readyRelease != null) "查看更新" else "检查更新",
        onConfirm = {
            if (readyRelease != null) {
                vm.openUpdateDialog()
            } else {
                vm.checkForAppUpdate(manual = true)
            }
            onDismiss()
        },
        dismissText = "关闭",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.smShape)
                    .background(colors.accentSoft)
                    .padding(BaySpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "当前版本 v${BuildConfig.VERSION_NAME}",
                        style = BayType.callout,
                        color = colors.accent,
                    )
                    Text(
                        text = "构建号 Build ${BuildConfig.VERSION_CODE}",
                        style = BayType.caption,
                        color = colors.textSecondary,
                    )
                }
            }

            if (readyRelease != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BayShapes.smShape)
                        .background(colors.success.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                        .padding(BaySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "新版本 v${readyRelease.versionName} 安装包已校验就绪",
                        style = BayType.footnote,
                        color = colors.success,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.xs)) {
                Text(
                    text = "端到端直传：文件、图片与消息优先通过 WebRTC DTLS-SRTP 点对点直连传输，并支持 SHA-256 完整性校验。",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
                Text(
                    text = "本地身份隐私：免账号注册，不收集手机号、通讯录、定位与广告标识符，身份资料仅存本机。",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
                Text(
                    text = "可控留存周期：支持阅后即焚（服务器不留存）或 1 至 7 天保鲜期，房内随时可清空或销毁。",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
