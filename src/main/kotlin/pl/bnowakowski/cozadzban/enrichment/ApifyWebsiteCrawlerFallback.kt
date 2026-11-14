// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** The final, browser-based option. One start URL, one page, and a capped actor charge. */
internal object ApifyWebsiteCrawlerFallback {
    private val log = LoggerFactory.getLogger(ApifyWebsiteCrawlerFallback::class.java)
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun fetch(url: String, token: String, apiBaseUrl: String): EnrichmentResult? {
        if (token.isBlank() || !ApifyWebFetchFallback.validPublicUrl(url)) return null
        return try {
            val input = mapOf(
                "startUrls" to listOf(mapOf("url" to url)),
                "crawlerType" to "playwright:firefox",
                "maxCrawlDepth" to 0,
                "maxCrawlPages" to 1,
                "maxResults" to 1,
                "maxRequestRetries" to 1,
                "maxSessionRotations" to 1,
                "requestTimeoutSecs" to 45,
                "dynamicContentWaitSecs" to 3,
                "saveMarkdown" to false,
                "summarize" to false,
                "proxyConfiguration" to mapOf("useApifyProxy" to true, "apifyProxyGroups" to listOf("UNBLOCKER")),
            )
            val endpoint = URI("${apiBaseUrl.trimEnd('/')}/v2/actors/apify~website-content-crawler/" +
                "run-sync-get-dataset-items?timeout=100&maxTotalChargeUsd=0.05")
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(110))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(input)))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                log.info("Apify Website Content Crawler failed for '{}'; status={}", url, response.statusCode())
                return null
            }
            parseItems(url, response.body()).also {
                if (it == null) log.info("Apify Website Content Crawler returned no usable article for '{}'", url)
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (ex: Exception) {
            log.info("Apify Website Content Crawler unavailable for '{}'; error={}", url, ex.javaClass.simpleName)
            null
        }
    }

    internal fun parseItems(requestedUrl: String, body: String): EnrichmentResult? {
        if (body.length > 3_000_000) return null
        val items = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        if (!items.isArray || items.size() != 1) return null
        return parseItem(requestedUrl, items[0])
    }

    private fun parseItem(requestedUrl: String, item: JsonNode): EnrichmentResult? {
        val loadedUrl = item.path("crawl").path("loadedUrl").asText("")
            .ifBlank { item.path("url").asText("") }
        if (!ApifyWebFetchFallback.sameArticleSite(requestedUrl, loadedUrl)) return null
        val crawlStatus = item.path("crawl").path("httpStatusCode")
        if (crawlStatus.isNumber && crawlStatus.asInt() !in 200..299) return null
        val metadata = item.path("metadata")
        val canonical = metadata.path("canonicalUrl").asText("")
        if (canonical.isNotBlank() && !ApifyWebFetchFallback.sameArticleSite(loadedUrl, canonical)) return null
        val title = metadata.path("title").asText("").trim()
        if (title.isBlank() || ApifyWebFetchFallback.isBlockTitle(title)) return null
        val text = item.path("text").asText("").trim()
        if (text.length < 100) return null
        val lead = metadata.path("description").asText("").trim().ifBlank { text.take(350) }
        if (lead.isBlank()) return null
        val thumbnail = metadata.path("openGraph").takeIf { it.isArray }
            ?.firstOrNull { it.path("property").asText("").equals("og:image", ignoreCase = true) }
            ?.path("content")?.asText("")?.takeIf { image ->
                runCatching { URI(image).scheme == "https" }.getOrDefault(false)
            }
        val publishedAt = metadata.path("datePublished").asText("").takeIf(String::isNotBlank)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return EnrichmentResult(
            title = Jsoup.parse(title).text(),
            lead = Jsoup.parse(lead).text().take(1_000),
            thumbnail = thumbnail,
            publishedAt = publishedAt,
            plainText = text.take(100_000),
        )
    }
}
