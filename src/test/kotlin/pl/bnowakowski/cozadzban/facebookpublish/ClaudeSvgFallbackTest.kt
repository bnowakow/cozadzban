// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.Optional
import javax.imageio.ImageIO

class ClaudeSvgFallbackTest {
    private val safeSvg = """
        <svg xmlns="http://www.w3.org/2000/svg" width="1200" height="630" viewBox="0 0 1200 630">
          <defs><linearGradient id="bg"><stop offset="0" stop-color="#1b2a49"/><stop offset="1" stop-color="#f36b32"/></linearGradient></defs>
          <rect width="1200" height="630" fill="url(#bg)"/>
          <circle cx="600" cy="315" r="160" fill="#ffffff" opacity="0.8"/>
        </svg>
    """.trimIndent()

    @Test
    fun `extracts the SVG from a response and renders it to 1200x630`() {
        val svg = SvgIllustrationRenderer.extract("Here it is:\n$safeSvg\nDone.")!!
        assertNull(SvgIllustrationRenderer.rejectionReason(svg))

        val png = SvgIllustrationRenderer.render(svg)!!

        val image = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(1200, image.width)
        assertEquals(630, image.height)
        assertNull(SvgIllustrationRenderer.extract("no svg here"))
    }

    @Test
    fun `rejects anything beyond plain shapes`() {
        fun svg(body: String) = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 1200 630">$body</svg>"""
        val unsafe = mapOf(
            "script" to svg("<script>alert(1)</script>"),
            "text" to svg("<text x='10' y='10'>Hello</text>"),
            "image" to svg("<image href='http://example.com/a.png' width='10' height='10'/>"),
            "foreignObject" to svg("<foreignObject width='10' height='10'/>"),
            "style element" to svg("<style>rect{fill:red}</style>"),
            "style attribute" to svg("<rect width='10' height='10' style='fill:red'/>"),
            "event handler" to svg("<rect width='10' height='10' onclick='x()'/>"),
            "external use" to svg("<use href='http://example.com/a.svg#x'/>"),
            "external xlink" to svg("<use xlink:href='other.svg#x'/>"),
            "external url()" to svg("<rect width='10' height='10' fill='url(http://example.com/x)'/>"),
            "data url" to svg("<rect width='10' height='10' fill='url(#a) data:x'/>"),
            "doctype" to """<!DOCTYPE svg [<!ENTITY x "y">]>""" + svg(""),
            "not svg" to "<html/>",
            "broken" to "<svg",
        )
        unsafe.forEach { (name, value) ->
            assertNotNull(SvgIllustrationRenderer.rejectionReason(value), "$name must be rejected")
            assertNull(SvgIllustrationRenderer.render(value), "$name must not render")
        }
        assertNull(SvgIllustrationRenderer.rejectionReason(svg("<g><use href='#a'/><path id='a' d='M0 0 L10 10'/></g>")))
    }

    @Test
    fun `Claude runs in print mode with every tool disabled`() {
        assertEquals(
            listOf(
                "claude", "-p", "--output-format", "text", "--model", "sonnet",
                "--tools", "", "--strict-mcp-config", "--disable-slash-commands", "--no-session-persistence",
            ),
            ClaudeSvgArticleImageGenerator.command(""),
        )
    }

    @Test
    fun `Claude usage limits pause with the reported or a one-hour retry time`() {
        val now = Instant.parse("2026-10-31T11:00:00Z")
        assertEquals(
            Instant.parse("2026-10-31T15:01:00Z"),
            ClaudeSvgArticleImageGenerator.usageLimit("Claude AI usage limit reached|1793458800", now)!!.retryAt,
        )
        assertEquals(now.plusSeconds(3600), ClaudeSvgArticleImageGenerator.usageLimit("You've hit your limit · resets 3pm", now)!!.retryAt)
        assertNull(ClaudeSvgArticleImageGenerator.usageLimit("Error: network unreachable", now))
    }

    @Test
    fun `Claude draws the image when Codex fails`() {
        val f = fixture()
        whenever(f.codex.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Failed("Codex exited with code 1."))
        whenever(f.claude.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Generated(byteArrayOf(1)))
        whenever(f.jobs.complete(538)).thenReturn("GENERATED")

        f.scheduler.generateOne()

        verify(f.jobs).complete(538)
        verify(f.jobs, never()).fail(any(), any(), any())
    }

    @Test
    fun `a Codex usage limit still pauses when Claude also fails, and the earlier reset wins when both are limited`() {
        val codexReset = Instant.parse("2026-11-04T06:37:00Z")
        val f = fixture()
        whenever(f.codex.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.UsageLimited("Codex limit.", codexReset))
        whenever(f.claude.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Failed("Claude SVG rejected."))
        f.scheduler.generateOne()
        verify(f.jobs).pauseForUsageLimit(538, codexReset, "Codex limit.")

        val claudeReset = Instant.parse("2026-11-01T12:00:00Z")
        val g = fixture()
        whenever(g.codex.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.UsageLimited("Codex limit.", codexReset))
        whenever(g.claude.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.UsageLimited("Claude limit.", claudeReset))
        g.scheduler.generateOne()
        verify(g.jobs).pauseForUsageLimit(538, claudeReset, "Codex limit. Claude limit.")
    }

    @Test
    fun `both failing records both reasons, and articles without text skip Claude`() {
        val f = fixture()
        whenever(f.codex.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Failed("Codex exited with code 1."))
        whenever(f.claude.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Failed("Claude SVG rejected."))
        f.scheduler.generateOne()
        verify(f.jobs).fail(538, 3, "Codex exited with code 1. Claude: Claude SVG rejected.")

        val g = fixture()
        whenever(g.codex.generate(any(), anyOrNull()))
            .thenReturn(ArticleImageGenerationResult.Failed(MISSING_IMAGE_CONTEXT_REASON, retryable = false))
        g.scheduler.generateOne()
        verify(g.claude, never()).generate(any(), anyOrNull())
        verify(g.jobs).failPermanent(538, MISSING_IMAGE_CONTEXT_REASON)
    }

    @Test
    fun `Claude works alone when Codex is disabled`() {
        val f = fixture(codexEnabled = false)
        whenever(f.claude.generate(any(), anyOrNull())).thenReturn(ArticleImageGenerationResult.Generated(byteArrayOf(1)))
        whenever(f.jobs.complete(538)).thenReturn("GENERATED")

        f.scheduler.generateOne()

        verify(f.codex, never()).generate(any(), anyOrNull())
        verify(f.jobs).complete(538)
        assertTrue(f.scheduler.isEnabled())
    }

    private class Fixture(
        val jobs: FacebookArticleImageGenerationRepository,
        val codex: CodexArticleImageGenerator,
        val claude: ClaudeSvgArticleImageGenerator,
        val scheduler: FacebookArticleImageGenerationScheduler,
    )

    private fun fixture(codexEnabled: Boolean = true): Fixture {
        val jobs: FacebookArticleImageGenerationRepository = mock()
        val articles: ArticleRepository = mock()
        val contents: ArticleContentRepository = mock()
        val codex: CodexArticleImageGenerator = mock()
        val claude: ClaudeSvgArticleImageGenerator = mock()
        whenever(codex.isEnabled()).thenReturn(codexEnabled)
        whenever(claude.isEnabled()).thenReturn(true)
        whenever(jobs.claim()).thenReturn(FacebookArticleImageJob(538, 1))
        whenever(articles.findById(538)).thenReturn(
            Optional.of(Article(id = 538, url = "https://example.com/a", language = "pl", title = "Title", createdByUserId = 1)),
        )
        whenever(contents.findById(538)).thenReturn(Optional.empty())
        return Fixture(jobs, codex, claude, FacebookArticleImageGenerationScheduler(jobs, articles, contents, codex, mock(), claude))
    }
}
