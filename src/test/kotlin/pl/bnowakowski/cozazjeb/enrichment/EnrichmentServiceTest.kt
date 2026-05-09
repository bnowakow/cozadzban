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
    fun `recovers Facebook reel 400 as minimal reel`() {
        val result = recoverFacebookPostFromGenericError(
            url = "https://www.facebook.com/reel/1648200636595572",
            statusCode = HttpURLConnection.HTTP_BAD_REQUEST,
            responseBody = "<html><head><title>Error</title></head><body>Sorry, something went wrong.</body></html>",
        )

        assertNotNull(result)
        assertEquals("Facebook reel", result?.title)
    }

    @Test
    fun `extracts Facebook plugin post message as lead`() {
        val expected = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val html = """
            <!doctype html>
            <html>
              <head><title>Facebook</title></head>
              <body>
                <div data-testid="post_message">
                  <p>White House press secretary Karoline Leavitt told reporters &quot;Americans will see oil and gas prices drop rapidly&quot; once the U.S. military&#039;s national security objectives are &quot;fully achieved&quot; in Iran.</p>
                </div>
              </body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.facebook.com/reel/1648200636595572", html)

        assertEquals(expected, result.lead)
        assertEquals(expected, result.plainText)
    }

    @Test
    fun `extracts Facebook watch metadata lead for reel fallback`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="1.1M views, 3K reactions | ${lead.escapeHtmlAttribute()} | Reuters">
                <meta property="og:description" content="${lead.escapeHtmlAttribute()}">
              </head>
              <body>Facebook logged-out watch page chrome</body>
            </html>
        """.trimIndent()

        val result = enrichHtml("https://www.facebook.com/reel/1648200636595572", html)

        assertEquals("1.1M views, 3K reactions | $lead | Reuters", result.title)
        assertEquals(lead, result.lead)
    }

    @Test
    fun `builds Facebook watch fallback URL for reels and videos`() {
        assertEquals(
            "https://www.facebook.com/watch/?v=1648200636595572",
            facebookWatchUrl("https://www.facebook.com/reel/1648200636595572"),
        )
        assertEquals(
            "https://www.facebook.com/watch/?v=2380672702377664",
            facebookWatchUrl("https://www.facebook.com/serwisdonaldpl/videos/2380672702377664/"),
        )
        assertEquals(null, facebookWatchUrl("https://www.facebook.com/example/posts/pfbid123"))
    }

    @Test
    fun `uses Reuters mobile fallback only for Reuters 401 responses`() {
        assertEquals(true, shouldUseReutersMobileFallback("https://reut.rs/4oRr7wu", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(true, shouldUseReutersMobileFallback("https://www.reuters.com/world/example/", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(false, shouldUseReutersMobileFallback("https://example.com/article", HttpURLConnection.HTTP_UNAUTHORIZED))
        assertEquals(false, shouldUseReutersMobileFallback("https://reut.rs/4oRr7wu", HttpURLConnection.HTTP_FORBIDDEN))
    }

    @Test
    fun `builds reader fallback URL only for HTTP URLs`() {
        assertEquals(
            "https://r.jina.ai/http://https://www.rp.pl/example",
            readerUrl("https://www.rp.pl/example"),
        )
        assertEquals(null, readerUrl("ftp://www.rp.pl/example"))
    }

    @Test
    fun `parses reader fallback title published time and content`() {
        val result = parseReaderMarkdownResult(
            url = "https://www.rp.pl/example",
            text = """
                Title: Czy Pete Hegseth nakazał dobijać ocalałych? Kongres sprawdzi doniesienia „Washington Post” - rp.pl

                URL Source: https://www.rp.pl/example

                Published Time: 2025-12-01T11:12:00+01:00

                Markdown Content:
                ## Z tego artykułu dowiesz się:

                *   Punkt listy

                Amerykanie od 2 września prowadzą na wodach międzynarodowych ataki na łodzie.
            """.trimIndent(),
        )

        assertEquals("Czy Pete Hegseth nakazał dobijać ocalałych? Kongres sprawdzi doniesienia „Washington Post” - rp.pl", result.title)
        assertEquals(Instant.parse("2025-12-01T10:12:00Z"), result.publishedAt)
        assertEquals(
            "Amerykanie od 2 września prowadzą na wodach międzynarodowych ataki na łodzie.",
            result.lead,
        )
    }

    @Test
    fun `extracts thumbnail from Facebook og image`() {
        val expectedThumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1605899074085738"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/wysokienapieciepl/posts/pfbid0TFpq7SGQtgZvDuMdZQskpMHCABrxHznrNeKkGoz4VpHDuVk5c6bd1BGT2j1XredMl">
                <meta property="og:title" content="WysokieNapiecie.pl">
                <meta property="og:image" content="$expectedThumbnail">
              </head>
              <body>Post</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expectedThumbnail, result.thumbnail)
        }
    }

    @Test
    fun `extracts thumbnail from twitter image when og image is missing`() {
        val expectedThumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1605899074085738"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="WysokieNapiecie.pl">
                <meta name="twitter:image" content="$expectedThumbnail">
              </head>
              <body>Post</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expectedThumbnail, result.thumbnail)
        }
    }

    @Test
    fun `extracts Facebook embedded publish timestamp`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="Artur Kurasiński">
                <meta property="og:description" content="Walka Trumpa z putinem wygląda tak.">
              </head>
              <body>
                <script>{"publish_time\":1762631004,"creation_time\":1762652986}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2025-11-08T19:43:24Z"), result.publishedAt)
        }
    }

    @Test
    fun `uses expanded Facebook embedded message instead of see more teaser`() {
        val expected = "Kiedy uciekasz przed więzieniem do Budapesztu, ale ten Budapeszt to trochę jak więzienie.\n\n" +
            "więcej: https://www.donald.pl/artykuly/tCWEBCLq/wegierski-gambit-posel-romanowski-z-uniewaznionymi-paszportami-ziobre-moze-czekac-to-samo"
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/serwisdonaldpl/posts/pfbid02fLBt8gqqFa59U1MmSiBsgz1zzE4EtNnPxmxrGsnFLXHsFgu2rSuZvgAxnpbpmwf6l">
                <meta property="og:title" content="donald.pl">
                <meta property="og:description" content="Kiedy uciekasz przed więzieniem do Budapesztu, ale ten Budapeszt to trochę jak więzienie. więcej:...">
              </head>
              <body>
                <script type="application/json">
                  {
                    "post_id":"894176306277335",
                    "actors":[],
                    "message":{
                      "__typename":"TextWithEntities",
                      "text":"Kiedy uciekasz przed wi\u0119zieniem do Budapesztu, ale ten Budapeszt to troch\u0119 jak wi\u0119zienie.\n\nwi\u0119cej: https:\/\/www.donald.pl\/artykuly\/tCWEBCLq\/wegierski-gambit-posel-romanowski-z-uniewaznionymi-paszportami-ziobre-moze-czekac-to-samo"
                    }
                  }
                </script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expected, result.lead)
            assertEquals(expected, result.plainText)
        }
    }

    @Test
    fun `extracts Facebook embedded message from large payload without regex overflow`() {
        val expected = "Niebezpiecznik post body with enough text to cache safely."
        val noise = "x".repeat(25_000)
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:url" content="https://www.facebook.com/niebezpiecznik/posts/pfbid02zG5UuLqFzVEeBHi4dLqX1kX96z3JhCpLE6mCZgrT4TjbrGzQzsP1wYMhGjWRcdQFl">
                <meta property="og:title" content="Niebezpiecznik">
                <meta property="og:description" content="Niebezpiecznik post body...">
              </head>
              <body>
                <script type="application/json">{"noise":"$noise","message":{"__typename":"TextWithEntities","text":"$expected"}}</script>
              </body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(expected, result.lead)
        }
    }

    @Test
    fun `extracts JSON-LD NewsArticle published date with offset`() {
        val html = """
            <!doctype html>
            <html>
              <head>
                <meta property="og:title" content="RP article">
                <script type="application/ld+json">
                  {
                    "@context": "https://schema.org",
                    "@type": "NewsArticle",
                    "datePublished": "2025-12-01T11:12:00+01:00"
                  }
                </script>
              </head>
              <body>Article</body>
            </html>
        """.trimIndent()

        withServer(html) { url ->
            val result = EnrichmentService(RestClient.builder()).enrich(url)

            assertEquals(Instant.parse("2025-12-01T10:12:00Z"), result.publishedAt)
        }
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

    private fun enrichHtml(url: String, html: String): EnrichmentResult {
        val service = EnrichmentService(RestClient.builder())
        val method = EnrichmentService::class.java.getDeclaredMethod(
            "enrichHtml",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, html) as EnrichmentResult
    }
}

private fun String.escapeHtmlAttribute(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
