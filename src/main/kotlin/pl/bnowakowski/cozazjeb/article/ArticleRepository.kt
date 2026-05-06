// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

interface ArticleRepository : CrudRepository<Article, Long>, ArticleRepositoryCustom {
    fun existsByUrl(url: String): Boolean
    fun findByUrl(url: String): Article?
}

interface ArticleRepositoryCustom {
    /**
     * Returns a page of articles sorted by [sortField] (validated against [SORT_COLUMN_MAP])
     * and [sortDirection] ("asc" or "desc", case-insensitive).
     *
     * @throws IllegalArgumentException if [sortField] is not in the allowlist or
     *   [sortDirection] is not "asc"/"desc" — caller should return 400.
     */
    fun findPage(page: Int, size: Int, sortField: String, sortDirection: String): List<Article>

    /**
     * Returns articles for RSS ordered by created_at DESC, optionally filtered by language.
     */
    fun findForRss(language: String?): List<Article>

    companion object {
        /**
         * Allowlist mapping from API sort field name to SQL column name (BR-26).
         * Raw user input MUST NOT be interpolated into SQL directly.
         */
        val SORT_COLUMN_MAP: Map<String, String> = mapOf(
            "id"        to "id",
            "createdAt" to "created_at",
            "language"  to "language",
            "title"     to "title",
            "url"       to "url",
        )
    }
}

@Repository
class ArticleRepositoryCustomImpl(
    private val jdbc: NamedParameterJdbcTemplate,
) : ArticleRepositoryCustom {

    override fun findPage(page: Int, size: Int, sortField: String, sortDirection: String): List<Article> {
        val column = ArticleRepositoryCustom.SORT_COLUMN_MAP[sortField]
            ?: throw IllegalArgumentException(
                "Invalid sort field '$sortField'. Allowed: ${ArticleRepositoryCustom.SORT_COLUMN_MAP.keys}"
            )
        val direction = when (sortDirection.uppercase()) {
            "ASC"  -> "ASC"
            "DESC" -> "DESC"
            else   -> throw IllegalArgumentException(
                "Invalid sort direction '$sortDirection'. Allowed: asc, desc"
            )
        }
        // column and direction are from a closed allowlist — safe to interpolate
        val sql = """
            SELECT id, url, language, title, thumbnail, lead, quote, ai_summary, created_by_user_id, created_at
              FROM article
             ORDER BY $column $direction
             LIMIT :limit OFFSET :offset
        """.trimIndent()
        val params = mapOf("limit" to size, "offset" to page * size)
        return jdbc.query(sql, params, ARTICLE_ROW_MAPPER)
    }

    override fun findForRss(language: String?): List<Article> {
        val hasLanguageFilter = !language.isNullOrBlank()
        val sql = if (hasLanguageFilter) {
            """
                SELECT id, url, language, title, thumbnail, lead, quote, ai_summary, created_by_user_id, created_at
                  FROM article
                 WHERE language = :language
                 ORDER BY created_at DESC
            """.trimIndent()
        } else {
            """
                SELECT id, url, language, title, thumbnail, lead, quote, ai_summary, created_by_user_id, created_at
                  FROM article
                 ORDER BY created_at DESC
            """.trimIndent()
        }
        val params = if (hasLanguageFilter) {
            mapOf("language" to language)
        } else {
            emptyMap<String, Any>()
        }
        return jdbc.query(sql, params, ARTICLE_ROW_MAPPER)
    }

    private companion object {
        val ARTICLE_ROW_MAPPER = RowMapper<Article> { rs, _ ->
            Article(
                id              = rs.getLong("id"),
                url             = rs.getString("url"),
                language        = rs.getString("language"),
                title           = rs.getString("title"),
                thumbnail       = rs.getString("thumbnail"),
                lead            = rs.getString("lead"),
                quote           = rs.getString("quote"),
                aiSummary       = rs.getString("ai_summary"),
                createdByUserId = rs.getLong("created_by_user_id"),
                createdAt       = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
            )
        }
    }
}
