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

    @Test
    fun `proposal urls fall back to shared facebook post when apify item has marker but no external article url`() {
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
              "facebookUrl": "https://www.facebook.com/bartek.dobrowolski.nowakowski/",
              "postId": "10242248758499042",
              "url": "https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid02q17wouWvem6ZdSi4CRnou1cnenRv5ngKeU8uwwVfwTE3CXksyhPttqD1BWcW39Gtl",
              "text": "Co za dzban",
              "sharedPost": {
                "url": "https://www.facebook.com/TheIndependentOnline/posts/pfbid02Jd1sh6WRFUR86UuXDJTYxHrDBHyERTQQW5kZ96dFb3L9tJSHpJdSZ3P4MsN7qyM7l",
                "text": "Trump has claimed Reflecting Pool vandals could face years in jail.",
                "media": [
                  {
                    "thumbnail": "https://scontent-iad6-1.xx.fbcdn.net/v/t39.99422-6/731199809_1796461034855739_2848968807400154079_n.png",
                    "url": "https://www.facebook.com/photo/?fbid=1565111552311499&set=a.395690795920253"
                  }
                ]
              },
              "topLevelUrl": "https://www.facebook.com/1342758879/posts/10242248758499042"
            }
            """.trimIndent(),
        )

        val urls = importer.proposalUrlsFromItem(item)

        assertEquals(
            listOf("https://www.facebook.com/TheIndependentOnline/posts/pfbid02Jd1sh6WRFUR86UuXDJTYxHrDBHyERTQQW5kZ96dFb3L9tJSHpJdSZ3P4MsN7qyM7l"),
            urls,
        )
    }
}
