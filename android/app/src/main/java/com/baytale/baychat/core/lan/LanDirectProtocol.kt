package com.baytale.baychat.core.lan

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 离线局域网 TCP Socket 直连帧协议（无中心服务器）：
 * [4 字节 Header 长度 (Int)] + [UTF-8 JSON Header] + [可选的 fileSize 字节文件流]
 */
object LanDirectProtocol {
    const val FRAME_TEXT = 1
    const val FRAME_FILE = 2
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val STREAM_BUF_SIZE = 64 * 1024

    val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class FrameHeader(
        val type: Int,
        val senderId: String = "",
        val senderName: String = "",
        val senderAvatar: String = "",
        val text: String = "",
        val fileName: String = "",
        val fileSize: Long = 0L,
        val mimeType: String = "application/octet-stream",
        val sha256: String = "",
        val timestamp: Long = System.currentTimeMillis(),
    )

    sealed class ReceivedFrame {
        data class TextFrame(val header: FrameHeader) : ReceivedFrame()
        data class FileFrame(
            val header: FrameHeader,
            val actualSha256: String,
            val verified: Boolean,
        ) : ReceivedFrame()
    }

    fun writeTextFrame(
        out: OutputStream,
        senderId: String,
        senderName: String,
        senderAvatar: String,
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val header = FrameHeader(
            type = FRAME_TEXT,
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            text = text,
            timestamp = timestamp,
        )
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val dataOut = DataOutputStream(out)
        dataOut.writeInt(headerBytes.size)
        dataOut.write(headerBytes)
        dataOut.flush()
    }

    fun writeFileFrame(
        out: OutputStream,
        senderId: String,
        senderName: String,
        senderAvatar: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        sha256: String,
        fileStream: InputStream,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        val header = FrameHeader(
            type = FRAME_FILE,
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            sha256 = sha256,
        )
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val dataOut = DataOutputStream(out)
        dataOut.writeInt(headerBytes.size)
        dataOut.write(headerBytes)

        val buf = ByteArray(STREAM_BUF_SIZE)
        var sent = 0L
        while (true) {
            val read = fileStream.read(buf)
            if (read <= 0) break
            dataOut.write(buf, 0, read)
            sent += read
            onProgress?.invoke(sent, fileSize)
        }
        dataOut.flush()
    }

    fun readFrame(
        input: InputStream,
        fileOutputSinkProvider: ((FrameHeader) -> OutputStream)? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ReceivedFrame? {
        val dataIn = DataInputStream(input)
        val headerLen = try {
            dataIn.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) {
            throw IllegalStateException("非法局域网协议帧长度: $headerLen")
        }
        val headerBytes = ByteArray(headerLen)
        dataIn.readFully(headerBytes)
        val header = json.decodeFromString(FrameHeader.serializer(), headerBytes.toString(Charsets.UTF_8))

        if (header.type == FRAME_TEXT) {
            return ReceivedFrame.TextFrame(header)
        }
        if (header.type == FRAME_FILE) {
            val sink = fileOutputSinkProvider?.invoke(header)
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(STREAM_BUF_SIZE)
            var remaining = header.fileSize.coerceAtLeast(0L)
            var received = 0L
            try {
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = dataIn.read(buf, 0, toRead)
                    if (n < 0) throw EOFException("局域网文件流意外中断")
                    digest.update(buf, 0, n)
                    sink?.write(buf, 0, n)
                    remaining -= n
                    received += n
                    onProgress?.invoke(received, header.fileSize)
                }
                sink?.flush()
            } finally {
                runCatching { sink?.close() }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            val ok = header.sha256.isBlank() || header.sha256.equals(actualSha, ignoreCase = true)
            return ReceivedFrame.FileFrame(header = header, actualSha256 = actualSha, verified = ok)
        }
        throw IllegalStateException("未知局域网协议帧类型: ${header.type}")
    }
}
