// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.net.URI

data class FacebookArticleImageJob(val articleId: Long, val attemptCount: Int)

@Repository
class FacebookArticleImageGenerationRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Called in the article creation transaction, after the enrichment result has been saved. */
    fun markMissingIfNeeded(articleId: Long, sourceThumbnail: String?) {
        if (hasUsableSourceThumbnail(sourceThumbnail)) return
        markMissing(articleId)
    }

    /** An article edit replaced the source thumbnail; keep an existing generated asset otherwise. */
    fun sourceThumbnailChanged(articleId: Long, sourceThumbnail: String?) {
        if (hasUsableSourceThumbnail(sourceThumbnail)) {
            jdbc.update(
                "DELETE FROM facebook_article_image_generation WHERE article_id = :articleId",
                mapOf("articleId" to articleId),
            )
        } else {
            markMissing(articleId)
        }
    }

    /** Meta explicitly rejected a source URL before publishing a post. */
    fun sourceThumbnailRejected(articleId: Long) = markMissing(articleId)

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
               SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'MISSING_THUMBNAIL' END,
                   next_attempt_at = now(), last_error = 'Image worker stopped before completing.', updated_at = now()
               WHERE status = 'GENERATING' AND updated_at < now() - interval '10 minutes'""",
            mapOf("maxAttempts" to maxAttempts),
        )
    }

    /** Claim at most one article per ten minutes across blue and green containers. */
    @Transactional
    fun claim(): FacebookArticleImageJob? {
        val slot = jdbc.query(
            """SELECT id FROM facebook_article_image_generation_pace
               WHERE id = 1 AND next_claim_at <= now() FOR UPDATE SKIP LOCKED""",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getInt(1) }.firstOrNull() ?: return null
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

    fun complete(articleId: Long): Boolean = jdbc.update(
        """UPDATE facebook_article_image_generation
           SET status = 'GENERATED', last_error = NULL, updated_at = now()
           WHERE article_id = :articleId AND status = 'GENERATING'""",
        mapOf("articleId" to articleId),
    ) == 1

    fun fail(articleId: Long, maxAttempts: Int, reason: String) {
        jdbc.update(
            """UPDATE facebook_article_image_generation
               SET status = CASE WHEN attempt_count >= :maxAttempts THEN 'FAILED' ELSE 'MISSING_THUMBNAIL' END,
                   next_attempt_at = now() + interval '10 minutes',
                   last_error = :reason, updated_at = now()
               WHERE article_id = :articleId AND status = 'GENERATING'""",
            mapOf("articleId" to articleId, "maxAttempts" to maxAttempts, "reason" to reason.take(200)),
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
