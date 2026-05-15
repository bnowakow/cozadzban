// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.version

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.build")
data class AppBuildProperties(
    var timestamp: String = "",
    var version: String = "dev",
    var commit: String = "unknown",
) {
    val displayVersion: String
        get() = if (commit.isBlank() || commit == "unknown") version else "$version+$commit"
}
