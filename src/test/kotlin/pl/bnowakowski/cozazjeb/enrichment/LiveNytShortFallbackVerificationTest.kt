// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class LiveNytShortFallbackVerificationTest {

    @Test
    @Disabled("Live NYTimes/r.jina.ai response is not deterministic and can return a 403/CAPTCHA placeholder.")
    fun `extracts metadata from live NYTimes short link through fallback`() {
        val result = EnrichmentService(RestClient.builder()).enrich("https://nyti.ms/4tisLZM")

        assertEquals("Pam Bondi Fired as Trump’s Attorney General", result.title)
        assertEquals(Instant.parse("2026-04-02T17:07:51Z"), result.publishedAt)
        assertEquals(
            "https://static01.nyt.com/images/2026/04/02/multimedia/00dc-bondiHFO-mzkg/00dc-bondiHFO-mzkg-largeHorizontalJumbo.jpg",
            result.thumbnail,
        )
    }
}
