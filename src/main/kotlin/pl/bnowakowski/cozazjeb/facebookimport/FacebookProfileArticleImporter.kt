// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import jakarta.annotation.PreDestroy
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
import org.springframework.stereotype.Component
import pl.bnowakowski.cozazjeb.article.ArticleInput
import pl.bnowakowski.cozazjeb.article.ArticleResponse
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.article.ArticleUrlConflictException
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

@Component
class FacebookProfileArticleImporter(
    private val properties: FacebookImportProperties,
    private val appUserRepository: AppUserRepository,
    private val articleService: ArticleService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val facebookProperties = FacebookLoginPropertiesReader()
    private val dotEnvValues = loadDotEnvValues()
    private val stateLock = Any()
    @Volatile private var activeImportThread: Thread? = null
    @Volatile private var driver: WebDriver? = null

    fun startImport() {
        require(properties.username.isNotBlank()) {
            "app.facebook-import.username must point to an existing app user"
        }
        val creator = appUserRepository.findByEmail(properties.username)
            ?: throw IllegalArgumentException("No app user exists for ${properties.username}")

        synchronized(stateLock) {
            if (activeImportThread?.isAlive == true) {
                throw FacebookImportAlreadyRunningException()
            }

            val importThread = Thread {
                try {
                    runImport(creator.id!!)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.info("Facebook import was interrupted")
                } catch (ex: Exception) {
                    logger.warn("Facebook import job failed", ex)
                } finally {
                    synchronized(stateLock) {
                        if (activeImportThread === Thread.currentThread()) {
                            activeImportThread = null
                        }
                    }
                }
            }.apply {
                name = "facebook-import-job"
                isDaemon = true
            }
            activeImportThread = importThread
            importThread.start()
        }
    }

    fun terminateImport() {
        val thread = synchronized(stateLock) {
            activeImportThread?.takeIf { it.isAlive } ?: throw FacebookImportNotRunningException()
        }
        thread.interrupt()
    }

    fun isImportRunning(): Boolean =
        synchronized(stateLock) {
            activeImportThread?.isAlive == true
        }

    private fun runImport(creatorId: Long) {
        val driver = ensureDriver()
        prepareProfileAndLogin(driver)
        sleep(properties.waitAfterPageOpen)
        val summary = ImportSummary()
        val passCount = (2 until properties.scrolls step 2).count()
        logger.info(
            "Facebook import starting {} discovery passes with up to {} configured scrolls",
            passCount,
            properties.scrolls,
        )
        for ((passIndex, scrollsThisPass) in (1 until properties.scrolls step 2).withIndex()) {
            logger.info(
                "Facebook import discovery pass {}/{} started with {} scrolls",
                passIndex + 1,
                passCount,
                scrollsThisPass,
            )
            repeat(scrollsThisPass) { index ->
                driver.findElement(By.tagName("body")).sendKeys(Keys.PAGE_DOWN)
                logger.info(
                    "Facebook import discovery pass {}/{} scroll {}/{}",
                    passIndex + 1,
                    passCount,
                    index + 1,
                    scrollsThisPass,
                )
                sleep(properties.waitAfterScroll)
            }
            expandSeeOriginalLinks(driver)

            val candidates = findCandidatePosts(driver)
            logger.info(
                "Facebook import discovery pass {}/{} found {} marked posts",
                passIndex + 1,
                passCount,
                candidates.size,
            )
            candidates.forEachIndexed { index, candidate ->
                summary.record(importCandidate(candidate, creatorId, index + 1, candidates.size))
            }
            logger.info(
                "Facebook import discovery pass {}/{} finished: {} processed, {} imported, {} already imported, {} skipped, {} failed so far",
                passIndex + 1,
                passCount,
                summary.processed,
                summary.imported,
                summary.alreadyImported,
                summary.skipped,
                summary.failed,
            )
        }
        logger.info(
            "Facebook import finished: {} processed, {} imported, {} already imported, {} skipped, {} failed",
            summary.processed,
            summary.imported,
            summary.alreadyImported,
            summary.skipped,
            summary.failed,
        )
    }

    fun openDriver(): WebDriver {
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
        return driver
    }

    fun prepareProfileAndLogin(driver: WebDriver) {
        driver.get(properties.profileUrl)
        sleep(properties.waitAfterPageOpen)

        if (isLoggedIn(driver)) {
            logger.info("Facebook already appears to be logged in")
            return
        }

        login(driver)
        waitForLogin(driver)
        driver.get(properties.profileUrl)
        sleep(properties.waitAfterPageOpen)
    }

    private fun login(driver: WebDriver) {
        val username = facebookCredential("username")
        val password = facebookCredential("password")

        logger.debug("trying to open facebook page")
        driver.get("https://www.facebook.com/login")

        if (username.isBlank() || password.isBlank()) {
            logger.info("No Facebook credentials configured; waiting for manual login in the Selenium window")
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
        sleep(properties.waitAfterLogin)
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
        val markedPosts = posts.mapNotNull { element ->
            val text = element.text.cleanText()
            if (markers.none { text.contains(it, ignoreCase = true) }) return@mapNotNull null
            MarkedFacebookPost(element, text)
        }
        logger.info(
            "FB_IMPORT_MARKED_POSTS_DISCOVERED containers={} marked={} markers={}",
            posts.size,
            markedPosts.size,
            markers,
        )
        return markedPosts.mapIndexedNotNull { index, markedPost ->
            logMarkedPostCandidate(driver, index + 1, markedPosts.size, markedPost)
            val postUrl = findPostUrl(
                driver,
                markedPost.element,
                discoveryProgress(index + 1, markedPosts.size),
            )
                ?: return@mapIndexedNotNull null
            FacebookPostCandidate(postUrl, markedPost.text)
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

    private fun findPostUrl(driver: WebDriver, element: WebElement): String? =
        findPostUrl(driver, element, null)

    private fun findPostUrl(driver: WebDriver, element: WebElement, discoveryProgress: String?): String? {
        val text = element.text
        val links = element.findElements(By.cssSelector("a[href]"))
            .mapNotNull { it.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl() }
            .filterNot { isMediaOrThumbnailUrl(it) }
            .filterNot { isMarkupNoiseUrl(it) }
            .distinct()

        val facebookPostUrls = buildList {
            extractFacebookPostUrlFromText(text)?.let(::add)
            addAll(links.filter { isFacebookPostUrl(it) })
        }.distinct()
        val htmlPostUrl = extractPostUrlFromHtml(driver, element)
            ?.takeIf { !isConfiguredProfilePostUrl(it) }
            ?.takeIf { !isFacebookPhotoUrl(it) || isImportableSharedFacebookPhotoUrl(it, text) }
        val decisionDiagnostics = buildList {
            addAll(urlDiagnostics("facebook-post", facebookPostUrls))
            addAll(urlDiagnostics("link", links))
            htmlPostUrl?.let { add(urlDiagnostic("html-post", it)) }
        }.distinctBy { "${it.source}:${it.url}" }
        logger.info(
            "{}FB_IMPORT_CONTAINER_URL_INPUTS textUrl={} htmlPostUrl={} links={} diagnostics={} textPreview={}",
            discoveryProgress?.let { "$it " }.orEmpty(),
            extractExternalArticleUrlFromText(text) ?: "<none>",
            htmlPostUrl ?: "<none>",
            formatUrls(links),
            decisionDiagnostics.take(LOG_DIAGNOSTIC_LIMIT),
            text.cleanText().abbreviateForLog(),
        )

        extractExternalArticleUrlFromText(text)?.let {
            logPostUrlDecision("visible-text-url", it, text, facebookPostUrls, links)
            return it
        }
        extractExternalArticleUrlFromHtml(driver, element)?.let {
            logPostUrlDecision("html-url", it, text, facebookPostUrls, links)
            return it
        }

        links.firstOrNull { isExternalArticleUrl(it) }?.let {
            logPostUrlDecision("link-url", it, text, facebookPostUrls, links)
            return it
        }

        if (htmlPostUrl != null && !isFacebookPhotoUrl(htmlPostUrl)) {
            logPostUrlDecision("html-facebook-fallback", htmlPostUrl, text, facebookPostUrls, links)
            return htmlPostUrl
        }

        val selectedFromOpenedPost = facebookPostUrls.asSequence()
            .filterNot { isConfiguredProfilePostUrl(it) }
            .mapNotNull { extractCandidateUrlFromFacebookPost(driver, it, discoveryProgress = discoveryProgress) }
            .firstOrNull()
            ?: facebookPostUrls.asSequence()
                .filter { isConfiguredProfilePostUrl(it) }
                .mapNotNull { extractCandidateUrlFromFacebookPost(driver, it, discoveryProgress = discoveryProgress) }
                .firstOrNull()
        if (selectedFromOpenedPost != null) {
            logPostUrlDecision("opened-facebook-post", selectedFromOpenedPost, text, facebookPostUrls, links)
            return selectedFromOpenedPost
        }

        facebookPostUrls.firstOrNull { !isConfiguredProfilePostUrl(it) && isImportableFacebookArticleUrl(it) }?.let {
            logPostUrlDecision("facebook-post-fallback", it, text, facebookPostUrls, links)
            return it
        }

        facebookPostUrls.firstOrNull { isImportableSharedFacebookPhotoUrl(it, text) }?.let {
            logPostUrlDecision("facebook-photo-fallback", it, text, facebookPostUrls, links)
            return it
        }

        htmlPostUrl?.let {
            logPostUrlDecision("html-facebook-fallback", it, text, facebookPostUrls, links)
            return it
        }

        logPostUrlDecision("none", null, text, facebookPostUrls, links)
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

    private fun extractCandidateUrlFromFacebookPost(
        driver: WebDriver,
        postUrl: String,
        visited: Set<String> = emptySet(),
    ): String? =
        extractCandidateUrlFromFacebookPost(driver, postUrl, visited, discoveryProgress = null)

    private fun extractCandidateUrlFromFacebookPost(
        driver: WebDriver,
        postUrl: String,
        visited: Set<String> = emptySet(),
        discoveryProgress: String?,
    ): String? {
        if (postUrl in visited) return null
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
            val bodyText = driver.findElement(By.tagName("body")).text
            val bodyFacebookCandidates = extractFacebookPostUrlCandidates(bodyText).toList()
            val linkFacebookCandidates = extractFacebookPostUrlCandidatesFromLinks(driver).toList()
            val pageTextFacebookCandidates = extractFacebookPostUrlCandidates(pageSource).toList()
            val pageHrefFacebookCandidates = HREF_VALUE_REGEX.findAll(pageSource)
                .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
                .filter { isFacebookPostUrl(it) }
                .toList()
            val facebookCandidates = facebookPostCandidatesFromOpenedPage(
                bodyFacebookCandidates,
                linkFacebookCandidates,
                pageTextFacebookCandidates,
                pageHrefFacebookCandidates,
            )

            val nestedCandidate = facebookCandidates.asSequence()
                .mapNotNull { candidate ->
                    extractCandidateUrlFromFacebookPost(
                        driver,
                        candidate,
                        visited = visited + postUrl,
                        discoveryProgress = discoveryProgress,
                    )
                }
                .firstOrNull()
            val bodyTextArticleUrl = extractExternalArticleUrlFromText(bodyText)
            val linkArticleUrls = externalArticleUrlsFromLinks(driver, bodyText)
            val pageTextArticleUrl = extractExternalArticleUrlFromText(pageSource, visibleText = bodyText)
            val pageHrefArticleUrls = HREF_VALUE_REGEX.findAll(pageSource)
                .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
                .distinct()
                .filter { isExternalArticleUrl(it) && isUrlHostMentionedInText(it, bodyText) }
                .toList()
            val openedDiagnostics = buildList {
                addAll(urlDiagnostics("opened-body-facebook", bodyFacebookCandidates))
                addAll(urlDiagnostics("opened-link-facebook", linkFacebookCandidates))
                addAll(urlDiagnostics("opened-page-text-facebook", pageTextFacebookCandidates))
                addAll(urlDiagnostics("opened-page-href-facebook", pageHrefFacebookCandidates))
                bodyTextArticleUrl?.let { add(urlDiagnostic("opened-body-article", it)) }
                addAll(urlDiagnostics("opened-link-article", linkArticleUrls))
                pageTextArticleUrl?.let { add(urlDiagnostic("opened-page-text-article", it)) }
                addAll(urlDiagnostics("opened-page-href-article", pageHrefArticleUrls))
            }.distinctBy { "${it.source}:${it.url}" }
            val facebookFallbackUrl = selectBestFacebookPostUrl(facebookCandidates.asSequence(), postUrl)
            val unsafeSelected = nestedCandidate
                ?: bodyTextArticleUrl
                ?: linkArticleUrls.firstOrNull()
                ?: pageTextArticleUrl
                ?: pageHrefArticleUrls.firstOrNull()
                ?: facebookFallbackUrl
            val selected = unsafeSelected?.takeUnless {
                isConfiguredProfilePostUrl(postUrl) &&
                    isExternalArticleUrl(it) &&
                    isGenericFacebookFeedPage(bodyText)
            }
            val selectedSource = when (selected) {
                null -> "none"
                nestedCandidate -> "nested-facebook-post"
                bodyTextArticleUrl -> "visible-text-url"
                linkArticleUrls.firstOrNull() -> "visible-link-url"
                pageTextArticleUrl -> "page-source-visible-url"
                pageHrefArticleUrls.firstOrNull() -> "page-source-href-visible-url"
                facebookFallbackUrl -> "facebook-post-fallback"
                else -> "unknown"
            }
            logOpenedPostUrlDecision(
                discoveryProgress = discoveryProgress,
                postUrl = postUrl,
                selectedSource = selectedSource,
                selectedUrl = selected,
                bodyText = bodyText,
                facebookCandidates = facebookCandidates,
                bodyTextArticleUrl = bodyTextArticleUrl,
                linkArticleUrls = linkArticleUrls,
                pageTextArticleUrl = pageTextArticleUrl,
                pageHrefArticleUrls = pageHrefArticleUrls,
                facebookFallbackUrl = facebookFallbackUrl,
            )
            logOpenedPostUrlDiagnostics(
                discoveryProgress = discoveryProgress,
                postUrl = postUrl,
                currentUrl = driver.currentUrl.orEmpty(),
                visited = visited,
                diagnostics = openedDiagnostics,
            )
            selected
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

    private fun extractFacebookPostUrlFromFacebookPost(driver: WebDriver, postUrl: String): String? {
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
            val bodyText = driver.findElement(By.tagName("body")).text
            selectBestFacebookPostUrl(extractFacebookPostUrlCandidates(bodyText), postUrl)
                ?: selectBestFacebookPostUrl(extractFacebookPostUrlCandidatesFromLinks(driver), postUrl)
                ?: selectBestFacebookPostUrl(extractFacebookPostUrlCandidates(pageSource), postUrl)
                ?: selectBestFacebookPostUrl(
                    HREF_VALUE_REGEX.findAll(pageSource)
                        .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
                        .distinct()
                        .filter { isFacebookPostUrl(it) },
                    postUrl,
                )
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

    private fun extractExternalArticleUrlFromLinks(driver: WebDriver, visibleText: String? = null): String? =
        externalArticleUrlsFromLinks(driver, visibleText).firstOrNull()

    private fun externalArticleUrlsFromLinks(driver: WebDriver, visibleText: String? = null): List<String> =
        driver.findElements(By.cssSelector("a[href]"))
            .asSequence()
            .mapNotNull { it.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl() }
            .filter { isExternalArticleUrl(it) }
            .filter { visibleText == null || isUrlHostMentionedInText(it, visibleText) }
            .distinct()
            .toList()

    private fun extractFacebookPostUrlFromLinks(driver: WebDriver): String? =
        selectBestFacebookPostUrl(extractFacebookPostUrlCandidatesFromLinks(driver))

    private fun facebookPostCandidatesFromOpenedPage(
        driver: WebDriver,
        bodyText: String,
        pageSource: String,
    ): List<String> =
        facebookPostCandidatesFromOpenedPage(
            extractFacebookPostUrlCandidates(bodyText).toList(),
            extractFacebookPostUrlCandidatesFromLinks(driver).toList(),
            extractFacebookPostUrlCandidates(pageSource).toList(),
            HREF_VALUE_REGEX.findAll(pageSource)
                .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
                .filter { isFacebookPostUrl(it) }
                .toList(),
        )

    private fun facebookPostCandidatesFromOpenedPage(
        bodyTextCandidates: List<String>,
        linkCandidates: List<String>,
        pageTextCandidates: List<String>,
        pageHrefCandidates: List<String>,
    ): List<String> =
        (bodyTextCandidates + linkCandidates + pageTextCandidates + pageHrefCandidates).distinct()

    private fun extractPostUrlFromHtml(driver: WebDriver, element: WebElement): String? {
        val html = elementOuterHtml(driver, element) ?: return null

        val candidates = listOf(
            FACEBOOK_POST_URL_REGEX,
            FACEBOOK_RELATIVE_POST_URL_REGEX,
            FACEBOOK_STORY_URL_REGEX,
            FACEBOOK_RELATIVE_STORY_URL_REGEX,
            FACEBOOK_PHOTO_URL_REGEX,
            FACEBOOK_RELATIVE_PHOTO_URL_REGEX,
        ).flatMap { regex -> regex.findAll(html).map { it.value } } +
            HREF_VALUE_REGEX.findAll(html)
                .map { it.groupValues[1].replace("&amp;", "&") }

        val cleanedCandidates = candidates.asSequence()
            .mapNotNull { it.toCleanFacebookUrl() }
            .distinct()

        return selectBestSharedFacebookPostUrl(cleanedCandidates.filter { isFacebookPostUrl(it) })
            ?: cleanedCandidates.firstOrNull { isExternalArticleUrl(it) }
    }

    private fun extractFacebookPostUrlCandidates(text: String): Sequence<String> =
        TEXT_URL_REGEX.findAll(text)
            .mapNotNull { it.value.decodeHtmlEntities().trimUrlBoundaryCharacters().toCleanFacebookUrl() }
            .filter { isFacebookPostUrl(it) }

    private fun extractFacebookPostUrlCandidatesFromLinks(driver: WebDriver): Sequence<String> =
        driver.findElements(By.cssSelector("a[href]"))
            .asSequence()
            .mapNotNull { it.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl() }
            .filter { isFacebookPostUrl(it) }

    private fun selectBestFacebookPostUrl(candidates: Sequence<String>, excludedUrl: String? = null): String? =
        candidates
            .filterNot { it == excludedUrl }
            .mapNotNull { candidate ->
                facebookPostUrlPriority(candidate)?.let { candidate to it }
            }
            .minWithOrNull(compareBy<Pair<String, Int>>({ it.second }, { it.first.length }))
            ?.first

    private fun selectBestSharedFacebookPostUrl(candidates: Sequence<String>, excludedUrl: String? = null): String? {
        val distinctCandidates = candidates.distinct().toList()
        return selectBestFacebookPostUrl(
            distinctCandidates.asSequence().filterNot { isConfiguredProfilePostUrl(it) },
            excludedUrl,
        ) ?: selectBestFacebookPostUrl(distinctCandidates.asSequence(), excludedUrl)
    }

    private fun facebookPostUrlPriority(url: String): Int? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        val query = uri.rawQuery.orEmpty()
        return when {
            path.contains("/posts/") -> 0
            path.contains("/reel/") || path.contains("/reels/") -> 1
            path.contains("/share/") -> 2
            path.contains("/permalink/") -> 2
            ((path.contains("/photo") || path.contains("/photo.php")) &&
                query.contains("fbid=") &&
                (query.contains("set=") || query.contains("story_fbid="))) ||
                query.contains("story_fbid=") -> 3
            else -> 4
        }
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
        extractExternalArticleUrlFromText(text, visibleText = null)

    private fun extractExternalArticleUrlFromText(text: String, visibleText: String?): String? =
        TEXT_URL_REGEX.findAll(text)
            .mapNotNull { it.value.decodeHtmlEntities().trimUrlBoundaryCharacters().toCleanFacebookUrl() }
            .distinct()
            .filter { visibleText == null || isUrlHostMentionedInText(it, visibleText) }
            .firstOrNull { isExternalArticleUrl(it) }

    private fun isUrlHostMentionedInText(url: String, text: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        val normalizedHost = host.removePrefix("www.")
        val normalizedText = text.lowercase()
        return normalizedText.contains(host) || normalizedText.contains(normalizedHost)
    }

    private fun isGenericFacebookFeedPage(text: String): Boolean {
        val normalizedText = text.lowercase()
        return (
            normalizedText.contains("create a post") ||
                normalizedText.contains("what's on your mind") ||
                normalizedText.contains("feed posts")
            ) &&
            normalizedText.contains("home")
    }

    private fun extractFacebookPostUrlFromText(text: String): String? =
        selectBestFacebookPostUrl(extractFacebookPostUrlCandidates(text).distinct())

    private fun logPostUrlDecision(
        selectedSource: String,
        selectedUrl: String?,
        text: String,
        facebookPostUrls: List<String>,
        links: List<String>,
    ) {
        logger.info(
            "FB_IMPORT_POST_URL_DECISION source={} selected={} facebookPostUrls={} externalLinks={} textPreview={}",
            selectedSource,
            selectedUrl ?: "<none>",
            formatUrls(facebookPostUrls),
            formatUrls(links.filter { isExternalArticleUrl(it) }),
            text.cleanText().abbreviateForLog(),
        )
    }

    private fun logMarkedPostCandidate(
        driver: WebDriver,
        candidateNumber: Int,
        candidateTotal: Int,
        markedPost: MarkedFacebookPost,
    ) {
        val progress = discoveryProgress(candidateNumber, candidateTotal)
        val links = linkDiagnostics(markedPost.element)
        logger.info(
            "{} FB_IMPORT_MARKED_POST_CONTAINER element={} links={} textPreview={}",
            progress,
            elementDiagnostic(driver, markedPost.element),
            links.take(LOG_DIAGNOSTIC_LIMIT),
            markedPost.text.abbreviateForLog(),
        )
    }

    private fun logOpenedPostUrlDecision(
        discoveryProgress: String?,
        postUrl: String,
        selectedSource: String,
        selectedUrl: String?,
        bodyText: String,
        facebookCandidates: List<String>,
        bodyTextArticleUrl: String?,
        linkArticleUrls: List<String>,
        pageTextArticleUrl: String?,
        pageHrefArticleUrls: List<String>,
        facebookFallbackUrl: String?,
    ) {
        logger.info(
            "{}FB_IMPORT_OPENED_POST_DECISION postUrl={} source={} selected={} facebookCandidates={} " +
                "bodyTextArticleUrl={} linkArticleUrls={} pageTextArticleUrl={} pageHrefArticleUrls={} " +
                "facebookFallbackUrl={} bodyTextPreview={}",
            discoveryProgress?.let { "$it " }.orEmpty(),
            postUrl,
            selectedSource,
            selectedUrl ?: "<none>",
            formatUrls(facebookCandidates),
            bodyTextArticleUrl ?: "<none>",
            formatUrls(linkArticleUrls),
            pageTextArticleUrl ?: "<none>",
            formatUrls(pageHrefArticleUrls),
            facebookFallbackUrl ?: "<none>",
            bodyText.cleanText().abbreviateForLog(),
        )
    }

    private fun logOpenedPostUrlDiagnostics(
        discoveryProgress: String?,
        postUrl: String,
        currentUrl: String,
        visited: Set<String>,
        diagnostics: List<UrlDiagnostic>,
    ) {
        logger.info(
            "{}FB_IMPORT_OPENED_POST_URL_INPUTS postUrl={} currentUrl={} visited={} diagnostics={}",
            discoveryProgress?.let { "$it " }.orEmpty(),
            postUrl,
            currentUrl,
            formatUrls(visited),
            diagnostics.take(LOG_DIAGNOSTIC_LIMIT),
        )
    }

    private fun elementDiagnostic(driver: WebDriver, element: WebElement): ElementDiagnostic =
        ElementDiagnostic(
            tag = safeAttribute(element) { tagName },
            role = safeAttribute(element, "role"),
            dataPagelet = safeAttribute(element, "data-pagelet"),
            ariaPosinset = safeAttribute(element, "aria-posinset"),
            dataAdPreview = safeAttribute(element, "data-ad-preview"),
            className = safeAttribute(element, "class").abbreviateForLog(160),
            rect = runCatching {
                val rect = element.rect
                "x=${rect.x},y=${rect.y},w=${rect.width},h=${rect.height}"
            }.getOrDefault("<unknown>"),
            ancestorPath = elementAncestorPath(driver, element).abbreviateForLog(400),
        )

    private fun linkDiagnostics(element: WebElement): List<LinkDiagnostic> =
        element.findElements(By.cssSelector("a[href]"))
            .mapNotNull { link ->
                runCatching {
                    val href = link.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl()
                        ?: return@runCatching null
                    LinkDiagnostic(
                        href = href,
                        text = link.text.cleanText().abbreviateForLog(120),
                        ariaLabel = safeAttribute(link, "aria-label").abbreviateForLog(120),
                        role = safeAttribute(link, "role"),
                        facebookPost = isFacebookPostUrl(href),
                        externalArticle = isExternalArticleUrl(href),
                        mediaOrThumbnail = isMediaOrThumbnailUrl(href),
                        markupNoise = isMarkupNoiseUrl(href),
                    )
                }.getOrNull()
            }
            .distinctBy { "${it.href}:${it.text}:${it.ariaLabel}" }

    private fun urlDiagnostics(source: String, urls: Iterable<String>): List<UrlDiagnostic> =
        urls.distinct().map { urlDiagnostic(source, it) }

    private fun urlDiagnostic(source: String, url: String): UrlDiagnostic =
        UrlDiagnostic(
            source = source,
            url = url,
            facebookPost = isFacebookPostUrl(url),
            configuredProfilePost = isConfiguredProfilePostUrl(url),
            facebookPhoto = isFacebookPhotoUrl(url),
            importableFacebookArticle = isImportableFacebookArticleUrl(url),
            externalArticle = isExternalArticleUrl(url),
            mediaOrThumbnail = isMediaOrThumbnailUrl(url),
            markupNoise = isMarkupNoiseUrl(url),
            priority = facebookPostUrlPriority(url),
        )

    private fun safeAttribute(element: WebElement, name: String): String =
        runCatching { element.getAttribute(name).orEmpty() }.getOrDefault("")

    private fun safeAttribute(element: WebElement, value: WebElement.() -> String): String =
        runCatching { element.value() }.getOrDefault("")

    private fun elementAncestorPath(driver: WebDriver, element: WebElement): String {
        val js = driver as? JavascriptExecutor ?: return "<javascript-unavailable>"
        return runCatching {
            js.executeScript(
                """
                const parts = [];
                let current = arguments[0];
                for (let i = 0; i < 8 && current; i++) {
                  const id = current.id ? '#' + current.id : '';
                  const role = current.getAttribute('role') ? '[role=' + current.getAttribute('role') + ']' : '';
                  const pagelet = current.getAttribute('data-pagelet') ? '[data-pagelet=' + current.getAttribute('data-pagelet') + ']' : '';
                  const aria = current.getAttribute('aria-posinset') ? '[aria-posinset=' + current.getAttribute('aria-posinset') + ']' : '';
                  const preview = current.getAttribute('data-ad-preview') ? '[data-ad-preview=' + current.getAttribute('data-ad-preview') + ']' : '';
                  parts.push(current.tagName.toLowerCase() + id + role + pagelet + aria + preview);
                  current = current.parentElement;
                }
                return parts.join(' <- ');
                """.trimIndent(),
                element,
            ) as? String
        }.getOrNull().orEmpty().ifBlank { "<unknown>" }
    }

    private fun formatUrls(urls: Iterable<String>): String =
        urls.distinct()
            .take(LOG_URL_LIMIT)
            .joinToString(prefix = "[", postfix = "]")
            .ifBlank { "[]" }

    private fun String.abbreviateForLog(limit: Int = LOG_TEXT_PREVIEW_LIMIT): String =
        if (length <= limit) this else take(limit) + "..."

    private fun valueDiagnostic(value: String?): String =
        value
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?.let { "present(len=${it.length},excerpt='${it.abbreviateForLog()}')" }
            ?: "absent"

    private fun importCandidate(
        candidate: FacebookPostCandidate,
        creatorId: Long,
        candidateNumber: Int,
        candidateTotal: Int,
    ): ImportOutcome {
        val progress = importProgress(candidateNumber, candidateTotal)
        try {
            if (!isImportableCandidateUrl(candidate.url, candidate.text)) {
                logger.info("{} Skipping non-importable Facebook candidate URL {}", progress, candidate.url)
                return ImportOutcome.SKIPPED
            }
            logger.info("{} Importing Facebook-marked post {}", progress, candidate.url)
            val article = createArticleWithRetry(candidate, creatorId, progress)
            logPostCreateArticleState(progress, candidate, article)
            if (isFacebookPostUrl(candidate.url)) {
                logger.warn(
                    "{} Facebook import will patch browser-captured content for {}; articleId={}; remote={}; candidateText={}",
                    progress,
                    candidate.url,
                    article.id,
                    isRemoteArticleApiConfigured(),
                    valueDiagnostic(candidate.text),
                )
                if (isRemoteArticleApiConfigured()) {
                    patchRemoteContent(article.id!!, candidate.url, candidate.text, progress)
                } else {
                    articleService.replaceContentCache(article.id!!, candidate.text)
                    logger.warn(
                        "{} Facebook import local content patch completed for {}; articleId={}",
                        progress,
                        candidate.url,
                        article.id,
                    )
                }
            } else {
                logger.warn(
                    "{} Facebook import will not patch browser-captured content for {}; isFacebookPostUrl=false; candidateText={}",
                    progress,
                    candidate.url,
                    valueDiagnostic(candidate.text),
                )
            }
            logger.info("{} Imported Facebook-marked post {}", progress, candidate.url)
            return ImportOutcome.IMPORTED
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: ArticleUrlConflictException) {
            logger.info("{} Skipping already imported post {}", progress, candidate.url)
            return ImportOutcome.ALREADY_IMPORTED
        } catch (ex: RestClientResponseException) {
            logger.error(
                "{} Could not import Facebook-marked post {}: {}",
                progress,
                candidate.url,
                importFailureReason(ex),
            )
            return ImportOutcome.FAILED
        } catch (ex: Exception) {
            logger.error(
                "{} Could not import Facebook-marked post {}: {}",
                progress,
                candidate.url,
                importFailureReason(ex),
                ex,
            )
            return ImportOutcome.FAILED
        }
    }

    private enum class ImportOutcome {
        IMPORTED,
        ALREADY_IMPORTED,
        SKIPPED,
        FAILED,
    }

    private data class ImportSummary(
        var processed: Int = 0,
        var imported: Int = 0,
        var alreadyImported: Int = 0,
        var skipped: Int = 0,
        var failed: Int = 0,
    ) {
        fun record(outcome: ImportOutcome) {
            processed++
            when (outcome) {
                ImportOutcome.IMPORTED -> imported++
                ImportOutcome.ALREADY_IMPORTED -> alreadyImported++
                ImportOutcome.SKIPPED -> skipped++
                ImportOutcome.FAILED -> failed++
            }
        }
    }

    private fun importProgress(candidateNumber: Int, candidateTotal: Int): String =
        "Facebook import candidate $candidateNumber/$candidateTotal:"

    private fun discoveryProgress(postNumber: Int, postTotal: Int): String =
        "Facebook discovery post $postNumber/$postTotal:"

    private fun importFailureReason(ex: Exception): String =
        when (ex) {
            is RestClientResponseException -> {
                val detail = problemDetail(ex.responseBodyAsString)
                    ?: ex.responseBodyAsString.takeIf { it.isNotBlank() }
                buildString {
                    append("remote API returned HTTP ")
                    append(ex.statusCode.value())
                    if (!detail.isNullOrBlank()) {
                        append(" - ")
                        append(detail.cleanText().abbreviateForLog(300))
                    }
                }
            }
            else -> ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName
        }

    private fun problemDetail(responseBody: String): String? =
        Regex(""""detail"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(responseBody)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")

    private fun createArticleWithRetry(
        candidate: FacebookPostCandidate,
        creatorId: Long,
        progress: String,
    ): ArticleResponse {
        var attempt = 1
        while (true) {
            try {
                return createArticle(candidate, creatorId)
            } catch (ex: RestClientResponseException) {
                val retryDelay = retryDelayForArticleCreateFailure(ex, attempt) ?: throw ex
                logger.warn(
                    "{} Facebook import article creation failed for {} on attempt {}: {}; retrying in {} seconds",
                    progress,
                    candidate.url,
                    attempt,
                    importFailureReason(ex),
                    retryDelay.seconds,
                )
                sleep(retryDelay)
                attempt++
            }
        }
    }

    private fun retryDelayForArticleCreateFailure(ex: RestClientResponseException, attempt: Int): Duration? {
        if (!isRetryableArticleCreateFailure(ex)) return null
        return when (attempt) {
            1 -> Duration.ofSeconds(10)
            2 -> Duration.ofSeconds(60)
            else -> null
        }
    }

    private fun isRetryableArticleCreateFailure(ex: RestClientResponseException): Boolean =
        ex.statusCode.value() == HttpStatus.UNPROCESSABLE_ENTITY.value() &&
            ex.responseBodyAsString.contains("URL enrichment failed: target returned HTTP 400")

    private fun createArticle(candidate: FacebookPostCandidate, creatorId: Long): ArticleResponse {
        logFacebookPhotoCreateMode(candidate, creatorId)
        if (!isRemoteArticleApiConfigured()) {
            if (properties.targetApiBaseUrl.isNotBlank() || properties.targetApiKey.isNotBlank()) {
                throw IllegalStateException(
                    "Remote Facebook import is misconfigured: set both " +
                        "APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL and APP_FACEBOOK_IMPORT_TARGET_API_KEY",
                )
            }
            val article = articleService.create(
                ArticleInput(
                    url = candidate.url,
                    language = properties.language,
                    quote = properties.markerPhrase,
                ),
                creatorId,
            )
            if (isFacebookPhotoUrl(candidate.url)) {
                logger.warn(
                    "Facebook photo import local create returned for {}; articleId={}; title={}; thumbnail={}; lead={}; publishedAt={}",
                    candidate.url,
                    article.id,
                    valueDiagnostic(article.title),
                    valueDiagnostic(article.thumbnail),
                    valueDiagnostic(article.lead),
                    article.publishedAt,
                )
            }
            return ArticleResponse.from(article, null)
        }

        try {
            if (isFacebookPhotoUrl(candidate.url)) {
                logger.warn(
                    "Facebook photo import remote create request for {}; targetPath='{}'; candidateText={}",
                    candidate.url,
                    properties.targetArticlePath,
                    valueDiagnostic(candidate.text),
                )
            }
            return remoteArticleClient()
                .post()
                .uri(properties.targetArticlePath)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(
                    ArticleInput(
                        url = candidate.url,
                        language = properties.language,
                        quote = properties.markerPhrase,
                    )
                )
                .retrieve()
                .body(ArticleResponse::class.java)
                ?: throw IllegalStateException("Remote article API did not return a created article")
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.value() == HttpStatus.CONFLICT.value()) {
                throw ArticleUrlConflictException(candidate.url)
            }
            if (ex.statusCode.value() == HttpStatus.FORBIDDEN.value()) {
                throw IllegalStateException(
                    "Remote article API rejected the Facebook import machine credential. " +
                        "Check APP_FACEBOOK_IMPORT_TARGET_API_KEY on the worker, " +
                        "APP_MACHINE_AUTH_API_KEY on the server, and that " +
                        "APP_MACHINE_AUTH_PRINCIPAL_EMAIL is an active allowlisted user.",
                    ex,
                )
            }
            throw ex
        }
    }

    private fun logFacebookPhotoCreateMode(candidate: FacebookPostCandidate, creatorId: Long) {
        if (!isFacebookPhotoUrl(candidate.url)) return

        logger.warn(
            "Facebook photo import create mode for {}; creatorId={}; remoteConfigured={}; targetBaseConfigured={}; " +
                "targetPath='{}'; targetHeader='{}'; candidateText={}",
            candidate.url,
            creatorId,
            isRemoteArticleApiConfigured(),
            properties.targetApiBaseUrl.isNotBlank(),
            properties.targetArticlePath,
            properties.targetApiKeyHeader,
            valueDiagnostic(candidate.text),
        )
    }

    private fun patchRemoteContent(articleId: Long, url: String, content: String, progress: String) {
        val response = try {
            remoteArticleClient()
                .patch()
                .uri("/api/articles/{id}", articleId)
                .contentType(MediaType.valueOf("application/merge-patch+json"))
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(mapOf("content" to content))
                .retrieve()
                .toBodilessEntity()
        } catch (ex: RestClientResponseException) {
            logger.error(
                "{} Facebook import remote content patch failed for {}; articleId={}; status={}; body={}; content={}",
                progress,
                url,
                articleId,
                ex.statusCode.value(),
                valueDiagnostic(ex.responseBodyAsString),
                valueDiagnostic(content),
            )
            throw ex
        }
        logger.warn(
            "{} Facebook import remote content patch completed for {}; articleId={}; status={}; content={}",
            progress,
            url,
            articleId,
            response.statusCode.value(),
            valueDiagnostic(content),
        )
    }

    private fun logPostCreateArticleState(progress: String, candidate: FacebookPostCandidate, article: ArticleResponse) {
        if (!isFacebookPhotoUrl(candidate.url)) return

        logger.warn(
            "{} Facebook photo article created before content patch; url={}; articleId={}; title={}; thumbnail={}; " +
                "lead={}; publishedAt={}; candidateText={}; willPatchContent={}",
            progress,
            candidate.url,
            article.id,
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
            valueDiagnostic(candidate.text),
            isFacebookPostUrl(candidate.url),
        )
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
        if (isVisiblyTruncatedUrl(uri)) return false
        if (host == "facebook.com" || host.endsWith(".facebook.com")) return false
        if (host == "youtube.com" || host.endsWith(".youtube.com")) return false
        if (host == "youtu.be") return false
        if (host == "meta.ai" || host.endsWith(".meta.ai")) return false
        if (isMediaOrThumbnailUrl(url)) return false
        if (isMarkupNoiseUrl(url)) return false
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return false
        return true
    }

    private fun isVisiblyTruncatedUrl(uri: URI): Boolean {
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        return path.contains("...") ||
            path.contains("%E2%80%A6", ignoreCase = true) ||
            query.contains("...") ||
            query.contains("%E2%80%A6", ignoreCase = true)
    }

    private fun isMediaOrThumbnailUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "fbcdn.net" || host.endsWith(".fbcdn.net")) return true
        if (host.startsWith("scontent-") && host.contains(".fbcdn.net")) return true
        return false
    }

    private fun isMarkupNoiseUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = (uri.path ?: "").trimEnd('/')
        return (host == "www.w3.org" && path.startsWith("/2000/svg")) ||
            ((host == "fbsbx.com" || host.endsWith(".fbsbx.com")) && path == "/maw_proxy_page")
    }

    private fun isImportableCandidateUrl(url: String, postText: String): Boolean =
        isExternalArticleUrl(url) ||
            isImportableFacebookArticleUrl(url) ||
            isImportableSharedFacebookPhotoUrl(url, postText)

    private fun isImportableFacebookArticleUrl(url: String): Boolean =
        isFacebookPostUrl(url) && !isFacebookPhotoUrl(url)

    private fun isImportableSharedFacebookPhotoUrl(url: String, postText: String): Boolean =
        isFacebookPhotoUrl(url) && !containsExternalUrl(postText)

    private fun isConfiguredProfilePostUrl(url: String): Boolean {
        val configuredSlug = configuredProfileSlug() ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        val firstPathSegment = uri.path
            ?.trim('/')
            ?.substringBefore('/')
            ?.lowercase()
            ?: return false
        return firstPathSegment == configuredSlug
    }

    private fun configuredProfileSlug(): String? {
        val uri = runCatching { URI(properties.profileUrl) }.getOrNull() ?: return null
        return uri.path
            ?.trim('/')
            ?.substringBefore('/')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
    }

    private fun containsExternalUrl(text: String): Boolean =
        TEXT_URL_REGEX.findAll(text)
            .mapNotNull { it.value.decodeHtmlEntities().trimUrlBoundaryCharacters().toCleanFacebookUrl() }
            .any { isExternalUrlLike(it) }

    private fun isExternalUrlLike(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.scheme !in setOf("http", "https")) return false
        if (host == "facebook.com" || host.endsWith(".facebook.com")) return false
        if (host == "youtube.com" || host.endsWith(".youtube.com")) return false
        if (host == "youtu.be") return false
        if (host == "meta.ai" || host.endsWith(".meta.ai")) return false
        if (isMediaOrThumbnailUrl(url)) return false
        if (isMarkupNoiseUrl(url)) return false
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return false
        return true
    }

    private fun isFacebookPostUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: ""
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        val query = uri.rawQuery.orEmpty()
        if (isFacebookNotificationNoiseUrl(query)) return false
        return path.contains("/posts/") ||
            isFacebookReelPath(path) ||
            path.contains("/share/") ||
            path.contains("/permalink/") ||
            ((path.contains("/photo") || path.contains("/photo.php")) &&
                query.contains("fbid=") &&
                (query.contains("set=") || query.contains("story_fbid="))) ||
            query.contains("story_fbid=")
    }

    private fun isFacebookReelPath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        if (!(normalized.startsWith("/reel/") || normalized.startsWith("/reels/"))) return false
        val id = normalized.substringAfterLast('/')
        return id.isNotBlank() && id != "reel" && id != "reels"
    }

    private fun isFacebookNotificationNoiseUrl(query: String): Boolean =
        query.contains("notif_id=") ||
            query.contains("notif_t=") ||
            query.contains("ref=notif") ||
            query.contains("feedback_reaction_generic")

    private fun isFacebookPhotoUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        val path = uri.path ?: ""
        return path.contains("/photo") || path.contains("/photo.php")
    }

    private fun sleep(duration: Duration) {
        Thread.sleep(duration.toMillis())
    }

    private data class FacebookPostCandidate(
        val url: String,
        val text: String,
    )

    private data class MarkedFacebookPost(
        val element: WebElement,
        val text: String,
    )

    private data class ElementDiagnostic(
        val tag: String,
        val role: String,
        val dataPagelet: String,
        val ariaPosinset: String,
        val dataAdPreview: String,
        val className: String,
        val rect: String,
        val ancestorPath: String,
    )

    private data class LinkDiagnostic(
        val href: String,
        val text: String,
        val ariaLabel: String,
        val role: String,
        val facebookPost: Boolean,
        val externalArticle: Boolean,
        val mediaOrThumbnail: Boolean,
        val markupNoise: Boolean,
    )

    private data class UrlDiagnostic(
        val source: String,
        val url: String,
        val facebookPost: Boolean,
        val configuredProfilePost: Boolean,
        val facebookPhoto: Boolean,
        val importableFacebookArticle: Boolean,
        val externalArticle: Boolean,
        val mediaOrThumbnail: Boolean,
        val markupNoise: Boolean,
        val priority: Int?,
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
        private const val LOG_URL_LIMIT = 12
        private const val LOG_DIAGNOSTIC_LIMIT = 24
        private const val LOG_TEXT_PREVIEW_LIMIT = 500
        private const val REMOTE_API_CONNECT_TIMEOUT_MS = 3_000
        private const val REMOTE_API_READ_TIMEOUT_MS = 5_000
    }

    private fun ensureDriver(): WebDriver = synchronized(stateLock) {
        val existing = driver
        if (existing != null && isDriverAlive(existing)) {
            return existing
        }

        runCatching { existing?.quit() }
        val created = openDriver()
        driver = created
        created
    }

    private fun isDriverAlive(driver: WebDriver): Boolean =
        runCatching { driver.windowHandles }.isSuccess

    private fun isRemoteArticleApiConfigured(): Boolean =
        properties.targetApiBaseUrl.isNotBlank() && properties.targetApiKey.isNotBlank()

    private fun remoteArticleClient(): RestClient =
        RestClient.builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(REMOTE_API_CONNECT_TIMEOUT_MS.toLong()))
                        .build(),
                ).apply {
                    setReadTimeout(Duration.ofMillis(REMOTE_API_READ_TIMEOUT_MS.toLong()))
                }
            )
            .baseUrl(properties.targetApiBaseUrl)
            .build()

    @PreDestroy
    fun shutdown() {
        synchronized(stateLock) {
            activeImportThread?.interrupt()
            activeImportThread = null
            runCatching { driver?.quit() }
            driver = null
        }
    }
}
