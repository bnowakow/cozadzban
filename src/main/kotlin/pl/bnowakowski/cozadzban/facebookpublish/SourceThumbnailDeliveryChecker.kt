// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.rss.SourceThumbnailCache

data class SourceThumbnailToCheck(val articleId: Long, val url: String)

@Component
class SourceThumbnailDeliveryChecker(
    private val jdbc: NamedParameterJdbcTemplate,
    private val articles: ArticleRepository,
    private val cache: SourceThumbnailCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Backfill older articles, then recheck each URL daily. A changed URL is pending again. */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    fun checkBatch() {
        val candidates = jdbc.query(
            """SELECT a.id, a.thumbnail FROM article a
               LEFT JOIN source_thumbnail_delivery_check checked
                 ON checked.article_id = a.id AND checked.thumbnail_url = a.thumbnail
               WHERE a.thumbnail IS NOT NULL AND btrim(a.thumbnail) <> ''
                 AND (checked.article_id IS NULL OR checked.checked_at < now() - interval '1 day')
               ORDER BY checked.checked_at NULLS FIRST, a.id DESC LIMIT :limit""",
            mapOf("limit" to 5),
        ) { rs, _ -> SourceThumbnailToCheck(rs.getLong("id"), rs.getString("thumbnail")) }
        for (candidate in candidates) {
            val article = articles.findById(candidate.articleId).orElse(null) ?: continue
            if (article.thumbnail != candidate.url) continue
            val error = runCatching { cache.refresh(article) }.getOrElse { it.javaClass.simpleName }
            val cacheAvailable = cache.load(article) != null
            jdbc.update(
                """INSERT INTO source_thumbnail_delivery_check(article_id, thumbnail_url, status, cache_available, checked_at, last_error)
                   SELECT id, :url, :status, :cacheAvailable, now(), :error FROM article
                   WHERE id = :articleId AND thumbnail = :url
                   ON CONFLICT (article_id) DO UPDATE
                   SET thumbnail_url = EXCLUDED.thumbnail_url, status = EXCLUDED.status,
                       cache_available = EXCLUDED.cache_available,
                       checked_at = EXCLUDED.checked_at, last_error = EXCLUDED.last_error""",
                mapOf(
                    "articleId" to candidate.articleId, "url" to candidate.url,
                    "status" to if (error == null) "AVAILABLE" else "UNAVAILABLE",
                    "cacheAvailable" to cacheAvailable,
                    "error" to error,
                ),
            )
            if (error != null) log.info("Source thumbnail check failed for article {}: {}", candidate.articleId, error)
        }
    }

}
