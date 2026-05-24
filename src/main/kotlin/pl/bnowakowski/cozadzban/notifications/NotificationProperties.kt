// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.notifications")
data class NotificationProperties(
    val enabled: Boolean = false,
    val encryptionKey: String = "",
    val pushover: Pushover = Pushover(),
) {
    data class Pushover(
        val appToken: String = "",
        val baseUrl: String = "https://api.pushover.net",
        val connectTimeout: Duration = Duration.ofSeconds(3),
        val readTimeout: Duration = Duration.ofSeconds(5),
    )

    val pushoverConfigured: Boolean
        get() = enabled && pushover.appToken.isNotBlank()
}
