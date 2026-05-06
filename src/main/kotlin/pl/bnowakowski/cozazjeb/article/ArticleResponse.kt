// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import com.fasterxml.jackson.annotation.JsonInclude
import pl.bnowakowski.cozazjeb.user.AppUser
import java.time.Instant

data class CreatedByInfo(val id: Long, val email: String)

/**
 * External DTO for article responses.
 *
 * `createdBy` is populated only when the caller is authenticated (item 46 — Phase 16).
 * Anonymous responses omit creator data entirely via [JsonInclude.Include.NON_NULL].
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ArticleResponse(
    val id: Long?,
    val url: String,
    val language: String,
    val title: String?,
    val thumbnail: String?,
    val lead: String?,
    val quote: String?,
    val aiSummary: String?,
    val publishedAt: Instant?,
    val createdAt: Instant?,
    val createdBy: CreatedByInfo?,
) {
    companion object {
        fun from(article: Article, creator: AppUser?): ArticleResponse = ArticleResponse(
            id = article.id,
            url = article.url,
            language = article.language,
            title = article.title,
            thumbnail = article.thumbnail,
            lead = article.lead,
            quote = article.quote,
            aiSummary = article.aiSummary,
            publishedAt = article.publishedAt,
            createdAt = article.createdAt,
            createdBy = creator?.id?.let { CreatedByInfo(it, creator.email) },
        )
    }
}
