// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

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

    val publishedAt: Instant? = null,
)

internal fun ArticleInput.withoutFacebookImportMarkerQuote(): ArticleInput =
    copy(quote = quote.withoutFacebookImportMarkerQuote())

internal fun String?.withoutFacebookImportMarkerQuote(): String? {
    val trimmed = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return trimmed.takeUnless { it.containsFacebookImportMarkerPhrase() }
}

internal fun String.containsFacebookImportMarkerPhrase(): Boolean {
    val normalized = map { char ->
        if (char.isWhitespace() || char == '\u00A0') ' ' else char.lowercaseChar()
    }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()

    return normalized.contains("co za dzban") || normalized.contains("what a fucker")
}
