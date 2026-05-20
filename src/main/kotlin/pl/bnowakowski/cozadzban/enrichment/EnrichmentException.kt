// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

/** Typed enrichment failure that callers map to HTTP 422 responses. */
class EnrichmentException(
    message: String,
    val reason: Reason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Reason {
        NON_2XX,
        TIMEOUT,
        UNREACHABLE,
    }
}
