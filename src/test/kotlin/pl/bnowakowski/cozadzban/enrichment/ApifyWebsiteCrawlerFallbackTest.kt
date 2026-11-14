// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApifyWebsiteCrawlerFallbackTest {
    private val storyText = "A detailed report from the publisher about the event and its consequences. ".repeat(3)

    private fun item(loadedUrl: String, title: String = "News article", status: Int = 200): String = """
        [{"url":"https://ft.trib.al/story", "crawl":{"loadedUrl":"$loadedUrl","httpStatusCode":$status},
          "metadata":{"canonicalUrl":"$loadedUrl","title":"$title","description":"Publisher summary of the article."},
          "text":"$storyText"}]
    """.trimIndent()

    @Test
    fun `accepts one resolved publisher article`() {
        val result = ApifyWebsiteCrawlerFallback.parseItems(
            "https://ft.trib.al/story", item("https://www.ft.com/content/story"),
        )
        assertEquals("News article", result?.title)
        assertEquals("Publisher summary of the article.", result?.lead)
    }

    @Test
    fun `rejects unrelated or blocked crawler pages`() {
        val url = "https://ft.trib.al/story"
        assertNull(ApifyWebsiteCrawlerFallback.parseItems(url, item("https://example.org/story")))
        assertNull(ApifyWebsiteCrawlerFallback.parseItems(url, item("https://ft.com/content/story", status = 403)))
        assertNull(ApifyWebsiteCrawlerFallback.parseItems(url, item("https://ft.com/content/story", title = "Just a moment")))
    }

    @Test
    fun `rejects multiple pages because only the requested article is allowed`() {
        val one = item("https://ft.com/content/story").removePrefix("[").removeSuffix("]")
        assertNull(ApifyWebsiteCrawlerFallback.parseItems("https://ft.trib.al/story", "[$one,$one]"))
    }
}
