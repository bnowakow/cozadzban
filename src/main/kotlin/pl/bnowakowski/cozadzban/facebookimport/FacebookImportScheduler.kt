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
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var schedulerThread: Thread? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
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

    internal fun launchScheduledImportOnce(): Boolean {
        if (!properties.schedule.enabled) return false
        return try {
            val importRunId = jobService.startImport(FacebookImportTrigger.SCHEDULED)
            logger.info("Scheduled Facebook import accepted importRunId={}", importRunId)
            true
        } catch (_: FacebookImportAlreadyRunningException) {
            logger.info("Skipping scheduled Facebook import because another import is already running")
            false
        } catch (ex: Exception) {
            logger.warn("Scheduled Facebook import could not be launched: {}", ex.message ?: ex.javaClass.simpleName, ex)
            false
        }
    }

    private fun runScheduleLoop() {
        try {
            sleep(properties.schedule.initialDelay)
            while (!Thread.currentThread().isInterrupted) {
                launchScheduledImportOnce()
                sleep(scheduleInterval())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.info("Facebook import scheduler stopped")
        }
    }

    private fun scheduleInterval(): Duration =
        properties.schedule.interval.takeIf { !it.isNegative && !it.isZero } ?: Duration.ofHours(8)

    private fun sleep(duration: Duration) {
        if (duration.isZero || duration.isNegative) return
        Thread.sleep(duration.toMillis())
    }

    @PreDestroy
    fun stop() {
        synchronized(this) {
            schedulerThread?.interrupt()
            schedulerThread = null
        }
    }
}
