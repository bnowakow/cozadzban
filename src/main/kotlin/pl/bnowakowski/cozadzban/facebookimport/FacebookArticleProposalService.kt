// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozadzban.article.ArticleInput
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.article.ArticleUrlConflictException
import pl.bnowakowski.cozadzban.enrichment.EnrichmentResult
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.security.MachineToMachineProperties
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import java.time.Duration
import java.time.Instant

@Service
@Transactional
class FacebookArticleProposalService(
    private val proposalRepository: FacebookArticleProposalRepository,
    private val runRepository: FacebookImportRunRepository,
    private val articleService: ArticleService,
    private val appUserRepository: AppUserRepository,
    private val machineProperties: MachineToMachineProperties,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {
    @Transactional(readOnly = true)
    fun existsByArticleUrl(rawUrl: String): Boolean {
        val canonicalUrl = ArticleService.canonicalizeUrl(rawUrl)
        return articleService.existsByUrl(canonicalUrl) ||
            proposalRepository.existsByCanonicalArticleUrl(canonicalUrl)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun submitBatch(request: FacebookProposalBatchRequest): FacebookProposalBatchResponse {
        require(request.importRunId.isNotBlank()) { "importRunId is required" }

        var submitted = 0
        var skippedExisting = 0
        request.proposals.forEach { proposal ->
            val candidateId = proposal.candidateId.trim()
            val articleUrl = proposal.articleUrl.trim()
            require(candidateId.isNotBlank()) { "candidateId is required" }
            require(articleUrl.isNotBlank()) { "articleUrl is required" }

            val canonicalUrl = ArticleService.canonicalizeUrl(articleUrl)
            val language = ArticleService.normalizeLanguage(proposal.language)
            val proposalLogs = proposalLogText(request, proposal)

            if (articleService.existsByUrl(canonicalUrl)) {
                skippedExisting++
                return@forEach
            }

            val existing = proposalRepository.findByCanonicalArticleUrl(canonicalUrl)
            if (existing != null) {
                proposalRepository.updateSeen(
                    id = existing.id,
                    importRunId = request.importRunId,
                    facebookPostUrl = proposal.facebookPostUrl,
                    logsCompressed = appendLogs(existing.logsCompressed, proposalLogs),
                    browserEnrichment = proposal.browserEnrichment,
                )
                skippedExisting++
                return@forEach
            }

            proposalRepository.insert(
                candidateId = candidateId,
                importRunId = request.importRunId,
                articleUrl = articleUrl,
                canonicalArticleUrl = canonicalUrl,
                facebookPostUrl = proposal.facebookPostUrl?.trim()?.takeIf { it.isNotBlank() },
                guessedLanguage = language,
                logsCompressed = GzipTextCodec.compress(proposalLogs),
                browserEnrichment = proposal.browserEnrichment,
            )
            submitted++
        }

        runRepository.recordBatch(
            importRunId = request.importRunId,
            discoveredCount = request.proposals.size,
            submittedCount = submitted,
            skippedExistingCount = skippedExisting,
            logsCompressed = GzipTextCodec.compress(request.logs),
            passIndex = request.passIndex,
            passCount = request.passCount,
        )

        if (submitted > 0) {
            eventPublisher?.publishEvent(
                FacebookProposalBatchSubmittedEvent(
                    importRunId = request.importRunId,
                    passIndex = request.passIndex,
                    passCount = request.passCount,
                    submitted = submitted,
                    skippedExisting = skippedExisting,
                ),
            )
        }

        return FacebookProposalBatchResponse(
            importRunId = request.importRunId,
            submitted = submitted,
            skippedExisting = skippedExisting,
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordProgress(importRunId: String, request: FacebookImportProgressRequest) {
        require(importRunId.isNotBlank()) { "importRunId is required" }
        require(request.phaseIndex <= request.phaseCount || request.phaseCount == 0) {
            "phaseIndex cannot exceed phaseCount"
        }
        runRepository.recordProgress(importRunId, request)
    }

    @Transactional(readOnly = true)
    fun latestRunningProgress(): FacebookImportProgressSnapshot? =
        runRepository.findLatestRunningProgress()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun terminateTimedOutRun(importRunId: String, timeout: Duration, timedOutAt: Instant = Instant.now()): Boolean {
        require(importRunId.isNotBlank()) { "importRunId is required" }
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        val terminated = runRepository.terminateTimedOutRunningRun(
            importRunId = importRunId,
            timedOutAt = timedOutAt,
            statusDetail = timeoutDetail(timeout),
        )
        if (terminated) {
            LOG.warn("Facebook import run {} timed out after {}; marked terminated", importRunId, timeout)
        }
        return terminated
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun terminateTimedOutRuns(timeout: Duration, timedOutAt: Instant = Instant.now()): List<String> {
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        val runIds = runRepository.terminateTimedOutRunningRuns(
            startedBefore = timedOutAt.minus(timeout),
            timedOutAt = timedOutAt,
            statusDetail = timeoutDetail(timeout),
        )
        runIds.forEach { runId ->
            LOG.warn("Facebook import run {} timed out after {}; marked terminated", runId, timeout)
        }
        return runIds
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeRun(importRunId: String, request: FacebookImportRunCompletionRequest) {
        require(importRunId.isNotBlank()) { "importRunId is required" }
        runRepository.complete(
            importRunId = importRunId,
            status = request.status,
            discoveredCount = request.discoveredCount,
            submittedCount = request.submittedCount,
            skippedExistingCount = request.skippedExistingCount,
            failedCount = request.failedCount,
            logsCompressed = GzipTextCodec.compress(request.logs),
        )
        eventPublisher?.publishEvent(
            FacebookImportRunCompletedEvent(
                importRunId = importRunId,
                status = request.status,
                discoveredCount = request.discoveredCount,
                submittedCount = request.submittedCount,
                skippedExistingCount = request.skippedExistingCount,
                failedCount = request.failedCount,
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordLoginRequired(importRunId: String, request: FacebookImportLoginRequiredRequest) {
        require(importRunId.isNotBlank()) { "importRunId is required" }
        val firstForRun = runRepository.recordLoginRequired(
            importRunId = importRunId,
            trigger = request.trigger,
            profileUrl = request.profileUrl,
            detectedAt = request.detectedAt,
        )
        if (firstForRun) {
            eventPublisher?.publishEvent(
                FacebookImportLoginRequiredEvent(
                    importRunId = importRunId,
                    trigger = request.trigger,
                    profileUrl = request.profileUrl,
                    detectedAt = request.detectedAt,
                ),
            )
        }
        if (request.timedOut) {
            eventPublisher?.publishEvent(
                FacebookImportLoginTimedOutEvent(
                    importRunId = importRunId,
                    trigger = request.trigger,
                    profileUrl = request.profileUrl,
                    timeoutMessage = request.timeoutMessage,
                    timedOutAt = request.detectedAt,
                ),
            )
        }
    }

    @Transactional(readOnly = true)
    fun findPage(statusFilter: FacebookArticleProposalStatusFilter, page: Int, size: Int): List<FacebookArticleProposal> {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..100) { "size must be between 1 and 100" }
        return proposalRepository.findPage(statusFilter, page, size)
    }

    @Transactional(readOnly = true)
    fun count(statusFilter: FacebookArticleProposalStatusFilter): Long =
        proposalRepository.count(statusFilter)

    @Transactional(readOnly = true)
    fun findById(id: Long): FacebookArticleProposal =
        proposalRepository.findById(id) ?: throw NoSuchElementException("Facebook article proposal $id not found")

    @Transactional(readOnly = true)
    fun logsFor(id: Long): String =
        GzipTextCodec.decompress(findById(id).logsCompressed)

    fun accept(id: Long, correctedLanguage: String, decidedByUserId: Long): FacebookArticleProposal {
        val proposal = findById(id)
        require(proposal.status != FacebookArticleProposalStatus.ACCEPTED) {
            "Facebook article proposal $id is already accepted"
        }
        val language = ArticleService.normalizeLanguage(correctedLanguage)
        if (articleService.existsByUrl(proposal.canonicalArticleUrl)) {
            LOG.info("Facebook article proposal {} already exists before accepting", id)
            return markAlreadyExists(id, proposal, decidedByUserId, language, proposal.canonicalArticleUrl)
        }
        val bot = importBotUser()
        return try {
            val input = ArticleInput(
                url = proposal.canonicalArticleUrl,
                language = language,
                quote = null,
            )
            val browserEnrichment = proposal.browserEnrichmentResult()
            val article = if (browserEnrichment != null) {
                articleService.createWithEnrichment(input, bot.id!!, browserEnrichment)
            } else {
                articleService.create(input, bot.id!!)
            }
            proposalRepository.markAccepted(
                id = id,
                articleId = article.id!!,
                decidedByUserId = decidedByUserId,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Accepted by userId=$decidedByUserId; articleId=${article.id}; botUserId=${bot.id}; " +
                        "language=$language; browserEnrichment=${browserEnrichment != null}",
                ),
            )
            findById(id)
        } catch (ex: ArticleUrlConflictException) {
            LOG.info("Facebook article proposal {} already exists while accepting", id, ex)
            markAlreadyExists(id, proposal, decidedByUserId, language, ex.url)
        } catch (ex: Exception) {
            LOG.warn("Facebook article proposal {} accept failed", id, ex)
            proposalRepository.markFailed(
                id = id,
                decidedByUserId = decidedByUserId,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Accept failed for userId=$decidedByUserId; language=$language; error=${failureMessage(ex)}",
                ),
            )
            throw ex
        }
    }

    fun reject(id: Long, correctedLanguage: String, decidedByUserId: Long): FacebookArticleProposal {
        val proposal = findById(id)
        require(proposal.status != FacebookArticleProposalStatus.ACCEPTED) {
            "Facebook article proposal $id is already accepted"
        }
        val language = ArticleService.normalizeLanguage(correctedLanguage)
        proposalRepository.markRejected(
            id = id,
            decidedByUserId = decidedByUserId,
            correctedLanguage = language,
            logsCompressed = appendLogs(
                proposal.logsCompressed,
                "Rejected by userId=$decidedByUserId; language=$language",
            ),
        )
        return findById(id)
    }

    private fun markAlreadyExists(
        id: Long,
        proposal: FacebookArticleProposal,
        decidedByUserId: Long,
        language: String,
        existingUrl: String,
    ): FacebookArticleProposal {
        proposalRepository.markAlreadyExists(
            id = id,
            decidedByUserId = decidedByUserId,
            correctedLanguage = language,
            logsCompressed = appendLogs(
                proposal.logsCompressed,
                "Accept skipped for userId=$decidedByUserId; language=$language; alreadyExistsUrl=$existingUrl",
            ),
        )
        return findById(id)
    }

    private fun proposalLogText(request: FacebookProposalBatchRequest, proposal: FacebookProposalSubmission): String =
        buildString {
            appendLine("importRunId=${request.importRunId}")
            appendLine("pass=${request.passIndex}/${request.passCount}")
            appendLine("candidateId=${proposal.candidateId}")
            appendLine("articleUrl=${proposal.articleUrl}")
            appendLine("facebookPostUrl=${proposal.facebookPostUrl ?: "<none>"}")
            appendLine("language=${proposal.language}")
            proposal.browserEnrichment?.let { enrichment ->
                appendLine("browserEnrichment:")
                appendLine("title=${valueDiagnostic(enrichment.title)}")
                appendLine("thumbnail=${valueDiagnostic(enrichment.thumbnail)}")
                appendLine("lead=${valueDiagnostic(enrichment.lead)}")
                appendLine("favicon=${valueDiagnostic(enrichment.favicon)}")
                appendLine("publishedAt=${enrichment.publishedAt}")
                appendLine("plainText=${valueDiagnostic(enrichment.plainText)}")
            }
            proposal.logs?.takeIf { it.isNotBlank() }?.let {
                appendLine("candidateLogs:")
                appendLine(it)
            }
        }

    private fun FacebookArticleProposal.browserEnrichmentResult(): EnrichmentResult? {
        if (
            !browserEnrichedTitle.isNullOrBlank() ||
            !browserEnrichedThumbnail.isNullOrBlank() ||
            !browserEnrichedLead.isNullOrBlank() ||
            !browserEnrichedFavicon.isNullOrBlank() ||
            browserEnrichedPublishedAt != null ||
            !browserEnrichedPlainText.isNullOrBlank()
        ) {
            return EnrichmentResult(
                title = browserEnrichedTitle,
                thumbnail = browserEnrichedThumbnail,
                lead = browserEnrichedLead,
                favicon = browserEnrichedFavicon,
                publishedAt = browserEnrichedPublishedAt,
                plainText = browserEnrichedPlainText,
            )
        }
        return browserEnrichmentFromStoredCandidateLogs()
    }

    private fun FacebookArticleProposal.browserEnrichmentFromStoredCandidateLogs(): EnrichmentResult? {
        if (!canonicalArticleUrl.contains("facebook.com", ignoreCase = true)) return null
        val candidateText = GzipTextCodec.decompress(logsCompressed)
            .substringAfter("candidateText:\n", "")
            .trim()
            .takeIf { it.length >= MIN_BROWSER_ENRICHED_TEXT_CHARS }
            ?.take(MAX_BROWSER_ENRICHED_TEXT_CHARS)
            ?: return null
        return EnrichmentResult(
            title = null,
            thumbnail = null,
            lead = candidateText,
            favicon = null,
            publishedAt = null,
            plainText = candidateText,
        )
    }

    private fun valueDiagnostic(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.let { "present(len=${it.length})" } ?: "absent"

    private fun appendLogs(existingCompressed: ByteArray?, addition: String): ByteArray? {
        if (addition.isBlank()) return existingCompressed
        val existing = GzipTextCodec.decompress(existingCompressed)
        val merged = if (existing.isBlank()) {
            addition
        } else {
            existing.trimEnd() + "\n\n---\n" + addition
        }
        return GzipTextCodec.compress(merged)
    }

    private fun importBotUser(): AppUser {
        val email = AllowlistAuthorizationManager.normalizeEmail(machineProperties.principalEmail)
            ?: throw IllegalArgumentException("app.machine-auth.principal-email must be configured for proposal acceptance")
        val user = appUserRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Import bot user '$email' is not allowlisted")
        require(user.status == AppUserStatus.ACTIVE) {
            "Import bot user '$email' must be ACTIVE"
        }
        return user
    }

    private fun failureMessage(ex: Exception): String =
        ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName

    private fun timeoutDetail(timeout: Duration): String =
        "Timed out after $timeout"

    companion object {
        private val LOG = LoggerFactory.getLogger(FacebookArticleProposalService::class.java)
        private const val MIN_BROWSER_ENRICHED_TEXT_CHARS = 40
        private const val MAX_BROWSER_ENRICHED_TEXT_CHARS = 3000
    }
}
