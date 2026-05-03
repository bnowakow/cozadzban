// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.Ordered
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
 * - Runs at [ORDER] = [Ordered.HIGHEST_PRECEDENCE] + 5, which is just before
 *   [org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor]
 *   (HIGHEST_PRECEDENCE + 10). This ensures SPRING_PROFILES_ACTIVE from .env
 *   is available when Spring Boot activates profiles and loads profile-specific
 *   property files (e.g. application-local.properties).
 */
class DotEnvEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {

    override fun getOrder(): Int = ORDER

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

        if (properties.isEmpty()) return

        // Activate profiles explicitly so ConfigData can still load profile-specific files
        // when the profile is declared only in .env. (Plain MapPropertySource is not subject
        // to the key-normalisation that SystemEnvironmentPropertySource uses, so ConfigData
        // would not find SPRING_PROFILES_ACTIVE via relaxed binding.)
        (properties["SPRING_PROFILES_ACTIVE"] as? String)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { environment.addActiveProfile(it) }

        // Remove any pre-existing "dotenv" source (e.g. on a DevTools restart where the
        // environment object is reused). MutablePropertySources.addAfter throws
        // IllegalArgumentException if a source with the same name already exists, and that
        // exception is silently swallowed by the post-processor framework — leaving our
        // values inaccessible.
        environment.propertySources.remove("dotenv")

        val source = MapPropertySource("dotenv", properties)
        if (environment.propertySources.contains("systemEnvironment")) {
            environment.propertySources.addAfter("systemEnvironment", source)
        } else {
            environment.propertySources.addLast(source)
        }
    }

    companion object {
        /** Must be lower than ConfigDataEnvironmentPostProcessor.ORDER (HIGHEST_PRECEDENCE + 10). */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 5
    }
}
