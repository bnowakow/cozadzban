// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PublisherRssFallbackTest {
    @Test
    fun `FT feed item must match the exact canonical article`() {
        val xml = """
            <rss version="2.0"><channel>
              <item>
                <title>Unrelated story</title>
                <link>https://www.ft.com/content/other</link>
                <description>Other summary</description>
              </item>
              <item>
                <title>Matching story</title>
                <link>https://www.ft.com/content/article-1?utm_source=rss</link>
                <description>Publisher summary</description>
                <pubDate>Fri, 13 Nov 2026 10:00:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent()

        val result = PublisherRssFallback.parseMatchingItem(xml, "https://ft.com/content/article-1")

        assertEquals("Matching story", result?.title)
        assertEquals("Publisher summary", result?.lead)
        assertEquals(Instant.parse("2026-11-13T10:00:00Z"), result?.publishedAt)
        assertNull(PublisherRssFallback.parseMatchingItem(xml, "https://ft.com/content/article-2"))
    }

    @Test
    fun `Euronews item without publisher summary cannot be accepted`() {
        val xml = """
            <rss version="2.0"><channel>
              <item>
                <title>Story</title>
                <link>https://www.euronews.com/2026/11/13/story</link>
                <description></description>
              </item>
            </channel></rss>
        """.trimIndent()

        assertNull(PublisherRssFallback.parseMatchingItem(xml, "https://euronews.com/2026/11/13/story"))
    }

    @Test
    fun `Euronews feed supplies matching summary and image`() {
        val xml = """
            <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel>
              <item>
                <title>European story</title>
                <link>https://www.euronews.com/2026/11/13/story?utm_source=feed</link>
                <description><![CDATA[<p>Publisher summary</p>]]></description>
                <media:thumbnail url="https://static.euronews.com/story.jpg"/>
              </item>
            </channel></rss>
        """.trimIndent()

        val result = PublisherRssFallback.parseMatchingItem(xml, "https://euronews.com/2026/11/13/story")

        assertEquals("European story", result?.title)
        assertEquals("Publisher summary", result?.lead)
        assertEquals("https://static.euronews.com/story.jpg", result?.thumbnail)
    }
}
