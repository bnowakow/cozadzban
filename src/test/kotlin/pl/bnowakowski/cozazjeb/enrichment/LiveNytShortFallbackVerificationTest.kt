// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class LiveNytShortFallbackVerificationTest {

    @Test
    fun `extracts metadata from live NYTimes short link through fallback`() {
        val result = EnrichmentService(RestClient.builder()).enrich("https://nyti.ms/4tisLZM")

        assertEquals("Pam Bondi Fired as Trump’s Attorney General", result.title)
        assertEquals(Instant.parse("2026-04-02T17:07:51Z"), result.publishedAt)
    }
}
