// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.user.AppUserRepository

class FacebookProfileArticleImporterMarkerTest {

    @Test
    fun `marker phrases include original and translated phrase`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("candidateMarkerPhrases")
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val markers = method.invoke(importer) as List<String>

        assertEquals(listOf("co za dzban", "what a fucker"), markers)
    }
}
