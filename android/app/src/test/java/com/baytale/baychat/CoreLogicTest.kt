package com.baytale.baychat

import com.baytale.baychat.core.model.CachedMessage
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.network.OutboundSignalQueue
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.TransferResumeUtils
import com.baytale.baychat.core.util.classifyPlaneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

// 核心业务逻辑纯 JVM 单元测试：验证 resumeKey 大小文件计算、稳定 peerId 派生、媒体安全白名单与断线消息队列
class CoreLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testResumeKeyForSmallFileMatchesFullHashFormula() {
        val data = ByteArray(128 * 1024) { idx -> (idx * 13 and 0xFF).toByte() }
        val file = tempFolder.newFile("small_archive.zip").apply { writeBytes(data) }

        val fromFile = TransferResumeUtils.computeResumeKeyFromFile(file)
        val fromBytes = TransferResumeUtils.computeResumeKeyFromBytes("small_archive.zip", data)
        val fromStream = file.inputStream().use {
            TransferResumeUtils.computeResumeKeyFromStream("small_archive.zip", data.size.toLong(), it)
        }
        val expectedFullHex = TransferResumeUtils.sha256Hex(data)
        val expectedKey = TransferResumeUtils.sha256Hex("small_archive.zip|${data.size}|$expectedFullHex")

        assertEquals(expectedKey, fromFile)
        assertEquals(expectedKey, fromBytes)
        assertEquals(expectedKey, fromStream)

        // 同名同大小但内容不同的文件必须产生完全不同的 resumeKey（修复 B4）
        val modified = data.copyOf().apply { this[size / 2] = (this[size / 2].toInt() xor 0x01).toByte() }
        val otherKey = TransferResumeUtils.computeResumeKeyFromBytes("small_archive.zip", modified)
        assertNotEquals(fromBytes, otherKey)
    }

    @Test
    fun testResumeKeyForLargeFileMatchesHeadTail1MiBFormula() {
        val totalSize = 3L * 1024L * 1024L // 3 MiB >= 2 MiB
        val file = tempFolder.newFile("large_video.mp4")
        val head = ByteArray(TransferResumeUtils.ONE_MIB) { idx -> ((idx + 3) and 0xFF).toByte() }
        val middle = ByteArray(TransferResumeUtils.ONE_MIB) { 0x55.toByte() }
        val tail = ByteArray(TransferResumeUtils.ONE_MIB) { idx -> ((idx * 7 + 19) and 0xFF).toByte() }

        RandomAccessFile(file, "rw").use { raf ->
            raf.write(head)
            raf.write(middle)
            raf.write(tail)
        }

        val fromFile = TransferResumeUtils.computeResumeKeyFromFile(file, "large_video.mp4")
        val fromStream = file.inputStream().use {
            TransferResumeUtils.computeResumeKeyFromStream("large_video.mp4", totalSize, it)
        }
        val expectedHeadHex = TransferResumeUtils.sha256Hex(head)
        val expectedTailHex = TransferResumeUtils.sha256Hex(tail)
        val expectedKey = TransferResumeUtils.sha256Hex("large_video.mp4|$totalSize|$expectedHeadHex|$expectedTailHex")

        assertEquals(expectedKey, fromFile)
        assertEquals(expectedKey, fromStream)

        // 修改尾部字节必须改变 resumeKey
        val file2 = File(tempFolder.root, "large_video_diff_tail.mp4")
        file.copyTo(file2, overwrite = true)
        RandomAccessFile(file2, "rw").use { raf ->
            raf.seek(totalSize - 10L)
            raf.write(0x99)
        }
        val diffKey = TransferResumeUtils.computeResumeKeyFromFile(file2, "large_video.mp4")
        assertNotEquals(fromFile, diffKey)
    }

    @Test
    fun testDeriveStablePeerId() {
        val p1 = TransferResumeUtils.derivePeerId("and_client_001", "849205")
        val p2 = TransferResumeUtils.derivePeerId("and_client_001", "849205")
        val pCaseRoom = TransferResumeUtils.derivePeerId("and_client_001", "DESIGN-LAB")
        val pCaseRoomLower = TransferResumeUtils.derivePeerId("and_client_001", "design-lab")
        val pOtherClient = TransferResumeUtils.derivePeerId("and_client_002", "849205")

        assertTrue(p1.startsWith("p_"))
        assertEquals(18, p1.length)
        assertEquals(p1, p2)
        assertEquals(pCaseRoom, pCaseRoomLower)
        assertNotEquals(p1, pOtherClient)
    }

    @Test
    fun testStrictMediaTypeWhitelistDefendsSpoofing() {
        // 合法图片
        assertTrue(TransferResumeUtils.isTrustedImage("photo.jpg", "image/jpeg"))
        assertTrue(TransferResumeUtils.isTrustedImage("pic.HEIC", "image/heic"))
        assertTrue(TransferResumeUtils.isTrustedImage("anim.webp", "image/webp"))

        // 伪造 isImage 绕过检查（B2 防御）
        assertFalse(TransferResumeUtils.isTrustedImage("malware.apk", "image/png"))
        assertFalse(TransferResumeUtils.isTrustedImage("xss.svg", "image/svg+xml"))
        assertFalse(TransferResumeUtils.isTrustedImage("script.html", "image/jpeg"))
        assertFalse(TransferResumeUtils.isTrustedImage("photo.png", "application/vnd.android.package-archive"))

        // 合法音频
        assertTrue(TransferResumeUtils.isTrustedAudio("voice.m4a", "audio/mp4"))
        assertTrue(TransferResumeUtils.isTrustedAudio("voice.webm", "audio/webm"))
        assertTrue(TransferResumeUtils.isTrustedAudio("voice.webm", "video/webm"))
        assertTrue(TransferResumeUtils.isTrustedAudio("clip.ogg", "application/ogg"))
        assertFalse(TransferResumeUtils.isTrustedAudio("payload.exe", "audio/mpeg"))
    }

    @Test
    fun testOutboundSignalQueueCachingCapacityAnd10MinExpiry() {
        val nowMs = AtomicLong(1_000_000L)
        val queue = OutboundSignalQueue(
            maxSize = 200,
            ttlMs = 10L * 60L * 1000L,
            clock = { nowMs.get() },
        )

        // join / ping 不入队
        queue.enqueue(SignalEnvelope(type = "join", roomCode = "111111"))
        queue.enqueue(SignalEnvelope(type = "ping"))
        assertEquals(0, queue.size())

        // 1) 在 t=1_000_000 放入 1 条即将过期的消息
        queue.enqueue(
            SignalEnvelope(
                type = "cache_message",
                message = CachedMessage(id = "msg_old", kind = "text", senderId = "p1", senderName = "A", text = "old"),
            )
        )

        // 前进 9 分钟后放入新消息
        nowMs.addAndGet(9L * 60L * 1000L)
        queue.enqueue(
            SignalEnvelope(
                type = "cache_message",
                message = CachedMessage(id = "msg_fresh", kind = "text", senderId = "p1", senderName = "A", text = "fresh"),
            )
        )

        // 再前进 2 分钟（总计 11 分钟，msg_old 超过 10 分钟应被淘汰，msg_fresh 仅 2 分钟应正常补发）
        nowMs.addAndGet(2L * 60L * 1000L)
        val drained = queue.drainValid()
        assertEquals(listOf("msg_old"), drained.expiredMessageIds)
        assertEquals(1, drained.readyToSend.size)
        assertEquals("msg_fresh", drained.readyToSend.first().message?.id)
        assertEquals(0, queue.size())

        // 2) 容量上限 200 条验证：塞入 205 条时最旧 5 条应被淘汰
        val dropped = mutableListOf<String>()
        for (i in 1..205) {
            val evicted = queue.enqueue(
                SignalEnvelope(
                    type = "cache_message",
                    message = CachedMessage(id = "m_$i", kind = "text", senderId = "p1", senderName = "A", text = "$i"),
                )
            )
            if (evicted != null) dropped.add(evicted)
        }
        assertEquals(200, queue.size())
        assertEquals(listOf("m_1", "m_2", "m_3", "m_4", "m_5"), dropped)
        val allFresh = queue.drainValid()
        assertEquals("m_6", allFresh.readyToSend.first().message?.id)
        assertEquals("m_205", allFresh.readyToSend.last().message?.id)
    }

    @Test
    fun testFormatNudgeTextAllBranches() {
        assertEquals(
            "我拍了拍自己",
            FormatUtils.formatNudgeText("我", "我", isSelfSender = true, isSelfTarget = true),
        )
        assertEquals(
            "我拍了拍\"小明\"",
            FormatUtils.formatNudgeText("我", "小明", isSelfSender = true, isSelfTarget = false),
        )
        assertEquals(
            "\"小红\" 拍了拍我",
            FormatUtils.formatNudgeText("小红", "我", isSelfSender = false, isSelfTarget = true),
        )
        assertEquals(
            "\"小红\" 拍了拍自己",
            FormatUtils.formatNudgeText("小红", "小红", isSelfSender = false, isSelfTarget = false, isSenderTargetSelf = true),
        )
        assertEquals(
            "\"小红\" 拍了拍 \"小刚\"",
            FormatUtils.formatNudgeText("小红", "小刚", isSelfSender = false, isSelfTarget = false, isSenderTargetSelf = false),
        )
    }

    @Test
    fun testClassifyPlaneKindCoversAllVariants() {
        assertEquals("apk", classifyPlaneKind("BayChat-v1.4.0.apk"))
        assertEquals("image", classifyPlaneKind("photo.webp", "image/webp"))
        assertEquals("video", classifyPlaneKind("clip.mp4"))
        assertEquals("audio", classifyPlaneKind("voice.m4a"))
        assertEquals("archive", classifyPlaneKind("backup.7z"))
        assertEquals("doc", classifyPlaneKind("manual.pdf"))
        assertEquals("doc", classifyPlaneKind("notes.md"))
        assertEquals("file", classifyPlaneKind("installer.bin"))
    }
}
