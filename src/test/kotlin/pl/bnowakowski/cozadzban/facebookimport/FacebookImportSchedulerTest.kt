// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FacebookImportSchedulerTest {

    private val jobService: FacebookImportJobService = mock()

    @Test
    fun `scheduled launch is skipped when disabled`() {
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = false)),
            jobService,
        )

        assertFalse(scheduler.launchScheduledImportOnce())

        verify(jobService, never()).startImport(FacebookImportTrigger.SCHEDULED)
    }

    @Test
    fun `scheduled launch starts facebook import with scheduled trigger`() {
        whenever(jobService.startImport(FacebookImportTrigger.SCHEDULED)).thenReturn("run-1")
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = true)),
            jobService,
        )

        assertTrue(scheduler.launchScheduledImportOnce())

        verify(jobService).startImport(FacebookImportTrigger.SCHEDULED)
    }

    @Test
    fun `scheduler launches immediately on startup and then at the configured interval`() {
        val attempts = AtomicInteger()
        val launchLatch = CountDownLatch(2)
        whenever(jobService.startImport(FacebookImportTrigger.SCHEDULED)).thenAnswer {
            val attempt = attempts.incrementAndGet()
            launchLatch.countDown()
            "run-$attempt"
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(
                    enabled = true,
                    interval = Duration.ofMillis(20),
                    initialDelay = Duration.ZERO,
                ),
            ),
            jobService,
        )

        scheduler.start()

        assertTrue(launchLatch.await(1, TimeUnit.SECONDS))
        assertTrue(attempts.get() >= 2)
        scheduler.stop()
    }

    @Test
    fun `scheduler honors configured initial delay before first launch`() {
        val launchLatch = CountDownLatch(1)
        whenever(jobService.startImport(FacebookImportTrigger.SCHEDULED)).thenAnswer {
            launchLatch.countDown()
            "run-1"
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(
                    enabled = true,
                    interval = Duration.ofSeconds(1),
                    initialDelay = Duration.ofMillis(100),
                ),
            ),
            jobService,
        )

        scheduler.start()

        assertFalse(launchLatch.await(30, TimeUnit.MILLISECONDS))
        assertTrue(launchLatch.await(1, TimeUnit.SECONDS))
        scheduler.stop()
    }

    @Test
    fun `scheduled launch skips busy worker without failing`() {
        whenever(jobService.startImport(FacebookImportTrigger.SCHEDULED))
            .thenThrow(FacebookImportAlreadyRunningException())
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = true)),
            jobService,
        )

        assertFalse(scheduler.launchScheduledImportOnce())
    }
}
