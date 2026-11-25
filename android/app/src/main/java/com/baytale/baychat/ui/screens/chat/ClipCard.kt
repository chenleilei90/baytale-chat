package com.baytale.baychat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 跨设备剪贴板同步卡片（严格遵循安全规范：收到时绝不自动写入本机系统剪贴板，仅由用户主动点击复制）。
 */
@Composable
fun ClipCard(
    item: ChatItem,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val cardBg = if (colors.isDark) colors.surfaceElevated else colors.surface
    val clipText = item.text.orEmpty()

    Column(
        modifier = modifier
            .widthIn(min = 210.dp, max = 280.dp)
            .clip(BayShapes.mdShape)
            .background(cardBg)
            .padding(BaySpacing.md),
        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(BayShapes.xsShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = if (item.isSelf) "已发送剪贴板片段" else "收到剪贴板片段",
                style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                maxLines = 1,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BayShapes.smShape)
                .background(colors.fill)
                .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
        ) {
            Text(
                text = clipText.ifBlank { "(空内容)" },
                style = BayType.callout,
                color = colors.textPrimary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            BayButton(
                text = "复制内容",
                onClick = {
                    if (clipText.isNotEmpty()) {
                        copyTextToClipboard(context, clipText)
                        onCopied()
                    }
                },
                style = BayButtonStyle.Secondary,
                size = BayButtonSize.Small,
                leadingIcon = Icons.Rounded.ContentCopy,
                enabled = clipText.isNotEmpty(),
            )
        }
    }
}
