// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.jsoup.nodes.Document
import org.jsoup.nodes.DataNode
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPublicationQueue
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A server-rendered public page so a story is useful to readers and crawlers on the first request. */
@RestController
class StoryPageController(
    private val articles: ArticleRepository,
    private val facebookPublications: FacebookPublicationQueue? = null,
    private val viewCounter: StoryViewCounter? = null,
    private val articleImages: FacebookArticleImageGenerationRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/stories/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    fun story(@PathVariable id: Long): ResponseEntity<String> {
        val article = articles.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val html = render(article, facebookPublications?.permalink(id))
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        if (request != null && StoryViewEligibility.shouldCount(request)) {
            try {
                viewCounter?.record(id)
            } catch (ex: Exception) {
                log.warn("Could not count story view for article {}: {}", id, ex.javaClass.simpleName)
            }
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
            .body(html)
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
        val sourceUri = runCatching { URI(article.url) }.getOrNull()
        val sourceName = sourceUri?.host?.removePrefix("www.")?.ifBlank { null } ?: article.url
        val favicon = article.favicon?.trim()?.takeIf { it.isNotEmpty() }
            ?: sourceUri?.takeIf { it.scheme in setOf("http", "https") && it.host != null }
                ?.let { "${it.scheme}://${it.host}/favicon.ico" }
        val thumbnail = article.thumbnail?.trim()
            ?.takeIf { FacebookArticleImageGenerationRepository.hasUsableSourceThumbnail(it) }
        val generatedImage = article.id?.let { articleImages?.status(it) == "GENERATED" } == true
        val storyImage = if (generatedImage) "/generated-images/${article.id}.png"
            else thumbnail?.let { "/rss/image/${article.id}" }
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
        storyImage?.let {
            head.appendElement("meta").attr("property", "og:image").attr("content", "https://cozadzban.pl$it")
        }
        head.appendElement("script").appendChild(DataNode(
            """
            (function() {
              var saved = null;
              try { saved = localStorage.getItem('cozadzban-theme'); } catch (ignored) {}
              var dark = saved === 'dark' ||
                (saved !== 'light' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
              if (dark) document.documentElement.setAttribute('theme', 'dark');
            })();
            """.trimIndent(),
        ))
        head.appendElement("style").appendText(
            """
            :root{color-scheme:light;--page:#f3f4f6;--surface:#fff;--text:#202124;--border:#ddd;--muted:#666;--link:#a83d13;--icon-bg:#ff6b2a;--icon-text:#190b04}
            html[theme=dark]{color-scheme:dark;--page:#0f1114;--surface:#181c21;--text:#f2f4f7;--border:#313942;--muted:#aeb6c0;--link:#ff9a64}
            body{margin:0;background:var(--page);color:var(--text);font:16px/1.6 system-ui,sans-serif}
            header{background:var(--surface);border-bottom:1px solid var(--border);padding:1rem max(1rem,calc((100vw - 44rem)/2))}
            header a{color:inherit;font-weight:800;text-decoration:none}
            main{box-sizing:border-box;max-width:44rem;margin:2rem auto;padding:2rem;background:var(--surface);border-radius:8px}
            h1{font-size:clamp(1.7rem,5vw,2.5rem);line-height:1.2;margin:.75rem 0 1.5rem}
            img{display:block;max-width:100%;height:auto;border-radius:6px}
            .image-credit{color:var(--muted);font-size:.85rem;margin:.35rem 0 1rem}
            .back{display:inline-block;margin-bottom:1rem;text-decoration:none;font-weight:700}
            .story-meta{display:flex;align-items:center;gap:.55rem;color:var(--muted);font-size:.9rem}
            .source-icon{position:relative;display:inline-flex;align-items:center;justify-content:center;flex:none;width:1.35rem;height:1.35rem;overflow:hidden;border-radius:50%;background:var(--icon-bg);color:var(--icon-text);font-size:.75rem;font-weight:800}
            .source-icon img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;border-radius:50%}
            .source-name{color:var(--text);font-weight:700}
            blockquote{border-left:4px solid #f36b32;margin:1.5rem 0;padding-left:1rem}
            main a{color:var(--link)}.original{display:inline-block;margin-top:1rem;font-weight:700}
            @media(max-width:46rem){main{margin:1rem;padding:1.25rem}}
            """.trimIndent(),
        )
        val body = doc.body()
        body.appendElement("header").appendElement("a").attr("href", "/").text("Co za dzban")
        val main = body.appendElement("main")
        main.appendElement("a").attr("class", "back").attr("href", "/")
            .attr("onclick", "if(document.referrer && document.referrer !== location.href && document.referrer.startsWith(location.origin + '/') && history.length > 1){history.back();return false}")
            .text(if (polish) "← Wróć" else "← Back")
        val meta = main.appendElement("p").attr("class", "story-meta")
        val icon = meta.appendElement("span").attr("class", "source-icon")
        icon.appendElement("span").text(sourceName.firstOrNull()?.uppercaseChar()?.toString() ?: "?")
        favicon?.let { icon.appendElement("img").attr("src", it).attr("alt", "").attr("onerror", "this.remove()") }
        meta.appendElement("span").attr("class", "source-name").text(sourceName)
        meta.appendElement("span").attr("aria-hidden", "true").text("•")
        meta.appendElement("time")
            .attr("datetime", date.toString()).text(dateLabel)
        main.appendElement("h1").text(title)
        storyImage?.let {
            main.appendElement("img").attr("src", it).attr("alt", title)
                .attr("onerror", "this.onerror=null;this.src='/cozadzban-logo.png'")
            if (generatedImage) {
                main.appendElement("p").attr("class", "image-credit")
                    .text(if (polish) "Ilustracja wygenerowana przez AI" else "AI-generated illustration")
            }
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
