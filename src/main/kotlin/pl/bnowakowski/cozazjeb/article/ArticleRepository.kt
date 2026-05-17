// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

interface ArticleRepository : CrudRepository<Article, Long>, ArticleRepositoryCustom {
    fun existsByUrl(url: String): Boolean
    fun findByUrl(url: String): Article?
}

interface ArticleRepositoryCustom {
    /**
     * Returns a page of articles sorted by [sortField] (validated against [SORT_COLUMN_MAP])
     * and [sortDirection] ("asc" or "desc", case-insensitive), with optional filters.
     *
     * @throws IllegalArgumentException if [sortField] is not in the allowlist or
     *   [sortDirection] is not "asc"/"desc" — caller should return 400.
     */
    fun findPage(
        page: Int,
        size: Int,
        sortField: String,
        sortDirection: String,
        language: String? = null,
        publishedFrom: Instant? = null,
        publishedTo: Instant? = null,
        createdFrom: Instant? = null,
        createdTo: Instant? = null,
    ): List<Article>

    /** Counts articles matching the same filter criteria used in [findPage]. */
    fun countFiltered(
        language: String? = null,
        publishedFrom: Instant? = null,
        publishedTo: Instant? = null,
        createdFrom: Instant? = null,
        createdTo: Instant? = null,
    ): Long

    /**
     * Returns articles for RSS ordered by created_at DESC, optionally filtered by language.
     */
    fun findForRss(language: String?): List<Article>

    /** Returns the distinct normalized language codes currently stored in the article table, sorted alphabetically. */
    fun findDistinctLanguages(): List<String>

    /** Returns the most frequently used normalized language codes, most-used first. */
    fun findTopLanguages(limit: Int): List<String>

    /** Returns articles whose favicon still needs to be downloaded into the local cache. */
    fun findFaviconBackfillCandidates(limit: Int): List<Article>

    /** Updates only the favicon field for a single article. */
    fun updateFavicon(id: Long, favicon: String): Boolean

    companion object {
        /**
         * Allowlist mapping from API sort field name to SQL column name (BR-26).
         * Raw user input MUST NOT be interpolated into SQL directly.
         */
        val SORT_COLUMN_MAP: Map<String, String> = mapOf(
            "id"          to "id",
            "createdAt"   to "created_at",
            "publishedAt" to "published_at",
            "language"    to "language",
            "title"       to "title",
            "url"         to "url",
        )
    }
}

@Repository
class ArticleRepositoryCustomImpl(
    private val jdbc: NamedParameterJdbcTemplate,
) : ArticleRepositoryCustom {

    override fun findPage(
        page: Int,
        size: Int,
        sortField: String,
        sortDirection: String,
        language: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): List<Article> {
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
        val (whereClause, params) = buildWhereClause(language, publishedFrom, publishedTo, createdFrom, createdTo)
        params["limit"] = size
        params["offset"] = page * size
        // column and direction are from a closed allowlist — safe to interpolate
        val sql = """
            SELECT id, url, language, title, thumbnail, favicon, lead, quote, ai_summary,
                   created_by_user_id, created_at, published_at
              FROM article
             $whereClause
             ORDER BY $column $direction
             LIMIT :limit OFFSET :offset
        """.trimIndent()
        return jdbc.query(sql, params, ARTICLE_ROW_MAPPER)
    }

    override fun countFiltered(
        language: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): Long {
        val (whereClause, params) = buildWhereClause(language, publishedFrom, publishedTo, createdFrom, createdTo)
        val sql = "SELECT COUNT(*) FROM article $whereClause"
        return jdbc.queryForObject(sql, params, Long::class.java) ?: 0L
    }

    override fun findForRss(language: String?): List<Article> {
        val hasLanguageFilter = !language.isNullOrBlank()
        val sql = if (hasLanguageFilter) {
            """
                SELECT id, url, language, title, thumbnail, favicon, lead, quote, ai_summary,
                       created_by_user_id, created_at, published_at
                  FROM article
                 WHERE language = :language
                 ORDER BY created_at DESC
            """.trimIndent()
        } else {
            """
                SELECT id, url, language, title, thumbnail, favicon, lead, quote, ai_summary,
                       created_by_user_id, created_at, published_at
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

    override fun findDistinctLanguages(): List<String> =
        jdbc.queryForList(
            "SELECT DISTINCT language FROM article WHERE language IS NOT NULL ORDER BY language",
            emptyMap<String, Any>(),
            String::class.java,
        )

    override fun findTopLanguages(limit: Int): List<String> =
        jdbc.queryForList(
            """
                SELECT language
                  FROM article
                 WHERE language IS NOT NULL
                 GROUP BY language
                 ORDER BY COUNT(*) DESC, language ASC
                 LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit.coerceAtLeast(0)),
            String::class.java,
        )

    override fun findFaviconBackfillCandidates(limit: Int): List<Article> =
        jdbc.query(
            """
                SELECT id, url, language, title, thumbnail, favicon, lead, quote, ai_summary,
                       created_by_user_id, created_at, published_at
                  FROM article
                 WHERE favicon IS NULL
                    OR TRIM(favicon) = ''
                    OR favicon NOT LIKE '/favicons/%'
                 ORDER BY id ASC
                 LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit.coerceAtLeast(0)),
            ARTICLE_ROW_MAPPER,
        )

    override fun updateFavicon(id: Long, favicon: String): Boolean {
        val updated = jdbc.update(
            """
                UPDATE article
                   SET favicon = :favicon
                 WHERE id = :id
            """.trimIndent(),
            mapOf("id" to id, "favicon" to favicon),
        )
        return updated > 0
    }

    /**
     * Builds a parameterised WHERE clause from the given filters.
     * Column names are hardcoded — only parameter values come from user input (BR-09).
     */
    private fun buildWhereClause(
        language: String?,
        publishedFrom: Instant?,
        publishedTo: Instant?,
        createdFrom: Instant?,
        createdTo: Instant?,
    ): Pair<String, MutableMap<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>()

        if (!language.isNullOrBlank()) {
            conditions += "language = :language"
            params["language"] = language
        }
        if (publishedFrom != null) {
            conditions += "published_at >= :publishedFrom"
            params["publishedFrom"] = Timestamp.from(publishedFrom)
        }
        if (publishedTo != null) {
            conditions += "published_at <= :publishedTo"
            params["publishedTo"] = Timestamp.from(publishedTo)
        }
        if (createdFrom != null) {
            conditions += "created_at >= :createdFrom"
            params["createdFrom"] = Timestamp.from(createdFrom)
        }
        if (createdTo != null) {
            conditions += "created_at <= :createdTo"
            params["createdTo"] = Timestamp.from(createdTo)
        }

        val whereClause = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        return Pair(whereClause, params)
    }

    private companion object {
        val ARTICLE_ROW_MAPPER = RowMapper<Article> { rs, _ ->
            Article(
                id              = rs.getLong("id"),
                url             = rs.getString("url"),
                language        = rs.getString("language"),
                title           = rs.getString("title"),
                thumbnail       = rs.getString("thumbnail"),
                favicon         = rs.getString("favicon"),
                lead            = rs.getString("lead"),
                quote           = rs.getString("quote"),
                aiSummary       = rs.getString("ai_summary"),
                createdByUserId = rs.getLong("created_by_user_id"),
                publishedAt     = rs.getTimestamp("published_at")?.toInstant(),
                createdAt       = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH,
            )
        }
    }
}
