// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.transaction.PlatformTransactionManager

const val FACEBOOK_IMPORT_JOB_NAME = "facebookImportJob"
const val FACEBOOK_IMPORT_STEP_NAME = "facebookImportStep"

@Configuration(proxyBeanMethods = false)
@Conditional(FacebookImportJdbcBatchCondition::class)
@EnableBatchProcessing
@EnableJdbcJobRepository
class FacebookImportBatchConfiguration {

    @Bean(FACEBOOK_IMPORT_JOB_NAME)
    fun facebookImportJob(
        jobRepository: JobRepository,
        facebookImportStep: Step,
    ): Job =
        JobBuilder(FACEBOOK_IMPORT_JOB_NAME, jobRepository)
            .start(facebookImportStep)
            .build()

    @Bean(FACEBOOK_IMPORT_STEP_NAME)
    fun facebookImportStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        tasklet: FacebookImportTasklet,
    ): Step =
        StepBuilder(FACEBOOK_IMPORT_STEP_NAME, jobRepository)
            .tasklet(tasklet, transactionManager)
            .build()
}

private class FacebookImportJdbcBatchCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val excludedAutoConfigurations = context.environment
            .getProperty("spring.autoconfigure.exclude")
            .orEmpty()
            .split(',')
            .map { it.trim() }

        return excludedAutoConfigurations.none { it.endsWith("DataSourceAutoConfiguration") }
    }
}
