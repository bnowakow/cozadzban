// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import jakarta.annotation.PreDestroy
import org.jsoup.Jsoup
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.Keys
import org.openqa.selenium.NoSuchWindowException
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebElement
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.firefox.FirefoxOptions
import org.openqa.selenium.firefox.FirefoxProfile
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.ArticleInput
import pl.bnowakowski.cozadzban.article.ArticleResponse
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.article.ArticleUrlConflictException
import pl.bnowakowski.cozadzban.user.AppUserRepository
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class FacebookProfileArticleImporter(
    private val properties: FacebookImportProperties,
    private val appUserRepository: AppUserRepository,
    private val articleService: ArticleService,
    private val proposalClient: FacebookImportProposalClient? = null,
    private val eventPublisher: ApplicationEventPublisher? = null,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val facebookProperties = FacebookLoginPropertiesReader()
    private val dotEnvValues = loadDotEnvValues()
    private val stateLock = Any()
    @Volatile private var activeImportThread: Thread? = null
    @Volatile private var driver: WebDriver? = null

    fun startImport() {
        startImport(FacebookCandidateApprovalHandler.acceptAll())
    }

    fun startImport(approvalHandler: FacebookCandidateApprovalHandler) {
        facebookImportUnavailableReason()?.let { throw IllegalArgumentException(it) }

        synchronized(stateLock) {
            if (activeImportThread?.isAlive == true) {
                throw FacebookImportAlreadyRunningException()
            }

            val importRunId = newImportRunId()
            val importThread = Thread {
                try {
                    runImport(importRunId, FacebookImportTrigger.MANUAL)
                } catch (ex: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logger.info("Facebook import was interrupted")
                } catch (ex: NoSuchWindowException) {
                    discardDriver()
                    if (Thread.currentThread().isInterrupted) {
                        logger.info("Facebook import was interrupted")
                    } else {
                        logger.info("Facebook import stopped because the browser window was closed")
                    }
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

    fun facebookImportUnavailableReason(): String? {
        if (!properties.enabled) {
            return "app.facebook-import.enabled must be true"
        }
        if (properties.targetApiBaseUrl.isNotBlank() != properties.targetApiKey.isNotBlank()) {
            return "Remote Facebook import is misconfigured: set both APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL and APP_FACEBOOK_IMPORT_TARGET_API_KEY"
        }
        return null
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

    fun newImportRunId(generatedAt: Instant = Instant.now()): String =
        facebookImportId(generatedAt)

    fun runImport(importRunId: String, trigger: FacebookImportTrigger = FacebookImportTrigger.MANUAL) {
        facebookImportUnavailableReason()?.let { throw IllegalArgumentException(it) }
        val currentThread = Thread.currentThread()
        synchronized(stateLock) {
            val activeThread = activeImportThread
            if (activeThread?.isAlive == true && activeThread !== currentThread) {
                throw FacebookImportAlreadyRunningException()
            }
            activeImportThread = currentThread
        }

        val summary = ProposalImportSummary()
        var completionStatus = FacebookImportRunStatus.FINISHED
        var completionLogs = ""
        try {
            runImportInternal(importRunId, trigger, summary)
            if (summary.failed > 0) {
                completionStatus = FacebookImportRunStatus.FAILED
            }
            completionLogs = summary.logsWith(
                "Facebook import finished: ${summary.discovered} discovered, ${summary.submitted} submitted, " +
                    "${summary.skippedExisting} skipped existing, ${summary.failed} failed.",
            )
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            completionStatus = FacebookImportRunStatus.TERMINATED
            completionLogs = summary.logsWith("Facebook import was terminated.")
            logger.info("Facebook import {} was interrupted", importRunId)
            throw ex
        } catch (ex: NoSuchWindowException) {
            discardDriver()
            completionStatus = if (Thread.currentThread().isInterrupted) {
                FacebookImportRunStatus.TERMINATED
            } else {
                FacebookImportRunStatus.FAILED
            }
            completionLogs = summary.logsWith(
                "Facebook import stopped because the browser window was closed: ${failureMessage(ex)}",
            )
            logger.warn("Facebook import {} stopped because the browser window was closed", importRunId, ex)
            throw ex
        } catch (ex: Exception) {
            completionStatus = FacebookImportRunStatus.FAILED
            completionLogs = summary.logsWith("Facebook import failed: ${failureMessage(ex)}")
            logger.warn("Facebook import {} failed", importRunId, ex)
            throw ex
        } finally {
            completeRunSafely(importRunId, completionStatus, summary, completionLogs)
            synchronized(stateLock) {
                if (activeImportThread === currentThread) {
                    activeImportThread = null
                }
            }
        }
    }

    private fun runImportInternal(
        facebookImportId: String,
        trigger: FacebookImportTrigger,
        summary: ProposalImportSummary,
    ) {
        val driver = ensureDriver()
        prepareProfileAndLogin(driver, facebookImportId, trigger)
        sleep(properties.waitAfterPageOpen)
        val passCount = (1 until properties.scrolls step 2).count()
        logger.info(
            "Facebook import {} starting {} discovery passes with up to {} configured scrolls",
            facebookImportId,
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
            val candidateDecisionLogs = mutableListOf<String>()
            val proposals = candidates.mapIndexedNotNull { index, candidate ->
                val candidateId = candidateApprovalId()
                if (!isImportableCandidateUrl(candidate.url, candidate.text)) {
                    candidateDecisionLogs += workerCandidateDecisionLogs(
                        candidateId = candidateId,
                        candidateNumber = index + 1,
                        candidateTotal = candidates.size,
                        candidate = candidate,
                        action = "skipped-non-importable",
                    )
                    logger.info(
                        "Facebook import discovery pass {}/{} candidate {}/{} importId={} candidateId={} url={} sourcePostUrl={} skippedNonImportable=true",
                        passIndex + 1,
                        passCount,
                        index + 1,
                        candidates.size,
                        facebookImportId,
                        candidateId,
                        candidate.url,
                        candidate.sourcePostUrl ?: "<none>",
                    )
                    return@mapIndexedNotNull null
                }
                val exists = proposalExists(candidate.url, candidateId)
                if (exists) {
                    summary.skippedExisting++
                    candidateDecisionLogs += workerCandidateDecisionLogs(
                        candidateId = candidateId,
                        candidateNumber = index + 1,
                        candidateTotal = candidates.size,
                        candidate = candidate,
                        action = "skipped-existing",
                    )
                    logger.info(
                        "Facebook import discovery pass {}/{} candidate {}/{} importId={} candidateId={} url={} sourcePostUrl={} skippedExisting=true",
                        passIndex + 1,
                        passCount,
                        index + 1,
                        candidates.size,
                        facebookImportId,
                        candidateId,
                        candidate.url,
                        candidate.sourcePostUrl ?: "<none>",
                    )
                    null
                } else {
                    val language = guessCandidateLanguage(candidate)
                    candidateDecisionLogs += workerCandidateDecisionLogs(
                        candidateId = candidateId,
                        candidateNumber = index + 1,
                        candidateTotal = candidates.size,
                        candidate = candidate,
                        action = "proposal-submit",
                        language = language,
                    )
                    logger.info(
                        "Facebook import discovery pass {}/{} candidate {}/{} importId={} candidateId={} url={} sourcePostUrl={} language={} skippedExisting=false action=proposal-submit",
                        passIndex + 1,
                        passCount,
                        index + 1,
                        candidates.size,
                        facebookImportId,
                        candidateId,
                        candidate.url,
                        candidate.sourcePostUrl ?: "<none>",
                        language,
                    )
                    FacebookProposalSubmission(
                        candidateId = candidateId,
                        articleUrl = candidate.url,
                        facebookPostUrl = candidate.sourcePostUrl,
                        language = language,
                        logs = candidateProposalLogs(candidate),
                    )
                }
            }
            summary.discovered += candidates.size
            val passLogs = workerPassLogs(
                passIndex = passIndex + 1,
                passCount = passCount,
                candidateCount = candidates.size,
                proposalCount = proposals.size,
                candidateDecisionLogs = candidateDecisionLogs,
            )
            summary.recordWorkerLogs(passLogs)
            if (proposals.isNotEmpty() || candidates.isNotEmpty()) {
                try {
                    val response = proposalClient?.submitBatch(
                        FacebookProposalBatchRequest(
                            importRunId = facebookImportId,
                            passIndex = passIndex + 1,
                            passCount = passCount,
                            proposals = proposals,
                            logs = passLogs,
                        ),
                    ) ?: FacebookProposalBatchResponse(facebookImportId, proposals.size, 0)
                    summary.submitted += response.submitted
                    summary.skippedExisting += response.skippedExisting
                } catch (ex: Exception) {
                    summary.failed += proposals.size
                    logger.warn(
                        "Facebook import discovery pass {}/{} could not submit proposal batch; importId={}; proposalCount={}; reason={}",
                        passIndex + 1,
                        passCount,
                        facebookImportId,
                        proposals.size,
                        importFailureReason(ex),
                        ex,
                    )
                }
            }
            logger.info(
                "Facebook import discovery pass {}/{} finished: {} discovered, {} submitted, {} skipped existing, {} failed so far",
                passIndex + 1,
                passCount,
                summary.discovered,
                summary.submitted,
                summary.skippedExisting,
                summary.failed,
            )
        }
        logger.info(
            "Facebook import finished: {} discovered, {} submitted, {} skipped existing, {} failed",
            summary.discovered,
            summary.submitted,
            summary.skippedExisting,
            summary.failed,
        )
    }

    private fun completeRunSafely(
        importRunId: String,
        status: FacebookImportRunStatus,
        summary: ProposalImportSummary,
        logs: String,
    ) {
        try {
            proposalClient?.completeRun(
                importRunId,
                FacebookImportRunCompletionRequest(
                    status = status,
                    discoveredCount = summary.discovered,
                    submittedCount = summary.submitted,
                    skippedExistingCount = summary.skippedExisting,
                    failedCount = summary.failed,
                    logs = logs,
                ),
            )
        } catch (ex: Exception) {
            logger.warn(
                "Facebook import {} could not record terminal status {}; reason={}",
                importRunId,
                status,
                failureMessage(ex),
                ex,
            )
        }
    }

    private fun failureMessage(ex: Throwable): String =
        ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName

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

    fun prepareProfileAndLogin(
        driver: WebDriver,
        importRunId: String? = null,
        trigger: FacebookImportTrigger = FacebookImportTrigger.MANUAL,
    ) {
        driver.get(properties.profileUrl)
        sleep(properties.waitAfterPageOpen)

        if (isLoggedIn(driver)) {
            logger.info("Facebook already appears to be logged in")
            return
        }

        importRunId?.let { publishLoginRequired(it, trigger) }
        logger.warn(
            "LOGIN_REQUIRED Facebook login is required before import can continue; importRunId={}; trigger={}; manualLoginTimeout={}",
            importRunId ?: "<unknown>",
            trigger,
            properties.manualLoginTimeout,
        )
        login(driver)
        waitForLogin(driver)
        driver.get(properties.profileUrl)
        sleep(properties.waitAfterPageOpen)
    }

    private fun publishLoginRequired(importRunId: String, trigger: FacebookImportTrigger) {
        val request = FacebookImportLoginRequiredRequest(
            trigger = trigger,
            profileUrl = properties.profileUrl,
        )
        if (proposalClient != null) {
            try {
                proposalClient.recordLoginRequired(importRunId, request)
                return
            } catch (ex: Exception) {
                logger.warn(
                    "Facebook import {} could not report login-required event to target server; reason={}",
                    importRunId,
                    failureMessage(ex),
                    ex,
                )
            }
        }
        eventPublisher?.publishEvent(
            FacebookImportLoginRequiredEvent(
                importRunId = importRunId,
                trigger = trigger,
                profileUrl = properties.profileUrl,
                detectedAt = request.detectedAt,
            ),
        )
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
            val text = elementText(element)?.cleanText() ?: return@mapNotNull null
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
            val postUrl = findPostUrlSelection(
                driver,
                markedPost.element,
                discoveryProgress(index + 1, markedPosts.size),
                markedPost.text,
            )
                ?: return@mapIndexedNotNull null
            FacebookPostCandidate(postUrl.url, markedPost.text, postUrl.sourcePostUrl)
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
        findPostUrlSelection(driver, element, null)?.url

    private fun findPostUrl(driver: WebDriver, element: WebElement, discoveryProgress: String?): String? =
        findPostUrlSelection(driver, element, discoveryProgress)?.url

    private fun findPostUrlSelection(
        driver: WebDriver,
        element: WebElement,
        discoveryProgress: String?,
        fallbackText: String? = null,
    ): PostUrlSelection? {
        val text = fallbackText ?: elementText(element) ?: return null
        val links = linkElements(element)
            .mapNotNull { link ->
                runCatching {
                    link.getAttribute("href")?.decodeHtmlEntities()?.toCleanFacebookUrl()
                }.getOrNull()
            }
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
        val containerSourcePostUrl = facebookPostUrls.firstOrNull { !isConfiguredProfilePostUrl(it) }
            ?: htmlPostUrl
            ?: facebookPostUrls.firstOrNull()
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
            return PostUrlSelection(it, containerSourcePostUrl)
        }

        if (htmlPostUrl != null && !isFacebookPhotoUrl(htmlPostUrl)) {
            logPostUrlDecision("html-facebook-fallback", htmlPostUrl, text, facebookPostUrls, links)
            return PostUrlSelection(htmlPostUrl, htmlPostUrl)
        }

        val sourcePostUrls = facebookSourcePostUrlsToOpen(facebookPostUrls, htmlPostUrl)
        val selectedFromOpenedPost = sourcePostUrls.asSequence()
            .filterNot { isConfiguredProfilePostUrl(it) }
            .mapNotNull { sourcePostUrl ->
                extractCandidateUrlFromFacebookPost(driver, sourcePostUrl, discoveryProgress = discoveryProgress)
                    ?.let { PostUrlSelection(it, sourcePostUrl) }
            }
            .firstOrNull()
            ?: sourcePostUrls.asSequence()
                .filter { isConfiguredProfilePostUrl(it) }
                .mapNotNull { sourcePostUrl ->
                    extractCandidateUrlFromFacebookPost(driver, sourcePostUrl, discoveryProgress = discoveryProgress)
                        ?.let { PostUrlSelection(it, sourcePostUrl) }
                }
                .firstOrNull()
        if (selectedFromOpenedPost != null) {
            logPostUrlDecision("opened-facebook-post", selectedFromOpenedPost.url, text, facebookPostUrls, links)
            return selectedFromOpenedPost
        }

        extractExternalArticleUrlFromHtml(driver, element, text)?.let {
            logPostUrlDecision("html-url", it, text, facebookPostUrls, links)
            return PostUrlSelection(it, containerSourcePostUrl)
        }

        links.firstOrNull { isExternalArticleUrl(it) && isUrlHostMentionedInText(it, text) }?.let {
            logPostUrlDecision("link-url", it, text, facebookPostUrls, links)
            return PostUrlSelection(it, containerSourcePostUrl)
        }

        facebookPostUrls.firstOrNull { !isConfiguredProfilePostUrl(it) && isImportableFacebookArticleUrl(it) }?.let {
            logPostUrlDecision("facebook-post-fallback", it, text, facebookPostUrls, links)
            return PostUrlSelection(it, it)
        }

        facebookPostUrls.firstOrNull { isImportableSharedFacebookPhotoUrl(it, text) }?.let {
            logPostUrlDecision("facebook-photo-fallback", it, text, facebookPostUrls, links)
            return PostUrlSelection(it, it)
        }

        htmlPostUrl?.let {
            logPostUrlDecision("html-facebook-fallback", it, text, facebookPostUrls, links)
            return PostUrlSelection(it, it)
        }

        logPostUrlDecision("none", null, text, facebookPostUrls, links)
        return null
    }

    private fun facebookSourcePostUrlsToOpen(facebookPostUrls: List<String>, htmlPostUrl: String?): List<String> =
        buildList {
            addAll(facebookPostUrls)
            if (htmlPostUrl != null && isFacebookPostUrl(htmlPostUrl)) {
                add(htmlPostUrl)
            }
        }.distinct()

    private fun extractExternalArticleUrlFromHtml(
        driver: WebDriver,
        element: WebElement,
        visibleText: String,
    ): String? {
        val html = elementOuterHtml(driver, element) ?: return null
        extractExternalArticleUrlFromText(html, visibleText = visibleText)?.let { return it }
        return HREF_VALUE_REGEX.findAll(html)
            .mapNotNull { it.groupValues[1].decodeHtmlEntities().toCleanFacebookUrl() }
            .distinct()
            .firstOrNull { isExternalArticleUrl(it) && isUrlHostMentionedInText(it, visibleText) }
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
        allowWeakExternalArticleUrls: Boolean = true,
        allowFacebookFallbackUrl: Boolean = true,
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

            val nestedSearchCandidates = nestedFacebookPostCandidatesToOpen(
                facebookCandidates = facebookCandidates,
                postUrl = postUrl,
                visited = visited,
            )
            val nestedCandidate = nestedSearchCandidates.asSequence()
                .mapNotNull { candidate ->
                    extractCandidateUrlFromFacebookPost(
                        driver,
                        candidate,
                        visited = visited + postUrl,
                        discoveryProgress = discoveryProgress,
                        allowWeakExternalArticleUrls = false,
                        allowFacebookFallbackUrl = false,
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
            val preferredExternalArticleUrl = preferredExternalArticleUrlForFacebookPost(
                postUrl,
                linkArticleUrls + listOfNotNull(pageTextArticleUrl) + pageHrefArticleUrls,
            )
            val weakExternalArticleUrl = if (!allowWeakExternalArticleUrls) {
                null
            } else if (isFacebookPhotoUrl(postUrl)) {
                linkArticleUrls.bestSpecificExternalArticleUrl()
            } else {
                linkArticleUrls.bestExternalArticleUrl()
                    ?: pageTextArticleUrl
                    ?: pageHrefArticleUrls.bestExternalArticleUrl()
            }
            val visibleBodyTextArticleUrl = if (isFacebookPhotoUrl(postUrl)) {
                bodyTextArticleUrl?.takeIf { isSpecificExternalArticleUrl(it) }
            } else {
                bodyTextArticleUrl
            }
            val unsafeSelected = nestedCandidate
                ?: visibleBodyTextArticleUrl
                ?: preferredExternalArticleUrl
                ?: weakExternalArticleUrl
                ?: facebookFallbackUrl.takeIf { allowFacebookFallbackUrl && !isFacebookPhotoUrl(postUrl) }
            val selected = unsafeSelected?.takeUnless {
                isConfiguredProfilePostUrl(postUrl) &&
                    isExternalArticleUrl(it) &&
                    isGenericFacebookFeedPage(bodyText)
            }
            val selectedSource = when (selected) {
                null -> "none"
                nestedCandidate -> "nested-facebook-post"
                visibleBodyTextArticleUrl -> "visible-text-url"
                preferredExternalArticleUrl -> "profile-matched-external-url"
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

    private fun nestedFacebookPostCandidatesToOpen(
        facebookCandidates: List<String>,
        postUrl: String,
        visited: Set<String>,
    ): List<String> {
        if (visited.isNotEmpty()) return emptyList()
        return facebookCandidates.asSequence()
            .filterNot { it == postUrl }
            .filterNot { it in visited }
            .filterNot { isFacebookPhotoUrl(it) }
            .filterNot { isFacebookCommentUrl(it) }
            .filterNot { isConfiguredProfilePostUrl(it) }
            .distinctBy { it.withoutFragment().withoutFacebookNoiseQuery() }
            .take(MAX_NESTED_FACEBOOK_POSTS_TO_OPEN)
            .toList()
    }

    private fun extractPostUrlFromHtml(driver: WebDriver, element: WebElement): String? {
        val html = elementOuterHtml(driver, element) ?: return null

        val normalizedHtml = html.withDecodedFacebookUrlEscapes()
        val candidates = listOf(
            FACEBOOK_POST_URL_REGEX,
            FACEBOOK_RELATIVE_POST_URL_REGEX,
            FACEBOOK_STORY_URL_REGEX,
            FACEBOOK_RELATIVE_STORY_URL_REGEX,
            FACEBOOK_PHOTO_URL_REGEX,
            FACEBOOK_RELATIVE_PHOTO_URL_REGEX,
        ).flatMap { regex ->
            regex.findAll(html).map { it.value } + regex.findAll(normalizedHtml).map { it.value }
        } +
            HREF_VALUE_REGEX.findAll(normalizedHtml)
                .map { it.groupValues[1].replace("&amp;", "&") }

        val cleanedCandidates = candidates.asSequence()
            .mapNotNull { it.toCleanFacebookUrl() }
            .distinct()

        return selectBestSharedFacebookPostUrl(cleanedCandidates.filter { isFacebookPostUrl(it) })
            ?: cleanedCandidates.firstOrNull { isExternalArticleUrl(it) }
    }

    private fun extractFacebookPostUrlCandidates(text: String): Sequence<String> =
        sequenceOf(text, text.withDecodedFacebookUrlEscapes())
            .flatMap { value -> TEXT_URL_REGEX.findAll(value).map { it.value } }
            .mapNotNull { it.decodeHtmlEntities().trimUrlBoundaryCharacters().toCleanFacebookUrl() }
            .filter { isFacebookPostUrl(it) }
            .distinct()

    private fun String.withDecodedFacebookUrlEscapes(): String =
        replace("\\/", "/")
            .replace("\\u0025", "%")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\u003F", "?")

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
        return normalizedText.contains(host) ||
            normalizedText.contains(normalizedHost) ||
            normalizedText.filter(Char::isLetterOrDigit)
                .contains(normalizedHost.substringBefore('.').filter(Char::isLetterOrDigit))
    }

    private fun List<String>.bestExternalArticleUrl(): String? =
        distinct()
            .minWithOrNull(
                compareBy<String>(
                    { if (isSpecificExternalArticleUrl(it)) 0 else 1 },
                    { it.length },
                )
            )

    private fun List<String>.bestSpecificExternalArticleUrl(): String? =
        distinct()
            .filter { isSpecificExternalArticleUrl(it) }
            .minByOrNull { it.length }

    private fun isSpecificExternalArticleUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val pathSegments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return pathSegments.size >= 2
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

    private fun preferredExternalArticleUrlForFacebookPost(postUrl: String, urls: Iterable<String>): String? {
        val profileToken = facebookPostProfileSlug(postUrl)?.urlOwnerToken() ?: return null
        return urls.distinct().firstOrNull { url ->
            val hostToken = runCatching { URI(url).host?.lowercase()?.removePrefix("www.")?.urlOwnerToken() }
                .getOrNull()
                ?: return@firstOrNull false
            hostToken.contains(profileToken) || profileToken.contains(hostToken)
        }
    }

    private fun facebookPostProfileSlug(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return null
        val firstSegment = uri.path
            ?.trim('/')
            ?.substringBefore('/')
            ?.lowercase()
            ?: return null
        return firstSegment.takeIf { it !in FACEBOOK_NON_PROFILE_PATH_SEGMENTS }
    }

    private fun String.urlOwnerToken(): String =
        filter(Char::isLetterOrDigit)

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
        linkElements(element)
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

    private fun elementText(element: WebElement): String? =
        runCatching { element.text }
            .getOrElse { ex ->
                if (ex is StaleElementReferenceException) {
                    logger.debug("Skipped a stale Facebook post container while reading text")
                }
                null
            }

    private fun linkElements(element: WebElement): List<WebElement> =
        runCatching { element.findElements(By.cssSelector("a[href]")) }
            .getOrElse { ex ->
                if (ex is StaleElementReferenceException) {
                    logger.debug("Skipped links for a stale Facebook post container")
                }
                emptyList()
            }

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

    private fun formatFailedUrls(urls: List<String>): String {
        if (urls.isEmpty()) return "[]"
        val uniqueUrls = urls.distinct()
        val shownUrls = uniqueUrls.take(LOG_URL_LIMIT).joinToString(separator = "\n")
        val omittedCount = uniqueUrls.size - LOG_URL_LIMIT
        return if (omittedCount > 0) {
            "$shownUrls\n... ($omittedCount more)"
        } else {
            shownUrls
        }
    }

    private fun proposalExists(url: String, candidateId: String): Boolean =
        runCatching { proposalClient?.existsByArticleUrl(url) ?: false }
            .onFailure { ex ->
                logger.warn(
                    "Facebook import proposal precheck failed; candidateId={}; url={}; treating as existing; reason={}",
                    candidateId,
                    url,
                    importFailureReason(ex as? Exception ?: RuntimeException(ex)),
                )
            }
            .getOrDefault(true)

    private fun candidateProposalLogs(candidate: FacebookPostCandidate): String =
        buildString {
            appendLine("selectedUrl=${candidate.url}")
            appendLine("sourcePostUrl=${candidate.sourcePostUrl ?: "<none>"}")
            appendLine("candidateTextLength=${candidate.text.length}")
            appendLine("candidateTextPreview=${candidate.text.cleanText().abbreviateForLog()}")
            appendLine("candidateText:")
            appendLine(candidate.text)
        }

    private fun workerCandidateDecisionLogs(
        candidateId: String,
        candidateNumber: Int,
        candidateTotal: Int,
        candidate: FacebookPostCandidate,
        action: String,
        language: String? = null,
    ): String =
        buildString {
            appendLine("candidate=$candidateNumber/$candidateTotal")
            appendLine("candidateId=$candidateId")
            appendLine("action=$action")
            language?.let { appendLine("language=$it") }
            append(candidateProposalLogs(candidate))
        }.trimEnd()

    private fun workerPassLogs(
        passIndex: Int,
        passCount: Int,
        candidateCount: Int,
        proposalCount: Int,
        candidateDecisionLogs: List<String>,
    ): String =
        buildString {
            appendLine(
                "Facebook import discovery pass $passIndex/$passCount found $candidateCount candidates " +
                    "and submitted $proposalCount proposals.",
            )
            candidateDecisionLogs.forEach { candidateLogs ->
                appendLine()
                appendLine(candidateLogs)
            }
        }.trimEnd()

    private fun guessCandidateLanguage(candidate: FacebookPostCandidate): String {
        val fallback = normalizedLanguageOrNull(properties.language) ?: "pl"
        return metadataLanguage(candidate.url)
            ?: urlLanguage(candidate.url)
            ?: textLanguage(candidate.text)
            ?: fallback
    }

    private fun metadataLanguage(url: String): String? {
        if (isFacebookUrl(url)) return null
        return runCatching {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "cozadzban-facebook-import-language-guess/1.0")
                .GET()
                .build()
            val response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val body = response.body().orEmpty()
            val doc = Jsoup.parse(body)
            val htmlLang = doc.selectFirst("html[lang]")?.attr("lang")
            val contentLanguage = response.headers().firstValue("Content-Language").orElse(null)
            val ogLocale = doc.selectFirst("meta[property=og:locale], meta[name=og:locale]")?.attr("content")
            listOf(htmlLang, contentLanguage, ogLocale)
                .asSequence()
                .mapNotNull(::normalizedLanguageOrNull)
                .firstOrNull()
        }.getOrNull()
    }

    private fun urlLanguage(url: String): String? {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return null
        return when {
            host.endsWith(".pl") || host in POLISH_LANGUAGE_HOSTS -> "pl"
            host.endsWith(".uk") || host.endsWith(".us") || host in ENGLISH_LANGUAGE_HOSTS -> "en"
            else -> null
        }
    }

    private fun textLanguage(text: String): String? {
        val normalized = text.lowercase()
        val polishSignals = listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż", " że ", " się ", " nie ", " oraz ")
        if (polishSignals.any { normalized.contains(it) }) return "pl"
        val englishSignals = listOf(" the ", " and ", " is ", " of ", " with ", " for ")
        return if (englishSignals.any { normalized.contains(it) }) "en" else null
    }

    private fun normalizedLanguageOrNull(raw: String?): String? {
        val candidate = raw
            ?.trim()
            ?.substringBefore(',')
            ?.substringBefore(';')
            ?.replace('_', '-')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore('-')
            ?: return null
        return runCatching { ArticleService.normalizeLanguage(candidate) }.getOrNull()
    }

    private fun approveCandidates(
        entries: List<CandidateApprovalEntry>,
        approvalHandler: FacebookCandidateApprovalHandler,
    ): List<FacebookPostCandidate> {
        if (entries.isEmpty()) return emptyList()
        return approvedCandidates(entries, approveCandidateDecisions(entries, approvalHandler))
    }

    private fun approveCandidateDecisions(
        entries: List<CandidateApprovalEntry>,
        approvalHandler: FacebookCandidateApprovalHandler,
    ): Map<String, FacebookCandidateApproval> =
        approvalHandler.approve(entries.map { it.approval })
            .associateBy { it.url }

    private fun approvedCandidates(
        entries: List<CandidateApprovalEntry>,
        approvalsByUrl: Map<String, FacebookCandidateApproval>,
    ): List<FacebookPostCandidate> {
        val approvedByUrl = approvalsByUrl.filterValues {
            it.decision == FacebookCandidateApprovalDecision.ACCEPT
        }
        return entries.mapNotNull { entry ->
            val approval = approvedByUrl[entry.candidate.url] ?: return@mapNotNull null
            entry.candidate.copy(language = approval.language)
        }
    }

    private fun isAlreadyImportedCandidateUrl(url: String): Boolean =
        if (isRemoteArticleApiConfigured()) {
            isAlreadyImportedRemoteCandidateUrl(url)
        } else {
            isAlreadyImportedLocalCandidateUrl(url)
        }

    private fun isAlreadyImportedLocalCandidateUrl(url: String): Boolean =
        runCatching { articleService.existsByUrl(url) }
            .onFailure { ex ->
                logger.warn(
                    "Facebook import local duplicate precheck failed for {}; proceeding to approval: {}",
                    url,
                    ex.message ?: ex.javaClass.simpleName,
                )
            }
            .getOrDefault(false)

    private fun isAlreadyImportedRemoteCandidateUrl(url: String): Boolean {
        val canonicalUrl = runCatching { ArticleService.canonicalizeUrl(url) }.getOrDefault(url)
        return runCatching {
            remoteArticleClient()
                .get()
                .uri { builder ->
                    builder
                        .path(properties.targetArticlePath)
                        .queryParam("existsUrl", canonicalUrl)
                        .build()
                }
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .retrieve()
                .body(RemoteArticleUrlExistsResponse::class.java)
                ?.exists ?: false
        }.onFailure { ex ->
            logger.warn(
                "Facebook import remote duplicate precheck failed for {}; canonicalUrl={}; treating as already imported: {}",
                url,
                canonicalUrl,
                ex.message ?: ex.javaClass.simpleName,
            )
        }.getOrDefault(true)
    }

    private fun candidateApprovalId(): String =
        "facebook-import-candidate-${CANDIDATE_APPROVAL_ID_SEQUENCE.incrementAndGet()}"

    private fun facebookImportId(generatedAt: Instant = Instant.now()): String =
        "facebook-import-${IMPORT_ARTIFACT_TIMESTAMP_FORMATTER.format(generatedAt)}-" +
            IMPORT_ID_SEQUENCE.incrementAndGet()

    private fun writeRejectedCandidateArtifact(
        facebookImportId: String,
        generatedAt: Instant,
        passIndex: Int,
        passCount: Int,
        candidateIndex: Int,
        candidateCount: Int,
        entry: CandidateApprovalEntry,
    ) {
        runCatching {
            val directory = rejectionArtifactDirectory()
            Files.createDirectories(directory)
            val filename = rejectedCandidateArtifactFilename(
                generatedAt = generatedAt,
                facebookImportId = facebookImportId,
                candidateId = entry.approval.candidateId,
            )
            val artifactPath = directory.resolve(filename)
            Files.writeString(
                artifactPath,
                rejectedCandidateArtifactJson(
                    facebookImportId = facebookImportId,
                    generatedAt = generatedAt,
                    passIndex = passIndex,
                    passCount = passCount,
                    candidateIndex = candidateIndex,
                    candidateCount = candidateCount,
                    entry = entry,
                ),
                StandardCharsets.UTF_8,
            )
            logger.info(
                "Facebook import rejected URL artifact written: importId={} candidateId={} url={} artifact={}",
                facebookImportId,
                entry.approval.candidateId,
                entry.candidate.url,
                artifactPath,
            )
        }.onFailure { ex ->
            logger.warn(
                "Facebook import could not write rejected URL artifact: importId={} candidateId={} url={} reason={}",
                facebookImportId,
                entry.approval.candidateId,
                entry.candidate.url,
                ex.message ?: ex.javaClass.simpleName,
                ex,
            )
        }
    }

    private fun rejectionArtifactDirectory(): Path =
        Path.of(properties.rejectionArtifactDir.ifBlank { "logs/facebook-import-rejections" })

    private fun rejectedCandidateArtifactFilename(
        generatedAt: Instant,
        facebookImportId: String,
        candidateId: String,
    ): String =
        listOf(
            REJECTION_ARTIFACT_TIMESTAMP_FORMATTER.format(generatedAt),
            facebookImportId,
            candidateId,
            "rejected-url",
        )
            .joinToString("_") { it.toFilenameToken() } + ".json"

    private fun rejectedCandidateArtifactJson(
        facebookImportId: String,
        generatedAt: Instant,
        passIndex: Int,
        passCount: Int,
        candidateIndex: Int,
        candidateCount: Int,
        entry: CandidateApprovalEntry,
    ): String {
        val candidate = entry.candidate
        return """
            {
              "facebookImportId": ${jsonString(facebookImportId)},
              "candidateId": ${jsonString(entry.approval.candidateId)},
              "generatedAt": ${jsonString(generatedAt.toString())},
              "decision": ${jsonString(FacebookCandidateApprovalDecision.REJECT.name)},
              "reason": "USER_REJECTED",
              "url": ${jsonString(candidate.url)},
              "sourcePostUrl": ${jsonNullableString(candidate.sourcePostUrl)},
              "language": ${jsonString(entry.approval.language)},
              "discoveryPass": $passIndex,
              "discoveryPassCount": $passCount,
              "discoveryIndex": ${entry.discoveryIndex},
              "candidateIndex": $candidateIndex,
              "candidateCount": $candidateCount,
              "candidateTextPreview": ${jsonString(candidate.text.cleanText().abbreviateForLog())},
              "candidateText": ${jsonString(candidate.text)},
              "urlSelectionDiagnostics": {
                "selectedUrl": ${jsonString(candidate.url)},
                "sourcePostUrl": ${jsonNullableString(candidate.sourcePostUrl)},
                "candidateTextLength": ${candidate.text.length}
              }
            }
        """.trimIndent() + "\n"
    }

    private fun String.toFilenameToken(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "unknown" }

    private fun jsonNullableString(value: String?): String =
        value?.let(::jsonString) ?: "null"

    private fun jsonString(value: String): String =
        buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (char.code < 0x20) {
                            append("\\u")
                            append(char.code.toString(16).padStart(4, '0'))
                        } else {
                            append(char)
                        }
                    }
                }
            }
            append('"')
        }

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
        var rejected: Int = 0,
        val failedUrls: MutableList<String> = mutableListOf(),
        val rejectedUrls: MutableList<String> = mutableListOf(),
    ) {
        fun record(outcome: ImportOutcome, url: String) {
            processed++
            when (outcome) {
                ImportOutcome.IMPORTED -> imported++
                ImportOutcome.ALREADY_IMPORTED -> alreadyImported++
                ImportOutcome.SKIPPED -> skipped++
                ImportOutcome.FAILED -> {
                    failed++
                    failedUrls += url
                }
            }
        }

        fun recordRejected(url: String) {
            rejected++
            rejectedUrls += url
        }
    }

    private data class ProposalImportSummary(
        var discovered: Int = 0,
        var submitted: Int = 0,
        var skippedExisting: Int = 0,
        var failed: Int = 0,
        private val workerLogs: MutableList<String> = mutableListOf(),
    ) {
        fun recordWorkerLogs(logs: String) {
            logs.takeIf { it.isNotBlank() }?.let { workerLogs += it }
        }

        fun logsWith(terminalLog: String): String =
            buildString {
                workerLogs.forEachIndexed { index, logs ->
                    if (index > 0) {
                        appendLine()
                        appendLine("---")
                    }
                    appendLine(logs.trimEnd())
                }
                if (isNotEmpty()) {
                    appendLine()
                    appendLine("---")
                }
                append(terminalLog)
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
                return createArticleAttempt(candidate, creatorId, attempt)
            } catch (ex: RestClientException) {
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

    private fun retryDelayForArticleCreateFailure(ex: RestClientException, attempt: Int): Duration? =
        when (ex) {
            is RestClientResponseException -> retryDelayForArticleCreateFailure(ex, attempt)
            else -> retryDelayForTransportArticleCreateFailure(attempt)
        }

    private fun retryDelayForArticleCreateFailure(ex: RestClientResponseException, attempt: Int): Duration? {
        if (!isRetryableArticleCreateFailure(ex)) return null
        return when (attempt) {
            1 -> Duration.ofSeconds(10)
            2 -> Duration.ofSeconds(60)
            else -> null
        }
    }

    private fun retryDelayForTransportArticleCreateFailure(attempt: Int): Duration? =
        when (attempt) {
            1 -> Duration.ofSeconds(10)
            2 -> Duration.ofSeconds(60)
            else -> null
        }

    private fun isRetryableArticleCreateFailure(ex: RestClientResponseException): Boolean =
        ex.statusCode.value() == HttpStatus.UNPROCESSABLE_ENTITY.value() &&
            ex.responseBodyAsString.contains("URL enrichment failed: target returned HTTP 400")

    private fun createArticle(candidate: FacebookPostCandidate, creatorId: Long): ArticleResponse =
        createArticleAttempt(candidate, creatorId, attempt = 1)

    private fun createArticleAttempt(candidate: FacebookPostCandidate, creatorId: Long, attempt: Int): ArticleResponse {
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
                    language = candidate.language,
                    quote = null,
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

        val requestId = remoteArticleCreateRequestId(attempt)
        val startedAt = System.nanoTime()
        try {
            if (isFacebookPhotoUrl(candidate.url)) {
                logger.warn(
                    "Facebook photo import remote create request starting for {}; requestId={}; attempt={}; targetBase={}; " +
                        "targetPath='{}'; connectTimeoutMs={}; readTimeoutMs={}; candidateText={}",
                    candidate.url,
                    requestId,
                    attempt,
                    remoteTargetBaseDiagnostic(),
                    properties.targetArticlePath,
                    targetApiConnectTimeoutMs(),
                    targetApiReadTimeoutMs(),
                    valueDiagnostic(candidate.text),
                )
            }
            val article = remoteArticleClient()
                .post()
                .uri(properties.targetArticlePath)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .header(REMOTE_CREATE_REQUEST_ID_HEADER, requestId)
                .body(
                    ArticleInput(
                        url = candidate.url,
                        language = candidate.language,
                        quote = null,
                    )
                )
                .retrieve()
                .body(ArticleResponse::class.java)
                ?: throw IllegalStateException("Remote article API did not return a created article")
            if (isFacebookUrl(candidate.url)) {
                logger.warn(
                    "Facebook import remote create response received for {}; requestId={}; attempt={}; durationMs={}; article={}",
                    candidate.url,
                    requestId,
                    attempt,
                    elapsedMs(startedAt),
                    articleResponseDiagnostic(article),
                )
            }
            return article
        } catch (ex: RestClientResponseException) {
            logRemoteArticleCreateHttpFailure(candidate, requestId, attempt, startedAt, ex)
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
        } catch (ex: RestClientException) {
            logRemoteArticleCreateTransportFailure(candidate, requestId, attempt, startedAt, ex)
            throw ex
        }
    }

    private fun logRemoteArticleCreateHttpFailure(
        candidate: FacebookPostCandidate,
        requestId: String,
        attempt: Int,
        startedAt: Long,
        ex: RestClientResponseException,
    ) {
        if (!isFacebookUrl(candidate.url)) return

        logger.warn(
            "Facebook import remote create HTTP failure for {}; requestId={}; attempt={}; durationMs={}; status={}; " +
                "targetBase={}; targetPath='{}'; responseBody={}",
            candidate.url,
            requestId,
            attempt,
            elapsedMs(startedAt),
            ex.statusCode.value(),
            remoteTargetBaseDiagnostic(),
            properties.targetArticlePath,
            valueDiagnostic(ex.responseBodyAsString),
        )
    }

    private fun logRemoteArticleCreateTransportFailure(
        candidate: FacebookPostCandidate,
        requestId: String,
        attempt: Int,
        startedAt: Long,
        ex: RestClientException,
    ) {
        if (!isFacebookUrl(candidate.url)) return

        logger.warn(
            "Facebook import remote create transport failure for {}; requestId={}; attempt={}; durationMs={}; " +
                "targetBase={}; targetPath='{}'; connectTimeoutMs={}; readTimeoutMs={}; exception={}",
            candidate.url,
            requestId,
            attempt,
            elapsedMs(startedAt),
            remoteTargetBaseDiagnostic(),
            properties.targetArticlePath,
            targetApiConnectTimeoutMs(),
            targetApiReadTimeoutMs(),
            exceptionDiagnostic(ex),
        )
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

    private fun remoteArticleCreateRequestId(attempt: Int): String =
        "facebook-import-create-${REMOTE_CREATE_REQUEST_ID_SEQUENCE.incrementAndGet()}-attempt-$attempt"

    private fun remoteTargetBaseDiagnostic(): String {
        val uri = runCatching { URI(properties.targetApiBaseUrl) }.getOrNull()
            ?: return "invalid"
        val port = uri.port.takeIf { it >= 0 }?.toString() ?: "default"
        return "scheme=${uri.scheme ?: "absent"},host=${uri.host ?: "absent"},port=$port"
    }

    private fun targetApiConnectTimeoutMs(): Long =
        properties.targetApiConnectTimeout.toMillis()

    private fun targetApiReadTimeoutMs(): Long =
        properties.targetApiReadTimeout.toMillis()

    private fun articleResponseDiagnostic(article: ArticleResponse): String =
        "id=${article.id},url='${article.url}',title=${valueDiagnostic(article.title)}," +
            "thumbnail=${valueDiagnostic(article.thumbnail)},lead=${valueDiagnostic(article.lead)}," +
            "publishedAt=${article.publishedAt}"

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun exceptionDiagnostic(ex: Throwable): String {
        val root = rootCause(ex)
        return "${ex.javaClass.simpleName}: ${ex.message.normalizedForLog()}; " +
            "rootCause=${root.javaClass.simpleName}: ${root.message.normalizedForLog()}"
    }

    private fun rootCause(ex: Throwable): Throwable =
        ex.cause?.let { rootCause(it) } ?: ex

    private fun String?.normalizedForLog(): String =
        this?.cleanText()?.takeIf { it.isNotBlank() } ?: "absent"

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
        if (isInstagramProfileUrl(uri)) return false
        if (host == "meta.ai" || host.endsWith(".meta.ai")) return false
        if (isMediaOrThumbnailUrl(url)) return false
        if (isMarkupNoiseUrl(url)) return false
        if (isKnownMarketplaceOfferUrl(uri)) return false
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return false
        return true
    }

    private fun isKnownMarketplaceOfferUrl(uri: URI): Boolean {
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val firstPathSegment = uri.path
            ?.trim('/')
            ?.substringBefore('/')
            ?.lowercase()
            ?: return false
        return host == "allegro.pl" && firstPathSegment == "oferta"
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
        if (isInstagramProfileUrl(uri)) return false
        if (host == "meta.ai" || host.endsWith(".meta.ai")) return false
        if (isMediaOrThumbnailUrl(url)) return false
        if (isMarkupNoiseUrl(url)) return false
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return false
        return true
    }

    private fun isFacebookUrl(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com")
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

    private fun isInstagramProfileUrl(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host != "instagram.com" && !host.endsWith(".instagram.com")) return false
        val segments = uri.path
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        if (segments.firstOrNull() == "_u" && segments.size == 2) return true
        return segments.size == 1
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

    private fun isFacebookCommentUrl(url: String): Boolean {
        val query = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        return query.contains("comment_id=") ||
            query.contains("reply_comment_id=")
    }

    private fun isFacebookPhotoUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host != "facebook.com" && !host.endsWith(".facebook.com")) return false
        val path = uri.path ?: ""
        return path.contains("/photo") || path.contains("/photo.php")
    }

    private fun String.withoutFragment(): String =
        substringBefore("#")

    private fun String.withoutFacebookNoiseQuery(): String {
        val uri = runCatching { URI(this) }.getOrNull() ?: return this
        val filteredQuery = uri.rawQuery
            ?.split("&")
            ?.filterNot { queryPart ->
                val name = queryPart.substringBefore("=")
                name in FACEBOOK_NESTED_POST_DEDUPE_QUERY_PARAMS
            }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }
        return runCatching {
            URI(uri.scheme, uri.authority, uri.path, filteredQuery, null).toString()
        }.getOrDefault(this)
    }

    private fun sleep(duration: Duration) {
        Thread.sleep(duration.toMillis())
    }

    private data class FacebookPostCandidate(
        val url: String,
        val text: String,
        val sourcePostUrl: String? = null,
        val language: String = "",
    ) {
        constructor(url: String, text: String) : this(url, text, null, "")
        constructor(url: String, text: String, sourcePostUrl: String?) : this(url, text, sourcePostUrl, "")
    }

    private data class CandidateApprovalEntry(
        val candidate: FacebookPostCandidate,
        val discoveryIndex: Int,
        val approval: FacebookCandidateApproval,
    )

    private data class PostUrlSelection(
        val url: String,
        val sourcePostUrl: String?,
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
        private const val FACEBOOK_IMPORT_USER_CONFIGURATION_ERROR =
            "app.facebook-import.username must point to an existing app user"
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
        private const val REMOTE_CREATE_REQUEST_ID_HEADER = "X-CoZaDzban-Import-Request-Id"
        private const val MAX_NESTED_FACEBOOK_POSTS_TO_OPEN = 2
        private val IMPORT_ARTIFACT_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val REJECTION_ARTIFACT_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)
        private val FACEBOOK_NESTED_POST_DEDUPE_QUERY_PARAMS = setOf(
            "__tn__",
            "comment_id",
            "reply_comment_id",
        )
        private val FACEBOOK_NON_PROFILE_PATH_SEGMENTS = setOf(
            "photo",
            "photo.php",
            "reel",
            "reels",
            "story.php",
            "permalink.php",
            "share",
            "shares",
        )
        private val POLISH_LANGUAGE_HOSTS = setOf(
            "donald.pl",
            "tvn24.pl",
            "gazeta.pl",
            "onet.pl",
            "wp.pl",
            "rmf24.pl",
        )
        private val ENGLISH_LANGUAGE_HOSTS = setOf(
            "bbc.com",
            "cnn.com",
            "theguardian.com",
            "nytimes.com",
            "wsj.com",
            "reuters.com",
            "apnews.com",
        )
        private val CANDIDATE_APPROVAL_ID_SEQUENCE = AtomicLong()
        private val IMPORT_ID_SEQUENCE = AtomicLong()
        private val REMOTE_CREATE_REQUEST_ID_SEQUENCE = AtomicLong()
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
        runCatching {
            val windowHandles = driver.windowHandles
            windowHandles.isNotEmpty() && driver.windowHandle in windowHandles
        }.getOrDefault(false)

    private fun discardDriver() {
        synchronized(stateLock) {
            driver = null
        }
    }

    private fun isRemoteArticleApiConfigured(): Boolean =
        properties.targetApiBaseUrl.isNotBlank() && properties.targetApiKey.isNotBlank()

    private fun remoteArticleClient(): RestClient =
        RestClient.builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                        .connectTimeout(properties.targetApiConnectTimeout)
                        .build(),
                ).apply {
                    setReadTimeout(properties.targetApiReadTimeout)
                }
            )
            .baseUrl(properties.targetApiBaseUrl)
            .build()

    private data class RemoteArticleUrlExistsResponse(
        val exists: Boolean = false,
    )

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
