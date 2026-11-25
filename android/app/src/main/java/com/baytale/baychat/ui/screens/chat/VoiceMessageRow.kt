package com.baytale.baychat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.ui.VoicePlaybackState
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlin.math.abs

private val WAVEFORM_BAR_HEIGHTS = listOf(8, 14, 10, 18, 22, 13, 16, 24, 15, 11, 20, 17, 9, 19, 14, 12, 16, 9)

/**
 * 语音消息气泡行：支持播放/暂停切换、确定性动态波形柱、点击或拖动波形条跳转进度。
 */
@Composable
fun VoiceMessageRow(
    item: ChatItem,
    voicePlayback: VoicePlaybackState,
    onTogglePlayback: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val isCurrent = voicePlayback.activeItemId == item.id
    val isPlaying = isCurrent && voicePlayback.isPlaying
    val durationMs = if (isCurrent && voicePlayback.durationMs > 0L) {
        voicePlayback.durationMs
    } else {
        parseCaptionDurationMs(item.caption)
    }
    val progressFraction = if (isCurrent && durationMs > 0L) {
        (voicePlayback.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val bubbleBg = if (item.isSelf) {
        colors.accent
    } else if (colors.isDark) {
        colors.surfaceElevated
    } else {
        colors.surface
    }
    val primaryTint = if (item.isSelf) colors.onAccent else colors.textPrimary
    val mutedTint = if (item.isSelf) colors.onAccent.copy(alpha = 0.42f) else colors.fillStrong
    val playBtnBg = if (item.isSelf) colors.onAccent.copy(alpha = 0.22f) else colors.accentSoft
    val playBtnIcon = if (item.isSelf) colors.onAccent else colors.accent

    // 根据消息 id 稳定打乱波形振幅，使每条语音呈现自然差异
    val bars = remember(item.id) {
        val seed = abs(item.id.hashCode())
        List(WAVEFORM_BAR_HEIGHTS.size) { index ->
            WAVEFORM_BAR_HEIGHTS[(index + seed) % WAVEFORM_BAR_HEIGHTS.size]
        }
    }

    var waveWidthPx by remember { mutableIntStateOf(1) }

    Column(
        modifier = modifier
            .width(236.dp)
            .clip(BayShapes.mdShape)
            .background(bubbleBg)
            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm + 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .bayClickable(onClick = onTogglePlayback)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(playBtnBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停语音" else "播放语音",
                    tint = playBtnIcon,
                    modifier = Modifier.size(20.dp),
                )
            }

            // 波形进度条（当前激活条支持点击/拖拽跳转播放进度）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .onSizeChanged { size -> waveWidthPx = size.width.coerceAtLeast(1) }
                    .pointerInput(isCurrent, durationMs, waveWidthPx) {
                        if (!isCurrent || durationMs <= 0L) return@pointerInput
                        detectTapGestures { offset ->
                            val frac = (offset.x / waveWidthPx.toFloat()).coerceIn(0f, 1f)
                            onSeekTo((frac * durationMs).toLong())
                        }
                    }
                    .pointerInput(isCurrent, durationMs, waveWidthPx) {
                        if (!isCurrent || durationMs <= 0L) return@pointerInput
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / waveWidthPx.toFloat()).coerceIn(0f, 1f)
                            onSeekTo((frac * durationMs).toLong())
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                bars.forEachIndexed { idx, heightDp ->
                    val barProgress = (idx + 0.5f) / bars.size.toFloat()
                    val active = progressFraction >= barProgress
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(heightDp.dp)
                            .clip(BayShapes.fullShape)
                            .background(if (active) primaryTint else mutedTint),
                    )
                }
            }

            val timeLabel = when {
                isCurrent && durationMs > 0L -> formatDurationMs(voicePlayback.positionMs)
                durationMs > 0L -> formatDurationMs(durationMs)
                else -> item.caption?.ifBlank { "语音" } ?: "语音"
            }

            Text(
                text = timeLabel,
                style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                color = primaryTint.copy(alpha = 0.9f),
                maxLines = 1,
            )
        }

        val tr = item.transfer
        if (tr != null && tr.status == TransferStatus.TRANSFERRING) {
            Spacer(modifier = Modifier.height(BaySpacing.xs))
            Text(
                text = "正在接收语音...",
                style = BayType.caption,
                color = primaryTint.copy(alpha = 0.8f),
            )
        }
    }
}

private fun parseCaptionDurationMs(caption: String?): Long {
    val raw = caption?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return 0L
    val sec = raw.removeSuffix("s").toLongOrNull() ?: return 0L
    return sec.coerceAtLeast(0L) * 1000L
}
