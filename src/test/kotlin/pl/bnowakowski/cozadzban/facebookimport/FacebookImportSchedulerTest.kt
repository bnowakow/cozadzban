// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.never
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FacebookImportSchedulerTest {

    private val jobService: FacebookImportJobService = mock()
    private val proposalService: FacebookArticleProposalService = mock()


    @Test
    fun `scheduled launch is skipped when disabled`() {
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = false)),
            jobService,
            proposalService,
        )

        assertFalse(scheduler.launchScheduledImportOnce())

        verify(jobService, never()).startScheduledImports(FacebookImportTrigger.SCHEDULED)
    }


    @Test
    fun `scheduled launch starts facebook import with scheduled trigger`() {
        whenever(jobService.startScheduledImports(FacebookImportTrigger.SCHEDULED)).thenReturn(listOf("run-1"))
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = true)),
            jobService,
            proposalService,
        )

        assertTrue(scheduler.launchScheduledImportOnce())

        verify(jobService).startScheduledImports(FacebookImportTrigger.SCHEDULED)
    }


    @Test
    fun `scheduler waits one interval before first scheduled launch by default`() {
        val launchLatch = CountDownLatch(1)
        val triggers = CopyOnWriteArrayList<FacebookImportTrigger>()
        whenever(jobService.startScheduledImports(any())).thenAnswer {
            triggers.add(it.getArgument(0))
            launchLatch.countDown()
            listOf("run-1")
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(
                    enabled = true,
                    interval = Duration.ofMillis(100),
                    initialDelay = Duration.ZERO,
                ),
            ),
            jobService,
            proposalService,
        )

        scheduler.start()

        assertFalse(launchLatch.await(30, TimeUnit.MILLISECONDS))
        assertTrue(launchLatch.await(1, TimeUnit.SECONDS))
        scheduler.stop()
        assertTrue(triggers.firstOrNull() == FacebookImportTrigger.SCHEDULED)
    }


    @Test
    fun `scheduler launches on startup when startup launch is enabled and then at the configured interval`() {
        val attempts = AtomicInteger()
        val launchLatch = CountDownLatch(2)
        val triggers = CopyOnWriteArrayList<FacebookImportTrigger>()
        whenever(jobService.startScheduledImports(any())).thenAnswer {
            val attempt = attempts.incrementAndGet()
            triggers.add(it.getArgument(0))
            launchLatch.countDown()
            listOf("run-$attempt")
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(
                    enabled = true,
                    runOnStartup = true,
                    interval = Duration.ofMillis(20),
                    initialDelay = Duration.ZERO,
                ),
            ),
            jobService,
            proposalService,
        )

        scheduler.start()

        assertTrue(launchLatch.await(1, TimeUnit.SECONDS))
        assertTrue(attempts.get() >= 2)
        scheduler.stop()
        assertTrue(triggers.firstOrNull() == FacebookImportTrigger.WORKER_STARTUP)
        assertTrue(triggers.drop(1).all { it == FacebookImportTrigger.SCHEDULED })
    }


    @Test
    fun `scheduler honors configured initial delay before first launch`() {
        val launchLatch = CountDownLatch(1)
        val triggers = CopyOnWriteArrayList<FacebookImportTrigger>()
        whenever(jobService.startScheduledImports(any())).thenAnswer {
            triggers.add(it.getArgument(0))
            launchLatch.countDown()
            listOf("run-1")
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(
                    enabled = true,
                    runOnStartup = true,
                    interval = Duration.ofSeconds(1),
                    initialDelay = Duration.ofMillis(100),
                ),
            ),
            jobService,
            proposalService,
        )

        scheduler.start()

        assertFalse(launchLatch.await(30, TimeUnit.MILLISECONDS))
        assertTrue(launchLatch.await(1, TimeUnit.SECONDS))
        scheduler.stop()
        assertTrue(triggers.firstOrNull() == FacebookImportTrigger.WORKER_STARTUP)
    }


    @Test
    fun `scheduled launch skips busy worker without failing`() {
        whenever(jobService.startScheduledImports(FacebookImportTrigger.SCHEDULED))
            .thenThrow(FacebookImportAlreadyRunningException())
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(schedule = FacebookImportProperties.Schedule(enabled = true)),
            jobService,
            proposalService,
        )

        assertFalse(scheduler.launchScheduledImportOnce())
    }

    @Test
    fun `stale run cleanup uses configured import timeout`() {
        whenever(proposalService.terminateTimedOutRuns(eq(Duration.ofMinutes(30)), any<Instant>())).thenReturn(listOf("run-stale"))
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(runTimeout = Duration.ofMinutes(30)),
            jobService,
            proposalService,
        )

        assertTrue(scheduler.terminateTimedOutRunsOnce().contains("run-stale"))

        verify(proposalService).terminateTimedOutRuns(eq(Duration.ofMinutes(30)), any<Instant>())
    }

    @Test
    fun `startup cleanup informs remote server when worker is remotely configured`() {
        val now = Instant.parse("2026-06-27T12:00:00Z")
        val proposalClient: FacebookImportProposalClient = mock()
        whenever(jobService.isImportRunning()).thenReturn(false)
        whenever(proposalClient.terminateAbandonedRunsOnStartup(now)).thenReturn(listOf("run-abandoned"))
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(selenium = FacebookImportProperties.Selenium(enabled = true)),
            jobService,
            proposalService,
            { now },
            proposalClient,
        )

        assertEquals(listOf("run-abandoned"), scheduler.cleanupAbandonedImportStateOnStartup())

        verify(proposalClient).terminateAbandonedRunsOnStartup(now)
    }

    @Test
    fun `startup cleanup skips passive server without local or remote worker capability`() {
        val now = Instant.parse("2026-06-27T12:00:00Z")
        val proposalClient: FacebookImportProposalClient = mock()
        whenever(jobService.isImportRunning()).thenReturn(false)
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(),
            jobService,
            proposalService,
            { now },
            proposalClient,
        )

        assertEquals(emptyList<String>(), scheduler.cleanupAbandonedImportStateOnStartup())

        verify(proposalClient, never()).terminateAbandonedRunsOnStartup(any())
    }


    @Test
    fun `stale run cleanup starts even when scheduled imports are disabled`() {
        val cleanupLatch = CountDownLatch(1)
        whenever(proposalService.terminateTimedOutRuns(any(), any<Instant>())).thenAnswer {
            cleanupLatch.countDown()
            emptyList<String>()
        }
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                staleRunCleanupInterval = Duration.ofMillis(20),
                schedule = FacebookImportProperties.Schedule(enabled = false),
            ),
            jobService,
            proposalService,
        )

        scheduler.start()

        assertTrue(cleanupLatch.await(1, TimeUnit.SECONDS))
        verify(jobService, never()).startScheduledImports(any())
        scheduler.stop()
    }

    @Test
    fun `apify scheduled launch uses apify import type independently from selenium schedule`() {
        val now = Instant.parse("2026-06-27T12:00:00Z")
        whenever(jobService.startImport(FacebookImportType.APIFY, FacebookImportTrigger.SCHEDULED)).thenReturn("run-apify")
        whenever(proposalService.latestRunTimestamp(FacebookImportType.APIFY)).thenReturn(now.minus(Duration.ofDays(2)))
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                schedule = FacebookImportProperties.Schedule(enabled = false),
                apify = FacebookImportProperties.Apify(scheduleEnabled = true),
            ),
            jobService,
            proposalService,
            { now },
        )

        assertTrue(scheduler.launchScheduledApifyImportOnce())

        verify(jobService).startImport(FacebookImportType.APIFY, FacebookImportTrigger.SCHEDULED)
        verify(jobService, never()).startScheduledImports(any())
    }

    @Test
    fun `apify scheduled launch is skipped when persisted last run is newer than interval`() {
        val now = Instant.parse("2026-06-27T12:00:00Z")
        whenever(proposalService.latestRunTimestamp(FacebookImportType.APIFY))
            .thenReturn(now.minus(Duration.ofHours(12)))
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                apify = FacebookImportProperties.Apify(
                    scheduleEnabled = true,
                    scheduleInterval = Duration.ofDays(1),
                ),
            ),
            jobService,
            proposalService,
            { now },
        )

        assertEquals(Duration.ofHours(12), scheduler.apifyDelayUntilNextEligibleRun(now))
        assertFalse(scheduler.launchScheduledApifyImportOnce())

        verify(jobService, never()).startImport(FacebookImportType.APIFY, FacebookImportTrigger.SCHEDULED)
    }

    @Test
    fun `apify scheduled launch is allowed when persisted last run is older than interval`() {
        val now = Instant.parse("2026-06-27T12:00:00Z")
        whenever(proposalService.latestRunTimestamp(FacebookImportType.APIFY))
            .thenReturn(now.minus(Duration.ofHours(25)))
        whenever(jobService.startImport(FacebookImportType.APIFY, FacebookImportTrigger.SCHEDULED)).thenReturn("run-apify")
        val scheduler = FacebookImportScheduler(
            FacebookImportProperties(
                apify = FacebookImportProperties.Apify(
                    scheduleEnabled = true,
                    scheduleInterval = Duration.ofDays(1),
                ),
            ),
            jobService,
            proposalService,
            { now },
        )

        assertEquals(Duration.ZERO, scheduler.apifyDelayUntilNextEligibleRun(now))
        assertTrue(scheduler.launchScheduledApifyImportOnce())

        verify(jobService).startImport(FacebookImportType.APIFY, FacebookImportTrigger.SCHEDULED)
    }

}
