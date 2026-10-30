// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Instant

data class FacebookArticleImageJob(val articleId: Long, val attemptCount: Int)
const val MISSING_IMAGE_CONTEXT_REASON = "Article has no text to illustrate."
data class FacebookArticleImageStatusRow(
    val articleId: Long,
    val title: String?,
    val language: String,
    val sourceThumbnail: String?,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val lastError: String?,
    val updatedAt: Instant?,
)
data class FacebookArticleImageQueueRow(
    val position: Long,
    val articleId: Long,
    val title: String?,
    val language: String,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
)

@Repository
class FacebookArticleImageGenerationRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun queuedCount(): Long = jdbc.queryForObject(
        "SELECT count(*) FROM facebook_article_image_generation WHERE status = 'MISSING_THUMBNAIL'",
        emptyMap<String, Any>(), Long::class.java,
    ) ?: 0L

    /** Use the same ordering as claim(); rows not yet due remain visible in the queue. */
    fun queuePage(limit: Int, offset: Int): List<FacebookArticleImageQueueRow> = jdbc.query(
        """WITH ranked AS (
               SELECT article_id, attempt_count, next_attempt_at, last_error,
                      row_number() OVER (ORDER BY next_attempt_at, article_id) AS queue_position
               FROM facebook_article_image_generation
               WHERE status = 'MISSING_THUMBNAIL'
           )
           SELECT ranked.queue_position, ranked.article_id, a.title, a.language,
                  ranked.attempt_count, ranked.next_attempt_at, ranked.last_error
           FROM ranked JOIN article a ON a.id = ranked.article_id
           ORDER BY ranked.queue_position LIMIT :limit OFFSET :offset""",
        mapOf("limit" to limit, "offset" to offset),
    ) { rs, _ ->
        FacebookArticleImageQueueRow(
            position = rs.getLong("queue_position"),
            articleId = rs.getLong("article_id"),
            title = rs.getString("title"),
            language = rs.getString("language"),
            attemptCount = rs.getInt("attempt_count"),
            nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
            lastError = rs.getString("last_error"),
        )
    }

    fun countStatuses(status: String): Long = jdbc.queryForObject(
        """SELECT count(*) FROM article a
           LEFT JOIN facebook_article_image_generation job ON job.article_id = a.id
           WHERE :status = '' OR COALESCE(job.status, 'SOURCE_THUMBNAIL') = :status""",
        mapOf("status" to status), Long::class.java,
    ) ?: 0L

    fun statusPage(status: String, limit: Int, offset: Int): List<FacebookArticleImageStatusRow> = jdbc.query(
        """SELECT a.id, a.title, a.language, a.thumbnail, job.status, job.attempt_count,
                  job.next_attempt_at, job.last_error, job.updated_at
           FROM article a
           LEFT JOIN facebook_article_image_generation job ON job.article_id = a.id
           WHERE :status = '' OR COALESCE(job.status, 'SOURCE_THUMBNAIL') = :status
           ORDER BY a.id DESC LIMIT :limit OFFSET :offset""",
        mapOf("status" to status, "limit" to limit, "offset" to offset),
    ) { rs, _ ->
        FacebookArticleImageStatusRow(
            articleId = rs.getLong("id"),
            title = rs.getString("title"),
            language = rs.getString("language"),
            sourceThumbnail = rs.getString("thumbnail"),
            status = rs.getString("status") ?: "SOURCE_THUMBNAIL",
            attemptCount = rs.getInt("attempt_count"),
            nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant(),
            lastError = rs.getString("last_error"),
            updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
        )
    }

    /** Called in the article creation transaction, after the enrichment result has been saved. */
    fun markMissingIfNeeded(articleId: Long, sourceThumbnail: String?) {
        if (hasUsableSourceThumbnail(sourceThumbnail)) return
        markMissing(articleId)
    }

    /** Meta explicitly rejected a source URL before publishing a post. */
    fun sourceThumbnailRejected(articleId: Long) = markMissing(articleId)

    /** Rebuild the illustration from the article's current text after enrichment is corrected. */
    fun requestRegeneration(articleId: Long) {
        jdbc.update(
            """INSERT INTO facebook_article_image_generation(article_id, status)
               VALUES (:articleId, 'MISSING_THUMBNAIL')
               ON CONFLICT (article_id) DO UPDATE
               SET status = CASE WHEN facebook_article_image_generation.status = 'GENERATING'
                                 THEN 'GENERATING' ELSE 'MISSING_THUMBNAIL' END,
                   regenerate_requested = facebook_article_image_generation.status = 'GENERATING',
                   attempt_count = CASE WHEN facebook_article_image_generation.status = 'GENERATING'
                                        THEN facebook_article_image_generation.attempt_count ELSE 0 END,
                   next_attempt_at = now(), last_error = NULL, updated_at = now()""",
            mapOf("articleId" to articleId),
        )
    }

    private fun markMissing(articleId: Long) {
        jdbc.update(
            """INSERT INTO facebook_article_image_generation(article_id, status)
               VALUES (:articleId, 'MISSING_THUMBNAIL')
               ON CONFLICT (article_id) DO UPDATE
               SET status = 'MISSING_THUMBNAIL', attempt_count = 0,
                   next_attempt_at = now(), last_error = NULL, updated_at = now()""",
            mapOf("articleId" to articleId),
        )
    }

    fun status(articleId: Long): String? = jdbc.query(
        "SELECT status FROM facebook_article_image_generation WHERE article_id = :articleId",
        mapOf("articleId" to articleId),
    ) { rs, _ -> rs.getString(1) }.firstOrNull()

    /** A stale worker may have died after claiming, before writing the generated asset. */
    fun recoverInterrupted(maxAttempts: Int) {
        jdbc.update(
            """UPDATE facebook_article_image_generation
               SET status = CASE WHEN regenerate_requested THEN 'MISSING_THUMBNAIL'
                                 WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'MISSING_THUMBNAIL' END,
                   attempt_count = CASE WHEN regenerate_requested THEN 0 ELSE attempt_count END,
                   regenerate_requested = false,
                   next_attempt_at = now(), last_error = 'Image worker stopped before completing.', updated_at = now()
               WHERE status = 'GENERATING' AND updated_at < now() - interval '10 minutes'""",
            mapOf("maxAttempts" to maxAttempts),
        )
    }

    /** Remove jobs that cannot produce a story-specific image without using a ten-minute slot. */
    fun failMissingContext(): Int = jdbc.update(
        """UPDATE facebook_article_image_generation job
           SET status = 'FAILED', last_error = :reason, updated_at = now()
           WHERE job.status = 'MISSING_THUMBNAIL'
             AND EXISTS (
                 SELECT 1 FROM article a
                 LEFT JOIN article_content c ON c.article_id = a.id
                 WHERE a.id = job.article_id
                   AND coalesce(btrim(a.title), '') = ''
                   AND coalesce(btrim(a.lead), '') = ''
                   AND coalesce(btrim(a.quote), '') = ''
                   AND coalesce(btrim(a.ai_summary), '') = ''
                   AND coalesce(btrim(c.content), '') = ''
             )""",
        mapOf("reason" to MISSING_IMAGE_CONTEXT_REASON),
    )

    /** Claim at most one article per ten minutes across blue and green containers. */
    @Transactional
    fun claim(): FacebookArticleImageJob? {
        val slot = jdbc.query(
            """SELECT id FROM facebook_article_image_generation_pace
               WHERE id = 1 AND next_claim_at <= now() FOR UPDATE SKIP LOCKED""",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getInt(1) }.firstOrNull() ?: return null
        if (hasGenerating()) return null
        val job = jdbc.query(
            """WITH candidate AS (
                   SELECT article_id FROM facebook_article_image_generation
                   WHERE status = 'MISSING_THUMBNAIL' AND next_attempt_at <= now()
                   ORDER BY next_attempt_at, article_id FOR UPDATE SKIP LOCKED LIMIT 1
               )
               UPDATE facebook_article_image_generation job
               SET status = 'GENERATING', attempt_count = job.attempt_count + 1, updated_at = now()
               FROM candidate WHERE job.article_id = candidate.article_id
               RETURNING job.article_id, job.attempt_count""",
            emptyMap<String, Any>(),
        ) { rs, _ -> FacebookArticleImageJob(rs.getLong("article_id"), rs.getInt("attempt_count")) }.firstOrNull()
        if (job != null) {
            jdbc.update(
                """UPDATE facebook_article_image_generation_pace
                   SET next_claim_at = now() + interval '10 minutes' WHERE id = :slot""",
                mapOf("slot" to slot),
            )
        }
        return job
    }

    /** ADMIN request: bypass the wait, but share the global lock and pace with automatic claims. */
    @Transactional
    fun claimManual(articleId: Long): FacebookArticleImageJob? {
        val slot = jdbc.query(
            "SELECT id FROM facebook_article_image_generation_pace WHERE id = 1 FOR UPDATE SKIP LOCKED",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getInt(1) }.firstOrNull() ?: return null
        if (hasGenerating()) return null
        val job = jdbc.query(
            """UPDATE facebook_article_image_generation
               SET status = 'GENERATING',
                   attempt_count = CASE WHEN status = 'FAILED' THEN 1 ELSE attempt_count + 1 END,
                   last_error = NULL, updated_at = now()
               WHERE article_id = :articleId AND status IN ('MISSING_THUMBNAIL', 'FAILED')
                 AND EXISTS (
                     SELECT 1 FROM article a
                     LEFT JOIN article_content c ON c.article_id = a.id
                     WHERE a.id = :articleId
                       AND (coalesce(btrim(a.title), '') <> ''
                            OR coalesce(btrim(a.lead), '') <> ''
                            OR coalesce(btrim(a.quote), '') <> ''
                            OR coalesce(btrim(a.ai_summary), '') <> ''
                            OR coalesce(btrim(c.content), '') <> '')
                 )
               RETURNING article_id, attempt_count""",
            mapOf("articleId" to articleId),
        ) { rs, _ -> FacebookArticleImageJob(rs.getLong("article_id"), rs.getInt("attempt_count")) }.firstOrNull()
        if (job != null) {
            jdbc.update(
                """UPDATE facebook_article_image_generation_pace
                   SET next_claim_at = GREATEST(next_claim_at, now() + interval '10 minutes')
                   WHERE id = :slot""",
                mapOf("slot" to slot),
            )
        }
        return job
    }

    private fun hasGenerating(): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM facebook_article_image_generation WHERE status = 'GENERATING')",
        emptyMap<String, Any>(), Boolean::class.java,
    ) == true

    fun complete(articleId: Long): String? = jdbc.query(
        """UPDATE facebook_article_image_generation
           SET status = CASE WHEN regenerate_requested THEN 'MISSING_THUMBNAIL' ELSE 'GENERATED' END,
               regenerate_requested = false, next_attempt_at = now(), last_error = NULL, updated_at = now()
           WHERE article_id = :articleId AND status = 'GENERATING'
           RETURNING status""",
        mapOf("articleId" to articleId),
    ) { rs, _ -> rs.getString("status") }.firstOrNull()

    fun fail(articleId: Long, maxAttempts: Int, reason: String) {
        jdbc.update(
            """UPDATE facebook_article_image_generation
               SET status = CASE WHEN regenerate_requested THEN 'MISSING_THUMBNAIL'
                                 WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'MISSING_THUMBNAIL' END,
                   attempt_count = CASE WHEN regenerate_requested THEN 0 ELSE attempt_count END,
                   next_attempt_at = CASE WHEN regenerate_requested THEN now() ELSE now() + interval '10 minutes' END,
                   regenerate_requested = false,
                   last_error = :reason, updated_at = now()
               WHERE article_id = :articleId AND status = 'GENERATING'""",
            mapOf("articleId" to articleId, "maxAttempts" to maxAttempts, "reason" to reason.take(200)),
        )
    }

    fun failPermanent(articleId: Long, reason: String) {
        jdbc.update(
            """UPDATE facebook_article_image_generation
               SET status = CASE WHEN regenerate_requested THEN 'MISSING_THUMBNAIL' ELSE 'FAILED' END,
                   attempt_count = CASE WHEN regenerate_requested THEN 0 ELSE attempt_count END,
                   next_attempt_at = now(), regenerate_requested = false,
                   last_error = :reason, updated_at = now()
               WHERE article_id = :articleId AND status = 'GENERATING'""",
            mapOf("articleId" to articleId, "reason" to reason.take(200)),
        )
    }

    fun rejectGenerated(articleId: Long) {
        jdbc.update(
            """UPDATE facebook_article_image_generation
               SET status = 'FAILED', last_error = 'Meta rejected the generated image.', updated_at = now()
               WHERE article_id = :articleId AND status = 'GENERATED'""",
            mapOf("articleId" to articleId),
        )
    }

    companion object {
        fun hasUsableSourceThumbnail(thumbnail: String?): Boolean {
            val uri = runCatching { URI(thumbnail?.trim().orEmpty()) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme?.lowercase() in setOf("http", "https") &&
                host.isNotBlank() && host !in setOf("localhost", "127.0.0.1", "::1")
        }
    }
}
