// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every time shown in the UI is Polish local time (Europe/Warsaw, CET/CEST), whatever the
 * visitor's browser zone. Timestamps are still stored and exchanged in UTC; only display and
 * picker input are converted here.
 */
object WarsawTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZONE)
    private val DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE)
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZONE)

    fun dateTime(instant: Instant): String = DATE_TIME.format(instant)
    fun dateTimeSeconds(instant: Instant): String = DATE_TIME_SECONDS.format(instant)
    fun date(instant: Instant): String = DATE.format(instant)

    /** Value for a DateTimePicker, which has no zone: the instant as Warsaw wall-clock time. */
    fun toPicker(instant: Instant?): LocalDateTime? = instant?.atZone(ZONE)?.toLocalDateTime()

    /** A DateTimePicker value read as Warsaw wall-clock time. */
    fun fromPicker(value: LocalDateTime?): Instant? = value?.atZone(ZONE)?.toInstant()
}
