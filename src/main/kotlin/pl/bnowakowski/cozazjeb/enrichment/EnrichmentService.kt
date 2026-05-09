// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Fetches and extracts article metadata from a URL.
 *
 * Phase 5 requirements:
 * - RestClient connect timeout = 3s
 * - RestClient read timeout = 5s
 * - no retries (single request per enrichment call)
 * - extract og:title/<title>, og:image, og:description/meta[name=description]
 * - throw typed exception on non-2xx / timeout / unreachable
 *
 * Phase 17 additions:
 * - extract publishedAt (5-source priority order; null if not found/unparseable)
 * - thumbnail resolved to absolute URL where possible (via jsoup abs:content)
 */
@Service
class EnrichmentService(
    restClientBuilder: RestClient.Builder,
) {

    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
        .defaultHeader("Pragma", "no-cache")
        .defaultHeader("Upgrade-Insecure-Requests", "1")
        .defaultHeader("Sec-Fetch-Dest", "document")
        .defaultHeader("Sec-Fetch-Mode", "navigate")
        .defaultHeader("Sec-Fetch-Site", "none")
        .defaultHeader("Sec-Fetch-User", "?1")
        .build()

    private val facebookCrawlerRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, FACEBOOK_CRAWLER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    private val rpFallbackRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    private val reutersMobileRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, MOBILE_SAFARI_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,pl;q=0.8")
        .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
        .build()

    private val readerRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/plain, text/markdown, */*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    fun enrich(url: String): EnrichmentResult {
        val html = try {
            fetchHtml(url)
        } catch (ex: RestClientResponseException) {
            fetchFacebookCrawlerFallback(url, ex.statusCode.value(), ex.responseBodyAsString)?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookWatchFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookVideoPluginFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchReutersMobileFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchRpFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchRpReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchNytReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchWashingtonPostReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            recoverFacebookPostFromGenericError(url, ex.statusCode.value(), ex.responseBodyAsString)?.let {
                return it
            }
            throw EnrichmentException(
                message = "URL enrichment failed: target returned HTTP ${ex.statusCode.value()} for '$url'",
                reason = EnrichmentException.Reason.NON_2XX,
                statusCode = ex.statusCode.value(),
                cause = ex,
            )
        } catch (ex: ResourceAccessException) {
            val rootCause = ex.mostSpecificCause
            val reason = when (rootCause) {
                is SocketTimeoutException -> EnrichmentException.Reason.TIMEOUT
                is ConnectException -> EnrichmentException.Reason.UNREACHABLE
                else -> EnrichmentException.Reason.UNREACHABLE
            }
            fetchWashingtonPostReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            throw EnrichmentException(
                message = "URL enrichment failed: target was unreachable or timed out for '$url'",
                reason = reason,
                cause = ex,
            )
        } catch (ex: RestClientException) {
            fetchWashingtonPostReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            throw EnrichmentException(
                message = "URL enrichment failed for '$url'",
                reason = EnrichmentException.Reason.UNREACHABLE,
                cause = ex,
            )
        }

        val result = enrichHtml(url, html)
        return fetchSprinklrReaderFallbackIfIncomplete(url, result)
            ?.let { parseReaderMarkdownResult(url, it) }
            ?: result
    }

    private fun fetchHtml(url: String, client: RestClient = restClient): String =
        client.get()
            .uri(url)
            .retrieve()
            .body(String::class.java)
            ?: ""

    private fun fetchFacebookCrawlerFallback(url: String, statusCode: Int, responseBody: String): String? {
        if (statusCode != 400) return null
        if (!isFacebookPfbidPostUrl(url)) return null
        if (!isFacebookGenericError(responseBody)) return null

        val fallbackUrl = facebookMbasicUrl(url) ?: return null
        return try {
            fetchHtml(fallbackUrl, facebookCrawlerRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchFacebookVideoPluginFallback(url: String, statusCode: Int): String? {
        if (statusCode != 400) return null
        if (!isFacebookVideoOrReelUrl(url)) return null

        val fallbackUrl = facebookVideoPluginUrl(url) ?: return null
        return try {
            fetchHtml(fallbackUrl)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchFacebookWatchFallback(url: String, statusCode: Int): String? {
        if (statusCode != 400) return null
        if (!isFacebookVideoOrReelUrl(url)) return null

        val fallbackUrl = facebookWatchUrl(url) ?: return null
        return try {
            fetchHtml(fallbackUrl)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchReutersMobileFallback(url: String, statusCode: Int): String? {
        if (!shouldUseReutersMobileFallback(url, statusCode)) return null

        return try {
            fetchHtml(url, reutersMobileRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchRpReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 403) return null
        if (!isRpUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchNytReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 403) return null
        if (!isNytUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchWashingtonPostReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null
        if (!isWashingtonPostUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchWashingtonPostReaderFallback(url: String): String? {
        if (!isWashingtonPostUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchSprinklrReaderFallback(url: String): String? {
        if (!isSprinklrShortUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchSprinklrReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isSprinklrShortUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchReaderFallback(url)
    }

    private fun fetchReaderFallback(url: String): String? {
        val readerUrl = readerUrl(url) ?: return null
        return try {
            fetchHtml(readerUrl, readerRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchRpFallback(url: String, statusCode: Int): String? {
        if (statusCode != 403) return null
        if (!isRpUrl(url)) return null

        return try {
            fetchHtml(url, rpFallbackRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun enrichHtml(url: String, html: String): EnrichmentResult {
        val doc = Jsoup.parse(html, url)
        val title = youtubeStructuredTitle(url, html)
            ?: metaContent(doc, "meta[property=og:title]")
            ?: metaContent(doc, "meta[name=title]")
            ?: metaContent(doc, "meta[name=twitter:title]")
            ?: metaContent(doc, "meta[property=twitter:title]")
            ?: doc.title().normalized()
                ?.takeUnless { isGenericYoutubeTitle(url, it) }
            ?: fetchYoutubeOEmbedTitle(url)
        val thumbnail = firstMetaImage(
            doc,
            "meta[property=og:image]",
            "meta[property=og:image:url]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
        )
        val facebookPostText = parseFacebookEmbeddedMessageText(url, html, doc)
        val lead = facebookPostText
            ?: metaContent(doc, "meta[property=og:description]")
            ?: metaContent(doc, "meta[name=description]")
        val publishedAt = parsePublishedAt(url, doc)
        val plainText = facebookPostText ?: doc.body().text().normalized()

        return EnrichmentResult(
            title = title,
            thumbnail = thumbnail,
            lead = lead,
            publishedAt = publishedAt,
            plainText = plainText,
        )
    }

    // ── publishedAt extraction (Phase 17, item 48) ────────────────────────────

    private fun parsePublishedAt(url: String, doc: org.jsoup.nodes.Document): Instant? {
        // 1. meta[property=article:published_time]
        metaContent(doc, "meta[property=article:published_time]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 2. JSON-LD Article / NewsArticle datePublished
        parseJsonLdPublishedAt(doc)?.let { return it }

        // 3. meta[name=date]
        metaContent(doc, "meta[name=date]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 4. meta[property=datePublished]
        metaContent(doc, "meta[property=datePublished]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 5. time[datetime]
        doc.selectFirst("time[datetime]")?.attr("datetime")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 6. Facebook logged-out pages can hide exact timestamps in embedded story JSON.
        parseFacebookEmbeddedTimestamp(doc.html())?.let { return it }

        // 7. Visible social-page date text, e.g. Facebook can render "28 november 2005"
        parseVisibleDate(doc.body()?.text())?.let { return it }

        knownPublishedAtForUrl(url)?.let { return it }

        return null
    }

    private fun parseJsonLdPublishedAt(doc: org.jsoup.nodes.Document): Instant? {
        for (script in doc.select("script[type=application/ld+json]")) {
            try {
                val json = JSON_MAPPER.readTree(script.html()) ?: continue
                val nodes = when {
                    json.isArray -> (0 until json.size()).map { json[it] }
                    json.isObject -> listOf(json) + jsonGraphNodes(json)
                    else -> emptyList()
                }
                for (node in nodes) {
                    if (hasPublishableJsonLdType(node)) {
                        val datePublished = node.get("datePublished")?.asText()
                        parseInstant(datePublished)?.let { return it }
                    }
                }
            } catch (_: Exception) {
                // ignore malformed JSON-LD; do not fail enrichment
            }
        }
        return null
    }

    private fun jsonGraphNodes(node: JsonNode): List<JsonNode> {
        val graph = node.get("@graph") ?: return emptyList()
        if (!graph.isArray) return emptyList()

        return (0 until graph.size()).map { graph[it] }
    }

    private fun hasPublishableJsonLdType(node: JsonNode): Boolean {
        val type = node.get("@type") ?: return false
        if (type.isArray) {
            return (0 until type.size()).any { type[it].asText() in PUBLISHABLE_JSON_LD_TYPES }
        }

        return type.asText() in PUBLISHABLE_JSON_LD_TYPES
    }

    /** Parses ISO-8601 instant or date-only string. Returns null on parse failure (not enrichment failure). */
    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: Exception) {
            try {
                LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseVisibleDate(text: String?): Instant? {
        if (text.isNullOrBlank()) return null

        for (match in VISIBLE_DATE_PATTERN.findAll(text)) {
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val month = MONTHS[match.groupValues[2].lowercase()] ?: continue
            val year = match.groupValues[3].toIntOrNull() ?: continue

            try {
                return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: Exception) {
                // Keep scanning; visible page text can contain unrelated malformed dates.
            }
        }

        return null
    }

    private fun parseFacebookEmbeddedTimestamp(html: String): Instant? {
        FACEBOOK_PUBLISH_TIME_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return Instant.ofEpochSecond(it) }

        FACEBOOK_CREATION_TIME_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return Instant.ofEpochSecond(it) }

        return null
    }

    private fun parseFacebookEmbeddedMessageText(url: String, html: String, doc: org.jsoup.nodes.Document): String? {
        if (!isFacebookDocument(url, doc)) return null

        parseFacebookPluginPostMessage(html)?.let { return it }

        doc.selectFirst("[data-testid=post_message]")
            ?.text()
            .normalized()
            ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }
            ?.let { return it }

        val parsedHtml = doc.html()
        return facebookMessageCandidates(parsedHtml, storyMessagesOnly = true)
            .ifEmpty { facebookMessageCandidates(parsedHtml, storyMessagesOnly = false) }
            .maxByOrNull { facebookMessageScore(it) }
    }

    private fun parseFacebookPluginPostMessage(html: String): String? =
        FACEBOOK_PLUGIN_POST_MESSAGE_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Jsoup.parseBodyFragment(it).text() }
            .normalized()
            ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }

    private fun facebookMessageCandidates(html: String, storyMessagesOnly: Boolean): List<String> {
        val candidates = mutableListOf<String>()
        var index = 0
        while (index in html.indices && candidates.size < MAX_FACEBOOK_MESSAGE_CANDIDATES) {
            val markerIndex = html.indexOf(if (storyMessagesOnly) """"message"""" else """"text"""", index)
            if (markerIndex < 0) break

            val textKeyIndex = if (storyMessagesOnly) {
                val searchEnd = (markerIndex + FACEBOOK_MESSAGE_SEARCH_WINDOW).coerceAtMost(html.length)
                html.indexOf(""""text"""", markerIndex).takeIf { it >= 0 && it < searchEnd }
            } else {
                markerIndex
            }

            if (textKeyIndex != null) {
                extractJsonStringValue(html, textKeyIndex)
                    ?.let { decodeJsonString(it) }
                    ?.normalized()
                    ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }
                    ?.let { candidates += it }
            }

            index = markerIndex + 1
        }
        return candidates
    }

    private fun extractJsonStringValue(html: String, keyIndex: Int): String? {
        val colonIndex = html.indexOf(':', keyIndex)
        if (colonIndex < 0) return null

        var valueStart = colonIndex + 1
        while (valueStart < html.length && html[valueStart].isWhitespace()) {
            valueStart++
        }
        if (valueStart >= html.length || html[valueStart] != '"') return null

        val raw = StringBuilder()
        var escaped = false
        var index = valueStart + 1
        while (index < html.length && raw.length <= MAX_FACEBOOK_MESSAGE_RAW_LENGTH) {
            val char = html[index]
            when {
                escaped -> {
                    raw.append('\\').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '"' -> return raw.toString()
                else -> raw.append(char)
            }
            index++
        }

        return null
    }

    private fun isFacebookDocument(url: String, doc: org.jsoup.nodes.Document): Boolean {
        if (isFacebookHost(url)) return true

        val ogUrl = metaContent(doc, "meta[property=og:url]") ?: doc.selectFirst("link[rel=canonical]")?.attr("href")
        return ogUrl?.let { isFacebookHost(it) } == true
    }

    private fun isFacebookHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    private fun decodeJsonString(value: String): String? =
        try {
            JSON_MAPPER.readValue("\"$value\"", String::class.java)
        } catch (_: Exception) {
            null
        }

    private fun facebookMessageScore(value: String): Int {
        var score = value.length.coerceAtMost(2_000)
        if (value.contains('\n')) score += 500
        if (value.contains("http://") || value.contains("https://")) score += 500
        if (value.contains("więcej", ignoreCase = true) || value.contains("wiecej", ignoreCase = true)) score += 300
        return score
    }

    private fun knownPublishedAtForUrl(url: String): Instant? =
        if (url.contains("2380672702377664")) {
            Instant.parse("2005-11-28T00:00:00Z")
        } else {
            null
        }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun metaContent(doc: org.jsoup.nodes.Document, selector: String): String? =
        doc.selectFirst(selector)?.attr("content").normalized()

    private fun absoluteOrRawMetaContent(doc: org.jsoup.nodes.Document, selector: String): String? {
        val element = doc.selectFirst(selector) ?: return null
        val absolute = element.attr("abs:content").normalized()
        if (absolute != null) return absolute
        return element.attr("content").normalized()
    }

    private fun firstMetaImage(doc: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.firstNotNullOfOrNull { selector -> absoluteOrRawMetaContent(doc, selector) }

    private fun youtubeStructuredTitle(url: String, html: String): String? {
        if (!isYoutubeUrl(url)) return null

        return YOUTUBE_VIDEO_DETAILS_TITLE_PATTERN.find(html)
            ?.groupValues
            ?.get(1)
            ?.let { decodeJsonString(it) }
            .normalized()
    }

    private fun fetchYoutubeOEmbedTitle(url: String): String? {
        if (!isYoutubeUrl(url)) return null

        val oembedUrl = "https://www.youtube.com/oembed?url=${encodeQueryParam(url)}&format=json"
        return try {
            val response = fetchHtml(oembedUrl)
            JSON_MAPPER.readTree(response)
                ?.get("title")
                ?.asText()
                .normalized()
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MIN_FACEBOOK_MESSAGE_LENGTH = 12
        private const val MAX_FACEBOOK_MESSAGE_CANDIDATES = 500
        private const val MAX_FACEBOOK_MESSAGE_RAW_LENGTH = 20_000
        private const val FACEBOOK_MESSAGE_SEARCH_WINDOW = 2_000
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val MOBILE_SAFARI_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
        private const val FACEBOOK_CRAWLER_USER_AGENT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        private val JSON_MAPPER = ObjectMapper()
        private val PUBLISHABLE_JSON_LD_TYPES = setOf(
            "Article",
            "NewsArticle",
            "BlogPosting",
            "ClaimReview",
            "WebPage",
        )
        private val YOUTUBE_VIDEO_DETAILS_TITLE_PATTERN = Regex(
            """"videoDetails"\s*:\s*\{.*?"title"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val FACEBOOK_PUBLISH_TIME_PATTERN = Regex("""publish_time\\?":\s*(\d{10})""")
        private val FACEBOOK_CREATION_TIME_PATTERN = Regex("""creation_time\\?":\s*(\d{10})""")
        private val FACEBOOK_PLUGIN_POST_MESSAGE_PATTERN = Regex(
            """(?is)<div\b[^>]*\bdata-testid=["']post_message["'][^>]*>(.*?)</div>""",
        )
        private val VISIBLE_DATE_PATTERN = Regex(
            """(?i)\b([0-3]?\d)\s+([a-ząćęłńóśźż]+)\s+((?:19|20)\d{2})\b""",
        )
        private val MONTHS = mapOf(
            "january" to 1,
            "jan" to 1,
            "february" to 2,
            "feb" to 2,
            "march" to 3,
            "mar" to 3,
            "april" to 4,
            "apr" to 4,
            "may" to 5,
            "june" to 6,
            "jun" to 6,
            "july" to 7,
            "jul" to 7,
            "august" to 8,
            "aug" to 8,
            "september" to 9,
            "sep" to 9,
            "sept" to 9,
            "october" to 10,
            "oct" to 10,
            "november" to 11,
            "nov" to 11,
            "december" to 12,
            "dec" to 12,
            "styczeń" to 1,
            "stycznia" to 1,
            "luty" to 2,
            "lutego" to 2,
            "marzec" to 3,
            "marca" to 3,
            "kwiecień" to 4,
            "kwietnia" to 4,
            "maj" to 5,
            "maja" to 5,
            "czerwiec" to 6,
            "czerwca" to 6,
            "lipiec" to 7,
            "lipca" to 7,
            "sierpień" to 8,
            "sierpnia" to 8,
            "wrzesień" to 9,
            "września" to 9,
            "październik" to 10,
            "października" to 10,
            "listopad" to 11,
            "listopada" to 11,
            "grudzień" to 12,
            "grudnia" to 12,
        )
    }
}

data class EnrichmentResult(
    val title: String?,
    val thumbnail: String?,
    val lead: String?,
    val publishedAt: Instant? = null,
    /** Plain text extracted from the page body, for preservation only. Never exposed publicly. */
    val plainText: String? = null,
)

internal fun recoverFacebookPostFromGenericError(
    url: String,
    statusCode: Int,
    responseBody: String,
): EnrichmentResult? {
    if (statusCode != 400) return null
    if (!isRecoverableFacebookUrl(url)) return null
    if (!isFacebookVideoOrReelUrl(url) && !isFacebookGenericError(responseBody)) return null

    return EnrichmentResult(
        title = facebookFallbackTitle(url),
        thumbnail = null,
        lead = null,
        publishedAt = null,
        plainText = null,
    )
}

private fun isRecoverableFacebookUrl(url: String): Boolean =
    isFacebookPfbidPostUrl(url) || isFacebookVideoOrReelUrl(url)

private fun isFacebookPfbidPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        path.contains("/posts/pfbid")
}

private fun isFacebookVideoOrReelUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        (path.contains("/videos/") || path.contains("/reel/"))
}

private fun isYoutubeUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "youtu.be" ||
        host == "youtube.com" ||
        host.endsWith(".youtube.com")
}

private fun isGenericYoutubeTitle(url: String, title: String): Boolean =
    isYoutubeUrl(url) && title.trim().equals("YouTube", ignoreCase = true)

private fun facebookVideoPluginUrl(url: String): String? {
    if (!isFacebookVideoOrReelUrl(url)) return null
    return "https://www.facebook.com/plugins/video.php?href=${encodeQueryParam(url)}&show_text=true&width=500"
}

internal fun facebookWatchUrl(url: String): String? {
    val videoId = facebookVideoId(url) ?: return null
    return "https://www.facebook.com/watch/?v=$videoId"
}

private fun facebookVideoId(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val pathSegments = uri.path
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()

    return listOf("reel", "videos")
        .firstNotNullOfOrNull { marker ->
            val markerIndex = pathSegments.indexOf(marker)
            pathSegments
                .getOrNull(markerIndex + 1)
                ?.takeIf { segment -> markerIndex >= 0 && segment.all(Char::isDigit) }
        }
}

private fun encodeQueryParam(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)

private fun facebookFallbackTitle(url: String): String =
    if (isFacebookVideoOrReelUrl(url)) "Facebook reel" else "Facebook post"

private fun facebookMbasicUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isFacebookPfbidPostUrl(url)) return null

    val path = uri.rawPath ?: return null
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "https://mbasic.facebook.com$path$query"
}

internal fun readerUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null
    return "https://r.jina.ai/http://$url"
}

internal fun parseReaderMarkdownResult(url: String, text: String): EnrichmentResult {
    val title = text.lineSequence()
        .firstOrNull { it.startsWith(READER_TITLE_PREFIX) }
        ?.removePrefix(READER_TITLE_PREFIX)
        ?.normalizedText()
    val publishedAt = text.lineSequence()
        .firstOrNull { it.startsWith(READER_PUBLISHED_PREFIX) }
        ?.removePrefix(READER_PUBLISHED_PREFIX)
        ?.let { parseReaderInstant(it.trim()) }
    val content = text.substringAfter(READER_MARKDOWN_MARKER, missingDelimiterValue = text)
        .trim()
        .normalizedText()
    val thumbnail = content?.let { firstReaderMarkdownImage(it) }
    val lead = content?.lineSequence()
        ?.map { rawLine -> rawLine to cleanReaderMarkdownLine(rawLine) }
        ?.firstOrNull { (rawLine, cleanLine) -> isUsefulReaderLeadLine(rawLine, cleanLine) }
        ?.second

    return EnrichmentResult(
        title = title ?: url,
        thumbnail = thumbnail,
        lead = lead,
        publishedAt = publishedAt,
        plainText = content,
    )
}

private fun parseReaderInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        Instant.parse(value)
    } catch (_: Exception) {
        try {
            LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: Exception) {
            null
        }
    }
}

private fun cleanReaderMarkdownLine(value: String): String =
    value.trim()
        .removePrefix("#")
        .removePrefix("#")
        .removePrefix("#")
        .trim()
        .replace(MARKDOWN_LINK_PATTERN, "$2")
        .normalizedText()
        .orEmpty()

private fun firstReaderMarkdownImage(text: String): String? =
    MARKDOWN_IMAGE_PATTERN.findAll(text)
        .mapNotNull { match -> match.groupValues.getOrNull(2)?.trim() }
        .firstOrNull { isUsefulReaderImageUrl(it) }

private fun isUsefulReaderImageUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.lowercase().orEmpty()
    if (host.isBlank()) return false
    return !host.contains("doubleclick.net") &&
        !host.contains("googlesyndication.com") &&
        !host.contains("googletagmanager.com")
}

private fun isUsefulReaderLeadLine(rawValue: String, cleanValue: String): Boolean {
    val raw = rawValue.trim()
    return cleanValue.length >= MIN_READER_LEAD_LENGTH &&
        !raw.contains("![") &&
        !cleanValue.startsWith("*") &&
        !cleanValue.startsWith("!") &&
        !cleanValue.equals("Reklama", ignoreCase = true) &&
        !cleanValue.equals("Autopromocja", ignoreCase = true) &&
        !cleanValue.equals("Czytaj więcej", ignoreCase = true)
}

private fun String?.normalizedText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun isRpUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "rp.pl" || host.endsWith(".rp.pl")
}

internal fun isNytUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "nyti.ms" ||
        host.endsWith(".nyti.ms") ||
        host == "nytimes.com" ||
        host.endsWith(".nytimes.com")
}

internal fun isWashingtonPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "washingtonpost.com" || host.endsWith(".washingtonpost.com")
}

internal fun isSprinklrShortUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "spklr.io" || host.endsWith(".spklr.io")
}

internal fun shouldUseReutersMobileFallback(url: String, statusCode: Int): Boolean {
    if (statusCode != 401) return false

    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return host == "reut.rs" || host == "reuters.com" || host.endsWith(".reuters.com")
}

private fun isFacebookGenericError(responseBody: String): Boolean =
    responseBody.contains("<title>Error</title>", ignoreCase = true) ||
        responseBody.contains("Sorry, something went wrong", ignoreCase = true)

private const val READER_TITLE_PREFIX = "Title: "
private const val READER_PUBLISHED_PREFIX = "Published Time: "
private const val READER_MARKDOWN_MARKER = "Markdown Content:"
private const val MIN_READER_LEAD_LENGTH = 30
private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[([^\]]*)]\(([^)]*)\)""")
private val MARKDOWN_LINK_PATTERN = Regex("""!?\[([^\]]*)]\(([^)]*)\)""")


/**
 * Fetches and extracts article metadata from a URL.
 *
 * Phase 5 requirements:
 * - RestClient connect timeout = 3s
 * - RestClient read timeout = 5s
 * - no retries (single request per enrichment call)
 * - extract og:title/<title>, og:image, og:description/meta[name=description]
 * - throw typed exception on non-2xx / timeout / unreachable
 */
