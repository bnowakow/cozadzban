// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.version

import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@Component
class AppVersionInfoContributor(
    private val buildProperties: AppBuildProperties,
) : InfoContributor {

    override fun contribute(builder: Info.Builder) {
        builder.withDetail(
            "app",
            mapOf(
                "version" to buildProperties.displayVersion,
                "baseVersion" to buildProperties.version,
                "commit" to (buildProperties.resolvedCommit ?: buildProperties.commit),
                "buildTimestamp" to buildProperties.timestamp,
            ),
        )
    }
}
