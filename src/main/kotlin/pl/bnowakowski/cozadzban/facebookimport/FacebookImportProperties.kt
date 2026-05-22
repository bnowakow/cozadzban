// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.facebook-import")
data class FacebookImportProperties(
    val enabled: Boolean = false,
    val profileUrl: String = "https://www.facebook.com/bartek.dobrowolski.nowakowski",
    val markerPhrase: String = "co za dzban",
    val translatedMarkerPhrase: String = "what a fucker",
    val language: String = "pl",
    val username: String = "",
    val password: String = "",
    val browser: Browser = Browser.FIREFOX,
    val headless: Boolean = false,
    val scrolls: Int = 8,
    val waitAfterLogin: Duration = Duration.ofSeconds(8),
    val waitAfterPageOpen: Duration = Duration.ofSeconds(5),
    val waitAfterScroll: Duration = Duration.ofSeconds(2),
    val manualLoginTimeout: Duration = Duration.ofMinutes(3),
    val targetApiBaseUrl: String = "",
    val targetApiKey: String = "",
    val targetApiKeyHeader: String = "X-CoZaDzban-M2M-Key",
    val targetArticlePath: String = "/api/articles",
    val targetApiConnectTimeout: Duration = Duration.ofSeconds(3),
    val targetApiReadTimeout: Duration = Duration.ofMinutes(5),
    val rejectionArtifactDir: String = "logs/facebook-import-rejections",
) {
    enum class Browser {
        FIREFOX,
        CHROME,
    }
}
