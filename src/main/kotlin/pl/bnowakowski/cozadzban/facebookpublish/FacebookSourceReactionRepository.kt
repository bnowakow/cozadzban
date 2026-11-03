// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

data class FacebookSourceReactionTarget(
    val articleId: Long,
    val sourceUrl: String,
    val publishedAt: Instant?,
    val graphPostId: String?,
)

enum class FacebookSourceReactionStatus { SUCCESS, FAILED, UNSUPPORTED }

@Repository
class FacebookSourceReactionRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun due(now: Instant, limit: Int): List<FacebookSourceReactionTarget> = jdbc.query(
        """SELECT article.id, article.url, article.published_at, source.graph_post_id
           FROM article
           LEFT JOIN facebook_source_post_reaction source
             ON source.article_id = article.id AND source.source_url = article.url
           WHERE article.url ILIKE :facebookUrl
             AND (source.article_id IS NULL OR source.next_attempt_at <= :now)
           ORDER BY source.last_attempt_at NULLS FIRST, article.id DESC
           LIMIT :limit""",
        mapOf("facebookUrl" to "%facebook.com/%", "now" to utcTimestamp(now),
            "limit" to limit.coerceIn(1, 20)),
    ) { rs, _ -> FacebookSourceReactionTarget(
        articleId = rs.getLong("id"),
        sourceUrl = rs.getString("url"),
        publishedAt = rs.getTimestamp("published_at")?.toInstant(),
        graphPostId = rs.getString("graph_post_id"),
    ) }

    /** Makes a failed check eligible for the background worker without losing its last result. */
    fun retryFailed(articleId: Long, now: Instant = Instant.now()): Boolean = jdbc.update(
        """UPDATE facebook_source_post_reaction source
           SET next_attempt_at = :now
           FROM article
           WHERE article.id = source.article_id AND article.url = source.source_url
             AND source.article_id = :articleId AND source.status = 'FAILED'
             AND source.next_attempt_at > :now""",
        mapOf("articleId" to articleId, "now" to utcTimestamp(now)),
    ) == 1

    /** Queues every waiting failure, including rows outside the current statistics page. */
    fun retryAllFailed(now: Instant = Instant.now()): Int = jdbc.update(
        """UPDATE facebook_source_post_reaction source
           SET next_attempt_at = :now
           FROM article
           WHERE article.id = source.article_id AND article.url = source.source_url
             AND source.status = 'FAILED' AND source.next_attempt_at > :now""",
        mapOf("now" to utcTimestamp(now)),
    )

    fun record(
        target: FacebookSourceReactionTarget,
        status: FacebookSourceReactionStatus,
        count: Long?,
        graphPostId: String?,
        error: String?,
        now: Instant,
        nextAttemptAt: Instant,
    ): Boolean {
        require((status == FacebookSourceReactionStatus.SUCCESS) == (count != null))
        require(count == null || count >= 0)
        return jdbc.update(
            """INSERT INTO facebook_source_post_reaction
                 (article_id, source_url, graph_post_id, reaction_count, status, error_detail,
                  measured_at, last_attempt_at, next_attempt_at)
               SELECT article.id, :sourceUrl, :graphPostId, :reactionCount, :status, :error,
                      :measuredAt, :now, :nextAttemptAt
               FROM article WHERE article.id = :articleId AND article.url = :sourceUrl
               ON CONFLICT (article_id) DO UPDATE SET
                 source_url = EXCLUDED.source_url,
                 graph_post_id = CASE
                   WHEN facebook_source_post_reaction.source_url = EXCLUDED.source_url
                     THEN COALESCE(EXCLUDED.graph_post_id, facebook_source_post_reaction.graph_post_id)
                   ELSE EXCLUDED.graph_post_id END,
                 reaction_count = CASE
                   WHEN EXCLUDED.status = 'FAILED'
                     AND facebook_source_post_reaction.source_url = EXCLUDED.source_url
                     THEN facebook_source_post_reaction.reaction_count
                   ELSE EXCLUDED.reaction_count END,
                 measured_at = CASE
                   WHEN EXCLUDED.status = 'FAILED'
                     AND facebook_source_post_reaction.source_url = EXCLUDED.source_url
                     THEN facebook_source_post_reaction.measured_at
                   ELSE EXCLUDED.measured_at END,
                 status = EXCLUDED.status,
                 error_detail = EXCLUDED.error_detail,
                 last_attempt_at = EXCLUDED.last_attempt_at,
                 next_attempt_at = EXCLUDED.next_attempt_at""",
            mapOf(
                "articleId" to target.articleId,
                "sourceUrl" to target.sourceUrl,
                "graphPostId" to graphPostId,
                "reactionCount" to count,
                "status" to status.name,
                "error" to error?.take(300),
                "measuredAt" to if (status == FacebookSourceReactionStatus.SUCCESS) utcTimestamp(now) else null,
                "now" to utcTimestamp(now),
                "nextAttemptAt" to utcTimestamp(nextAttemptAt),
            ),
        ) == 1
    }
}
