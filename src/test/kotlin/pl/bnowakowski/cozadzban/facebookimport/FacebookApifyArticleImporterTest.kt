// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
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

    @Test
    fun `proposal urls include plain text links and anchor hrefs while ignoring facebook image cdn urls`() {
        val proposalClient: FacebookImportProposalClient = mock()
        val importer = FacebookApifyArticleImporter(
            properties = FacebookImportProperties(
                apify = FacebookImportProperties.Apify(enabled = true, apiToken = "token"),
            ),
            proposalClient = proposalClient,
        )
        val objectMapper = ObjectMapper()
        val item = objectMapper.readTree(
            """
            {
              "text": "Check <a href=\"https://example.com/article\">the article</a>, visit https://example.org/news, <a href=\"https://scontent-bos5-1.xx.fbcdn.net/v/t39.3088-6/123.jpg\">photo</a>, <img src=\"https://scontent-waw2-2.cdninstagram.com/v/t51.82787-15/image.jpg\">, and <a href=\"https://static.xx.fbcdn.net/rsrc.php/v4/yD/r/icon.png\">icon</a>"
            }
            """.trimIndent(),
        )

        val urls = importer.proposalUrlsFromItem(item)

        assertEquals(listOf("https://example.com/article", "https://example.org/news"), urls)
    }
}
