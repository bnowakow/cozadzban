// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
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
        .build()

    fun enrich(url: String): EnrichmentResult {
        val html = try {
            restClient.get()
                .uri(url)
                .retrieve()
                .body(String::class.java)
                ?: ""
        } catch (ex: RestClientResponseException) {
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
        val publishedAt = parsePublishedAt(doc)

        return EnrichmentResult(
            title = title,
            thumbnail = thumbnail,
            lead = lead,
            publishedAt = publishedAt,
        )
    }

    // ── publishedAt extraction (Phase 17, item 48) ────────────────────────────

    private fun parsePublishedAt(doc: org.jsoup.nodes.Document): Instant? {
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
        private val JSON_MAPPER = ObjectMapper()
    }
}

data class EnrichmentResult(
    val title: String?,
    val thumbnail: String?,
    val lead: String?,
    val publishedAt: Instant? = null,
)


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

