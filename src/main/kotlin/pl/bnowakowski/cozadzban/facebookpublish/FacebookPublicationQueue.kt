// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** How long a Page post waits for its generated illustration before using the placeholder. */
internal val DEFAULT_IMAGE_WAIT: Duration = Duration.ofHours(24)

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

data class FacebookPublicationRetryProgress(
    val total: Int,
    val queued: Int,
    val published: Int,
    val failed: Int,
    val needsReview: Int,
) {
    val completed: Int get() = total - queued
}

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
    /**
     * Claims the oldest pending publication. With [waitForImages], a post whose illustration is still
     * pending or has failed is held back, so it can go out with a generated image (failed jobs can be
     * retried from the admin thumbnails page). After [imageWaitMax] from its creation it is
     * published anyway, and the image provider falls back to the branded placeholder.
     */
    fun claim(waitForImages: Boolean = false, imageWaitMax: Duration = DEFAULT_IMAGE_WAIT): FacebookPublicationJob? = jdbc.query(
        """WITH candidate AS (
               SELECT publication.id FROM facebook_page_publication publication
               WHERE publication.status = 'PENDING' AND publication.retry_batch_id IS NULL
                 AND (:waitForImages = false
                      OR publication.created_at <= now() - :waitSeconds * interval '1 second'
                      OR NOT EXISTS (
                     SELECT 1 FROM facebook_article_image_generation image
                     WHERE image.article_id = publication.article_id_snapshot
                       AND image.status IN ('MISSING_THUMBNAIL', 'GENERATING', 'FAILED')
                 ))
               ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
           )
           UPDATE facebook_page_publication p SET status = 'PUBLISHING', attempt_count = attempt_count + 1,
               updated_at = now() FROM candidate WHERE p.id = candidate.id
           RETURNING p.id, p.article_id_snapshot, p.language, p.page_id, p.status, p.post_id, p.last_error, p.created_at""",
        mapOf("waitForImages" to waitForImages, "waitSeconds" to imageWaitMax.seconds.coerceAtLeast(0)), mapper,
    ).firstOrNull()

    /** One batch publication may start every two minutes, even when several workers are running. */
    @Transactional
    fun claimBatch(waitForImages: Boolean = false, imageWaitMax: Duration = DEFAULT_IMAGE_WAIT): FacebookPublicationJob? = jdbc.query(
        """WITH ready_batch AS (
               SELECT id FROM facebook_publication_retry_batch batch
               WHERE next_claim_at <= now()
                 AND EXISTS (SELECT 1 FROM facebook_page_publication publication
                             WHERE publication.retry_batch_id = batch.id AND publication.status = 'PENDING')
                 AND NOT EXISTS (SELECT 1 FROM facebook_page_publication publication
                                 WHERE publication.retry_batch_id = batch.id AND publication.status = 'PUBLISHING')
               ORDER BY id DESC LIMIT 1 FOR UPDATE SKIP LOCKED
           ), candidate AS (
               SELECT publication.id FROM facebook_page_publication publication
               JOIN ready_batch batch ON batch.id = publication.retry_batch_id
               WHERE publication.status = 'PENDING'
                 AND (:waitForImages = false
                      OR publication.created_at <= now() - :waitSeconds * interval '1 second'
                      OR NOT EXISTS (
                          SELECT 1 FROM facebook_article_image_generation image
                          WHERE image.article_id = publication.article_id_snapshot
                            AND image.status IN ('MISSING_THUMBNAIL', 'GENERATING', 'FAILED')
                      ))
               ORDER BY publication.created_at, publication.id
               FOR UPDATE OF publication SKIP LOCKED LIMIT 1
           ), pace AS (
               UPDATE facebook_publication_retry_batch batch
               SET next_claim_at = now() + interval '2 minutes'
               FROM ready_batch, candidate
               WHERE batch.id = ready_batch.id
               RETURNING batch.id
           )
           UPDATE facebook_page_publication publication
           SET status = 'PUBLISHING', attempt_count = attempt_count + 1, updated_at = now()
           FROM candidate, pace
           WHERE publication.id = candidate.id
           RETURNING publication.id, publication.article_id_snapshot, publication.language,
                     publication.page_id, publication.status, publication.post_id,
                     publication.last_error, publication.created_at""",
        mapOf("waitForImages" to waitForImages, "waitSeconds" to imageWaitMax.seconds.coerceAtLeast(0)), mapper,
    ).firstOrNull()

    /** A source URL failed before Meta created a post; wait for image generation. */
    fun deferForImage(id: Long) {
        jdbc.update(
            """UPDATE facebook_page_publication SET status = 'PENDING', updated_at = now()
               WHERE id = :id AND status = 'PUBLISHING'""",
            mapOf("id" to id),
        )
    }

    @Transactional
    fun markPublished(id: Long, postId: String, permalink: String, imageSource: String, photoId: String?) {
        jdbc.update(
            """UPDATE facebook_page_publication SET status = 'PUBLISHED', post_id = :postId,
               post_permalink = :permalink, image_source = :imageSource, photo_id = :photoId, last_error = NULL,
               published_at = now(), updated_at = now() WHERE id = :id AND status = 'PUBLISHING'""",
            mapOf("id" to id, "postId" to postId, "permalink" to permalink, "imageSource" to imageSource,
                "photoId" to photoId),
        )
        paceBatchAfterOutcome(id)
    }

    @Transactional
    fun markFailed(id: Long, error: String, imageSource: String? = null) = markOutcome(id, "FAILED", error, imageSource)
    @Transactional
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
        paceBatchAfterOutcome(id)
    }

    private fun paceBatchAfterOutcome(id: Long) {
        jdbc.update(
            """UPDATE facebook_publication_retry_batch batch
               SET next_claim_at = GREATEST(batch.next_claim_at, now() + interval '2 minutes')
               FROM facebook_page_publication publication
               WHERE publication.id = :id AND publication.retry_batch_id = batch.id""",
            mapOf("id" to id),
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

    fun retryableCount(): Int = jdbc.queryForObject(
        """SELECT count(*) FROM facebook_page_publication publication
           WHERE publication.article_id IS NOT NULL
             AND publication.status IN ('FAILED', 'UNCONFIGURED')
             AND EXISTS (SELECT 1 FROM facebook_page_connection page
                         WHERE page.language = publication.language AND page.enabled = true
                           AND (publication.status = 'UNCONFIGURED' OR page.page_id = publication.page_id))""",
        emptyMap<String, Any>(), Int::class.java,
    ) ?: 0

    /** Never bulk retry uncertain results: a Facebook post may already exist. */
    @Transactional
    fun retryAll(): FacebookPublicationRetryProgress? {
        jdbc.query("SELECT pg_advisory_xact_lock(521206)", emptyMap<String, Any>()) { rs, _ -> rs.getObject(1) }
        val current = latestRetryProgress()
        if (current != null && current.queued > 0) return current
        val count = retryableCount()
        if (count == 0) return current
        val batchId = jdbc.queryForObject(
            """INSERT INTO facebook_publication_retry_batch(total, next_claim_at)
               SELECT :total, GREATEST(now(), COALESCE(
                   (SELECT next_claim_at FROM facebook_publication_retry_batch ORDER BY id DESC LIMIT 1), now()))
               RETURNING id""",
            mapOf("total" to count), Long::class.java,
        ) ?: return current
        jdbc.update(
            """UPDATE facebook_page_publication publication
               SET status = 'PENDING', page_id = page.page_id, last_error = NULL,
                   retry_batch_id = :batchId, updated_at = now()
               FROM facebook_page_connection page
               WHERE publication.article_id IS NOT NULL
                 AND publication.status IN ('FAILED', 'UNCONFIGURED')
                 AND page.language = publication.language AND page.enabled = true
                 AND (publication.status = 'UNCONFIGURED' OR page.page_id = publication.page_id)""",
            mapOf("batchId" to batchId),
        )
        return latestRetryProgress()
    }

    fun latestRetryProgress(): FacebookPublicationRetryProgress? = jdbc.query(
        """SELECT batch.total,
                  count(*) FILTER (WHERE publication.status IN ('PENDING', 'PUBLISHING')) AS queued,
                  count(*) FILTER (WHERE publication.status = 'PUBLISHED') AS published,
                  count(*) FILTER (WHERE publication.status = 'FAILED') AS failed,
                  count(*) FILTER (WHERE publication.status = 'NEEDS_RECONCILIATION') AS needs_review
           FROM facebook_publication_retry_batch batch
           LEFT JOIN facebook_page_publication publication ON publication.retry_batch_id = batch.id
           WHERE batch.id = (SELECT max(id) FROM facebook_publication_retry_batch)
           GROUP BY batch.id, batch.total""",
        emptyMap<String, Any>(),
    ) { rs, _ ->
        FacebookPublicationRetryProgress(
            rs.getInt("total"), rs.getInt("queued"), rs.getInt("published"),
            rs.getInt("failed"), rs.getInt("needs_review"),
        )
    }.firstOrNull()

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
    @Transactional
    fun retryAfterManualReview(id: Long): Boolean {
        val updated = jdbc.update(
            """UPDATE facebook_page_publication SET
                 status = CASE WHEN article_id IS NULL THEN 'DELETED' ELSE 'PENDING' END,
                 post_id = NULL, last_error = NULL, updated_at = now()
               WHERE id = :id AND status = 'NEEDS_RECONCILIATION'""",
            mapOf("id" to id),
        ) == 1
        if (updated) {
            jdbc.update(
                """UPDATE facebook_page_sync SET status = 'CANCELLED', updated_at = now()
                   WHERE publication_id = :id AND action = 'DELETE' AND status = 'PENDING'
                     AND EXISTS (SELECT 1 FROM facebook_page_publication
                                 WHERE id = :id AND status = 'DELETED')""",
                mapOf("id" to id),
            )
        }
        return updated
    }

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
