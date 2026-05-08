// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
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

    private fun withServer(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}/")
        } finally {
            server.stop(0)
        }
    }
}
