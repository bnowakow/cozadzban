// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.analytics

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsIndexHtmlRequestListenerTest {

    @Test
    fun `injects analytics init script as raw data node`() {
        val doc = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
        val listener = AnalyticsIndexHtmlRequestListener(
            AnalyticsProperties(
                googleMeasurementId = "G-TEST",
                statcounterProjectId = "123",
                statcounterSecurityId = "abc",
            ),
        )

        injectAnalyticsScript(listener, doc)

        val script = doc.head().selectFirst("#czj-analytics-init")
        assertEquals("script", script?.tagName())
        assertTrue(script?.childNode(0) is DataNode)
        assertTrue(script?.data().orEmpty().contains("czj_analytics_consent"))
        assertTrue(script?.data().orEmpty().contains("G-TEST"))
        assertTrue(script?.data().orEmpty().contains("window.sc_project = 123"))
    }

    private fun injectAnalyticsScript(listener: AnalyticsIndexHtmlRequestListener, doc: org.jsoup.nodes.Document) {
        val method = AnalyticsIndexHtmlRequestListener::class.java.getDeclaredMethod(
            "injectAnalyticsScript",
            org.jsoup.nodes.Document::class.java,
        )
        method.isAccessible = true
        method.invoke(listener, doc)
    }
}
