// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import java.time.Instant

enum class FacebookArticleProposalStatus {
    ACCEPTED,
    REJECTED,
    FAILED,
    ALREADY_EXISTS,
}

enum class FacebookArticleProposalStatusFilter {
    PENDING,
    ACCEPTED,
    REJECTED,
    FAILED,
    ALREADY_EXISTS,
    ALL,
}

enum class FacebookImportRunStatus {
    RUNNING,
    FINISHED,
    FAILED,
    TERMINATED,
}

const val FACEBOOK_IMPORT_PROGRESS_PHASE_COUNT = 8

enum class FacebookImportProgressPhase(val phaseIndex: Int, val label: String) {
    STARTING(1, "Starting"),
    OPENING_PROFILE(2, "Opening Facebook profile"),
    CHECKING_LOGIN(3, "Checking Facebook login"),
    SCROLLING_PROFILE(4, "Scrolling profile"),
    EXPANDING_POSTS(5, "Expanding posts"),
    COLLECTING_POSTS(6, "Collecting marked posts"),
    CHECKING_EXISTING(7, "Checking existing imports"),
    SENDING_PROPOSALS(8, "Sending proposals"),
}

data class FacebookImportRun(
    val importRunId: String,
    val importType: FacebookImportType = FacebookImportType.SELENIUM,
    val status: FacebookImportRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val discoveredCount: Int,
    val submittedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val currentPassIndex: Int,
    val passCount: Int,
    val phase: String?,
    val statusDetail: String?,
    val phaseIndex: Int,
    val phaseCount: Int,
    val lastStatusAt: Instant,
    val summaryLogsCompressed: ByteArray?,
)

data class FacebookArticleProposal(
    val id: Long,
    val candidateId: String,
    val importRunId: String,
    val importType: FacebookImportType = FacebookImportType.SELENIUM,
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
    val importType: FacebookImportType = FacebookImportType.SELENIUM,
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
    val importType: FacebookImportType = FacebookImportType.SELENIUM,
    val discoveredCount: Int = 0,
    val submittedCount: Int = 0,
    val skippedExistingCount: Int = 0,
    val failedCount: Int = 0,
    val logs: String? = null,
)

data class FacebookImportLoginRequiredRequest(
    val trigger: FacebookImportTrigger,
    val profileUrl: String,
    val detectedAt: Instant = Instant.now(),
    val timedOut: Boolean = false,
    val timeoutMessage: String? = null,
)

data class FacebookImportProgressRequest(
    val importType: FacebookImportType = FacebookImportType.SELENIUM,
    val phase: String,
    val detail: String? = null,
    val phaseIndex: Int,
    val phaseCount: Int,
    val passIndex: Int = 0,
    val passCount: Int = 0,
    val matchedPostCount: Int = 0,
    val submittedCount: Int = 0,
    val skippedExistingCount: Int = 0,
    val failedCount: Int = 0,
    val occurredAt: Instant = Instant.now(),
)

data class FacebookImportProgressSnapshot(
    val importRunId: String,
    val status: FacebookImportRunStatus,
    val startedAt: Instant,
    val lastUpdatedAt: Instant,
    val phase: String?,
    val detail: String?,
    val phaseIndex: Int,
    val phaseCount: Int,
    val passIndex: Int,
    val passCount: Int,
    val matchedPostCount: Int,
    val submittedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
)
