// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.time.Duration
import java.time.Instant
import java.util.Optional

class CodexUsageLimitTest {
    private val now = Instant.parse("2026-10-31T10:40:00Z")

    private val realOutput = """
        user
        Use ${'$'}imagegen to create exactly one editorial illustration for a Facebook Page story post.
        warning: Codex could not find bubblewrap on PATH.
        ERROR: You’ve hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), visit https://chatgpt.com/settings/usage to purchase more credits or try again at Nov 4th, 2026 6:36 AM.
    """.trimIndent()

    @Test
    fun `reads the retry time from Codex's usage-limit error`() {
        val limited = CodexArticleImageGenerator.usageLimit(realOutput, now)!!

        assertEquals(Instant.parse("2026-11-04T06:37:00Z"), limited.retryAt)
        assertEquals("Codex usage limit reached; retrying after 2026-11-04T06:37:00Z.", limited.reason)
    }

    @Test
    fun `afternoon times and missing or past times are handled`() {
        assertEquals(
            Instant.parse("2026-11-04T18:06:00Z"),
            CodexArticleImageGenerator.usageLimit("ERROR: You've hit your usage limit. Try again at Nov 4, 2026 6:05 PM.", now)!!.retryAt,
        )
        assertEquals(now.plusSeconds(3600), CodexArticleImageGenerator.usageLimit("ERROR: You've hit your usage limit.", now)!!.retryAt)
        assertEquals(
            now.plusSeconds(3600),
            CodexArticleImageGenerator.usageLimit("ERROR: You've hit your usage limit. Try again at Oct 1st, 2026 6:05 AM.", now)!!.retryAt,
        )
    }

    @Test
    fun `only Codex error lines count, not the echoed article text`() {
        val echoed = "user\nTitle: Man says he hit your usage limit. Try again at Nov 4th, 2026 6:36 AM.\nERROR: unexpected status 500"
        assertNull(CodexArticleImageGenerator.usageLimit(echoed, now))
        assertEquals("unexpected status 500", CodexArticleImageGenerator.lastErrorLine(echoed))
        assertNull(CodexArticleImageGenerator.lastErrorLine("no errors here"))
    }

    @Test
    fun `a usage-limited job is paused without a failed attempt`() {
        val jobs: FacebookArticleImageGenerationRepository = mock()
        val articles: ArticleRepository = mock()
        val contents: ArticleContentRepository = mock()
        val generator: CodexArticleImageGenerator = mock()
        val article = Article(id = 538, url = "https://example.com/a", language = "pl", title = "Title", createdByUserId = 1)
        whenever(generator.isEnabled()).thenReturn(true)
        whenever(jobs.claim()).thenReturn(FacebookArticleImageJob(538, 1))
        whenever(articles.findById(538)).thenReturn(Optional.of(article))
        whenever(contents.findById(538)).thenReturn(Optional.empty())
        val retryAt = Instant.parse("2026-11-04T06:37:00Z")
        whenever(generator.generate(any(), org.mockito.kotlin.anyOrNull()))
            .thenReturn(ArticleImageGenerationResult.UsageLimited("Codex usage limit reached", retryAt))

        FacebookArticleImageGenerationScheduler(jobs, articles, contents, generator, mock()).generateOne()

        verify(jobs).pauseForUsageLimit(538, retryAt, "Codex usage limit reached")
        verify(jobs, never()).fail(any(), any(), any())
        verify(jobs, never()).failPermanent(any(), any())
    }

    @Test
    fun `pausing undoes the attempt and holds the shared generation slot`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        FacebookArticleImageGenerationRepository(jdbc)
            .pauseForUsageLimit(538, Instant.parse("2026-11-04T06:37:00Z"), "Codex usage limit reached")

        val sql = argumentCaptor<String>()
        verify(jdbc, times(2)).update(sql.capture(), any<Map<String, Any>>())
        assertTrue(sql.firstValue.contains("attempt_count = GREATEST(attempt_count - 1, 0)"))
        assertTrue(sql.firstValue.contains("status = 'MISSING_THUMBNAIL'"))
        assertTrue(sql.secondValue.contains("next_claim_at = GREATEST(next_claim_at, :retryAt)"))
    }

    @Test
    fun `publication waits for pending or failed images up to the configured limit`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        whenever(jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<FacebookPublicationJob>>())).thenReturn(emptyList())

        FacebookPublicationQueue(jdbc).claim(waitForImages = true, imageWaitMax = Duration.ofHours(24))

        val sql = argumentCaptor<String>()
        val params = argumentCaptor<Map<String, Any>>()
        verify(jdbc).query(sql.capture(), params.capture(), any<RowMapper<FacebookPublicationJob>>())
        assertTrue(sql.firstValue.contains("image.status IN ('MISSING_THUMBNAIL', 'GENERATING', 'FAILED')"))
        assertTrue(sql.firstValue.contains("publication.created_at <= now() - :waitSeconds * interval '1 second'"))
        assertEquals(86_400L, params.firstValue["waitSeconds"])
        assertEquals(true, params.firstValue["waitForImages"])
    }
}
