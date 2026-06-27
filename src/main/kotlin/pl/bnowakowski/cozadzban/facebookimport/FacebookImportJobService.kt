// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class FacebookImportJobService @Autowired constructor(
    private val jobOperatorProvider: ObjectProvider<JobOperator>,
    @Qualifier(FACEBOOK_IMPORT_JOB_NAME) private val facebookImportJobProvider: ObjectProvider<Job>,
    runners: List<FacebookImportRunner>,
    private val proposalService: FacebookArticleProposalService,
    private val properties: FacebookImportProperties = FacebookImportProperties(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val runnersByType: Map<FacebookImportType, FacebookImportRunner> = buildMap {
        runners.forEach { runner ->
            val importType: FacebookImportType? = runCatching { runner.importType }.getOrNull()
            put(importType ?: FacebookImportType.SELENIUM, runner)
        }
    }
    private val stateLock = Any()

    @Volatile private var activeLaunchThread: Thread? = null
    @Volatile private var activeTimeoutThread: Thread? = null
    @Volatile private var activeImportRunId: String? = null
    @Volatile private var activeImportStartedAt: Instant? = null
    @Volatile private var activeImportTrigger: FacebookImportTrigger? = null
    @Volatile private var activeImportType: FacebookImportType? = null

    constructor(
        jobOperatorProvider: ObjectProvider<JobOperator>,
        facebookImportJobProvider: ObjectProvider<Job>,
        importer: FacebookProfileArticleImporter,
        proposalService: FacebookArticleProposalService,
        properties: FacebookImportProperties = FacebookImportProperties(),
    ) : this(jobOperatorProvider, facebookImportJobProvider, listOf(importer), proposalService, properties)

    fun startImport(
        importType: FacebookImportType = FacebookImportType.SELENIUM,
        trigger: FacebookImportTrigger = FacebookImportTrigger.MANUAL,
    ): String {
        val importer = runner(importType)
        facebookImportUnavailableReason(importType)?.let { throw IllegalArgumentException(it) }
        val jobOperator = jobOperatorProvider.getIfAvailable()
            ?: throw IllegalArgumentException("Spring Batch job infrastructure is unavailable")
        val facebookImportJob = facebookImportJobProvider.getIfAvailable()
            ?: throw IllegalArgumentException("Facebook import Spring Batch job is unavailable")

        val importRunId = importer.newImportRunId()
        val requestedAt = Instant.now()
        val runTimeout = effectiveRunTimeout()
        proposalService.terminateTimedOutRuns(runTimeout, requestedAt)
        terminateActiveImportIfTimedOut(requestedAt, runTimeout)
        synchronized(stateLock) {
            if (activeLaunchThread?.isAlive == true || runnersByType.values.any { it.isImportRunning() }) {
                throw FacebookImportAlreadyRunningException()
            }

            val launchThread = Thread {
                logger.info(
                    "Facebook import batch job started importRunId={} importType={} trigger={} timeout={}",
                    importRunId,
                    importType,
                    trigger,
                    runTimeout,
                )
                try {
                    val execution = jobOperator.start(
                        facebookImportJob,
                        JobParametersBuilder()
                            .addString("facebookImportId", importRunId)
                            .addString("importType", importType.name)
                            .addString("trigger", trigger.name)
                            .addString("requestedAt", Instant.now().toString())
                            .toJobParameters(),
                    )
                    logger.info(
                        "Facebook import batch job completed importRunId={} importType={} trigger={} jobExecutionId={} status={} durationMs={}",
                        importRunId,
                        importType,
                        trigger,
                        execution.id,
                        execution.status,
                        elapsedMs(requestedAt),
                    )
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.info(
                        "Facebook import batch launch interrupted importRunId={} importType={} trigger={} durationMs={}",
                        importRunId,
                        importType,
                        trigger,
                        elapsedMs(requestedAt),
                    )
                } catch (ex: Exception) {
                    if (Thread.currentThread().isInterrupted) {
                        logger.info(
                            "Facebook import batch launch interrupted importRunId={} importType={} trigger={} durationMs={}",
                            importRunId,
                            importType,
                            trigger,
                            elapsedMs(requestedAt),
                        )
                    } else {
                        logger.warn(
                            "Facebook import batch launch failed importRunId={} importType={} trigger={} durationMs={}",
                            importRunId,
                            importType,
                            trigger,
                            elapsedMs(requestedAt),
                            ex,
                        )
                    }
                } finally {
                    clearActiveLaunch(Thread.currentThread())
                }
            }.apply {
                name = "facebook-import-batch-launch"
                isDaemon = true
            }
            val timeoutThread = timeoutThread(importRunId, trigger, requestedAt, runTimeout)
            activeLaunchThread = launchThread
            activeTimeoutThread = timeoutThread
            activeImportRunId = importRunId
            activeImportStartedAt = requestedAt
            activeImportTrigger = trigger
            activeImportType = importType
            launchThread.start()
            timeoutThread.start()
        }
        return importRunId
    }

    fun startImport(trigger: FacebookImportTrigger): String =
        startImport(FacebookImportType.SELENIUM, trigger)

    fun startScheduledImports(trigger: FacebookImportTrigger = FacebookImportTrigger.SCHEDULED): List<String> {
        val launched = mutableListOf<String>()
        scheduledImportTypes().forEach { importType ->
            launched += startImport(importType, trigger)
            waitUntilIdle()
        }
        return launched
    }

    fun terminateImport() {
        val thread = synchronized(stateLock) {
            activeLaunchThread?.takeIf { it.isAlive }
        }
        val activeImporter = activeImportType?.let { runnersByType[it] }
        if (thread == null && activeImporter?.isImportRunning() != true) {
            throw FacebookImportNotRunningException()
        }

        val importerTermination = runCatching { activeImporter?.terminateImport() }.exceptionOrNull()
        if (importerTermination != null && importerTermination !is FacebookImportNotRunningException) {
            throw importerTermination
        }
        thread?.interrupt()
    }

    fun isImportRunning(): Boolean =
        activeLaunchThread?.isAlive == true || runnersByType.values.any { it.isImportRunning() }

    fun currentProgress(): FacebookImportProgressSnapshot? {
        val now = Instant.now()
        val runTimeout = effectiveRunTimeout()
        val activeTimedOut = terminateActiveImportIfTimedOut(now, runTimeout)
        proposalService.terminateTimedOutRuns(runTimeout, now)
        proposalService.latestRunningProgress()?.let { return it }
        if (activeTimedOut) return null
        val fallback = synchronized(stateLock) {
            if (activeLaunchThread?.isAlive != true && runnersByType.values.none { it.isImportRunning() }) {
                null
            } else {
                activeImportRunId?.let { importRunId ->
                    importRunId to (activeImportStartedAt ?: Instant.now())
                }
            }
        } ?: return null

        activeImportType
            ?.let { runnersByType[it] }
            ?.currentProgressSnapshot()
            ?.takeIf { it.importRunId == fallback.first }
            ?.let { return it }

        return FacebookImportProgressSnapshot(
            importRunId = fallback.first,
            status = FacebookImportRunStatus.RUNNING,
            startedAt = fallback.second,
            lastUpdatedAt = Instant.now(),
            phase = FacebookImportProgressPhase.STARTING.label,
            detail = null,
            phaseIndex = FacebookImportProgressPhase.STARTING.phaseIndex,
            phaseCount = FACEBOOK_IMPORT_PROGRESS_PHASE_COUNT,
            passIndex = 0,
            passCount = 0,
            matchedPostCount = 0,
            submittedCount = 0,
            skippedExistingCount = 0,
            failedCount = 0,
        )
    }

    fun facebookImportUnavailableReason(importType: FacebookImportType = FacebookImportType.SELENIUM): String? =
        (runnersByType[importType] ?: runnersByType.values.singleOrNull())
            ?.unavailableReason()
            ?: if (runnersByType[importType] != null || runnersByType.values.size == 1) {
                null
            } else {
                "Facebook import runner $importType is unavailable"
            }

    fun availableImportTypes(): List<FacebookImportType> =
        FacebookImportType.entries.filter { facebookImportUnavailableReason(it) == null }

    fun scheduledImportTypes(): List<FacebookImportType> =
        listOf(FacebookImportType.API, FacebookImportType.SELENIUM).filter { facebookImportUnavailableReason(it) == null }

    private fun timeoutThread(
        importRunId: String,
        trigger: FacebookImportTrigger,
        requestedAt: Instant,
        timeout: Duration,
    ): Thread =
        Thread {
            try {
                Thread.sleep(timeout.toMillis().coerceAtLeast(1))
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            terminateTimedOutActiveImport(importRunId, trigger, requestedAt, timeout)
        }.apply {
            name = "facebook-import-timeout"
            isDaemon = true
        }

    private fun terminateActiveImportIfTimedOut(now: Instant, timeout: Duration): Boolean {
        val active = synchronized(stateLock) {
            val importRunId = activeImportRunId ?: return false
            val startedAt = activeImportStartedAt ?: return false
            val trigger = activeImportTrigger ?: FacebookImportTrigger.MANUAL
            val running = activeLaunchThread?.isAlive == true || runnersByType.values.any { it.isImportRunning() }
            if (!running || Duration.between(startedAt, now) < timeout) return false
            ActiveImport(importRunId, trigger, startedAt)
        }
        terminateTimedOutActiveImport(active.importRunId, active.trigger, active.startedAt, timeout)
        return true
    }

    private fun terminateTimedOutActiveImport(
        importRunId: String,
        trigger: FacebookImportTrigger,
        requestedAt: Instant,
        timeout: Duration,
    ) {
        val launchThread = synchronized(stateLock) {
            if (activeImportRunId != importRunId) return
            val running = activeLaunchThread?.isAlive == true || runnersByType.values.any { it.isImportRunning() }
            if (!running) return
            activeLaunchThread
        }
        val timedOutAt = Instant.now()
        logger.warn(
            "Facebook import run timed out importRunId={} trigger={} timeout={} elapsedMs={}; terminating",
            importRunId,
            trigger,
            timeout,
            elapsedMs(requestedAt, timedOutAt),
        )
        val importerTermination = runCatching { activeImportType?.let { runnersByType[it] }?.terminateImport() }
            .exceptionOrNull()
        if (importerTermination != null && importerTermination !is FacebookImportNotRunningException) {
            logger.warn("Facebook import {} timeout termination failed", importRunId, importerTermination)
        }
        launchThread?.interrupt()
        runCatching {
            proposalService.terminateTimedOutRun(importRunId, timeout, timedOutAt)
        }.onFailure { ex ->
            logger.warn("Facebook import {} could not persist timeout status", importRunId, ex)
        }
    }

    private fun clearActiveLaunch(thread: Thread) {
        synchronized(stateLock) {
            if (activeLaunchThread === thread) {
                activeLaunchThread = null
                activeTimeoutThread?.interrupt()
                activeTimeoutThread = null
                activeImportRunId = null
                activeImportStartedAt = null
                activeImportTrigger = null
                activeImportType = null
            }
        }
    }

    private fun runner(importType: FacebookImportType): FacebookImportRunner =
        runnersByType[importType]
            ?: runnersByType.values.singleOrNull()
            ?: throw IllegalArgumentException("Facebook import runner $importType is unavailable")

    private fun waitUntilIdle() {
        while (isImportRunning()) {
            Thread.sleep(100)
        }
    }

    private fun effectiveRunTimeout(): Duration =
        properties.runTimeout.takeIf { !it.isZero && !it.isNegative } ?: DEFAULT_RUN_TIMEOUT

    private fun elapsedMs(startedAt: Instant, finishedAt: Instant = Instant.now()): Long =
        Duration.between(startedAt, finishedAt).toMillis().coerceAtLeast(0)

    private data class ActiveImport(
        val importRunId: String,
        val trigger: FacebookImportTrigger,
        val startedAt: Instant,
    )

    private companion object {
        val DEFAULT_RUN_TIMEOUT: Duration = Duration.ofHours(1)
    }
}
