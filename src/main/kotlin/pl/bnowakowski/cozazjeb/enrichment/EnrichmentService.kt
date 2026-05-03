// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.jsoup.Jsoup
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.net.ConnectException
import java.net.SocketTimeoutException

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
@Service
class EnrichmentService(restClientBuilder: RestClient.Builder) {

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

        return EnrichmentResult(
            title = title,
            thumbnail = thumbnail,
            lead = lead,
        )
    }

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
    }
}

data class EnrichmentResult(
    val title: String?,
    val thumbnail: String?,
    val lead: String?,
)
