// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import java.time.Instant

enum class FacebookArticleProposalStatus {
    ACCEPTED,
    REJECTED,
    FAILED,
}

enum class FacebookArticleProposalStatusFilter {
    PENDING,
    ACCEPTED,
    REJECTED,
    FAILED,
    ALL,
}

enum class FacebookImportRunStatus {
    RUNNING,
    FINISHED,
    FAILED,
    TERMINATED,
}

data class FacebookImportRun(
    val importRunId: String,
    val status: FacebookImportRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val discoveredCount: Int,
    val submittedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val summaryLogsCompressed: ByteArray?,
)

data class FacebookArticleProposal(
    val id: Long,
    val candidateId: String,
    val importRunId: String,
    val articleUrl: String,
    val canonicalArticleUrl: String,
    val facebookPostUrl: String?,
    val guessedLanguage: String,
    val correctedLanguage: String?,
    val status: FacebookArticleProposalStatus?,
    val articleId: Long?,
    val decidedByUserId: Long?,
    val decidedAt: Instant?,
    val submittedAt: Instant,
    val lastSeenAt: Instant,
    val logsCompressed: ByteArray?,
) {
    val effectiveLanguage: String
        get() = correctedLanguage ?: guessedLanguage
}

data class FacebookProposalSubmission(
    val candidateId: String,
    val articleUrl: String,
    val facebookPostUrl: String?,
    val language: String,
    val logs: String?,
)

data class FacebookProposalBatchRequest(
    val importRunId: String,
    val passIndex: Int,
    val passCount: Int,
    val proposals: List<FacebookProposalSubmission>,
    val logs: String? = null,
)

data class FacebookProposalBatchResponse(
    val importRunId: String,
    val submitted: Int,
    val skippedExisting: Int,
)

data class FacebookProposalExistsResponse(
    val exists: Boolean,
)

data class FacebookImportRunCompletionRequest(
    val status: FacebookImportRunStatus,
    val discoveredCount: Int = 0,
    val submittedCount: Int = 0,
    val skippedExistingCount: Int = 0,
    val failedCount: Int = 0,
    val logs: String? = null,
)
