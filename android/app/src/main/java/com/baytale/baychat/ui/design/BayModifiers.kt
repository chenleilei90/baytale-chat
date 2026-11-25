package com.baytale.baychat.ui.design

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.util.BayHaptic

/**
 * Bay Design 2.0 统一轻按压交互修饰符。
 *
 * 替代原生 M3 矩形/圆形水波纹：按压时以 120ms 过渡缩放至 0.97f、透明度降至 0.85f，
 * 内置防连点节流（默认 400ms），并尊重系统「减少动态效果」无障碍设置。
 * 提示：在需要整块背景随按压缩放的组件上，请将 [bayClickable] 放在 `clip` 与 `background` 之前。
 */
fun Modifier.bayClickable(
    enabled: Boolean = true,
    haptic: Boolean = true,
    debounceMs: Long = 400L,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val lastClickAtRef = remember { longArrayOf(0L) }

    // 检测系统无障碍「减少动态效果」（ANIMATOR_DURATION_SCALE == 0f）
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    val animatedScale by animateFloatAsState(
        targetValue = if (!reduceMotion && pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 120),
        label = "bayClickScale",
    )
    val animatedAlpha by animateFloatAsState(
        targetValue = if (!reduceMotion && pressed && enabled) 0.85f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 120),
        label = "bayClickAlpha",
    )

    val finalScale = if (reduceMotion) 1f else animatedScale
    val finalAlpha = if (reduceMotion) {
        if (pressed && enabled) 0.85f else 1f
    } else {
        animatedAlpha
    }

    this
        .graphicsLayer {
            scaleX = finalScale
            scaleY = finalScale
            alpha = finalAlpha
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                val now = SystemClock.elapsedRealtime()
                val prev = lastClickAtRef[0]
                if (debounceMs <= 0L || prev == 0L || now - prev >= debounceMs) {
                    lastClickAtRef[0] = now
                    if (haptic) {
                        BayHaptic.tap(hapticFeedback, enabled = true)
                    }
                    onClick()
                }
            },
        )
}

/**
 * 透明扩充最小交互热区（默认 44dp × 44dp），同时保留内部子组件的视觉宽高。
 * 透传水平最小宽度约束（如 fillMaxWidth / weight），放开垂直 minHeight 以免小胶囊被视觉拉高。
 */
@Composable
internal fun BayMinTouchTarget(
    modifier: Modifier = Modifier,
    minTouchSize: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val minTouchPx = minTouchSize.roundToPx()
        val childConstraints = constraints.copy(minHeight = 0)
        val placeable = measurables.first().measure(childConstraints)
        val layoutWidth = maxOf(placeable.width, minTouchPx)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = maxOf(placeable.height, minTouchPx)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth, layoutHeight) {
            val x = (layoutWidth - placeable.width) / 2
            val y = (layoutHeight - placeable.height) / 2
            placeable.placeRelative(x, y)
        }
    }
}
