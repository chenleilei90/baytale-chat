package com.baytale.baychat.ui.screens.chat

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.VoicePlaybackState
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 微信风格语音消息气泡：
 * - 气泡宽度随语音时长（1..60s）在 72.dp 至可用宽度的 65% 之间线性增长；
 * - 3 段声波弧在播放中逐段点亮，静止时完整显示；
 * - 对方未播放语音在气泡外侧显示 8.dp 纯圆未读红点（零描边）；
 * - 仅响应点击播放与长按菜单，不拦截横向拖拽以免与长按手势冲突。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceMessageRow(
    item: ChatItem,
    voicePlayback: VoicePlaybackState,
    isUnread: Boolean,
    hapticEnabled: Boolean,
    onTogglePlayback: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isCurrent = voicePlayback.activeItemId == item.id
    val isPlaying = isCurrent && voicePlayback.isPlaying
    val animationsEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) != 0f
        }.getOrDefault(true)
    }

    val durationSec = remember(item.caption, isCurrent, voicePlayback.durationMs) {
        val parsed = parseCaptionDurationSeconds(item.caption)
        when {
            parsed > 0 -> parsed.coerceIn(1, 60)
            isCurrent && voicePlayback.durationMs > 0L -> {
                ((voicePlayback.durationMs + 500L) / 1000L).toInt().coerceIn(1, 60)
            }
            else -> 1
        }
    }

    val bubbleBg = if (item.isSelf) {
        colors.accent
    } else if (colors.isDark) {
        colors.surfaceElevated
    } else {
        colors.surface
    }
    val primaryTint = if (item.isSelf) colors.onAccent else colors.textPrimary
    val mutedTint = if (item.isSelf) colors.onAccent.copy(alpha = 0.28f) else colors.textTertiary

    // 播放中逐段点亮 3 段声波弧（若系统已关闭动画则静止展示完整声波弧）
    val activeWaveCount = if (isPlaying && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "voiceWaveAnim")
        val step by transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 840, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "voiceWaveStep",
        )
        (step.toInt() % 3) + 1
    } else {
        3
    }

    val clickInteraction = remember { MutableInteractionSource() }
    val semanticDesc = remember(item.isSelf, item.senderName, durationSec, isPlaying, isUnread) {
        val owner = if (item.isSelf) "我发送的" else "${item.senderName}发送的"
        val status = when {
            isPlaying -> "正在播放"
            !item.isSelf && isUnread -> "未读"
            else -> "点击播放"
        }
        "${owner}语音消息，时长${durationSec}秒，$status"
    }

    BoxWithConstraints(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = semanticDesc
        },
    ) {
        val targetMaxWidth = maxOf(72.dp, maxWidth * 0.65f)
        val fraction = ((durationSec - 1).coerceIn(0, 59)) / 59f
        val bubbleWidth = 72.dp + (targetMaxWidth - 72.dp) * fraction

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
        ) {
            Column(
                modifier = Modifier
                    .width(bubbleWidth)
                    .clip(BayShapes.mdShape)
                    .background(bubbleBg)
                    .combinedClickable(
                        interactionSource = clickInteraction,
                        indication = null,
                        onClick = {
                            BayHaptic.tap(haptic, hapticEnabled)
                            onTogglePlayback()
                        },
                        onLongClick = {
                            BayHaptic.heavy(haptic, hapticEnabled)
                            onLongPress()
                        },
                    )
                    .padding(horizontal = BaySpacing.md, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (item.isSelf) {
                        Text(
                            text = "${durationSec}″",
                            style = BayType.callout.copy(fontFamily = FontFamily.Monospace),
                            color = primaryTint,
                            maxLines = 1,
                        )
                        VoiceWaveArcs(
                            pointLeft = true,
                            activeCount = activeWaveCount,
                            activeColor = primaryTint,
                            inactiveColor = mutedTint,
                        )
                    } else {
                        VoiceWaveArcs(
                            pointLeft = false,
                            activeCount = activeWaveCount,
                            activeColor = primaryTint,
                            inactiveColor = mutedTint,
                        )
                        Text(
                            text = "${durationSec}″",
                            style = BayType.callout.copy(fontFamily = FontFamily.Monospace),
                            color = primaryTint,
                            maxLines = 1,
                        )
                    }
                }

                val tr = item.transfer
                if (tr != null && tr.status == TransferStatus.TRANSFERRING) {
                    Spacer(modifier = Modifier.height(BaySpacing.xs))
                    Text(
                        text = "接收中",
                        style = BayType.caption,
                        color = primaryTint.copy(alpha = 0.8f),
                    )
                }
            }

            // 对方未读语音外侧纯圆红点（零描边）
            if (!item.isSelf && isUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.danger),
                )
            }
        }
    }
}

/**
 * 3 段微信风格声波弧绘制：第 1 段为内侧实心扇形，第 2、3 段为外扩圆角弧线。
 */
@Composable
private fun VoiceWaveArcs(
    pointLeft: Boolean,
    activeCount: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val strokePx = 1.9.dp.toPx()
        val originX = if (pointLeft) w * 0.82f else w * 0.18f
        val originY = h * 0.5f
        val startAngle = if (pointLeft) 138f else -42f
        val sweepAngle = 84f

        val r1 = w * 0.18f
        val r2 = w * 0.42f
        val r3 = w * 0.66f

        // 第 1 段声波
        drawArc(
            color = if (activeCount >= 1) activeColor else inactiveColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = true,
            topLeft = Offset(originX - r1, originY - r1),
            size = Size(r1 * 2f, r1 * 2f),
        )

        // 第 2 段声波弧
        drawArc(
            color = if (activeCount >= 2) activeColor else inactiveColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(originX - r2, originY - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )

        // 第 3 段声波弧
        drawArc(
            color = if (activeCount >= 3) activeColor else inactiveColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(originX - r3, originY - r3),
            size = Size(r3 * 2f, r3 * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

private fun parseCaptionDurationSeconds(caption: String?): Int {
    val raw = caption?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return 0
    return raw.removeSuffix("s").removeSuffix("″").toIntOrNull() ?: 0
}
