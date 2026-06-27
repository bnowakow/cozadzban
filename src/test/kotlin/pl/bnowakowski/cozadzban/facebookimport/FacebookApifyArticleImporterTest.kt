// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.slf4j.LoggerFactory

class FacebookApifyArticleImporterTest {

    @Test
    fun `unavailableReason logs the exceeded cost limit message`() {
        val proposalClient: FacebookImportProposalClient = mock()
        val importer = FacebookApifyArticleImporter(
            properties = FacebookImportProperties(
                apify = FacebookImportProperties.Apify(
                    enabled = true,
                    apiToken = "token",
                    resultsLimit = 100,
                    maxCostUsd = 0.1,
                ),
            ),
            proposalClient = proposalClient,
        )

        val logger = LoggerFactory.getLogger(FacebookApifyArticleImporter::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.addAppender(listAppender)

        try {
            val reason = importer.unavailableReason()

            assertEquals(
                "APP_FACEBOOK_IMPORT_APIFY_RESULTS_LIMIT=100 exceeds APP_FACEBOOK_IMPORT_APIFY_MAX_COST_USD=0.1",
                reason,
            )
            assertTrue(
                listAppender.list.any { event ->
                    event.formattedMessage == reason
                },
            )
        } finally {
            logger.detachAppender(listAppender)
            listAppender.stop()
        }
    }
}
