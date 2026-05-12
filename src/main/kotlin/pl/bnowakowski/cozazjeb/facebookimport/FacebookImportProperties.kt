// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.facebook-import")
data class FacebookImportProperties(
    val enabled: Boolean = false,
    val profileUrl: String = "https://www.facebook.com/bartek.dobrowolski.nowakowski",
    val markerPhrase: String = "co za zjeb",
    val translatedMarkerPhrase: String = "what a fucker",
    val language: String = "pl",
    val creatorEmail: String = "",
    val username: String = "",
    val password: String = "",
    val browser: Browser = Browser.FIREFOX,
    val headless: Boolean = false,
    val scrolls: Int = 8,
    val waitAfterLogin: Duration = Duration.ofSeconds(8),
    val waitAfterPageOpen: Duration = Duration.ofSeconds(5),
    val waitAfterScroll: Duration = Duration.ofSeconds(2),
    val manualLoginTimeout: Duration = Duration.ofMinutes(3),
) {
    enum class Browser {
        FIREFOX,
        CHROME,
    }
}
