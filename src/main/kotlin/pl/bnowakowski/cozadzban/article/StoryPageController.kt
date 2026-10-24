// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.jsoup.nodes.Document
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPublicationQueue
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import pl.bnowakowski.cozadzban.security.UiPrincipalMapper
import pl.bnowakowski.cozadzban.user.Role
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
    private val uiPrincipalMapper: UiPrincipalMapper? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/stories/{id}", produces = [MediaType.TEXT_HTML_VALUE])
    fun story(@PathVariable id: Long): ResponseEntity<String> {
        val article = articles.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        val authentication = SecurityContextHolder.getContext().authentication
        val signedIn = authentication != null && authentication.isAuthenticated && authentication.principal != "anonymousUser"
        val principal = if (signedIn) uiPrincipalMapper?.resolve(authentication) else null
        val csrf = request?.getAttribute(CsrfToken::class.java.name) as? CsrfToken
            ?: request?.getAttribute("_csrf") as? CsrfToken
        val html = render(article, facebookPublications?.permalink(id), signedIn, principal?.allowlisted == true,
            principal?.role == Role.ADMIN, csrf)
        if (request != null && StoryViewEligibility.shouldCount(request)) {
            try {
                viewCounter?.record(id)
            } catch (ex: Exception) {
                log.warn("Could not count story view for article {}: {}", id, ex.javaClass.simpleName)
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
            .body(html)
    }

    private fun render(
        article: Article,
        facebookPostUrl: String?,
        signedIn: Boolean,
        canAccessUserPages: Boolean,
        isAdmin: Boolean,
        csrf: CsrfToken?,
    ): String {
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
              window.cozadzbanToggleTheme = function() {
                var next = document.documentElement.getAttribute('theme') === 'dark' ? 'light' : 'dark';
                if (next === 'dark') {
                  document.documentElement.setAttribute('theme', 'dark');
                  document.body.setAttribute('theme', 'dark');
                } else {
                  document.documentElement.removeAttribute('theme');
                  document.body.removeAttribute('theme');
                }
                try { localStorage.setItem('cozadzban-theme', next); } catch (ignored) {}
                var menu = document.querySelector('.top-menu');
                if (menu) menu.removeAttribute('open');
              };
              document.addEventListener('click', function(event) {
                var menu = document.querySelector('.top-menu');
                if (menu && !menu.contains(event.target)) menu.removeAttribute('open');
              });
            })();
            """.trimIndent(),
        ))
        head.appendElement("style").appendText(
            """
            :root{color-scheme:light;--page:#eef0f3;--surface:#fff;--text:#15171a;--border:#d4d9df;--muted:#626970;--link:#a83d13;--icon-bg:#ff6b2a;--icon-text:#190b04;--primary:#ff6b2a;--primary-text:#190b04;--menu-hover:#e4e8ed;--menu-shadow:0 1px 2px rgba(15,23,42,.12),0 8px 24px rgba(15,23,42,.08);--site-font:-apple-system,BlinkMacSystemFont,Roboto,'Segoe UI',Helvetica,Arial,sans-serif}
            html[theme=dark]{color-scheme:dark;--page:#0f1114;--surface:#181c21;--text:#f2f4f7;--border:#313942;--muted:#aeb6c0;--link:#ff9a64}
            html[theme=dark]{--menu-hover:#2a3037;--menu-shadow:0 1px 2px rgba(0,0,0,.35),0 10px 28px rgba(0,0,0,.3)}
            body{margin:0;background:var(--page);color:var(--text);font:16px/1.625 var(--site-font);-webkit-font-smoothing:antialiased}
            header{position:fixed;z-index:1000;top:0;left:0;right:0;box-sizing:border-box;height:66px;display:flex;align-items:center;justify-content:space-between;gap:1rem;background:var(--surface);border-bottom:1px solid var(--menu-hover);padding:0 1.5rem;box-shadow:0 2px 10px rgba(15,23,42,.08)}
            html[theme=dark] header{box-shadow:0 2px 14px rgba(0,0,0,.32)}
            .brand{display:inline-flex;align-items:center;gap:1rem;min-width:0;color:inherit;font-size:1.375rem;font-weight:800;text-decoration:none;white-space:nowrap}
            .brand img{display:block;width:46px;height:46px;flex:none;border-radius:50%;object-fit:cover;box-shadow:0 1px 4px rgba(0,0,0,.26)}
            .top-actions{display:flex;align-items:center;gap:1rem;flex:none;padding-right:.25rem}
            .top-actions a,.top-actions button,.top-actions summary{box-sizing:border-box;border:0;border-radius:4px;background:transparent;color:var(--text);cursor:pointer;font:500 1rem/1.25 var(--site-font);text-decoration:none}
            .top-actions .rss{display:inline-flex;align-items:center;justify-content:center;gap:.35rem;height:2rem;min-width:3.75rem;margin:.25rem 0;padding:0 .625rem;font-size:.875rem}
            .top-actions svg{display:block;width:1rem;height:1rem;fill:currentColor}
            .top-actions .auth{display:inline-flex;align-items:center;justify-content:center;height:2.25rem;min-width:4.5rem;margin:.25rem 0;padding:0 .75rem;background:var(--primary);color:var(--primary-text);font-weight:600}
            .top-actions .auth:hover{filter:brightness(.95)}
            .top-actions form{margin:0}
            .top-menu{position:relative}
            .top-menu summary{display:flex;align-items:center;justify-content:center;width:34px;height:34px;margin:0 .125rem;list-style:none}
            .top-menu summary:hover{background:var(--menu-hover)}
            .top-menu summary svg{width:1rem;height:1rem}
            .top-menu summary::-webkit-details-marker{display:none}
            .top-menu-items{position:absolute;top:calc(100% + .65rem);right:0;z-index:1001;display:flex;flex-direction:column;min-width:13rem;padding:.35rem;background:var(--surface);border:1px solid var(--border);border-radius:8px;box-shadow:var(--menu-shadow)}
            .top-menu:not([open]) .top-menu-items{display:none}
            .top-menu-items a,.top-menu-items button{display:flex;align-items:center;gap:.5rem;width:100%;padding:.55rem .7rem;text-align:left;white-space:nowrap}
            .top-menu-items svg{width:1rem;height:1rem;flex:none;color:var(--muted)}
            .top-menu-items a:hover,.top-menu-items button:hover{background:var(--menu-hover)}
            .top-menu-items hr{width:100%;border:0;border-top:1px solid var(--border)}
            .top-menu-section{padding:.35rem .7rem;color:var(--muted);font-size:.875rem;font-weight:700}
            .top-actions a:focus-visible,.top-actions button:focus-visible,.top-actions summary:focus-visible{outline:2px solid #ff6b2a;outline-offset:2px}
            main{box-sizing:border-box;max-width:44rem;margin:calc(66px + 2rem) auto 2rem;padding:2rem;background:var(--surface);border-radius:8px}
            h1{font-size:clamp(1.7rem,5vw,2.5rem);line-height:1.2;margin:.75rem 0 1.5rem}
            img{display:block;max-width:100%;height:auto;border-radius:6px}
            .image-credit{color:var(--muted);font-size:.85rem;margin:.35rem 0 1rem}
            .back{display:inline-block;margin-bottom:1rem;text-decoration:none;font-weight:700}
            .story-meta{display:flex;align-items:center;gap:.55rem;color:var(--muted);font-size:.9rem}
            .source-icon{position:relative;display:inline-flex;align-items:center;justify-content:center;flex:none;width:1.35rem;height:1.35rem;overflow:hidden;border-radius:50%;background:var(--icon-bg);color:var(--icon-text);font-size:.75rem;font-weight:800}
            .source-icon img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;border-radius:50%}
            .source-name{color:var(--text);font-weight:700}
            blockquote{border-left:4px solid #f36b32;margin:1.5rem 0;padding-left:1rem}
            main a{color:var(--link)}.story-action{display:inline-flex;align-items:center;gap:.45rem;font-weight:700}.story-action svg{width:1.1rem;height:1.1rem;flex:none;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.facebook-icon{display:inline-flex;align-items:center;justify-content:center;width:1.1rem;height:1.1rem;flex:none;border-radius:50%;background:#1877f2;color:#fff;font:bold 1rem/1 Arial,sans-serif}.original{margin-top:1rem}
            @media(max-width:46rem){main{margin:calc(66px + 1rem) 1rem 1rem;padding:1.25rem}}
            @media(max-width:25rem){header{padding:0 1rem;gap:.5rem}.brand{gap:.4rem;font-size:1rem}.brand img{width:36px;height:36px}.top-actions{gap:.25rem;padding-right:0}.top-actions .rss{min-width:0;padding:0 .25rem}.top-actions .auth{min-width:0;padding:0 .5rem}}
            """.trimIndent(),
        )
        val body = doc.body()
        val header = body.appendElement("header")
        val brand = header.appendElement("a").attr("class", "brand").attr("href", "/")
        brand.appendElement("img").attr("src", "/cozadzban-logo.png")
            .attr("alt", "").attr("width", "46").attr("height", "46")
        brand.appendElement("span").text("Co za dzban")
        val actions = header.appendElement("nav").attr("class", "top-actions").attr("aria-label", "Main navigation")
        val rss = actions.appendElement("a").attr("class", "rss").attr("href", "/rss")
            .attr("target", "_blank").attr("rel", "noopener noreferrer")
        rss.appendElement("svg").attr("viewBox", "0 0 16 16").attr("aria-hidden", "true")
            .appendElement("path").attr("d", "M4.4 13.8a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 0 1 4.4 0ZM0 5.4v3.1A7.5 7.5 0 0 1 7.5 16h3.1C10.6 10.1 5.9 5.4 0 5.4ZM0 0v3.2C7.1 3.2 12.8 8.9 12.8 16H16C16 7.2 8.8 0 0 0Z")
        rss.appendElement("span").text("RSS")
        if (signedIn && csrf != null) {
            val form = actions.appendElement("form").attr("action", "/auth/logout").attr("method", "post")
                .attr("onsubmit", "event.preventDefault();fetch(this.action,{method:'POST',credentials:'same-origin',body:new FormData(this)}).then(function(response){if(response.ok)location.href='/';else location.reload()}).catch(function(){location.reload()})")
            form.appendElement("input").attr("type", "hidden").attr("name", csrf.parameterName).attr("value", csrf.token)
            form.appendElement("button").attr("type", "submit").attr("class", "auth").text("Logout")
        } else if (!signedIn) {
            actions.appendElement("a").attr("class", "auth").attr("href", "/auth/login").text("Login")
        }
        val menu = actions.appendElement("details").attr("class", "top-menu")
        menu.appendElement("summary").attr("aria-label", "Menu").attr("title", "Menu")
            .appendElement("svg").attr("viewBox", "0 0 16 16").attr("aria-hidden", "true")
            .appendElement("path").attr("d", "M0 1h16v3H0V1Zm0 5h16v3H0V6Zm0 5h16v3H0v-3Z")
        val menuItems = menu.appendElement("div").attr("class", "top-menu-items")
        if (canAccessUserPages) {
            appendMenuLink(menuItems, "/", "Feed", "home")
            appendMenuLink(menuItems, "/article-proposals", "Article proposals", "list")
            appendMenuLink(menuItems, "/?addArticle=1", "Add article", "plus")
            appendMenuLink(menuItems, "/notification-settings", "Notification settings", "bell")
        }
        val themeButton = menuItems.appendElement("button").attr("type", "button")
            .attr("onclick", "cozadzbanToggleTheme()")
        appendMenuIcon(themeButton, "adjust")
        themeButton.appendElement("span").text("Toggle dark mode")
        appendMenuLink(menuItems, "/privacy-policy.html", "Privacy Policy", "lock")
        appendMenuLink(menuItems, "/user-data-deletion.html", "User data deletion", "trash")
        if (isAdmin) {
            menuItems.appendElement("hr")
            menuItems.appendElement("span").attr("class", "top-menu-section").text("Admin")
            appendMenuLink(menuItems, "/admin", "Manage users", "users")
            appendMenuLink(menuItems, "/admin/facebook-pages", "Facebook Pages", "globe")
            appendMenuLink(menuItems, "/admin/facebook-statistics", "Story and Facebook statistics", "chart")
            appendMenuLink(menuItems, "/admin/facebook-thumbnails", "Article thumbnails", "picture")
            appendMenuLink(menuItems, "/admin/article-content-cache", "Article content cache", "database")
        }
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
        val original = main.appendElement("a").attr("class", "story-action original").attr("href", article.url)
            .attr("rel", "noopener noreferrer")
        val externalIcon = original.appendElement("svg").attr("viewBox", "0 0 24 24").attr("aria-hidden", "true")
        externalIcon.appendElement("path").attr("d", "M15 3h6v6M10 14 21 3M21 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h6")
        original.appendElement("span").text(if (polish) "Czytaj oryginalną publikację" else "Read the original story")
        facebookPostUrl?.takeIf { it.startsWith("https://www.facebook.com/") }?.let { url ->
            val facebook = main.appendElement("p").appendElement("a").attr("class", "story-action").attr("href", url)
                .attr("rel", "noopener noreferrer")
            facebook.appendElement("span").attr("class", "facebook-icon").attr("aria-hidden", "true").text("f")
            facebook.appendElement("span").text(if (polish) "Skomentuj na Facebooku" else "Comment on Facebook")
        }
        return "<!doctype html>\n${doc.outerHtml()}"
    }

    private fun appendMenuLink(menu: Element, href: String, label: String, icon: String) {
        val link = menu.appendElement("a").attr("href", href)
        appendMenuIcon(link, icon)
        link.appendElement("span").text(label)
    }

    private fun appendMenuIcon(item: Element, icon: String) {
        val path = when (icon) {
            "home" -> "M1 8 8 2l7 6v7H9v-5H7v5H1z"
            "list" -> "M1 3h14v2H1zm0 4h14v2H1zm0 4h14v2H1z"
            "plus" -> "M7 1h2v6h6v2H9v6H7V9H1V7h6z"
            "bell" -> "M8 1a5 5 0 0 0-5 5v3l-2 3v1h14v-1l-2-3V6a5 5 0 0 0-5-5Zm-2 13h4a2 2 0 0 1-4 0Z"
            "adjust" -> "M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0Zm0 2v12A6 6 0 0 1 8 2Z"
            "lock" -> "M4 6V4a4 4 0 1 1 8 0v2h2v9H2V6zm2 0h4V4a2 2 0 1 0-4 0z"
            "trash" -> "M5 1h6l1 2h3v2H1V3h3zm-2 5h10l-1 9H4z"
            "users" -> "M5 2a3 3 0 1 0 0 6 3 3 0 0 0 0-6Zm7 1a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM0 14a5 5 0 0 1 10 0v1H0zm11-5a4 4 0 0 1 5 4v2h-4v-1a7 7 0 0 0-1-5Z"
            "globe" -> "M8 0a8 8 0 1 0 0 16A8 8 0 0 0 8 0Zm0 2c1 0 2 2 2.4 5H5.6C6 4 7 2 8 2ZM2 7c.2-2 1.2-3.3 2.5-4.2A15 15 0 0 0 3.6 7zm0 2h1.6c.1 1.6.4 3 .9 4.2A6 6 0 0 1 2 9Zm3.6 0h4.8C10 12 9 14 8 14s-2-2-2.4-5Zm5.9 4.2c.5-1.2.8-2.6.9-4.2H14a6 6 0 0 1-2.5 4.2ZM12.4 7c-.1-1.6-.4-3-.9-4.2A6 6 0 0 1 14 7Z"
            "chart" -> "M1 14h14v1H1zM2 8h3v5H2zm4-6h3v11H6zm4 4h3v7h-3z"
            "picture" -> "M1 2h14v12H1zm2 2v8h10V4zm2 1a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm-1 6 3-3 2 2 2-2 2 3z"
            "database" -> "M8 1c4 0 7 1 7 3v8c0 2-3 3-7 3s-7-1-7-3V4c0-2 3-3 7-3Zm0 2C5 3 3 3.6 3 4s2 1 5 1 5-.6 5-1-2-1-5-1Zm-5 4v2c0 .4 2 1 5 1s5-.6 5-1V7c-1.3.7-3 1-5 1S4.3 7.7 3 7Zm0 5c0 .4 2 1 5 1s5-.6 5-1v-1c-1.3.7-3 1-5 1s-3.7-.3-5-1z"
            else -> return
        }
        item.appendElement("svg").attr("viewBox", "0 0 16 16").attr("aria-hidden", "true")
            .appendElement("path").attr("d", path)
    }
}
