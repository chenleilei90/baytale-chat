package com.baytale.baychat.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 房间端到端聊天记录加解密工具（AES-256-GCM）：
 * 基于房间口令与密码派生密钥，确保服务端缓存数据库与第三方均无法窥探聊天明文内容。
 */
object ChatCrypto {
    private const val PREFIX = "::E2EE1::"
    private const val IV_LEN_BYTES = 12
    private const val TAG_LEN_BITS = 128
    private const val DECRYPT_FAILED_FALLBACK = "[加密消息，无法解密]"
    private val random = SecureRandom()

    private fun deriveKey(roomCode: String, roomPassword: String): SecretKeySpec {
        val material = "baychat-e2ee-v1:${roomCode.trim().lowercase(Locale.ROOT)}:$roomPassword"
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hash, "AES")
    }

    /**
     * 使用房间密钥加密明文；空字符串原样返回。
     */
    fun encrypt(roomCode: String, roomPassword: String, plainText: String): String {
        if (plainText.isEmpty()) return ""
        val iv = ByteArray(IV_LEN_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(roomCode, roomPassword), GCMParameterSpec(TAG_LEN_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(encrypted, 0, payload, iv.size, encrypted.size)
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    /**
     * 使用房间密钥解密密文；兼容旧版未加密或旧版密信前缀内容，解密失败时返回安全占位提示。
     */
    fun decrypt(roomCode: String, roomPassword: String, raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        if (!raw.startsWith(PREFIX)) {
            return FormatUtils.decodeLegacySecret(raw)
        }
        return runCatching {
            val encoded = raw.removePrefix(PREFIX)
            val payload = Base64.getDecoder().decode(encoded)
            if (payload.size <= IV_LEN_BYTES) return DECRYPT_FAILED_FALLBACK
            val iv = payload.copyOfRange(0, IV_LEN_BYTES)
            val cipherBytes = payload.copyOfRange(IV_LEN_BYTES, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(roomCode, roomPassword), GCMParameterSpec(TAG_LEN_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrElse { DECRYPT_FAILED_FALLBACK }
    }
}
