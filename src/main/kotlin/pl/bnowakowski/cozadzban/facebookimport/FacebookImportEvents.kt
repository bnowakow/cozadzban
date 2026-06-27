// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant

enum class FacebookImportTrigger {
    MANUAL,
    WORKER_STARTUP,
    SCHEDULED,
}

enum class FacebookImportType {
    APIFY,
    SELENIUM,
    ;

    @JsonValue
    fun toJson(): String =
        when (this) {
            APIFY -> "API"
            SELENIUM -> "SELENIUM"
        }

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromJson(value: String?): FacebookImportType =
            when (value?.trim()?.uppercase()) {
                "API", "APIFY" -> APIFY
                "SELENIUM" -> SELENIUM
                else -> throw IllegalArgumentException("Unsupported Facebook import type: $value")
            }
    }
}

interface FacebookImportRunner {
    val importType: FacebookImportType

    fun newImportRunId(generatedAt: Instant = Instant.now()): String

    fun runImport(importRunId: String, trigger: FacebookImportTrigger = FacebookImportTrigger.MANUAL)

    fun terminateImport()

    fun isImportRunning(): Boolean

    fun currentProgressSnapshot(): FacebookImportProgressSnapshot?

    fun unavailableReason(): String?
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
