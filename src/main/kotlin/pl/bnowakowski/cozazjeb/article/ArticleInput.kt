// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class ArticleInput(
    @field:NotBlank
    @field:Size(max = 2048)
    @field:Pattern(regexp = "^https?://.+")
    val url: String,

    @field:NotBlank
    @field:Size(max = 20)
    val language: String,

    @field:Size(min = 1, max = 2000)
    val quote: String? = null,
)
