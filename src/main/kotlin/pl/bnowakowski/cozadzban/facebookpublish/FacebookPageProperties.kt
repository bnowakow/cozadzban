// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.facebook-page")
data class FacebookPageProperties(
    val appId: String = "",
    val appSecret: String = "",
    val configurationId: String = "",
    val redirectUri: String = "",
    val encryptionKey: String = "",
    val graphVersion: String = "v26.0",
    val graphBaseUrl: String = "https://graph.facebook.com",
    val authorizationBaseUrl: String = "https://www.facebook.com",
) {
    val missingOAuthSettings: List<String>
        get() = buildList {
            if (appId.isBlank()) add("APP_FACEBOOK_PAGE_APP_ID")
            if (appSecret.isBlank()) add("APP_FACEBOOK_PAGE_APP_SECRET")
            if (configurationId.isBlank()) add("APP_FACEBOOK_PAGE_CONFIGURATION_ID")
            if (!redirectUri.startsWith("https://")) add("APP_FACEBOOK_PAGE_REDIRECT_URI (HTTPS URL)")
            if (encryptionKey.isBlank()) add("APP_FACEBOOK_PAGE_ENCRYPTION_KEY")
        }

    val oauthConfigured: Boolean
        get() = missingOAuthSettings.isEmpty()
}
