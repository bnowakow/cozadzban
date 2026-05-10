// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class LiveFacebookMzimuFallbackVerificationTest {

    @Test
    fun `live unavailable Facebook post falls back to profile title`() {
        val result = EnrichmentService(RestClient.builder()).enrich(
            "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
        )

        assertEquals("Facebook post by mzimu", result.title)
        assertNull(result.thumbnail)
        assertNull(result.lead)
        assertNull(result.publishedAt)
        assertNull(result.plainText)
    }
}
