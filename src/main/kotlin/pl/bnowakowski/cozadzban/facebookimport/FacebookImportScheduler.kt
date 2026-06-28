// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

@Component
class FacebookImportScheduler(
    private val properties: FacebookImportProperties,
    private val jobService: FacebookImportJobService,
    private val proposalService: FacebookArticleProposalService,
    private val clock: Supplier<Instant> = Supplier { Instant.now() },
    private val proposalClient: FacebookImportProposalClient = FacebookImportProposalClient(properties, proposalService),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var schedulerThread: Thread? = null
    @Volatile
    private var apifySchedulerThread: Thread? = null
    @Volatile
    private var staleRunCleanupThread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        cleanupAbandonedImportStateOnStartup()
        startStaleRunCleanup()
        startApifySchedule()
        if (!properties.schedule.enabled) {
            logger.info("Scheduled Facebook import is disabled")
            return
        }
        synchronized(this) {
            if (schedulerThread?.isAlive == true) return
            schedulerThread = Thread {
                runScheduleLoop()
            }.apply {
                name = "facebook-import-scheduler"
                isDaemon = true
                start()
            }
        }
    }

    internal fun launchScheduledApifyImportOnce(trigger: FacebookImportTrigger = FacebookImportTrigger.SCHEDULED): Boolean =
        if (isApifyImportAllowedNow(clock.get())) {
            launchScheduledImportOnce(FacebookImportType.APIFY, trigger)
        } else {
            false
        }

    internal fun terminateTimedOutRunsOnce(): List<String> =
        proposalService.terminateTimedOutRuns(effectiveRunTimeout())

    internal fun cleanupAbandonedImportStateOnStartup(): List<String> =
        if (jobService.isImportRunning() || !shouldCleanupAbandonedImportStateOnStartup()) {
            emptyList()
        } else {
            runCatching {
                proposalClient.terminateAbandonedRunsOnStartup(clock.get())
            }.onFailure { ex ->
                logger.warn("Facebook import startup abandoned-run cleanup failed", ex)
            }.getOrElse { emptyList() }
        }.also { terminated ->
            if (terminated.isNotEmpty()) {
                logger.warn("Terminated {} abandoned Facebook import run(s) on startup", terminated.size)
            }
        }

    internal fun launchScheduledImportOnce(trigger: FacebookImportTrigger = FacebookImportTrigger.SCHEDULED): Boolean {
        if (!properties.schedule.enabled) return false
        return launchScheduledImportOnce(null, trigger)
    }

    private fun launchScheduledImportOnce(
        singleImportType: FacebookImportType?,
        trigger: FacebookImportTrigger = FacebookImportTrigger.SCHEDULED,
    ): Boolean {
        return try {
            val importRunIds = if (singleImportType == null) {
                jobService.startScheduledImports(trigger)
            } else {
                listOf(jobService.startImport(singleImportType, trigger))
            }
            if (importRunIds.isEmpty()) {
                logger.info("Skipping scheduled Facebook import because no import type is available")
                false
            } else {
                logger.info(
                    "Scheduled Facebook import accepted importRunIds={} trigger={} order={}",
                    importRunIds,
                    trigger,
                    jobService.scheduledImportTypes(trigger),
                )
                true
            }
        } catch (_: FacebookImportAlreadyRunningException) {
            logger.info("Skipping scheduled Facebook import because another import is already running")
            false
        } catch (ex: Exception) {
            logger.warn(
                "Scheduled Facebook import could not be launched trigger={}: {}",
                trigger,
                ex.message ?: ex.javaClass.simpleName,
                ex,
            )
            false
        }
    }

    private fun startApifySchedule() {
        if (!properties.apify.scheduleEnabled) {
            logger.info("Scheduled Facebook Apify import is disabled")
            return
        }
        synchronized(this) {
            if (apifySchedulerThread?.isAlive == true) return
            apifySchedulerThread = Thread {
                runApifyScheduleLoop()
            }.apply {
                name = "facebook-apify-import-scheduler"
                isDaemon = true
                start()
            }
        }
    }

    private fun startStaleRunCleanup() {
        synchronized(this) {
            if (staleRunCleanupThread?.isAlive == true) return
            staleRunCleanupThread = Thread {
                runStaleRunCleanupLoop()
            }.apply {
                name = "facebook-import-stale-run-cleanup"
                isDaemon = true
                start()
            }
        }
    }

    private fun runStaleRunCleanupLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val terminated = runCatching { terminateTimedOutRunsOnce() }
                    .onFailure { ex ->
                        logger.warn("Facebook import stale-run cleanup failed", ex)
                    }
                    .getOrElse { emptyList() }
                if (terminated.isNotEmpty()) {
                    logger.warn("Terminated {} stale Facebook import run(s)", terminated.size)
                }
                sleep(staleRunCleanupInterval())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.info("Facebook import stale-run cleanup stopped")
        }
    }

    private fun runScheduleLoop() {
        try {
            sleep(properties.schedule.initialDelay)
            var nextTrigger = if (properties.schedule.runOnStartup) {
                FacebookImportTrigger.WORKER_STARTUP
            } else {
                sleep(scheduleInterval())
                FacebookImportTrigger.SCHEDULED
            }
            while (!Thread.currentThread().isInterrupted) {
                launchScheduledImportOnce(nextTrigger)
                nextTrigger = FacebookImportTrigger.SCHEDULED
                sleep(scheduleInterval())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.info("Facebook import scheduler stopped")
        }
    }

    private fun runApifyScheduleLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val sleepDuration = apifyDelayUntilNextEligibleRun(clock.get())
                sleep(sleepDuration)
                if (!Thread.currentThread().isInterrupted) {
                    launchScheduledApifyImportOnce()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.info("Facebook Apify import scheduler stopped")
        }
    }

    private fun scheduleInterval(): Duration =
        properties.schedule.interval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(8)

    private fun apifyScheduleInterval(): Duration =
        properties.apify.scheduleInterval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofDays(1)

    internal fun apifyDelayUntilNextEligibleRun(now: Instant = Instant.now()): Duration {
        val interval = apifyScheduleInterval()
        val latestRunAt = runCatching { proposalService.latestRunTimestamp(FacebookImportType.APIFY) }
            .onFailure { ex -> logger.warn("Facebook Apify latest-run lookup failed; delaying one interval", ex) }
            .getOrNull() ?: return interval
        val elapsed = Duration.between(latestRunAt, now)
        return if (elapsed >= interval) Duration.ZERO else interval.minus(elapsed)
    }

    private fun isApifyImportAllowedNow(now: Instant): Boolean {
        val delay = apifyDelayUntilNextEligibleRun(now)
        if (delay.isZero || delay.isNegative) return true
        logger.info("Skipping scheduled Facebook Apify import; next eligible run in {}", delay)
        return false
    }

    private fun staleRunCleanupInterval(): Duration =
        properties.staleRunCleanupInterval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofMinutes(1)

    private fun effectiveRunTimeout(): Duration =
        properties.runTimeout.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(1)

    private fun shouldCleanupAbandonedImportStateOnStartup(): Boolean =
        properties.isSeleniumEnabled() || properties.apify.enabled

    private fun sleep(duration: Duration) {
        if (duration.isZero || duration.isNegative) return
        Thread.sleep(duration.toMillis())
    }

    @PreDestroy
    fun stop() {
        synchronized(this) {
            schedulerThread?.interrupt()
            schedulerThread = null
            apifySchedulerThread?.interrupt()
            apifySchedulerThread = null
            staleRunCleanupThread?.interrupt()
            staleRunCleanupThread = null
        }
    }
}
