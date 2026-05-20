// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

interface ArticleContentRepository : CrudRepository<ArticleContent, Long>, ArticleContentRepositoryCustom

interface ArticleContentRepositoryCustom {
    fun existsByArticleId(articleId: Long): Boolean
    fun deleteByArticleId(articleId: Long)
    fun insert(content: ArticleContent)
    fun findPage(
        page: Int,
        size: Int,
        articleId: Long? = null,
        articleUrl: String? = null,
        publishedFrom: Instant? = null,
        publishedTo: Instant? = null,
        createdFrom: Instant? = null,
        createdTo: Instant? = null,
    ): List<ArticleContent>
    fun countFiltered(
        articleId: Long? = null,
        articleUrl: String? = null,
        publishedFrom: Instant? = null,
        publishedTo: Instant? = null,
        createdFrom: Instant? = null,
        createdTo: Instant? = null,
    ): Long
}

@Repository
class ArticleContentRepositoryCustomImpl(
    private val jdbc: NamedParameterJdbcTemplate,
) : ArticleContentRepositoryCustom {

    override fun existsByArticleId(articleId: Long): Boolean =
        (jdbc.queryForObject(
            "SELECT COUNT(*) FROM article_content WHERE article_id = :id",
            mapOf("id" to articleId),
            Long::class.java,
        ) ?: 0L) > 0L

    override fun deleteByArticleId(articleId: Long) {
        jdbc.update(
            "DELETE FROM article_content WHERE article_id = :id",
            mapOf("id" to articleId),
        )
    }

    override fun insert(content: ArticleContent) {
        jdbc.update(
            """
                INSERT INTO article_content (article_id, content, truncated, captured_at)
                VALUES (:articleId, :content, :truncated, :capturedAt)
            """.trimIndent(),
            mapOf(
                "articleId" to content.articleId,
                "content" to content.content,
                "truncated" to content.truncated,
                "capturedAt" to Timestamp.from(content.capturedAt ?: java.time.Instant.now()),
            ),
        )
    }

    override fun findPage(
        page: Int,
        size: Int,
        articleId: Long?,
        articleUrl: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): List<ArticleContent> {
        val (whereClause, params) = buildWhereClause(
            articleId, articleUrl, publishedFrom, publishedTo, createdFrom, createdTo,
        )
        params["limit"] = size
        params["offset"] = page * size

        val sql = """
            SELECT c.article_id, c.content, c.truncated, c.captured_at
              FROM article_content c
              JOIN article a ON a.id = c.article_id
             $whereClause
             ORDER BY c.captured_at DESC NULLS LAST, c.article_id DESC
             LIMIT :limit OFFSET :offset
        """.trimIndent()
        return jdbc.query(sql, params, ARTICLE_CONTENT_ROW_MAPPER)
    }

    override fun countFiltered(
        articleId: Long?,
        articleUrl: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): Long {
        val (whereClause, params) = buildWhereClause(
            articleId, articleUrl, publishedFrom, publishedTo, createdFrom, createdTo,
        )
        val sql = """
            SELECT COUNT(*)
              FROM article_content c
              JOIN article a ON a.id = c.article_id
             $whereClause
        """.trimIndent()
        return jdbc.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    private fun buildWhereClause(
        articleId: Long?,
        articleUrl: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): Pair<String, MutableMap<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        if (articleId != null) {
            conditions += "c.article_id = :articleId"
            params["articleId"] = articleId
        }
        if (!articleUrl.isNullOrBlank()) {
            conditions += "LOWER(a.url) LIKE :articleUrl"
            params["articleUrl"] = "%${articleUrl.lowercase()}%"
        }
        if (publishedFrom != null) {
            conditions += "a.published_at >= :publishedFrom"
            params["publishedFrom"] = Timestamp.from(publishedFrom)
        }
        if (publishedTo != null) {
            conditions += "a.published_at <= :publishedTo"
            params["publishedTo"] = Timestamp.from(publishedTo)
        }
        if (createdFrom != null) {
            conditions += "a.created_at >= :createdFrom"
            params["createdFrom"] = Timestamp.from(createdFrom)
        }
        if (createdTo != null) {
            conditions += "a.created_at <= :createdTo"
            params["createdTo"] = Timestamp.from(createdTo)
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return Pair(whereClause, params)
    }

    private companion object {
        val ARTICLE_CONTENT_ROW_MAPPER = RowMapper<ArticleContent> { rs, _ ->
            ArticleContent(
                articleId = rs.getLong("article_id"),
                content = rs.getString("content"),
                truncated = rs.getBoolean("truncated"),
                capturedAt = rs.getTimestamp("captured_at")?.toInstant(),
            )
        }
    }
}
