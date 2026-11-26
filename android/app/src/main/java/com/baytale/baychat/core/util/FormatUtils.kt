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

    /**
     * 归一化时间戳为 13 位毫秒：拦截 <= 0 或异常小值防止出现 1970 年时间，并兼容 10 位秒级时间戳。
     */
    fun normalizeEpochMs(ts: Long, fallbackMs: Long = System.currentTimeMillis()): Long {
        return when {
            ts <= 0L -> fallbackMs
            ts in 1_000_000_000L..9_999_999_999L -> ts * 1000L
            ts < 1_000_000_000L -> fallbackMs
            else -> ts
        }
    }

    fun formatClock(timestampMs: Long): String {
        val safeTs = normalizeEpochMs(timestampMs)
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(safeTs))
    }

    fun formatExpiry(expiresAtMs: Long): String {
        val remainMs = expiresAtMs - System.currentTimeMillis()
        if (remainMs <= 0) return "即将过期"
        val hours = remainMs / 3_600_000L
        return if (hours >= 24) "剩余 ${hours / 24} 天" else "剩余 ${hours.coerceAtLeast(1)} 小时"
    }

    fun formatRelativeTime(timestampMs: Long): String {
        val diffSec = ((System.currentTimeMillis() - timestampMs) / 1000L).coerceAtLeast(0L)
        if (diffSec < 60L) return "刚刚"
        val mins = diffSec / 60L
        if (mins < 60L) return "${mins} 分钟前"
        val hours = mins / 60L
        if (hours < 24L) return "${hours} 小时前"
        val days = hours / 24L
        if (days < 30L) return "${days} 天前"
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestampMs))
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

    // UX-23：安全拆分文件名与扩展名，供 UI 做中间省略布局并保证扩展名完整可见
    fun splitFileNameAndExt(name: String): Pair<String, String> {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0 || lastDot >= name.length - 1) {
            return name to ""
        }
        val ext = name.substring(lastDot + 1)
        if (ext.length > 8 || !ext.all { it.isLetterOrDigit() }) {
            return name to ""
        }
        return name.substring(0, lastDot) to name.substring(lastDot)
    }

    // UX-24：严格从剪贴板提取合法房间码（仅匹配已知邀请链接或纯粹房间码格式，拒绝普通中文长句）
    fun extractValidRoomCodeFromClipboard(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val codePattern = Regex("^[a-zA-Z0-9_-]{2,32}$")
        if (codePattern.matches(trimmed)) {
            return trimmed.lowercase(Locale.ROOT)
        }
        val looksLikeInviteLink = trimmed.contains("baychat://", ignoreCase = true) ||
            trimmed.contains("?room=", ignoreCase = true) ||
            trimmed.contains("/r/", ignoreCase = true)
        if (!looksLikeInviteLink) return null
        val extracted = extractRoomCode(trimmed)
        return if (codePattern.matches(extracted)) extracted else null
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

    /**
     * 格式化「拍一拍」系统提示文案。
     */
    fun formatNudgeText(
        fromName: String,
        targetName: String,
        isSelfSender: Boolean,
        isSelfTarget: Boolean,
        isSenderTargetSelf: Boolean = false,
    ): String = when {
        isSelfSender && isSelfTarget -> "我拍了拍自己"
        isSelfSender -> "我拍了拍\"$targetName\""
        isSelfTarget -> "\"$fromName\" 拍了拍我"
        isSenderTargetSelf -> "\"$fromName\" 拍了拍自己"
        else -> "\"$fromName\" 拍了拍 \"$targetName\""
    }
}
