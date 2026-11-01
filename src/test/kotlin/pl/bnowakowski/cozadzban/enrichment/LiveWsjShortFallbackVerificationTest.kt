// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.client.RestClient

// Live WSJ/Datadome behavior is not deterministic; run manually when verifying this short link.
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_WSJ_VERIFICATION", matches = "true")
class LiveWsjShortFallbackVerificationTest {

    @Test
    fun `enriches live WSJ short link through fallback`() {
        val result = EnrichmentService(RestClient.builder()).enrich("https://on.wsj.com/4cniLaK")

        assertEquals(
            "Judge Dismisses Trump's Defamation Lawsuit Against News Corp Over Jeffrey Epstein Reporting",
            result.title,
        )
    }
}
