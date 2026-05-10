// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.time.Instant
import java.util.Optional

class ArticleServiceTitleTest {

    @Test
    fun `Facebook reel placeholder title is replaced with lead excerpt`() {
        val lead = "This Facebook reel has enough useful text to become the article title instead of the placeholder."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook reel",
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook plugin page title is replaced with lead excerpt for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook",
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook watch metrics title is replaced with lead excerpt for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the objectives are achieved."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "1.1M views, 3K reactions | $lead | Reuters",
            lead = lead,
            contentForCache = "1.1M views, 3K reactions | $lead | Reuters",
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook profile fallback title is replaced with cached post excerpt`() {
        val postText = "Michał Zimny zaczyna ten post od właściwej treści, więc to ona powinna zostać tytułem."
        val title = titleForSave(
            url = "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
            title = "Facebook post by mzimu",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook profile fallback title is not cached as content`() {
        val content = selectContentForCache(
            url = "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
            plainText = null,
            lead = null,
            title = "Facebook post by mzimu",
        )

        assertEquals(null, content)
    }

    @Test
    fun `patching Facebook post cached content updates title from content excerpt`() {
        val articleRepository = mock<ArticleRepository>()
        val articleContentRepository = mock<ArticleContentRepository>()
        val service = ArticleService(
            articleRepository = articleRepository,
            enrichmentService = mock<EnrichmentService>(),
            appUserRepository = mock<AppUserRepository>(),
            articleContentRepository = articleContentRepository,
        )
        val article = Article(
            id = 249L,
            url = "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
            language = "pl",
            title = "Facebook post by mzimu",
            thumbnail = null,
            lead = null,
            quote = null,
            aiSummary = null,
            publishedAt = Instant.parse("2026-05-10T08:54:32Z"),
            createdByUserId = 1L,
            createdAt = Instant.parse("2026-05-10T08:54:31Z"),
        )
        val postText = "Michał Zimny zaczyna ten post od właściwej treści, więc to ona powinna zostać tytułem."
        whenever(articleRepository.findById(249L)).thenReturn(Optional.of(article))
        whenever(articleRepository.save(any())).thenAnswer { it.arguments[0] }

        val saved = service.patch(249L, mapOf("content" to postText))

        assertEquals(postText.excerptForArticleTitle(), saved.title)
    }

    private fun titleForSave(url: String, title: String?, lead: String?, contentForCache: String?): String? {
        val service = articleService()
        val method = ArticleService::class.java.getDeclaredMethod(
            "titleForSave",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, title, lead, contentForCache) as String?
    }

    private fun selectContentForCache(url: String, plainText: String?, lead: String?, title: String?): String? {
        val service = articleService()
        val method = ArticleService::class.java.getDeclaredMethod(
            "selectContentForCache",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, plainText, lead, title) as String?
    }

    private fun articleService(): ArticleService =
        ArticleService(
            articleRepository = mock<ArticleRepository>(),
            enrichmentService = mock<EnrichmentService>(),
            appUserRepository = mock<AppUserRepository>(),
            articleContentRepository = mock<ArticleContentRepository>(),
        )
}

private fun String.excerptForArticleTitle(): String =
    if (length <= 120) this else take(120).trimEnd() + "..."
