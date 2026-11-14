// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApifyWebFetchFallbackTest {
    private fun response(loaded: String, status: Int = 200, title: String = "A real article", canonical: String = loaded) = """
        {
          "fetch": {"loadedUrl": "$loaded", "httpStatusCode": $status, "contentType": "text/html; charset=utf-8"},
          "metadata": {"canonicalUrl": "$canonical", "title": "$title"},
          "raw": "<html><head><title>A real article</title><meta name='description' content='A useful article summary from the publisher.'></head><body><article>Article content from the publisher.</article></body></html>"
        }
    """.trimIndent()

    @Test
    fun `accepts resolved short link on the expected publisher`() {
        val page = ApifyWebFetchFallback.parsePage(
            "https://ft.trib.al/abc123",
            response("https://www.ft.com/content/story-1"),
        )
        assertEquals("https://www.ft.com/content/story-1", page?.loadedUrl)
    }

    @Test
    fun `rejects publisher error and challenge pages`() {
        val url = "https://tvn24.pl/polska/story-1"
        assertNull(ApifyWebFetchFallback.parsePage(url, response(url, status = 403)))
        assertNull(ApifyWebFetchFallback.parsePage(url, response(url, title = "Just a moment")))
    }

    @Test
    fun `rejects unrelated redirects and wrong canonical article`() {
        val url = "https://reut.rs/abc123"
        assertNull(ApifyWebFetchFallback.parsePage(url, response("https://example.org/story")))
        assertNull(ApifyWebFetchFallback.parsePage(
            "https://www.reuters.com/world/story-1",
            response("https://www.reuters.com/world/story-2"),
        ))
        assertNull(ApifyWebFetchFallback.parsePage(
            "https://www.reuters.com/world/story-1",
            response("https://www.reuters.com/world/story-1", canonical = "https://www.reuters.com/world/story-2"),
        ))
    }

    @Test
    fun `direct proxy HTML validation rejects unrelated canonical URL`() {
        val html = "<html><head><title>A real article</title><link rel='canonical' href='https://ft.com/content/other'></head>" +
            "<body><article>A substantial article body from the publisher.</article></body></html>"
        assertNull(ApifyWebFetchFallback.validateHtmlPage(
            "https://ft.trib.al/abc123", "https://www.ft.com/content/story-1", html,
        ))
    }
}
