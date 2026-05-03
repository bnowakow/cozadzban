// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.io.File

/**
 * Loads key=value pairs from a .env file in the working directory into
 * Spring's Environment as the lowest-priority property source.
 *
 * - Silently skips when .env is absent (production / CI environments
 *   supply vars through the platform, not a file).
 * - Variables already present in the environment (system env / JVM system
 *   properties) take precedence because .env is added last (addLast).
 */
class DotEnvEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val dotEnvFile = File(".env")
        if (!dotEnvFile.exists()) return

        val properties: Map<String, Any> = dotEnvFile.readLines()
            .filterNot { line -> line.isBlank() || line.trimStart().startsWith('#') }
            .mapNotNull { line ->
                val eqIndex = line.indexOf('=')
                if (eqIndex < 1) null
                else line.substring(0, eqIndex).trim() to line.substring(eqIndex + 1).trim()
            }
            .toMap()

        if (properties.isNotEmpty()) {
            environment.propertySources.addLast(MapPropertySource("dotenv", properties))
        }
    }
}
