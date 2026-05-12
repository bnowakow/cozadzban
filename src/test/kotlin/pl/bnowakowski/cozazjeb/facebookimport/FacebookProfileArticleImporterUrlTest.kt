// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.user.AppUserRepository

class FacebookProfileArticleImporterUrlTest {

    @Test
    fun `relative facebook post urls are normalized into canonical facebook post urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("toCleanFacebookUrl", String::class.java)
        method.isAccessible = true

        val raw = "/bartek.dobrowolski.nowakowski/posts/pfbid0M7aniN6eGaBnpHDeFDbzy273na651LUH8DBpbeaAyMt3YaZK3Ag4W2B6EKjpR6pul" +
            "?__cft__[0]=AZZxZ5ny2urM3XmQPSuB09mjfJUpUQgGUeCq9uTpxSBKMnL5Pjl07SFOiMatzYKYQ-poI2-Xu0OkmHVFq-8ehGCm2XHUScMxkG77vemsp0mS83CMnUB1kcnIHxG7vz5xNn7yTOgiN8Q4PxwH_BAidTeDazSz10Gh9k-42SYDZ9zHMNtMx23GpNgmt-y1i_6q2nyIUU3g0f-IO9WwA9oo5uwB" +
            "&__tn__=%2CO%2CP-R"

        assertEquals(
            "https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0M7aniN6eGaBnpHDeFDbzy273na651LUH8DBpbeaAyMt3YaZK3Ag4W2B6EKjpR6pul",
            method.invoke(importer, raw),
        )
    }

    @Test
    fun `external article urls are extracted from facebook preview description text`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("extractExternalArticleUrlFromText", String::class.java)
        method.isAccessible = true

        val facebookPostText = """
            Co za zjeb
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0QNQd21g5G2RNF77B2UWD9jxYFCMiYLgefeXfWA5bpmjnG7dCidWbxFYY8FexH6HHl
            0odxxKC5.com
            Kuba
            Więcej: https://www.donald.pl/artykuly/u5mW2EW9/okazalo-sie-ze-ziobro-wcale-nie-uciekl-do-usa-tylko-dostal-prace-jako-komentator-tv-republika.
        """.trimIndent()

        assertEquals(
            "https://www.donald.pl/artykuly/u5mW2EW9/okazalo-sie-ze-ziobro-wcale-nie-uciekl-do-usa-tylko-dostal-prace-jako-komentator-tv-republika",
            method.invoke(importer, facebookPostText),
        )
    }

    @Test
    fun `external article urls are extracted from facebook preview html with escaped query separators`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("extractExternalArticleUrlFromText", String::class.java)
        method.isAccessible = true

        val facebookPreviewHtml = """
            <a href="?__cft__[0]=ignored#?idd">0odxxKC5.com</a>
            <span data-ad-rendering-role="description">
                Więcej: https://www.donald.pl/artykuly/example?utm_source=facebook&amp;utm_medium=social
            </span>
        """.trimIndent()

        assertEquals(
            "https://www.donald.pl/artykuly/example?utm_source=facebook&utm_medium=social",
            method.invoke(importer, facebookPreviewHtml),
        )
    }

    @Test
    fun `facebook redirect urls are normalized before choosing external article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("extractExternalArticleUrlFromText", String::class.java)
        method.isAccessible = true

        val redirectUrl = "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.donald.pl%2Fartykuly%2Fu5mW2EW9%2Fokazalo-sie-ze-ziobro-wcale-nie-uciekl-do-usa-tylko-dostal-prace-jako-komentator-tv-republika%3Ffbclid%3DIwZXh0bgNhZW0CMTAAYnJpZBExcnNLSk15YnFHWEprQWFtWnNydGM&h=AUCGmspOkz_RXbiymHhO1nUZKmCYt9E6uTBJUG5BssOxyW2bLe811RlO9cyPTl6bDl7oJMowJVi-BXlRpq1fqu2U263axeCZGNCuCFFLPOmqO3cI7sCcVZS6-QHYW8MmvrkNgBWy-1hM-uYWXug"

        assertEquals(
            "https://www.donald.pl/artykuly/u5mW2EW9/okazalo-sie-ze-ziobro-wcale-nie-uciekl-do-usa-tylko-dostal-prace-jako-komentator-tv-republika?fbclid=IwZXh0bgNhZW0CMTAAYnJpZBExcnNLSk15YnFHWEprQWFtWnNydGM",
            method.invoke(importer, redirectUrl),
        )
    }
}
