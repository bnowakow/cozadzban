// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class FacebookImportJobService(
    private val jobOperatorProvider: ObjectProvider<JobOperator>,
    @Qualifier(FACEBOOK_IMPORT_JOB_NAME) private val facebookImportJobProvider: ObjectProvider<Job>,
    private val importer: FacebookProfileArticleImporter,
    private val proposalService: FacebookArticleProposalService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val stateLock = Any()

    @Volatile
    private var activeLaunchThread: Thread? = null
    @Volatile
    private var activeImportRunId: String? = null
    @Volatile
    private var activeImportStartedAt: Instant? = null

    fun startImport(trigger: FacebookImportTrigger = FacebookImportTrigger.MANUAL): String {
        facebookImportUnavailableReason()?.let { throw IllegalArgumentException(it) }
        val jobOperator = jobOperatorProvider.getIfAvailable()
            ?: throw IllegalArgumentException("Spring Batch job infrastructure is unavailable")
        val facebookImportJob = facebookImportJobProvider.getIfAvailable()
            ?: throw IllegalArgumentException("Facebook import Spring Batch job is unavailable")

        val importRunId = importer.newImportRunId()
        val requestedAt = Instant.now()
        synchronized(stateLock) {
            if (activeLaunchThread?.isAlive == true || importer.isImportRunning()) {
                throw FacebookImportAlreadyRunningException()
            }

            val launchThread = Thread {
                try {
                    val execution = jobOperator.start(
                        facebookImportJob,
                        JobParametersBuilder()
                            .addString("facebookImportId", importRunId)
                            .addString("trigger", trigger.name)
                            .addString("requestedAt", Instant.now().toString())
                            .toJobParameters(),
                    )
                    logger.info(
                        "Facebook import batch job completed importRunId={} trigger={} jobExecutionId={} status={}",
                        importRunId,
                        trigger,
                        execution.id,
                        execution.status,
                    )
                } catch (ex: Exception) {
                    if (Thread.currentThread().isInterrupted) {
                        logger.info(
                            "Facebook import batch launch interrupted importRunId={} trigger={}",
                            importRunId,
                            trigger,
                        )
                    } else {
                        logger.warn(
                            "Facebook import batch launch failed importRunId={} trigger={}",
                            importRunId,
                            trigger,
                            ex,
                        )
                    }
                } finally {
                    synchronized(stateLock) {
                        if (activeLaunchThread === Thread.currentThread()) {
                            activeLaunchThread = null
                            activeImportRunId = null
                            activeImportStartedAt = null
                        }
                    }
                }
            }.apply {
                name = "facebook-import-batch-launch"
                isDaemon = true
            }
            activeLaunchThread = launchThread
            activeImportRunId = importRunId
            activeImportStartedAt = requestedAt
            launchThread.start()
        }
        return importRunId
    }

    fun terminateImport() {
        val thread = synchronized(stateLock) {
            activeLaunchThread?.takeIf { it.isAlive }
        }
        if (thread == null && !importer.isImportRunning()) {
            throw FacebookImportNotRunningException()
        }

        val importerTermination = runCatching { importer.terminateImport() }.exceptionOrNull()
        if (importerTermination != null && importerTermination !is FacebookImportNotRunningException) {
            throw importerTermination
        }
        thread?.interrupt()
    }

    fun isImportRunning(): Boolean =
        activeLaunchThread?.isAlive == true || importer.isImportRunning()

    fun currentProgress(): FacebookImportProgressSnapshot? {
        proposalService.latestRunningProgress()?.let { return it }
        val fallback = synchronized(stateLock) {
            if (activeLaunchThread?.isAlive != true && !importer.isImportRunning()) {
                null
            } else {
                activeImportRunId?.let { importRunId ->
                    importRunId to (activeImportStartedAt ?: Instant.now())
                }
            }
        } ?: return null

        importer.currentProgressSnapshot()
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

    fun facebookImportUnavailableReason(): String? =
        importer.facebookImportUnavailableReason()
}
