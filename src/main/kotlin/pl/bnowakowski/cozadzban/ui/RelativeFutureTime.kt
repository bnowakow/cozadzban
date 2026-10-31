// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import java.time.Duration
import java.time.Instant

/** Phrases a future instant relative to now, e.g. "in 3 days" or "in 2 weeks and 2 days". */
internal object RelativeFutureTime {
    /** Rounds down to whole units, so a deadline reads as slightly sooner rather than later. */
    fun format(target: Instant, now: Instant): String {
        val duration = Duration.between(now, target)
        if (duration <= Duration.ZERO) return "now"
        val hours = duration.toHours()
        val days = duration.toDays()
        val weeks = days / 7
        val remainingDays = days % 7
        return when {
            hours < 1 -> "in less than an hour"
            days < 1 -> "in ${count(hours, "hour")}"
            weeks < 1 -> "in ${count(days, "day")}"
            remainingDays == 0L -> "in ${count(weeks, "week")}"
            else -> "in ${count(weeks, "week")} and ${count(remainingDays, "day")}"
        }
    }

    private fun count(value: Long, unit: String): String = if (value == 1L) "1 $unit" else "$value ${unit}s"
}
