// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class LiveFacebookMzimuVerificationTest {

    @Test
    fun `live mzimu Facebook post is not cached as unavailable shell`() {
        val result = EnrichmentService(RestClient.builder()).enrich(
            "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
        )

        println(result)
        assertEquals("x", result.title)
    }
}
