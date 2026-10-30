// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.analytics

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleRepository

class AnalyticsIndexHtmlRequestListenerTest {

    @Test
    fun `injects shared theme bootstrap as raw data node`() {
        val doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
        val listener = AnalyticsIndexHtmlRequestListener(AnalyticsProperties())

        injectThemeBootstrap(listener, doc)

        val script = doc.head().selectFirst("#czj-theme-bootstrap")
        assertEquals("script", script?.tagName())
        assertTrue(script?.childNode(0) is DataNode)
        assertTrue(script?.data().orEmpty().contains("function applyTheme"))
        assertTrue(script?.data().orEmpty().contains("cozadzban-theme"))
    }

    @Test
    fun `injects analytics init script as raw data node`() {
        val doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
        val listener = AnalyticsIndexHtmlRequestListener(
            AnalyticsProperties(
                googleMeasurementId = "G-TEST",
                statcounterProjectId = "123",
                statcounterSecurityId = "abc",
            ),
        )

        injectAnalyticsScript(listener, doc)

        val script = doc.head().selectFirst("#czj-analytics-init")
        assertEquals("script", script?.tagName())
        assertTrue(script?.childNode(0) is DataNode)
        assertTrue(script?.data().orEmpty().contains("czj_analytics_consent"))
        assertTrue(script?.data().orEmpty().contains("G-TEST"))
        assertTrue(script?.data().orEmpty().contains("window.sc_project = 123"))
    }

    @Test
    fun `provides distinct Polish and English homepage descriptions`() {
        val listener = AnalyticsIndexHtmlRequestListener(AnalyticsProperties())
        val polish = Jsoup.parse("<html><head></head><body></body></html>")
        val english = Jsoup.parse("<html><head></head><body></body></html>")
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectSearchMetadata", org.jsoup.nodes.Document::class.java, String::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, polish, "pl")
        method.invoke(listener, english, "en")

        assertEquals("pl", polish.selectFirst("html")?.attr("lang"))
        assertEquals("en", english.selectFirst("html")?.attr("lang"))
        assertTrue(polish.selectFirst("meta[name=description]")!!.attr("content").contains("wiadomości"))
        assertTrue(english.selectFirst("meta[name=description]")!!.attr("content").contains("news"))
        assertEquals("https://cozadzban.pl/?lang=pl", polish.selectFirst("link[rel=canonical]")?.attr("href"))
        assertEquals(3, polish.select("link[hreflang]").size)
        assertEquals("https://cozadzban.pl/", polish.selectFirst("link[hreflang=x-default]")?.attr("href"))
    }

    @Test
    fun `default homepage has an alternate link pointing to itself`() {
        val listener = AnalyticsIndexHtmlRequestListener(AnalyticsProperties())
        val doc = Jsoup.parse("<html><head></head><body></body></html>")
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectSearchMetadata", org.jsoup.nodes.Document::class.java, String::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, doc, null)

        val canonical = doc.selectFirst("link[rel=canonical]")!!.attr("href")
        assertTrue(doc.select("link[hreflang]").any { it.attr("href") == canonical })
    }

    @Test
    fun `absolute base preserves the request origin and Vaadin route resolution`() {
        val listener = AnalyticsIndexHtmlRequestListener(AnalyticsProperties())
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "makeBaseHrefAbsolute", org.jsoup.nodes.Document::class.java, String::class.java,
        )
        method.isAccessible = true
        listOf(
            Triple("https://cozadzban.pl/", ".", "https://cozadzban.pl/"),
            Triple("http://localhost:8080/admin/settings", "../", "http://localhost:8080/"),
            Triple("http://localhost:8080/app/admin", "./", "http://localhost:8080/app/"),
        ).forEach { (requestUrl, relativeBase, expected) ->
            val doc = Jsoup.parse("<head><base href='$relativeBase'></head>")
            method.invoke(listener, doc, requestUrl)
            assertEquals(expected, doc.selectFirst("base")!!.attr("href"))
            assertEquals(1, doc.select("base").size)
        }
    }

    @Test
    fun `initial HTML exposes headings summaries and internal links without JavaScript`() {
        val articles = mock<ArticleRepository>()
        whenever(articles.findPage(0, 16, "publishedAt", "desc", "pl")).thenReturn(listOf(
            Article(id = 42, url = "https://example.com/story", language = "pl",
                title = "Historia <pilna>", lead = "Opis <script>alert(1)</script>", createdByUserId = 987654),
            Article(id = 43, url = "https://example.com/untitled", language = "pl",
                title = " ", createdByUserId = 987654),
        ))
        val listener = AnalyticsIndexHtmlRequestListener(AnalyticsProperties(), articles)
        for (body in listOf("", "<div id='outlet'></div>")) {
            val doc = Jsoup.parse("<html><head></head><body>$body</body></html>")
            injectHomeContent(listener, doc, "pl")

            assertEquals(1, doc.select("h1").size)
            assertTrue(doc.selectFirst("h1")!!.text().contains("wiadomości i historie"))
            assertEquals("Najnowsze historie", doc.selectFirst("h2")!!.text())
            assertEquals("Historia <pilna>", doc.selectFirst("h3 a")!!.text())
            assertEquals("/stories/42", doc.selectFirst("h3 a")!!.attr("href"))
            assertEquals("Opis <script>alert(1)</script>", doc.selectFirst("li p")!!.text())
            assertEquals(1, doc.select("li").size)
            assertEquals(4, doc.select("nav a").size)
            assertTrue(doc.select("#czj-initial-home script").isEmpty())
            assertFalse(doc.body().html().contains("987654"))
        }
    }

    @Test
    fun `empty homepage still has useful English content and navigation`() {
        val doc = Jsoup.parse("<div id='outlet'></div>")
        injectHomeContent(AnalyticsIndexHtmlRequestListener(AnalyticsProperties()), doc, null)

        assertEquals("Co za dzban — news and stories", doc.selectFirst("h1")!!.text())
        assertEquals("Recent stories", doc.selectFirst("h2")!!.text())
        assertTrue(doc.selectFirst("main p")!!.text().contains("News and stories"))
        assertEquals(4, doc.select("nav a").size)
    }

    private fun injectHomeContent(listener: AnalyticsIndexHtmlRequestListener, doc: org.jsoup.nodes.Document, language: String?) {
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectHomeContent", org.jsoup.nodes.Document::class.java, String::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, doc, language)
    }

    private fun injectAnalyticsScript(listener: AnalyticsIndexHtmlRequestListener, doc: org.jsoup.nodes.Document) {
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectAnalyticsScript",
            org.jsoup.nodes.Document::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, doc)
    }

    private fun injectThemeBootstrap(listener: AnalyticsIndexHtmlRequestListener, doc: org.jsoup.nodes.Document) {
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectThemeBootstrap",
            org.jsoup.nodes.Document::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, doc)
    }
}
