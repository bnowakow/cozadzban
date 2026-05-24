// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import java.time.Instant

enum class FacebookImportTrigger {
    MANUAL,
    SCHEDULED,
}

data class FacebookImportLoginRequiredEvent(
    val importRunId: String,
    val trigger: FacebookImportTrigger,
    val profileUrl: String,
    val detectedAt: Instant = Instant.now(),
)

data class FacebookImportLoginTimedOutEvent(
    val importRunId: String,
    val trigger: FacebookImportTrigger,
    val profileUrl: String,
    val timeoutMessage: String?,
    val timedOutAt: Instant = Instant.now(),
)

data class FacebookProposalBatchSubmittedEvent(
    val importRunId: String,
    val passIndex: Int,
    val passCount: Int,
    val submitted: Int,
    val skippedExisting: Int,
    val occurredAt: Instant = Instant.now(),
)

data class FacebookImportRunCompletedEvent(
    val importRunId: String,
    val status: FacebookImportRunStatus,
    val discoveredCount: Int,
    val submittedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val occurredAt: Instant = Instant.now(),
)
