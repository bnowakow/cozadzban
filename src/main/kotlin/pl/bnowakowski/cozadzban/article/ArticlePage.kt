// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

data class ArticlePage(
    val content: List<Article>,
    val pageable: PageableInfo,
    val totalElements: Long,
    val totalPages: Int,
    val size: Int,
    val number: Int,
    val numberOfElements: Int,
    val sort: SortInfo,
    val first: Boolean,
    val last: Boolean,
    val empty: Boolean,
)

data class ArticleResponsePage(
    val content: List<ArticleResponse>,
    val pageable: PageableInfo,
    val totalElements: Long,
    val totalPages: Int,
    val size: Int,
    val number: Int,
    val numberOfElements: Int,
    val sort: SortInfo,
    val first: Boolean,
    val last: Boolean,
    val empty: Boolean,
)

data class PageableInfo(
    val pageNumber: Int,
    val pageSize: Int,
    val offset: Long,
    val paged: Boolean,
    val unpaged: Boolean,
    val sort: SortInfo,
)

data class SortInfo(
    val sorted: Boolean,
    val unsorted: Boolean,
    val empty: Boolean = !sorted,
)
