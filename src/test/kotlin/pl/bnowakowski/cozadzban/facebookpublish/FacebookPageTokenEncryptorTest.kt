// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class FacebookPageTokenEncryptorTest {
    @Test
    fun `Page token is encrypted with a fresh nonce and can be recovered`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val encryptor = FacebookPageTokenEncryptor(FacebookPageProperties(encryptionKey = key))
        val first = encryptor.encrypt("page-access-token")
        val second = encryptor.encrypt("page-access-token")

        assertNotEquals(first, second)
        assertEquals("page-access-token", encryptor.decrypt(first))
        assertEquals("page-access-token", encryptor.decrypt(second))
    }

    @Test
    fun `wrong key cannot decrypt a stored Page token`() {
        val first = FacebookPageTokenEncryptor(FacebookPageProperties(encryptionKey = "0123456789abcdef"))
        val second = FacebookPageTokenEncryptor(FacebookPageProperties(encryptionKey = "fedcba9876543210"))
        assertThrows(Exception::class.java) { second.decrypt(first.encrypt("secret")) }
    }
}
