// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.rss.MetaSourceImageCache
import java.net.HttpURLConnection
import java.net.URI

data class SourceThumbnailToCheck(val articleId: Long, val url: String)

@Component
class SourceThumbnailDeliveryChecker(
    private val jdbc: NamedParameterJdbcTemplate,
    private val articles: ArticleRepository,
    private val metaImages: MetaSourceImageCache,
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
            val error = if (metaImages.supports(candidate.url)) {
                val article = articles.findById(candidate.articleId).orElse(null) ?: continue
                if (article.thumbnail != candidate.url) continue
                runCatching {
                    if (metaImages.loadOrFetch(article) != null) null else "Cached source image unavailable"
                }.getOrElse { it.javaClass.simpleName }
            } else {
                check(candidate.url)
            }
            jdbc.update(
                """INSERT INTO source_thumbnail_delivery_check(article_id, thumbnail_url, status, checked_at, last_error)
                   SELECT id, :url, :status, now(), :error FROM article
                   WHERE id = :articleId AND thumbnail = :url
                   ON CONFLICT (article_id) DO UPDATE
                   SET thumbnail_url = EXCLUDED.thumbnail_url, status = EXCLUDED.status,
                       checked_at = EXCLUDED.checked_at, last_error = EXCLUDED.last_error""",
                mapOf(
                    "articleId" to candidate.articleId, "url" to candidate.url,
                    "status" to if (error == null) "AVAILABLE" else "UNAVAILABLE",
                    "error" to error,
                ),
            )
            if (error != null) log.info("Source thumbnail check failed for article {}: {}", candidate.articleId, error)
        }
    }

    private fun check(url: String): String? {
        if (!FacebookArticleImageGenerationRepository.hasUsableSourceThumbnail(url)) return "Invalid image URL"
        return try {
            val connection = (URI(url.trim()).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            }
            try {
                val status = connection.responseCode
                val type = connection.contentType.orEmpty().substringBefore(';').trim().lowercase()
                val prefix = if (status in 200..299) connection.inputStream.use { it.readNBytes(32) } else byteArrayOf()
                when {
                    status !in 200..299 -> "HTTP $status"
                    !type.startsWith("image/") -> "Response is not an image ($type)"
                    prefix.isEmpty() -> "Empty image response"
                    prefix.toString(Charsets.UTF_8).trimStart().startsWith("<html", ignoreCase = true) -> "HTML returned as an image"
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        } catch (ex: Exception) {
            ex.javaClass.simpleName
        }
    }
}
