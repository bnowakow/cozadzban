// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
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

    fun fetch(url: String, token: String): Page? {
        if (token.isBlank() || !validPublicUrl(url)) return null
        return try {
            val body = mapper.writeValueAsString(mapOf("url" to url, "formats" to listOf("raw")))
            val request = HttpRequest.newBuilder(URI("https://web-fetch.apify.actor/"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                log.info("Apify Web Fetch failed for '{}'; status={}", url, response.statusCode())
                null
            } else {
                parsePage(url, response.body()).also {
                    if (it == null) log.info("Apify Web Fetch returned no usable article for '{}'", url)
                }
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (ex: Exception) {
            log.info("Apify Web Fetch unavailable for '{}'; error={}", url, ex.javaClass.simpleName)
            null
        }
    }

    internal fun parsePage(requestedUrl: String, body: String): Page? {
        if (body.length > MAX_RESPONSE_CHARS) return null
        val root = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        val fetch = root.path("fetch")
        if (fetch.path("httpStatusCode").asInt() !in 200..299) return null
        if (!fetch.path("contentType").asText("").lowercase().startsWith("text/html")) return null
        val loadedUrl = fetch.path("loadedUrl").asText("")
        if (!sameArticleSite(requestedUrl, loadedUrl)) return null
        val canonicalUrl = root.path("metadata").path("canonicalUrl").asText("")
        if (canonicalUrl.isNotBlank() && !sameArticleSite(loadedUrl, canonicalUrl)) return null
        val title = root.path("metadata").path("title").asText("").trim()
        if (title.isBlank() || isBlockTitle(title)) return null
        val html = root.path("raw").asText("")
        if (html.length < 100 || !html.contains("<html", ignoreCase = true)) return null
        return Page(loadedUrl, html)
    }

    private fun isBlockTitle(title: String): Boolean {
        val lower = title.lowercase()
        return listOf("access denied", "just a moment", "security verification", "are you a robot",
            "enable javascript", "request blocked", "page unavailable", "verify you are human")
            .any(lower::contains)
    }

    private fun sameArticleSite(requested: String, loaded: String): Boolean {
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

    private fun validPublicUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            host.contains('.') && host != "localhost" && !host.endsWith(".local") &&
            !host.endsWith(".internal") && !host.matches(Regex("[0-9.]+")) && !host.contains(':')
    }
}
