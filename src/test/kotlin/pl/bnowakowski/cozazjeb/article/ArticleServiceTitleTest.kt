// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentResult
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.time.Instant
import java.util.Optional

class ArticleServiceTitleTest {

    @Test
    fun `create does not persist facebook import marker as quote for linked article`() {
        val articleRepository: ArticleRepository = mock()
        val enrichmentService: EnrichmentService = mock()
        val articleContentRepository: ArticleContentRepository = mock()
        val service = ArticleService(
            articleRepository,
            enrichmentService,
            mock<AppUserRepository>(),
            articleContentRepository,
        )
        whenever(articleRepository.existsByUrl(any())).thenReturn(false)
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = "TVN24 article",
                thumbnail = null,
                lead = "A lead from the linked article.",
                plainText = "A lead from the linked article.",
            ),
        )
        whenever(articleRepository.save(any())).thenAnswer { invocation ->
            (invocation.arguments[0] as Article).copy(id = 44L)
        }

        service.create(
            ArticleInput(
                url = "https://tvn24.pl/biznes/ze-swiata/donald-trump-pytany-o-tajwan-powiedzialem-ze-nie-rozmawiam-o-tym-st9050825",
                language = "pl",
                quote = " Co\u00A0za   zjeb ",
            ),
            creatorId = 7L,
        )

        val articleCaptor = argumentCaptor<Article>()
        verify(articleRepository).save(articleCaptor.capture())
        assertNull(articleCaptor.firstValue.quote)
    }

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
    fun `Facebook share fallback title is replaced with cached post excerpt`() {
        val postText = "Treść wpisu z udostępnionego linku Facebooka powinna stać się tytułem po ręcznym zapisaniu cache."
        val title = titleForSave(
            url = "https://www.facebook.com/share/18e3PrKAEK/",
            title = "Facebook share",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook photo fallback title is replaced with cached post excerpt`() {
        val postText = "Trybunał Konstytucyjny orzekł, że niezgodne z konstytucją jest takie rozumienie ustawy o statusie sędziów TK."
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            title = "Facebook photo",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook photo login title is replaced with cached post excerpt`() {
        val postText = "Trybunał Konstytucyjny orzekł, że niezgodne z konstytucją jest takie rozumienie ustawy o statusie sędziów TK."
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            title = "Zaloguj się lub zarejestruj, aby wyświetlić",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook photo login title is not saved when no post text exists`() {
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            title = "Zaloguj się lub zarejestruj, aby wyświetlić",
            lead = null,
            contentForCache = null,
        )

        assertEquals(null, title)
    }

    @Test
    fun `Facebook photo fallback title is not cached as content`() {
        val content = selectContentForCache(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            plainText = null,
            lead = null,
            title = "Facebook photo",
        )

        assertEquals(null, content)
    }

    @Test
    fun `Facebook photo login title is not cached as content`() {
        val content = selectContentForCache(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            plainText = null,
            lead = null,
            title = "Zaloguj się lub zarejestruj, aby wyświetlić",
        )

        assertEquals(null, content)
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

    @Test
    fun `replacing Facebook post cached content updates title from content excerpt`() {
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
        whenever(articleContentRepository.findById(249L)).thenReturn(
            Optional.of(ArticleContent(articleId = 249L, content = postText, truncated = false)),
        )

        val savedContent = service.replaceContentCache(249L, postText)

        assertEquals(postText, savedContent.content)
        verify(articleRepository).save(article.copy(title = postText.excerptForArticleTitle()))
    }

    @Test
    fun `preserved article content cache is trimmed without changing title`() {
        val articleContentRepository = mock<ArticleContentRepository>()
        val service = ArticleService(
            articleRepository = mock<ArticleRepository>(),
            enrichmentService = mock<EnrichmentService>(),
            appUserRepository = mock<AppUserRepository>(),
            articleContentRepository = articleContentRepository,
        )
        val longContent = List(260) { "word$it" }.joinToString(" ")
        val contentCaptor = argumentCaptor<ArticleContent>()
        val method = ArticleService::class.java.getDeclaredMethod(
            "preserveContent",
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true

        method.invoke(service, 42L, "https://example.com/article", longContent)

        verify(articleContentRepository).insert(contentCaptor.capture())
        assertEquals(42L, contentCaptor.firstValue.articleId)
        assertTrue(contentCaptor.firstValue.content.length <= 1_203)
        assertTrue(contentCaptor.firstValue.content.endsWith("..."))
        assertEquals(true, contentCaptor.firstValue.truncated)
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
