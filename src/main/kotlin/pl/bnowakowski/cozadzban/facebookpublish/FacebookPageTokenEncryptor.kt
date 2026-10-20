// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class FacebookPageTokenEncryptor(private val properties: FacebookPageProperties) {
    private val random = SecureRandom()

    fun encrypt(token: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 12) { "Invalid encrypted Page token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKeySpec {
        val value = properties.encryptionKey.trim()
        val bytes = if (value.length in setOf(32, 48, 64) && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            runCatching { Base64.getDecoder().decode(value) }.getOrNull()
                ?.takeIf { it.size in setOf(16, 24, 32) }
                ?: value.toByteArray(Charsets.UTF_8)
        }
        require(bytes.size in setOf(16, 24, 32)) { "app.facebook-page.encryption-key must decode to 16, 24, or 32 bytes" }
        return SecretKeySpec(bytes, "AES")
    }
}
