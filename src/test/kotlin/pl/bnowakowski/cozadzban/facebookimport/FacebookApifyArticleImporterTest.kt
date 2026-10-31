// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Instant

class FacebookApifyArticleImporterTest {

    @Test
    fun `records the actual cost and actor run ID for the imported dataset`() {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val response = when (exchange.requestURI.path) {
                "/v2/acts/apify~facebook-posts-scraper/runs" ->
                    """{"data":{"id":"actor-run-1","status":"SUCCEEDED","defaultDatasetId":"dataset-1"}}"""
                "/v2/actor-runs/actor-run-1" ->
                    """{"data":{"id":"actor-run-1","status":"SUCCEEDED","defaultDatasetId":"dataset-1","usageTotalUsd":0.127}}"""
                "/v2/datasets/dataset-1/items" -> "[]"
                else -> error("Unexpected Apify request: ${exchange.requestURI}")
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val proposalClient: FacebookImportProposalClient = mock()
            val importer = FacebookApifyArticleImporter(
                properties = FacebookImportProperties(
                    apify = FacebookImportProperties.Apify(
                        enabled = true,
                        apiToken = "token",
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    ),
                ),
                proposalClient = proposalClient,
            )

            importer.runImport("import-1", FacebookImportTrigger.SCHEDULED)

            val completion = argumentCaptor<FacebookImportRunCompletionRequest>()
            verify(proposalClient).completeRun(eq("import-1"), completion.capture())
            assertEquals(FacebookImportRunStatus.FINISHED, completion.firstValue.status)
            assertEquals("actor-run-1", completion.firstValue.apifyActorRunId)
            assertEquals(BigDecimal("0.127"), completion.firstValue.apifyCostUsd)
            assertEquals(
                listOf(
                    "POST /v2/acts/apify~facebook-posts-scraper/runs",
                    "GET /v2/actor-runs/actor-run-1",
                    "GET /v2/datasets/dataset-1/items",
                ),
                requests,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `does not start an Apify run when the remaining credit cannot cover one`() {
        val requests = mutableListOf<String>()
        withApify(requests, limitsUsedUsd = "4.95") { baseUrl ->
            val proposalClient: FacebookImportProposalClient = mock()
            val importer = importerWithUsage(baseUrl, proposalClient)

            val failure = assertThrows(ApifyCreditsExhaustedException::class.java) {
                importer.runImport("import-credits", FacebookImportTrigger.SCHEDULED)
            }

            assertTrue(failure.message!!.contains("\$0.05 left"), failure.message)
            assertEquals(listOf("GET /v2/users/me/limits"), requests, "no actor run may be started")
            val completion = argumentCaptor<FacebookImportRunCompletionRequest>()
            verify(proposalClient).completeRun(eq("import-credits"), completion.capture())
            assertEquals(FacebookImportRunStatus.FAILED, completion.firstValue.status)
            assertTrue(completion.firstValue.apifyCreditsExhausted)
            assertEquals(0, BigDecimal("0.05").compareTo(completion.firstValue.apifyRemainingUsd))
            assertEquals(Instant.parse("2026-11-26T23:59:59.999Z"), completion.firstValue.apifyUsageCycleEndAt)
            assertTrue(completion.firstValue.statusDetail!!.contains("Apify credits are exhausted"))
        }
    }

    @Test
    fun `an Apify payment error when starting the run is reported as exhausted credits`() {
        val requests = mutableListOf<String>()
        withApify(
            requests,
            limitsUsedUsd = "2.00",
            startStatus = 402,
            startBody = """{"error":{"type":"not-enough-usage-to-run-paid-actor","message":"Not enough usage"}}""",
        ) { baseUrl ->
            val proposalClient: FacebookImportProposalClient = mock()

            assertThrows(ApifyCreditsExhaustedException::class.java) {
                importerWithUsage(baseUrl, proposalClient).runImport("import-402", FacebookImportTrigger.MANUAL)
            }

            val completion = argumentCaptor<FacebookImportRunCompletionRequest>()
            verify(proposalClient).completeRun(eq("import-402"), completion.capture())
            assertTrue(completion.firstValue.apifyCreditsExhausted)
            assertTrue(completion.firstValue.statusDetail!!.contains("not-enough-usage-to-run-paid-actor"))
        }
    }

    @Test
    fun `other Apify errors are not reported as exhausted credits`() {
        val requests = mutableListOf<String>()
        withApify(
            requests,
            limitsUsedUsd = "2.00",
            startStatus = 400,
            startBody = """{"error":{"type":"invalid-input","message":"Bad input"}}""",
        ) { baseUrl ->
            val proposalClient: FacebookImportProposalClient = mock()

            assertThrows(Exception::class.java) {
                importerWithUsage(baseUrl, proposalClient).runImport("import-400", FacebookImportTrigger.MANUAL)
            }

            val completion = argumentCaptor<FacebookImportRunCompletionRequest>()
            verify(proposalClient).completeRun(eq("import-400"), completion.capture())
            assertEquals(FacebookImportRunStatus.FAILED, completion.firstValue.status)
            assertFalse(completion.firstValue.apifyCreditsExhausted)
        }
    }

    private fun importerWithUsage(baseUrl: String, proposalClient: FacebookImportProposalClient): FacebookApifyArticleImporter {
        val properties = FacebookImportProperties(
            apify = FacebookImportProperties.Apify(enabled = true, apiToken = "token", baseUrl = baseUrl),
        )
        return FacebookApifyArticleImporter(properties, proposalClient, ApifyAccountUsageClient(properties))
    }

    private fun withApify(
        requests: MutableList<String>,
        limitsUsedUsd: String,
        startStatus: Int = 201,
        startBody: String = """{"data":{"id":"actor-run-1","status":"SUCCEEDED","defaultDatasetId":"dataset-1"}}""",
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val (status, body) = when (exchange.requestURI.path) {
                "/v2/users/me/limits" -> 200 to """{"data":{
                    "monthlyUsageCycle":{"startAt":"2026-10-27T00:00:00.000Z","endAt":"2026-11-26T23:59:59.999Z"},
                    "limits":{"maxMonthlyUsageUsd":5},"current":{"monthlyUsageUsd":$limitsUsedUsd}}}"""
                "/v2/acts/apify~facebook-posts-scraper/runs" -> startStatus to startBody
                else -> 404 to """{"error":{"type":"record-not-found"}}"""
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

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
