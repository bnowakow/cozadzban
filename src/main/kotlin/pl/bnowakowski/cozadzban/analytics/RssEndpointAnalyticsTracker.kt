// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.analytics

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.util.UUID

@Component
class RssEndpointAnalyticsTracker(
    private val analytics: AnalyticsProperties,
    restClientBuilder: RestClient.Builder,
    @Value("\${app.analytics.google-measurement-protocol-endpoint:https://www.google-analytics.com/mp/collect}")
    private val googleMeasurementProtocolEndpoint: String,
    @Value("\${app.analytics.statcounter-counter-base-url:https://c.statcounter.com}")
    private val statcounterCounterBaseUrl: String,
) {

    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(ANALYTICS_CONNECT_TIMEOUT_MS)
                setReadTimeout(ANALYTICS_READ_TIMEOUT_MS)
            },
        )
        .build()

    fun recordRssFeedReached(lang: String?) {
        val normalizedLang = lang?.trim()?.takeIf { it.isNotBlank() } ?: "all"
        recordGoogleRssFeedReached(normalizedLang)
        recordStatcounterRssFeedReached()
    }

    private fun recordGoogleRssFeedReached(lang: String) {
        if (!analytics.isGoogleServerEventEnabled) return

        val uri = UriComponentsBuilder
            .fromUriString(googleMeasurementProtocolEndpoint.trim())
            .queryParam("measurement_id", analytics.googleMeasurementId.trim())
            .queryParam("api_secret", analytics.googleApiSecret.trim())
            .build()
            .toUri()

        val sessionId = Instant.now().epochSecond
        val payload = mapOf(
            "client_id" to UUID.randomUUID().toString(),
            "consent" to mapOf(
                "ad_user_data" to "DENIED",
                "ad_personalization" to "DENIED",
            ),
            "events" to listOf(
                mapOf(
                    "name" to RSS_EVENT_NAME,
                    "params" to mapOf(
                        "page_location" to rssPageLocation(lang),
                        "page_title" to "Co za dzban RSS",
                        "rss_lang" to lang.take(MAX_GA_PARAM_VALUE_LENGTH),
                        "session_id" to sessionId,
                        "engagement_time_msec" to 1,
                    ),
                ),
            ),
        )

        try {
            restClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
        } catch (ex: Exception) {
            LOG.warn("Failed to emit Google Analytics RSS event", ex)
        }
    }

    private fun recordStatcounterRssFeedReached() {
        if (!analytics.isStatcounterEnabled) return

        val uri = UriComponentsBuilder
            .fromUriString(statcounterCounterBaseUrl.trim().removeSuffix("/"))
            .pathSegment(
                analytics.statcounterProjectId.trim(),
                "0",
                analytics.statcounterSecurityId.trim(),
                "1",
            )
            .build()
            .toUri()

        try {
            restClient.get()
                .uri(uri)
                .header(HttpHeaders.REFERER, "$SITE_URL/rss")
                .header(HttpHeaders.USER_AGENT, "cozadzban-rss-analytics/1.0")
                .retrieve()
                .toBodilessEntity()
        } catch (ex: Exception) {
            LOG.warn("Failed to emit StatCounter RSS hit", ex)
        }
    }

    private fun rssPageLocation(lang: String): String =
        if (lang == "all") {
            "$SITE_URL/rss"
        } else {
            UriComponentsBuilder
                .fromUriString("$SITE_URL/rss")
                .queryParam("lang", lang)
                .build()
                .toUriString()
                .take(MAX_GA_PARAM_VALUE_LENGTH)
        }

    companion object {
        private const val SITE_URL = "https://cozadzban.pl"
        private const val RSS_EVENT_NAME = "rss_feed_reached"
        private const val ANALYTICS_CONNECT_TIMEOUT_MS = 500
        private const val ANALYTICS_READ_TIMEOUT_MS = 500
        private const val MAX_GA_PARAM_VALUE_LENGTH = 100
        private val LOG = LoggerFactory.getLogger(RssEndpointAnalyticsTracker::class.java)
    }
}
