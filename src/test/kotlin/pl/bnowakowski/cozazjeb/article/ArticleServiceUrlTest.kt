// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArticleServiceUrlTest {

    @Test
    fun `canonicalizeUrl strips Facebook tracking query parameters`() {
        val raw = "https://www.facebook.com/akurasinski/posts/pfbid034RS4LUSHXeQr9D7u5HBnXkovQpL28ygoF9LRqGGJPXoeU9tbAw5be1ZKvi2oix5il" +
            "?__cft__[0]=AZYLv1aAlEENIDt6J0rMQQex4ss1_wCyfKDCSqJahjIqiCMRh6JZfwK8OEnNJjGp0HvguVepHbBJflOEZTdClzGdhLDfXNSJh6TRfhIxgDi7yursoVXKxXRtg5H_wlAxczCDnxVSCH7sOC19na01sbACisH4gua6dQon51IyxUAQzUW5EbnPVPn822wTHYHhZ8cVjuEGeWaR58zZZ-5O7us0" +
            "&__tn__=%2CO%2CP-y-R"

        assertEquals(
            "https://www.facebook.com/akurasinski/posts/pfbid034RS4LUSHXeQr9D7u5HBnXkovQpL28ygoF9LRqGGJPXoeU9tbAw5be1ZKvi2oix5il",
            ArticleService.canonicalizeUrl(raw),
        )
    }

    @Test
    fun `canonicalizeUrl strips Facebook share tracking query parameters`() {
        assertEquals(
            "https://www.facebook.com/share/18e3PrKAEK/",
            ArticleService.canonicalizeUrl("https://www.facebook.com/share/18e3PrKAEK/?mibextid=wwXIfr"),
        )
    }

    @Test
    fun `canonicalizeUrl keeps Facebook photo identity query parameters while stripping tracking`() {
        assertEquals(
            "https://www.facebook.com/photo/?fbid=2957148791343834&set=a.359995211059218",
            ArticleService.canonicalizeUrl(
                "https://www.facebook.com/photo/?fbid=2957148791343834&set=a.359995211059218" +
                    "&__cft__[0]=AZbDN214kWeuGMkjcsTNlGxjqoCh5ThiBa&__tn__=EH-y-R",
            ),
        )
    }

    @Test
    fun `canonicalizeUrl keeps Facebook story identity query parameters while stripping tracking`() {
        assertEquals(
            "https://www.facebook.com/story.php?story_fbid=123&id=456",
            ArticleService.canonicalizeUrl(
                "https://www.facebook.com/story.php?story_fbid=123&id=456&__cft__[0]=AZb&__tn__=%2CO%2CP-R",
            ),
        )
    }

    @Test
    fun `canonicalizeUrl strips Instagram tracking query parameters`() {
        assertEquals(
            "https://www.instagram.com/reel/DW6kHAvsM-p/",
            ArticleService.canonicalizeUrl(
                "https://www.instagram.com/reel/DW6kHAvsM-p/?igsh=MW5saTB1aWt1aW5nMQ%3D%3D",
            ),
        )
    }

    @Test
    fun `canonicalizeUrl keeps query parameters for non Facebook URLs`() {
        assertEquals(
            "https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8",
            ArticleService.canonicalizeUrl("https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8"),
        )
    }
}
