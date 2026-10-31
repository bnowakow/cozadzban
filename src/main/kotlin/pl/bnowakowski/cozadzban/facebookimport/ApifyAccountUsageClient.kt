// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Apify account usage for the current monthly billing cycle, from GET /v2/users/me/limits. */
data class ApifyAccountUsage(
    val limitUsd: BigDecimal,
    val usedUsd: BigDecimal,
    val cycleStartAt: Instant?,
    val cycleEndAt: Instant?,
) {
    val remainingUsd: BigDecimal get() = (limitUsd - usedUsd).max(BigDecimal.ZERO)
}

/**
 * Reads how much Apify usage is left this billing cycle. Returns null when no token is configured
 * or Apify cannot be reached; callers then carry on without the check. Results are cached briefly
 * so admin pages don't call Apify on every render.
 */
@Component
class ApifyAccountUsageClient(
    private val properties: FacebookImportProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    @Volatile private var cached: Pair<Instant, ApifyAccountUsage>? = null

    /** Cached for [CACHE_TTL]; use [fresh] to bypass the cache before deciding whether to run. */
    fun current(): ApifyAccountUsage? {
        cached?.let { (fetchedAt, usage) ->
            if (Duration.between(fetchedAt, clock.instant()) < CACHE_TTL) return usage
        }
        return fresh()
    }

    fun fresh(): ApifyAccountUsage? {
        val token = properties.apify.apiToken.trim()
        if (token.isEmpty()) return null
        return runCatching {
            val body = client().get()
                .uri(URI.create("${properties.apify.baseUrl.trimEnd('/')}/v2/users/me/limits"))
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .body<String>()
                ?: return@runCatching null
            parse(body)
        }.onFailure { ex ->
            logger.warn("Could not read Apify account usage: {}", ex.message ?: ex.javaClass.simpleName)
        }.getOrNull()?.also { cached = clock.instant() to it }
    }

    internal fun parse(body: String): ApifyAccountUsage? {
        val data = objectMapper.readTree(body).path("data")
        val limit = data.path("limits").path("maxMonthlyUsageUsd")
        val used = data.path("current").path("monthlyUsageUsd")
        if (!limit.isNumber || !used.isNumber) return null
        val cycle = data.path("monthlyUsageCycle")
        return ApifyAccountUsage(
            limitUsd = limit.decimalValue(),
            usedUsd = used.decimalValue(),
            cycleStartAt = cycle.path("startAt").asText("").takeIf { it.isNotBlank() }?.let(Instant::parse),
            cycleEndAt = cycle.path("endAt").asText("").takeIf { it.isNotBlank() }?.let(Instant::parse),
        )
    }

    private fun client(): RestClient {
        // Short timeouts: admin pages read this while rendering.
        val requestFactory = JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
        )
        requestFactory.setReadTimeout(REQUEST_TIMEOUT)
        return RestClient.builder().requestFactory(requestFactory).build()
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(5)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
