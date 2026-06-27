// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class FacebookImportScheduler(
    private val properties: FacebookImportProperties,
    private val jobService: FacebookImportJobService,
    private val proposalService: FacebookArticleProposalService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var schedulerThread: Thread? = null
    @Volatile
    private var staleRunCleanupThread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        startStaleRunCleanup()
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

    internal fun terminateTimedOutRunsOnce(): List<String> =
        proposalService.terminateTimedOutRuns(effectiveRunTimeout())

    internal fun launchScheduledImportOnce(trigger: FacebookImportTrigger = FacebookImportTrigger.SCHEDULED): Boolean {
        if (!properties.schedule.enabled) return false
        return try {
            val importRunIds = jobService.startScheduledImports(trigger)
            if (importRunIds.isEmpty()) {
                logger.info("Skipping scheduled Facebook import because no import type is available")
                false
            } else {
                logger.info(
                    "Scheduled Facebook import accepted importRunIds={} trigger={} order={}",
                    importRunIds,
                    trigger,
                    jobService.scheduledImportTypes(),
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
            var nextTrigger = FacebookImportTrigger.WORKER_STARTUP
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

    private fun scheduleInterval(): Duration =
        properties.schedule.interval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(8)

    private fun staleRunCleanupInterval(): Duration =
        properties.staleRunCleanupInterval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofMinutes(1)

    private fun effectiveRunTimeout(): Duration =
        properties.runTimeout.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(1)

    private fun sleep(duration: Duration) {
        if (duration.isZero || duration.isNegative) return
        Thread.sleep(duration.toMillis())
    }

    @PreDestroy
    fun stop() {
        synchronized(this) {
            schedulerThread?.interrupt()
            schedulerThread = null
            staleRunCleanupThread?.interrupt()
            staleRunCleanupThread = null
        }
    }
}
