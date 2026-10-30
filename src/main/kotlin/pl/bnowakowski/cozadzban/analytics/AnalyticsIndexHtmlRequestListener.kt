// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.analytics

import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import com.vaadin.flow.server.VaadinServletRequest
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.net.URI

/**
 * Vaadin [VaadinServiceInitListener] that:
 * 1. Injects Google Analytics 4 and StatCounter scripts into the index HTML when the
 *    corresponding environment variables are configured (item 59 — conditional rendering).
 * 2. Injects an inline cookie-consent banner + JS that gates the analytics scripts on the
 *    visitor's explicit consent stored in localStorage["czj_analytics_consent"] (item 60).
 *
 * Scripts are only loaded when consent == "accepted". The banner is shown until the
 * visitor explicitly chooses "Accept" or "Decline". A "Manage cookies" link reopens the
 * choice at any time.
 */
@Component
class AnalyticsIndexHtmlRequestListener(
    private val analytics: AnalyticsProperties,
    private val articles: ArticleRepository? = null,
) : VaadinServiceInitListener {

    override fun serviceInit(event: ServiceInitEvent) {
        event.addIndexHtmlRequestListener { response ->
            val doc: Document = response.document
            (response.vaadinRequest as? VaadinServletRequest)?.httpServletRequest?.let { request ->
                makeBaseHrefAbsolute(doc, request.requestURL.toString())
            }
            val language = response.vaadinRequest.getParameter("lang")
            injectSearchMetadata(doc, language)
            if (response.vaadinRequest.pathInfo.orEmpty().trim('/').isEmpty()) {
                injectHomeContent(doc, language)
            }
            injectVaadinDevToolsBootstrap(doc)
            injectThemeBootstrap(doc)
            injectConsentBannerStyles(doc)
            injectConsentBannerHtml(doc)
            injectAnalyticsScript(doc)
        }
    }

    private fun makeBaseHrefAbsolute(doc: Document, requestUrl: String) {
        // Vaadin generates a route-relative base before invoking this listener.
        // Resolve it against this request so assets also work on localhost and nested routes.
        doc.selectFirst("base[href]")?.let { base ->
            base.attr("href", URI(requestUrl).resolve(base.attr("href")).toString())
        }
    }

    private fun injectSearchMetadata(doc: Document, requestedLanguage: String?) {
        val language = requestedLanguage?.lowercase()?.takeIf { it == "pl" || it == "en" }
        val polish = language == "pl"
        val title = if (polish) "Co za dzban — wiadomości i historie" else "Co za dzban — news and stories"
        val description = if (polish) {
            "Przegląd wiadomości i historii z różnych źródeł. Czytaj streszczenia, poznaj kontekst i przejdź do oryginalnych publikacji."
        } else {
            "A collection of news and stories from different sources. Read summaries, explore the context, and follow links to the original publications."
        }
        val url = "https://cozadzban.pl" + when (language) {
            "pl" -> "/?lang=pl"
            "en" -> "/?lang=en"
            else -> "/"
        }
        doc.selectFirst("html")!!.attr("lang", language ?: "en")
        doc.head().select("title, meta[name=description], link[rel=canonical], link[rel=alternate][hreflang]").remove()
        doc.head().appendElement("title").text(title)
        doc.head().appendElement("meta").attr("name", "description").attr("content", description)
        doc.head().appendElement("link").attr("rel", "canonical").attr("href", url)
        listOf("pl", "en").forEach { lang ->
            doc.head().appendElement("link").attr("rel", "alternate")
                .attr("hreflang", lang).attr("href", "https://cozadzban.pl/?lang=$lang")
        }
        doc.head().appendElement("link").attr("rel", "alternate")
            .attr("hreflang", "x-default").attr("href", "https://cozadzban.pl/")
        doc.head().appendElement("meta").attr("property", "og:title").attr("content", title)
        doc.head().appendElement("meta").attr("property", "og:description").attr("content", description)
        doc.head().appendElement("meta").attr("property", "og:url").attr("content", url)
        doc.head().appendElement("meta").attr("property", "og:locale").attr("content", if (polish) "pl_PL" else "en_US")
    }

    /** Visible until Vaadin renders; also gives non-JavaScript readers a useful homepage. */
    private fun injectHomeContent(doc: Document, requestedLanguage: String?) {
        val outlet = doc.selectFirst("#outlet") ?: doc.body().appendElement("div").attr("id", "outlet")
        val language = requestedLanguage?.lowercase()?.takeIf { it == "pl" || it == "en" }
        val polish = language == "pl"
        val main = outlet.appendElement("main").attr("id", "czj-initial-home")
        main.appendElement("h1").text(if (polish) "Co za dzban — wiadomości i historie" else "Co za dzban — news and stories")
        main.appendElement("p").text(if (polish) {
            "Wiadomości i historie z różnych źródeł. Czytaj streszczenia i przejdź do oryginalnych publikacji."
        } else {
            "News and stories from different sources. Read summaries and follow links to the original publications."
        })
        main.appendElement("h2").text(if (polish) "Najnowsze historie" else "Recent stories")
        val list = main.appendElement("ul")
        articles?.findPage(0, 16, "publishedAt", "desc", language)
            ?.asSequence()
            ?.filter { it.id != null && !it.title.isNullOrBlank() }
            ?.take(12)
            ?.forEach { article ->
                val item = list.appendElement("li")
                item.appendElement("h3").appendElement("a")
                    .attr("href", "/stories/${article.id}")
                    .text(article.title!!.trim())
                article.lead?.trim()?.takeIf { it.isNotEmpty() }?.let { lead ->
                    item.appendElement("p").text(lead)
                }
            }
        val navigation = main.appendElement("nav")
            .attr("aria-label", if (polish) "Więcej na stronie" else "More on this site")
        listOf(
            "/sitemap.xml" to (if (polish) "Mapa strony" else "Sitemap"),
            "/rss" to "RSS",
            "/privacy-policy.html" to (if (polish) "Polityka prywatności" else "Privacy policy"),
            "/user-data-deletion.html" to (if (polish) "Usuwanie danych" else "User data deletion"),
        ).forEach { (href, label) ->
            navigation.appendElement("a").attr("href", href).text(label)
        }
        doc.head().appendElement("style").attr("id", "czj-initial-home-style").appendText(
            """
            #czj-initial-home{box-sizing:border-box;max-width:42rem;margin:calc(66px + 1.35rem) auto 2rem;padding:1.5rem;background:var(--lumo-base-color,#fff);color:var(--lumo-body-text-color,#15171a);font:16px/1.6 system-ui,sans-serif;border-radius:8px}
            #czj-initial-home h1{margin:0 0 .5rem;font-size:clamp(1.75rem,5vw,2.25rem);line-height:1.2}
            #czj-initial-home h2{margin:1.5rem 0 .5rem;font-size:1.3rem}
            #czj-initial-home ul{padding-left:1.35rem}#czj-initial-home li{margin:.45rem 0}
            #czj-initial-home h3{margin:1rem 0 .25rem;font-size:1.1rem;line-height:1.4}
            #czj-initial-home li p{margin:.25rem 0 1rem;overflow-wrap:anywhere}
            #czj-initial-home a{color:var(--lumo-primary-text-color,#a83d13)}
            #czj-initial-home nav{display:flex;flex-wrap:wrap;gap:.5rem 1rem;margin-top:1.5rem}
            @media(max-width:46rem){#czj-initial-home{margin:calc(66px + 1rem) 1rem 1rem}}
            """.trimIndent(),
        )
        doc.body().appendElement("script").attr("id", "czj-initial-home-cleanup").appendChild(DataNode(
            """
            (function() {
                var outlet = document.getElementById('outlet');
                var initial = document.getElementById('czj-initial-home');
                if (!outlet || !initial) return;
                function removeAfterRender() {
                    if (Array.from(outlet.children).some(function(child) { return child !== initial; })) {
                        initial.remove();
                        observer.disconnect();
                    }
                }
                var observer = new MutationObserver(removeAfterRender);
                observer.observe(outlet, { childList: true });
                removeAfterRender();
            })();
            """.trimIndent(),
        ))
    }

    private fun injectVaadinDevToolsBootstrap(doc: Document) {
        val script = doc.head().prependElement("script")
        script.attr("id", "czj-vaadin-devtools-bootstrap")
        script.appendChild(DataNode(
            """
            window.Vaadin = window.Vaadin || {};
            window.Vaadin.devToolsPlugins = window.Vaadin.devToolsPlugins || [];
            """.trimIndent(),
        ))
    }

    private fun injectThemeBootstrap(doc: Document) {
        val script = doc.head().appendElement("script")
        script.attr("id", "czj-theme-bootstrap")
        script.appendChild(DataNode(
            """
            (function () {
                function applyTheme(mode) {
                    var dark = mode === 'dark';
                    var root = document.documentElement;
                    var body = document.body;
                    if (dark) {
                        root.setAttribute('theme', 'dark');
                        if (body) body.setAttribute('theme', 'dark');
                    } else {
                        root.removeAttribute('theme');
                        if (body) body.removeAttribute('theme');
                    }
                }

                function initialTheme() {
                    var stored = null;
                    try {
                        stored = localStorage.getItem('cozadzban-theme');
                    } catch (e) {
                        // Ignore storage failures and fall back to the system preference.
                    }
                    var dark = stored ? stored === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
                    return dark ? 'dark' : 'light';
                }

                var mode = initialTheme();
                applyTheme(mode);
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function () {
                        applyTheme(mode);
                    }, { once: true });
                } else {
                    applyTheme(mode);
                }
            })();
            """.trimIndent(),
        ))
    }

    // -------------------------------------------------------------------------
    // Consent banner HTML (item 60)
    // -------------------------------------------------------------------------

    private fun injectConsentBannerStyles(doc: Document) {
        val style = doc.head().appendElement("style")
        style.attr("id", "czj-consent-styles")
        style.text(
            """
            #czj-consent-banner {
                position: fixed;
                bottom: 0;
                left: 0;
                right: 0;
                z-index: 99999;
                background: var(--czj-card-bg, var(--lumo-base-color, #ffffff));
                border-top: 1px solid var(--czj-border, var(--lumo-contrast-10pct, #d4d9df));
                color: var(--czj-text, var(--lumo-body-text-color, #15171a));
                font-family: sans-serif;
                font-size: 14px;
                padding: 14px 20px;
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 12px;
                box-shadow: 0 -2px 8px rgba(15, 23, 42, 0.12);
            }
            html[theme~="dark"] #czj-consent-banner {
                box-shadow: 0 -2px 12px rgba(0,0,0,0.35);
            }
            #czj-consent-banner p { margin: 0; flex: 1 1 260px; }
            #czj-consent-banner .czj-consent-actions { display: flex; gap: 8px; flex-wrap: wrap; }
            #czj-consent-banner button {
                cursor: pointer;
                border: 1px solid var(--czj-border, var(--lumo-contrast-10pct, #d4d9df));
                border-radius: 4px;
                padding: 7px 16px;
                font-size: 14px;
            }
            #czj-consent-accept {
                background: var(--lumo-primary-color, #ff6b2a);
                color: var(--lumo-primary-contrast-color, #190b04);
            }
            #czj-consent-decline {
                background: var(--lumo-contrast-10pct, #eef0f3);
                color: var(--czj-text, var(--lumo-body-text-color, #15171a));
            }
            #czj-manage-cookies {
                font-size: 12px;
                color: var(--czj-muted, var(--lumo-secondary-text-color, #626970));
                cursor: pointer;
                text-decoration: underline;
                background: none;
                border: none;
                padding: 0;
                font-family: sans-serif;
            }
            """.trimIndent(),
        )
    }

    private fun injectConsentBannerHtml(doc: Document) {
        val body = doc.body()

        // Cookie consent banner (visible by default only when analytics is enabled)
        val banner = body.prependElement("div")
        banner.attr("id", "czj-consent-banner")
        banner.attr("data-nosnippet", "")
        if (!analytics.isAnyEnabled) {
            banner.attr("style", "display:none")
        }
        banner.appendElement("p").text(
            "We use analytics cookies to understand how visitors interact with this site. " +
                "No personal data is sold. You can change your choice at any time.",
        )
        val actions = banner.appendElement("div").attr("class", "czj-consent-actions")
        actions.appendElement("button")
            .attr("id", "czj-consent-accept")
            .attr("onclick", "czjConsentAccept()")
            .text("Accept")
        actions.appendElement("button")
            .attr("id", "czj-consent-decline")
            .attr("onclick", "czjConsentDecline()")
            .text("Decline")

        // "Manage cookies" link — always present when analytics is configured, so users can revoke consent
        if (analytics.isAnyEnabled) {
            val footer = body.appendElement("div")
            footer.attr("id", "czj-cookie-footer")
            footer.attr("data-nosnippet", "")
            footer.attr("style", "position:fixed;bottom:4px;right:8px;z-index:9999")
            footer.appendElement("button")
                .attr("id", "czj-manage-cookies")
                .attr("onclick", "czjManageCookies()")
                .attr("title", "Change cookie preferences")
                .text("Manage cookies")
        }
    }

    // -------------------------------------------------------------------------
    // Analytics + consent JS (items 59 + 60)
    // -------------------------------------------------------------------------

    private fun injectAnalyticsScript(doc: Document) {
        val gaId = analytics.googleMeasurementId.trim()
        val scProjectId = analytics.statcounterProjectId.trim()
        val scSecurityId = analytics.statcounterSecurityId.trim()

        val analyticsEnabled = analytics.isAnyEnabled

        val gaLoader = if (analytics.isGoogleEnabled) gaLoaderScript(gaId) else ""
        val scLoader = if (analytics.isStatcounterEnabled) scLoaderScript(scProjectId, scSecurityId) else ""

        val script = doc.head().appendElement("script")
        script.attr("id", "czj-analytics-init")
        script.appendChild(DataNode(
            """
            (function () {
                var CONSENT_KEY = 'czj_analytics_consent';
                var CONSENT_COOKIE_MAX_AGE_SECONDS = 31536000;
                var analyticsEnabled = ${analyticsEnabled};

                function getSharedCookieDomain() {
                    var host = window.location.hostname || '';
                    if (!host || host === 'localhost' || /^\d+\.\d+\.\d+\.\d+$/.test(host)) return null;
                    var parts = host.split('.');
                    if (parts.length < 2) return null;
                    return '.' + parts.slice(-2).join('.');
                }

                function setCookie(name, value, maxAgeSeconds, domain) {
                    var cookie = name + '=' + encodeURIComponent(value)
                        + '; Max-Age=' + maxAgeSeconds
                        + '; Path=/; SameSite=Lax';
                    if (window.location.protocol === 'https:') cookie += '; Secure';
                    if (domain) cookie += '; Domain=' + domain;
                    document.cookie = cookie;
                }

                function getCookie(name) {
                    var prefix = name + '=';
                    var parts = document.cookie ? document.cookie.split(';') : [];
                    for (var i = 0; i < parts.length; i++) {
                        var c = parts[i].trim();
                        if (c.indexOf(prefix) === 0) {
                            return decodeURIComponent(c.substring(prefix.length));
                        }
                    }
                    return null;
                }

                function deleteCookie(name, domain) {
                    var cookie = name + '=; Max-Age=0; Path=/; SameSite=Lax';
                    if (window.location.protocol === 'https:') cookie += '; Secure';
                    if (domain) cookie += '; Domain=' + domain;
                    document.cookie = cookie;
                }

                function setWindowNameConsent(value) {
                    try {
                        var payload = {};
                        if (window.name && window.name.indexOf('{') === 0) {
                            payload = JSON.parse(window.name);
                        }
                        payload[CONSENT_KEY] = value;
                        window.name = JSON.stringify(payload);
                    } catch (e) {
                        // Ignore if window.name cannot be parsed or written.
                    }
                }

                function getWindowNameConsent() {
                    try {
                        if (!window.name || window.name.indexOf('{') !== 0) return null;
                        var payload = JSON.parse(window.name);
                        return payload[CONSENT_KEY] || null;
                    } catch (e) {
                        return null;
                    }
                }

                function clearWindowNameConsent() {
                    try {
                        if (!window.name || window.name.indexOf('{') !== 0) return;
                        var payload = JSON.parse(window.name);
                        delete payload[CONSENT_KEY];
                        window.name = JSON.stringify(payload);
                    } catch (e) {
                        // Ignore if window.name cannot be parsed.
                    }
                }

                function setStoredConsent(value) {
                    try {
                        localStorage.setItem(CONSENT_KEY, value);
                    } catch (e) {
                        // Some browsers/privacy modes block localStorage access.
                    }
                    setWindowNameConsent(value);
                    setCookie(CONSENT_KEY, value, CONSENT_COOKIE_MAX_AGE_SECONDS, null);
                    setCookie(CONSENT_KEY, value, CONSENT_COOKIE_MAX_AGE_SECONDS, getSharedCookieDomain());
                }

                function getStoredConsent() {
                    try {
                        var lsValue = localStorage.getItem(CONSENT_KEY);
                        if (lsValue) return lsValue;
                    } catch (e) {
                        // localStorage unavailable; fall back to cookie.
                    }
                    var cookieValue = getCookie(CONSENT_KEY);
                    if (cookieValue) return cookieValue;
                    return getWindowNameConsent();
                }

                function clearStoredConsent() {
                    try {
                        localStorage.removeItem(CONSENT_KEY);
                    } catch (e) {
                        // Ignore when localStorage is unavailable.
                    }
                    clearWindowNameConsent();
                    deleteCookie(CONSENT_KEY, null);
                    deleteCookie(CONSENT_KEY, getSharedCookieDomain());
                }

                function loadAnalytics() {
                    if (!analyticsEnabled) return;
                    ${gaLoader.trimIndent()}
                    ${scLoader.trimIndent()}
                }

                function showBanner() {
                    var banner = document.getElementById('czj-consent-banner');
                    if (banner) banner.style.display = 'flex';
                }

                function hideBanner() {
                    var banner = document.getElementById('czj-consent-banner');
                    if (banner) banner.style.display = 'none';
                }

                function applyStoredConsent() {
                    var consent = getStoredConsent();
                    if (consent === 'accepted') {
                        loadAnalytics();
                        hideBanner();
                    } else if (consent === 'declined') {
                        hideBanner();
                    } else if (analyticsEnabled) {
                        showBanner();
                    } else {
                        hideBanner();
                    }
                }

                window.czjConsentAccept = function () {
                    setStoredConsent('accepted');
                    hideBanner();
                    loadAnalytics();
                };

                window.czjConsentDecline = function () {
                    setStoredConsent('declined');
                    hideBanner();
                };

                window.czjManageCookies = function () {
                    clearStoredConsent();
                    showBanner();
                };

                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', applyStoredConsent, { once: true });
                } else {
                    applyStoredConsent();
                }
            })();
            """.trimIndent(),
        ))
    }

    // -------------------------------------------------------------------------
    // Script snippet builders
    // -------------------------------------------------------------------------

    private fun gaLoaderScript(measurementId: String): String =
        """
        if (!document.getElementById('czj-ga-script')) {
            var gaEl = document.createElement('script');
            gaEl.id = 'czj-ga-script';
            gaEl.async = true;
            gaEl.src = 'https://www.googletagmanager.com/gtag/js?id=${measurementId}';
            document.head.appendChild(gaEl);
            window.dataLayer = window.dataLayer || [];
            function gtag(){dataLayer.push(arguments);}
            window.gtag = gtag;
            gtag('js', new Date());
            gtag('config', '${measurementId}');
        }
        """.trimIndent()

    private fun scLoaderScript(projectId: String, securityId: String): String =
        """
        if (!document.getElementById('czj-sc-script')) {
            window.sc_project = ${projectId};
            window.sc_invisible = 1;
            window.sc_security = '${securityId}';
            var scEl = document.createElement('script');
            scEl.id = 'czj-sc-script';
            scEl.type = 'text/javascript';
            scEl.src = 'https://www.statcounter.com/counter/counter.js';
            scEl.async = true;
            document.head.appendChild(scEl);
        }
        """.trimIndent()
}
