// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportLoginRequiredEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportLoginTimedOutEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportRunCompletedEvent
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportRunStatus
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportTrigger
import pl.bnowakowski.cozadzban.user.Role

class NotificationDeliveryServiceTest {
    private val repository: NotificationPreferenceRepository = mock()
    private val encryptor: PushoverUserKeyEncryptor = mock()
    private val pushoverClient: PushoverClient = mock()
    private val service = NotificationDeliveryService(
        NotificationProperties(
            enabled = true,
            pushover = NotificationProperties.Pushover(appToken = "app-token"),
        ),
        repository,
        encryptor,
        pushoverClient,
    )

    @Test
    fun `scheduled facebook login required sends to opted-in admins`() {
        whenever(repository.findPushoverRecipientsForFacebookLoginRequired()).thenReturn(
            listOf(recipient(role = Role.ADMIN)),
        )
        whenever(encryptor.decrypt("encrypted")).thenReturn("user-key")

        service.onFacebookLoginRequired(
            FacebookImportLoginRequiredEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.SCHEDULED,
                profileUrl = "https://www.facebook.com/profile",
            ),
        )

        val captor = argumentCaptor<PushoverMessage>()
        verify(pushoverClient).send(captor.capture())
        assertEquals("user-key", captor.firstValue.userKey)
        assertEquals(listOf("iphone", "mac"), captor.firstValue.devices)
        assertEquals("Facebook login required", captor.firstValue.title)
    }

    @Test
    fun `manual facebook login required sends nothing`() {
        service.onFacebookLoginRequired(
            FacebookImportLoginRequiredEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.MANUAL,
                profileUrl = "https://www.facebook.com/profile",
            ),
        )

        verify(repository, never()).findPushoverRecipientsForFacebookLoginRequired()
        verify(pushoverClient, never()).send(org.mockito.kotlin.any())
    }

    @Test
    fun `worker startup facebook login required sends nothing`() {
        service.onFacebookLoginRequired(
            FacebookImportLoginRequiredEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.WORKER_STARTUP,
                profileUrl = "https://www.facebook.com/profile",
            ),
        )

        verify(repository, never()).findPushoverRecipientsForFacebookLoginRequired()
        verify(pushoverClient, never()).send(org.mockito.kotlin.any())
    }

    @Test
    fun `scheduled facebook login timeout sends to opted-in admins`() {
        whenever(repository.findPushoverRecipientsForFacebookLoginRequired()).thenReturn(
            listOf(recipient(role = Role.ADMIN)),
        )
        whenever(encryptor.decrypt("encrypted")).thenReturn("user-key")

        service.onFacebookLoginTimedOut(
            FacebookImportLoginTimedOutEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.SCHEDULED,
                profileUrl = "https://www.facebook.com/profile",
                timeoutMessage = "Facebook login was not detected within PT1M",
            ),
        )

        val captor = argumentCaptor<PushoverMessage>()
        verify(pushoverClient).send(captor.capture())
        assertEquals("Facebook login timed out", captor.firstValue.title)
        assertEquals(
            "Scheduled Facebook import run-1 timed out waiting for login or two-factor approval.",
            captor.firstValue.message,
        )
    }

    @Test
    fun `manual facebook login timeout sends nothing`() {
        service.onFacebookLoginTimedOut(
            FacebookImportLoginTimedOutEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.MANUAL,
                profileUrl = "https://www.facebook.com/profile",
                timeoutMessage = "Facebook login was not detected within PT1M",
            ),
        )

        verify(repository, never()).findPushoverRecipientsForFacebookLoginRequired()
        verify(pushoverClient, never()).send(org.mockito.kotlin.any())
    }

    @Test
    fun `worker startup facebook login timeout sends nothing`() {
        service.onFacebookLoginTimedOut(
            FacebookImportLoginTimedOutEvent(
                importRunId = "run-1",
                trigger = FacebookImportTrigger.WORKER_STARTUP,
                profileUrl = "https://www.facebook.com/profile",
                timeoutMessage = "Facebook login was not detected within PT1M",
            ),
        )

        verify(repository, never()).findPushoverRecipientsForFacebookLoginRequired()
        verify(pushoverClient, never()).send(org.mockito.kotlin.any())
    }

    @Test
    fun `completed run with submitted proposals sends summary`() {
        whenever(repository.findPushoverRecipientsForProposalSummary()).thenReturn(
            listOf(recipient(role = Role.USER)),
        )
        whenever(encryptor.decrypt("encrypted")).thenReturn("user-key")

        service.onFacebookImportRunCompleted(
            FacebookImportRunCompletedEvent(
                importRunId = "run-1",
                status = FacebookImportRunStatus.FINISHED,
                discoveredCount = 2,
                submittedCount = 2,
                skippedExistingCount = 0,
                failedCount = 0,
            ),
        )

        val captor = argumentCaptor<PushoverMessage>()
        verify(pushoverClient).send(captor.capture())
        assertEquals("New article proposals", captor.firstValue.title)
        assertEquals("https://cozadzban.pl/article-proposals", captor.firstValue.url)
    }

    @Test
    fun `completed run without new proposals sends nothing`() {
        service.onFacebookImportRunCompleted(
            FacebookImportRunCompletedEvent(
                importRunId = "run-1",
                status = FacebookImportRunStatus.FINISHED,
                discoveredCount = 2,
                submittedCount = 0,
                skippedExistingCount = 2,
                failedCount = 0,
            ),
        )

        verify(repository, never()).findPushoverRecipientsForProposalSummary()
        verify(pushoverClient, never()).send(org.mockito.kotlin.any())
    }

    private fun recipient(role: Role): NotificationRecipient =
        NotificationRecipient(
            appUserId = 1L,
            email = "user@example.com",
            role = role,
            pushoverUserKeyEncrypted = "encrypted",
            pushoverDevices = listOf("iphone", "mac"),
        )
}
