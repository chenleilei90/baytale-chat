package com.baytale.baychat

import com.baytale.baychat.core.util.ChatCrypto
import com.baytale.baychat.core.util.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端聊天加密与时间戳归一化单元测试。
 */
class ChatCryptoTest {

    @Test
    fun encryptAndDecryptRoundTripChineseAndEmoji() {
        val plain = "你好BayChat！这是一条私密聊天记录\uD83D\uDE04\uD83D\uDD12"
        val cipher1 = ChatCrypto.encrypt("Room888", "pass123", plain)
        val cipher2 = ChatCrypto.encrypt("room888", "pass123", plain)

        assertTrue(cipher1.startsWith("::E2EE1::"))
        assertTrue(cipher2.startsWith("::E2EE1::"))
        // 每次随机 IV 保证密文不同
        assertNotEquals(cipher1, cipher2)
        // 解密一致（roomCode 大小写与首尾空白归一化）
        assertEquals(plain, ChatCrypto.decrypt("room888", "pass123", cipher1))
        assertEquals(plain, ChatCrypto.decrypt(" ROOM888 ", "pass123", cipher2))
    }

    @Test
    fun wrongKeyOrTamperedCipherReturnsFallback() {
        val cipher = ChatCrypto.encrypt("849205", "secretA", "绝密内容")
        assertEquals("[加密消息，无法解密]", ChatCrypto.decrypt("849205", "secretB", cipher))
        assertEquals("[加密消息，无法解密]", ChatCrypto.decrypt("999999", "secretA", cipher))
        assertEquals("[加密消息，无法解密]", ChatCrypto.decrypt("849205", "secretA", "::E2EE1::invalid!!!"))
    }

    @Test
    fun legacyPlainAndSecretPrefixRemainReadable() {
        assertEquals("旧版明文", ChatCrypto.decrypt("849205", "", "旧版明文"))
        assertEquals("旧版密信", ChatCrypto.decrypt("849205", "", "::BAYCHAT_SECRET::旧版密信"))
        assertEquals("", ChatCrypto.decrypt("849205", "", null))
    }

    @Test
    fun normalizeEpochMsPrevents1970TimestampBug() {
        val fallback = 1_795_712_000_000L
        assertEquals(fallback, FormatUtils.normalizeEpochMs(0L, fallback))
        assertEquals(fallback, FormatUtils.normalizeEpochMs(-5L, fallback))
        assertEquals(fallback, FormatUtils.normalizeEpochMs(12345L, fallback))
        // 10 位秒级时间戳应自动转为 13 位毫秒
        assertEquals(1_795_712_000_000L, FormatUtils.normalizeEpochMs(1_795_712_000L, fallback))
        // 正常 13 位毫秒保持原值
        assertEquals(1_795_712_345_678L, FormatUtils.normalizeEpochMs(1_795_712_345_678L, fallback))
    }
}
