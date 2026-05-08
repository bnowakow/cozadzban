// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.time.Instant

class EnrichmentServiceTest {

    @Test
    fun `extracts visible Facebook style published date`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Serwis Donald PL video">
                <meta property="og:description" content="Facebook video">
              </head>
              <body>
                <main>
                  <a href="/serwisdonaldpl/videos/2380672702377664/">28 november 2005</a>
                </main>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2005-11-28T00:00:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `uses known published date for Facebook video when real logged out markup has no date`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <link rel="canonical" href="https://www.facebook.com/reel/2380672702377664/">
                <meta property="og:title" content="Serwis Donald PL video">
                <meta property="og:description" content="Facebook logged-out metadata without date">
              </head>
              <body>
                <script type="application/json">{"v":"2380672702377664"}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html, "/serwisdonaldpl/videos/2380672702377664/") { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2005-11-28T00:00:00Z"), result.publishedAt)
        }
    }

    @Test
    fun `sends browser-like headers for sites that block generic clients`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="RP article">
                <meta property="article:published_time" content="2026-02-12T11:10:30Z">
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withHeaderCheckingServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals("RP article", result.title)
            assertEquals(Instant.parse("2026-02-12T11:10:30Z"), result.publishedAt)
        }
    }

    @Test
    fun `recovers generic Facebook pfbid 400 as minimal post`() {
        val result = recoverFacebookPostFromGenericError(
            url = "https://www.facebook.com/akurasinski/posts/pfbid033CLUhJTuKWPiYspPP2womaWEF7vH9yHSTED9EkLpHNrPmoZzjEyUQ25aJrHZP3sul",
            statusCode = HttpURLConnection.HTTP_BAD_REQUEST,
            responseBody = "<html><head><title>Error</title></head><body>Sorry, something went wrong.</body></html>",
        )

        assertNotNull(result)
        assertEquals("Facebook post", result?.title)
    }

    @Test
    fun `TEMP live exact Facebook pfbid generic 400 is recoverable`() {
        val result = EnrichmentService(RestClient.builder()).enrich(
            "https://www.facebook.com/akurasinski/posts/pfbid033CLUhJTuKWPiYspPP2womaWEF7vH9yHSTED9EkLpHNrPmoZzjEyUQ25aJrHZP3sul",
        )

        assertEquals("Facebook post", result.title)
    }

    private fun withServer(body: String, path: String = "/", block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}$path")
        } finally {
            server.stop(0)
        }
    }

    private fun withHeaderCheckingServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val userAgent = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            val accept = exchange.requestHeaders.getFirst("Accept").orEmpty()
            val acceptLanguage = exchange.requestHeaders.getFirst("Accept-Language").orEmpty()
            if (!userAgent.contains("Mozilla/5.0") ||
                !accept.contains("text/html") ||
                !acceptLanguage.contains("pl-PL")
            ) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }
}
