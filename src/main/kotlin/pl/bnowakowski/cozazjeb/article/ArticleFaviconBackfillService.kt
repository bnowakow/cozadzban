// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService

@Service
class ArticleFaviconBackfillService(
    private val articleRepository: ArticleRepository,
    private val enrichmentService: EnrichmentService,
    @Value("\${app.favicon-backfill.enabled:true}") private val enabled: Boolean,
    @Value("\${app.favicon-backfill.max-articles:500}") private val maxArticles: Int,
) {

    private val log = LoggerFactory.getLogger(ArticleFaviconBackfillService::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!enabled) {
            log.info("Article favicon backfill skipped; disabled by app.favicon-backfill.enabled=false")
            return
        }

        Thread({ backfillOnce() }, "article-favicon-backfill").apply {
            isDaemon = true
            start()
        }
    }

    fun backfillOnce(): ArticleFaviconBackfillResult {
        val limit = maxArticles.coerceAtLeast(0)
        if (limit == 0) return ArticleFaviconBackfillResult(processed = 0, updated = 0, failed = 0)

        val candidates = articleRepository.findFaviconBackfillCandidates(limit)
        if (candidates.isEmpty()) {
            log.info("Article favicon backfill complete; no candidates found")
            return ArticleFaviconBackfillResult(processed = 0, updated = 0, failed = 0)
        }

        var updated = 0
        var failed = 0
        candidates.forEach { article ->
            val id = article.id
            if (id == null) {
                failed += 1
                return@forEach
            }

            val favicon = runCatching { enrichmentService.refreshFavicon(article.url) }
                .onFailure { ex ->
                    log.debug(
                        "Article favicon backfill failed; articleId={}; url='{}'; exception={}: {}",
                        id,
                        article.url,
                        ex.javaClass.simpleName,
                        ex.message,
                    )
                }
                .getOrNull()

            if (favicon.isNullOrBlank()) {
                failed += 1
                return@forEach
            }

            if (articleRepository.updateFavicon(id, favicon)) {
                updated += 1
            } else {
                failed += 1
            }
        }

        val result = ArticleFaviconBackfillResult(
            processed = candidates.size,
            updated = updated,
            failed = failed,
        )
        log.info(
            "Article favicon backfill finished; processed={}; updated={}; failed={}; limit={}",
            result.processed,
            result.updated,
            result.failed,
            limit,
        )
        return result
    }
}

data class ArticleFaviconBackfillResult(
    val processed: Int,
    val updated: Int,
    val failed: Int,
)
