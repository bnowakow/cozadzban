// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.web.client.RestClient

@ExtendWith(OutputCaptureExtension::class)
class LiveFacebookMzimuFallbackVerificationTest {

    @Test
    fun `live unavailable Facebook post is rejected instead of saved as profile title`(output: CapturedOutput) {
        val exception = assertThrows(EnrichmentException::class.java) {
            EnrichmentService(RestClient.builder()).enrich(
                "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
            )
        }

        assertEquals(EnrichmentException.Reason.NON_2XX, exception.reason)
        assertTrue(output.all.contains("Facebook post enrichment rejected before save"))
        assertTrue(output.all.contains("action=no-article-write"))
    }
}
