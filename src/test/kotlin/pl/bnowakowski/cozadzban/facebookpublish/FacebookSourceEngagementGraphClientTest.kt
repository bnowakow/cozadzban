// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class FacebookSourceEngagementGraphClientTest {
    @Test
    fun `matches a pfbid permalink and reads only the aggregate count`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals("Bearer 42|server-secret", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("/v26.0/ExamplePage/posts", exchange.requestURI.path)
            assertTrue(exchange.requestURI.rawQuery.contains("fields="))
            assertTrue(!exchange.requestURI.rawQuery.contains("server-secret"))
            val body = """{"data":[{"id":"99_111","permalink_url":"https://www.facebook.com/ExamplePage/posts/pfbidOther","reactions":{"summary":{"total_count":1}}},{"id":"99_222","permalink_url":"https://www.facebook.com/ExamplePage/posts/pfbidTarget","reactions":{"summary":{"total_count":37}}}]}"""
                .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val graph = FacebookSourceEngagementGraphClient(FacebookSourceProperties(
                appId = "42", appSecret = "server-secret",
                graphBaseUrl = "http://127.0.0.1:${server.address.port}",
            ))
            val result = graph.measure(
                FacebookSourcePostReference("ExamplePage", "pfbidTarget"),
                Instant.parse("2026-10-01T12:00:00Z"), null,
            )
            assertEquals(FacebookSourceReactionMeasurement("99_222", 37), result)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `falls back to the public Page edge when a direct Post read is denied`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/v26.0/99_222") {
                """{"error":{"code":210,"message":"Page token required"}}"""
            } else {
                assertEquals("/v26.0/99/posts", exchange.requestURI.path)
                """{"data":[{"id":"99_222","permalink_url":"https://www.facebook.com/ExamplePage/posts/pfbidTarget","reactions":{"summary":{"total_count":38}}}]}"""
            }.toByteArray()
            val status = if (requests.getAndIncrement() == 0) 400 else 200
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val graph = FacebookSourceEngagementGraphClient(FacebookSourceProperties(
                appId = "42", appSecret = "server-secret",
                graphBaseUrl = "http://127.0.0.1:${server.address.port}",
            ))
            val result = graph.measure(FacebookSourcePostReference("ExamplePage", "pfbidTarget"), null, "99_222")
            assertEquals(FacebookSourceReactionMeasurement("99_222", 38), result)
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
