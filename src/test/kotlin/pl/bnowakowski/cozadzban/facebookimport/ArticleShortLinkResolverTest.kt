// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class ArticleShortLinkResolverTest {
    @Test
    fun `resolves bitly to publisher URL before import`() {
        val destination = "https://www.economist.com/united-states/2026/11/13/the-jensen-huang-scandal-reaches-donald-trumps-cabinet"

        assertEquals(destination, ArticleShortLinkResolver.resolve("http://bit.ly/4iQcwQ3") { URI(destination) })
    }

    @Test
    fun `does not accept an insecure or unresolved destination`() {
        assertNull(ArticleShortLinkResolver.resolve("http://bit.ly/4iQcwQ3") { URI("http://example.com/story") })
        assertNull(ArticleShortLinkResolver.resolve("http://bit.ly/4iQcwQ3") { null })
        assertNull(ArticleShortLinkResolver.resolve("https://example.com/story") { URI("https://other.example/story") })
    }
}
