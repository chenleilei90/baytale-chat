package com.baytale.baychat

import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.util.FormatUtils
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 跨端协议兼容单元测试：验证房间码提取、信令 JSON 兼容与二进制帧头大端序编码
class ProtocolCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testFormatBytesAndLimits() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("512 B", FormatUtils.formatBytes(512))
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
        assertEquals("500MB", FormatUtils.formatLimitMB(500))
        assertEquals("1GB", FormatUtils.formatLimitMB(1024))
        assertEquals("不限制", FormatUtils.formatLimitMB(0))
    }

    @Test
    fun testExtractRoomCodeFromLinksAndRawCodes() {
        assertEquals("849205", FormatUtils.extractRoomCode("849205"))
        assertEquals("849205", FormatUtils.extractRoomCode("https://chat.baytale.com/?room=849205"))
        assertEquals("my-desk", FormatUtils.extractRoomCode("https://chat.baytale.com/r/MY-DESK"))
        assertEquals("sweet-888", FormatUtils.extractRoomCode("baychat://room/sweet-888"))
        assertEquals("992211", FormatUtils.extractRoomCode("/r/992211"))
    }

    @Test
    fun testLegacySecretDecode() {
        assertEquals("hello", FormatUtils.decodeLegacySecret("::BAYCHAT_SECRET::hello"))
        assertEquals("plain", FormatUtils.decodeLegacySecret("plain"))
    }

    @Test
    fun testSignalEnvelopeForwardCompatibility() {
        val raw = """
            {
              "type": "joined",
              "roomCode": "849205",
              "unknownFutureField": 12345,
              "room": {
                "code": "849205",
                "mode": "ephemeral",
                "hasPassword": false,
                "maxPeers": 6,
                "peerCount": 1,
                "retentionDays": 1,
                "maxFileSizeMB": 500
              },
              "history": {
                "retentionDays": 1,
                "messageCount": 0
              }
            }
        """.trimIndent()
        val env = json.decodeFromString(SignalEnvelope.serializer(), raw)
        assertEquals("joined", env.type)
        assertEquals("849205", env.room?.code)
        assertEquals(500, env.room?.maxFileSizeMB)
    }

    @Test
    fun testBinaryFileFrameHeaderMatchesWebEndianness() {
        // 验证与 web/src/lib/webrtc.ts 一致的 8 字节大端序帧头：[streamId uint32 BE][chunkIndex uint32 BE]
        val frame = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        frame.putInt(7)
        frame.putInt(-1) // 0xFFFFFFFF EOF_CHUNK_INDEX
        frame.put(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val arr = frame.array()
        assertEquals(0x00.toByte(), arr[0])
        assertEquals(0x07.toByte(), arr[3])
        assertEquals(0xFF.toByte(), arr[4])
        assertEquals(0xFF.toByte(), arr[7])
        assertTrue(FormatUtils.bytesToHex(byteArrayOf(0x0A, 0xFF.toByte())) == "0aff")
    }
}
