// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArticleTitleShortenerTest {
    @Test
    fun `cleans a model reply into a single headline`() {
        assertEquals(
            "Prokurator w siedzibie TK po nagraniach z prezydentem Nawrockim",
            ArticleTitleShortener.cleanTitle(
                "\n„Prokurator w siedzibie TK po nagraniach z prezydentem Nawrockim.”\nextra line", 150,
            ),
        )
        assertEquals("Short and factual headline", ArticleTitleShortener.cleanTitle("Title: \"Short and factual headline\"", 150))
    }

    @Test
    fun `rejects replies that are not usable titles`() {
        assertNull(ArticleTitleShortener.cleanTitle("", 150))
        assertNull(ArticleTitleShortener.cleanTitle("Too short", 5))
        assertNull(ArticleTitleShortener.cleanTitle("x".repeat(151), 150))
        assertNull(ArticleTitleShortener.cleanTitle("Read more at https://example.com/story", 150))
    }
}
