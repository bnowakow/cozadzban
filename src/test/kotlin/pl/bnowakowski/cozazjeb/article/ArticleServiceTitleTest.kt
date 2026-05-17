// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    fun `create rejects Facebook article with same published time and thumbnail media id`() {
        val articleRepository: ArticleRepository = mock()
        val enrichmentService: EnrichmentService = mock()
        val articleContentRepository: ArticleContentRepository = mock()
        val service = ArticleService(
            articleRepository,
            enrichmentService,
            mock<AppUserRepository>(),
            articleContentRepository,
        )
        val publishedAt = Instant.parse("2026-05-07T09:08:45Z")
        val mediaId = "688617344_1661302661683504_8203445369845282032_n.jpg"
        whenever(articleRepository.existsByUrl(any())).thenReturn(false)
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = "Facebook post",
                thumbnail = "https://scontent-waw2-2.xx.fbcdn.net/v/t15.5256-10/$mediaId?new=1",
                lead = "Mili Panstwo, prezydent chce referendum ws. polityki klimatycznej.",
                plainText = null,
                publishedAt = publishedAt,
            ),
        )
        whenever(articleRepository.findFacebookDuplicateCandidatesByPublishedAt(publishedAt)).thenReturn(
            listOf(
                ArticleDuplicateCandidate(
                    article = Article(
                        id = 237L,
                        url = "https://www.facebook.com/reel/2758125771253657/",
                        language = "pl",
                        thumbnail = "https://scontent-waw2-2.xx.fbcdn.net/v/t15.5256-10/$mediaId?old=1",
                        createdByUserId = 7L,
                        publishedAt = publishedAt,
                    ),
                    content = "Different extracted text is still duplicate because the Facebook media id matches.",
                ),
            ),
        )

        val exception = assertThrows(ArticleUrlConflictException::class.java) {
            service.create(
                ArticleInput(
                    url = "https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl",
                    language = "pl",
                ),
                creatorId = 7L,
            )
        }

        assertEquals("https://www.facebook.com/reel/2758125771253657/", exception.url)
        verify(articleRepository, org.mockito.kotlin.never()).save(any())
    }

    @Test
    fun `create rejects Facebook article with same published time and normalized content prefix`() {
        val articleRepository: ArticleRepository = mock()
        val enrichmentService: EnrichmentService = mock()
        val articleContentRepository: ArticleContentRepository = mock()
        val service = ArticleService(
            articleRepository,
            enrichmentService,
            mock<AppUserRepository>(),
            articleContentRepository,
        )
        val publishedAt = Instant.parse("2026-05-07T19:07:02Z")
        val postText = "Mili Panstwo, patrzcie na to: " +
            "Tylko w latach 2021-2024 UE zaplacila za import surowcow kopalnych 1,8 bln euro. " +
            "To jest rownowartosc dwukrotnosci polskiego PKB z 2025 r. " +
            "Pierwsze 44 dni wojny w Zatoce Perskiej kosztowaly UE dodatkowe miliardy."
        val photoText = "Mili Panstwo, patrzcie na to: " +
            "Tylko w latach 2021-2024 UE zaplacila za import surowcow kopalnych 1,8 bln euro. " +
            "To jest rownowartosc dwukrotnosci polskiego PKB z 2025 r. ... " +
            "Pierwsze 44 dni wojny w Zatoce Perskiej kosztowaly UE dodatkowe miliardy."
        whenever(articleRepository.existsByUrl(any())).thenReturn(false)
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = "Facebook photo",
                thumbnail = "https://scontent-waw2-1.xx.fbcdn.net/v/t39.30808-6/688084211_1496190558542372_428040976938426255_n.jpg",
                lead = photoText,
                plainText = photoText,
                publishedAt = publishedAt,
            ),
        )
        whenever(articleRepository.findFacebookDuplicateCandidatesByPublishedAt(publishedAt)).thenReturn(
            listOf(
                ArticleDuplicateCandidate(
                    article = Article(
                        id = 240L,
                        url = "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0cmzW1Mr2ZtDhdBUVJEJWVzXNHRfkr3g8RbAEG5rhtV8ZCMzG9jXjZXFPQJeNNfFdl",
                        language = "pl",
                        thumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1496190555209039",
                        createdByUserId = 7L,
                        publishedAt = publishedAt,
                    ),
                    content = postText,
                ),
            ),
        )

        val exception = assertThrows(ArticleUrlConflictException::class.java) {
            service.create(
                ArticleInput(
                    url = "https://www.facebook.com/photo/?fbid=1496190555209039&set=a.248625223298918",
                    language = "pl",
                ),
                creatorId = 7L,
            )
        }

        assertEquals(
            "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0cmzW1Mr2ZtDhdBUVJEJWVzXNHRfkr3g8RbAEG5rhtV8ZCMzG9jXjZXFPQJeNNfFdl",
            exception.url,
        )
        verify(articleRepository, org.mockito.kotlin.never()).save(any())
    }

    @Test
    fun `create rejects Facebook article with same published time and short matching lookaside content`() {
        val articleRepository: ArticleRepository = mock()
        val enrichmentService: EnrichmentService = mock()
        val articleContentRepository: ArticleContentRepository = mock()
        val service = ArticleService(
            articleRepository,
            enrichmentService,
            mock<AppUserRepository>(),
            articleContentRepository,
        )
        val publishedAt = Instant.parse("2026-05-08T06:00:41Z")
        val postText = "Do roboty kochani, ale też nie przemęczajcie się. " +
            "Przemęczenie szkodzi zdrowiu, a nie będzie miał was kto leczyć."
        whenever(articleRepository.existsByUrl(any())).thenReturn(false)
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = postText,
                thumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1028710846157213",
                lead = postText,
                plainText = null,
                publishedAt = publishedAt,
            ),
        )
        whenever(articleRepository.findFacebookDuplicateCandidatesByPublishedAt(publishedAt)).thenReturn(
            listOf(
                ArticleDuplicateCandidate(
                    article = Article(
                        id = 241L,
                        url = "https://www.facebook.com/serwisdonaldpl/posts/pfbid0NrAXQE57R6yqPjam86nLnRYM5jdaSCLo7SwxJCh1w3ZPLykqUH41E4SJ9uM2HR2Ul",
                        language = "pl",
                        thumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1028710846157213",
                        createdByUserId = 7L,
                        publishedAt = publishedAt,
                    ),
                    content = postText,
                ),
            ),
        )

        val exception = assertThrows(ArticleUrlConflictException::class.java) {
            service.create(
                ArticleInput(
                    url = "https://www.facebook.com/serwisdonaldpl/posts/pfbid0NyhhziCpsmtnWwrhc9m4wfQ4ZP8h1K42QVox9Zt7U5pFmaYRvMF6KPeBgYnTaeRZl",
                    language = "pl",
                ),
                creatorId = 7L,
            )
        }

        assertEquals(
            "https://www.facebook.com/serwisdonaldpl/posts/pfbid0NrAXQE57R6yqPjam86nLnRYM5jdaSCLo7SwxJCh1w3ZPLykqUH41E4SJ9uM2HR2Ul",
            exception.url,
        )
        verify(articleRepository, org.mockito.kotlin.never()).save(any())
    }

    @Test
    fun `create rejects Facebook article when existing cache is noisy but title matches same published content`() {
        val articleRepository: ArticleRepository = mock()
        val enrichmentService: EnrichmentService = mock()
        val articleContentRepository: ArticleContentRepository = mock()
        val service = ArticleService(
            articleRepository,
            enrichmentService,
            mock<AppUserRepository>(),
            articleContentRepository,
        )
        val publishedAt = Instant.parse("2026-05-02T04:10:44Z")
        val title = "Pentagon ogłasza: Stany Zjednoczone wycofują tysiące żołnierzy z Niemiec 👇"
        whenever(articleRepository.existsByUrl(any())).thenReturn(false)
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = title,
                thumbnail = null,
                lead = title,
                plainText = null,
                publishedAt = publishedAt,
            ),
        )
        whenever(articleRepository.findFacebookDuplicateCandidatesByPublishedAt(publishedAt)).thenReturn(
            listOf(
                ArticleDuplicateCandidate(
                    article = Article(
                        id = 262L,
                        url = "https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl",
                        language = "pl",
                        title = title,
                        lead = title,
                        createdByUserId = 7L,
                        publishedAt = publishedAt,
                    ),
                    content = "Online status indicator Active Bartek Dobrowolski-Nowakowski · Co za zjeb See more RMF24.pl",
                ),
            ),
        )

        val exception = assertThrows(ArticleUrlConflictException::class.java) {
            service.create(
                ArticleInput(
                    url = "https://www.facebook.com/rmf24/posts/pfbid0qX8Gq1thmkWNY64v6LHdjmw5xsYWX1KDC5mrtumXepgTviJY38DE12DYCVHYfvq9l",
                    language = "pl",
                ),
                creatorId = 7L,
            )
        }

        assertEquals(
            "https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl",
            exception.url,
        )
        verify(articleRepository, org.mockito.kotlin.never()).save(any())
    }

    @Test
    fun `Facebook reel placeholder title is replaced with full lead`() {
        val lead = "This Facebook reel has enough useful text to become the article title instead of the placeholder."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook reel",
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead, title)
    }

    @Test
    fun `Facebook plugin page title is replaced with full lead for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook",
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead, title)
    }

    @Test
    fun `Facebook watch metrics title is replaced with full lead for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the objectives are achieved."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "1.1M views, 3K reactions | $lead | Reuters",
            lead = lead,
            contentForCache = "1.1M views, 3K reactions | $lead | Reuters",
        )

        assertEquals(lead, title)
    }

    @Test
    fun `Facebook fallback title uses trimmed cache text for very long lead`() {
        val lead = List(260) { "word$it" }.joinToString(" ")
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1287889633490862&set=a.358353523111149",
            title = "Facebook photo",
            lead = lead,
            contentForCache = lead,
        )

        assertTrue(title!!.length <= 1_203)
        assertTrue(title.endsWith("..."))
        assertTrue(lead.startsWith(title.removeSuffix("...")))
    }

    @Test
    fun `Facebook profile fallback title is replaced with full cached post text`() {
        val postText = "Michał Zimny zaczyna ten post od właściwej treści, więc to ona powinna zostać tytułem."
        val title = titleForSave(
            url = "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
            title = "Facebook post by mzimu",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText, title)
    }

    @Test
    fun `Facebook share fallback title is replaced with full cached post text`() {
        val postText = "Treść wpisu z udostępnionego linku Facebooka powinna stać się tytułem po ręcznym zapisaniu cache."
        val title = titleForSave(
            url = "https://www.facebook.com/share/18e3PrKAEK/",
            title = "Facebook share",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText, title)
    }

    @Test
    fun `Facebook photo fallback title is replaced with full cached post text`() {
        val postText = "Trybunał Konstytucyjny orzekł, że niezgodne z konstytucją jest takie rozumienie ustawy o statusie sędziów TK."
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            title = "Facebook photo",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText, title)
    }

    @Test
    fun `Facebook photo login title is replaced with full cached post text`() {
        val postText = "Trybunał Konstytucyjny orzekł, że niezgodne z konstytucją jest takie rozumienie ustawy o statusie sędziów TK."
        val title = titleForSave(
            url = "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            title = "Zaloguj się lub zarejestruj, aby wyświetlić",
            lead = null,
            contentForCache = postText,
        )

        assertEquals(postText, title)
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

        assertEquals(postText, saved.title)
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
        verify(articleRepository).save(article.copy(title = postText))
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
