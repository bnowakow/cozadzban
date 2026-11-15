// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ImagePromptDirectionTest {
    @Test
    fun `optional direction is appended to image prompt references without changing the article`() {
        assertEquals("Article title", appendImageDirection("Article title", null))
        assertEquals(
            "Article title | Image direction: blue background with a single chip",
            appendImageDirection("Article title", "  blue   background\nwith a single chip  "),
        )
        assertEquals(
            "Article text | Image direction: blue background with a single chip",
            appendImageDirection("Article text", "blue background with a single chip"),
        )
    }
}
