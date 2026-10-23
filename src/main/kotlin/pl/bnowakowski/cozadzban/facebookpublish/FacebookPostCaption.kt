// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import pl.bnowakowski.cozadzban.article.Article

object FacebookPostCaption {
    fun forArticle(article: Article): String {
        val fallback = if (article.language.startsWith("pl")) "Nowa historia" else "New story"
        val title = clean(article.title).takeIf { it.isNotBlank() } ?: fallback
        val lead = sequenceOf(article.lead, article.aiSummary, article.quote)
            .map(::clean).firstOrNull { it.isNotBlank() }?.take(550)
        return listOfNotNull(title.take(230), lead).joinToString("\n\n")
    }

    private fun clean(raw: String?): String = raw.orEmpty()
        .replace(Regex("(?i)\\b(?:https?://|www\\.)\\S+|\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/\\S*)?"), "")
        .replace(Regex("\\s+"), " ").trim()
}
