// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.client.RestClient
import pl.bnowakowski.cozadzban.ui.WarsawTime

// Live NYTimes/r.jina.ai response is not deterministic and can return a 403/CAPTCHA placeholder.
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_NYT_VERIFICATION", matches = "true")
class LiveNytShortFallbackVerificationTest {

    @Test
    fun `extracts metadata from live NYTimes short link through fallback`() {
        val result = EnrichmentService(RestClient.builder()).enrich("https://nyti.ms/4tisLZM")

        assertEquals("Trump Fires Pam Bondi as Attorney General", result.title)
        // The reader fallback gives 17:07:51 UTC; oEmbed only gives the date, read as Warsaw midnight.
        assertEquals("2026-04-02", result.publishedAt?.let(WarsawTime::date))
        assertEquals(
            "https://static01.nyt.com/images/2026/04/02/multimedia/00dc-bondiHFO-mzkg/00dc-bondiHFO-mzkg-largeHorizontalJumbo.jpg",
            result.thumbnail,
        )
    }
}
