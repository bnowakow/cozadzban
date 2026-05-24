// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.stereotype.Component

@Component
class FacebookImportTasklet(
    private val importer: FacebookProfileArticleImporter,
) : Tasklet {

    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val parameters = contribution.stepExecution.jobParameters
        val importRunId = parameters.getString("facebookImportId")
            ?: throw IllegalArgumentException("facebookImportId job parameter is required")
        val trigger = parameters.getString("trigger")
            ?.let(FacebookImportTrigger::valueOf)
            ?: FacebookImportTrigger.MANUAL

        importer.runImport(importRunId, trigger)
        return RepeatStatus.FINISHED
    }
}
