// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.Mockito.mock as mockitoMock
import org.mockito.Mockito.withSettings
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.user.AppUserRepository
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.By
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriver.TargetLocator
import org.openqa.selenium.WebElement
import java.time.Duration

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
            Co za dzban
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
    fun `visibly truncated external article urls are ignored`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("extractExternalArticleUrlFromText", String::class.java)
        method.isAccessible = true

        assertNull(
            method.invoke(
                importer,
                "Więcej: https://www.donald.pl/.../rozenek-majdan-zachwycona",
            ),
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

    @Test
    fun `facebook thumbnail links are ignored when a real external article link is present`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mock<WebDriver>()
        val element = mock<WebElement>()
        val thumbnailLink = mock<WebElement>()
        val articleLink = mock<WebElement>()

        whenever(element.text).thenReturn("Co za dzban\ndonald.pl\nJeszcze tylko dać mu schowek na miotły")
        whenever(element.findElements(any())).thenReturn(listOf(thumbnailLink, articleLink))
        whenever(thumbnailLink.getAttribute("href")).thenReturn(
            "https://scontent-waw2-2.xx.fbcdn.net/v/t39.30808-1/320732156_1180463042845884_2036739628543932095_n.jpg?stp=cp0_dst-jpg_s80x80_tt6",
        )
        whenever(articleLink.getAttribute("href")).thenReturn(
            "https://www.donald.pl/artykuly/example",
        )

        assertEquals(
            "https://www.donald.pl/artykuly/example",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `stale post containers are skipped while finding post urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mock<WebDriver>()
        val element = mock<WebElement>()

        whenever(element.text).thenThrow(StaleElementReferenceException("detached"))

        assertNull(method.invoke(importer, driver, element))
    }

    @Test
    fun `stale post containers do not break link diagnostics`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("linkDiagnostics", WebElement::class.java)
        method.isAccessible = true

        val element = mock<WebElement>()

        whenever(element.findElements(any())).thenThrow(StaleElementReferenceException("detached"))

        assertEquals(emptyList<Any>(), method.invoke(importer, element))
    }

    @Test
    fun `remote facebook import fails loudly when api key is missing`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(
                targetApiBaseUrl = "https://cozadzban.pl",
            ),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "createArticle",
            Class.forName("pl.bnowakowski.cozadzban.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate"),
            Long::class.javaPrimitiveType,
        )
        method.isAccessible = true

        val candidateClass = Class.forName("pl.bnowakowski.cozadzban.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate")
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        val candidate = constructor.newInstance("https://www.donald.pl/artykuly/example", "text")

        val ex = assertThrows(InvocationTargetException::class.java) {
            method.invoke(importer, candidate, 1L)
        }

        assertEquals(
            "Remote Facebook import is misconfigured: set both APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL and APP_FACEBOOK_IMPORT_TARGET_API_KEY",
            ex.cause?.message,
        )
    }

    @Test
    fun `shared facebook post urls are preferred over svg namespace noise`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val noiseLink = mock<WebElement>()
        val body = mock<WebElement>()
        val sharedPostUrl =
            "https://www.facebook.com/TheOther98/posts/pfbid0yg1Q7qK1B4XUFmJwCMvx1eXLgcNjKY1MniDQo8KKLkbkRWL1qfF2HxwH3qaBL3aGl?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R"
        val openedPageSource = """
            Repost:
            $sharedPostUrl
            https://www.fbsbx.com/maw_proxy_page/?__cci=FQAREiIVBBn1VAQGCgwOEBIWGBweICImKCosMDI2ODo8sgJCROoCZm5wdr4CfI4BkAHCApIBlgGYAZoB5gKiAcgCugKoAawBrgGwAbIBtAHOAroBvgHWAsABwgHKAsYByAHKAcwBzALQAdQB2AHaAegC1ALyAvAC5AHoAfgB%2BgHgAvwBigLGAowCjgKQAtgCmAKiAhgMRmFjZWJvb2tIb3N0GAN3ZWIYFVhDb21ldFBob3RvQ29udHJvbGxlcgA%3D.AapoIosQxmR-2RHUA0-dpTxXSOHDhrrotKmiISQ1AK3_0uA6
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid031egEbQtCXbCE4cM5MAy5zPEoCHwipaSNb82jc5uMyeyfj4d9GeT8zrNmHsx2691hl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(element.findElements(any())).thenReturn(listOf(noiseLink))
        whenever(noiseLink.getAttribute("href")).thenReturn("http://www.w3.org/2000/svg")
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/TheOther98/posts/pfbid0yg1Q7qK1B4XUFmJwCMvx1eXLgcNjKY1MniDQo8KKLkbkRWL1qfF2HxwH3qaBL3aGl",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `shared facebook post urls outrank unrelated photo urls inside opened posts`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            Noise:
            https://www.facebook.com/photo/?fbid=934444956156520&set=a.297920313142324&notif_id=1778436967465666&notif_t=feedback_reaction_generic&ref=notif
            https://www.facebook.com/serwisdonaldpl/posts/pfbid02SXLtusqBivEYVoiccGGc9UMV9cu66h7BSRpEBGZgjpz448Mwe8hTpxa7UEmw6Ftbl?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0oTm7AmPCdGddLfbxLZbjcq5JvTDHMAA7wyCwhRyVXDUuBQRKuKc6RyumCFEvoEFjl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/serwisdonaldpl/posts/pfbid02SXLtusqBivEYVoiccGGc9UMV9cu66h7BSRpEBGZgjpz448Mwe8hTpxa7UEmw6Ftbl",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `malformed facebook photo urls do not outrank shared post urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            Noise:
            https://www.facebook.com/photo/?__tn__=%2CO*F#?jfj
            https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid02cEkxoSWVBbQM8kwok36HJsqUfc6mcWMYpq43X3Fv9c6foLRFHxiT9xWBEPT4D1zzl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `facebook notification photo urls are ignored when a shared post is present`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            Noise:
            https://www.facebook.com/photo/?fbid=934444956156520&set=a.297920313142324&notif_id=1778436967465666&notif_t=feedback_reaction_generic&ref=notif
            https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid02cEkxoSWVBbQM8kwok36HJsqUfc6mcWMYpq43X3Fv9c6foLRFHxiT9xWBEPT4D1zzl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `nested facebook post search ignores photos comments profile links and caps fan out`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "nestedFacebookPostCandidatesToOpen",
            List::class.java,
            String::class.java,
            Set::class.java,
        )
        method.isAccessible = true

        val postUrl = "https://www.facebook.com/photo/?fbid=1&set=a.1"
        val candidates = listOf(
            postUrl,
            "https://www.facebook.com/photo/?fbid=2&set=a.2",
            "https://www.facebook.com/aljazeera/posts/pfbid1?comment_id=123",
            "https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid-profile",
            "https://www.facebook.com/aljazeera/posts/pfbid1?__tn__=*F",
            "https://www.facebook.com/aljazeera/posts/pfbid1#",
            "https://www.facebook.com/donaldpl/posts/pfbid2",
            "https://www.facebook.com/third/posts/pfbid3",
        )

        assertEquals(
            listOf(
                "https://www.facebook.com/aljazeera/posts/pfbid1?__tn__=*F",
                "https://www.facebook.com/donaldpl/posts/pfbid2",
            ),
            method.invoke(importer, candidates, postUrl, emptySet<String>()),
        )
        assertEquals(
            emptyList<String>(),
            method.invoke(importer, candidates, postUrl, setOf(postUrl)),
        )
    }

    @Test
    fun `profile matched external article urls outrank unrelated opened facebook page links`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "preferredExternalArticleUrlForFacebookPost",
            String::class.java,
            Iterable::class.java,
        )
        method.isAccessible = true

        assertEquals(
            "https://thenextweb.com/news/palantir-retail-sell-off-germany-military-rejection?fbclid=abc",
            method.invoke(
                importer,
                "https://www.facebook.com/thenextweb/posts/pfbid029YvfVCrCbZmEBmhf6H7Kdg1Sw6Y1ojKhwgD522v9emzXjFnQ736SxKVM3SWrhmMkl",
                listOf(
                    "https://www.bankobranie.pl/2026/04/erste-konto-smart-z-bonusem.html",
                    "https://podyplomowestudia.eu/",
                    "https://thenextweb.com/news/palantir-retail-sell-off-germany-military-rejection?fbclid=abc",
                ),
            ),
        )
    }

    @Test
    fun `host mention matches spaced publication names`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "isUrlHostMentionedInText",
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true

        assertEquals(
            true,
            method.invoke(
                importer,
                "https://thenextweb.com/news/palantir-retail-sell-off-germany-military-rejection",
                "The Next Web · Palantir grew 85%. Germany shortlisted 3 rivals instead.",
            ),
        )
    }

    @Test
    fun `youtube noise does not outrank a nested shared facebook post`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            Noise:
            https://youtu.be/cuFqOkT5P6w?is=UPmTSiXXjJZrCyrR
            https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid02cEkxoSWVBbQM8kwok36HJsqUfc6mcWMYpq43X3Fv9c6foLRFHxiT9xWBEPT4D1zzl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02gTAsX1c6sXGdyavT1aa9txW5aum6ucjxaCxzDFrhHVSkzNgNBfHp4DhDXCVeziK3l",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `youtube noise does not outrank a facebook reel link on the same post`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val reelLink = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            Shared post:
            https://www.facebook.com/reel/2758125771253657/?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://youtu.be/cuFqOkT5P6w?is=UPmTSiXXjJZrCyrR
            """.trimIndent(),
        )
        whenever(element.findElements(any())).thenReturn(listOf(reelLink))
        whenever(reelLink.getAttribute("href")).thenReturn(
            "https://www.facebook.com/reel/2758125771253657/?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R",
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/reel/2758125771253657/",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `shared facebook reel in post html outranks opening the marker post url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(
                profileUrl = "https://www.facebook.com/bartek.dobrowolski.nowakowski",
            ),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val js = driver as JavascriptExecutor
        val element = mock<WebElement>()
        val markerPostLink = mock<WebElement>()
        val markerPostUrl =
            "https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl?comment_id=948743507964221"
        val reelUrl =
            "https://www.facebook.com/reel/2758125771253657/?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R"

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            Co za dzban
            Jakub Wiech pisze - mikroblog is in Warsaw.
            Mili Państwo, prezydent chce referendum ws. polityki klimatycznej
            """.trimIndent(),
        )
        whenever(element.findElements(any())).thenReturn(listOf(markerPostLink))
        whenever(markerPostLink.getAttribute("href")).thenReturn(markerPostUrl)
        whenever(js.executeScript(any<String>(), any<Array<Any>>())).thenReturn(
            """
            <a href="$markerPostUrl">Comment</a>
            <a href="$reelUrl">Shared reel</a>
            """.trimIndent(),
        )

        assertEquals(
            "https://www.facebook.com/reel/2758125771253657/",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `facebook reel urls outrank photo urls inside opened posts`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val openedPageSource = """
            https://www.facebook.com/photo/?fbid=934444956156520&set=a.297920313142324&notif_id=1778436967465666&notif_t=feedback_reaction_generic&ref=notif
            https://www.facebook.com/reel/2758125771253657/?__cft__[0]=ignored&__tn__=%2CO%2CP-y-R
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(openedPageSource)
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            "https://www.facebook.com/reel/2758125771253657/",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `facebook redirect article urls outrank photo urls inside opened posts`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val articleUrl = "https://www.donald.pl/artykuly/3RAih9AJ/mentzen-wyjasnil-ze-zwiazki-jednoplciowe-juz-sa-legalne-bo-przeciez-nie-idzie-sie-za-nie-siedziec"
        val redirectUrl =
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.donald.pl%2Fartykuly%2F3RAih9AJ%2Fmentzen-wyjasnil-ze-zwiazki-jednoplciowe-juz-sa-legalne-bo-przeciez-nie-idzie-sie-za-nie-siedziec%3Ffbclid%3DIwZXh0bgNhZW0CMTAAc3J0YwZhcHBfaWQQMjIyMDM5MTc4ODIwMDg5MgABHkH2FtNLFqJm9_QqnN5fjdO-e4uaOxzmHMX_HO61BBywMxLEneorc3sgqWhO_aem_d28BsCcbBY84yovahLx1uw&h=AUD-O-2_CwWP3UEW8dTg75CAVgQIgF_KQHZYzFsK4PovAm73_LZ59SD9PyBL5dE9J136TIrKGlijbbhdG9-kLITceReB4TX_kxNr0lxGyFaUPEdnva4HW452UmiqC-Epnc8_UTgs3GcKbO18Q0ac9Q&__tn__=-UK-y-R&c[0]=AUDgSYwwxU3fC3BQJbYd_b7KYoMbLNV0Y9SEFBlK0Gf_LpiRm6Ju7Z7XkwveTNAiRpYQ_27NlJDWQmMlywQwLHiJ-UuESneAa9JutmHeEtBk9XoaeYH00v-RlqTf9b1CWQF1S9ptbUUUPcwpHjn1MY9Lev1CngjHTE4mJFTvXcMyCQD8UfTzGDJKqpxF0ZFYl8WnxhYMJFw1iXdP6U_baLHGx9dJ2L-g0wmH26Y6vFj5jA"
        val openedPageSource = """
            https://www.facebook.com/photo.php?fbid=1500106161786298&set=p.15001061
            $redirectUrl
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0g1j4o1emzjr3MB8pn9gvoH7wCyrZkahLAvarWFK6iT8X1Ba25PKYe3uELNm6UsyFl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Więcej: $articleUrl")
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            articleUrl,
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `facebook redirect article urls outrank reel tab noise inside opened posts`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val body = mock<WebElement>()
        val articleUrl = "https://www.donald.pl/artykuly/vFc46sAQ/wegry-oddaly-ukrainie-kase-ktora-sobie-na-spontanie-aresztowaly"
        val redirectUrl =
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.donald.pl%2Fartykuly%2FvFc46sAQ%2Fwegry-oddaly-ukrainie-kase-ktora-sobie-na-spontanie-aresztowaly%3Ffbclid%3DIwZXh0bgNhZW0CMTAAc3J0YwZhcHBfaWQQMjIyMDM5MTc4ODIwMDg5MgABHg34o09RStprVuz56ooKGcj0ukb1ci1ZVkX4utkDXnP0Peht6lB792bNf0c7_aem_OinABwMIMtDEpscNF6J39Q&h=AUAfnHIq-snlj3qDjbmQ0luUoTgnm-SXJctGB1LTwvd_YVJTkEbuQJRV9fb95NfGdso7mfMJkL1krtWirYtQkaqFETwEQEKsKdUG6Fgb3nk9jFdjudPLybEfHa9zvpNI7ooG706r2FnzJGXfRImFzg&__tn__=-UK-y-R&c[0]=AUBmjpJTj1XbRJ2G9yvq0bJLMlA4LZ-r2tK_fBmPseu9shY1ff4EnB727pp040qEQUccdRM5JCBi_V4jVZ4Lz1SZ3d2bg4xrrO1C3D-ulH3fDRt0iN6GjIsLTukv1DeqLirssAIvxhIkrR9bcAjHJvrUle02MFuCKyS2TYpkhG0zYeJMHd1XqAPUBtnE9quUSmY0NoiRV5EFIMHnoHiRiIXbdKgEzcd6abjRWfsciykfGw"
        val openedPageSource = """
            https://www.facebook.com/reel/?s=tab
            $redirectUrl
        """.trimIndent()

        whenever(element.text).thenReturn(
            """
            Bartek Dobrowolski-Nowakowski
            https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid02T33o317tEoBtEY3CMhsBjQJqrZ9A93SqadshafagMK7vdBoP1j7Rq1ZMzuvAFcxcl?__cft__[0]=ignored&__tn__=%2CO%2CP-R
            """.trimIndent(),
        )
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Więcej: $articleUrl")
        whenever(driver.pageSource).thenReturn(openedPageSource)

        assertEquals(
            articleUrl,
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `html fallback prefers shared facebook posts over photo permalinks`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("extractPostUrlFromHtml", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val js = driver as JavascriptExecutor
        val element = mock<WebElement>()
        val html = """
            <a href="https://www.facebook.com/photo.php?fbid=1777518183208990&amp;set=p.1777518183208990&amp;type=3&amp;__tn__=R*F">Radio TOK FM</a>
            <a href="https://www.facebook.com/tokfm/posts/pfbid026ZVrRC8b9XdWH12GqSmN6Yhqdu7LAQ1VbLQxkby6PN1wenpPwEAj5SRT5HBQaJvNl?__cft__[0]=ignored&amp;__tn__=%2CO%2CP-y-R">Shared post</a>
        """.trimIndent()

        whenever(js.executeScript(any<String>(), any<Array<Any>>())).thenReturn(html)

        assertEquals(
            "https://www.facebook.com/tokfm/posts/pfbid026ZVrRC8b9XdWH12GqSmN6Yhqdu7LAQ1VbLQxkby6PN1wenpPwEAj5SRT5HBQaJvNl",
            method.invoke(importer, driver, element),
        )
    }

    @Test
    fun `opened facebook posts ignore external page links whose host is not visible in the post text`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "extractCandidateUrlFromFacebookPost",
            WebDriver::class.java,
            String::class.java,
            Set::class.java,
        )
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val body = mock<WebElement>()
        val supportLink = mock<WebElement>()
        val postUrl = "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid02vNtYBBGLw8qC1atWU1X3E9XNbY3BJNAFaKNGSsGCbUvPNUesM4Tiyot9Ah1Eetf1l"

        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Jakub Wiech pisze - mikroblog\nMili Państwo, wiecie dlaczego Polska przegrywa negocjacje?")
        whenever(driver.findElements(any())).thenReturn(
            emptyList(),
            listOf(supportLink),
            listOf(supportLink),
        )
        whenever(supportLink.getAttribute("href")).thenReturn("https://patronite.pl/jakubwiech?fbclid=ignored")
        whenever(driver.pageSource).thenReturn(
            """<a href="https://patronite.pl/jakubwiech?fbclid=ignored">Support</a>""",
        )

        assertEquals(null, method.invoke(importer, driver, postUrl, emptySet<String>()))
    }

    @Test
    fun `opened facebook posts accept external links whose host is visible in the post text`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod(
            "extractCandidateUrlFromFacebookPost",
            WebDriver::class.java,
            String::class.java,
            Set::class.java,
        )
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val body = mock<WebElement>()
        val articleLink = mock<WebElement>()
        val postUrl = "https://www.facebook.com/serwisdonaldpl/posts/pfbid02SXLtusqBivEYVoiccGGc9UMV9cu66h7BSRpEBGZgjpz448Mwe8hTpxa7UEmw6Ftbl"
        val articleUrl = "https://www.donald.pl/artykuly/example"
        val redirectUrl = "https://l.facebook.com/l.php?u=https%3A%2F%2Fwww.donald.pl%2Fartykuly%2Fexample&h=ignored"

        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("donald.pl\nKrótki opis artykułu")
        whenever(driver.findElements(any())).thenReturn(
            emptyList(),
            listOf(articleLink),
            listOf(articleLink),
        )
        whenever(articleLink.getAttribute("href")).thenReturn(redirectUrl)
        whenever(driver.pageSource).thenReturn("""<a href="$redirectUrl">donald.pl</a>""")

        assertEquals(articleUrl, method.invoke(importer, driver, postUrl, emptySet<String>()))
    }

    @Test
    fun `w3 svg namespace urls are ignored as article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isExternalArticleUrl", String::class.java)
        method.isAccessible = true

        assertEquals(false, method.invoke(importer, "http://www.w3.org/2000/svg"))
    }

    @Test
    fun `facebook maw proxy urls are ignored as article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isExternalArticleUrl", String::class.java)
        method.isAccessible = true

        assertEquals(
            false,
            method.invoke(
                importer,
                "https://www.fbsbx.com/maw_proxy_page/?__cci=FQAREiIVBBn1VAQGCgwOEBIWGBweICImKCosMDI2ODo8",
            ),
        )
    }

    @Test
    fun `youtube and meta ai urls are ignored as article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isExternalArticleUrl", String::class.java)
        method.isAccessible = true

        assertEquals(false, method.invoke(importer, "https://youtu.be/cuFqOkT5P6w?is=UPmTSiXXjJZrCyrR"))
        assertEquals(false, method.invoke(importer, "https://www.meta.ai/?utm_source=facebook_bookmarks"))
    }

    @Test
    fun `allegro marketplace offers are ignored as article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isExternalArticleUrl", String::class.java)
        method.isAccessible = true

        assertEquals(false, method.invoke(importer, "https://allegro.pl/oferta/pilka-fifa-world-"))
    }

    @Test
    fun `instagram profile urls are ignored as article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isExternalArticleUrl", String::class.java)
        method.isAccessible = true

        assertEquals(false, method.invoke(importer, "https://www.instagram.com/_u/potus?fbclid=ignored"))
        assertEquals(false, method.invoke(importer, "https://www.instagram.com/potus?fbclid=ignored"))
        assertEquals(true, method.invoke(importer, "https://www.instagram.com/reel/DW6kHAvsM-p/"))
    }

    @Test
    fun `facebook reel tab urls are not selected as post urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("isFacebookPostUrl", String::class.java)
        method.isAccessible = true

        assertEquals(false, method.invoke(importer, "https://www.facebook.com/reel/?s=tab"))
    }

    @Test
    fun `facebook maw proxy links are not selected as post urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mock<WebDriver>()
        val element = mock<WebElement>()
        val proxyLink = mock<WebElement>()

        whenever(element.text).thenReturn("Co za dzban")
        whenever(element.findElements(any())).thenReturn(listOf(proxyLink))
        whenever(proxyLink.getAttribute("href")).thenReturn(
            "https://www.fbsbx.com/maw_proxy_page/?__cci=FQAREiIVBBn1VAQGCgwOEBIWGBweICImKCosMDI2ODo8",
        )

        assertEquals(null, method.invoke(importer, driver, element))
    }

    @Test
    fun `photo posts are opened to extract the article url inside them`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()

        whenever(element.text).thenReturn("Co za dzban")
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(photoLink.getAttribute("href")).thenReturn(
            "https://www.facebook.com/photo/?fbid=1031328785895419&set=a.267259188969053",
        )
        whenever(driver.findElement(any())).thenReturn(body)
        val articleUrl = "https://www.donald.pl/artykuly/u5mW2EW9/okazalo-sie-ze-ziobro-wcale-nie-uciekl-do-usa-tylko-dostal-prace-jako-komentator-tv-republika"
        whenever(body.text).thenReturn("Więcej: $articleUrl")
        whenever(driver.pageSource).thenReturn("Więcej: $articleUrl")

        val result = method.invoke(importer, driver, element)

        assertEquals(
            articleUrl,
            result,
        )
    }

    @Test
    fun `shared photo posts without extracted article url are returned for import`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=934444956156520&set=a.297920313142324"

        whenever(element.text).thenReturn("Co za dzban")
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Only Facebook photo content\n$photoUrl")
        whenever(driver.pageSource).thenReturn("Only Facebook photo content\n$photoUrl")

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `shared photo posts ignore unrelated container links and fall back to source post url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val unrelatedLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=983286114627177&set=pcb.983297487959373"
        val unrelatedUrl = "http://arianagrande.lnk.to/htimylm"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za zjeb · Sławosz Uznański-Wiśniewski · " +
                "Nie mogę odnieść się do treści listu napisanego przez Panią Prezes Polskiej Agencji Kosmicznej.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink, unrelatedLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(unrelatedLink.getAttribute("href")).thenReturn(unrelatedUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Only Facebook photo content\n$photoUrl")
        whenever(driver.pageSource).thenReturn("Only Facebook photo content\n$photoUrl")

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `opened photo posts prefer visible article links over unrelated container links`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val unrelatedContainerLink = mock<WebElement>()
        val articleLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=1423580749810707&set=a.642107707958019"
        val articleUrl = "https://thenextweb.com/news/palantir-retail-sell-off-germany-military-rejection"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za dzban · The Next Web · Palantir grew 85%.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink, unrelatedContainerLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(unrelatedContainerLink.getAttribute("href")).thenReturn("https://wyborcza.pl/magazyn/")
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(
            emptyList(),
            emptyList(),
            listOf(unrelatedContainerLink, articleLink),
            listOf(unrelatedContainerLink, articleLink),
        )
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "The Next Web · Palantir grew 85%. Germany shortlisted 3 rivals instead.",
        )
        whenever(driver.pageSource).thenReturn(
            """<a href="https://wyborcza.pl/magazyn/">Magazyn</a><a href="$articleUrl">The Next Web</a>""",
        )
        whenever(articleLink.getAttribute("href")).thenReturn(articleUrl)

        assertEquals(articleUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `opened photo posts ignore creator support profile links and fall back to source post url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val supportLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=1501965407964887&set=a.248625223298918"
        val supportUrl = "https://patronite.pl/jakubwiech"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za dzban · Jakub Wiech pisze - mikroblog · " +
                "Mili Państwo, ten artykuł Rzeczpospolitej jest po prostu zwykłą manipulacją.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(
            emptyList(),
            emptyList(),
            listOf(supportLink),
            listOf(supportLink),
        )
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "Jakub Wiech pisze - mikroblog\n" +
                "Mili Państwo, ten artykuł Rzeczpospolitej jest po prostu zwykłą manipulacją.\n" +
                "patronite.pl",
        )
        whenever(driver.pageSource).thenReturn("""<a href="$supportUrl">patronite.pl/jakubwiech</a>""")
        whenever(supportLink.getAttribute("href")).thenReturn(supportUrl)

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `opened photo posts ignore visible creator support urls and fall back to source post url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=1501965407964887&set=a.248625223298918"
        val supportUrl = "https://patronite.pl/jakubwiech"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za dzban · Jakub Wiech pisze - mikroblog · " +
                "Mili Państwo, ten artykuł Rzeczpospolitej jest po prostu zwykłą manipulacją.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "Jakub Wiech pisze - mikroblog\n" +
                "Mili Państwo, ten artykuł Rzeczpospolitej jest po prostu zwykłą manipulacją.\n" +
                supportUrl,
        )
        whenever(driver.pageSource).thenReturn(supportUrl)

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `shared photo posts ignore instagram profile chrome and fall back to source post url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=122175532688723345&set=a.122108345924723345"
        val instagramProfileUrl = "https://www.instagram.com/_u/potus?fbclid=ignored"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za dzban · The White House is with President Donald J. Trump.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "The White House is with President Donald J. Trump.\n$instagramProfileUrl",
        )
        whenever(driver.pageSource).thenReturn(
            """<a href="$instagramProfileUrl">Instagram</a>""",
        )

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `opened photo posts fall back to the photo when nested facebook posts do not expose article urls`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=1501965407964887&set=a.248625223298918"
        val sharedPostUrl =
            "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0F2BWZwcZ74V6pkiR9cHashJnKhJtYCY3Ein4PXhYE2RJCsR4H55JzazBJqfv8jusl"
        val escapedSharedPostUrl = sharedPostUrl
            .replace("/", "\\/")
            .replace("?", "\\u003F")
            .replace("&", "\\u0026")
        val pageSource = """
            <script>
              ["CometFeedStory", "$escapedSharedPostUrl?__cft__[0]=ignored\u0026__tn__=%2CO%2CP-y-R"]
            </script>
            <a href="https://patronite.pl/jakubwiech?fbclid=ignored">patronite.pl/jakubwiech</a>
        """.trimIndent()

        whenever(element.text).thenReturn("Co za dzban Jakub Wiech pisze - mikroblog")
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "Jakub Wiech pisze - mikroblog\nMili Państwo, ten artykuł Rzeczpospolitej jest manipulacją.\npatronite.pl",
        )
        whenever(driver.pageSource).thenReturn(pageSource)

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `opened photo posts do not import unrelated nested facebook posts when original text has no url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=26658416310482918&set=a.174617535956156"
        val originalPostUrl =
            "https://www.facebook.com/akurasinski/posts/pfbid02SS6xH6AQX2ejwDB3AvUwhSEV7XhcjkxNFPMPxWwu2hxUCcuSFuUMSVJij9VL8hssl"
        val unrelatedNestedPostUrl =
            "https://www.facebook.com/Indynxt/posts/pfbid0347R7fEt3GSP6LgYk87pwvjVHj46t6SrdL333S6jFZs8TwrxeZbt6xxoHLa4gJBBZl"

        whenever(element.text).thenReturn(
            "Bartek Dobrowolski-Nowakowski · Co za dzban What a fucker · Artur Kurasiński · To jest porażka obecnego rządu.",
        )
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(
            setOf("main"),
            setOf("main", "popup-photo"),
            setOf("main"),
            setOf("main", "popup-original"),
            setOf("main"),
        )
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn(
            "Artur Kurasiński · To jest porażka obecnego rządu.",
            "INDY NXT · Six races in and the fight for the championship could not be closer",
        )
        whenever(driver.pageSource).thenReturn(
            "$originalPostUrl $photoUrl",
            "$unrelatedNestedPostUrl",
        )

        assertEquals(photoUrl, method.invoke(importer, driver, element))
    }

    @Test
    fun `shared photo posts are not returned when marker text contains an external url`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(waitAfterPageOpen = Duration.ZERO),
            mock<AppUserRepository>(),
            mock<ArticleService>(),
        )

        val method = importer.javaClass.getDeclaredMethod("findPostUrl", WebDriver::class.java, WebElement::class.java)
        method.isAccessible = true

        val driver = mockitoMock(
            WebDriver::class.java,
            withSettings().extraInterfaces(JavascriptExecutor::class.java),
        ) as WebDriver
        val targetLocator = mock<TargetLocator>()
        val element = mock<WebElement>()
        val photoLink = mock<WebElement>()
        val body = mock<WebElement>()
        val photoUrl = "https://www.facebook.com/photo/?fbid=934444956156520&set=a.297920313142324"

        whenever(element.text).thenReturn("Co za dzban https://www.donald.pl/.../example")
        whenever(element.findElements(any())).thenReturn(listOf(photoLink))
        whenever(photoLink.getAttribute("href")).thenReturn(photoUrl)
        whenever(driver.windowHandle).thenReturn("main")
        whenever(driver.windowHandles).thenReturn(setOf("main"), setOf("main", "popup"), setOf("main"))
        whenever(driver.switchTo()).thenReturn(targetLocator)
        whenever(targetLocator.window(any())).thenReturn(driver)
        whenever(driver.findElements(any())).thenReturn(emptyList())
        whenever(driver.findElement(any())).thenReturn(body)
        whenever(body.text).thenReturn("Only Facebook photo content\n$photoUrl")
        whenever(driver.pageSource).thenReturn("Only Facebook photo content\n$photoUrl")

        assertNull(method.invoke(importer, driver, element))
    }
}
