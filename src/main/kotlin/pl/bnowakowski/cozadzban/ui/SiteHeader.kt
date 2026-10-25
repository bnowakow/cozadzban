// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.security.web.csrf.CsrfToken
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType

/** The feed and server-rendered story use this same header markup and stylesheet. */
object SiteHeader {
    // Static assets are cached for an hour. Change this URL when header.js changes.
    const val SCRIPT_URL = "/cozadzban-header.js?v=20261025-admin-import"

    data class Options(
        val signedIn: Boolean,
        val canAccessUserPages: Boolean,
        val isAdmin: Boolean,
        val csrf: CsrfToken? = null,
        val importTypes: List<FacebookImportType> = emptyList(),
        val importAvailable: Boolean = false,
        val importRunning: Boolean = false,
    )

    fun importTypes(service: FacebookImportJobService?, properties: FacebookImportProperties): List<FacebookImportType> {
        if (service == null) return emptyList()
        return service.availableImportTypes().toMutableList().apply {
            if (properties.apify.enabled && FacebookImportType.APIFY !in this) add(FacebookImportType.APIFY)
        }
    }

    fun html(options: Options): String = Document.createShell("").let { doc ->
        doc.outputSettings().prettyPrint(false)
        appendTo(doc.body(), options)
        doc.body().html()
    }

    fun appendTo(parent: Element, options: Options): Element {
        val header = parent.appendElement("header").attr("class", "czj-site-header")
        val brand = header.appendElement("a").attr("class", "czj-site-brand")
            .attr("href", "/").attr("router-ignore", "")
            .attr("title", "Go to main page").attr("aria-label", "Go to main page")
        brand.appendElement("img").attr("src", "/cozadzban-logo.png")
            .attr("alt", "").attr("width", "46").attr("height", "46")
        brand.appendElement("span").text("Co za dzban")

        val actions = header.appendElement("nav").attr("class", "czj-site-actions")
            .attr("aria-label", "Main navigation")
        val rss = actions.appendElement("a").attr("class", "czj-site-rss")
            .attr("href", "/rss").attr("router-ignore", "")
            .attr("target", "_blank").attr("rel", "noopener noreferrer")
        icon(rss, "rss")
        rss.appendElement("span").text("RSS")

        if (options.signedIn) {
            val form = actions.appendElement("form").attr("class", "czj-site-logout")
                .attr("action", "/auth/logout").attr("method", "post")
            options.csrf?.let {
                form.appendElement("input").attr("type", "hidden")
                    .attr("name", it.parameterName).attr("value", it.token)
            }
            form.appendElement("button").attr("type", "submit").attr("class", "czj-site-auth")
                .text("Logout")
        } else {
            actions.appendElement("a").attr("class", "czj-site-auth")
                .attr("href", "/auth/login").attr("router-ignore", "").text("Login")
        }

        val menu = actions.appendElement("details").attr("class", "czj-site-menu")
        val trigger = menu.appendElement("summary").attr("aria-label", "Menu").attr("title", "Menu")
        icon(trigger, "menu")
        val items = menu.appendElement("div").attr("class", "czj-site-menu-items")
        if (options.canAccessUserPages) {
            link(items, "/", "Feed", "home")
            link(items, "/article-proposals", "Article proposals", "list")
            link(items, "/?addArticle=1", "Add article", "plus")
            link(items, "/notification-settings", "Notification settings", "bell")
        }
        button(items, "Toggle dark mode", "adjust", "theme")
        link(items, "/privacy-policy.html", "Privacy Policy", "lock")
        link(items, "/user-data-deletion.html", "User data deletion", "trash")
        if (options.isAdmin) {
            items.appendElement("hr")
            items.appendElement("span").attr("class", "czj-site-menu-section").text("Admin")
            link(items, "/admin", "Manage users", "users")
            link(items, "/admin/facebook-pages", "Facebook Pages", "globe")
            link(items, "/admin/facebook-statistics", "Story and Facebook statistics", "chart")
            link(items, "/admin/facebook-thumbnails", "Article thumbnails", "picture")
            link(items, "/admin/article-content-cache", "Article content cache", "database")
            if (options.importAvailable) {
                if (options.importTypes.isEmpty()) {
                    button(items, "Import Facebook unavailable", "download", null).attr("disabled", "")
                } else {
                    options.importTypes.forEach { type ->
                        val label = when (type) {
                            FacebookImportType.APIFY -> "Import Facebook with Apify"
                            FacebookImportType.SELENIUM -> "Import Facebook with Selenium"
                        }
                        button(items, label, "download", "import-${type.name.lowercase()}")
                    }
                }
                button(items, "Stop Facebook import", "stop", "stop-import")
                    .apply { if (!options.importRunning) attr("disabled", "") }
            }
        }
        return header
    }

    private fun link(parent: Element, href: String, label: String, symbol: String) {
        val item = parent.appendElement("a").attr("href", href).attr("router-ignore", "")
        icon(item, symbol)
        item.appendElement("span").text(label)
    }

    private fun button(parent: Element, label: String, symbol: String, action: String?): Element {
        val item = parent.appendElement("button").attr("type", "button")
        if (action != null) item.attr("data-action", action)
        icon(item, symbol)
        item.appendElement("span").text(label)
        return item
    }

    private fun icon(parent: Element, symbol: String) {
        val path = when (symbol) {
            "rss" -> "M4.4 13.8a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 0 1 4.4 0ZM0 5.4v3.1A7.5 7.5 0 0 1 7.5 16h3.1C10.6 10.1 5.9 5.4 0 5.4ZM0 0v3.2C7.1 3.2 12.8 8.9 12.8 16H16C16 7.2 8.8 0 0 0Z"
            "menu" -> "M0 1h16v3H0V1Zm0 5h16v3H0V6Zm0 5h16v3H0v-3Z"
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
            "download" -> "M7 1h2v8l3-3 1 1-5 5-5-5 1-1 3 3zM1 13h14v2H1z"
            "stop" -> "M2 2h12v12H2z"
            else -> return
        }
        parent.appendElement("svg").attr("viewBox", "0 0 16 16").attr("aria-hidden", "true")
            .appendElement("path").attr("d", path)
    }
}
