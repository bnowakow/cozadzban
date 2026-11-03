// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FacebookSourcePostReferenceTest {
    @Test
    fun `parses Page post permalinks without mixing Page and post IDs`() {
        assertEquals(
            FacebookSourcePostReference("rmf24", "pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl"),
            FacebookSourcePostReference.fromUrl(
                "https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl?mibextid=abc",
            ),
        )
        assertEquals(
            "100068095113051_1287890776824081",
            FacebookSourcePostReference.fromUrl(
                "https://www.facebook.com/permalink.php?story_fbid=1287890776824081&id=100068095113051",
            )?.directGraphId,
        )
    }

    @Test
    fun `rejects other Facebook content and lookalike hosts`() {
        assertNull(FacebookSourcePostReference.fromUrl("https://facebook.com.evil.example/page/posts/123"))
        assertNull(FacebookSourcePostReference.fromUrl("https://www.facebook.com/reel/123"))
        assertNull(FacebookSourcePostReference.fromUrl("https://www.facebook.com/share/p/abc"))
        assertNull(FacebookSourcePostReference.fromUrl("https://www.facebook.com/groups/3/posts/123"))
        assertNull(FacebookSourcePostReference.fromUrl("https://www.facebook.com/story.php?story_fbid=%ZZ&id=123"))
    }
}
