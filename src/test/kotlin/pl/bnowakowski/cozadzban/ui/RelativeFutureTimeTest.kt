// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RelativeFutureTimeTest {
    private val now = Instant.parse("2026-10-31T10:00:00Z")

    private fun inFuture(duration: Duration) = RelativeFutureTime.format(now.plus(duration), now)

    @Test
    fun `phrases future instants in whole units`() {
        assertEquals("now", RelativeFutureTime.format(now.minusSeconds(5), now))
        assertEquals("in less than an hour", inFuture(Duration.ofMinutes(59)))
        assertEquals("in 1 hour", inFuture(Duration.ofMinutes(90)))
        assertEquals("in 23 hours", inFuture(Duration.ofHours(23)))
        assertEquals("in 1 day", inFuture(Duration.ofHours(30)))
        assertEquals("in 3 days", inFuture(Duration.ofDays(3).plusHours(20)))
        assertEquals("in 1 week", inFuture(Duration.ofDays(7)))
        assertEquals("in 1 week and 1 day", inFuture(Duration.ofDays(8)))
        assertEquals("in 2 weeks and 2 days", inFuture(Duration.ofDays(16).plusHours(5)))
        assertEquals("in 3 weeks", inFuture(Duration.ofDays(21)))
    }

    @Test
    fun `the Apify cycle end reads as the reset moment`() {
        val cycleEnd = Instant.parse("2026-11-26T23:59:59.999Z")
        assertEquals("in 3 weeks and 5 days", RelativeFutureTime.format(cycleEnd.plusMillis(1), now))
    }
}
