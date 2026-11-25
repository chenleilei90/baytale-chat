package com.baytale.baychat

import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.PublicRoomsResponse
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

    @Test
    fun testAirDefaultThemeAndPublicRoomsAndReleaseJson() {
        assertEquals(AppThemeMode.AIR, AppThemeMode.fromId(null))
        assertEquals(AppThemeMode.AIR, AppThemeMode.fromId("unknown"))
        assertEquals(AppThemeMode.NEON, AppThemeMode.fromId("neon"))

        val publicJson = """
            {
              "count": 1,
              "rooms": [
                {
                  "code": "602819",
                  "mode": "ephemeral",
                  "hasPassword": false,
                  "allowRandom": true,
                  "topic": "☕ 闲聊摸鱼",
                  "maxPeers": 6,
                  "peerCount": 2,
                  "retentionDays": 1,
                  "maxFileSizeMB": 0
                }
              ]
            }
        """.trimIndent()
        val parsedPublic = json.decodeFromString(PublicRoomsResponse.serializer(), publicJson)
        assertEquals(1, parsedPublic.count)
        assertEquals("602819", parsedPublic.rooms.first().code)
        assertTrue(parsedPublic.rooms.first().allowRandom)
        assertEquals("☕ 闲聊摸鱼", parsedPublic.rooms.first().topic)

        val relJson = """
            {
              "versionCode": 3,
              "versionName": "1.1.0",
              "apkUrl": "https://chat.baytale.com/apk/BayChat-v1.1.0.apk",
              "sha256": "abc123",
              "sizeBytes": 45000000,
              "releaseNotes": "清风极简与随机串门"
            }
        """.trimIndent()
        val rel = json.decodeFromString(AppReleaseInfo.serializer(), relJson)
        assertEquals(3, rel.versionCode)
        assertEquals("1.1.0", rel.versionName)
        // 验证旧版 version.json 缺少企业级 OTA 新字段时的向后兼容默认值
        assertEquals("", rel.fallbackUrl)
        assertEquals(0, rel.minSupportedCode)
        assertEquals(false, rel.forceUpdate)

        val enterpriseRelJson = """
            {
              "versionCode": 8,
              "versionName": "1.1.5",
              "apkUrl": "https://chat.baytale.com/apk/BayChat-v1.1.5.apk",
              "fallbackUrl": "https://github.com/baytale/baychat/releases/download/v1.1.5/BayChat-v1.1.5.apk",
              "sha256": "deadbeef",
              "sizeBytes": 48000000,
              "releaseNotes": "企业级 OTA 自动更新",
              "minSupportedCode": 5,
              "forceUpdate": true
            }
        """.trimIndent()
        val enterpriseRel = json.decodeFromString(AppReleaseInfo.serializer(), enterpriseRelJson)
        assertEquals(8, enterpriseRel.versionCode)
        assertEquals("https://github.com/baytale/baychat/releases/download/v1.1.5/BayChat-v1.1.5.apk", enterpriseRel.fallbackUrl)
        assertEquals(5, enterpriseRel.minSupportedCode)
        assertTrue(enterpriseRel.forceUpdate)
    }
}
