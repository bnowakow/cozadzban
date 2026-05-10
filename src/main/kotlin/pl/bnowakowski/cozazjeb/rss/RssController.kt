// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.rss

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.Locale

@RestController
class RssController(
    private val articleRepository: ArticleRepository,
    restClientBuilder: RestClient.Builder,
    @Value("\${app.build.timestamp:}") buildTimestampRaw: String,
) {

    private val imageRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(IMAGE_CONNECT_TIMEOUT_MS)
                setReadTimeout(IMAGE_READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, IMAGE_FETCH_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .build()
    private val buildTimestampRfc822: String = parseBuildTimestamp(buildTimestampRaw)

    @GetMapping("/rss", produces = ["application/rss+xml"])
    fun getRssFeed(@RequestParam(required = false) lang: String?): ResponseEntity<String> {
        val filter = lang?.trim()?.takeIf { it.isNotEmpty() }
        val channelLanguage = filter ?: "en"
        val articles = articleRepository.findForRss(filter)

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<rss version=\"2.0\" xmlns:media=\"http://search.yahoo.com/mrss/\"><channel>")
            append("<title>Co za zjeb</title>")
            append("<link>https://cozazjeb.pl</link>")
            append("<description>fucked up news</description>")
            append("<language>${xmlEscape(channelLanguage)}</language>")
            append("<generator>cozazjeb.pl</generator>")
            append("<lastBuildDate>$buildTimestampRfc822</lastBuildDate>")

            articles.forEach { appendRssItem(it) }

            append("</channel></rss>")
        }

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/rss+xml"))
            .body(xml)
    }

    private fun StringBuilder.appendRssItem(article: Article) {
        val title = article.title ?: article.url
        val description = article.lead.orEmpty()
        val guid = article.id?.toString() ?: article.url
        // BR-07: use publishedAt when present, fall back to createdAt
        val pubDate = (article.publishedAt ?: article.createdAt ?: Instant.EPOCH)
            .atOffset(ZoneOffset.UTC).format(RFC_822_FORMATTER)

        append("<item>")
        append("<title>${xmlEscape(title)}</title>")
        append("<link>${xmlEscape(article.url)}</link>")
        append("<description>${xmlEscape(description)}</description>")
        article.thumbnail
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { article.id != null }
            ?.let { thumbnail ->
                val escapedThumbnail = xmlEscape("$SITE_URL/rss/image/${article.id}")
                append("<media:thumbnail url=\"$escapedThumbnail\" />")
                append("<media:content url=\"$escapedThumbnail\" medium=\"image\" />")
            }
        append("<guid isPermaLink=\"false\">${xmlEscape(guid)}</guid>")
        append("<pubDate>$pubDate</pubDate>")
        append("</item>")
    }

    @GetMapping("/rss/image/{id}")
    fun getRssImage(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val article = articleRepository.findById(id)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Article $id not found") }
        val thumbnail = article.thumbnail?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Article $id has no thumbnail")

        val response = try {
            imageRestClient.get()
                .uri(thumbnail)
                .retrieve()
                .toEntity(ByteArray::class.java)
        } catch (ex: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Thumbnail could not be fetched", ex)
        }

        val contentType = response.headers.contentType
        if (contentType == null || contentType.type != "image" || response.body == null) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Thumbnail response was not an image")
        }

        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePublic())
            .body(response.body!!)
    }

    private fun parseBuildTimestamp(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) {
            throw IllegalStateException("app.build.timestamp is missing or blank")
        }
        val instant = try {
            Instant.parse(value)
        } catch (ex: Exception) {
            throw IllegalStateException("app.build.timestamp '$value' is not valid ISO-8601 UTC", ex)
        }
        return instant.atOffset(ZoneOffset.UTC).format(RFC_822_FORMATTER)
    }

    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    companion object {
        private const val SITE_URL = "https://cozazjeb.pl"
        private const val IMAGE_CONNECT_TIMEOUT_MS = 3_000
        private const val IMAGE_READ_TIMEOUT_MS = 5_000
        private const val IMAGE_FETCH_USER_AGENT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        private val RFC_822_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)
    }
}
