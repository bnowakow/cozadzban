// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozadzban.article.ArticleInput
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.security.MachineToMachineProperties
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus

@Service
@Transactional
class FacebookArticleProposalService(
    private val proposalRepository: FacebookArticleProposalRepository,
    private val runRepository: FacebookImportRunRepository,
    private val articleService: ArticleService,
    private val appUserRepository: AppUserRepository,
    private val machineProperties: MachineToMachineProperties,
) {
    @Transactional(readOnly = true)
    fun existsByArticleUrl(rawUrl: String): Boolean {
        val canonicalUrl = ArticleService.canonicalizeUrl(rawUrl)
        return articleService.existsByUrl(canonicalUrl) ||
            proposalRepository.existsByCanonicalArticleUrl(canonicalUrl)
    }

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
            )
            submitted++
        }

        runRepository.recordBatch(
            importRunId = request.importRunId,
            discoveredCount = request.proposals.size,
            submittedCount = submitted,
            skippedExistingCount = skippedExisting,
            logsCompressed = GzipTextCodec.compress(request.logs),
        )

        return FacebookProposalBatchResponse(
            importRunId = request.importRunId,
            submitted = submitted,
            skippedExisting = skippedExisting,
        )
    }

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
            )
            findById(id)
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

    companion object {
        private val LOG = LoggerFactory.getLogger(FacebookArticleProposalService::class.java)
    }
}
