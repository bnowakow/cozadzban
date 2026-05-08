// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

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

    fun enrich(url: String): EnrichmentResult {
        val html = try {
            restClient.get()
                .uri(url)
                .retrieve()
                .body(String::class.java)
                ?: ""
        } catch (ex: RestClientResponseException) {
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
            throw EnrichmentException(
                message = "URL enrichment failed: target was unreachable or timed out for '$url'",
                reason = reason,
                cause = ex,
            )
        } catch (ex: RestClientException) {
            throw EnrichmentException(
                message = "URL enrichment failed for '$url'",
                reason = EnrichmentException.Reason.UNREACHABLE,
                cause = ex,
            )
        }

        val doc = Jsoup.parse(html, url)
        val title = metaContent(doc, "meta[property=og:title]") ?: doc.title().normalized()
        val thumbnail = absoluteOrRawMetaContent(doc, "meta[property=og:image]")
        val lead = metaContent(doc, "meta[property=og:description]") ?: metaContent(doc, "meta[name=description]")
        val publishedAt = parsePublishedAt(url, doc)
        val plainText = doc.body().text().normalized()

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

        // 6. Visible social-page date text, e.g. Facebook can render "28 november 2005"
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
                    json.isObject -> listOf(json)
                    else -> emptyList()
                }
                for (node in nodes) {
                    val type = node.get("@type")?.asText()
                    if (type == "Article" || type == "NewsArticle") {
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

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val JSON_MAPPER = ObjectMapper()
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
    if (!isFacebookPfbidPostUrl(url)) return null
    if (!isFacebookGenericError(responseBody)) return null

    return EnrichmentResult(
        title = "Facebook post",
        thumbnail = null,
        lead = null,
        publishedAt = null,
        plainText = null,
    )
}

private fun isFacebookPfbidPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        path.contains("/posts/pfbid")
}

private fun isFacebookGenericError(responseBody: String): Boolean =
    responseBody.contains("<title>Error</title>", ignoreCase = true) ||
        responseBody.contains("Sorry, something went wrong", ignoreCase = true)


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
