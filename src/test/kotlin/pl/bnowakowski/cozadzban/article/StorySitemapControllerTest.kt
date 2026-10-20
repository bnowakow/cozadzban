package pl.bnowakowski.cozadzban.article

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
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
        val doc = Jsoup.parse(xml, Parser.xmlParser())
        assertEquals(4, doc.select("url").size)
        assertEquals("https://cozadzban.pl/?lang=pl", doc.select("loc")[1].text())
        assertEquals("https://cozadzban.pl/stories/7", doc.select("loc")[3].text())
        assertEquals("2026-10-19T10:00:00Z", doc.selectFirst("lastmod")?.text())
    }
}
