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
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook plugin page title is replaced with lead excerpt for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the U.S. military's national security objectives are \"fully achieved\" in Iran."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "Facebook",
            lead = lead,
            contentForCache = lead,
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    @Test
    fun `Facebook watch metrics title is replaced with lead excerpt for reel`() {
        val lead = "White House press secretary Karoline Leavitt told reporters " +
            "\"Americans will see oil and gas prices drop rapidly\" once the objectives are achieved."
        val title = titleForSave(
            url = "https://www.facebook.com/reel/1648200636595572",
            title = "1.1M views, 3K reactions | $lead | Reuters",
            lead = lead,
            contentForCache = "1.1M views, 3K reactions | $lead | Reuters",
        )

        assertEquals(lead.excerptForArticleTitle(), title)
    }

    private fun titleForSave(url: String, title: String?, lead: String?, contentForCache: String?): String? {
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
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, url, title, lead, contentForCache) as String?
    }
}

private fun String.excerptForArticleTitle(): String =
    if (length <= 120) this else take(120).trimEnd() + "..."
