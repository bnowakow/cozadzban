// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.analytics

import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Vaadin [VaadinServiceInitListener] that:
 * 1. Injects Google Analytics 4 and StatCounter scripts into the index HTML when the
 *    corresponding environment variables are configured (item 59 — conditional rendering).
 * 2. Injects an inline cookie-consent banner + JS that gates the analytics scripts on the
 *    visitor's explicit consent stored in localStorage["czj_analytics_consent"] (item 60).
 *
 * Scripts are only loaded when consent == "accepted". The banner is shown once and
 * dismissed by choosing "Accept" or "Decline". A "Manage cookies" link reopens the
 * choice at any time.
 */
@Component
class AnalyticsIndexHtmlRequestListener(
    private val analytics: AnalyticsProperties,
) : VaadinServiceInitListener {

    override fun serviceInit(event: ServiceInitEvent) {
        event.addIndexHtmlRequestListener { response ->
            val doc: Document = response.document
            injectConsentBannerStyles(doc)
            injectConsentBannerHtml(doc)
            injectAnalyticsScript(doc)
        }
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
                background: #1e1e1e;
                color: #f0f0f0;
                font-family: sans-serif;
                font-size: 14px;
                padding: 14px 20px;
                display: flex;
                flex-wrap: wrap;
                align-items: center;
                gap: 12px;
                box-shadow: 0 -2px 8px rgba(0,0,0,0.4);
            }
            #czj-consent-banner p { margin: 0; flex: 1 1 260px; }
            #czj-consent-banner .czj-consent-actions { display: flex; gap: 8px; flex-wrap: wrap; }
            #czj-consent-banner button {
                cursor: pointer;
                border: none;
                border-radius: 4px;
                padding: 7px 16px;
                font-size: 14px;
            }
            #czj-consent-accept { background: #4caf50; color: #fff; }
            #czj-consent-decline { background: #555; color: #fff; }
            #czj-manage-cookies {
                font-size: 12px;
                color: #aaa;
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

        // Cookie consent banner (hidden until JS decides to show it)
        val banner = body.prependElement("div")
        banner.attr("id", "czj-consent-banner")
        banner.attr("style", "display:none")
        banner.html(
            """
            <p>We use analytics cookies to understand how visitors interact with this site.
               No personal data is sold. You can change your choice at any time.</p>
            <div class="czj-consent-actions">
                <button id="czj-consent-accept" onclick="czjConsentAccept()">Accept</button>
                <button id="czj-consent-decline" onclick="czjConsentDecline()">Decline</button>
            </div>
            """.trimIndent(),
        )

        // "Manage cookies" link — always present when analytics is configured, so users can revoke consent
        if (analytics.isAnyEnabled) {
            val footer = body.appendElement("div")
            footer.attr("id", "czj-cookie-footer")
            footer.attr("style", "position:fixed;bottom:4px;right:8px;z-index:9999")
            footer.html(
                """<button id="czj-manage-cookies" onclick="czjManageCookies()" title="Change cookie preferences">Manage cookies</button>""",
            )
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
        script.html(
            """
            (function () {
                var CONSENT_KEY = 'czj_analytics_consent';
                var analyticsEnabled = ${analyticsEnabled};

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

                window.czjConsentAccept = function () {
                    localStorage.setItem(CONSENT_KEY, 'accepted');
                    hideBanner();
                    loadAnalytics();
                };

                window.czjConsentDecline = function () {
                    localStorage.setItem(CONSENT_KEY, 'declined');
                    hideBanner();
                };

                window.czjManageCookies = function () {
                    localStorage.removeItem(CONSENT_KEY);
                    showBanner();
                };

                var consent = localStorage.getItem(CONSENT_KEY);
                if (consent === 'accepted') {
                    loadAnalytics();
                } else if (consent === null && analyticsEnabled) {
                    showBanner();
                }
            })();
            """.trimIndent(),
        )
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
