package com.baytale.baychat.core.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// 纯原生清脆提示音与细腻触感引擎（零外部音频资源，24kHz PCM16 Mono 实时数学合成）
// 特点：
// 1. 平滑余弦淡入淡出包络 + 自然指数尾韵，彻底消除爆音与咔哒声
// 2. 独立单线程后台串行异步播放并安全释放 AudioTrack，绝不阻塞主线程
// 3. 播放前检测系统响铃模式，仅在正常响铃模式下发声，静音/震动模式绝不打扰
// ============================================================================
class BaySoundPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val released = AtomicBoolean(false)
    private val lastMessageInAt = AtomicLong(0L)
    private val lastRefreshTickAt = AtomicLong(0L)

    // 后台单线程串行播放，避免多段短音效叠加产生削波爆音
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BaySoundPlayer").apply { isDaemon = true }
    }

    // 单音符合成描述（支持固定音高或平滑滑音，用于模拟水滴声与清脆和弦）
    private data class ToneSegment(
        val startFreqHz: Double,
        val endFreqHz: Double = startFreqHz,
        val durationMs: Int,
        val amplitude: Double = 0.20,
        val gapAfterMs: Int = 0,
        val harmonicRatio: Double = 0.10,
        val decayRate: Double = 2.6,
    )

    // 进房成功 / 随机串门空降：轻快晶莹的纯五度双音（C5 -> G5）
    fun playRoomJoin() {
        enqueueTones(
            listOf(
                ToneSegment(startFreqHz = 523.25, durationMs = 68, amplitude = 0.19, gapAfterMs = 18, harmonicRatio = 0.12),
                ToneSegment(startFreqHz = 783.99, durationMs = 120, amplitude = 0.22, harmonicRatio = 0.12, decayRate = 3.2),
            )
        )
    }

    // 收到对方新文本/媒体消息：柔和清脆水滴声（640Hz -> 940Hz 平滑微上扬），带 220ms 连播节流
    fun playMessageIn() {
        val now = System.currentTimeMillis()
        val prev = lastMessageInAt.get()
        if (now - prev < MESSAGE_THROTTLE_MS) return
        if (!lastMessageInAt.compareAndSet(prev, now)) return

        enqueueTones(
            listOf(
                ToneSegment(
                    startFreqHz = 640.0,
                    endFreqHz = 940.0,
                    durationMs = 76,
                    amplitude = 0.20,
                    harmonicRatio = 0.06,
                    decayRate = 4.2,
                )
            )
        )
    }

    // 文件收发完成 / OTA 更新包校验就绪：明快愉悦的上升大三和弦琶音（C5 -> E5 -> G5）
    fun playTransferDone() {
        enqueueTones(
            listOf(
                ToneSegment(startFreqHz = 523.25, durationMs = 64, amplitude = 0.18, gapAfterMs = 12, harmonicRatio = 0.10),
                ToneSegment(startFreqHz = 659.25, durationMs = 64, amplitude = 0.20, gapAfterMs = 12, harmonicRatio = 0.10),
                ToneSegment(startFreqHz = 783.99, durationMs = 145, amplitude = 0.22, harmonicRatio = 0.12, decayRate = 2.8),
            )
        )
    }

    // 下拉刷新完成：极轻柔克制的微扬提示音（880Hz -> 1046.5Hz，42ms），带 300ms 节流
    fun playRefreshTick() {
        val now = System.currentTimeMillis()
        val prev = lastRefreshTickAt.get()
        if (now - prev < REFRESH_THROTTLE_MS) return
        if (!lastRefreshTickAt.compareAndSet(prev, now)) return

        enqueueTones(
            listOf(
                ToneSegment(
                    startFreqHz = 880.0,
                    endFreqHz = 1046.5,
                    durationMs = 42,
                    amplitude = 0.13,
                    harmonicRatio = 0.04,
                    decayRate = 5.0,
                )
            )
        )
    }

    // 释放后台播放线程池（随 ViewModel onCleared 安全调用）
    fun release() {
        if (released.compareAndSet(false, true)) {
            runCatching { executor.shutdownNow() }
        }
    }

    private fun enqueueTones(segments: List<ToneSegment>) {
        if (released.get() || segments.isEmpty()) return
        runCatching {
            executor.execute {
                if (released.get()) return@execute
                if (!isRingerModeNormal()) return@execute
                playPcmBlocking(synthesizeSegments(segments))
            }
        }
    }

    // 严格遵守系统静音/震动设定：仅 RINGER_MODE_NORMAL 时允许发声
    private fun isRingerModeNormal(): Boolean {
        return runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.ringerMode == AudioManager.RINGER_MODE_NORMAL
        }.getOrDefault(false)
    }

    // 纯数学合成 24kHz PCM16 单声道波形，每个音符施加余弦淡入/淡出包络防止爆音
    private fun synthesizeSegments(segments: List<ToneSegment>): ShortArray {
        val totalSamples = segments.sumOf { seg ->
            msToSamples(seg.durationMs) + msToSamples(seg.gapAfterMs)
        }
        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1024)
        // MODE_STATIC 缓冲区末尾补足静默样本至至少 minBufBytes，避免部分厂商 ROM 截断尾部或报参错
        val paddedSamples = maxOf(totalSamples + msToSamples(12), (minBufBytes / 2) + 64)
        val buffer = ShortArray(paddedSamples)

        var cursor = 0
        for (seg in segments) {
            val sampleCount = msToSamples(seg.durationMs).coerceAtLeast(1)
            val attackSamples = minOf(msToSamples(8), sampleCount / 4).coerceAtLeast(1)
            val releaseSamples = minOf(msToSamples(24), sampleCount / 2).coerceAtLeast(1)
            var phase = 0.0
            var harmonicPhase = 0.0

            for (i in 0 until sampleCount) {
                val progress = i.toDouble() / sampleCount.toDouble()
                val instantFreq = seg.startFreqHz + (seg.endFreqHz - seg.startFreqHz) * progress
                val phaseStep = 2.0 * PI * instantFreq / SAMPLE_RATE.toDouble()
                phase += phaseStep
                harmonicPhase += phaseStep * 2.0

                // 半余弦平滑起音包络 + 半余弦平滑收尾包络 + 自然指数尾韵
                val attackEnv = if (i < attackSamples) {
                    0.5 * (1.0 - cos(PI * i.toDouble() / attackSamples.toDouble()))
                } else {
                    1.0
                }
                val releaseStart = sampleCount - releaseSamples
                val releaseEnv = if (i >= releaseStart) {
                    val pos = (i - releaseStart).toDouble() / releaseSamples.toDouble()
                    0.5 * (1.0 + cos(PI * pos))
                } else {
                    1.0
                }
                val naturalDecay = exp(-seg.decayRate * progress)
                val envelope = attackEnv * releaseEnv * naturalDecay

                val wave = sin(phase) + seg.harmonicRatio * sin(harmonicPhase)
                val normalized = (wave / (1.0 + seg.harmonicRatio)) * seg.amplitude * envelope
                val pcmVal = (normalized.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt()
                if (cursor + i < buffer.size) {
                    buffer[cursor + i] = pcmVal.toShort()
                }
            }
            cursor += sampleCount + msToSamples(seg.gapAfterMs)
        }
        return buffer
    }

    private fun playPcmBlocking(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        var track: AudioTrack? = null
        try {
            val byteLen = pcm.size * 2
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(byteLen)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) return
            val written = track.write(pcm, 0, pcm.size)
            if (written <= 0) return

            track.play()
            val playDurationMs = ((pcm.size * 1000L) / SAMPLE_RATE) + 32L
            Thread.sleep(playDurationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // 任何底层音频硬件异常均安全吞掉，绝不影响业务与 UI 稳定性
        } finally {
            runCatching {
                if (track?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
            }
            runCatching { track?.release() }
        }
    }

    private fun msToSamples(ms: Int): Int = ((SAMPLE_RATE * ms) / 1000).coerceAtLeast(0)

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val MESSAGE_THROTTLE_MS = 220L
        private const val REFRESH_THROTTLE_MS = 300L
    }
}

// 统一细腻触感反馈工具（受用户设置 hapticEnabled 全局控制，全版本安全降级）
object BayHaptic {
    // 轻触感：切 Tab、点筛选胶囊、点浮球返回
    fun tap(view: View?, enabled: Boolean) {
        if (!enabled || view == null) return
        runCatching {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                HapticFeedbackConstants.TEXT_HANDLE_MOVE
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            view.performHapticFeedback(code)
        }
    }

    // 轻触感（Compose LocalHapticFeedback 重载）
    fun tap(haptic: HapticFeedback?, enabled: Boolean) {
        if (!enabled || haptic == null) return
        runCatching {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // 确认触感：下拉刷新完成、保存资料、切换重要开关
    fun confirm(view: View?, enabled: Boolean) {
        if (!enabled || view == null) return
        runCatching {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
            view.performHapticFeedback(code)
        }
    }

    // 重触感：长按浮球、浮球拖入底部退出热区
    fun heavy(view: View?, enabled: Boolean) {
        if (!enabled || view == null) return
        runCatching {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // 重触感（Compose LocalHapticFeedback 重载）
    fun heavy(haptic: HapticFeedback?, enabled: Boolean) {
        if (!enabled || haptic == null) return
        runCatching {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
