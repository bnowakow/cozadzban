package pl.bnowakowski.cozadzban.article

import org.jsoup.Jsoup
import com.fasterxml.jackson.databind.ObjectMapper
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
        val data = ObjectMapper().readTree(doc.selectFirst("script[type='application/ld+json']")!!.data())
        assertEquals("https://cozadzban.pl/stories/42", data["url"].asText())
        assertEquals("https://example.com/story", data["about"]["url"].asText())
        assertEquals("Wiadomość <pilna>", data["name"].asText())
        assertFalse(data.has("author"))
    }

    @Test
    fun `returns 404 for a missing story`() {
        whenever(articles.findById(404)).thenReturn(Optional.empty())
        val response = controller.story(404)
        assertEquals(404, response.statusCode.value())
        val doc = Jsoup.parse(response.body.orEmpty())
        assertEquals("Page not found", doc.selectFirst("h1")!!.text())
        assertEquals("noindex", doc.selectFirst("meta[name=robots]")!!.attr("content"))
        assertTrue(doc.select("a[href='/']").isNotEmpty())
    }

    @Test
    fun `structured data cannot terminate the script element or invent a publication date`() {
        whenever(articles.findById(42)).thenReturn(Optional.of(article().copy(
            title = "Title </script><script>alert(1)</script>", publishedAt = null, createdAt = null,
        )))
        val doc = Jsoup.parse(controller.story(42).body.orEmpty())
        val script = doc.selectFirst("script[type='application/ld+json']")!!
        val data = ObjectMapper().readTree(script.data())
        assertEquals("Title </script><script>alert(1)</script>", data["name"].asText())
        assertFalse(data.has("datePublished"))
        assertTrue(doc.select("script").none { it.data() == "alert(1)" })
    }

    @Test
    fun `generated story image shows the versioned JPEG while link previews keep the PNG`() {
        val images: pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository = mock()
        val generated: pl.bnowakowski.cozadzban.facebookpublish.CodexArticleImageGenerator = mock()
        whenever(images.status(42)).thenReturn("GENERATED")
        whenever(generated.webImageUrl(42)).thenReturn("/generated-images/42.jpg?v=123")
        whenever(articles.findById(42)).thenReturn(Optional.of(article()))

        val doc = Jsoup.parse(
            StoryPageController(articles, articleImages = images, generatedImages = generated).story(42).body.orEmpty(),
        )

        assertEquals("/generated-images/42.jpg?v=123", doc.selectFirst("main img")?.attr("src"))
        assertEquals(
            "https://cozadzban.pl/generated-images/42.png",
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
        )
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
