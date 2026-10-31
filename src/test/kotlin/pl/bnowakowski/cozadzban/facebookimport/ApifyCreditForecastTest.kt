// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApifyCreditForecastTest {
    private val cycleStart = Instant.parse("2026-10-27T00:00:00Z")
    private val cycleEnd = Instant.parse("2026-11-26T23:59:59.999Z")

    private fun usage(usedUsd: String, limitUsd: String = "5") =
        ApifyAccountUsage(BigDecimal(limitUsd), BigDecimal(usedUsd), cycleStart, cycleEnd)

    @Test
    fun `predicts exhaustion from the spend rate so far this cycle`() {
        // 2.00 used in 4 days = 0.50/day; 3.00 left = 6 days.
        val forecast = ApifyCreditForecast.predict(usage("2.00"), Instant.parse("2026-10-31T00:00:00Z"))!!

        assertEquals(0, BigDecimal("0.5").compareTo(forecast.dailyUsageUsd))
        assertEquals(6.0, forecast.daysLeft, 0.0001)
        assertEquals(Instant.parse("2026-11-06T00:00:00Z"), forecast.predictedExhaustionAt)
        assertNull(forecast.alertDaysBefore)
    }

    @Test
    fun `alert threshold is 3 days, then 1 day`() {
        val now = Instant.parse("2026-10-31T00:00:00Z")
        // 0.50/day with 1.50 left = 3 days; 4.40 used (1.10/day) leaves 0.60, about 0.55 days.
        assertEquals(3, ApifyCreditForecast.predict(usage("2.00", limitUsd = "3.50"), now)!!.alertDaysBefore)
        assertEquals(1, ApifyCreditForecast.predict(usage("4.40"), now)!!.alertDaysBefore)
    }

    @Test
    fun `no forecast when the cycle resets first, nothing is left, or nothing was spent`() {
        val now = Instant.parse("2026-10-31T00:00:00Z")
        assertNull(ApifyCreditForecast.predict(usage("0.20"), now), "0.05/day lasts past the cycle end")
        assertNull(ApifyCreditForecast.predict(usage("5.00"), now), "exhausted is a separate notification")
        assertNull(ApifyCreditForecast.predict(usage("0"), now))
        assertNull(ApifyCreditForecast.predict(ApifyAccountUsage(BigDecimal("5"), BigDecimal("2"), null, cycleEnd), now))
    }

    @Test
    fun `elapsed time counts as at least one day`() {
        // 1.00 spent in the first hour must not be read as 24/day.
        val forecast = ApifyCreditForecast.predict(usage("1.00"), cycleStart.plusSeconds(3600))!!
        assertEquals(0, BigDecimal("1").compareTo(forecast.dailyUsageUsd))
        assertEquals(4.0, forecast.daysLeft, 0.0001)
    }

    @Test
    fun `scheduler sends each alert once per cycle`() {
        val alerts: ApifyCreditAlertRepository = mock()
        val publisher: ApplicationEventPublisher = mock()
        val usageClient: ApifyAccountUsageClient = mock()
        // 0.50/day with 1.25 left = 2.5 days.
        whenever(usageClient.fresh()).thenReturn(usage("2.00", limitUsd = "3.25"))
        whenever(alerts.claim(eq(cycleStart), eq(3), any())).thenReturn(true, false)
        val scheduler = scheduler(usageClient, alerts, publisher)

        scheduler.evaluate()
        scheduler.evaluate()

        val event = argumentCaptor<Any>()
        verify(publisher).publishEvent(event.capture())
        val low = event.firstValue as ApifyCreditsLowEvent
        assertEquals(3, low.daysBefore)
        assertEquals(2.5, low.daysLeft, 0.0001)
        assertEquals(Instant.parse("2026-11-02T12:00:00Z"), low.predictedExhaustionAt)
        verify(alerts, never()).claim(eq(cycleStart), eq(1), any())
    }

    @Test
    fun `a first check under a day sends only the 1-day alert`() {
        val alerts: ApifyCreditAlertRepository = mock()
        val publisher: ApplicationEventPublisher = mock()
        val usageClient: ApifyAccountUsageClient = mock()
        whenever(usageClient.fresh()).thenReturn(usage("4.40"))
        whenever(alerts.claim(any(), any(), any())).thenReturn(true)

        scheduler(usageClient, alerts, publisher).evaluate()

        val event = argumentCaptor<Any>()
        verify(publisher).publishEvent(event.capture())
        assertEquals(1, (event.firstValue as ApifyCreditsLowEvent).daysBefore)
        verify(alerts).claim(eq(cycleStart), eq(3), any())
    }

    @Test
    fun `scheduler does nothing when disabled or without a token`() {
        val usageClient: ApifyAccountUsageClient = mock()
        val publisher: ApplicationEventPublisher = mock()
        ApifyCreditForecastScheduler(properties("token"), usageClient, mock(), publisher, enabled = false).check()
        ApifyCreditForecastScheduler(properties(""), usageClient, mock(), publisher).check()
        verify(usageClient, never()).fresh()
        verify(publisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun `claim inserts once per cycle and threshold`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        whenever(jdbc.update(any<String>(), any<Map<String, Any>>())).thenReturn(1, 0)
        val repository = ApifyCreditAlertRepository(jdbc)
        val forecast = ApifyCreditForecast.predict(usage("4.40"), Instant.parse("2026-10-31T00:00:00Z"))!!

        assertTrue(repository.claim(cycleStart, 1, forecast))
        assertTrue(!repository.claim(cycleStart, 1, forecast))

        val sql = argumentCaptor<String>()
        verify(jdbc, org.mockito.kotlin.times(2)).update(sql.capture(), any<Map<String, Any>>())
        assertTrue(sql.firstValue.contains("ON CONFLICT (cycle_start_at, days_before) DO NOTHING"))
    }

    private fun scheduler(
        usageClient: ApifyAccountUsageClient,
        alerts: ApifyCreditAlertRepository,
        publisher: ApplicationEventPublisher,
    ) = ApifyCreditForecastScheduler(
        properties("token"), usageClient, alerts, publisher,
        clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun properties(token: String) =
        FacebookImportProperties(apify = FacebookImportProperties.Apify(apiToken = token))
}
