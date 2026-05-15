// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.client.RestClient

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_YOUTUBE_VERIFICATION", matches = "true")
class LiveYoutubeTitleVerificationTest {

    @Test
    fun `extracts title from live YouTube short URL`() {
        val result = EnrichmentService(RestClient.builder()).enrich(
            "https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8",
        )

        assertEquals("V EN MariaAI DemoB72 aLoora d5 9x16 s51 ID 6262", result.title)
    }
}
