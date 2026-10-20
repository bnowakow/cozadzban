// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.jsoup.nodes.Document
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPublicationQueue
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A server-rendered public page so a story is useful to readers and crawlers on the first request. */
@RestController
class StoryPageController(
    private val articles: ArticleRepository,
    private val facebookPublications: FacebookPublicationQueue? = null,
) {

    @GetMapping("/stories/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    fun story(@PathVariable id: Long): ResponseEntity<String> {
        val article = articles.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
            .body(render(article, facebookPublications?.permalink(id)))
    }

    private fun render(article: Article, facebookPostUrl: String?): String {
        val language = article.language.trim().lowercase().takeIf { it.matches(Regex("[a-z]{2,3}(-[a-z]{2,3})?")) } ?: "en"
        val polish = language.startsWith("pl")
        val title = article.title?.trim()?.takeIf { it.isNotEmpty() } ?: article.url
        val description = sequenceOf(article.lead, article.aiSummary, article.quote, article.title)
            .filterNotNull().map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() }?.take(300)
            ?: if (polish) "Historia w serwisie Co za dzban." else "A story on Co za dzban."
        val canonical = "https://cozadzban.pl/stories/${article.id}"
        val date = article.publishedAt ?: article.createdAt ?: Instant.EPOCH
        val dateLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(date)
        val doc = Document.createShell("")
        doc.outputSettings().prettyPrint(false)
        doc.selectFirst("html")!!.attr("lang", language)
        val head = doc.head()
        head.appendElement("meta").attr("charset", "UTF-8")
        head.appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        head.appendElement("title").text("$title | Co za dzban")
        head.appendElement("meta").attr("name", "description").attr("content", description)
        head.appendElement("link").attr("rel", "canonical").attr("href", canonical)
        head.appendElement("link").attr("rel", "icon").attr("href", "/favicon.png")
        head.appendElement("meta").attr("property", "og:type").attr("content", "article")
        head.appendElement("meta").attr("property", "og:title").attr("content", title)
        head.appendElement("meta").attr("property", "og:description").attr("content", description)
        head.appendElement("meta").attr("property", "og:url").attr("content", canonical)
        head.appendElement("meta").attr("property", "og:locale").attr("content", if (polish) "pl_PL" else "en_US")
        head.appendElement("meta").attr("property", "article:published_time").attr("content", date.toString())
        article.thumbnail?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
            head.appendElement("meta").attr("property", "og:image").attr("content", it)
        }
        head.appendElement("style").appendText(
            """
            body{margin:0;background:#f3f4f6;color:#202124;font:16px/1.6 system-ui,sans-serif}
            header{background:#fff;border-bottom:1px solid #ddd;padding:1rem max(1rem,calc((100vw - 44rem)/2))}
            header a{color:inherit;font-weight:800;text-decoration:none}
            main{box-sizing:border-box;max-width:44rem;margin:2rem auto;padding:2rem;background:#fff;border-radius:8px}
            h1{font-size:clamp(1.7rem,5vw,2.5rem);line-height:1.2;margin:.75rem 0 1.5rem}
            img{display:block;max-width:100%;height:auto;border-radius:6px}
            .source{color:#666;font-size:.9rem}blockquote{border-left:4px solid #f36b32;margin:1.5rem 0;padding-left:1rem}
            .original{display:inline-block;margin-top:1rem;color:#a83d13;font-weight:700}
            @media(max-width:46rem){main{margin:1rem;padding:1.25rem}}
            """.trimIndent(),
        )
        val body = doc.body()
        body.appendElement("header").appendElement("a").attr("href", "/").text("Co za dzban")
        val main = body.appendElement("main")
        main.appendElement("p").attr("class", "source").appendElement("time")
            .attr("datetime", date.toString()).text(dateLabel)
        main.appendElement("h1").text(title)
        article.thumbnail?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let {
            main.appendElement("img").attr("src", it).attr("alt", title)
        }
        article.lead?.trim()?.takeIf { it.isNotEmpty() }?.let { main.appendElement("p").text(it) }
        article.quote?.trim()?.takeIf { it.isNotEmpty() }?.let { main.appendElement("blockquote").text(it) }
        article.aiSummary?.trim()?.takeIf { it.isNotEmpty() }?.let { main.appendElement("p").text(it) }
        main.appendElement("a").attr("class", "original").attr("href", article.url)
            .attr("rel", "noopener noreferrer").text(if (polish) "Czytaj oryginalną publikację" else "Read the original story")
        facebookPostUrl?.takeIf { it.startsWith("https://www.facebook.com/") }?.let { url ->
            main.appendElement("p").appendElement("a").attr("href", url)
                .attr("rel", "noopener noreferrer").text(if (polish) "Skomentuj na Facebooku" else "Comment on Facebook")
        }
        return "<!doctype html>\n${doc.outerHtml()}"
    }
}
