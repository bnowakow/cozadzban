// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import org.openqa.selenium.Cookie
import org.openqa.selenium.WebDriver
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import java.nio.charset.StandardCharsets

class FacebookProfileArticleImporterJobTest {

    private val articleService: ArticleService = mock()
    private val appUserRepository: AppUserRepository = mock()

    @Test
    fun `startImport launches a background job and reuses the opened driver`() {
        val importer = spy(
            FacebookProfileArticleImporter(
                FacebookImportProperties(
                    username = "admin@example.com",
                    password = "secret",
                    scrolls = 0,
                    waitAfterPageOpen = Duration.ZERO,
                    waitAfterScroll = Duration.ZERO,
                    manualLoginTimeout = Duration.ofSeconds(1),
                ),
                appUserRepository,
                articleService,
            ),
        )
        val driver = mock<WebDriver>()
        val options = mock<WebDriver.Options>()

        whenever(appUserRepository.findByEmail("admin@example.com")).thenReturn(
            AppUser(1L, "admin@example.com", Role.ADMIN),
        )
        whenever(driver.manage()).thenReturn(options)
        whenever(options.getCookieNamed("c_user")).thenReturn(Cookie("c_user", "123"))
        whenever(options.getCookieNamed("xs")).thenReturn(Cookie("xs", "abc"))
        whenever(driver.currentUrl).thenReturn("https://www.facebook.com/admin.example")
        whenever(driver.windowHandles).thenReturn(setOf("main"))
        doNothing().whenever(driver).get(any())
        whenever(driver.findElements(any())).thenReturn(emptyList())
        doReturn(driver).whenever(importer).openDriver()

        importer.startImport()

        waitUntil("facebook import to finish") { !importer.isImportRunning() }

        assertDoesNotThrow { importer.startImport() }
        waitUntil("second facebook import to finish") { !importer.isImportRunning() }
        verify(importer).openDriver()
    }

    @Test
    fun `terminateImport cancels the active job without closing the browser`() {
        val importer = spy(
            FacebookProfileArticleImporter(
                FacebookImportProperties(
                    username = "admin@example.com",
                    password = "",
                    scrolls = 0,
                    waitAfterPageOpen = Duration.ZERO,
                    waitAfterScroll = Duration.ZERO,
                    manualLoginTimeout = Duration.ofSeconds(10),
                ),
                appUserRepository,
                articleService,
            ),
        )
        val driver = mock<WebDriver>()
        val options = mock<WebDriver.Options>()

        whenever(appUserRepository.findByEmail("admin@example.com")).thenReturn(
            AppUser(1L, "admin@example.com", Role.ADMIN),
        )
        whenever(driver.manage()).thenReturn(options)
        whenever(options.getCookieNamed("c_user")).thenReturn(null)
        whenever(options.getCookieNamed("xs")).thenReturn(null)
        whenever(driver.currentUrl).thenReturn("https://www.facebook.com/login")
        whenever(driver.windowHandles).thenReturn(setOf("main"))
        doNothing().whenever(driver).get(any())
        doReturn(driver).whenever(importer).openDriver()

        importer.startImport()
        waitUntil("facebook import to start") { importer.isImportRunning() }

        importer.terminateImport()
        waitUntil("facebook import to stop") { !importer.isImportRunning() }

        verify(importer).openDriver()
        verify(driver, never()).quit()
    }

    @Test
    fun `startImport rejects a second run while the first is still active`() {
        val importer = spy(
            FacebookProfileArticleImporter(
                FacebookImportProperties(
                    username = "admin@example.com",
                    password = "",
                    scrolls = 0,
                    waitAfterPageOpen = Duration.ZERO,
                    waitAfterScroll = Duration.ZERO,
                    manualLoginTimeout = Duration.ofSeconds(10),
                ),
                appUserRepository,
                articleService,
            ),
        )
        val driver = mock<WebDriver>()
        val options = mock<WebDriver.Options>()

        whenever(appUserRepository.findByEmail("admin@example.com")).thenReturn(
            AppUser(1L, "admin@example.com", Role.ADMIN),
        )
        whenever(driver.manage()).thenReturn(options)
        whenever(options.getCookieNamed("c_user")).thenReturn(null)
        whenever(options.getCookieNamed("xs")).thenReturn(null)
        whenever(driver.currentUrl).thenReturn("https://www.facebook.com/login")
        whenever(driver.windowHandles).thenReturn(setOf("main"))
        doNothing().whenever(driver).get(any())
        doReturn(driver).whenever(importer).openDriver()

        importer.startImport()
        waitUntil("facebook import to start") { importer.isImportRunning() }

        assertTrue(
            runCatching { importer.startImport() }
                .exceptionOrNull() is FacebookImportAlreadyRunningException,
        )

        importer.terminateImport()
        waitUntil("facebook import to stop") { !importer.isImportRunning() }
    }

    @Test
    fun `article creation retries only transient facebook enrichment failures`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod(
            "retryDelayForArticleCreateFailure",
            org.springframework.web.client.RestClientResponseException::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val transientFailure = HttpClientErrorException.create(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unprocessable Content",
            HttpHeaders.EMPTY,
            """{"detail":"URL enrichment failed: target returned HTTP 400 for 'https://www.facebook.com/photo/?fbid=1'"}"""
                .toByteArray(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )
        val permanentFailure = HttpClientErrorException.create(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unprocessable Content",
            HttpHeaders.EMPTY,
            """{"detail":"URL enrichment failed: target returned HTTP 404 for 'https://example.com/missing'"}"""
                .toByteArray(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )

        assertEquals(Duration.ofSeconds(10), method.invoke(importer, transientFailure, 1))
        assertEquals(Duration.ofSeconds(60), method.invoke(importer, transientFailure, 2))
        assertNull(method.invoke(importer, transientFailure, 3))
        assertNull(method.invoke(importer, permanentFailure, 1))
    }

    @Test
    fun `article creation retries transient remote transport failures`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod(
            "retryDelayForArticleCreateFailure",
            RestClientException::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val timeoutFailure = ResourceAccessException("I/O error on POST request: Request cancelled")

        assertEquals(Duration.ofSeconds(10), method.invoke(importer, timeoutFailure, 1))
        assertEquals(Duration.ofSeconds(60), method.invoke(importer, timeoutFailure, 2))
        assertNull(method.invoke(importer, timeoutFailure, 3))
    }

    @Test
    fun `import failure reason includes remote problem detail`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod("importFailureReason", Exception::class.java)
        method.isAccessible = true
        val failure = HttpClientErrorException.create(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Unprocessable Content",
            HttpHeaders.EMPTY,
            """{"detail":"URL enrichment failed: target returned HTTP 400 for 'https://www.facebook.com/photo/?fbid=1'"}"""
                .toByteArray(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8,
        )

        assertEquals(
            "remote API returned HTTP 422 - URL enrichment failed: target returned HTTP 400 for 'https://www.facebook.com/photo/?fbid=1'",
            method.invoke(importer, failure),
        )
    }

    @Test
    fun `failed url summary prints unique urls on separate lines`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod("formatFailedUrls", List::class.java)
        method.isAccessible = true

        assertEquals(
            "https://example.com/a\nhttps://example.com/b",
            method.invoke(
                importer,
                listOf(
                    "https://example.com/a",
                    "https://example.com/b",
                    "https://example.com/a",
                ),
            ),
        )
    }

    @Test
    fun `rejected artifact filename contains timestamp import id and candidate id`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod(
            "rejectedCandidateArtifactFilename",
            Instant::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true

        val filename = method.invoke(
            importer,
            Instant.parse("2026-05-15T19:42:31.123Z"),
            "facebook-import-20260515T194231Z-7",
            "facebook-import-candidate-42",
        ) as String

        assertTrue(filename.startsWith("20260515T194231123Z_"))
        assertTrue(filename.contains("facebook-import-20260515T194231Z-7"))
        assertTrue(filename.contains("facebook-import-candidate-42"))
        assertTrue(filename.endsWith("_rejected-url.json"))
        assertFalse(filename.contains(":"))
        assertFalse(filename.contains("/"))
    }

    @Test
    fun `rejected candidate writes one debug artifact`(@TempDir tempDir: Path) {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(rejectionArtifactDir = tempDir.toString()),
            appUserRepository,
            articleService,
        )
        val candidateClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate",
        )
        val constructor = candidateClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
        )
        constructor.isAccessible = true
        val candidate = constructor.newInstance(
            "https://example.com/rejected",
            "full candidate text\nwith useful diagnostics",
            "https://www.facebook.com/source/posts/123",
        )
        val entry = candidateApprovalEntry(candidate, 3, "facebook-import-candidate-42")
        val method = importer.javaClass.getDeclaredMethod(
            "writeRejectedCandidateArtifact",
            String::class.java,
            Instant::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Class.forName(
                "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$CandidateApprovalEntry",
            ),
        )
        method.isAccessible = true

        method.invoke(
            importer,
            "facebook-import-20260515T194231Z-7",
            Instant.parse("2026-05-15T19:42:31.123Z"),
            2,
            4,
            3,
            5,
            entry,
        )

        val artifacts = Files.list(tempDir).use { stream -> stream.toList() }
        assertEquals(1, artifacts.size)
        val artifact = artifacts.single()
        assertTrue(artifact.fileName.toString().contains("facebook-import-20260515T194231Z-7"))
        assertTrue(artifact.fileName.toString().contains("facebook-import-candidate-42"))
        val json = Files.readString(artifact)
        assertTrue(json.contains("\"facebookImportId\": \"facebook-import-20260515T194231Z-7\""))
        assertTrue(json.contains("\"candidateId\": \"facebook-import-candidate-42\""))
        assertTrue(json.contains("\"url\": \"https://example.com/rejected\""))
        assertTrue(json.contains("\"sourcePostUrl\": \"https://www.facebook.com/source/posts/123\""))
        assertTrue(json.contains("\"candidateText\": \"full candidate text\\nwith useful diagnostics\""))
        assertTrue(json.contains("\"reason\": \"USER_REJECTED\""))
        assertTrue(json.contains("\"discoveryPass\": 2"))
        assertTrue(json.contains("\"discoveryIndex\": 3"))
    }

    @Test
    fun `accepted candidate filtering does not write rejection artifacts`(@TempDir tempDir: Path) {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(rejectionArtifactDir = tempDir.toString()),
            appUserRepository,
            articleService,
        )
        val candidateClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate",
        )
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        val candidate = constructor.newInstance("https://example.com/a", "accepted text")
        val entries = listOf(candidateApprovalEntry(candidate, 1, "facebook-import-candidate-1"))
        val method = importer.javaClass.getDeclaredMethod(
            "approveCandidates",
            List::class.java,
            FacebookCandidateApprovalHandler::class.java,
        )
        method.isAccessible = true

        val approved = method.invoke(
            importer,
            entries,
            FacebookCandidateApprovalHandler { approvals -> approvals },
        ) as List<*>

        assertEquals(1, approved.size)
        assertEquals(0L, Files.list(tempDir).use { stream -> stream.count() })
    }

    @Test
    fun `rejected artifact write failure does not throw`(@TempDir tempDir: Path) {
        val notDirectory = tempDir.resolve("not-directory")
        Files.writeString(notDirectory, "blocks directory creation")
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(rejectionArtifactDir = notDirectory.toString()),
            appUserRepository,
            articleService,
        )
        val candidateClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate",
        )
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        val candidate = constructor.newInstance("https://example.com/rejected", "candidate text")
        val entry = candidateApprovalEntry(candidate, 1, "facebook-import-candidate-1")
        val method = importer.javaClass.getDeclaredMethod(
            "writeRejectedCandidateArtifact",
            String::class.java,
            Instant::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Class.forName(
                "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$CandidateApprovalEntry",
            ),
        )
        method.isAccessible = true

        assertDoesNotThrow {
            method.invoke(
                importer,
                "facebook-import-20260515T194231Z-7",
                Instant.parse("2026-05-15T19:42:31.123Z"),
                1,
                1,
                1,
                1,
                entry,
            )
        }
    }

    @Test
    fun `candidate approval rejects candidates before import`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(language = "pl"),
            appUserRepository,
            articleService,
        )
        val candidateClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate",
        )
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        val accepted = constructor.newInstance("https://example.com/a", "accepted text")
        val rejected = constructor.newInstance("https://example.com/b", "rejected text")
        val entries = listOf(
            candidateApprovalEntry(accepted, 1, "facebook-import-candidate-1"),
            candidateApprovalEntry(rejected, 2, "facebook-import-candidate-2"),
        )
        val method = importer.javaClass.getDeclaredMethod(
            "approveCandidates",
            List::class.java,
            FacebookCandidateApprovalHandler::class.java,
        )
        method.isAccessible = true

        val approved = method.invoke(
            importer,
            entries,
            FacebookCandidateApprovalHandler { approvals ->
                approvals.map { approval ->
                    if (approval.url == "https://example.com/b") {
                        approval.copy(decision = FacebookCandidateApprovalDecision.REJECT)
                    } else {
                        approval
                    }
                }
            },
        ) as List<*>

        assertEquals(1, approved.size)
        assertEquals("https://example.com/a", candidateUrl(approved.single()!!))
    }

    @Test
    fun `candidate approval carries changed language into approved candidate`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(language = "pl"),
            appUserRepository,
            articleService,
        )
        val candidateClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate",
        )
        val constructor = candidateClass.getDeclaredConstructor(String::class.java, String::class.java)
        constructor.isAccessible = true
        val candidate = constructor.newInstance("https://example.com/a", "accepted text")
        val entries = listOf(candidateApprovalEntry(candidate, 1, "facebook-import-candidate-1"))
        val method = importer.javaClass.getDeclaredMethod(
            "approveCandidates",
            List::class.java,
            FacebookCandidateApprovalHandler::class.java,
        )
        method.isAccessible = true

        val approved = method.invoke(
            importer,
            entries,
            FacebookCandidateApprovalHandler { approvals ->
                approvals.map { approval -> approval.copy(language = "en") }
            },
        ) as List<*>

        assertEquals("en", candidateLanguage(approved.single()!!))
    }


    @Test
    fun `candidate duplicate precheck uses article service`() {
        val importer = FacebookProfileArticleImporter(
            FacebookImportProperties(language = "pl"),
            appUserRepository,
            articleService,
        )
        val method = importer.javaClass.getDeclaredMethod("isAlreadyImportedCandidateUrl", String::class.java)
        method.isAccessible = true
        whenever(articleService.existsByUrl("https://example.com/existing")).thenReturn(true)

        assertTrue(method.invoke(importer, "https://example.com/existing") as Boolean)
    }

    @Test
    fun `candidate duplicate precheck uses remote article api when configured`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/articles") { exchange ->
            val url = queryParameters(exchange).getValue("existsUrl")
            assertEquals("https://www.facebook.com/photo/?fbid=1440551624785854&set=a.653758993465125", url)
            assertEquals("test-machine-key", exchange.requestHeaders.getFirst("X-CoZaZjeb-M2M-Key"))
            exchange.respondJson("""{"exists":true}""")
        }
        server.start()
        try {
            val importer = FacebookProfileArticleImporter(
                FacebookImportProperties(
                    language = "pl",
                    targetApiBaseUrl = "http://127.0.0.1:${server.address.port}",
                    targetApiKey = "test-machine-key",
                ),
                appUserRepository,
                articleService,
            )
            val method = importer.javaClass.getDeclaredMethod("isAlreadyImportedCandidateUrl", String::class.java)
            method.isAccessible = true

            assertTrue(
                method.invoke(
                    importer,
                    "https://www.facebook.com/photo/?fbid=1440551624785854&set=a.653758993465125",
                ) as Boolean,
            )
            verify(articleService, never()).existsByUrl(any())
        } finally {
            server.stop(0)
        }
    }

    private fun queryParameters(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery
            .orEmpty()
            .split("&")
            .filter { it.isNotBlank() }
            .associate { parameter ->
                val parts = parameter.split("=", limit = 2)
                val name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8)
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
                name to value
            }

    private fun HttpExchange.respondJson(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun candidateApprovalEntry(candidate: Any, discoveryIndex: Int, candidateId: String): Any {
        val entryClass = Class.forName(
            "pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$CandidateApprovalEntry",
        )
        val constructor = entryClass.getDeclaredConstructor(
            Class.forName("pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter\$FacebookPostCandidate"),
            Int::class.javaPrimitiveType,
            FacebookCandidateApproval::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(
            candidate,
            discoveryIndex,
            FacebookCandidateApproval(
                url = candidateUrl(candidate),
                language = "pl",
                candidateId = candidateId,
                sourcePostUrl = candidateSourcePostUrl(candidate),
            ),
        )
    }

    private fun candidateUrl(candidate: Any): String {
        val getter = candidate.javaClass.getDeclaredMethod("getUrl")
        getter.isAccessible = true
        return getter.invoke(candidate) as String
    }

    private fun candidateLanguage(candidate: Any): String {
        val getter = candidate.javaClass.getDeclaredMethod("getLanguage")
        getter.isAccessible = true
        return getter.invoke(candidate) as String
    }

    private fun candidateSourcePostUrl(candidate: Any): String? {
        val getter = candidate.javaClass.getDeclaredMethod("getSourcePostUrl")
        getter.isAccessible = true
        return getter.invoke(candidate) as String?
    }

    private fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for $label")
    }
}
