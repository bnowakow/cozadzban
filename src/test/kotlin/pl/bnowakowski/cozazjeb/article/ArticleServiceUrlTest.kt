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
    fun `canonicalizeUrl keeps query parameters for non Facebook URLs`() {
        assertEquals(
            "https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8",
            ArticleService.canonicalizeUrl("https://youtu.be/Xi-HcxcM3dc?is=5gYGOGAFM0CG2OQ8"),
        )
    }
}
