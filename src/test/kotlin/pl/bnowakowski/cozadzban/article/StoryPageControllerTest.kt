package pl.bnowakowski.cozadzban.article

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.Optional

class StoryPageControllerTest {
    private val articles: ArticleRepository = mock()
    private val controller = StoryPageController(articles)

    @Test
    fun `renders Polish story metadata and escaped content in initial HTML`() {
        whenever(articles.findById(42)).thenReturn(Optional.of(article()))

        val response = controller.story(42)
        val html = response.body.orEmpty()
        val doc = Jsoup.parse(html)

        assertEquals(200, response.statusCode.value())
        assertEquals("pl", doc.selectFirst("html")?.attr("lang"))
        assertEquals("Wiadomość <pilna> | Co za dzban", doc.title())
        assertEquals("Opis historii", doc.selectFirst("meta[name=description]")?.attr("content"))
        assertEquals("https://cozadzban.pl/stories/42", doc.selectFirst("link[rel=canonical]")?.attr("href"))
        assertEquals("pl_PL", doc.selectFirst("meta[property=og:locale]")?.attr("content"))
        assertEquals("2026-10-19T10:00:00Z", doc.selectFirst("time")?.attr("datetime"))
        assertTrue(html.contains("Wiadomość &lt;pilna&gt;"))
        assertFalse(doc.body().outerHtml().contains("<pilna>"))
        assertEquals("https://example.com/story", doc.selectFirst("a.original")?.attr("href"))
    }

    @Test
    fun `returns 404 for a missing story`() {
        whenever(articles.findById(404)).thenReturn(Optional.empty())
        assertEquals(404, controller.story(404).statusCode.value())
    }

    private fun article() = Article(
        id = 42,
        url = "https://example.com/story",
        language = "pl",
        title = "Wiadomość <pilna>",
        lead = "Opis historii",
        createdByUserId = 1,
        publishedAt = Instant.parse("2026-10-19T10:00:00Z"),
    )
}
