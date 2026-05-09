// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository

class ArticleServiceTitleTest {

    @Test
    fun `Facebook reel placeholder title is replaced with lead excerpt`() {
        val lead = "This Facebook reel has enough useful text to become the article title instead of the placeholder."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook reel",
            contentForCache = lead,
        )

        assertEquals(lead, title)
    }

    private fun titleForSave(url: String, title: String?, contentForCache: String?): String? {
        val service = ArticleService(
            articleRepository = mock<ArticleRepository>(),
            enrichmentService = mock<EnrichmentService>(),
            appUserRepository = mock<AppUserRepository>(),
            articleContentRepository = mock<ArticleContentRepository>(),
        )
        val method = ArticleService::class.java.getDeclaredMethod(
            "titleForSave",
            String::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, title, contentForCache) as String?
    }
}
