package com.baytale.baychat.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * [BayButton] 视觉风格枚举。
 */
enum class BayButtonStyle {
    /** 主题实底强强调按钮（主行动） */
    Primary,

    /** 主题浅底次强调按钮（次行动） */
    Secondary,

    /** 中性填充按钮（替代传统 OutlinedButton） */
    Neutral,

    /** 无底色纯文字按钮（替代传统 TextButton） */
    Plain,

    /** 高危实底红按钮（销毁、清空等不可逆主操作） */
    Destructive,

    /** 高危浅底红按钮（婉拒、退出等温和警示操作） */
    DestructiveSoft,
}

/**
 * [BayButton] 尺寸规格枚举。
 */
enum class BayButtonSize {
    /** 大按钮：最小高 50dp、圆角 14dp、headline 字阶 */
    Large,

    /** 中按钮：最小高 40dp、圆角 12dp、callout Medium 字阶 */
    Medium,

    /** 小按钮：最小高 32dp、全圆角胶囊、footnote Medium 字阶（外层热区 >= 44dp） */
    Small,
}

/**
 * Bay Design 2.0 统一按钮组件（基于 Row 自绘，零描边、带按压缩放与触感，支持大字号撑开与最小 44dp 热区）。
 */
@Composable
fun BayButton(
    text: String,
    onClick: () -> Unit,
    style: BayButtonStyle = BayButtonStyle.Primary,
    size: BayButtonSize = BayButtonSize.Medium,
    leadingIcon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors

    val containerColor = when (style) {
        BayButtonStyle.Primary -> colors.accent
        BayButtonStyle.Secondary -> colors.accentSoft
        BayButtonStyle.Neutral -> colors.fill
        BayButtonStyle.Plain -> Color.Transparent
        BayButtonStyle.Destructive -> colors.danger
        BayButtonStyle.DestructiveSoft -> colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.12f)
    }

    val contentColor = when (style) {
        BayButtonStyle.Primary -> colors.onAccent
        BayButtonStyle.Secondary -> colors.accent
        BayButtonStyle.Neutral -> colors.textPrimary
        BayButtonStyle.Plain -> colors.accent
        BayButtonStyle.Destructive -> colors.onAccent
        BayButtonStyle.DestructiveSoft -> colors.danger
    }

    val minHeight: Dp
    val shape: RoundedCornerShape
    val verticalPadding: Dp
    val textStyle = when (size) {
        BayButtonSize.Large -> {
            minHeight = 50.dp
            shape = RoundedCornerShape(14.dp)
            verticalPadding = 10.dp
            BayType.headline
        }
        BayButtonSize.Medium -> {
            minHeight = 40.dp
            shape = BayShapes.smShape
            verticalPadding = 8.dp
            BayType.callout.copy(fontWeight = FontWeight.Medium)
        }
        BayButtonSize.Small -> {
            minHeight = 32.dp
            shape = BayShapes.fullShape
            verticalPadding = 6.dp
            BayType.footnote.copy(fontWeight = FontWeight.Medium)
        }
    }

    val horizontalPadding = when (size) {
        BayButtonSize.Large -> 20.dp
        BayButtonSize.Medium -> 16.dp
        BayButtonSize.Small -> 12.dp
    }

    val iconSize = when (size) {
        BayButtonSize.Large -> 18.dp
        BayButtonSize.Medium -> 16.dp
        BayButtonSize.Small -> 14.dp
    }

    val interactive = enabled && !loading
    val interactionModifier = Modifier
        .semantics { role = Role.Button }
        .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
        .bayClickable(enabled = interactive, onClick = onClick)

    val buttonContent: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(
            modifier = rowModifier
                .heightIn(min = minHeight)
                .clip(shape)
                .background(containerColor)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = contentColor,
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }

            Text(
                text = text,
                style = textStyle,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (size == BayButtonSize.Small) {
        // 小尺寸按钮保持 32dp 视觉胶囊的同时，外层透明扩充触摸热区至 >= 44dp
        BayMinTouchTarget(
            modifier = modifier.then(interactionModifier),
        ) {
            buttonContent(Modifier)
        }
    } else {
        buttonContent(modifier.then(interactionModifier))
    }
}

/**
 * [BayIconButton] 视觉风格枚举。
 */
enum class BayIconStyle {
    /** 中性填充圆底 */
    Filled,

    /** 主题浅底强调 */
    Accent,

    /** 无底色纯图标 */
    Plain,
}

/**
 * Bay Design 2.0 统一圆形图标按钮，替代尺寸杂乱的原生 IconButton。
 * 当视觉尺寸小于 44dp 时，外层透明区域自动扩充至 44dp × 44dp 以满足无障碍最小触摸热区。
 */
@Composable
fun BayIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    style: BayIconStyle = BayIconStyle.Filled,
    size: Dp = 36.dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val containerColor = when (style) {
        BayIconStyle.Filled -> colors.fill
        BayIconStyle.Accent -> colors.accentSoft
        BayIconStyle.Plain -> Color.Transparent
    }
    val tintColor = when (style) {
        BayIconStyle.Filled -> colors.textPrimary
        BayIconStyle.Accent -> colors.accent
        BayIconStyle.Plain -> colors.textSecondary
    }
    val iconSize = (size * 0.52f).coerceIn(14.dp, 24.dp)
    val touchTargetSize = maxOf(size, 44.dp)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = touchTargetSize, minHeight = touchTargetSize)
            .semantics { role = Role.Button }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .bayClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tintColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
