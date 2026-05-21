// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("article_content")
data class ArticleContent(
    @Id val articleId: Long,
    val content: String,
    val truncated: Boolean = false,
    val capturedAt: Instant? = null,
)
