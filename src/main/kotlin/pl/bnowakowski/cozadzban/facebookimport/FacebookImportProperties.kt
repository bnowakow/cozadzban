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
    val runTimeout: Duration = Duration.ofHours(1),
    val targetApiBaseUrl: String = "",
    val targetApiKey: String = "",
    val targetApiKeyHeader: String = "X-CoZaDzban-M2M-Key",
    val targetArticlePath: String = "/api/articles",
    val targetProposalPath: String = "/api/facebook-import/proposals",
    val targetProposalExistsPath: String = "/api/facebook-import/proposals/exists",
    val targetRunPath: String = "/api/facebook-import/runs",
    val targetApiConnectTimeout: Duration = Duration.ofSeconds(3),
    val targetApiReadTimeout: Duration = Duration.ofMinutes(5),
    val rejectionArtifactDir: String = "logs/facebook-import-rejections",
    val staleRunCleanupInterval: Duration = Duration.ofMinutes(1),
    val schedule: Schedule = Schedule(),
) {
    data class Schedule(
        val enabled: Boolean = false,
        val interval: Duration = Duration.ofHours(8),
        val initialDelay: Duration = Duration.ZERO,
    )

    enum class Browser {
        FIREFOX,
        CHROME,
    }
}
