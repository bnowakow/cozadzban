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
                pushoverDevices = eq(emptyList()),
                facebookLoginRequiredEnabled = eq(false),
                facebookProposalsSubmittedEnabled = eq(true),
                facebookProposalsAutoApprovedEnabled = eq(true),
            ),
        ).thenReturn(preference(login = false, proposals = true, autoApproved = true))
        whenever(pushoverClient.validateUser("user-key", emptyList()))
            .thenReturn(PushoverUserValidation(listOf("iphone")))

        val summary = service.save(
            AppUser(2L, "user@example.com", Role.USER),
            NotificationPreferenceInput(
                pushoverUserKey = "user-key",
                pushoverDevices = emptyList(),
                facebookLoginRequiredEnabled = true,
                facebookProposalsSubmittedEnabled = true,
                facebookProposalsAutoApprovedEnabled = true,
            ),
        )

        assertFalse(summary.facebookLoginRequiredEnabled)
        assertTrue(summary.facebookProposalsSubmittedEnabled)
        assertTrue(summary.facebookProposalsAutoApprovedEnabled)
        verify(pushoverClient).validateUser("user-key", emptyList())
    }

    @Test
    fun `admin can enable both notification types`() {
        whenever(repository.findByUserId(1L)).thenReturn(null)
        whenever(encryptor.encrypt("admin-key")).thenReturn("encrypted")
        whenever(repository.upsert(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(preference(login = true, proposals = true, autoApproved = true))
        whenever(pushoverClient.validateUser("admin-key", listOf("iphone", "mac")))
            .thenReturn(PushoverUserValidation(listOf("iphone", "mac")))

        val summary = service.save(
            AppUser(1L, "admin@example.com", Role.ADMIN),
            NotificationPreferenceInput(
                pushoverUserKey = "admin-key",
                pushoverDevices = listOf("iphone", "mac"),
                facebookLoginRequiredEnabled = true,
                facebookProposalsSubmittedEnabled = true,
                facebookProposalsAutoApprovedEnabled = true,
            ),
        )

        assertTrue(summary.facebookLoginRequiredEnabled)
        assertTrue(summary.facebookProposalsSubmittedEnabled)
        assertTrue(summary.facebookProposalsAutoApprovedEnabled)
        verify(pushoverClient).validateUser("admin-key", listOf("iphone", "mac"))
    }

    @Test
    fun `available devices use supplied key or saved key`() {
        whenever(repository.findByUserId(1L)).thenReturn(preference(login = true, proposals = true, autoApproved = false))
        whenever(encryptor.decrypt("encrypted")).thenReturn("saved-key")
        whenever(pushoverClient.availableDevices("saved-key")).thenReturn(listOf("iphone", "mac"))

        val devices = service.availablePushoverDevices(AppUser(1L, "admin@example.com", Role.ADMIN), "")

        assertTrue(devices.containsAll(listOf("iphone", "mac")))
        verify(pushoverClient).availableDevices("saved-key")
    }

    private fun preference(login: Boolean, proposals: Boolean, autoApproved: Boolean): NotificationPreference =
        NotificationPreference(
            appUserId = 1L,
            provider = NotificationProvider.PUSHOVER,
            pushoverUserKeyEncrypted = "encrypted",
            pushoverUserKeySuffix = "-key",
            pushoverDevices = emptyList(),
            facebookLoginRequiredEnabled = login,
            facebookProposalsSubmittedEnabled = proposals,
            facebookProposalsAutoApprovedEnabled = autoApproved,
            createdAt = null,
            updatedAt = null,
        )
}
