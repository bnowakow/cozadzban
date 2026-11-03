// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.boot.context.properties.ConfigurationProperties

/** Credentials for the separate Meta app requesting Page Public Content Access. */
@ConfigurationProperties(prefix = "app.facebook-source")
data class FacebookSourceProperties(
    val appId: String = "",
    val appSecret: String = "",
    val graphVersion: String = "v26.0",
    val graphBaseUrl: String = "https://graph.facebook.com",
) {
    val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()

    /** App tokens stay on the server and are sent only in the Authorization header. */
    internal val appToken: String get() = "$appId|$appSecret"
}
