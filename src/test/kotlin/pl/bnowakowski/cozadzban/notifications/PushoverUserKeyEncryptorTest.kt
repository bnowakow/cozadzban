// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Base64

class PushoverUserKeyEncryptorTest {

    @Test
    fun `encrypts and decrypts user key`() {
        val encryptor = encryptorWithKey("0123456789abcdef")

        val encrypted = encryptor.encrypt("pushover-user-key")

        assertNotEquals("pushover-user-key", encrypted)
        assertEquals("pushover-user-key", encryptor.decrypt(encrypted))
    }

    @Test
    fun `accepts base64 encoded AES key`() {
        val key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".toByteArray())
        val encryptor = encryptorWithKey(key)

        val encrypted = encryptor.encrypt("user-key")

        assertEquals("user-key", encryptor.decrypt(encrypted))
    }

    @Test
    fun `missing encryption key fails before encrypting`() {
        val encryptor = encryptorWithKey("")

        assertThrows(IllegalArgumentException::class.java) {
            encryptor.encrypt("user-key")
        }
    }

    @Test
    fun `key suffix exposes only trailing characters`() {
        assertEquals("cdef", NotificationPreferenceService.keySuffix("0123456789abcdef"))
    }

    private fun encryptorWithKey(key: String): PushoverUserKeyEncryptor =
        PushoverUserKeyEncryptor(NotificationProperties(enabled = true, encryptionKey = key))
}
