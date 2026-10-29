// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.jsoup.Jsoup
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FacebookPostArticleMatch(
    val articleId: Long,
    val title: String?,
    val articleUrl: String,
) {
    val storyUrl: String get() = "/stories/$articleId"
    val originalArticleUrl: String?
        get() = articleUrl.takeUnless {
            val host = runCatching { URI(it).host?.lowercase() }.getOrNull()
            host == "facebook.com" || host?.endsWith(".facebook.com") == true
        }
}

/** Resolves a public Facebook post to a locally published or imported article. */
@Service
class FacebookPostLookupService(private val jdbc: NamedParameterJdbcTemplate) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun findArticle(rawUrl: String): FacebookPostArticleMatch? {
        val parsed = parseFacebookPostUrl(rawUrl)
        findByImportedPost(parsed.identifier)?.let { return it }
        val numericPostId = parsed.identifier.takeIf { it.all(Char::isDigit) }
            ?: resolveNumericPostId(parsed.canonicalUrl)
        return numericPostId?.let { findByPublishedPost(it) }
    }

    private fun findByImportedPost(identifier: String): FacebookPostArticleMatch? = jdbc.query(
        """
            SELECT a.id, a.title, a.url
              FROM article a
              LEFT JOIN facebook_article_proposal proposal ON proposal.id = a.source_facebook_proposal_id
             WHERE a.url LIKE :identifier
                OR proposal.article_url LIKE :identifier
                OR proposal.facebook_post_url LIKE :identifier
             ORDER BY a.id DESC LIMIT 1
        """.trimIndent(),
        mapOf("identifier" to "%$identifier%"),
    ) { rs, _ -> FacebookPostArticleMatch(rs.getLong("id"), rs.getString("title"), rs.getString("url")) }
        .firstOrNull()

    private fun findByPublishedPost(postId: String): FacebookPostArticleMatch? = jdbc.query(
        """
            SELECT a.id, a.title, a.url
              FROM facebook_page_publication publication
              JOIN article a ON a.id = publication.article_id_snapshot
             WHERE split_part(publication.post_id, '_', 2) = :postId
                OR publication.post_permalink LIKE :postIdPattern
             ORDER BY publication.published_at DESC NULLS LAST LIMIT 1
        """.trimIndent(),
        mapOf("postId" to postId, "postIdPattern" to "%$postId%"),
    ) { rs, _ -> FacebookPostArticleMatch(rs.getLong("id"), rs.getString("title"), rs.getString("url")) }
        .firstOrNull()

    private fun resolveNumericPostId(canonicalUrl: String): String? {
        return try {
            val pluginUrl = "https://www.facebook.com/plugins/post.php?href=${URLEncoder.encode(canonicalUrl, Charsets.UTF_8)}&show_text=true&width=500"
            val request = HttpRequest.newBuilder(URI(pluginUrl))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            Jsoup.parse(response.body(), "https://www.facebook.com")
                .select("a[href]")
                .asSequence()
                .mapNotNull { element -> runCatching { parseFacebookPostUrl(element.attr("abs:href")) }.getOrNull() }
                .map { it.identifier }
                .firstOrNull { it.all(Char::isDigit) }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
    }
}

private data class ParsedFacebookPostUrl(val identifier: String, val canonicalUrl: String)

private val FACEBOOK_HOSTS = setOf("facebook.com", "www.facebook.com", "m.facebook.com", "mbasic.facebook.com")
private val FACEBOOK_POST_ID = Regex("(?:pfbid)?[A-Za-z0-9]{8,180}")

private fun parseFacebookPostUrl(rawUrl: String): ParsedFacebookPostUrl {
    require(rawUrl.length <= 4096) { "Facebook URL is too long" }
    val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        ?: throw IllegalArgumentException("Enter a valid Facebook post URL")
    require(uri.scheme == "https" && uri.host?.lowercase() in FACEBOOK_HOSTS && uri.userInfo == null && uri.port == -1) {
        "Enter an https://facebook.com post URL"
    }
    val path = uri.path.orEmpty()
    val identifier: String
    val canonicalUrl: String
    if (path == "/permalink.php" || path == "/story.php") {
        val values = uri.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        identifier = values["story_fbid"].orEmpty()
        val pageId = values["id"].orEmpty()
        require(pageId.all(Char::isDigit) && pageId.isNotEmpty()) { "Facebook post URL needs a numeric Page ID" }
        canonicalUrl = "https://www.facebook.com/permalink.php?story_fbid=$identifier&id=$pageId"
    } else {
        val segments = path.trim('/').split('/')
        require(segments.size >= 3 && segments[segments.size - 2] == "posts") {
            "Enter a Facebook post or permalink URL"
        }
        identifier = segments.last()
        canonicalUrl = "https://www.facebook.com/${segments.takeLast(3).joinToString("/")}"
    }
    require(FACEBOOK_POST_ID.matches(identifier)) { "Facebook post ID is invalid" }
    return ParsedFacebookPostUrl(identifier, canonicalUrl)
}
