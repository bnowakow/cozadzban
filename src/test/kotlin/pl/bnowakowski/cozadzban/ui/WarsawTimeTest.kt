// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class WarsawTimeTest {
    @Test
    fun `formats in Warsaw summer and winter time`() {
        assertEquals("2026-05-24 12:15", WarsawTime.dateTime(Instant.parse("2026-05-24T10:15:30Z")))
        assertEquals("2026-05-24 12:15:30", WarsawTime.dateTimeSeconds(Instant.parse("2026-05-24T10:15:30Z")))
        assertEquals("2026-11-15 11:00", WarsawTime.dateTime(Instant.parse("2026-11-15T10:00:00Z")))
    }

    @Test
    fun `the date follows Warsaw midnight, not UTC midnight`() {
        assertEquals("2026-10-20", WarsawTime.date(Instant.parse("2026-10-19T22:30:00Z")))
        assertEquals("2026-10-19", WarsawTime.date(Instant.parse("2026-10-19T21:59:00Z")))
    }

    @Test
    fun `picker values are Warsaw wall-clock time`() {
        val instant = Instant.parse("2026-07-01T08:00:00Z")
        assertEquals(LocalDateTime.parse("2026-07-01T10:00:00"), WarsawTime.toPicker(instant))
        assertEquals(instant, WarsawTime.fromPicker(LocalDateTime.parse("2026-07-01T10:00:00")))
        assertEquals(Instant.parse("2026-01-10T11:00:00Z"), WarsawTime.fromPicker(LocalDateTime.parse("2026-01-10T12:00:00")))
        assertNull(WarsawTime.toPicker(null))
        assertNull(WarsawTime.fromPicker(null))
    }
}
