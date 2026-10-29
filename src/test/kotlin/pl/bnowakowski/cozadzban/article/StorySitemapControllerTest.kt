package pl.bnowakowski.cozadzban.article

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class StorySitemapControllerTest {
    @Test
    fun `lists language feeds and individual story URLs`() {
        val articles: ArticleRepository = mock()
        whenever(articles.findForRss(null)).thenReturn(listOf(
            Article(id = 7, url = "https://example.com/story", language = "en", createdByUserId = 1,
                publishedAt = Instant.parse("2026-10-19T10:00:00Z")),
        ))

        val xml = StorySitemapController(articles).sitemap().body.orEmpty()
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<?xml-stylesheet type=\"text/xsl\" href=\"/sitemap.xsl\"?>\n"))
        val doc = Jsoup.parse(xml, Parser.xmlParser())
        assertEquals(6, doc.select("url").size)
        assertEquals("https://cozadzban.pl/?lang=pl", doc.select("loc")[1].text())
        assertEquals("https://cozadzban.pl/privacy-policy.html", doc.select("loc")[3].text())
        assertEquals("https://cozadzban.pl/user-data-deletion.html", doc.select("loc")[4].text())
        assertEquals("https://cozadzban.pl/stories/7", doc.select("loc")[5].text())
        assertEquals(0, doc.select("lastmod").size)
    }
}
