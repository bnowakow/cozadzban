// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.Role

class NotificationPreferenceServiceTest {
    private val repository: NotificationPreferenceRepository = mock()
    private val encryptor: PushoverUserKeyEncryptor = mock()
    private val pushoverClient: PushoverClient = mock()
    private val service = NotificationPreferenceService(repository, encryptor, pushoverClient)

    @Test
    fun `user cannot enable facebook login required notifications`() {
        whenever(repository.findByUserId(2L)).thenReturn(null)
        whenever(encryptor.encrypt("user-key")).thenReturn("encrypted")
        whenever(
            repository.upsert(
                appUserId = eq(2L),
                pushoverUserKeyEncrypted = eq("encrypted"),
                pushoverUserKeySuffix = eq("-key"),
                pushoverDevice = eq(null),
                facebookLoginRequiredEnabled = eq(false),
                facebookProposalsSubmittedEnabled = eq(true),
            ),
        ).thenReturn(preference(login = false, proposals = true))

        val summary = service.save(
            AppUser(2L, "user@example.com", Role.USER),
            NotificationPreferenceInput(
                pushoverUserKey = "user-key",
                pushoverDevice = null,
                facebookLoginRequiredEnabled = true,
                facebookProposalsSubmittedEnabled = true,
            ),
        )

        assertFalse(summary.facebookLoginRequiredEnabled)
        assertTrue(summary.facebookProposalsSubmittedEnabled)
        verify(pushoverClient).validateUser("user-key", null)
    }

    @Test
    fun `admin can enable both notification types`() {
        whenever(repository.findByUserId(1L)).thenReturn(null)
        whenever(encryptor.encrypt("admin-key")).thenReturn("encrypted")
        whenever(repository.upsert(any(), any(), any(), any(), any(), any()))
            .thenReturn(preference(login = true, proposals = true))

        val summary = service.save(
            AppUser(1L, "admin@example.com", Role.ADMIN),
            NotificationPreferenceInput(
                pushoverUserKey = "admin-key",
                pushoverDevice = "iphone",
                facebookLoginRequiredEnabled = true,
                facebookProposalsSubmittedEnabled = true,
            ),
        )

        assertTrue(summary.facebookLoginRequiredEnabled)
        assertTrue(summary.facebookProposalsSubmittedEnabled)
        verify(pushoverClient).validateUser("admin-key", "iphone")
    }

    private fun preference(login: Boolean, proposals: Boolean): NotificationPreference =
        NotificationPreference(
            appUserId = 1L,
            provider = NotificationProvider.PUSHOVER,
            pushoverUserKeyEncrypted = "encrypted",
            pushoverUserKeySuffix = "-key",
            pushoverDevice = null,
            facebookLoginRequiredEnabled = login,
            facebookProposalsSubmittedEnabled = proposals,
            createdAt = null,
            updatedAt = null,
        )
}
