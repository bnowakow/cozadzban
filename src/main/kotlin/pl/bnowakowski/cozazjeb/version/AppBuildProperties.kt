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
    val resolvedCommit: String?
        get() = commit
            .takeUnless { it.isBlank() || it == "unknown" || it.startsWith("\${") }
            ?: currentGitCommit()

    val displayVersion: String
        get() = resolvedCommit
            ?.let { "$version+$it" }
            ?: version

    private fun currentGitCommit(): String? =
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                null
            } else {
                process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
            }
        }.getOrNull()
}
