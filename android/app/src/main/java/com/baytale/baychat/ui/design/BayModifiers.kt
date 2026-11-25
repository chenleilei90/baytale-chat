package com.baytale.baychat.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import com.baytale.baychat.core.util.BayHaptic

/**
 * Bay Design 2.0 统一轻按压交互修饰符。
 *
 * 替代原生 M3 矩形/圆形水波纹：按压时以 120ms 过渡缩放至 0.97f、透明度降至 0.85f，
 * 并在点击触发时附带克制的系统轻触感反馈。
 * 提示：在需要整块背景随按压缩放的组件上，请将 [bayClickable] 放在 `clip` 与 `background` 之前。
 */
fun Modifier.bayClickable(
    enabled: Boolean = true,
    haptic: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hapticFeedback = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "bayClickScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.85f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "bayClickAlpha",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = {
                if (haptic) {
                    BayHaptic.tap(hapticFeedback, enabled = true)
                }
                onClick()
            },
        )
}
