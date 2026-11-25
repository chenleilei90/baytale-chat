package com.baytale.baychat

import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.AppearanceMode
import com.baytale.baychat.core.model.BlockedPeer
import com.baytale.baychat.core.model.NearbyHeartbeatResponse
import com.baytale.baychat.core.model.PickupCreateResponse
import com.baytale.baychat.core.model.PickupInfo
import com.baytale.baychat.core.model.PublicRoomsResponse
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.TransferResumeUtils
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 跨端协议兼容单元测试：验证房间码提取、信令 JSON 兼容、二进制帧头、取件码/附近设备/房主令牌/屏蔽模型序列化
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
              "isOwner": true,
              "ownerToken": "otk_abc123",
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
        assertEquals(true, env.isOwner)
        assertEquals("otk_abc123", env.ownerToken)
    }

    @Test
    fun testBinaryFileFrameHeaderMatchesWebEndianness() {
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
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromId(null))
        assertEquals(AppearanceMode.DARK, AppearanceMode.fromId("dark"))

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

    @Test
    fun testPickupNearbyOwnerTokenAndBlockedPeerJsonCompatibility() {
        val pickupCreateRaw = """
            {
              "code": "491820",
              "ownerToken": "pm_secret_99",
              "expiresAt": 1790000000000,
              "files": [
                {
                  "fileId": "f_0011223344556677",
                  "name": "report.pdf",
                  "size": 204800,
                  "mime": "application/pdf"
                }
              ]
            }
        """.trimIndent()
        val pickupCreate = json.decodeFromString(PickupCreateResponse.serializer(), pickupCreateRaw)
        assertEquals("491820", pickupCreate.code)
        assertEquals("pm_secret_99", pickupCreate.ownerToken)
        assertEquals(204800L, pickupCreate.files.first().size)

        val pickupInfoRaw = """
            {
              "code": "491820",
              "files": [
                {
                  "fileId": "f_0011223344556677",
                  "name": "report.pdf",
                  "size": 204800,
                  "mime": "application/pdf",
                  "ready": true
                }
              ],
              "expiresAt": 1790000000000,
              "burnAfterRead": false,
              "downloads": 1,
              "hasPassword": true
            }
        """.trimIndent()
        val pickupInfo = json.decodeFromString(PickupInfo.serializer(), pickupInfoRaw)
        assertTrue(pickupInfo.hasPassword)
        assertEquals(1, pickupInfo.downloads)
        assertEquals("report.pdf", pickupInfo.fileName)

        val nearbyRaw = """
            {
              "devices": [
                {
                  "clientId": "web_01",
                  "name": "MacBook Chrome",
                  "avatar": "preset:rocket",
                  "device": "web"
                }
              ],
              "invites": [
                {
                  "inviteId": "inv_77",
                  "fromClientId": "web_01",
                  "fromName": "MacBook Chrome",
                  "fromAvatar": "preset:rocket",
                  "roomCode": "812345",
                  "expiresAt": 1780000060000
                }
              ]
            }
        """.trimIndent()
        val nearby = json.decodeFromString(NearbyHeartbeatResponse.serializer(), nearbyRaw)
        assertEquals(1, nearby.devices.size)
        assertEquals("web_01", nearby.devices.first().clientId)
        assertEquals("812345", nearby.invites.first().roomCode)

        val roomRaw = """
            {
              "code": "design-lab",
              "mode": "fixed",
              "ownerToken": "own_xyz",
              "isOwner": true
            }
        """.trimIndent()
        val roomSummary = json.decodeFromString(RoomSummary.serializer(), roomRaw)
        assertEquals("own_xyz", roomSummary.ownerToken)
        assertTrue(roomSummary.isOwner)
        assertFalse(roomSummary.allowRandom)

        // 旧版持久化 SavedFixedRoom / RecentRoom 缺省新字段向后兼容
        val oldSaved = json.decodeFromString(SavedFixedRoom.serializer(), """{"code":"studio","hasPassword":false}""")
        assertEquals("", oldSaved.ownerToken)
        assertEquals("", oldSaved.alias)

        val oldRecent = json.decodeFromString(RecentRoom.serializer(), """{"code":"123456","mode":"ephemeral"}""")
        assertEquals("", oldRecent.ownerToken)

        val blocked = BlockedPeer(clientId = "c_bad_1", name = "恶意成员", blockedAt = 1780000005000L)
        val encodedBlocked = json.encodeToString(BlockedPeer.serializer(), blocked)
        val decodedBlocked = json.decodeFromString(BlockedPeer.serializer(), encodedBlocked)
        assertEquals(blocked, decodedBlocked)

        val keyHex = TransferResumeUtils.computeResumeKeyFromBytes("sample.bin", byteArrayOf(1, 2, 3, 4))
        assertEquals(64, keyHex.length)
        assertEquals("resume_${keyHex.take(24)}.part", TransferResumeUtils.partFileNameForKey(keyHex))
    }
}
