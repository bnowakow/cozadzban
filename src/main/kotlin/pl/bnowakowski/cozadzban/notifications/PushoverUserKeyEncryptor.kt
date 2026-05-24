// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class PushoverUserKeyEncryptor(
    private val properties: NotificationProperties,
) {
    private val random = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(GCM_IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(encryptedText: String): String {
        val payload = Base64.getDecoder().decode(encryptedText)
        require(payload.size > GCM_IV_BYTES) { "Encrypted Pushover key payload is invalid" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKeySpec {
        val configured = properties.encryptionKey.trim()
        require(configured.isNotBlank()) { "app.notifications.encryption-key must be configured" }
        val keyBytes = decodeKey(configured)
        require(keyBytes.size in AES_KEY_SIZES) {
            "app.notifications.encryption-key must decode to 16, 24, or 32 bytes"
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun decodeKey(value: String): ByteArray {
        if (value.length in setOf(32, 48, 64) && value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            return value.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }
        val decoded = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
        if (decoded?.size in AES_KEY_SIZES) return decoded!!
        return value.toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val AES_KEY_SIZES = setOf(16, 24, 32)
    }
}
