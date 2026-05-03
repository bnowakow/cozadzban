// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

class ArticleUrlConflictException(val url: String) : RuntimeException(
    "An article with URL '$url' already exists"
)
