package com.baytale.baychat.ui.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * Bay Design 2.0 开关组件：彻底清除 M3 Switch 默认未选中边框，纯填充滑轨与白色滑块。
 */
@Composable
fun BaySwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current

    Switch(
        checked = checked,
        onCheckedChange = if (onCheckedChange != null) {
            { next ->
                BayHaptic.tap(haptic, enabled = true)
                onCheckedChange(next)
            }
        } else {
            null
        },
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = Color.Transparent,
            checkedIconColor = colors.accent,
            uncheckedThumbColor = colors.onAccent,
            uncheckedTrackColor = colors.fillStrong,
            uncheckedBorderColor = Color.Transparent,
            uncheckedIconColor = colors.fillStrong,
            disabledCheckedThumbColor = colors.onAccent.copy(alpha = 0.8f),
            disabledCheckedTrackColor = colors.accent.copy(alpha = 0.4f),
            disabledCheckedBorderColor = Color.Transparent,
            disabledUncheckedThumbColor = colors.onAccent.copy(alpha = 0.6f),
            disabledUncheckedTrackColor = colors.fill,
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * iOS 风格分段控制器：最小高 34dp 填充轨道 + 弹性平移白底滑块（支持大字号自适应撑高）。
 */
@Composable
fun BaySegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current
    val safeSelected = selected.coerceIn(0, options.lastIndex)
    val outerShape = RoundedCornerShape(9.dp)
    val thumbShape = RoundedCornerShape(7.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .clip(outerShape)
            .background(colors.fill)
            .padding(2.dp),
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * safeSelected,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "baySegmentedOffset",
        )

        val thumbShadowModifier = if (colors.isDark) {
            Modifier
        } else {
            Modifier.shadow(elevation = 1.dp, shape = thumbShape, clip = false)
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // 选中项滑动指示块：使用 matchParentSize 与文本行等高，大字号时随内容同步增高
            Box(modifier = Modifier.matchParentSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = thumbOffset)
                        .width(segmentWidth)
                        .fillMaxHeight()
                        .then(thumbShadowModifier)
                        .clip(thumbShape)
                        .background(if (colors.isDark) colors.surfaceElevated else colors.surface),
                )
            }

            // 选项标签层
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == safeSelected
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 30.dp)
                            .clip(thumbShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    if (index != safeSelected) {
                                        BayHaptic.tap(haptic, enabled = true)
                                        onSelect(index)
                                    }
                                },
                            )
                            .padding(horizontal = BaySpacing.sm, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = BayType.footnote.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 轻量胶囊筛选项：最小高 32dp 视觉胶囊（外层透明热区 >= 44dp），零描边，支持大字号自适应撑高。
 */
@Composable
fun BayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val containerColor = if (selected) colors.accent else colors.fill
    val contentColor = if (selected) colors.onAccent else colors.textPrimary

    BayMinTouchTarget(
        modifier = modifier
            .semantics { role = Role.Checkbox }
            .bayClickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(BayShapes.fullShape)
                .background(containerColor)
                .padding(horizontal = BaySpacing.md, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
