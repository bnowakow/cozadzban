// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class RssEndpointAnalyticsTrackerTest {

    @Test
    fun `records GA4 event and StatCounter hit when configured`() {
        withRecordingServer { baseUrl, requests ->
            val tracker = RssEndpointAnalyticsTracker(
                AnalyticsProperties(
                    googleMeasurementId = "G-TEST",
                    googleApiSecret = "ga-secret",
                    statcounterProjectId = "123",
                    statcounterSecurityId = "sc-secret",
                ),
                RestClient.builder(),
                "$baseUrl/mp/collect",
                baseUrl,
            )

            tracker.recordRssFeedReached("pl")

            assertEquals(2, requests.size)
            val googleRequest = requests[0]
            assertEquals("POST", googleRequest.method)
            assertEquals("/mp/collect", googleRequest.path)
            assertEquals("measurement_id=G-TEST&api_secret=ga-secret", googleRequest.query)

            val payload = ObjectMapper().readTree(googleRequest.body)
            assertFalse(payload["client_id"].asText().isBlank())
            assertEquals("DENIED", payload["consent"]["ad_user_data"].asText())
            assertEquals("DENIED", payload["consent"]["ad_personalization"].asText())
            assertEquals("rss_feed_reached", payload["events"][0]["name"].asText())
            val params = payload["events"][0]["params"]
            assertEquals("https://cozadzban.pl/rss?lang=pl", params["page_location"].asText())
            assertEquals("Co za dzban RSS", params["page_title"].asText())
            assertEquals("pl", params["rss_lang"].asText())
            assertEquals(1, params["engagement_time_msec"].asInt())

            val statcounterRequest = requests[1]
            assertEquals("GET", statcounterRequest.method)
            assertEquals("/123/0/sc-secret/1", statcounterRequest.path)
            assertEquals("https://cozadzban.pl/rss", statcounterRequest.headers["Referer"]?.firstOrNull())
            assertEquals("cozadzban-rss-analytics/1.0", statcounterRequest.headers["User-agent"]?.firstOrNull())
        }
    }

    @Test
    fun `skips Google event when API secret is blank`() {
        withRecordingServer { baseUrl, requests ->
            val tracker = RssEndpointAnalyticsTracker(
                AnalyticsProperties(
                    googleMeasurementId = "G-TEST",
                    statcounterProjectId = "123",
                    statcounterSecurityId = "sc-secret",
                ),
                RestClient.builder(),
                "$baseUrl/mp/collect",
                baseUrl,
            )

            tracker.recordRssFeedReached(null)

            assertEquals(1, requests.size)
            assertEquals("GET", requests[0].method)
            assertEquals("/123/0/sc-secret/1", requests[0].path)
        }
    }

    private fun withRecordingServer(block: (String, List<RecordedRequest>) -> Unit) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.query,
                    headers = exchange.requestHeaders,
                    body = body,
                ),
            )
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    )
}
