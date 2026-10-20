// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.facebook-page")
data class FacebookPageProperties(
    val appId: String = "",
    val appSecret: String = "",
    val redirectUri: String = "",
    val encryptionKey: String = "",
    val graphVersion: String = "v26.0",
    val graphBaseUrl: String = "https://graph.facebook.com",
    val authorizationBaseUrl: String = "https://www.facebook.com",
) {
    val oauthConfigured: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank() && redirectUri.startsWith("https://") && encryptionKey.isNotBlank()
}
