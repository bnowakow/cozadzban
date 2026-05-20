// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.enrichment.EnrichmentService

class ArticleFaviconBackfillServiceTest {

    private val articleRepository: ArticleRepository = mock()
    private val enrichmentService: EnrichmentService = mock()

    @Test
    fun `backfill downloads and stores real favicon for candidates`() {
        val article = Article(
            id = 7L,
            url = "https://example.com/story",
            language = "en",
            createdByUserId = 1L,
        )
        whenever(articleRepository.findFaviconBackfillCandidates(10)).thenReturn(listOf(article))
        whenever(enrichmentService.refreshFavicon(article.url)).thenReturn("/favicons/example.png")
        whenever(articleRepository.updateFavicon(7L, "/favicons/example.png")).thenReturn(true)
        val service = ArticleFaviconBackfillService(articleRepository, enrichmentService, enabled = true, maxArticles = 10)

        val result = service.backfillOnce()

        assertEquals(ArticleFaviconBackfillResult(processed = 1, updated = 1, failed = 0), result)
        verify(enrichmentService).refreshFavicon(article.url)
        verify(articleRepository).updateFavicon(7L, "/favicons/example.png")
    }

    @Test
    fun `backfill does not update article when favicon cannot be downloaded`() {
        val article = Article(
            id = 8L,
            url = "https://example.com/no-icon",
            language = "en",
            createdByUserId = 1L,
        )
        whenever(articleRepository.findFaviconBackfillCandidates(10)).thenReturn(listOf(article))
        whenever(enrichmentService.refreshFavicon(article.url)).thenReturn(null)
        val service = ArticleFaviconBackfillService(articleRepository, enrichmentService, enabled = true, maxArticles = 10)

        val result = service.backfillOnce()

        assertEquals(ArticleFaviconBackfillResult(processed = 1, updated = 0, failed = 1), result)
        verify(articleRepository, never()).updateFavicon(any(), any())
    }
}
