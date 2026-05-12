// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.Keys
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.firefox.FirefoxProfile
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import pl.bnowakowski.cozazjeb.article.ArticleInput
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.article.ArticleUrlConflictException
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.math.max

@Component
@ConditionalOnProperty(prefix = "app.facebook-import", name = ["enabled"], havingValue = "true")
@ConditionalOnExpression("#{systemProperties['org.gradle.test.worker'] == null}")
class FacebookProfileArticleImporter(
    private val properties: FacebookImportProperties,
    private val appUserRepository: AppUserRepository,
    private val articleService: ArticleService,
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val facebookProperties = FacebookLoginPropertiesReader()
    private val dotEnvValues = loadDotEnvValues()

    override fun run(args: ApplicationArguments) {
        require(properties.creatorEmail.isNotBlank()) {
            "app.facebook-import.creator-email must point to an existing app user"
        }
        val creator = appUserRepository.findByEmail(properties.creatorEmail)
            ?: throw IllegalArgumentException("No app user exists for ${properties.creatorEmail}")

        openDriver().use { driver ->
            login(driver)
            waitForSecondFactorConfirmation()
            waitForLogin(driver)
            driver.get(properties.profileUrl)
            sleep(properties.waitAfterPageOpen)
            repeat(max(properties.scrolls, 0)) { index ->
                driver.findElement(By.tagName("body")).sendKeys(Keys.PAGE_DOWN)
                logger.info("Facebook import scroll {}/{}", index + 1, properties.scrolls)
                sleep(properties.waitAfterScroll)
            }
            expandSeeOriginalLinks(driver)

            val candidates = findCandidatePosts(driver)
            logger.info("Facebook import found {} marked posts", candidates.size)
            candidates.forEach { candidate ->
                importCandidate(candidate, creator.id!!)
            }
        }
    }

    private fun openDriver(): CloseableWebDriver {
        val driver = when (properties.browser) {
            FacebookImportProperties.Browser.FIREFOX -> FirefoxDriver(
                FirefoxOptions().apply {
                    if (browserHeadless()) addArguments("--headless")
                    addArguments("--width=1000")
                    addArguments("--height=3440")
                    profile = FirefoxProfile()
                }
            )
            FacebookImportProperties.Browser.CHROME -> ChromeDriver(
                ChromeOptions().apply {
                    if (browserHeadless()) addArguments("--headless=new")
                    addArguments("--window-size=1000,3440")
                }
            )
        }
        if (!browserHeadless()) {
            driver.manage().window().position = org.openqa.selenium.Point(900, 0)
        }
        return CloseableWebDriver(driver)
    }

    private fun login(driver: WebDriver) {
        val username = facebookCredential("username")
        val password = facebookCredential("password")

        logger.debug("trying to open facebook page")
        driver.get("https://www.facebook.com/login")
        if (isLoggedIn(driver)) {
            logger.info("Facebook already appears to be logged in")
            return
        }

        if (username.isBlank() || password.isBlank()) {
            logger.info("No Facebook credentials configured; waiting for manual login in the Selenium window")
            waitForLogin(driver)
            return
        }

        closeCookieConsentModalIfPresent(driver)

        logger.debug("trying to fill user and password")
        val emailField = waitForLoginField(driver, listOf(
            By.id("email"),
            By.cssSelector("input[name='email']"),
            By.cssSelector("input[type='email']"),
        ), "email")
        val passwordField = waitForLoginField(driver, listOf(
            By.id("pass"),
            By.cssSelector("input[name='pass']"),
            By.cssSelector("input[type='password']"),
        ), "password")
        emailField.sendKeys(username)
        passwordField.sendKeys(password)

        Thread.sleep(500)
        logger.debug("trying to click on login button")
        val loginClicked = runCatching {
            passwordField.sendKeys(Keys.ENTER)
            true
        }.getOrDefault(false) || clickLoginButtonIfPresent(driver)
        if (!loginClicked) {
            throw NoSuchElementException("Unable to locate Facebook login button")
        }
        // TODO: insert stdin "continue" gate here after the user completes second factor in the Selenium browser.
        sleep(properties.waitAfterLogin)
    }

    private fun waitForSecondFactorConfirmation() {
        print(
            """
            If Facebook showed a second factor / challenge, finish it now in the Selenium window.
            If it did not appear, just press Enter to continue.
            Continue? [Y/n]: 
            """.trimIndent(),
        )
        System.out.flush()
        val response = runCatching {
            System.`in`.bufferedReader().readLine()
        }.getOrNull()
        if (response.isNullOrBlank() || response.equals("y", ignoreCase = true) || response.equals("yes", ignoreCase = true)) {
            logger.info("Continuing after manual second factor confirmation")
            return
        }
        throw IllegalStateException("Facebook import aborted by user response: $response")
    }

    private fun waitForScanInspectionConfirmation() {
        print(
            """
            Facebook profile is loaded.
            Inspect the Selenium window if you want, then press Enter to dump post blocks and continue.
            Continue? [Y/n]: 
            """.trimIndent(),
        )
        System.out.flush()
        val response = runCatching {
            System.`in`.bufferedReader().readLine()
        }.getOrNull()
        if (response.isNullOrBlank() || response.equals("y", ignoreCase = true) || response.equals("yes", ignoreCase = true)) {
            logger.info("Continuing with Facebook article scan")
            return
        }
        throw IllegalStateException("Facebook import aborted by user response: $response")
    }

    private fun dumpArticleBoundaries(driver: WebDriver) {
        val selectors = listOf(
            "[data-pagelet^='FeedUnit_']",
            "[role='article']",
            "div[aria-posinset]",
            "[data-ad-preview='message']",
        )

        selectors.forEach { selector ->
            val blocks = driver.findElements(By.cssSelector(selector))
            logger.info("Debug selector {} matched {} blocks", selector, blocks.size)
            blocks.take(8).forEachIndexed { index, element ->
                val text = runCatching { element.text.cleanText() }.getOrNull().orEmpty()
                val hrefs = runCatching {
                    element.findElements(By.cssSelector("a[href]"))
                        .mapNotNull { it.getAttribute("href") }
                        .distinct()
                        .take(6)
                }.getOrDefault(emptyList())
                val htmlSnippet = runCatching {
                    val js = driver as? JavascriptExecutor ?: return@runCatching null
                    js.executeScript(
                        """
                        const post = arguments[0];
                        return (post.outerHTML || post.innerHTML || '').slice(0, 900);
                        """.trimIndent(),
                        element,
                    ) as? String
                }.getOrNull().orEmpty()

                logger.info(
                    "SEL {} ITEM {} text='{}' hrefs={} html='{}'",
                    selector,
                    index + 1,
                    text.take(260),
                    hrefs.joinToString(separator = " | "),
                    htmlSnippet.replace(Regex("\\s+"), " ").take(360),
                )
            }
            if (blocks.size > 8) {
                logger.info("Skipping {} additional blocks for selector {}", blocks.size - 8, selector)
            }
        }
    }

    private fun facebookCredential(key: String): String =
        when (key) {
            "username" -> resolveCredential("APP_FACEBOOK_IMPORT_USERNAME", properties.username, "username")
            "password" -> resolveCredential("APP_FACEBOOK_IMPORT_PASSWORD", properties.password, "password")
            "browser.headless" -> resolveBoolean("APP_FACEBOOK_IMPORT_HEADLESS", properties.headless, "browser.headless").toString()
            else -> facebookProperties.getProperty(key).orEmpty()
        }

    private fun browserHeadless(): Boolean =
        resolveBoolean("APP_FACEBOOK_IMPORT_HEADLESS", properties.headless, "browser.headless")

    private fun resolveCredential(dotEnvKey: String, configValue: String, propertiesKey: String): String {
        dotEnvValues[dotEnvKey]?.let { if (it.isNotBlank()) return it }
        if (configValue.isNotBlank()) return configValue
        return facebookProperties.getProperty(propertiesKey).orEmpty()
    }

    private fun resolveBoolean(dotEnvKey: String, configValue: Boolean, propertiesKey: String): Boolean {
        dotEnvValues[dotEnvKey]?.let { return it.equals("true", ignoreCase = true) }
        if (configValue) return true
        return facebookProperties.getProperty(propertiesKey)?.toBoolean() == true
    }

    private fun loadDotEnvValues(): Map<String, String> {
        val file = File(".env")
        if (!file.exists()) return emptyMap()

        return file.readLines()
            .asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .mapNotNull { line ->
                val eqIndex = line.indexOf('=')
                if (eqIndex < 1) null else line.substring(0, eqIndex).trim() to line.substring(eqIndex + 1).trim()
            }
            .toMap()
    }

    private fun waitForLoginField(driver: WebDriver, selectors: List<By>, fieldName: String): WebElement {
        val deadline = System.nanoTime() + properties.manualLoginTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            for (selector in selectors) {
                val element = runCatching { driver.findElement(selector) }.getOrNull()
                if (element != null) {
                    return element
                }
            }
            sleep(Duration.ofSeconds(1))
        }
        throw NoSuchElementException("Unable to locate Facebook $fieldName field")
    }

    private fun clickLoginButtonIfPresent(driver: WebDriver): Boolean {
        val selectors = listOf(
            By.name("login"),
            By.cssSelector("button[type='submit']"),
            By.cssSelector("input[type='submit']"),
            By.xpath("//button[@type='submit']"),
            By.xpath("//div[@role='button' and (normalize-space()='Log in' or normalize-space()='Log In' or normalize-space()='Logowanie')]"),
        )
        for (selector in selectors) {
            val element = runCatching { driver.findElement(selector) }.getOrNull() ?: continue
            runCatching { element.click() }.getOrNull()?.let { return true }
        }
        return false
    }

    private fun closeCookieConsentModalIfPresent(driver: WebDriver) {
        logger.debug("checking if cookie consent form is present")
        if (!driver.pageSource.orEmpty().contains("Decline optional cookies")) {
            return
        }

        var i = 0
        while (true) {
            driver.findElement(By.cssSelector("body")).sendKeys(Keys.TAB)
            Thread.sleep(100)
            val elementText = driver.switchTo().activeElement().text
            logger.trace("tab i={} element_txt={}", i, elementText)
            if (elementText == "Decline optional cookies") {
                break
            }
            i++
        }
        logger.debug("trying to click on cookie consent form")
        try {
            driver.findElement(By.cssSelector("body")).sendKeys(Keys.RETURN)
        } catch (_: Exception) {
            logger.info("exception while pressing RETURN on Tabbed button. Trying to click button By.className")
            driver.findElement(By.className("_42ft")).click()
        }
    }

    private fun waitForLogin(driver: WebDriver) {
        val deadline = System.nanoTime() + properties.manualLoginTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (isLoggedIn(driver)) {
                logger.info("Facebook login detected")
                return
            }
            sleep(Duration.ofSeconds(2))
        }
        throw IllegalStateException("Facebook login was not detected within ${properties.manualLoginTimeout}")
    }

    private fun isLoggedIn(driver: WebDriver): Boolean =
        runCatching {
            val cUser = driver.manage().getCookieNamed("c_user")
            val xs = driver.manage().getCookieNamed("xs")
            if (cUser == null || xs == null) {
                return@runCatching false
            }
            val url = driver.currentUrl.orEmpty()
            if (!url.contains("facebook.com")) return@runCatching false
            if (url.contains("login") || url.contains("checkpoint") || url.contains("recover")) {
                return@runCatching false
            }
            true
        }.getOrDefault(false)

    private fun findCandidatePosts(driver: WebDriver): List<FacebookPostCandidate> {
        val posts = collectPostContainers(driver)
        val markers = candidateMarkerPhrases()
        return posts.mapNotNull { element ->
            val text = element.text.cleanText()
            if (markers.none { text.contains(it, ignoreCase = true) }) return@mapNotNull null
            val postUrl = findPostUrl(driver, element)
                ?: return@mapNotNull null
            FacebookPostCandidate(postUrl, text)
        }.distinctBy { it.url }
    }

    private fun collectPostContainers(driver: WebDriver): List<WebElement> {
        val selectors = listOf(
            By.cssSelector("[data-pagelet^='FeedUnit_']"),
            By.cssSelector("[role='article']"),
            By.cssSelector("div[aria-posinset]"),
            By.cssSelector("[data-ad-preview='message']"),
        )
        return selectors.flatMap { selector -> driver.findElements(selector) }
    }

    private fun candidateMarkerPhrases(): List<String> =
        listOf(properties.markerPhrase, properties.translatedMarkerPhrase)
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct()

    private fun expandSeeOriginalLinks(driver: WebDriver) {
        val locator = By.xpath(
            "//*[(@role='button' or self::a or self::button or self::div) and " +
                "(normalize-space()='See original' or normalize-space()='See Original')]",
        )

        var clickedCount = 0
        while (true) {
            val element = driver.findElements(locator)
                .firstOrNull()
                ?: break

            runCatching {
                (driver as? JavascriptExecutor)?.executeScript(
                    "arguments[0].scrollIntoView({block: 'center'});",
                    element,
                )
            }

            val clicked = runCatching { element.click(); true }.getOrDefault(false) ||
                runCatching {
                    (driver as? JavascriptExecutor)?.executeScript("arguments[0].click();", element)
                    true
                }.getOrDefault(false)

            if (!clicked) {
                logger.debug("Could not click a See original control")
                break
            }

            clickedCount++
            logger.debug("Clicked See original control {}", clickedCount)
            sleep(properties.waitAfterScroll)
        }

        if (clickedCount == 0) {
            logger.debug("No See original controls found on the Facebook page")
        } else {
            logger.info("Clicked {} See original controls", clickedCount)
        }
    }

    private fun findPostUrl(driver: WebDriver, element: WebElement): String? {
        val links = element.findElements(By.cssSelector("a[href]"))
            .mapNotNull { it.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl() }
            .distinct()

        links.firstOrNull { isExternalArticleUrl(it) }?.let { return it }
        extractExternalArticleUrlFromText(element.text)?.let { return it }
        extractExternalArticleUrlFromHtml(driver, element)?.let { return it }

        links.asSequence()
            .filter { isFacebookPostUrl(it) }
            .mapNotNull { extractExternalArticleUrlFromFacebookPost(driver, it) }
            .firstOrNull()
            ?.let { return it }

        links.firstOrNull { isFacebookPostUrl(it) }?.let { return it }

        extractPostUrlFromHtml(driver, element)?.let { return it }

        return null
    }

    private fun extractExternalArticleUrlFromHtml(driver: WebDriver, element: WebElement): String? {
        val html = elementOuterHtml(driver, element) ?: return null
        extractExternalArticleUrlFromText(html)?.let { return it }
        return HREF_VALUE_REGEX.findAll(html)
            .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
            .distinct()
            .firstOrNull { isExternalArticleUrl(it) }
    }

    private fun extractExternalArticleUrlFromFacebookPost(driver: WebDriver, postUrl: String): String? {
        val originalWindow = driver.windowHandle
        val originalHandles = driver.windowHandles
        val js = driver as? JavascriptExecutor ?: return null
        var openedWindow: String? = null

        return runCatching {
            js.executeScript("window.open(arguments[0], '_blank');", postUrl)
            val newWindow = driver.windowHandles.firstOrNull { it !in originalHandles } ?: return@runCatching null
            openedWindow = newWindow
            driver.switchTo().window(newWindow)
            sleep(properties.waitAfterPageOpen)
            expandSeeOriginalLinks(driver)
            val pageSource = driver.pageSource.orEmpty()
            extractExternalArticleUrlFromText(driver.findElement(By.tagName("body")).text)
                ?: extractExternalArticleUrlFromText(pageSource)
                ?: HREF_VALUE_REGEX.findAll(pageSource)
                    .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
                    .distinct()
                    .firstOrNull { isExternalArticleUrl(it) }
        }.getOrNull().also {
            runCatching {
                openedWindow?.let { window ->
                    driver.switchTo().window(window)
                    driver.close()
                }
                driver.switchTo().window(originalWindow)
            }
        }
    }

    private fun extractPostUrlFromHtml(driver: WebDriver, element: WebElement): String? {
        val html = elementOuterHtml(driver, element) ?: return null

        val candidates = mutableListOf<String>()
        candidates += listOf(
            FACEBOOK_POST_URL_REGEX,
            FACEBOOK_RELATIVE_POST_URL_REGEX,
            FACEBOOK_STORY_URL_REGEX,
            FACEBOOK_RELATIVE_STORY_URL_REGEX,
            FACEBOOK_PHOTO_URL_REGEX,
            FACEBOOK_RELATIVE_PHOTO_URL_REGEX,
        ).flatMap { regex ->
            regex.findAll(html).map { it.value }
        }
        candidates += HREF_VALUE_REGEX.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&") }
            .toList()

        return candidates.asSequence()
            .mapNotNull { it.toCleanFacebookUrl() }
            .firstOrNull { isFacebookPostUrl(it) }
    }

    private fun elementOuterHtml(driver: WebDriver, element: WebElement): String? {
        val js = driver as? JavascriptExecutor ?: return null
        return runCatching {
            js.executeScript(
                """
                const post = arguments[0];
                return post.outerHTML || post.innerHTML || null;
                """.trimIndent(),
                element,
            ) as? String
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractExternalArticleUrlFromText(text: String): String? =
        TEXT_URL_REGEX.findAll(text)
            .mapNotNull { it.value.decodeHtmlEntities().trimUrlBoundaryCharacters().toCleanFacebookUrl() }
            .distinct()
            .firstOrNull { isExternalArticleUrl(it) }

    private fun importCandidate(candidate: FacebookPostCandidate, creatorId: Long) {
        try {
            val article = articleService.create(
                ArticleInput(
                    url = candidate.url,
                    language = properties.language,
                    quote = properties.markerPhrase,
                ),
                creatorId,
            )
            if (isFacebookPostUrl(candidate.url)) {
                articleService.replaceContentCache(article.id!!, candidate.text)
            }
            logger.info("Imported Facebook-marked post {}", candidate.url)
        } catch (ex: ArticleUrlConflictException) {
            logger.info("Skipping already imported post {}", candidate.url)
        } catch (ex: Exception) {
            logger.warn("Could not import Facebook-marked post {}", candidate.url, ex)
        }
    }

    private fun String.cleanText(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.decodeHtmlEntities(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun String.trimUrlBoundaryCharacters(): String =
        trim()
            .trimEnd('.', ',', ';', ':', ')', ']', '}', '…')

    private fun String.toCleanFacebookUrl(): String? {
        val normalized = when {
            startsWith("//") -> "https:$this"
            startsWith("/") -> "https://www.facebook.com$this"
            else -> this
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (uri.host?.endsWith("facebook.com") == true && uri.path == "/l.php") {
            return uri.rawQuery
                ?.split("&")
                ?.firstOrNull { it.startsWith("u=") }
                ?.substringAfter("=")
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        }
        facebookStoryPostUrl(uri)?.let { return it }
        return normalized
            .substringBefore("?__cft__")
            .substringBefore("&__cft__")
    }

    private fun facebookStoryPostUrl(uri: URI): String? {
        val host = uri.host?.lowercase() ?: return null
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return null
        if (uri.path !in setOf("/story.php", "/permalink.php", "/photo.php")) return null

        val query = uri.rawQuery
            ?.split("&")
            ?.mapNotNull {
                val name = it.substringBefore("=", "")
                val value = it.substringAfter("=", "")
                if (name.isBlank() || value.isBlank()) null else name to URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
            ?.toMap()
            ?: return null
        val postId = query["story_fbid"] ?: query["fbid"] ?: return null
        val actorId = query["id"] ?: return null
        return "https://www.facebook.com/$actorId/posts/$postId"
    }

    private fun isExternalArticleUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        if (host == "facebook.com" || host.endsWith(".facebook.com")) return false
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return false
        return true
    }

    private fun isFacebookPostUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: ""
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        return path.contains("/posts/") ||
            path.contains("/share/") ||
            path.contains("/permalink/") ||
            path.contains("/photo") ||
            (uri.query ?: "").contains("story_fbid=")
    }

    private fun sleep(duration: Duration) {
        Thread.sleep(duration.toMillis())
    }

    private data class FacebookPostCandidate(
        val url: String,
        val text: String,
    )

    companion object {
        private val FACEBOOK_POST_URL_REGEX =
            Regex("""https?://(?:www\.)?facebook\.com/[^"'<> ]+/posts/[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val FACEBOOK_RELATIVE_POST_URL_REGEX =
            Regex("""/[^"'<> ]+/posts/[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val FACEBOOK_STORY_URL_REGEX =
            Regex("""https?://(?:www\.)?facebook\.com/(?:story\.php|permalink\.php)\?[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val FACEBOOK_RELATIVE_STORY_URL_REGEX =
            Regex("""/(?:story\.php|permalink\.php)\?[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val FACEBOOK_PHOTO_URL_REGEX =
            Regex("""https?://(?:www\.)?facebook\.com/photo(?:\.php)?\?[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val FACEBOOK_RELATIVE_PHOTO_URL_REGEX =
            Regex("""/(?:photo(?:\.php)?)\?[^"'<> ]+""", RegexOption.IGNORE_CASE)
        private val HREF_VALUE_REGEX =
            Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val TEXT_URL_REGEX =
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    }

    private class CloseableWebDriver(private val delegate: WebDriver) : WebDriver by delegate, AutoCloseable {
        override fun close() {
            delegate.quit()
        }
    }
}
