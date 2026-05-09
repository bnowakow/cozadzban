// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Instant

class LiveDlvrItVerificationTest {

    @Test
    fun `enriches live DlvrIt short link`() {
        val result = EnrichmentService(RestClient.builder()).enrich("http://dlvr.it/TS1M9k")

        assertNotNull(result.title)
        assertTrue(result.title!!.contains("Święczkowski", ignoreCase = true), result.title)
        assertNotNull(result.thumbnail)
        assertTrue(result.thumbnail!!.startsWith("https://cdn.wiadomosci.onet.pl/"), result.thumbnail)
        assertEquals(Instant.parse("2026-04-13T16:59:27Z"), result.publishedAt)
    }
}
