package com.baytale.baychat.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * Bay Design 2.0 基础卡片容器：纯色 surface 承托，20dp 圆角，零描边、零阴影。
 */
@Composable
fun BayCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = BaySpacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BayTheme.colors
    val baseModifier = if (onClick != null) {
        modifier.bayClickable(onClick = onClick)
    } else {
        modifier
    }

    Column(
        modifier = baseModifier
            .clip(BayShapes.lgShape)
            .background(colors.surface)
            .padding(padding),
        content = content,
    )
}

/**
 * iOS Inset Grouped 风格分组容器：16dp 圆角 surface 卡片，可选顶部组标题与底部说明文字。
 */
@Composable
fun BayGroup(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BayTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                style = BayType.footnote,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    start = BaySpacing.lg,
                    end = BaySpacing.lg,
                    bottom = BaySpacing.sm,
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BayShapes.mdShape)
                .background(colors.surface),
            content = content,
        )

        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = BayType.footnote,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    start = BaySpacing.lg,
                    end = BaySpacing.lg,
                    top = BaySpacing.sm,
                ),
            )
        }
    }
}

/**
 * [BayListRow] 右侧尾部件类型定义。
 */
sealed interface RowTrailing {
    data object None : RowTrailing
    data object Chevron : RowTrailing
    data class Switch(val checked: Boolean, val onChange: (Boolean) -> Unit) : RowTrailing
    data class Custom(val content: @Composable () -> Unit) : RowTrailing
}

/**
 * [BayGroup] 内部发丝级分隔线：高 0.5dp，支持根据左侧是否带图标自动缩进（58dp / 16dp）。
 */
@Composable
fun BayGroupDivider(
    hasLeadingIcon: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (hasLeadingIcon) 58.dp else 16.dp)
            .height(0.5.dp)
            .background(colors.separator),
    )
}

/**
 * iOS / 飞书质感列表行：最小高 52dp，支持 30dp 圆角图标块、双行文案、右侧读数及尾部件。
 */
@Composable
fun BayListRow(
    icon: ImageVector? = null,
    iconTint: Color? = null,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    trailing: RowTrailing = RowTrailing.None,
    destructive: Boolean = false,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current
    val isRowToggleable = trailing is RowTrailing.Switch && onClick == null
    val rowModifier = when {
        onClick != null -> modifier.bayClickable(onClick = onClick)
        isRowToggleable -> {
            val switchTrailing = trailing as RowTrailing.Switch
            val interactionSource = remember { MutableInteractionSource() }
            modifier
                .semantics(mergeDescendants = true) {}
                .toggleable(
                    value = switchTrailing.checked,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Switch,
                    onValueChange = { next ->
                        BayHaptic.tap(haptic, enabled = true)
                        switchTrailing.onChange(next)
                    },
                )
        }
        else -> modifier
    }

    Column(modifier = rowModifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = BaySpacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                val badgeBg = iconTint ?: if (destructive) colors.danger else colors.accent
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(BayShapes.xsShape)
                        .background(badgeBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.width(BaySpacing.md))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = BayType.body,
                    color = if (destructive) colors.danger else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = BayType.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(BaySpacing.sm))
                Text(
                    text = value,
                    style = BayType.callout,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (trailing) {
                RowTrailing.None -> Unit
                RowTrailing.Chevron -> {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                is RowTrailing.Switch -> {
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    BaySwitch(
                        checked = trailing.checked,
                        onCheckedChange = trailing.onChange,
                        modifier = if (isRowToggleable) Modifier.clearAndSetSemantics {} else Modifier,
                    )
                }
                is RowTrailing.Custom -> {
                    Spacer(modifier = Modifier.width(BaySpacing.sm))
                    trailing.content()
                }
            }
        }

        if (showDivider) {
            BayGroupDivider(hasLeadingIcon = icon != null)
        }
    }
}
