// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Last resort for an article page that the regular fetch and publisher fallbacks cannot read. */
internal object ApifyWebFetchFallback {
    private const val MAX_RESPONSE_CHARS = 12_000_000
    private val log = LoggerFactory.getLogger(ApifyWebFetchFallback::class.java)
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    data class Page(val loadedUrl: String, val html: String)

    fun fetch(url: String, token: String, browserWaitMillis: Int = 0): Page? {
        if (token.isBlank() || !validPublicUrl(url)) return null
        return try {
            val input = mutableMapOf<String, Any>("url" to url, "formats" to listOf("raw"))
            if (browserWaitMillis > 0) input["wait"] = browserWaitMillis
            val body = mapper.writeValueAsString(input)
            val request = HttpRequest.newBuilder(URI("https://web-fetch.apify.actor/"))
                .timeout(Duration.ofSeconds(if (browserWaitMillis > 0) 75 else 45))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.info("Apify Web Fetch failed for '{}'; browserWaitMillis={}; status={}", url, browserWaitMillis, response.statusCode())
                null
            } else {
                val parsed = parsePageResult(url, response.body())
                if (parsed.page == null) log.info(
                    "Apify Web Fetch returned no usable article for '{}'; browserWaitMillis={}; reason={}",
                    url, browserWaitMillis, parsed.reason,
                )
                parsed.page
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (ex: Exception) {
            log.info("Apify Web Fetch unavailable for '{}'; browserWaitMillis={}; error={}",
                url, browserWaitMillis, ex.javaClass.simpleName)
            null
        }
    }

    private data class ParseResult(val page: Page?, val reason: String)

    internal fun parsePage(requestedUrl: String, body: String): Page? = parsePageResult(requestedUrl, body).page

    private fun parsePageResult(requestedUrl: String, body: String): ParseResult {
        if (body.length > MAX_RESPONSE_CHARS) return ParseResult(null, "response-too-large")
        val root = runCatching { mapper.readTree(body) }.getOrNull() ?: return ParseResult(null, "invalid-json")
        val fetch = root.path("fetch")
        val targetStatus = fetch.path("httpStatusCode").asInt()
        if (targetStatus !in 200..299) return ParseResult(null, "target-http-$targetStatus")
        if (!fetch.path("contentType").asText("").lowercase().startsWith("text/html"))
            return ParseResult(null, "not-html")
        val loadedUrl = fetch.path("loadedUrl").asText("")
        if (!sameArticleSite(requestedUrl, loadedUrl)) return ParseResult(null, "unrelated-redirect")
        val canonicalUrl = root.path("metadata").path("canonicalUrl").asText("")
        if (canonicalUrl.isNotBlank() && !sameArticleSite(loadedUrl, canonicalUrl))
            return ParseResult(null, "unrelated-canonical")
        val title = root.path("metadata").path("title").asText("").trim()
        if (title.isBlank() || isBlockTitle(title)) return ParseResult(null, "missing-or-block-title")
        val html = root.path("raw").asText("")
        if (html.length < 100 || !html.contains("<html", ignoreCase = true))
            return ParseResult(null, "missing-html")
        return ParseResult(Page(loadedUrl, html), "ok")
    }

    internal fun validateHtmlPage(requestedUrl: String, loadedUrl: String, html: String): Page? {
        if (html.length !in 100..MAX_RESPONSE_CHARS || !sameArticleSite(requestedUrl, loadedUrl)) return null
        val doc = Jsoup.parse(html, loadedUrl)
        if (isBlockTitle(doc.title()) || doc.title().isBlank()) return null
        val canonical = doc.selectFirst("link[rel=canonical]")?.absUrl("href").orEmpty()
        if (canonical.isNotBlank() && !sameArticleSite(loadedUrl, canonical)) return null
        return Page(loadedUrl, html)
    }

    internal fun isBlockTitle(title: String): Boolean {
        val lower = title.lowercase()
        return listOf("access denied", "just a moment", "security verification", "are you a robot",
            "enable javascript", "request blocked", "page unavailable", "verify you are human")
            .any(lower::contains)
    }

    internal fun sameArticleSite(requested: String, loaded: String): Boolean {
        val source = runCatching { URI(requested) }.getOrNull() ?: return false
        val target = runCatching { URI(loaded) }.getOrNull() ?: return false
        if (!validPublicUrl(loaded)) return false
        val sourceHost = source.host.lowercase().removePrefix("www.")
        val targetHost = target.host.lowercase().removePrefix("www.")
        val sameHost = sourceHost == targetHost
        val shortLinkPublisher = when (sourceHost) {
            "ft.trib.al", "on.ft.com" -> "ft.com"
            "l.euronews.com" -> "euronews.com"
            "reut.rs" -> "reuters.com"
            else -> null
        }
        if (shortLinkPublisher != null) return targetHost == shortLinkPublisher
        return sameHost && source.rawPath?.trimEnd('/') == target.rawPath?.trimEnd('/')
    }

    internal fun validPublicUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            host.contains('.') && host != "localhost" && !host.endsWith(".local") &&
            !host.endsWith(".internal") && !host.matches(Regex("[0-9.]+")) && !host.contains(':')
    }
}
