// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.JobInstance
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.ObjectProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FacebookImportJobServiceTest {

    private val jobOperator: JobOperator = mock()
    private val job: Job = mock()
    private val importer: FacebookProfileArticleImporter = mock()
    private val proposalService: FacebookArticleProposalService = mock()
    private val jobOperatorProvider: ObjectProvider<JobOperator> = mock()
    private val jobProvider: ObjectProvider<Job> = mock()

    @Test
    fun `startImport launches batch job with facebook import parameters`() {
        val latch = CountDownLatch(1)
        whenever(jobOperatorProvider.getIfAvailable()).thenReturn(jobOperator)
        whenever(jobProvider.getIfAvailable()).thenReturn(job)
        whenever(importer.facebookImportUnavailableReason()).thenReturn(null)
        whenever(importer.newImportRunId(any())).thenReturn("run-1")
        whenever(importer.isImportRunning()).thenReturn(false)
        whenever(jobOperator.start(eq(job), any())).thenAnswer { invocation ->
            latch.countDown()
            JobExecution(
                99L,
                JobInstance(1L, FACEBOOK_IMPORT_JOB_NAME),
                invocation.getArgument(1),
            )
        }
        val service = FacebookImportJobService(jobOperatorProvider, jobProvider, importer, proposalService)

        assertEquals("run-1", service.startImport())
        assertTrue(latch.await(1, TimeUnit.SECONDS))

        val parameters = argumentCaptor<JobParameters>()
        verify(jobOperator).start(eq(job), parameters.capture())
        assertEquals("run-1", parameters.firstValue.getString("facebookImportId"))
        assertEquals(FacebookImportTrigger.MANUAL.name, parameters.firstValue.getString("trigger"))
    }

    @Test
    fun `startImport launches scheduled batch job with scheduled trigger`() {
        val latch = CountDownLatch(1)
        whenever(jobOperatorProvider.getIfAvailable()).thenReturn(jobOperator)
        whenever(jobProvider.getIfAvailable()).thenReturn(job)
        whenever(importer.facebookImportUnavailableReason()).thenReturn(null)
        whenever(importer.newImportRunId(any())).thenReturn("run-scheduled")
        whenever(importer.isImportRunning()).thenReturn(false)
        whenever(jobOperator.start(eq(job), any())).thenAnswer { invocation ->
            latch.countDown()
            JobExecution(
                100L,
                JobInstance(2L, FACEBOOK_IMPORT_JOB_NAME),
                invocation.getArgument(1),
            )
        }
        val service = FacebookImportJobService(jobOperatorProvider, jobProvider, importer, proposalService)

        assertEquals("run-scheduled", service.startImport(FacebookImportTrigger.SCHEDULED))
        assertTrue(latch.await(1, TimeUnit.SECONDS))

        val parameters = argumentCaptor<JobParameters>()
        verify(jobOperator).start(eq(job), parameters.capture())
        assertEquals("run-scheduled", parameters.firstValue.getString("facebookImportId"))
        assertEquals(FacebookImportTrigger.SCHEDULED.name, parameters.firstValue.getString("trigger"))
    }

    @Test
    fun `startImport rejects overlapping manual and scheduled launches`() {
        val latch = CountDownLatch(1)
        whenever(jobOperatorProvider.getIfAvailable()).thenReturn(jobOperator)
        whenever(jobProvider.getIfAvailable()).thenReturn(job)
        whenever(importer.facebookImportUnavailableReason()).thenReturn(null)
        whenever(importer.newImportRunId(any())).thenReturn("run-1", "run-2")
        whenever(importer.isImportRunning()).thenReturn(false)
        whenever(jobOperator.start(eq(job), any())).thenAnswer {
            latch.countDown()
            Thread.sleep(5_000)
            JobExecution(99L, JobInstance(1L, FACEBOOK_IMPORT_JOB_NAME), it.getArgument(1))
        }
        val service = FacebookImportJobService(jobOperatorProvider, jobProvider, importer, proposalService)

        service.startImport()
        assertTrue(latch.await(1, TimeUnit.SECONDS))

        assertThrows(FacebookImportAlreadyRunningException::class.java) {
            service.startImport(FacebookImportTrigger.SCHEDULED)
        }

        service.terminateImport()
        waitUntil { !service.isImportRunning() }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition())
    }
}
