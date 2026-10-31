// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.article

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

data class ArticleTitleShorteningJob(val articleId: Long, val originalTitle: String, val attemptCount: Int)

@Repository
class ArticleTitleShorteningRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /** Put back a stored short title when an import or refresh restored the same long one. */
    fun reapplyShortened(): Int = jdbc.update(
        """UPDATE article a SET title = s.shortened_title
           FROM article_title_shortening s
           WHERE s.article_id = a.id AND s.status = 'SHORTENED' AND a.title = s.original_title""",
        emptyMap<String, Any>(),
    )

    /**
     * Claims the newest article whose title is too long. The lease (next_attempt_at in the future)
     * keeps it from being claimed again while the model runs, and lets a crashed run retry later.
     * A title that changed since the last attempt starts over with fresh attempts.
     */
    fun claim(maxLength: Int, lease: Instant): ArticleTitleShorteningJob? = jdbc.query(
        """WITH candidate AS (
               SELECT a.id, a.title FROM article a
               LEFT JOIN article_title_shortening s ON s.article_id = a.id
               WHERE char_length(a.title) > :maxLength
                 AND (s.article_id IS NULL OR s.original_title <> a.title
                      OR (s.status = 'PENDING' AND s.next_attempt_at <= now()))
               ORDER BY a.id DESC LIMIT 1
           )
           INSERT INTO article_title_shortening AS s
               (article_id, original_title, status, attempt_count, next_attempt_at, updated_at)
           SELECT id, title, 'PENDING', 0, :lease, now() FROM candidate
           ON CONFLICT (article_id) DO UPDATE SET
               attempt_count = CASE WHEN s.original_title = EXCLUDED.original_title THEN s.attempt_count ELSE 0 END,
               shortened_title = CASE WHEN s.original_title = EXCLUDED.original_title THEN s.shortened_title END,
               original_title = EXCLUDED.original_title,
               status = 'PENDING',
               next_attempt_at = EXCLUDED.next_attempt_at,
               updated_at = now()
           RETURNING s.article_id, s.original_title, s.attempt_count""",
        mapOf("maxLength" to maxLength, "lease" to Timestamp.from(lease)),
    ) { rs, _ ->
        ArticleTitleShorteningJob(rs.getLong("article_id"), rs.getString("original_title"), rs.getInt("attempt_count"))
    }.firstOrNull()

    /** Replaces the title only if it is still the one that was shortened. */
    @Transactional
    fun complete(job: ArticleTitleShorteningJob, shortTitle: String, tool: String): Boolean {
        val updated = jdbc.update(
            "UPDATE article SET title = :short WHERE id = :id AND title = :original",
            mapOf("short" to shortTitle, "id" to job.articleId, "original" to job.originalTitle),
        )
        jdbc.update(
            """UPDATE article_title_shortening
               SET status = CASE WHEN :updated THEN 'SHORTENED' ELSE 'PENDING' END,
                   shortened_title = CASE WHEN :updated THEN :short END,
                   tool = :tool, last_error = NULL, next_attempt_at = now(), updated_at = now()
               WHERE article_id = :id""",
            mapOf("updated" to (updated > 0), "short" to shortTitle, "tool" to tool, "id" to job.articleId),
        )
        return updated > 0
    }

    fun fail(job: ArticleTitleShorteningJob, maxAttempts: Int, retryAt: Instant, reason: String) {
        jdbc.update(
            """UPDATE article_title_shortening
               SET attempt_count = attempt_count + 1,
                   status = CASE WHEN attempt_count + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                   next_attempt_at = :retryAt, last_error = :reason, updated_at = now()
               WHERE article_id = :id""",
            mapOf("maxAttempts" to maxAttempts, "retryAt" to Timestamp.from(retryAt),
                "reason" to reason.take(300), "id" to job.articleId),
        )
    }

    /** A usage limit is not the article's fault: wait without spending an attempt. */
    fun pause(job: ArticleTitleShorteningJob, retryAt: Instant, reason: String) {
        jdbc.update(
            """UPDATE article_title_shortening
               SET next_attempt_at = :retryAt, last_error = :reason, updated_at = now()
               WHERE article_id = :id""",
            mapOf("retryAt" to Timestamp.from(retryAt), "reason" to reason.take(300), "id" to job.articleId),
        )
    }
}
