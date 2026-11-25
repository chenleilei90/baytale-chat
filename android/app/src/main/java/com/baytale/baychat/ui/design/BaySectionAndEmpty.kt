package com.baytale.baychat.ui.design

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 分区标题栏：左侧 headline 标题，右侧可选主题色操作文字链。
 */
@Composable
fun BaySectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = BayType.headline,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Text(
                text = actionLabel,
                style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .bayClickable(onClick = onAction)
                    .clip(BayShapes.xsShape)
                    .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
            )
        }
    }
}

/**
 * 统一空状态占位视图：柔和圆形图标底、简洁标题、克制副标题与可选引导按钮。
 */
@Composable
fun BayEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = BaySpacing.xxl, vertical = BaySpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(26.dp),
            )
        }

        Spacer(modifier = Modifier.height(BaySpacing.md))

        Text(
            text = title,
            style = BayType.headline,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )

        if (message.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = BayType.subhead,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }

        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Spacer(modifier = Modifier.height(BaySpacing.lg))
            BayButton(
                text = actionLabel,
                onClick = onAction,
                style = BayButtonStyle.Secondary,
                size = BayButtonSize.Medium,
            )
        }
    }
}
