// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class FacebookSyncJob(val id: Long, val publicationId: Long, val articleId: Long?, val pageId: String,
                           val language: String, val postId: String, val photoId: String?, val action: String)
data class FacebookSyncProblem(val id: Long, val articleId: Long, val language: String, val action: String,
                               val status: String, val postId: String?, val lastError: String?)

@Repository
class FacebookPageSyncRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun queueUpdate(articleId: Long, replacementRequired: Boolean) {
        jdbc.update(
            """INSERT INTO facebook_page_sync(publication_id, action, status, replacement_required)
               SELECT id, 'UPDATE', 'PENDING', :replacementRequired
               FROM facebook_page_publication WHERE article_id = :articleId
                 AND status IN ('PUBLISHING', 'PUBLISHED', 'NEEDS_RECONCILIATION')
                 AND NOT EXISTS (SELECT 1 FROM facebook_page_sync deletion
                                 WHERE deletion.publication_id = facebook_page_publication.id
                                   AND deletion.action = 'DELETE' AND deletion.status <> 'CANCELLED')
               ON CONFLICT (publication_id, action) DO UPDATE
               SET requested_at = now(), last_error = NULL,
                   replacement_required = facebook_page_sync.replacement_required OR EXCLUDED.replacement_required,
                   dirty = facebook_page_sync.dirty OR facebook_page_sync.status IN ('ATTEMPTING', 'NEEDS_RECONCILIATION'),
                   status = CASE WHEN facebook_page_sync.status IN ('ATTEMPTING', 'NEEDS_RECONCILIATION')
                                 THEN facebook_page_sync.status ELSE 'PENDING' END""",
            mapOf("articleId" to articleId, "replacementRequired" to replacementRequired),
        )
    }

    /** Called before article deletion, within the same transaction. */
    fun queueDelete(articleId: Long) {
        jdbc.update(
            """UPDATE facebook_page_publication SET status = 'DELETED', updated_at = now()
               WHERE article_id = :articleId AND status IN ('PENDING', 'UNCONFIGURED', 'FAILED')""",
            mapOf("articleId" to articleId),
        )
        jdbc.update(
            """UPDATE facebook_page_sync sync SET status = 'CANCELLED', updated_at = now()
               FROM facebook_page_publication publication
               WHERE sync.publication_id = publication.id AND publication.article_id = :articleId
                 AND sync.action = 'UPDATE' AND sync.status <> 'ATTEMPTING'""",
            mapOf("articleId" to articleId),
        )
        jdbc.update(
            """INSERT INTO facebook_page_sync(publication_id, action, status)
               SELECT id, 'DELETE', 'PENDING' FROM facebook_page_publication
               WHERE article_id = :articleId
                 AND status IN ('PUBLISHED', 'PUBLISHING', 'NEEDS_RECONCILIATION')
               ON CONFLICT (publication_id, action) DO NOTHING""",
            mapOf("articleId" to articleId),
        )
        jdbc.update(
            """DELETE FROM facebook_link_comment comment
               USING facebook_page_publication publication
               WHERE comment.publication_id = publication.id AND publication.article_id = :articleId
                 AND comment.status = 'SELECTED'""",
            mapOf("articleId" to articleId),
        )
    }

    @Transactional
    fun claim(): FacebookSyncJob? = jdbc.query(
        """WITH candidate AS (
             SELECT sync.id FROM facebook_page_sync sync
             JOIN facebook_page_publication publication ON publication.id = sync.publication_id
             JOIN facebook_page_connection page ON page.page_id = publication.page_id AND page.enabled = true
             WHERE sync.status = 'PENDING' AND publication.post_id IS NOT NULL
               AND publication.status = 'PUBLISHED'
               AND (sync.action = 'DELETE' OR publication.article_id IS NOT NULL)
               AND NOT EXISTS (SELECT 1 FROM facebook_page_sync other
                               WHERE other.publication_id = sync.publication_id AND other.id <> sync.id
                                 AND other.status = 'ATTEMPTING')
               AND NOT (sync.action = 'UPDATE' AND EXISTS (
                   SELECT 1 FROM facebook_page_sync deletion WHERE deletion.publication_id = sync.publication_id
                   AND deletion.action = 'DELETE' AND deletion.status <> 'CANCELLED'))
             ORDER BY CASE WHEN sync.action = 'DELETE' THEN 0 ELSE 1 END, sync.requested_at, sync.id
             FOR UPDATE OF sync SKIP LOCKED LIMIT 1
           )
           UPDATE facebook_page_sync sync SET status = 'ATTEMPTING', attempt_count = sync.attempt_count + 1,
               attempted_at = now(), updated_at = now()
           FROM candidate, facebook_page_publication publication
           WHERE sync.id = candidate.id AND publication.id = sync.publication_id
           RETURNING sync.id, sync.publication_id, publication.article_id, publication.page_id,
                     publication.language, publication.post_id, publication.photo_id, sync.action""",
        emptyMap<String, Any>(),
    ) { rs, _ -> FacebookSyncJob(rs.getLong("id"), rs.getLong("publication_id"),
        rs.getLong("article_id").takeUnless { rs.wasNull() }, rs.getString("page_id"),
        rs.getString("language"), rs.getString("post_id"), rs.getString("photo_id"), rs.getString("action")) }.firstOrNull()

    fun savePhotoId(publicationId: Long, photoId: String) {
        jdbc.update(
            """UPDATE facebook_page_publication SET photo_id = :photoId, updated_at = now()
               WHERE id = :publicationId AND photo_id IS NULL""",
            mapOf("publicationId" to publicationId, "photoId" to photoId),
        )
    }

    @Transactional
    fun confirm(id: Long, action: String): Boolean {
        val updated = jdbc.update(
            """UPDATE facebook_page_sync SET
                 status = CASE WHEN action = 'UPDATE' AND dirty THEN 'PENDING'
                               WHEN action = 'UPDATE' AND replacement_required THEN 'REPLACEMENT_REQUIRED'
                               ELSE 'CONFIRMED' END,
                 dirty = false, completed_at = now(),
                 last_error = CASE WHEN action = 'UPDATE' AND replacement_required AND NOT dirty
                                   THEN 'The image or Page language changed; manual post replacement is required.'
                                   ELSE NULL END,
                 updated_at = now()
               WHERE id = :id AND status = 'ATTEMPTING' AND action = :action""",
            mapOf("id" to id, "action" to action),
        ) == 1
        if (updated && action == "DELETE") {
            jdbc.update(
                """UPDATE facebook_page_publication SET status = 'DELETED', updated_at = now()
                   WHERE id = (SELECT publication_id FROM facebook_page_sync WHERE id = :id)""",
                mapOf("id" to id),
            )
        }
        return updated
    }

    fun fail(id: Long, uncertain: Boolean, error: String): Boolean = jdbc.update(
        """UPDATE facebook_page_sync SET status = :status, last_error = :error, updated_at = now()
           WHERE id = :id AND status = 'ATTEMPTING'""",
        mapOf("id" to id, "status" to if (uncertain) "NEEDS_RECONCILIATION" else "FAILED",
            "error" to error.take(300)),
    ) == 1

    fun recoverInterrupted(): Int = jdbc.update(
        """UPDATE facebook_page_sync SET status = 'NEEDS_RECONCILIATION',
           last_error = 'Worker stopped during the Meta request; verify the Facebook post before retrying.',
           updated_at = now() WHERE status = 'ATTEMPTING' AND updated_at < now() - interval '10 minutes'""",
        emptyMap<String, Any>(),
    )

    fun recentProblems(): List<FacebookSyncProblem> = jdbc.query(
        """SELECT sync.id, publication.article_id_snapshot, publication.language, sync.action, sync.status,
                  publication.post_id, sync.last_error
           FROM facebook_page_sync sync JOIN facebook_page_publication publication ON publication.id = sync.publication_id
           WHERE sync.status IN ('FAILED', 'NEEDS_RECONCILIATION', 'REPLACEMENT_REQUIRED')
           ORDER BY sync.updated_at DESC LIMIT 100""",
        emptyMap<String, Any>(),
    ) { rs, _ -> FacebookSyncProblem(rs.getLong("id"), rs.getLong("article_id_snapshot"),
        rs.getString("language"), rs.getString("action"), rs.getString("status"),
        rs.getString("post_id"), rs.getString("last_error")) }

    fun retryReviewed(id: Long): Boolean = jdbc.update(
        """UPDATE facebook_page_sync SET status = 'PENDING', last_error = NULL, updated_at = now()
           WHERE id = :id AND status IN ('FAILED', 'NEEDS_RECONCILIATION')""",
        mapOf("id" to id),
    ) == 1

    @Transactional
    fun confirmReviewed(id: Long): Boolean {
        val action = jdbc.query(
            """SELECT action FROM facebook_page_sync WHERE id = :id
               AND (status = 'NEEDS_RECONCILIATION' OR (action = 'DELETE' AND status = 'FAILED'))""",
            mapOf("id" to id),
        ) { rs, _ -> rs.getString(1) }.firstOrNull() ?: return false
        val updated = jdbc.update(
            """UPDATE facebook_page_sync SET
                 status = CASE WHEN action = 'UPDATE' AND dirty THEN 'PENDING'
                               WHEN action = 'UPDATE' AND replacement_required THEN 'REPLACEMENT_REQUIRED'
                               ELSE 'CONFIRMED' END,
                 dirty = false, completed_at = now(), last_error = NULL, updated_at = now()
               WHERE id = :id
                 AND (status = 'NEEDS_RECONCILIATION' OR (action = 'DELETE' AND status = 'FAILED'))""",
            mapOf("id" to id),
        ) == 1
        if (updated && action == "DELETE") {
            jdbc.update(
                """UPDATE facebook_page_publication SET status = 'DELETED', updated_at = now()
                   WHERE id = (SELECT publication_id FROM facebook_page_sync WHERE id = :id)""",
                mapOf("id" to id),
            )
        }
        return updated
    }
}
