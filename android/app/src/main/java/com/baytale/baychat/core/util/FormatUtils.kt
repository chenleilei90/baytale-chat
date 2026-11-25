package com.baytale.baychat.core.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.math.pow

// 通用工具：字节格式化、房间码解析、离线二维码生成与随机昵称
object FormatUtils {
    private val ADJECTIVES = listOf("星辰", "闪电", "极光", "青柠", "云朵", "蜜桃", "海盐", "微风", "漫游", "轻羽")
    private val NOUNS = listOf("旅人", "飞鸟", "企鹅", "鲸鱼", "海豚", "猫咪", "柴犬", "松鼠", "信使", "搭子")
    val PRESET_AVATARS = listOf(
        "preset:cat" to "🐱",
        "preset:dog" to "🐶",
        "preset:fox" to "🦊",
        "preset:panda" to "🐼",
        "preset:owl" to "🦉",
        "preset:whale" to "🐳",
        "preset:rocket" to "🚀",
        "preset:spark" to "⚡",
    )

    fun generateRandomName(): String {
        val adj = ADJECTIVES.random()
        val noun = NOUNS.random()
        val num = (10..99).random()
        return "$adj$noun$num"
    }

    fun generateClientId(): String = "and_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    fun generatePeerId(): String = "ap_${UUID.randomUUID().toString().replace("-", "").take(14)}"

    fun presetEmoji(avatar: String?): String {
        if (avatar.isNullOrBlank()) return "⚡"
        return PRESET_AVATARS.firstOrNull { it.first == avatar }?.second ?: "✨"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return if (digitGroups == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun formatLimitMB(mb: Int): String {
        if (mb <= 0) return "不限制"
        if (mb >= 1024) {
            val gb = mb / 1024.0
            return if (mb % 1024 == 0) "${mb / 1024}GB" else String.format(Locale.US, "%.1fGB", gb)
        }
        return "${mb}MB"
    }

    fun formatEta(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0) return ""
        val total = seconds.toInt()
        val m = total / 60
        val s = total % 60
        return if (m > 0) "${m}m${s}s" else "${s}s"
    }

    fun formatClock(timestampMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }

    fun formatExpiry(expiresAtMs: Long): String {
        val remainMs = expiresAtMs - System.currentTimeMillis()
        if (remainMs <= 0) return "即将过期"
        val hours = remainMs / 3_600_000L
        return if (hours >= 24) "剩余 ${hours / 24} 天" else "剩余 ${hours.coerceAtLeast(1)} 小时"
    }

    // 兼容完整链接（https://chat.baytale.com/?room=849205、/r/849205）、baychat://room/849205 与纯口令
    fun extractRoomCode(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        runCatching {
            if (trimmed.contains("://") || trimmed.contains("?room=") || trimmed.contains("/r/")) {
                val normalized = when {
                    trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true) ||
                        trimmed.startsWith("baychat://", ignoreCase = true) -> trimmed
                    trimmed.startsWith("/") -> "https://chat.baytale.com$trimmed"
                    else -> "https://$trimmed"
                }
                val uri = URI(normalized)
                uri.rawQuery?.split("&")?.forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2 && kv[0] == "room" && kv[1].isNotBlank()) {
                        return URLDecoder.decode(kv[1], "UTF-8").trim().lowercase(Locale.ROOT)
                    }
                }
                val segments = uri.path.orEmpty().split("/").filter { it.isNotBlank() }
                if (uri.scheme.equals("baychat", ignoreCase = true) &&
                    uri.host.equals("room", ignoreCase = true) &&
                    segments.isNotEmpty()
                ) {
                    return segments.first().trim().lowercase(Locale.ROOT)
                }
                if (segments.size >= 2 && segments[0] == "r") {
                    return segments[1].trim().lowercase(Locale.ROOT)
                }
            }
        }
        return trimmed.lowercase(Locale.ROOT)
    }

    // 兼容旧版网页端防窥密信前缀，避免历史缓存展示乱码
    fun decodeLegacySecret(raw: String?): String {
        val text = raw ?: return ""
        val prefix = "::BAYCHAT_SECRET::"
        return if (text.startsWith(prefix)) text.removePrefix(prefix) else text
    }

    // 离线生成清晰邀请二维码 Bitmap
    fun generateQrBitmap(content: String, sizePx: Int = 560): Bitmap? {
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val offset = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[offset + x] = if (matrix[x, y]) Color.parseColor("#1F1629") else Color.WHITE
                }
            }
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            }
        }.getOrNull()
    }

    fun bytesToHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }
}
