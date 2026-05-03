// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.rss

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@RestController
class RssController(
    private val articleRepository: ArticleRepository,
    @Value("\${app.build.timestamp:}") buildTimestampRaw: String,
) {

    private val buildTimestampRfc822: String = parseBuildTimestamp(buildTimestampRaw)

    @GetMapping("/rss", produces = ["application/rss+xml"])
    fun getRssFeed(@RequestParam(required = false) lang: String?): ResponseEntity<String> {
        val filter = lang?.trim()?.takeIf { it.isNotEmpty() }
        val channelLanguage = filter ?: "en"
        val articles = articleRepository.findForRss(filter)

        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<rss version=\"2.0\"><channel>")
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
        val pubDate = article.createdAt?.atOffset(ZoneOffset.UTC)?.format(RFC_822_FORMATTER)
            ?: Instant.EPOCH.atOffset(ZoneOffset.UTC).format(RFC_822_FORMATTER)

        append("<item>")
        append("<title>${xmlEscape(title)}</title>")
        append("<link>${xmlEscape(article.url)}</link>")
        append("<description>${xmlEscape(description)}</description>")
        append("<guid isPermaLink=\"false\">${xmlEscape(guid)}</guid>")
        append("<pubDate>$pubDate</pubDate>")
        append("</item>")
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
        private val RFC_822_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH)
    }
}
