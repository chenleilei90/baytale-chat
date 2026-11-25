package com.baytale.baychat.core.util

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

// 纯 Kotlin 断点指纹、稳定 PeerId 派生与媒体安全白名单判定工具（零 Android 依赖，可直接 JVM 单测）
object TransferResumeUtils {
    const val ONE_MIB: Int = 1024 * 1024
    const val TWO_MIB: Long = 2L * 1024L * 1024L
    const val IMAGE_AUTO_ACCEPT_MAX_BYTES: Long = 25L * 1024L * 1024L
    const val AUDIO_AUTO_ACCEPT_MAX_BYTES: Long = 25L * 1024L * 1024L

    private val IMAGE_MIME_WHITELIST = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/gif",
        "image/webp",
        "image/heic",
        "image/heif",
    )

    private val IMAGE_EXT_WHITELIST = setOf(
        "jpg",
        "jpeg",
        "png",
        "gif",
        "webp",
        "heic",
        "heif",
    )

    private val AUDIO_EXT_WHITELIST = setOf(
        "m4a",
        "aac",
        "ogg",
        "webm",
        "mp3",
        "wav",
    )

    private val VIDEO_EXT_WHITELIST = setOf(
        "mp4",
        "mov",
        "mkv",
        "avi",
        "3gp",
        "m4v",
        "webm",
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return FormatUtils.bytesToHex(digest)
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    // B9：由稳定 clientId + roomCode 派生固定房间内 peerId，避免重进房或重连导致历史归属错乱
    fun derivePeerId(clientId: String, roomCode: String): String {
        val cleanClient = clientId.trim()
        val cleanRoom = roomCode.trim().lowercase(Locale.ROOT)
        return "p_" + sha256Hex("$cleanClient:$cleanRoom").take(16)
    }

    // B4：根据文件头尾摘要（<2MiB 为全量摘要）计算跨端一致的 resumeKey
    fun computeResumeKey(
        fileName: String,
        fileSize: Long,
        headBytes: ByteArray,
        tailBytes: ByteArray = ByteArray(0),
    ): String {
        val cleanName = fileName.trim()
        return if (fileSize < TWO_MIB) {
            val fullHex = sha256Hex(headBytes)
            sha256Hex("$cleanName|$fileSize|$fullHex")
        } else {
            val headHex = sha256Hex(headBytes)
            val tailHex = sha256Hex(tailBytes)
            sha256Hex("$cleanName|$fileSize|$headHex|$tailHex")
        }
    }

    // B4：针对字节数组（如压缩后图片/语音内存块）计算 resumeKey
    fun computeResumeKeyFromBytes(fileName: String, bytes: ByteArray): String {
        val size = bytes.size.toLong()
        return if (size < TWO_MIB) {
            computeResumeKey(fileName, size, bytes)
        } else {
            val head = bytes.copyOfRange(0, ONE_MIB)
            val tail = bytes.copyOfRange(bytes.size - ONE_MIB, bytes.size)
            computeResumeKey(fileName, size, head, tail)
        }
    }

    // B4：针对本地 File 流式计算 resumeKey（大文件 O(1) seek，仅读取前 1MiB 与后 1MiB）
    fun computeResumeKeyFromFile(file: File, fileName: String = file.name): String {
        val size = file.length().coerceAtLeast(0L)
        if (size < TWO_MIB) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            val fullHex = FormatUtils.bytesToHex(md.digest())
            return sha256Hex("${fileName.trim()}|$size|$fullHex")
        }
        val head = ByteArray(ONE_MIB)
        val tail = ByteArray(ONE_MIB)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(0L)
            readFullyCompat(raf, head)
            raf.seek((size - ONE_MIB).coerceAtLeast(0L))
            readFullyCompat(raf, tail)
        }
        return computeResumeKey(fileName, size, head, tail)
    }

    // 流式读取任意 InputStream 计算 resumeKey（兼容无法 seek 的虚拟 ContentProvider 流）
    fun computeResumeKeyFromStream(
        fileName: String,
        fileSize: Long,
        inputStream: InputStream,
    ): String {
        if (fileSize in 0L until TWO_MIB) {
            val md = MessageDigest.getInstance("SHA-256")
            var actualSize = 0L
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = inputStream.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
                actualSize += r
            }
            val resolvedSize = if (fileSize > 0L) fileSize else actualSize
            val fullHex = FormatUtils.bytesToHex(md.digest())
            return sha256Hex("${fileName.trim()}|$resolvedSize|$fullHex")
        }
        val headMd = MessageDigest.getInstance("SHA-256")
        val ring = ByteArray(ONE_MIB)
        var ringPos = 0
        var totalRead = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = inputStream.read(buf)
            if (r <= 0) break
            if (totalRead < ONE_MIB) {
                val headTake = minOf(r.toLong(), ONE_MIB - totalRead).toInt()
                headMd.update(buf, 0, headTake)
            }
            for (i in 0 until r) {
                ring[ringPos] = buf[i]
                ringPos = (ringPos + 1) % ONE_MIB
            }
            totalRead += r
        }
        val tailMd = MessageDigest.getInstance("SHA-256")
        val tailCount = minOf(totalRead, ONE_MIB.toLong()).toInt()
        if (totalRead >= ONE_MIB) {
            // 按环形缓冲写入顺序喂入最后 1MiB
            val firstPart = ONE_MIB - ringPos
            tailMd.update(ring, ringPos, firstPart)
            if (ringPos > 0) {
                tailMd.update(ring, 0, ringPos)
            }
        } else if (tailCount > 0) {
            tailMd.update(ring, 0, tailCount)
        }
        val resolvedSize = if (fileSize > 0L) fileSize else totalRead
        val headHex = FormatUtils.bytesToHex(headMd.digest())
        val tailHex = FormatUtils.bytesToHex(tailMd.digest())
        return sha256Hex("${fileName.trim()}|$resolvedSize|$headHex|$tailHex")
    }

    private fun readFullyCompat(raf: RandomAccessFile, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = raf.read(target, offset, target.size - offset)
            if (read <= 0) break
            offset += read
        }
    }

    // B2：接收端严格按 MIME 白名单与扩展名校验是否为可安全自动预览的图片
    fun isTrustedImage(fileName: String, mimeType: String?): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return ext in IMAGE_EXT_WHITELIST && mime in IMAGE_MIME_WHITELIST
    }

    // B2：接收端严格按音频扩展名与 MIME 白名单判定是否为语音/音频文件
    fun isTrustedAudio(fileName: String, mimeType: String?): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()
        if (ext !in AUDIO_EXT_WHITELIST) return false
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.isBlank() || mime == "application/octet-stream") return true
        if (mime.startsWith("audio/") || mime == "application/ogg") return true
        return ext == "webm" && mime == "video/webm"
    }

    // B7：媒体路由判定是否为视频（排除纯语音 webm）
    fun isVideoMedia(fileName: String, mimeType: String?, isExplicitAudio: Boolean = false): Boolean {
        if (isExplicitAudio) return false
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime.startsWith("audio/")) return false
        return mime.startsWith("video/") || (ext in VIDEO_EXT_WHITELIST && ext != "webm")
    }

    fun isHeicOrHeif(fileName: String, mimeType: String?): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return ext == "heic" || ext == "heif" || mime == "image/heic" || mime == "image/heif"
    }

    fun partFileNameForKey(resumeKey: String): String =
        "resume_${resumeKey.trim().lowercase(Locale.ROOT).take(24)}.part"

    fun legacyPartFileName(fileName: String, fileSize: Long): String {
        val keyHash = sha256Hex("$fileName::$fileSize").take(20)
        return "resume_$keyHash.part"
    }
}
