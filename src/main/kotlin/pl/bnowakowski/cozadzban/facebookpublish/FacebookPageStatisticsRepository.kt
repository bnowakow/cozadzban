// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import pl.bnowakowski.cozadzban.ui.WarsawTime

data class FacebookLinkCounts(val total: Long, val thisMonth: Long, val last30Days: Long)

data class FacebookPageStatistics(
    val language: String,
    val pageName: String,
    val publishedPosts: Long,
    val storyViews: Long,
    val storyViews7Days: Long,
    val latestReactionTotal: Long,
    val confirmedLinksThisMonth: Long,
    val monthlyLinkLimit: Int,
)

data class FacebookArticleStatistics(
    val articleId: Long,
    val language: String,
    val title: String?,
    val sourceUrl: String,
    val storyViews: Long,
    val storyViews7Days: Long,
    val reactionCount: Long?,
    val reactionMeasuredAt: Instant?,
    val sourceReactionCount: Long?,
    val sourceReactionMeasuredAt: Instant?,
    val sourceReactionStatus: String?,
    val sourceReactionError: String?,
    val sourceReactionNextAttemptAt: Instant?,
    val publicationStatus: String?,
    val postUrl: String?,
    val linkCommentStatus: String?,
)

@Repository
class FacebookPageStatisticsRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val pages: FacebookPageConnectionRepository,
) {
    fun linkCounts(pageId: String, now: Instant = Instant.now()): FacebookLinkCounts {
        val monthStart = now.atZone(WarsawTime.ZONE).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(WarsawTime.ZONE).toInstant()
        return jdbc.query(
            """SELECT COUNT(*) AS total,
                      COUNT(*) FILTER (WHERE confirmed_at >= :monthStart) AS this_month,
                      COUNT(*) FILTER (WHERE confirmed_at >= :last30DaysStart) AS last_30_days
               FROM facebook_link_comment
               WHERE page_id = :pageId AND status = 'CONFIRMED' AND confirmed_at <= :now""",
            mapOf("pageId" to pageId, "monthStart" to utcTimestamp(monthStart),
                "last30DaysStart" to utcTimestamp(now.minusSeconds(30L * 24 * 60 * 60)),
                "now" to utcTimestamp(now)),
        ) { rs, _ -> FacebookLinkCounts(rs.getLong("total"), rs.getLong("this_month"),
            rs.getLong("last_30_days")) }.first()
    }

    fun pageStatistics(now: Instant = Instant.now()): List<FacebookPageStatistics> {
        val sevenDays = LocalDate.ofInstant(now, WarsawTime.ZONE).minusDays(6)
        return pages.all().map { page ->
            val zone = WarsawTime.ZONE
            val month = now.atZone(zone).toLocalDate().withDayOfMonth(1)
            val start = month.atStartOfDay(zone).toInstant()
            val end = month.plusMonths(1).atStartOfDay(zone).toInstant()
            jdbc.query(
                """WITH views AS (
                     SELECT article_id, SUM(view_count) AS total_views,
                            SUM(view_count) FILTER (WHERE view_date >= :sevenDays) AS views_7d
                     FROM article_story_view_daily GROUP BY article_id
                   )
                   SELECT COUNT(publication.id) FILTER (WHERE publication.status = 'PUBLISHED') AS posts,
                          COALESCE(SUM(views.total_views) FILTER (WHERE publication.status = 'PUBLISHED'), 0) AS story_views,
                          COALESCE(SUM(views.views_7d) FILTER (WHERE publication.status = 'PUBLISHED'), 0) AS views_7d,
                          COALESCE(SUM(reaction.reaction_count) FILTER (WHERE publication.status = 'PUBLISHED'), 0) AS reactions,
                          (SELECT COUNT(*) FROM facebook_link_comment comment
                           WHERE comment.page_id = :pageId AND comment.status = 'CONFIRMED'
                             AND comment.confirmed_at >= :monthStart AND comment.confirmed_at < :monthEnd) AS links_this_month
                   FROM facebook_page_publication publication
                   LEFT JOIN views ON views.article_id = publication.article_id
                   LEFT JOIN LATERAL (
                     SELECT reaction_count FROM facebook_post_reaction_snapshot
                     WHERE publication_id = publication.id AND status = 'SUCCESS'
                     ORDER BY measured_at DESC, id DESC LIMIT 1
                   ) reaction ON true
                   WHERE publication.page_id = :pageId""",
                mapOf("pageId" to page.pageId, "sevenDays" to sevenDays,
                    "monthStart" to utcTimestamp(start), "monthEnd" to utcTimestamp(end)),
            ) { rs, _ -> FacebookPageStatistics(page.language, page.pageName, rs.getLong("posts"),
                rs.getLong("story_views"), rs.getLong("views_7d"), rs.getLong("reactions"),
                rs.getLong("links_this_month"), page.monthlyLinkLimit) }.first()
        }
    }

    fun articleCount(language: String): Long = jdbc.queryForObject(
        "SELECT COUNT(*) FROM article WHERE :language = '' OR language = :language",
        mapOf("language" to language), Long::class.java,
    ) ?: 0L

    fun articleStatistics(language: String, sort: String, limit: Int, offset: Int,
                          now: Instant = Instant.now()): List<FacebookArticleStatistics> {
        require(sort in setOf("VIEWS", "REACTIONS", "SOURCE_REACTIONS", "NEWEST")) { "Unsupported statistics sort" }
        require(limit in 1..100 && offset >= 0) { "Invalid statistics page" }
        val sevenDays = LocalDate.ofInstant(now, WarsawTime.ZONE).minusDays(6)
        return jdbc.query(
            """WITH views AS (
                 SELECT article_id, SUM(view_count) AS total_views,
                        SUM(view_count) FILTER (WHERE view_date >= :sevenDays) AS views_7d
                 FROM article_story_view_daily GROUP BY article_id
               )
               SELECT article.id, article.language, article.title, article.url AS source_url,
                      COALESCE(views.total_views, 0) AS story_views,
                      COALESCE(views.views_7d, 0) AS views_7d,
                      reaction.reaction_count, reaction.measured_at,
                      source.reaction_count AS source_reaction_count,
                      source.measured_at AS source_measured_at,
                      source.status AS source_status,
                      source.error_detail AS source_error,
                      source.next_attempt_at AS source_next_attempt_at,
                      publication.status AS publication_status, publication.post_permalink,
                      comment.status AS comment_status
               FROM article
               LEFT JOIN views ON views.article_id = article.id
               LEFT JOIN facebook_page_publication publication ON publication.article_id = article.id
               LEFT JOIN LATERAL (
                 SELECT reaction_count, measured_at FROM facebook_post_reaction_snapshot
                 WHERE publication_id = publication.id AND status = 'SUCCESS'
                 ORDER BY measured_at DESC, id DESC LIMIT 1
               ) reaction ON true
               LEFT JOIN facebook_source_post_reaction source
                 ON source.article_id = article.id AND source.source_url = article.url
               LEFT JOIN facebook_link_comment comment ON comment.publication_id = publication.id
               WHERE :language = '' OR article.language = :language
               ORDER BY CASE WHEN :sort = 'VIEWS' THEN COALESCE(views.total_views, 0) END DESC,
                        CASE WHEN :sort = 'REACTIONS' THEN COALESCE(reaction.reaction_count, 0) END DESC,
                        CASE WHEN :sort = 'SOURCE_REACTIONS' THEN COALESCE(source.reaction_count, 0) END DESC,
                        CASE WHEN :sort = 'NEWEST' THEN article.id END DESC,
                        article.id DESC
               LIMIT :limit OFFSET :offset""",
            mapOf("language" to language, "sort" to sort, "sevenDays" to sevenDays,
                "limit" to limit, "offset" to offset),
        ) { rs, _ -> FacebookArticleStatistics(rs.getLong("id"), rs.getString("language"), rs.getString("title"),
            rs.getString("source_url"),
            rs.getLong("story_views"), rs.getLong("views_7d"),
            rs.getLong("reaction_count").takeUnless { rs.wasNull() },
            rs.getTimestamp("measured_at")?.toInstant(),
            rs.getLong("source_reaction_count").takeUnless { rs.wasNull() },
            rs.getTimestamp("source_measured_at")?.toInstant(),
            rs.getString("source_status"), rs.getString("source_error"),
            rs.getTimestamp("source_next_attempt_at")?.toInstant(),
            rs.getString("publication_status"),
            rs.getString("post_permalink"), rs.getString("comment_status")) }
    }
}
