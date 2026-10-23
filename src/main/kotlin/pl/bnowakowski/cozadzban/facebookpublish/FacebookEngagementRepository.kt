// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId

data class FacebookReactionTarget(val publicationId: Long, val postId: String)
data class FacebookLinkAttempt(val id: Long, val pageId: String, val language: String, val postId: String, val storyUrl: String)
data class FacebookLinkProblem(val id: Long, val articleId: Long, val language: String, val status: String,
                               val postId: String, val storyUrl: String, val lastError: String?)
data class FacebookEngagementActivity(val articleId: Long, val language: String, val postUrl: String?,
                                      val reactionCount: Long?, val reactionStatus: String?,
                                      val commentStatus: String?)

@Repository
class FacebookEngagementRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun dueReactions(pageId: String, measuredBefore: Instant, limit: Int = 100): List<FacebookReactionTarget> = jdbc.query(
        """SELECT publication.id, publication.post_id FROM facebook_page_publication publication
           WHERE publication.page_id = :pageId AND publication.status = 'PUBLISHED'
             AND publication.post_id IS NOT NULL
             AND NOT EXISTS (SELECT 1 FROM facebook_post_reaction_snapshot snapshot
                             WHERE snapshot.publication_id = publication.id AND snapshot.measured_at >= :measuredBefore)
           ORDER BY publication.published_at, publication.id LIMIT :limit""",
        mapOf("pageId" to pageId, "measuredBefore" to utcTimestamp(measuredBefore), "limit" to limit),
    ) { rs, _ -> FacebookReactionTarget(rs.getLong("id"), rs.getString("post_id")) }

    fun recordReaction(publicationId: Long, count: Long?, error: String?) {
        jdbc.update(
            """INSERT INTO facebook_post_reaction_snapshot(publication_id, reaction_count, status, error_detail)
               VALUES (:publicationId, :count, :status, :error)""",
            mapOf("publicationId" to publicationId, "count" to count,
                "status" to if (count == null) "FAILED" else "SUCCESS", "error" to error?.take(300)),
        )
    }

    /** Lock the Page configuration so competing schedulers share one monthly budget. */
    @Transactional
    fun reserveHighestRanked(page: FacebookPageConnection, now: Instant): Int {
        val locked = jdbc.query(
            """SELECT page_id, monthly_link_limit, minimum_post_age_hours, scheduler_interval_minutes, time_zone
               FROM facebook_page_connection
               WHERE language = :language AND page_id = :pageId AND enabled = true FOR UPDATE""",
            mapOf("language" to page.language, "pageId" to page.pageId),
        ) { rs, _ -> FacebookPageSettings(true, rs.getString("time_zone"), rs.getInt("monthly_link_limit"),
            rs.getInt("minimum_post_age_hours"), rs.getInt("scheduler_interval_minutes")) }.firstOrNull() ?: return 0
        val zone = ZoneId.of(locked.timeZone)
        val start = now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val end = now.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant()
        val used = jdbc.queryForObject(
            """SELECT count(*) FROM facebook_link_comment
               WHERE page_id = :pageId AND (
                 (status = 'CONFIRMED' AND confirmed_at >= :start AND confirmed_at < :end)
                 OR status IN ('SELECTED', 'ATTEMPTING', 'NEEDS_RECONCILIATION'))""",
            mapOf("pageId" to page.pageId, "start" to utcTimestamp(start), "end" to utcTimestamp(end)), Long::class.java,
        ) ?: 0L
        val hasFailure = jdbc.queryForObject(
            "SELECT count(*) FROM facebook_link_comment WHERE page_id = :pageId AND status = 'FAILED'",
            mapOf("pageId" to page.pageId), Long::class.java,
        ) ?: 0L
        if (hasFailure > 0) return 0
        val remaining = (locked.monthlyLinkLimit.toLong() - used).coerceAtLeast(0).toInt()
        if (remaining == 0) return 0
        val cutoff = now.minusSeconds(locked.minimumPostAgeHours.toLong() * 3600)
        val freshness = now.minusSeconds(locked.schedulerIntervalMinutes.toLong() * 120)
        val candidates = jdbc.query(
            """SELECT publication.id, publication.article_id_snapshot, publication.post_id
               FROM facebook_page_publication publication
               JOIN article article ON article.id = publication.article_id
               JOIN LATERAL (
                 SELECT status, reaction_count, measured_at FROM facebook_post_reaction_snapshot
                 WHERE publication_id = publication.id ORDER BY measured_at DESC, id DESC LIMIT 1
               ) reaction ON true
               WHERE publication.page_id = :pageId AND publication.status = 'PUBLISHED'
                 AND publication.published_at <= :cutoff
                 AND publication.post_id IS NOT NULL
                 AND reaction.status = 'SUCCESS' AND reaction.measured_at >= :freshness
                 AND NOT EXISTS (SELECT 1 FROM facebook_link_comment comment
                                 WHERE comment.publication_id = publication.id)
               ORDER BY reaction.reaction_count DESC, publication.published_at, publication.id
               LIMIT :remaining""",
            mapOf("pageId" to page.pageId, "cutoff" to utcTimestamp(cutoff),
                "freshness" to utcTimestamp(freshness), "remaining" to remaining),
        ) { rs, _ -> Triple(rs.getLong("id"), rs.getLong("article_id_snapshot"), rs.getString("post_id")) }
        var reserved = 0
        candidates.forEach { (publicationId, articleId, postId) ->
            reserved += jdbc.update(
                """INSERT INTO facebook_link_comment(publication_id, page_id, post_id, story_url, status)
                   VALUES (:publicationId, :pageId, :postId, :storyUrl, 'SELECTED')
                   ON CONFLICT (publication_id) DO NOTHING""",
                mapOf("publicationId" to publicationId, "pageId" to page.pageId, "postId" to postId,
                    "storyUrl" to "https://cozadzban.pl/stories/$articleId"),
            )
        }
        return reserved
    }

    @Transactional
    fun claimComment(page: FacebookPageConnection, now: Instant): FacebookLinkAttempt? {
        val current = jdbc.query(
            """SELECT monthly_link_limit, time_zone FROM facebook_page_connection
               WHERE language = :language AND page_id = :pageId AND enabled = true FOR UPDATE""",
            mapOf("language" to page.language, "pageId" to page.pageId),
        ) { rs, _ -> Pair(rs.getInt("monthly_link_limit"), rs.getString("time_zone")) }.firstOrNull() ?: return null
        val zone = ZoneId.of(current.second)
        val start = now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val end = now.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant()
        val budget = jdbc.query(
            """SELECT count(*) FILTER (WHERE (status = 'CONFIRMED' AND confirmed_at >= :start AND confirmed_at < :end)
                                       OR status IN ('ATTEMPTING', 'NEEDS_RECONCILIATION')) AS used,
                      count(*) FILTER (WHERE status = 'FAILED') AS failures
               FROM facebook_link_comment WHERE page_id = :pageId""",
            mapOf("pageId" to page.pageId, "start" to utcTimestamp(start), "end" to utcTimestamp(end)),
        ) { rs, _ -> Pair(rs.getLong("used"), rs.getLong("failures")) }.first()
        if (budget.first >= current.first || budget.second > 0) return null
        return jdbc.query(
        """WITH candidate AS (
             SELECT comment.id FROM facebook_link_comment comment
             JOIN facebook_page_connection page ON page.page_id = comment.page_id AND page.enabled = true
             JOIN facebook_page_publication publication ON publication.id = comment.publication_id
             WHERE comment.status = 'SELECTED' AND comment.page_id = :pageId
               AND publication.status = 'PUBLISHED'
               AND publication.article_id IS NOT NULL
             ORDER BY comment.created_at, comment.id FOR UPDATE OF comment SKIP LOCKED LIMIT 1
           )
           UPDATE facebook_link_comment comment
           SET status = 'ATTEMPTING', attempted_at = now(), updated_at = now()
           FROM candidate, facebook_page_publication publication
           WHERE comment.id = candidate.id AND publication.id = comment.publication_id
           RETURNING comment.id, comment.page_id, publication.language, comment.post_id, comment.story_url""",
        mapOf("pageId" to page.pageId),
    ) { rs, _ -> FacebookLinkAttempt(rs.getLong("id"), rs.getString("page_id"), rs.getString("language"),
        rs.getString("post_id"), rs.getString("story_url")) }.firstOrNull()
    }

    fun confirmComment(id: Long, commentId: String): Boolean = jdbc.update(
        """UPDATE facebook_link_comment SET status = 'CONFIRMED', comment_id = :commentId,
           confirmed_at = now(), last_error = NULL, updated_at = now()
           WHERE id = :id AND status = 'ATTEMPTING'""",
        mapOf("id" to id, "commentId" to commentId),
    ) == 1

    @Transactional
    fun markCommentOutcome(id: Long, status: String, error: String): Boolean {
        if (status == "FAILED") {
            jdbc.query(
                """SELECT page.page_id FROM facebook_page_connection page
                   JOIN facebook_link_comment comment ON comment.page_id = page.page_id
                   WHERE comment.id = :id FOR UPDATE OF page""",
                mapOf("id" to id),
            ) { rs, _ -> rs.getString(1) }
        }
        val updated = jdbc.update(
            """UPDATE facebook_link_comment SET status = :status, last_error = :error, updated_at = now()
               WHERE id = :id AND status = 'ATTEMPTING'""",
            mapOf("id" to id, "status" to status, "error" to error.take(300)),
        ) == 1
        if (updated && status == "FAILED") {
            jdbc.update(
                """DELETE FROM facebook_link_comment WHERE status = 'SELECTED'
                   AND page_id = (SELECT page_id FROM facebook_link_comment WHERE id = :id)""",
                mapOf("id" to id),
            )
        }
        return updated
    }

    fun recoverInterruptedComments(): Int = jdbc.update(
        """UPDATE facebook_link_comment SET status = 'NEEDS_RECONCILIATION',
           last_error = 'Worker stopped during comment request; check the Facebook post before retrying.',
           updated_at = now()
           WHERE status = 'ATTEMPTING' AND updated_at < now() - interval '10 minutes'""",
        emptyMap<String, Any>(),
    )

    fun recentCommentProblems(): List<FacebookLinkProblem> = jdbc.query(
        """SELECT comment.id, publication.article_id_snapshot, publication.language, comment.status,
                  comment.post_id, comment.story_url, comment.last_error
           FROM facebook_link_comment comment
           JOIN facebook_page_publication publication ON publication.id = comment.publication_id
           WHERE comment.status IN ('FAILED', 'NEEDS_RECONCILIATION')
             AND publication.article_id IS NOT NULL
           ORDER BY comment.updated_at DESC LIMIT 100""",
        emptyMap<String, Any>(),
    ) { rs, _ -> FacebookLinkProblem(rs.getLong("id"), rs.getLong("article_id_snapshot"),
        rs.getString("language"), rs.getString("status"), rs.getString("post_id"),
        rs.getString("story_url"), rs.getString("last_error")) }

    fun recentActivity(): List<FacebookEngagementActivity> = jdbc.query(
        """SELECT publication.article_id_snapshot, publication.language, publication.post_permalink,
                  reaction.reaction_count, reaction.status AS reaction_status, comment.status AS comment_status
           FROM facebook_page_publication publication
           LEFT JOIN LATERAL (
             SELECT reaction_count, status FROM facebook_post_reaction_snapshot
             WHERE publication_id = publication.id ORDER BY measured_at DESC, id DESC LIMIT 1
           ) reaction ON true
           LEFT JOIN facebook_link_comment comment ON comment.publication_id = publication.id
           WHERE publication.status = 'PUBLISHED'
           ORDER BY publication.published_at DESC, publication.id DESC LIMIT 100""",
        emptyMap<String, Any>(),
    ) { rs, _ -> FacebookEngagementActivity(rs.getLong("article_id_snapshot"), rs.getString("language"),
        rs.getString("post_permalink"), rs.getLong("reaction_count").takeUnless { rs.wasNull() },
        rs.getString("reaction_status"), rs.getString("comment_status")) }

    fun confirmReviewedComment(id: Long, commentId: String): Boolean {
        require(commentId.matches(Regex("[0-9]+_[0-9]+"))) { "Use a Facebook comment ID in objectId_commentId format" }
        return jdbc.update(
            """UPDATE facebook_link_comment SET status = 'CONFIRMED', comment_id = :commentId,
               confirmed_at = COALESCE(attempted_at, now()), last_error = NULL, updated_at = now()
               WHERE id = :id AND status = 'NEEDS_RECONCILIATION'""",
            mapOf("id" to id, "commentId" to commentId),
        ) == 1
    }

    fun retryReviewedAbsent(id: Long): Boolean = jdbc.update(
        """UPDATE facebook_link_comment comment SET status = 'SELECTED', last_error = NULL, updated_at = now()
           FROM facebook_page_publication publication, facebook_page_connection page
           WHERE comment.id = :id AND comment.status = 'NEEDS_RECONCILIATION'
             AND publication.id = comment.publication_id AND publication.status = 'PUBLISHED'
             AND publication.article_id IS NOT NULL
             AND page.page_id = comment.page_id AND page.enabled = true""",
        mapOf("id" to id),
    ) == 1

    @Transactional
    fun retryFailedComment(id: Long, page: FacebookPageConnection, now: Instant): Boolean {
        val enabled = jdbc.query(
            """SELECT page_id, monthly_link_limit, time_zone FROM facebook_page_connection
               WHERE language = :language AND page_id = :pageId AND enabled = true FOR UPDATE""",
            mapOf("language" to page.language, "pageId" to page.pageId),
        ) { rs, _ -> Pair(rs.getInt("monthly_link_limit"), rs.getString("time_zone")) }.firstOrNull() ?: return false
        val zone = ZoneId.of(enabled.second)
        val start = now.atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant()
        val end = now.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(zone).toInstant()
        val used = jdbc.queryForObject(
            """SELECT count(*) FROM facebook_link_comment
               WHERE page_id = :pageId AND (
                 (status = 'CONFIRMED' AND confirmed_at >= :start AND confirmed_at < :end)
                 OR status IN ('SELECTED', 'ATTEMPTING', 'NEEDS_RECONCILIATION'))""",
            mapOf("pageId" to page.pageId, "start" to utcTimestamp(start), "end" to utcTimestamp(end)), Long::class.java,
        ) ?: 0L
        if (used >= enabled.first) return false
        return jdbc.update(
            """UPDATE facebook_link_comment comment SET status = 'SELECTED', last_error = NULL,
               updated_at = now() FROM facebook_page_publication publication
               WHERE comment.id = :id AND comment.status = 'FAILED' AND comment.page_id = :pageId
                 AND publication.id = comment.publication_id AND publication.status = 'PUBLISHED'
                 AND publication.article_id IS NOT NULL""",
            mapOf("id" to id, "pageId" to page.pageId),
        ) == 1
    }
}
