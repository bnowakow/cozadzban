// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.analytics

import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

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
) : VaadinServiceInitListener {

    override fun serviceInit(event: ServiceInitEvent) {
        event.addIndexHtmlRequestListener { response ->
            val doc: Document = response.document
            injectVaadinDevToolsBootstrap(doc)
            injectConsentBannerStyles(doc)
            injectConsentBannerHtml(doc)
            injectAnalyticsScript(doc)
        }
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
