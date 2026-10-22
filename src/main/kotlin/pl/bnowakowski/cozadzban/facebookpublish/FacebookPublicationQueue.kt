// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class FacebookPublicationJob(
    val id: Long,
    val articleId: Long,
    val language: String,
    val pageId: String?,
    val status: String,
    val postId: String?,
    val lastError: String?,
    val createdAt: Instant,
)

/** The row is inserted in the same transaction as the article. Existing articles are never backfilled. */
@Repository
class FacebookPublicationQueue(private val jdbc: NamedParameterJdbcTemplate) {
    fun enqueue(articleId: Long, language: String) {
        jdbc.update(
            """INSERT INTO facebook_page_publication(article_id, article_id_snapshot, language, page_id, status)
               SELECT :articleId, :articleId, :language, p.page_id,
                      CASE WHEN p.page_id IS NULL THEN 'UNCONFIGURED' ELSE 'PENDING' END
               FROM (SELECT 1) seed LEFT JOIN facebook_page_connection p
                 ON p.language = :language AND p.enabled = true
               WHERE true
               ON CONFLICT (article_id_snapshot) DO NOTHING""",
            mapOf("articleId" to articleId, "language" to language),
        )
    }

    /** Claim one job atomically across application instances. A crash after this point needs reconciliation. */
    @Transactional
    fun claim(): FacebookPublicationJob? = jdbc.query(
        """WITH candidate AS (
               SELECT id FROM facebook_page_publication WHERE status = 'PENDING'
               ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
           )
           UPDATE facebook_page_publication p SET status = 'PUBLISHING', attempt_count = attempt_count + 1,
               updated_at = now() FROM candidate WHERE p.id = candidate.id
           RETURNING p.id, p.article_id_snapshot, p.language, p.page_id, p.status, p.post_id, p.last_error, p.created_at""",
        emptyMap<String, Any>(), mapper,
    ).firstOrNull()

    fun markPublished(id: Long, postId: String, permalink: String, imageSource: String) {
        jdbc.update(
            """UPDATE facebook_page_publication SET status = 'PUBLISHED', post_id = :postId,
               post_permalink = :permalink, image_source = :imageSource, last_error = NULL,
               published_at = now(), updated_at = now() WHERE id = :id AND status = 'PUBLISHING'""",
            mapOf("id" to id, "postId" to postId, "permalink" to permalink, "imageSource" to imageSource),
        )
    }

    fun markFailed(id: Long, error: String, imageSource: String? = null) = markOutcome(id, "FAILED", error, imageSource)
    fun markUncertain(id: Long, error: String, imageSource: String? = null, postId: String? = null) =
        markOutcome(id, "NEEDS_RECONCILIATION", error, imageSource, postId)

    private fun markOutcome(id: Long, status: String, error: String, imageSource: String?, postId: String? = null) {
        jdbc.update(
            """UPDATE facebook_page_publication SET status = :status, last_error = :error,
               image_source = :imageSource, post_id = COALESCE(:postId, post_id), updated_at = now()
               WHERE id = :id AND status = 'PUBLISHING'""",
            mapOf("id" to id, "status" to status, "error" to error.take(300),
                "imageSource" to imageSource, "postId" to postId),
        )
    }

    fun permalink(articleId: Long): String? = jdbc.query(
        """SELECT post_permalink FROM facebook_page_publication
           WHERE article_id = :articleId AND status = 'PUBLISHED'""",
        mapOf("articleId" to articleId),
    ) { rs, _ -> rs.getString(1) }.firstOrNull()

    fun recentProblems(): List<FacebookPublicationJob> = jdbc.query(
        """SELECT id, article_id_snapshot, language, page_id, status, post_id, last_error, created_at
           FROM facebook_page_publication WHERE status IN ('UNCONFIGURED', 'FAILED', 'NEEDS_RECONCILIATION')
           ORDER BY created_at DESC LIMIT 100""",
        emptyMap<String, Any>(), mapper,
    )

    fun retryFailed(id: Long): Boolean = jdbc.update(
        """UPDATE facebook_page_publication SET status = 'PENDING', last_error = NULL, updated_at = now()
           WHERE id = :id AND status = 'FAILED'""",
        mapOf("id" to id),
    ) == 1

    /** Attach the Page currently enabled for this language before queuing an unpublished article. */
    fun retryUnconfigured(id: Long): Boolean = jdbc.update(
        """UPDATE facebook_page_publication publication
           SET page_id = page.page_id, status = 'PENDING', last_error = NULL, updated_at = now()
           FROM facebook_page_connection page
           WHERE publication.id = :id AND publication.status = 'UNCONFIGURED'
             AND publication.article_id IS NOT NULL
             AND page.language = publication.language AND page.enabled = true""",
        mapOf("id" to id),
    ) == 1

    /** Only an ADMIN who checked the Page can decide an uncertain request did not publish. */
    fun retryAfterManualReview(id: Long): Boolean = jdbc.update(
        """UPDATE facebook_page_publication SET status = 'PENDING', post_id = NULL,
               last_error = NULL, updated_at = now()
           WHERE id = :id AND status = 'NEEDS_RECONCILIATION'""",
        mapOf("id" to id),
    ) == 1

    fun confirmAfterManualReview(id: Long, postId: String, permalink: String): Boolean {
        require(postId.matches(Regex("[0-9]+_[0-9]+"))) { "Use a Page post ID in pageId_postId format" }
        val uri = java.net.URI(permalink)
        require(uri.scheme == "https" && uri.host?.lowercase() in setOf("facebook.com", "www.facebook.com")) {
            "Use a Facebook post URL"
        }
        return jdbc.update(
            """UPDATE facebook_page_publication SET status = 'PUBLISHED', post_id = :postId,
               post_permalink = :permalink, published_at = now(), last_error = NULL, updated_at = now()
               WHERE id = :id AND status = 'NEEDS_RECONCILIATION'
                 AND split_part(:postId, '_', 1) = page_id""",
            mapOf("id" to id, "postId" to postId, "permalink" to permalink),
        ) == 1
    }

    /** Never requeue an uncertain result automatically: Meta might already have published it. */
    fun recoverInterruptedClaims(): Int = jdbc.update(
        """UPDATE facebook_page_publication SET status = 'NEEDS_RECONCILIATION',
               last_error = 'Worker stopped after claiming this post; verify the Page before retrying.',
               updated_at = now() WHERE status = 'PUBLISHING' AND updated_at < now() - interval '10 minutes'""",
        emptyMap<String, Any>(),
    )

    private val mapper = { rs: java.sql.ResultSet, _: Int ->
        FacebookPublicationJob(
            rs.getLong("id"), rs.getLong("article_id_snapshot"), rs.getString("language"),
            rs.getString("page_id"), rs.getString("status"), rs.getString("post_id"),
            rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
        )
    }
}
