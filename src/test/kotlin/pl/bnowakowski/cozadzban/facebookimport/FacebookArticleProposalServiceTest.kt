// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleInput
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.security.MachineToMachineProperties
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.Role
import java.time.Instant

class FacebookArticleProposalServiceTest {
    private val proposalRepository: FacebookArticleProposalRepository = mock()
    private val runRepository: FacebookImportRunRepository = mock()
    private val articleService: ArticleService = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val service = proposalService()

    private fun proposalService(eventPublisher: ApplicationEventPublisher? = null) = FacebookArticleProposalService(
        proposalRepository,
        runRepository,
        articleService,
        appUserRepository,
        MachineToMachineProperties(
            enabled = true,
            apiKey = "machine-key",
            principalEmail = "facebook-import-bot@cozadzban.pl",
        ),
        eventPublisher,
    )

    @Test
    fun `worker-facing write methods commit outside the long batch transaction`() {
        assertRequiresNew("submitBatch", FacebookProposalBatchRequest::class.java)
        assertRequiresNew("recordProgress", String::class.java, FacebookImportProgressRequest::class.java)
        assertRequiresNew("completeRun", String::class.java, FacebookImportRunCompletionRequest::class.java)
        assertRequiresNew("recordLoginRequired", String::class.java, FacebookImportLoginRequiredRequest::class.java)
    }

    @Test
    fun `gzip text codec round trips proposal logs`() {
        val compressed = GzipTextCodec.compress("line one\nline two")

        assertFalse(compressed!!.toString(Charsets.UTF_8).contains("line one"))
        assertEquals("line one\nline two", GzipTextCodec.decompress(compressed))
    }

    @Test
    fun `submit batch skips articles that already exist`() {
        whenever(articleService.existsByUrl("https://example.com/existing")).thenReturn(true)

        val response = service.submitBatch(
            FacebookProposalBatchRequest(
                importRunId = "run-1",
                passIndex = 1,
                passCount = 1,
                proposals = listOf(
                    FacebookProposalSubmission(
                        candidateId = "candidate-1",
                        articleUrl = "https://example.com/existing",
                        facebookPostUrl = "https://www.facebook.com/source/posts/1",
                        language = "PL",
                        logs = "candidate logs",
                    ),
                ),
            ),
        )

        assertEquals(0, response.submitted)
        assertEquals(1, response.skippedExisting)
        verify(proposalRepository, never()).insert(any(), any(), any(), any(), any(), any(), any())
        verify(runRepository).recordBatch("run-1", 1, 0, 1, null, 1, 1)
    }

    @Test
    fun `submit batch updates seen timestamp for existing proposal without resetting decision`() {
        val existing = proposal(
            status = FacebookArticleProposalStatus.REJECTED,
            logsCompressed = GzipTextCodec.compress("old logs"),
        )
        whenever(articleService.existsByUrl("https://example.com/story")).thenReturn(false)
        whenever(proposalRepository.findByCanonicalArticleUrl("https://example.com/story")).thenReturn(existing)

        val response = service.submitBatch(
            FacebookProposalBatchRequest(
                importRunId = "run-2",
                passIndex = 1,
                passCount = 2,
                proposals = listOf(
                    FacebookProposalSubmission(
                        candidateId = "candidate-2",
                        articleUrl = "https://example.com/story",
                        facebookPostUrl = "https://www.facebook.com/source/posts/2",
                        language = "en",
                        logs = "new logs",
                    ),
                ),
            ),
        )

        assertEquals(0, response.submitted)
        assertEquals(1, response.skippedExisting)
        val logsCaptor = argumentCaptor<ByteArray>()
        verify(proposalRepository).updateSeen(
            eq(existing.id),
            eq("run-2"),
            eq("https://www.facebook.com/source/posts/2"),
            logsCaptor.capture(),
        )
        val logs = GzipTextCodec.decompress(logsCaptor.firstValue)
        assertTrue(logs.contains("old logs"))
        assertTrue(logs.contains("new logs"))
    }

    @Test
    fun `submit batch publishes event when new proposals are accepted`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)
        whenever(articleService.existsByUrl("https://example.com/new-story")).thenReturn(false)
        whenever(proposalRepository.findByCanonicalArticleUrl("https://example.com/new-story")).thenReturn(null)
        whenever(proposalRepository.insert(any(), any(), any(), any(), any(), any(), any())).thenReturn(
            proposal(status = null, logsCompressed = GzipTextCodec.compress("candidate logs")),
        )

        eventingService.submitBatch(
            FacebookProposalBatchRequest(
                importRunId = "run-event",
                passIndex = 2,
                passCount = 3,
                proposals = listOf(
                    FacebookProposalSubmission(
                        candidateId = "candidate-event",
                        articleUrl = "https://example.com/new-story",
                        facebookPostUrl = "https://www.facebook.com/source/posts/event",
                        language = "pl",
                        logs = "candidate logs",
                    ),
                ),
            ),
        )

        val eventCaptor = argumentCaptor<Any>()
        verify(publisher).publishEvent(eventCaptor.capture())
        val event = eventCaptor.firstValue as FacebookProposalBatchSubmittedEvent
        assertEquals("run-event", event.importRunId)
        assertEquals(2, event.passIndex)
        assertEquals(3, event.passCount)
        assertEquals(1, event.submitted)
        assertEquals(0, event.skippedExisting)
    }

    @Test
    fun `submit batch does not publish proposal event when all proposals are skipped`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)
        whenever(articleService.existsByUrl("https://example.com/existing")).thenReturn(true)

        eventingService.submitBatch(
            FacebookProposalBatchRequest(
                importRunId = "run-skip",
                passIndex = 1,
                passCount = 1,
                proposals = listOf(
                    FacebookProposalSubmission(
                        candidateId = "candidate-skip",
                        articleUrl = "https://example.com/existing",
                        facebookPostUrl = "https://www.facebook.com/source/posts/skip",
                        language = "pl",
                        logs = "candidate logs",
                    ),
                ),
            ),
        )

        verify(publisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `recordProgress stores worker progress`() {
        val occurredAt = Instant.parse("2026-05-24T10:00:00Z")
        val request = FacebookImportProgressRequest(
            phase = "Sending proposals",
            phaseIndex = 8,
            phaseCount = 8,
            passIndex = 2,
            passCount = 3,
            matchedPostCount = 5,
            submittedCount = 2,
            skippedExistingCount = 1,
            failedCount = 0,
            occurredAt = occurredAt,
        )

        service.recordProgress("run-progress", request)

        verify(runRepository).recordProgress("run-progress", request)
    }

    @Test
    fun `completeRun publishes run completed event`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)

        eventingService.completeRun(
            "run-complete",
            FacebookImportRunCompletionRequest(
                status = FacebookImportRunStatus.TERMINATED,
                discoveredCount = 7,
                submittedCount = 3,
                skippedExistingCount = 2,
                failedCount = 1,
                logs = "terminated by admin",
            ),
        )

        val eventCaptor = argumentCaptor<Any>()
        verify(publisher).publishEvent(eventCaptor.capture())
        val event = eventCaptor.firstValue as FacebookImportRunCompletedEvent
        assertEquals("run-complete", event.importRunId)
        assertEquals(FacebookImportRunStatus.TERMINATED, event.status)
        assertEquals(7, event.discoveredCount)
        assertEquals(3, event.submittedCount)
        assertEquals(2, event.skippedExistingCount)
        assertEquals(1, event.failedCount)
    }

    @Test
    fun `recordLoginRequired publishes first login required event`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)
        whenever(
            runRepository.recordLoginRequired(
                eq("run-login"),
                eq(FacebookImportTrigger.SCHEDULED),
                eq("https://www.facebook.com/profile"),
                any(),
            ),
        ).thenReturn(true)

        eventingService.recordLoginRequired(
            "run-login",
            FacebookImportLoginRequiredRequest(
                trigger = FacebookImportTrigger.SCHEDULED,
                profileUrl = "https://www.facebook.com/profile",
                detectedAt = Instant.parse("2026-05-24T10:00:00Z"),
            ),
        )

        val eventCaptor = argumentCaptor<Any>()
        verify(publisher).publishEvent(eventCaptor.capture())
        val event = eventCaptor.firstValue as FacebookImportLoginRequiredEvent
        assertEquals("run-login", event.importRunId)
        assertEquals(FacebookImportTrigger.SCHEDULED, event.trigger)
        assertEquals("https://www.facebook.com/profile", event.profileUrl)
    }

    @Test
    fun `recordLoginRequired suppresses duplicate login required event for same run`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)
        whenever(runRepository.recordLoginRequired(any(), any(), any(), any())).thenReturn(false)

        eventingService.recordLoginRequired(
            "run-login",
            FacebookImportLoginRequiredRequest(
                trigger = FacebookImportTrigger.SCHEDULED,
                profileUrl = "https://www.facebook.com/profile",
            ),
        )

        verify(publisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `recordLoginRequired publishes timeout event under login required flow`() {
        val publisher: ApplicationEventPublisher = mock()
        val eventingService = proposalService(publisher)
        whenever(runRepository.recordLoginRequired(any(), any(), any(), any())).thenReturn(false)

        eventingService.recordLoginRequired(
            "run-login",
            FacebookImportLoginRequiredRequest(
                trigger = FacebookImportTrigger.SCHEDULED,
                profileUrl = "https://www.facebook.com/profile",
                detectedAt = Instant.parse("2026-05-24T10:03:00Z"),
                timedOut = true,
                timeoutMessage = "Facebook login was not detected within PT3M",
            ),
        )

        val eventCaptor = argumentCaptor<Any>()
        verify(publisher).publishEvent(eventCaptor.capture())
        val event = eventCaptor.firstValue as FacebookImportLoginTimedOutEvent
        assertEquals("run-login", event.importRunId)
        assertEquals(FacebookImportTrigger.SCHEDULED, event.trigger)
        assertEquals("https://www.facebook.com/profile", event.profileUrl)
        assertEquals("Facebook login was not detected within PT3M", event.timeoutMessage)
        assertEquals(Instant.parse("2026-05-24T10:03:00Z"), event.timedOutAt)
    }

    @Test
    fun `accept creates article as import bot and marks proposal accepted`() {
        val pending = proposal(status = null)
        val accepted = pending.copy(status = FacebookArticleProposalStatus.ACCEPTED, articleId = 99L)
        whenever(proposalRepository.findById(1L)).thenReturn(pending, accepted)
        whenever(appUserRepository.findByEmail("facebook-import-bot@cozadzban.pl")).thenReturn(
            AppUser(7L, "facebook-import-bot@cozadzban.pl", Role.USER),
        )
        whenever(articleService.create(any(), eq(7L))).thenReturn(
            Article(
                id = 99L,
                url = pending.canonicalArticleUrl,
                language = "pl",
                createdByUserId = 7L,
            ),
        )

        service.accept(1L, "PL", decidedByUserId = 3L)

        val inputCaptor = argumentCaptor<ArticleInput>()
        verify(articleService).create(inputCaptor.capture(), eq(7L))
        assertEquals(pending.canonicalArticleUrl, inputCaptor.firstValue.url)
        assertEquals("pl", inputCaptor.firstValue.language)
        verify(proposalRepository).markAccepted(eq(1L), eq(99L), eq(3L), eq("pl"), any())
    }

    @Test
    fun `failed accept marks proposal failed`() {
        val pending = proposal(status = null)
        whenever(proposalRepository.findById(1L)).thenReturn(pending)
        whenever(appUserRepository.findByEmail("facebook-import-bot@cozadzban.pl")).thenReturn(
            AppUser(7L, "facebook-import-bot@cozadzban.pl", Role.USER),
        )
        whenever(articleService.create(any(), eq(7L))).thenThrow(IllegalArgumentException("boom"))

        val failure = runCatching { service.accept(1L, "en", decidedByUserId = 3L) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        verify(proposalRepository).markFailed(eq(1L), eq(3L), eq("en"), any())
    }

    @Test
    fun acceptMarksProposalAlreadyExistsWhenArticleUrlAlreadyExists() {
        val pending = proposal(status = null)
        val alreadyExists = pending.copy(status = FacebookArticleProposalStatus.ALREADY_EXISTS)
        whenever(proposalRepository.findById(1L)).thenReturn(pending, alreadyExists)
        whenever(articleService.existsByUrl(pending.canonicalArticleUrl)).thenReturn(true)

        val updated = service.accept(1L, "pl", decidedByUserId = 3L)

        assertEquals(FacebookArticleProposalStatus.ALREADY_EXISTS, updated.status)
        verify(articleService, never()).create(any(), any())
        verify(proposalRepository).markAlreadyExists(eq(1L), eq(3L), eq("pl"), any())
        verify(proposalRepository, never()).markFailed(any(), any(), any(), any())
    }

    private fun proposal(
        status: FacebookArticleProposalStatus?,
        logsCompressed: ByteArray? = null,
    ): FacebookArticleProposal =
        FacebookArticleProposal(
            id = 1L,
            candidateId = "candidate-1",
            importRunId = "run-1",
            articleUrl = "https://example.com/story",
            canonicalArticleUrl = "https://example.com/story",
            facebookPostUrl = "https://www.facebook.com/source/posts/1",
            guessedLanguage = "pl",
            correctedLanguage = null,
            status = status,
            articleId = null,
            decidedByUserId = null,
            decidedAt = null,
            submittedAt = Instant.parse("2026-05-24T10:00:00Z"),
            lastSeenAt = Instant.parse("2026-05-24T10:00:00Z"),
            logsCompressed = logsCompressed,
        )

    private fun assertRequiresNew(methodName: String, vararg parameterTypes: Class<*>) {
        val transactional = FacebookArticleProposalService::class.java
            .getDeclaredMethod(methodName, *parameterTypes)
            .getAnnotation(Transactional::class.java)

        assertEquals(Propagation.REQUIRES_NEW, transactional?.propagation)
    }
}
