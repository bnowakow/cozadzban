// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class ApifyRunTimeoutTest {
    @Test
    fun `Apify imports use their own shorter timeout, capped by the general one`() {
        val defaults = FacebookImportProperties()
        assertEquals(Duration.ofMinutes(10), defaults.runTimeoutFor(FacebookImportType.APIFY))
        assertEquals(Duration.ofHours(1), defaults.runTimeoutFor(FacebookImportType.SELENIUM))
        assertEquals(Duration.ofHours(1), defaults.runTimeoutFor(null))

        val capped = FacebookImportProperties(runTimeout = Duration.ofMinutes(5), apify = FacebookImportProperties.Apify(runTimeout = Duration.ofMinutes(20)))
        assertEquals(Duration.ofMinutes(5), capped.effectiveApifyRunTimeout())

        val invalid = FacebookImportProperties(apify = FacebookImportProperties.Apify(runTimeout = Duration.ZERO))
        assertEquals(Duration.ofMinutes(10), invalid.effectiveApifyRunTimeout())
    }

    @Test
    fun `Apify stops the actor before the app stops waiting, which is before the run times out`() {
        assertEquals(Duration.ofMinutes(9), FacebookApifyArticleImporter.pollingTimeout(Duration.ofMinutes(10)))
        assertEquals(Duration.ofMinutes(8), FacebookApifyArticleImporter.actorTimeout(Duration.ofMinutes(10)))
        assertEquals(Duration.ofMinutes(1), FacebookApifyArticleImporter.pollingTimeout(Duration.ofSeconds(30)))
        assertEquals(Duration.ofMinutes(1), FacebookApifyArticleImporter.actorTimeout(Duration.ofSeconds(30)))
    }
}
