// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.springframework.data.repository.CrudRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

interface ArticleContentRepository : CrudRepository<ArticleContent, Long>, ArticleContentRepositoryCustom

interface ArticleContentRepositoryCustom {
    fun existsByArticleId(articleId: Long): Boolean
    fun deleteByArticleId(articleId: Long)
    fun insert(content: ArticleContent)
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
            "INSERT INTO article_content (article_id, content, truncated, captured_at) VALUES (:articleId, :content, :truncated, :capturedAt)",
            mapOf(
                "articleId" to content.articleId,
                "content" to content.content,
                "truncated" to content.truncated,
                "capturedAt" to content.capturedAt,
            ),
        )
    }
}
