// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.InetSocketAddress
import java.time.Instant

class FacebookImportProposalClientTest {
    private val proposalService: FacebookArticleProposalService = mock()

    @Test
    fun `completeRun sends remote request and persists local run`() {
        val requests = mutableListOf<HttpExchange>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/facebook-import/runs/run-1/complete") { exchange ->
            requests += exchange
            assertEquals("POST", exchange.requestMethod)
            assertEquals("test-machine-key", exchange.requestHeaders["X-CoZaDzban-M2M-Key"]?.firstOrNull())
            exchange.sendResponseHeaders(204, -1)
            exchange.responseBody.close()
        }
        server.start()

        try {
            val client = FacebookImportProposalClient(
                properties = FacebookImportProperties(
                    targetApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    targetApiKey = "test-machine-key",
                ),
                proposalService = proposalService,
            )

            client.completeRun(
                "run-1",
                FacebookImportRunCompletionRequest(
                    status = FacebookImportRunStatus.FINISHED,
                    discoveredCount = 1,
                ),
            )

            assertEquals(1, requests.size)
            assertEquals("/api/facebook-import/runs/run-1/complete", requests.first().requestURI.path)
            verify(proposalService).completeRun(eq("run-1"), any())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `recordProgress sends remote request and persists local progress`() {
        val requests = mutableListOf<HttpExchange>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/facebook-import/runs/run-1/progress") { exchange ->
            requests += exchange
            assertEquals("POST", exchange.requestMethod)
            assertEquals("test-machine-key", exchange.requestHeaders["X-CoZaDzban-M2M-Key"]?.firstOrNull())
            exchange.sendResponseHeaders(204, -1)
            exchange.responseBody.close()
        }
        server.start()

        try {
            val client = FacebookImportProposalClient(
                properties = FacebookImportProperties(
                    targetApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    targetApiKey = "test-machine-key",
                ),
                proposalService = proposalService,
            )

            client.recordProgress(
                "run-1",
                FacebookImportProgressRequest(
                    phase = "Sending proposals",
                    phaseIndex = 1,
                    phaseCount = 1,
                    passIndex = 1,
                    passCount = 1,
                ),
            )

            assertEquals(1, requests.size)
            assertEquals("/api/facebook-import/runs/run-1/progress", requests.first().requestURI.path)
            verify(proposalService).recordProgress(eq("run-1"), any())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `terminateAbandonedRunsOnStartup sends remote request and persists local cleanup`() {
        val requests = mutableListOf<HttpExchange>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/facebook-import/runs/abandoned-startup-cleanup") { exchange ->
            requests += exchange
            assertEquals("POST", exchange.requestMethod)
            assertEquals("test-machine-key", exchange.requestHeaders["X-CoZaDzban-M2M-Key"]?.firstOrNull())
            val response = """{"terminatedRunIds":["server-run"]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            val client = FacebookImportProposalClient(
                properties = FacebookImportProperties(
                    targetApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    targetApiKey = "test-machine-key",
                ),
                proposalService = proposalService,
            )
            val startedAt = Instant.parse("2026-06-27T12:00:00Z")

            assertEquals(listOf("server-run"), client.terminateAbandonedRunsOnStartup(startedAt))

            assertEquals(1, requests.size)
            assertEquals("/api/facebook-import/runs/abandoned-startup-cleanup", requests.first().requestURI.path)
            verify(proposalService).terminateAbandonedRunsOnStartup(startedAt)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `terminateAbandonedRunsOnStartup uses local service when remote is not configured`() {
        val startedAt = Instant.parse("2026-06-27T12:00:00Z")
        whenever(proposalService.terminateAbandonedRunsOnStartup(startedAt)).thenReturn(listOf("local-run"))
        val client = FacebookImportProposalClient(
            properties = FacebookImportProperties(),
            proposalService = proposalService,
        )

        assertEquals(listOf("local-run"), client.terminateAbandonedRunsOnStartup(startedAt))

        verify(proposalService).terminateAbandonedRunsOnStartup(startedAt)
    }
}
