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
    private enum class AutoAcceptReason(val persistedValue: String) {
        APIFY_NEW_PROPOSAL("new_apify_proposal"),
        APIFY_MATCHED_PENDING_PROPOSAL("matched_pending_selenium_proposal"),
    }

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
                val mergedLogs = appendLogs(existing.logsCompressed, proposalLogs)
                proposalRepository.updateSeen(
                    id = existing.id,
                    importRunId = request.importRunId,
                    articleUrl = articleUrl,
                    guessedLanguage = language,
                    importType = request.importType,
                    facebookPostUrl = proposal.facebookPostUrl,
                    logsCompressed = mergedLogs,
                )
                if (request.importType == FacebookImportType.APIFY && existing.status == null) {
                    autoAcceptProposal(
                        existing.copy(
                            importRunId = request.importRunId,
                            importType = request.importType,
                            articleUrl = articleUrl,
                            facebookPostUrl = proposal.facebookPostUrl?.trim()?.takeIf { it.isNotBlank() },
                            guessedLanguage = language,
                            logsCompressed = mergedLogs,
                        ),
                        AutoAcceptReason.APIFY_MATCHED_PENDING_PROPOSAL,
                    )
                }
                skippedExisting++
                return@forEach
            }

            val inserted = proposalRepository.insert(
                candidateId = candidateId,
                importRunId = request.importRunId,
                importType = request.importType,
                articleUrl = articleUrl,
                canonicalArticleUrl = canonicalUrl,
                facebookPostUrl = proposal.facebookPostUrl?.trim()?.takeIf { it.isNotBlank() },
                guessedLanguage = language,
                logsCompressed = GzipTextCodec.compress(proposalLogs),
            )
            if (request.importType == FacebookImportType.APIFY) {
                autoAcceptProposal(inserted, AutoAcceptReason.APIFY_NEW_PROPOSAL)
            }
            submitted++
        }

        runRepository.recordBatch(
            importRunId = request.importRunId,
            importType = request.importType,
            trigger = request.trigger,
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

    @Transactional(readOnly = true)
    fun latestProgress(): FacebookImportProgressSnapshot? =
        runRepository.findLatestProgress()

    @Transactional(readOnly = true)
    fun latestRunTimestamp(importType: FacebookImportType): Instant? =
        runRepository.findLatestRunTimestamp(importType)

    @Transactional(readOnly = true)
    fun latestRunTimestamp(importType: FacebookImportType, trigger: FacebookImportTrigger): Instant? =
        runRepository.findLatestRunTimestamp(importType, trigger)

    @Transactional(readOnly = true)
    fun latestAutomaticRunTimestamp(importType: FacebookImportType): Instant? =
        runRepository.findLatestRunTimestamp(importType, setOf(FacebookImportTrigger.WORKER_STARTUP, FacebookImportTrigger.SCHEDULED))

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
    fun terminateAbandonedRunsOnStartup(startedAt: Instant = Instant.now()): List<String> {
        val runIds = runRepository.terminateAbandonedRunningRuns(
            abandonedAt = startedAt,
            statusDetail = "Interrupted by application restart before startup cleanup",
        )
        runIds.forEach { runId ->
            LOG.warn("Facebook import run {} was still marked running on startup; marked terminated", runId)
        }
        return runIds
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeRun(importRunId: String, request: FacebookImportRunCompletionRequest) {
        require(importRunId.isNotBlank()) { "importRunId is required" }
        runRepository.complete(
            importRunId = importRunId,
            importType = request.importType,
            trigger = request.trigger,
            status = request.status,
            discoveredCount = request.discoveredCount,
            submittedCount = request.submittedCount,
            skippedExistingCount = request.skippedExistingCount,
            failedCount = request.failedCount,
            statusDetail = request.statusDetail,
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
            val article = articleService.create(
                ArticleInput(
                    url = proposal.canonicalArticleUrl,
                    language = language,
                    quote = null,
                ),
                bot.id!!,
            )
            proposalRepository.markAccepted(
                id = id,
                articleId = article.id!!,
                decidedByUserId = decidedByUserId,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Accepted by userId=$decidedByUserId; articleId=${article.id}; botUserId=${bot.id}; language=$language",
                ),
                acceptedBy = "USER:$decidedByUserId",
                acceptedReason = "manual_review",
            )
            articleService.recordImportSource(
                articleId = article.id!!,
                sourceImportType = proposal.importType,
                sourceImportRunId = proposal.importRunId,
                sourceFacebookProposalId = proposal.id,
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
            proposal.logs?.takeIf { it.isNotBlank() }?.let {
                appendLine("candidateLogs:")
                appendLine(it)
            }
        }

    private fun autoAcceptProposal(proposal: FacebookArticleProposal, reason: AutoAcceptReason) {
        val bot = importBotUser()
        val language = proposal.effectiveLanguage
        if (articleService.existsByUrl(proposal.canonicalArticleUrl)) {
            proposalRepository.markAlreadyExists(
                id = proposal.id,
                decidedByUserId = bot.id!!,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Apify auto-accept skipped; alreadyExistsUrl=${proposal.canonicalArticleUrl}; reason=${reason.persistedValue}",
                ),
            )
            return
        }
        try {
            val article = articleService.create(
                ArticleInput(
                    url = proposal.canonicalArticleUrl,
                    language = language,
                    quote = null,
                ),
                bot.id!!,
            )
            proposalRepository.markAccepted(
                id = proposal.id,
                articleId = article.id!!,
                decidedByUserId = bot.id,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Apify auto-accepted; articleId=${article.id}; botUserId=${bot.id}; language=$language; reason=${reason.persistedValue}",
                ),
                acceptedBy = "APIFY_AUTO_ACCEPT",
                acceptedReason = reason.persistedValue,
            )
            articleService.recordImportSource(
                articleId = article.id!!,
                sourceImportType = proposal.importType,
                sourceImportRunId = proposal.importRunId,
                sourceFacebookProposalId = proposal.id,
            )
        } catch (ex: ArticleUrlConflictException) {
            proposalRepository.markAlreadyExists(
                id = proposal.id,
                decidedByUserId = bot.id!!,
                correctedLanguage = language,
                logsCompressed = appendLogs(
                    proposal.logsCompressed,
                    "Apify auto-accept skipped; alreadyExistsUrl=${ex.url}; reason=${reason.persistedValue}",
                ),
            )
        }
    }

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
    }
}
