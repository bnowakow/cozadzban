// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.article

import jakarta.servlet.http.HttpServletRequest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** Counts document loads without persisting visitor identifiers or request headers. */
@Repository
class StoryViewCounter(private val jdbc: NamedParameterJdbcTemplate) {
    fun record(articleId: Long) {
        jdbc.update(
            """INSERT INTO article_story_view_daily(article_id, view_date_utc, view_count)
               VALUES (:articleId, (now() AT TIME ZONE 'UTC')::date, 1)
               ON CONFLICT (article_id, view_date_utc) DO UPDATE
               SET view_count = article_story_view_daily.view_count + 1""",
            mapOf("articleId" to articleId),
        )
    }
}

/** Heuristic filtering: counts browser HTML navigations, excluding known crawlers and previews. */
object StoryViewEligibility {
    private val botPattern = Regex(
        "bot|spider|crawl|preview|facebookexternalhit|facebot|whatsapp|telegram|twitter|linkedin|discord|slack|" +
            "headless|lighthouse|pagespeed|curl|wget|python|okhttp|httpclient|postman|insomnia|" +
            "google-inspectiontool|chatgpt-user|oai-search|perplexity|semrush|ahrefs",
        RegexOption.IGNORE_CASE,
    )

    fun shouldCount(request: HttpServletRequest): Boolean {
        if (request.method != "GET") return false
        if (!request.getHeader("Accept").orEmpty().contains("text/html", ignoreCase = true)) return false
        val userAgent = request.getHeader("User-Agent").orEmpty()
        if (!userAgent.contains("Mozilla/5.0", ignoreCase = true) || botPattern.containsMatchIn(userAgent)) return false
        val destination = request.getHeader("Sec-Fetch-Dest").orEmpty()
        if (destination.isNotBlank() && destination != "document" && destination != "iframe") return false
        val mode = request.getHeader("Sec-Fetch-Mode").orEmpty()
        if (mode.isNotBlank() && mode != "navigate") return false
        return true
    }
}
