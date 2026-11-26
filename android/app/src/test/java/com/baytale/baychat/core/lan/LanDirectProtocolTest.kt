package com.baytale.baychat.core.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanDirectProtocolTest {
    @Test
    fun textFrameRoundTripOverLoopbackSocket() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<LanDirectProtocol.ReceivedFrame?> {
                server.accept().use { client ->
                    LanDirectProtocol.readFrame(client.getInputStream())
                }
            }
            Socket("127.0.0.1", server.localPort).use { sock ->
                LanDirectProtocol.writeTextFrame(
                    out = sock.getOutputStream(),
                    senderId = "u_test_1",
                    senderName = "测试手机",
                    senderAvatar = "preset:panda",
                    text = "断外网局域网直连你好",
                    timestamp = 1795700000000L,
                )
            }
            val frame = future.get(5, TimeUnit.SECONDS) as LanDirectProtocol.ReceivedFrame.TextFrame
            assertEquals("u_test_1", frame.header.senderId)
            assertEquals("测试手机", frame.header.senderName)
            assertEquals("断外网局域网直连你好", frame.header.text)
        } finally {
            pool.shutdownNow()
            runCatching { server.close() }
        }
    }

    @Test
    fun fileFrameRoundTripWithSha256Verification() {
        val payload = ByteArray(256 * 1024) { idx -> ((idx * 31) and 0xFF).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
        val receivedBytes = ByteArrayOutputStream()

        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<LanDirectProtocol.ReceivedFrame?> {
                server.accept().use { client ->
                    LanDirectProtocol.readFrame(
                        input = client.getInputStream(),
                        fileOutputSinkProvider = { receivedBytes },
                    )
                }
            }
            Socket("127.0.0.1", server.localPort).use { sock ->
                LanDirectProtocol.writeFileFrame(
                    out = sock.getOutputStream(),
                    senderId = "u_test_2",
                    senderName = "发送端",
                    senderAvatar = "preset:fox",
                    fileName = "lan_sample.bin",
                    fileSize = payload.size.toLong(),
                    mimeType = "application/octet-stream",
                    sha256 = expectedSha,
                    fileStream = ByteArrayInputStream(payload),
                )
            }
            val frame = future.get(5, TimeUnit.SECONDS) as LanDirectProtocol.ReceivedFrame.FileFrame
            assertTrue(frame.verified)
            assertEquals(expectedSha, frame.actualSha256)
            assertEquals("lan_sample.bin", frame.header.fileName)
            assertArrayEquals(payload, receivedBytes.toByteArray())
        } finally {
            pool.shutdownNow()
            runCatching { server.close() }
        }
    }
}
