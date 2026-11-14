// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Uses only a publisher's own RSS item that links to the exact requested article. */
internal object PublisherRssFallback {
    private const val MAX_FEED_CHARS = 2_000_000
    private val log = LoggerFactory.getLogger(PublisherRssFallback::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private enum class Publisher(val feedUrl: String) {
        FT("https://www.ft.com/news-feed?format=rss"),
        EURONEWS("https://www.euronews.com/rss?format=mrss"),
    }

    fun fetch(url: String, fetchFeed: (String) -> String): EnrichmentResult? {
        val publisher = publisherFor(url) ?: return null
        val target = resolveTarget(url, publisher) ?: run {
            log.info("Publisher RSS fallback could not resolve article URL '{}'", url)
            return null
        }
        val xml = try {
            fetchFeed(publisher.feedUrl)
        } catch (ex: Exception) {
            log.info("Publisher RSS feed unavailable for '{}' ({})", url, ex.javaClass.simpleName)
            return null
        }
        return parseMatchingItem(xml, target, publisher).also {
            if (it == null) log.info("Publisher RSS feed has no usable exact match for '{}'", url)
        }
    }

    internal fun parseMatchingItem(xml: String, target: String): EnrichmentResult? {
        val publisher = publisherFor(target) ?: return null
        return parseMatchingItem(xml, target, publisher)
    }

    private fun parseMatchingItem(xml: String, target: String, publisher: Publisher): EnrichmentResult? {
        if (xml.length > MAX_FEED_CHARS || !xml.contains("<rss", ignoreCase = true)) return null
        val targetKey = articleKey(target, publisher) ?: return null
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        for (item in document.getElementsByTag("item")) {
            val itemUrls = listOf(item.childText("link"), item.childText("guid"))
            if (itemUrls.none { articleKey(it, publisher) == targetKey }) continue
            val title = item.childText("title").trim().takeIf { it.isNotBlank() } ?: return null
            val description = item.childText("description")
            val lead = Jsoup.parseBodyFragment(description).text().trim().takeIf { it.isNotBlank() }
                ?.take(1_000) ?: return null
            val image = item.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
                ?: item.getElementsByTag("media:content")
                    .firstOrNull { it.attr("medium") == "image" || it.attr("type").startsWith("image/") }
                    ?.attr("url")
            val publishedAt = runCatching {
                ZonedDateTime.parse(item.childText("pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            }.getOrNull()
            return EnrichmentResult(
                title = title,
                thumbnail = image?.takeIf(::isHttpsUrl),
                lead = lead,
                publishedAt = publishedAt,
            )
        }
        return null
    }

    private fun Element.childText(name: String): String =
        children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }?.text().orEmpty()

    private fun resolveTarget(url: String, publisher: Publisher): String? {
        if (articleKey(url, publisher) != null) return url
        var current = runCatching { URI(url) }.getOrNull() ?: return null
        repeat(4) {
            if (current.scheme != "https" || !allowedRedirectHost(current.host, publisher)) return null
            val next = redirectLocation(current, "HEAD") ?: redirectLocation(current, "GET") ?: return null
            current = current.resolve(next)
            if (articleKey(current.toString(), publisher) != null) return current.toString()
        }
        return null
    }

    private fun redirectLocation(uri: URI, method: String): String? {
        return try {
            val request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in listOf(301, 302, 303, 307, 308)) null
            else response.headers().firstValue("location").orElse(null)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun publisherFor(url: String): Publisher? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https") return null
        return when (uri.host?.lowercase()) {
            "ft.trib.al", "on.ft.com", "ft.com", "www.ft.com" -> Publisher.FT
            "l.euronews.com", "euronews.com", "www.euronews.com" -> Publisher.EURONEWS
            else -> null
        }
    }

    private fun allowedRedirectHost(host: String?, publisher: Publisher): Boolean = when (publisher) {
        Publisher.FT -> host?.lowercase() in setOf("ft.trib.al", "on.ft.com", "ft.com", "www.ft.com")
        Publisher.EURONEWS -> host?.lowercase() in setOf("l.euronews.com", "euronews.com", "www.euronews.com")
    }

    private fun articleKey(url: String, publisher: Publisher): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || !allowedRedirectHost(uri.host, publisher)) return null
        val host = uri.host.lowercase().removePrefix("www.")
        if (host == "ft.trib.al" || host == "on.ft.com" || host == "l.euronews.com") return null
        val path = uri.rawPath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return "$host$path"
    }

    private fun isHttpsUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
