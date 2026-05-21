// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_FACEBOOK_PHOTO_EXAMPLES", matches = "true")
class LiveFacebookPhotoExamplesProbeTest {

    @Test
    fun `probe live Facebook photo examples from production logs`() {
        val service = EnrichmentService(RestClient.builder())
        val failures = mutableListOf<String>()

        EXAMPLE_URLS.forEachIndexed { index, url ->
            val startedAt = System.nanoTime()
            val result = try {
                service.enrich(url)
            } catch (ex: Exception) {
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                LOG.warn(
                    "Facebook photo live Kotlin probe exception; exampleIndex={}; url='{}'; durationMs={}; exception={}: {}",
                    index + 1,
                    url,
                    durationMs,
                    ex::class.java.simpleName,
                    ex.message,
                )
                failures += "example ${index + 1} failed with ${ex::class.java.simpleName}: ${ex.message}"
                return@forEachIndexed
            }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            val degraded = result.title == "Facebook photo" &&
                result.thumbnail == null &&
                result.lead == null &&
                result.publishedAt == null &&
                result.plainText == null

            LOG.warn(
                "Facebook photo live Kotlin probe result; exampleIndex={}; url='{}'; durationMs={}; degraded={}; " +
                    "title={}; thumbnail={}; lead={}; publishedAt={}; plainText={}",
                index + 1,
                url,
                durationMs,
                degraded,
                valueDiagnostic(result.title),
                valueDiagnostic(result.thumbnail),
                valueDiagnostic(result.lead),
                result.publishedAt,
                valueDiagnostic(result.plainText),
            )

            collectResultFailures("normal-enrich", url, degraded, result, failures)

            val curlFallbackResult = try {
                forceCurlFallback(service, url)
            } catch (ex: Exception) {
                LOG.warn(
                    "Facebook photo live Kotlin forced curl fallback exception; exampleIndex={}; url='{}'; exception={}: {}",
                    index + 1,
                    url,
                    ex::class.java.simpleName,
                    ex.message,
                )
                failures += "example ${index + 1} forced curl fallback failed with ${ex::class.java.simpleName}: ${ex.message}"
                null
            }

            if (curlFallbackResult == null) {
                failures += "example ${index + 1} forced curl fallback returned no usable HTML for $url"
            } else {
                val curlDegraded = curlFallbackResult.title == "Facebook photo" &&
                    curlFallbackResult.thumbnail == null &&
                    curlFallbackResult.lead == null &&
                    curlFallbackResult.publishedAt == null &&
                    curlFallbackResult.plainText == null
                LOG.warn(
                    "Facebook photo live Kotlin forced curl fallback result; exampleIndex={}; url='{}'; degraded={}; " +
                        "title={}; thumbnail={}; lead={}; publishedAt={}; plainText={}",
                    index + 1,
                    url,
                    curlDegraded,
                    valueDiagnostic(curlFallbackResult.title),
                    valueDiagnostic(curlFallbackResult.thumbnail),
                    valueDiagnostic(curlFallbackResult.lead),
                    curlFallbackResult.publishedAt,
                    valueDiagnostic(curlFallbackResult.plainText),
                )
                collectResultFailures("forced-curl-fallback", url, curlDegraded, curlFallbackResult, failures)
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `probe live Facebook post examples from production logs`() {
        val service = EnrichmentService(RestClient.builder())
        val failures = mutableListOf<String>()

        POST_EXAMPLE_URLS.forEachIndexed { index, example ->
            val url = example.url
            val startedAt = System.nanoTime()
            val result = try {
                service.enrich(url)
            } catch (ex: EnrichmentException) {
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                LOG.warn(
                    "Facebook post live Kotlin probe controlled rejection; exampleIndex={}; url='{}'; durationMs={}; " +
                        "exception={}: {}",
                    index + 1,
                    url,
                    durationMs,
                    ex::class.java.simpleName,
                    ex.message,
                )
                if (example.expectedResult == FacebookPostExpectedResult.CONTROLLED_REJECTION) {
                    return@forEachIndexed
                }
                failures += "post example ${index + 1} was rejected unexpectedly with ${ex::class.java.simpleName}: ${ex.message}"
                return@forEachIndexed
            } catch (ex: Exception) {
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                LOG.warn(
                    "Facebook post live Kotlin probe unexpected exception; exampleIndex={}; url='{}'; durationMs={}; " +
                        "exception={}: {}",
                    index + 1,
                    url,
                    durationMs,
                    ex::class.java.simpleName,
                    ex.message,
                )
                failures += "post example ${index + 1} failed unexpectedly with ${ex::class.java.simpleName}: ${ex.message}"
                return@forEachIndexed
            }

            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            if (example.expectedResult == FacebookPostExpectedResult.CONTROLLED_REJECTION) {
                failures += "post example ${index + 1} should be rejected before save but returned metadata for $url"
            }
            LOG.warn(
                "Facebook post live Kotlin probe result; exampleIndex={}; url='{}'; durationMs={}; " +
                    "title={}; thumbnail={}; lead={}; publishedAt={}; plainText={}",
                index + 1,
                url,
                durationMs,
                valueDiagnostic(result.title),
                valueDiagnostic(result.thumbnail),
                valueDiagnostic(result.lead),
                result.publishedAt,
                valueDiagnostic(result.plainText),
            )

            collectPostResultFailures(url, result, failures, expectThumbnail = example.expectThumbnail)

            if (example.forceNumericPermalinkFallback) {
                val forcedNumericResult = try {
                    forcePostNumericPermalinkFallback(service, url)
                } catch (ex: Exception) {
                    LOG.warn(
                        "Facebook post live Kotlin forced numeric permalink fallback exception; exampleIndex={}; " +
                            "url='{}'; exception={}: {}",
                        index + 1,
                        url,
                        ex::class.java.simpleName,
                        ex.message,
                    )
                    failures += "post example ${index + 1} forced numeric permalink fallback failed with " +
                        "${ex::class.java.simpleName}: ${ex.message}"
                    null
                }

                if (forcedNumericResult == null) {
                    failures += "post example ${index + 1} forced numeric permalink fallback returned no usable HTML for $url"
                } else {
                    LOG.warn(
                        "Facebook post live Kotlin forced numeric permalink fallback result; exampleIndex={}; url='{}'; " +
                            "title={}; thumbnail={}; lead={}; publishedAt={}; plainText={}",
                        index + 1,
                        url,
                        valueDiagnostic(forcedNumericResult.title),
                        valueDiagnostic(forcedNumericResult.thumbnail),
                        valueDiagnostic(forcedNumericResult.lead),
                        forcedNumericResult.publishedAt,
                        valueDiagnostic(forcedNumericResult.plainText),
                    )
                    collectPostResultFailures(url, forcedNumericResult, failures, expectThumbnail = example.expectThumbnail)
                }
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun forceCurlFallback(service: EnrichmentService, url: String): EnrichmentResult? {
        val fetchMethod = EnrichmentService::class.java.getDeclaredMethod(
            "fetchFacebookPhotoCurlFallback",
            String::class.java,
            String::class.java,
        )
        fetchMethod.isAccessible = true
        val html = fetchMethod.invoke(service, url, "live-test-forced-curl-fallback") as String? ?: return null

        val enrichMethod = EnrichmentService::class.java.getDeclaredMethod(
            "enrichHtml",
            String::class.java,
            String::class.java,
        )
        enrichMethod.isAccessible = true
        return enrichMethod.invoke(service, url, html) as EnrichmentResult
    }

    private fun forcePostNumericPermalinkFallback(service: EnrichmentService, url: String): EnrichmentResult? {
        val pluginMethod = EnrichmentService::class.java.getDeclaredMethod(
            "fetchFacebookPostPluginFallbackIfIncomplete",
            String::class.java,
            EnrichmentResult::class.java,
        )
        pluginMethod.isAccessible = true
        val emptyResult = EnrichmentResult(
            title = null,
            thumbnail = null,
            lead = null,
            publishedAt = null,
            plainText = null,
        )
        val pluginHtml = pluginMethod.invoke(service, url, emptyResult) as String? ?: return null
        LOG.warn(
            "Facebook post live Kotlin forced numeric permalink plugin shell; url='{}'; diagnostics={}",
            url,
            facebookPostPluginShellDiagnostics(service, pluginHtml),
        )

        val numericMethod = EnrichmentService::class.java.getDeclaredMethod(
            "fetchFacebookPostNumericPermalinkFallbackFromPluginShell",
            String::class.java,
            String::class.java,
        )
        numericMethod.isAccessible = true
        val numericHtml = numericMethod.invoke(service, url, pluginHtml) as String? ?: return null
        LOG.warn(
            "Facebook post live Kotlin forced numeric permalink HTML; url='{}'; htmlChars={}",
            url,
            numericHtml.length,
        )

        val enrichMethod = EnrichmentService::class.java.getDeclaredMethod(
            "enrichHtml",
            String::class.java,
            String::class.java,
        )
        enrichMethod.isAccessible = true
        return enrichMethod.invoke(service, url, numericHtml) as EnrichmentResult
    }

    private fun facebookPostPluginShellDiagnostics(service: EnrichmentService, html: String): String {
        val method = EnrichmentService::class.java.getDeclaredMethod(
            "facebookPostPluginShellDiagnostics",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, html) as String
    }

    private fun collectResultFailures(
        source: String,
        url: String,
        degraded: Boolean,
        result: EnrichmentResult,
        failures: MutableList<String>,
    ) {
        if (result.title == null) failures += "$source missing title for $url"
        if (degraded) failures += "$source returned degraded Facebook photo metadata for $url"
        if (result.thumbnail == null) failures += "$source missing Facebook photo thumbnail for $url"
        if (result.lead == null) failures += "$source missing Facebook photo post text lead for $url"
        if (result.publishedAt == null) failures += "$source missing Facebook photo published date for $url"
        if (result.plainText == null) failures += "$source missing Facebook photo plain text for $url"
        collectCleanFacebookTextFailures(source, url, result, failures)
    }

    private fun collectCleanFacebookTextFailures(
        source: String,
        url: String,
        result: EnrichmentResult,
        failures: MutableList<String>,
    ) {
        val fields = listOf(
            "title" to result.title,
            "lead" to result.lead,
            "plainText" to result.plainText,
        )
        val badMarkers = listOf(
            "Przesyłanie listy kontaktów",
            "Zaloguj się do Facebooka",
            "Log in to Facebook",
            "CDATA",
            "<![CDATA[",
            "]]>",
            "&lt;![CDATA[",
            "]]&gt;",
            "&amp;lt;![CDATA[",
            "]]&amp;gt;",
            "\\u003c![CDATA[",
            "]]\\u003e",
        )

        fields.forEach { (field, value) ->
            badMarkers.forEach { marker ->
                if (value.orEmpty().contains(marker, ignoreCase = true)) {
                    failures += "$source expected $field to be clean Facebook post text for $url; found marker '$marker'"
                }
            }
        }
    }

    private fun collectPostResultFailures(
        url: String,
        result: EnrichmentResult,
        failures: MutableList<String>,
        expectThumbnail: Boolean = false,
    ) {
        val usableText = listOf(result.lead, result.plainText, result.title)
            .firstOrNull { value ->
                !value.isNullOrBlank() &&
                    !isBadFacebookPostText(value)
            }

        if (usableText == null) {
            failures += "post example returned no usable Facebook post text for $url"
        }
        if (expectThumbnail && result.thumbnail == null) {
            failures += "post example returned no thumbnail for photo-bearing Facebook post $url"
        }
        collectCleanFacebookTextFailures("facebook-post-live", url, result, failures)
    }

    private fun isBadFacebookPostText(value: String): Boolean {
        val normalized = value.replace(Regex("""\s+"""), " ").trim()
        return normalized.equals("Facebook", ignoreCase = true) ||
            normalized.equals("Facebook post", ignoreCase = true) ||
            normalized.startsWith("Facebook post by ", ignoreCase = true) ||
            normalized.equals("Zobacz posty, zdjęcia i nie tylko na Facebooku.", ignoreCase = true) ||
            normalized.equals("Zobacz posty, zdjęcia i nie tylko na Facebooku", ignoreCase = true) ||
            normalized.equals("See posts, photos and more on Facebook.", ignoreCase = true) ||
            normalized.equals("See posts, photos and more on Facebook", ignoreCase = true) ||
            normalized.contains("zaloguj", ignoreCase = true) ||
            normalized.contains("zarejestruj", ignoreCase = true) ||
            normalized.contains("log in", ignoreCase = true) ||
            normalized.contains("sign up", ignoreCase = true)
    }

    private fun valueDiagnostic(value: String?): String =
        value
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.let { "present(len=${it.length},excerpt='${it.take(180)}')" }
            ?: "absent"

    companion object {
        private val LOG = LoggerFactory.getLogger(LiveFacebookPhotoExamplesProbeTest::class.java)

        private enum class FacebookPostExpectedResult {
            SUCCESS,
            CONTROLLED_REJECTION,
        }

        private data class FacebookPostExample(
            val url: String,
            val expectedResult: FacebookPostExpectedResult,
            val forceNumericPermalinkFallback: Boolean = false,
            val expectThumbnail: Boolean = false,
        )

        private val EXAMPLE_URLS = listOf(
            "https://www.facebook.com/photo/?fbid=1306021968296170&set=a.567191575512550",
            "https://www.facebook.com/photo/?fbid=1496190555209039&set=a.248625223298918",
            "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
        )

        private val POST_EXAMPLE_URLS = listOf(
            FacebookPostExample(
                "https://www.facebook.com/permalink.php?story_fbid=pfbid02cm7v6Fc2x2irAtxjbvkdh7cUQ22mKtQxadh4z7VRezhDFZfiNuC944cZiXYRnyFal&id=100068095113051",
                FacebookPostExpectedResult.SUCCESS,
                forceNumericPermalinkFallback = true,
                expectThumbnail = true,
            ),
            FacebookPostExample(
                "https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl",
                FacebookPostExpectedResult.SUCCESS,
            ),
            FacebookPostExample(
                "https://www.facebook.com/mzimu/posts/pfbid02ouRUuuRuoF5KnkqjiyyyvDGKWGqRWSWEjA7Tmf1Tw9XZZbNP8dd3YTh6LXNtgrU7l",
                FacebookPostExpectedResult.CONTROLLED_REJECTION,
            ),
        )
    }
}
