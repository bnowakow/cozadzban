// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.math.BigDecimal
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

/** Published on the server when an Apify import reports that the account's credits are used up. */
data class FacebookApifyCreditsExhaustedEvent(
    val importRunId: String,
    val trigger: FacebookImportTrigger,
    val remainingUsd: BigDecimal?,
    val cycleEndAt: Instant?,
    val detail: String?,
    val occurredAt: Instant = Instant.now(),
)

/** Published when Apify credit is predicted to run out within [daysBefore] days, once per cycle and threshold. */
data class ApifyCreditsLowEvent(
    val daysBefore: Int,
    val daysLeft: Double,
    val predictedExhaustionAt: Instant,
    val remainingUsd: BigDecimal,
    val limitUsd: BigDecimal,
    val dailyUsageUsd: BigDecimal,
    val cycleEndAt: Instant?,
    val occurredAt: Instant = Instant.now(),
)

data class FacebookImportRunCompletedEvent(
    val importRunId: String,
    val status: FacebookImportRunStatus,
    val discoveredCount: Int,
    val submittedCount: Int,
    val autoApprovedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val occurredAt: Instant = Instant.now(),
)
