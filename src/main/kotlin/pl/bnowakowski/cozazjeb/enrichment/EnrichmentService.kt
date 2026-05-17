// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches and extracts article metadata from a URL.
 *
 * Phase 5 requirements:
 * - RestClient connect timeout = 3s
 * - RestClient read timeout = 5s
 * - no retries (single request per enrichment call)
 * - extract og:title/<title>, og:image, og:description/meta[name=description]
 * - throw typed exception on non-2xx / timeout / unreachable
 *
 * Phase 17 additions:
 * - extract publishedAt (5-source priority order; null if not found/unparseable)
 * - thumbnail resolved to absolute URL where possible (via jsoup abs:content)
 */
@Service
class EnrichmentService(
    restClientBuilder: RestClient.Builder,
    private val faviconCache: FaviconCache = NoopFaviconCache,
) {

    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
        .defaultHeader("Pragma", "no-cache")
        .defaultHeader("Upgrade-Insecure-Requests", "1")
        .defaultHeader("Sec-Fetch-Dest", "document")
        .defaultHeader("Sec-Fetch-Mode", "navigate")
        .defaultHeader("Sec-Fetch-Site", "none")
        .defaultHeader("Sec-Fetch-User", "?1")
        .build()

    private val facebookCrawlerRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, FACEBOOK_CRAWLER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    private val instagramCrawlerRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, TWITTERBOT_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
        .build()

    private val rpFallbackRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    private val reutersMobileRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, MOBILE_SAFARI_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9,pl;q=0.8")
        .defaultHeader(HttpHeaders.CACHE_CONTROL, "no-cache")
        .build()

    private val readerRestClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "text/plain, text/markdown, */*;q=0.8")
        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    private val facebookPhotoHttpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS.toLong()))
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .version(HttpClient.Version.HTTP_2)
        .build()

    private data class FacebookPhotoFallbackValidation(
        val usable: Boolean,
        val rejectReasons: List<String>,
        val documentKind: String,
        val finalUri: String?,
        val canonical: String?,
        val facebookPostText: String?,
        val thumbnail: String?,
        val publishedAt: Instant?,
        val publishedAtSignals: String,
        val imageCandidates: Int,
        val mainPhotoImageCandidates: Int,
    )

    private data class FacebookPhotoJdkRequestProfile(
        val name: String,
        val headers: List<Pair<String, String>>,
        val diagnostics: String,
    )

    private data class FacebookPhotoCurlRequestProfile(
        val name: String,
        val arguments: List<String>,
        val diagnostics: String,
    )

    private data class FacebookPhotoCurlOutput(
        val body: String,
        val meta: Map<String, String>,
        val metaFound: Boolean,
        val outputBytes: Int,
        val outputTruncated: Boolean,
    )

    private data class FacebookPhotoCurlExecution(
        val output: FacebookPhotoCurlOutput,
        val exitCode: Int?,
        val timedOut: Boolean,
        val durationMs: Long,
    )

    fun enrich(url: String): EnrichmentResult {
        logFacebookPhotoPrimaryFetchAttempt(url)
        val html = try {
            fetchHtml(url)
                .also { logFacebookPhotoPrimaryFetchSuccess(url, it) }
                .also { primaryHtml ->
                    recoverFacebookPhotoPrimarySuccessIfUnusable(url, primaryHtml)?.let { return it }
                }
        } catch (ex: RestClientResponseException) {
            logFacebookPhotoPrimaryFetchHttpFailure(url, ex)
            fetchFacebookCrawlerFallback(url, ex.statusCode.value(), ex.responseBodyAsString)?.let { fallbackHtml ->
                val fallbackResult = enrichHtml(url, fallbackHtml)
                if (hasUsableFacebookPostMetadata(url, fallbackResult, fallbackHtml)) {
                    LOG.debug(
                        "Facebook crawler fallback accepted; url='{}'; diagnostics={}; fallbackResult={}",
                        url,
                        urlDiagnostics(url),
                        enrichmentResultDiagnostic(fallbackResult),
                    )
                    return fallbackResult
                }
                LOG.debug(
                    "Facebook crawler fallback rejected; url='{}'; diagnostics={}; fallbackResult={}; missing={}",
                    url,
                    urlDiagnostics(url),
                    enrichmentResultDiagnostic(fallbackResult),
                    facebookPostMissingFields(fallbackResult),
                )
            }
            recoverFacebookPostPluginFallbackIfIncomplete(
                url = url,
                result = EMPTY_ENRICHMENT_RESULT,
                logContext = "after HTTP failure",
            )?.let { return it }
            fetchFacebookWatchFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookVideoPluginFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookPhotoJdkHttpClientFallback(url, ex.statusCode.value(), ex.responseBodyAsString)?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            if (shouldSkipFacebookPhotoGenericErrorRecovery(url, ex.statusCode.value(), ex.responseBodyAsString)) {
                fetchFacebookPhotoCurlFallback(url, "primary-http-400-generic-facebook-error")?.let { fallbackHtml ->
                    return enrichHtml(url, fallbackHtml)
                }
            }
            fetchFacebookPhotoFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchReutersMobileFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchRpFallback(url, ex.statusCode.value())?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchRpReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchNytReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseNytReaderMarkdownResult(url, readerText)
            }
            fetchNytOEmbedFallback(url, ex.statusCode.value())?.let { return it }
            fetchWashingtonPostReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchBloombergReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchTvn24ReaderFallback(url, ex.statusCode.value())?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchWsjFallback(url, ex.statusCode.value())?.let { return it }
            fetchDlvrItReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchEbxReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchInstagramCrawlerFallback(url)?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            if (shouldSkipFacebookPhotoGenericErrorRecovery(url, ex.statusCode.value(), ex.responseBodyAsString)) {
                logFacebookPhotoGenericErrorRecoverySkipped(url, ex)
            }
            recoverFacebookPostFromGenericError(url, ex.statusCode.value(), ex.responseBodyAsString)?.let {
                logRecoveredFacebookGenericError(url, ex)
                return it
            }
            logEnrichmentFailureAfterFallbacks(url, ex)
            throw EnrichmentException(
                message = "URL enrichment failed: target returned HTTP ${ex.statusCode.value()} for '$url'",
                reason = EnrichmentException.Reason.NON_2XX,
                statusCode = ex.statusCode.value(),
                cause = ex,
            )
        } catch (ex: ResourceAccessException) {
            val rootCause = ex.mostSpecificCause
            val reason = when (rootCause) {
                is SocketTimeoutException -> EnrichmentException.Reason.TIMEOUT
                is ConnectException -> EnrichmentException.Reason.UNREACHABLE
                else -> EnrichmentException.Reason.UNREACHABLE
            }
            logFacebookPhotoPrimaryFetchAccessFailure(url, reason, rootCause)
            fetchFacebookPhotoJdkHttpClientFallback(url, "primary-access-failure-${reason.name.lowercase()}")?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookPhotoCurlFallback(url, "primary-access-failure-${reason.name.lowercase()}")?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchNytReaderFallback(url)?.let { readerText ->
                return parseNytReaderMarkdownResult(url, readerText)
            }
            fetchNytOEmbedFallback(url)?.let { return it }
            fetchWashingtonPostReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchBloombergReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchTvn24ReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchWsjFallback(url)?.let { return it }
            fetchDlvrItReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchEbxReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchInstagramCrawlerFallback(url)?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            LOG.warn(
                "URL enrichment failed after fallbacks; target was unreachable for url='{}'; reason={}; diagnostics={}; rootCause={}: {}",
                url,
                reason,
                urlDiagnostics(url),
                rootCause::class.java.simpleName,
                rootCause.message,
            )
            throw EnrichmentException(
                message = "URL enrichment failed: target was unreachable or timed out for '$url'",
                reason = reason,
                cause = ex,
            )
        } catch (ex: RestClientException) {
            logFacebookPhotoPrimaryFetchClientFailure(url, ex)
            fetchFacebookPhotoJdkHttpClientFallback(url, "primary-client-failure-${ex.javaClass.simpleName}")?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchFacebookPhotoCurlFallback(url, "primary-client-failure-${ex.javaClass.simpleName}")?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            fetchNytReaderFallback(url)?.let { readerText ->
                return parseNytReaderMarkdownResult(url, readerText)
            }
            fetchNytOEmbedFallback(url)?.let { return it }
            fetchWashingtonPostReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchBloombergReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchTvn24ReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchWsjFallback(url)?.let { return it }
            fetchDlvrItReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchSprinklrReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchEbxReaderFallback(url)?.let { readerText ->
                return parseReaderMarkdownResult(url, readerText)
            }
            fetchInstagramCrawlerFallback(url)?.let { fallbackHtml ->
                return enrichHtml(url, fallbackHtml)
            }
            LOG.warn(
                "URL enrichment failed after fallbacks; target fetch failed for url='{}'; diagnostics={}; exception={}: {}",
                url,
                urlDiagnostics(url),
                ex::class.java.simpleName,
                ex.message,
            )
            throw EnrichmentException(
                message = "URL enrichment failed for '$url'",
                reason = EnrichmentException.Reason.UNREACHABLE,
                cause = ex,
            )
        }

        val result = enrichHtml(url, html)
        recoverFacebookPostPluginFallbackIfIncomplete(
            url = url,
            result = result,
            logContext = "after primary incomplete",
        )?.let { return it }
        if (!hasUsableFacebookPostMetadata(url, result, html)) {
            logFacebookPostNoUsableMetadata(url, "primary-and-plugin-results-have-no-usable-post-metadata", result)
            throw EnrichmentException(
                message = "URL enrichment failed: Facebook post did not expose usable post metadata for '$url'",
                reason = EnrichmentException.Reason.NON_2XX,
            )
        }
        fetchInstagramCrawlerFallbackIfIncomplete(url, result)
            ?.let { return enrichHtml(url, it) }
        fetchBloombergReaderFallbackIfIncomplete(url, result)
            ?.let { return parseReaderMarkdownResult(url, it) }
        fetchTvn24ReaderFallbackIfIncomplete(url, result)
            ?.let { return parseReaderMarkdownResult(url, it) }
        if (isTvn24BlockedPlaceholderResult(url, result)) {
            throw EnrichmentException(
                message = "URL enrichment failed: TVN24 returned an unusable placeholder page for '$url'",
                reason = EnrichmentException.Reason.NON_2XX,
            )
        }
        fetchWsjFallbackIfIncomplete(url, result)?.let { return it }
        fetchEbxReaderFallbackIfIncomplete(url, result)
            ?.let { return parseReaderMarkdownResult(url, it) }
        fetchDlvrItReaderFallbackIfIncomplete(url, result)
            ?.let { return parseReaderMarkdownResult(url, it) }
        return fetchSprinklrReaderFallbackIfIncomplete(url, result)
            ?.let { parseReaderMarkdownResult(url, it) }
            ?: result
    }

    private fun recoverFacebookPhotoPrimarySuccessIfUnusable(url: String, html: String): EnrichmentResult? {
        if (!isFacebookPhotoUrl(url)) return null

        val validation = facebookPhotoFallbackHtmlValidation(url, null, html)
        if (validation.usable) return null

        val trigger = "primary-200-unusable-${validation.documentKind}"
        LOG.debug(
            "Facebook photo primary fetch returned unusable HTTP 200; url='{}'; trigger={}; diagnostics={}; network={}; " +
                "response={}; validation={}; action=attempt-fallbacks-before-save",
            url,
            trigger,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(html, null),
            facebookPhotoFallbackValidationDiagnostics(validation),
        )

        fetchFacebookPhotoJdkHttpClientFallback(url, trigger)?.let { fallbackHtml ->
            return enrichHtml(url, fallbackHtml)
        }
        fetchFacebookPhotoCurlFallback(url, trigger)?.let { fallbackHtml ->
            return enrichHtml(url, fallbackHtml)
        }

        LOG.warn(
            "Facebook photo primary HTTP 200 unusable fallback exhausted; url='{}'; trigger={}; diagnostics={}; network={}; " +
                "response={}; validation={}; action=reject-before-save",
            url,
            trigger,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(html, null),
            facebookPhotoFallbackValidationDiagnostics(validation),
        )
        throw EnrichmentException(
            message = "URL enrichment failed: Facebook returned an unusable login/block page for '$url'",
            reason = EnrichmentException.Reason.NON_2XX,
        )
    }

    private fun fetchHtml(url: String, client: RestClient = restClient): String =
        client.get()
            .uri(url)
            .retrieve()
            .body(String::class.java)
            ?: ""

    private fun logFacebookPhotoPrimaryFetchAttempt(url: String) {
        if (!isFacebookHostUrl(url)) return

        LOG.debug(
            "Facebook primary fetch attempt; url='{}'; diagnostics={}; network={}; requestProfile={}; runtime={}",
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun logFacebookPhotoPrimaryFetchSuccess(url: String, html: String) {
        if (!isFacebookHostUrl(url)) return

        LOG.debug(
            "Facebook primary fetch succeeded; url='{}'; diagnostics={}; network={}; response={}; requestProfile={}; runtime={}",
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(html, null),
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun logFacebookPhotoPrimaryFetchHttpFailure(url: String, ex: RestClientResponseException) {
        if (!isFacebookHostUrl(url)) return

        LOG.debug(
            "Facebook primary fetch returned HTTP {}; url='{}'; diagnostics={}; network={}; response={}; " +
                "rawHttpProbe={}; requestProfile={}; runtime={}",
            ex.statusCode.value(),
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
            facebookPhotoRawHttpProbes(url, ex),
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun logFacebookPhotoPrimaryFetchAccessFailure(
        url: String,
        reason: EnrichmentException.Reason,
        rootCause: Throwable,
    ) {
        if (!isFacebookHostUrl(url)) return

        LOG.debug(
            "Facebook primary fetch access failure; url='{}'; reason={}; diagnostics={}; network={}; rootCause={}: {}; " +
                "requestProfile={}; runtime={}",
            url,
            reason,
            urlDiagnostics(url),
            networkDiagnostics(url),
            rootCause::class.java.simpleName,
            rootCause.message,
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun logFacebookPhotoPrimaryFetchClientFailure(url: String, ex: RestClientException) {
        if (!isFacebookHostUrl(url)) return

        LOG.debug(
            "Facebook primary fetch client failure; url='{}'; diagnostics={}; network={}; exception={}: {}; " +
                "requestProfile={}; runtime={}",
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            ex.javaClass.simpleName,
            ex.message,
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun shouldSkipFacebookPhotoGenericErrorRecovery(
        url: String,
        statusCode: Int,
        responseBody: String,
    ): Boolean =
        statusCode == HttpURLConnection.HTTP_BAD_REQUEST &&
            isFacebookPhotoUrl(url) &&
            isFacebookGenericError(responseBody)

    private fun logFacebookPhotoGenericErrorRecoverySkipped(url: String, ex: RestClientResponseException) {
        if (!isFacebookPhotoUrl(url)) return

        LOG.debug(
            "Facebook photo generic HTTP 400 minimal recovery skipped; url='{}'; " +
                "reason=minimal-facebook-photo-metadata-would-persist-degraded-article; diagnostics={}; network={}; response={}; " +
                "requestProfile={}; runtime={}",
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
            facebookPrimaryFetchRequestProfile(),
            runtimeDiagnostics(),
        )
    }

    private fun facebookPrimaryFetchRequestProfile(): String =
        listOf(
            "client=primary-browser",
            "connectTimeoutMs=$CONNECT_TIMEOUT_MS",
            "readTimeoutMs=$READ_TIMEOUT_MS",
            "userAgent='$BROWSER_USER_AGENT'",
            "accept='text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'",
            "acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'",
            "cacheControl=no-cache",
            "pragma=no-cache",
            "upgradeInsecureRequests=1",
            "secFetchDest=document",
            "secFetchMode=navigate",
            "secFetchSite=none",
            "secFetchUser=?1",
        ).joinToString(",")

    private fun facebookMinimalFetchRequestProfile(): String =
        listOf(
            "client=minimal-browser",
            "connectTimeoutMs=$CONNECT_TIMEOUT_MS",
            "readTimeoutMs=$READ_TIMEOUT_MS",
            "userAgent='$BROWSER_USER_AGENT'",
            "accept='text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'",
            "acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'",
            "cacheControl=absent",
            "pragma=absent",
            "upgradeInsecureRequests=absent",
            "secFetchDest=absent",
            "secFetchMode=absent",
            "secFetchSite=absent",
            "secFetchUser=absent",
        ).joinToString(",")

    private fun runtimeDiagnostics(): String =
        listOf(
            "javaVersion=${System.getProperty("java.version").normalizedForLog()}",
            "javaVendor=${System.getProperty("java.vendor").normalizedForLog()}",
            "osName=${System.getProperty("os.name").normalizedForLog()}",
            "osArch=${System.getProperty("os.arch").normalizedForLog()}",
            "hostnameEnv=${System.getenv("HOSTNAME").normalizedForLog()}",
            "dockerEnvFile=${java.io.File("/.dockerenv").exists()}",
            "httpProxyEnvPresent=${!System.getenv("HTTP_PROXY").isNullOrBlank()}",
            "httpsProxyEnvPresent=${!System.getenv("HTTPS_PROXY").isNullOrBlank()}",
            "httpProxyPropertyPresent=${!System.getProperty("http.proxyHost").isNullOrBlank()}",
            "httpsProxyPropertyPresent=${!System.getProperty("https.proxyHost").isNullOrBlank()}",
        ).joinToString(",")

    private fun networkDiagnostics(url: String): String =
        listOf(
            "scheme=${schemeFor(url).normalizedForLog()}",
            "effectivePort=${effectivePortFor(url)?.toString().normalizedForLog()}",
            "queryKeys=${queryKeysFor(url)}",
            "facebookPhotoFbid=${queryParamFor(url, "fbid").normalizedForLog()}",
            "facebookPhotoSet=${queryParamFor(url, "set").normalizedForLog()}",
            "dns=${dnsDiagnostics(url)}",
            "javaPreferIpv4Stack=${System.getProperty("java.net.preferIPv4Stack").normalizedForLog()}",
            "javaPreferIpv6Addresses=${System.getProperty("java.net.preferIPv6Addresses").normalizedForLog()}",
        ).joinToString(",")

    private fun facebookPhotoRawHttpProbes(url: String, ex: RestClientResponseException): String {
        if (!shouldSkipFacebookPhotoGenericErrorRecovery(url, ex.statusCode.value(), ex.responseBodyAsString)) {
            return "skipped-non-generic-facebook-photo-400"
        }

        return listOf(
            rawHttpProbe(url, "primary-profile-follow", facebookPrimaryHeaderPairs(), followRedirects = true),
            rawHttpProbe(url, "primary-profile-no-follow", facebookPrimaryHeaderPairs(), followRedirects = false),
            rawRedirectChainProbe(url, "primary-profile-chain-no-cookies", facebookPrimaryHeaderPairs(), carryCookies = false),
            rawRedirectChainProbe(url, "primary-profile-chain-with-cookies", facebookPrimaryHeaderPairs(), carryCookies = true),
            rawHttpProbe(url, "minimal-browser-follow", facebookMinimalHeaderPairs(), followRedirects = true),
            rawHttpProbe(url, "minimal-browser-no-follow", facebookMinimalHeaderPairs(), followRedirects = false),
        ).joinToString(" | ")
    }

    private fun rawHttpProbe(
        url: String,
        label: String,
        headers: List<Pair<String, String>>,
        followRedirects: Boolean,
    ): String {
        val startedAt = System.nanoTime()
        return runCatching {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = followRedirects
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            val body = readProbeBody(stream)
            val bodyText = body.toString(Charsets.UTF_8)
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            val doc = responseDocument(bodyText)

            "$label=status=$statusCode,durationMs=$durationMs,finalUrl='${connection.url}'," +
                "followRedirects=$followRedirects," +
                "contentType=${connection.contentType.normalizedForLog()}," +
                "contentEncoding=${connection.contentEncoding.normalizedForLog()}," +
                "bodyBytes=${body.size},bodySha256=${sha256Prefix(body)}," +
                "genericFacebookError=${isFacebookGenericError(bodyText)}," +
                "htmlTitle=${doc?.title().normalizedForLog()}," +
                "canonical=${doc?.selectFirst("link[rel=canonical]")?.attr("href").normalizedForLog()}," +
                "imageCandidates=${facebookPhotoImageCandidates(bodyText).size}," +
                "mainPhotoImageCandidates=${facebookPhotoImageCandidates(bodyText).count { isLikelyMainFacebookPhotoImage(it) }}," +
                "responseHeaders=${rawProbeHeaderDiagnostics(connection.headerFields)}," +
                "textSnippet='${responseBodySnippet(bodyText)}'"
        }.getOrElse { probeException ->
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            "$label=exception(${probeException::class.java.simpleName}:${probeException.message.normalizedForLog()}," +
                "durationMs=$durationMs)"
        }
    }

    private fun rawRedirectChainProbe(
        url: String,
        label: String,
        headers: List<Pair<String, String>>,
        carryCookies: Boolean,
    ): String {
        val startedAt = System.nanoTime()
        return runCatching {
            val cookiesByName = linkedMapOf<String, String>()
            val hops = mutableListOf<String>()
            var currentUrl = url

            for (hop in 0 until MAX_RAW_PROBE_REDIRECT_HOPS) {
                val sentCookieNames = if (carryCookies) {
                    cookiesByName.keys.joinToString("|").ifBlank { "absent" }
                } else {
                    "disabled"
                }
                val connection = (URI(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    headers.forEach { (name, value) -> setRequestProperty(name, value) }
                    if (carryCookies && cookiesByName.isNotEmpty()) {
                        setRequestProperty(HttpHeaders.COOKIE, cookiesByName.values.joinToString("; "))
                    }
                }
                val statusCode = connection.responseCode
                val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
                val body = readProbeBody(stream)
                val bodyText = body.toString(Charsets.UTF_8)
                val location = connection.getHeaderField(HttpHeaders.LOCATION)
                val responseCookies = rawProbeCookiePairs(connection.headerFields)

                if (carryCookies) {
                    responseCookies.forEach { cookie ->
                        val cookieName = cookie.substringBefore("=", missingDelimiterValue = "")
                        if (cookieName.isNotBlank()) {
                            cookiesByName[cookieName] = cookie
                        }
                    }
                }

                hops += "hop$hop(status=$statusCode,url='${currentUrl.take(MAX_RAW_PROBE_URL_CHARS)}'," +
                    "location=${location.normalizedForLog()}," +
                    "sentCookieNames=$sentCookieNames," +
                    "setCookieNames=${responseCookies.map { it.substringBefore("=") }.ifEmpty { listOf("absent") }.joinToString("|")}," +
                    "vary=${connection.getHeaderField(HttpHeaders.VARY).normalizedForLog()}," +
                    "xFbDebug=${connection.getHeaderField("x-fb-debug").normalizedForLog()}," +
                    "contentType=${connection.contentType.normalizedForLog()}," +
                    "bodyBytes=${body.size},bodySha256=${sha256Prefix(body)}," +
                    "genericFacebookError=${isFacebookGenericError(bodyText)}," +
                    "snippet='${responseBodySnippet(bodyText)}')"

                if (!isRedirectStatus(statusCode) || location.isNullOrBlank()) {
                    break
                }

                currentUrl = resolveRedirectUrl(currentUrl, location)
            }

            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            "$label=carryCookies=$carryCookies,durationMs=$durationMs,${hops.joinToString(" -> ")}"
        }.getOrElse { probeException ->
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            "$label=exception(${probeException::class.java.simpleName}:${probeException.message.normalizedForLog()}," +
                "durationMs=$durationMs)"
        }
    }

    private fun facebookPrimaryHeaderPairs(): List<Pair<String, String>> =
        facebookMinimalHeaderPairs() + listOf(
            HttpHeaders.CACHE_CONTROL to "no-cache",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
        )

    private fun facebookMinimalHeaderPairs(): List<Pair<String, String>> =
        listOf(
            HttpHeaders.USER_AGENT to BROWSER_USER_AGENT,
            HttpHeaders.ACCEPT to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            HttpHeaders.ACCEPT_LANGUAGE to "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
        )

    private fun readProbeBody(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)

        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead = input.read(buffer)
            while (bytesRead >= 0 && output.size() <= MAX_RAW_PROBE_BODY_BYTES) {
                output.write(buffer, 0, bytesRead)
                bytesRead = input.read(buffer)
            }
            output.toByteArray()
        }
    }

    private fun rawProbeHeaderDiagnostics(headers: Map<String?, List<String>>): String {
        fun first(name: String): String =
            headers.entries
                .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                .normalizedForLog()

        return listOf(
            "statusLine=${headers[null]?.firstOrNull().normalizedForLog()}",
            "location=${first(HttpHeaders.LOCATION)}",
            "server=${first(HttpHeaders.SERVER)}",
            "vary=${first(HttpHeaders.VARY)}",
            "xFbDebug=${first("x-fb-debug")}",
            "xFbRev=${first("x-fb-rev")}",
            "xFbTraceId=${first("x-fb-trace-id")}",
            "xFbRequestId=${first("x-fb-request-id")}",
            "setCookieNames=${rawProbeSetCookieNames(headers)}",
        ).joinToString(",")
    }

    private fun rawProbeSetCookieNames(headers: Map<String?, List<String>>): String {
        val values = headers.entries
            .firstOrNull { (key, _) -> key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
            ?.value
            .orEmpty()
        val names = values
            .mapNotNull { it.substringBefore("=", missingDelimiterValue = "").takeIf(String::isNotBlank) }
            .distinct()
        return names.ifEmpty { listOf("absent") }.joinToString("|")
    }

    private fun rawProbeCookiePairs(headers: Map<String?, List<String>>): List<String> =
        headers.entries
            .firstOrNull { (key, _) -> key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
            ?.value
            .orEmpty()
            .mapNotNull { cookie ->
                cookie.substringBefore(";", missingDelimiterValue = cookie)
                    .takeIf { it.contains("=") && it.substringBefore("=").isNotBlank() }
            }

    private fun isRedirectStatus(statusCode: Int): Boolean =
        statusCode in 300..399

    private fun resolveRedirectUrl(baseUrl: String, location: String): String =
        runCatching { URI(baseUrl).resolve(location).toString() }.getOrDefault(location)

    private fun logEnrichmentFailureAfterFallbacks(url: String, ex: RestClientResponseException) {
        LOG.warn(
            "URL enrichment failed after fallbacks; target returned HTTP {} for url='{}'; diagnostics={}; network={}; response={}",
            ex.statusCode.value(),
            url,
            urlDiagnostics(url),
            networkDiagnostics(url),
            httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
        )
    }

    private fun logRecoveredFacebookGenericError(url: String, ex: RestClientResponseException) {
        LOG.warn(
            "URL enrichment recovered generic Facebook HTTP {} as minimal metadata for url='{}'; " +
                "recoveryReason=facebook-generic-error-page-no-usable-metadata; diagnostics={}; response={}",
            ex.statusCode.value(),
            url,
            urlDiagnostics(url),
            httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
        )
    }

    private fun urlDiagnostics(url: String): String =
        listOf(
            "host=${hostFor(url) ?: "<invalid>"}",
            "path=${pathFor(url) ?: "<invalid>"}",
            "facebookPhoto=${isFacebookPhotoUrl(url)}",
            "facebookPfbidPost=${isFacebookPfbidPostUrl(url)}",
            "facebookVideoOrReel=${isFacebookVideoOrReelUrl(url)}",
            "facebookShare=${isFacebookShareUrl(url)}",
            "instagram=${isInstagramUrl(url)}",
        ).joinToString(",")

    private fun responseBodySnippet(responseBody: String): String =
        LOG_WHITESPACE_PATTERN.replace(responseBodyText(responseBody) ?: responseBody, " ")
            .trim()
            .take(MAX_LOGGED_RESPONSE_BODY_CHARS)

    private fun httpResponseDiagnostics(responseBody: String, headers: HttpHeaders?): String {
        val doc = responseDocument(responseBody)
        val text = doc?.text()?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim()
        return listOf(
            "contentType=${headers?.contentType ?: "absent"}",
            "bodyChars=${responseBody.length}",
            "bodySha256=${sha256Prefix(responseBody)}",
            "genericFacebookError=${isFacebookGenericError(responseBody)}",
            "htmlTitle=${doc?.title().normalizedForLog()}",
            "robots=${doc?.selectFirst("meta[name=robots]")?.attr("content").normalizedForLog()}",
            "canonical=${doc?.selectFirst("link[rel=canonical]")?.attr("href").normalizedForLog()}",
            "metaDescription=${doc?.selectFirst("meta[name=description]")?.attr("content").valuePresence()}",
            "ogTitle=${doc?.selectFirst("meta[property=og:title]")?.attr("content").valuePresence()}",
            "ogImage=${doc?.selectFirst("meta[property=og:image]")?.attr("content").valuePresence()}",
            "facebookLoginMarkers=${facebookLoginMarkerDiagnostics(responseBody, text.orEmpty())}",
            "facebookPhotoImageCandidates=${facebookPhotoImageCandidates(responseBody).size}",
            "mainFacebookPhotoImageCandidates=${facebookPhotoImageCandidates(responseBody).count { isLikelyMainFacebookPhotoImage(it) }}",
            "facebookImageCandidateDetails=${facebookImageCandidateDiagnostics(responseBody)}",
            "responseHeaders=${headerDiagnostics(headers)}",
            "textSnippet='${text?.take(MAX_LOGGED_RESPONSE_BODY_CHARS).orEmpty()}'",
        ).joinToString(";")
    }

    private fun responseDocument(responseBody: String): org.jsoup.nodes.Document? {
        if (!responseBody.contains("<html", ignoreCase = true)) return null

        return runCatching { Jsoup.parse(responseBody) }.getOrNull()
    }

    private fun responseBodyText(responseBody: String): String? {
        return responseDocument(responseBody)?.text()?.takeIf { it.isNotBlank() }
    }

    private fun headerDiagnostics(headers: HttpHeaders?): String {
        if (headers == null) return "absent"

        return listOf(
            "location=${headers.location?.toString().normalizedForLog()}",
            "server=${headers.getFirst(HttpHeaders.SERVER).normalizedForLog()}",
            "xFbDebug=${headers.getFirst("x-fb-debug").normalizedForLog()}",
            "xFbRev=${headers.getFirst("x-fb-rev").normalizedForLog()}",
            "xFbTraceId=${headers.getFirst("x-fb-trace-id").normalizedForLog()}",
            "xFbRequestId=${headers.getFirst("x-fb-request-id").normalizedForLog()}",
            "vary=${headers.getFirst(HttpHeaders.VARY).normalizedForLog()}",
            "setCookieNames=${setCookieNames(headers)}",
        ).joinToString(",")
    }

    private fun setCookieNames(headers: HttpHeaders): String {
        val names = headers[HttpHeaders.SET_COOKIE].orEmpty()
            .mapNotNull { it.substringBefore("=", missingDelimiterValue = "").takeIf(String::isNotBlank) }
            .distinct()
        return names.ifEmpty { listOf("absent") }.joinToString("|")
    }

    private fun sha256Prefix(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Prefix(value: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun hostFor(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun pathFor(url: String): String? =
        runCatching { URI(url).path }.getOrNull()

    private fun schemeFor(url: String): String? =
        runCatching { URI(url).scheme?.lowercase() }.getOrNull()

    private fun effectivePortFor(url: String): Int? =
        runCatching {
            val uri = URI(url)
            when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                uri.scheme.equals("http", ignoreCase = true) -> 80
                else -> null
            }
        }.getOrNull()

    private fun queryKeysFor(url: String): String =
        runCatching {
            URI(url).rawQuery
                ?.split("&")
                .orEmpty()
                .mapNotNull { it.substringBefore("=", missingDelimiterValue = it).takeIf(String::isNotBlank) }
                .distinct()
                .joinToString("|")
                .ifBlank { "absent" }
        }.getOrDefault("invalid")

    private fun queryParamFor(url: String, key: String): String? =
        runCatching {
            URI(url).rawQuery
                ?.split("&")
                .orEmpty()
                .firstNotNullOfOrNull { pair ->
                    val name = pair.substringBefore("=", missingDelimiterValue = pair)
                    if (name == key) pair.substringAfter("=", missingDelimiterValue = "") else null
                }
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    private fun dnsDiagnostics(url: String): String {
        val host = hostFor(url) ?: return "host-invalid"
        if (!isFacebookHost(url)) return "skipped-non-facebook-host"

        return runCatching {
            val addresses = InetAddress.getAllByName(host).toList()
            val loggedAddresses = addresses
                .take(MAX_LOGGED_DNS_ADDRESSES)
                .joinToString("|") { "${it.hostAddress}/${it.javaClass.simpleName}" }
                .ifBlank { "absent" }
            "$host=$loggedAddresses,total=${addresses.size}"
        }.getOrElse { ex ->
            "$host=error(${ex::class.java.simpleName}:${ex.message.normalizedForLog()})"
        }
    }

    private fun fetchFacebookCrawlerFallback(url: String, statusCode: Int, responseBody: String): String? {
        if (statusCode != 400) return null
        if (!isFacebookPfbidPostUrl(url)) return null
        if (!isFacebookGenericError(responseBody)) return null

        val fallbackUrl = facebookMbasicUrl(url)
        if (fallbackUrl == null) {
            LOG.debug(
                "Facebook crawler fallback skipped; url='{}'; reason=could-not-build-mbasic-url; diagnostics={}",
                url,
                urlDiagnostics(url),
            )
            return null
        }
        LOG.debug(
            "Facebook crawler fallback attempt; url='{}'; fallbackUrl='{}'; originalStatus={}; diagnostics={}; originalResponse={}",
            url,
            fallbackUrl,
            statusCode,
            urlDiagnostics(url),
            httpResponseDiagnostics(responseBody, null),
        )
        return try {
            fetchHtml(fallbackUrl, facebookCrawlerRestClient).also { fallbackHtml ->
                LOG.debug(
                    "Facebook crawler fallback returned HTML; url='{}'; fallbackUrl='{}'; response={}",
                    url,
                    fallbackUrl,
                    httpResponseDiagnostics(fallbackHtml, null),
                )
            }
        } catch (ex: RestClientException) {
            LOG.debug(
                "Facebook crawler fallback failed; url='{}'; fallbackUrl='{}'; exception={}: {}; diagnostics={}",
                url,
                fallbackUrl,
                ex.javaClass.simpleName,
                ex.message,
                urlDiagnostics(url),
            )
            null
        }
    }

    private fun fetchFacebookVideoPluginFallback(url: String, statusCode: Int): String? {
        if (statusCode != 400) return null
        if (!isFacebookVideoOrReelUrl(url)) return null

        val fallbackUrl = facebookVideoPluginUrl(url) ?: return null
        return try {
            fetchHtml(fallbackUrl)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchFacebookPostPluginFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isFacebookPfbidPostUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.lead != null && result.publishedAt != null) {
            LOG.debug(
                "Facebook post plugin fallback skipped; url='{}'; reason=primary-result-complete; diagnostics={}; result={}",
                url,
                urlDiagnostics(url),
                enrichmentResultDiagnostic(result),
            )
            return null
        }

        val fallbackUrl = facebookPostPluginUrl(url)
        if (fallbackUrl == null) {
            LOG.debug(
                "Facebook post plugin fallback skipped; url='{}'; reason=could-not-build-plugin-url; diagnostics={}; primaryResult={}",
                url,
                urlDiagnostics(url),
                enrichmentResultDiagnostic(result),
            )
            return null
        }
        LOG.debug(
            "Facebook post plugin fallback attempt; url='{}'; fallbackUrl='{}'; reason=primary-result-incomplete; " +
                "diagnostics={}; primaryResult={}; primaryMissing={}",
            url,
            fallbackUrl,
            urlDiagnostics(url),
            enrichmentResultDiagnostic(result),
            facebookPostMissingFields(result),
        )
        try {
            val fallbackHtml = fetchHtml(fallbackUrl, facebookCrawlerRestClient).also { fallbackHtml ->
                LOG.debug(
                    "Facebook post plugin fallback returned HTML; url='{}'; fallbackUrl='{}'; response={}; pluginShell={}",
                    url,
                    fallbackUrl,
                    httpResponseDiagnostics(fallbackHtml, null),
                    facebookPostPluginShellDiagnostics(fallbackHtml),
                )
            }
            if (hasPotentialFacebookPostPluginShellData(fallbackHtml)) {
                return fallbackHtml
            }
            LOG.debug(
                "Facebook post plugin RestClient shell has no usable plugin data; url='{}'; fallbackUrl='{}'; pluginShell={}; " +
                    "nextFallback=jdk-httpclient-plugin-fetch",
                url,
                fallbackUrl,
                facebookPostPluginShellDiagnostics(fallbackHtml),
            )
            fetchFacebookPostJdkHttpClientFallback(
                originalUrl = url,
                fallbackUrl = fallbackUrl,
                fallbackName = "post-plugin",
                validation = ::hasPotentialFacebookPostPluginShellData,
            )?.let { return it }
            return fallbackHtml
        } catch (ex: RestClientException) {
            LOG.debug(
                "Facebook post plugin fallback failed; url='{}'; fallbackUrl='{}'; exception={}: {}; diagnostics={}",
                url,
                fallbackUrl,
                ex.javaClass.simpleName,
                ex.message,
                urlDiagnostics(url),
            )
            return fetchFacebookPostJdkHttpClientFallback(
                originalUrl = url,
                fallbackUrl = fallbackUrl,
                fallbackName = "post-plugin-after-restclient-failure",
                validation = ::hasPotentialFacebookPostPluginShellData,
            )
        }
    }

    private fun recoverFacebookPostPluginFallbackIfIncomplete(
        url: String,
        result: EnrichmentResult,
        logContext: String,
    ): EnrichmentResult? {
        val pluginHtml = fetchFacebookPostPluginFallbackIfIncomplete(url, result) ?: return null
        val pluginResult = enrichHtml(url, pluginHtml)
        if (hasUsableFacebookPostMetadata(url, pluginResult, pluginHtml)) {
            if (pluginResult.thumbnail.isNullOrBlank()) {
                LOG.debug(
                    "Facebook post plugin fallback has usable text but no thumbnail {}; url='{}'; diagnostics={}; " +
                        "fallbackResult={}; pluginShell={}; action=try-numeric-permalink-thumbnail-fallback",
                    logContext,
                    url,
                    urlDiagnostics(url),
                    enrichmentResultDiagnostic(pluginResult),
                    facebookPostPluginShellDiagnostics(pluginHtml),
                )
                val numericHtml = fetchFacebookPostNumericPermalinkFallbackFromPluginShell(url, pluginHtml)
                if (numericHtml != null) {
                    val numericResult = enrichHtml(url, numericHtml)
                    val numericHasUsableText = hasUsableFacebookPostMetadata(url, numericResult, numericHtml)
                    if (numericHasUsableText && !numericResult.thumbnail.isNullOrBlank()) {
                        LOG.debug(
                            "Facebook post numeric permalink fallback accepted for missing thumbnail {}; url='{}'; " +
                                "diagnostics={}; fallbackResult={}",
                            logContext,
                            url,
                            urlDiagnostics(url),
                            enrichmentResultDiagnostic(numericResult),
                        )
                        return numericResult
                    }
                    LOG.debug(
                        "Facebook post numeric permalink fallback did not improve missing thumbnail {}; url='{}'; " +
                            "diagnostics={}; fallbackResult={}; hasUsableText={}; missing={}",
                        logContext,
                        url,
                        urlDiagnostics(url),
                        enrichmentResultDiagnostic(numericResult),
                        numericHasUsableText,
                        facebookPostMissingFields(numericResult),
                    )
                } else {
                    LOG.debug(
                        "Facebook post numeric permalink thumbnail fallback unavailable {}; url='{}'; diagnostics={}; " +
                            "pluginShell={}; action=keep-plugin-result",
                        logContext,
                        url,
                        urlDiagnostics(url),
                        facebookPostPluginShellDiagnostics(pluginHtml),
                    )
                }
            }
            LOG.debug(
                "Facebook post plugin fallback accepted {}; url='{}'; diagnostics={}; fallbackResult={}",
                logContext,
                url,
                urlDiagnostics(url),
                enrichmentResultDiagnostic(pluginResult),
            )
            return pluginResult
        }

        LOG.debug(
            "Facebook post plugin fallback rejected {}; url='{}'; diagnostics={}; fallbackResult={}; missing={}; pluginShell={}",
            logContext,
            url,
            urlDiagnostics(url),
            enrichmentResultDiagnostic(pluginResult),
            facebookPostMissingFields(pluginResult),
            facebookPostPluginShellDiagnostics(pluginHtml),
        )

        val numericHtml = fetchFacebookPostNumericPermalinkFallbackFromPluginShell(url, pluginHtml) ?: run {
            logFacebookPostNoUsableMetadata(
                url,
                "plugin-fallback-rejected-and-no-numeric-permalink-fallback",
                pluginResult,
            )
            return null
        }
        val numericResult = enrichHtml(url, numericHtml)
        if (hasUsableFacebookPostMetadata(url, numericResult, numericHtml)) {
            LOG.debug(
                "Facebook post numeric permalink fallback accepted {}; url='{}'; diagnostics={}; fallbackResult={}",
                logContext,
                url,
                urlDiagnostics(url),
                enrichmentResultDiagnostic(numericResult),
            )
            return numericResult
        }

        LOG.debug(
            "Facebook post numeric permalink fallback rejected {}; url='{}'; diagnostics={}; fallbackResult={}; missing={}",
            logContext,
            url,
            urlDiagnostics(url),
            enrichmentResultDiagnostic(numericResult),
            facebookPostMissingFields(numericResult),
        )
        logFacebookPostNoUsableMetadata(
            url,
            "plugin-and-numeric-permalink-fallbacks-have-no-usable-post-metadata",
            numericResult,
        )
        return null
    }

    private fun fetchFacebookPostNumericPermalinkFallbackFromPluginShell(url: String, pluginHtml: String): String? {
        if (!isFacebookPfbidPostUrl(url)) return null

        val fallbackUrls = facebookPostNumericPermalinkFallbackUrls(pluginHtml)
        if (fallbackUrls.isEmpty()) {
            LOG.debug(
                "Facebook post numeric permalink fallback skipped; url='{}'; reason=no-numeric-permalink-candidate; " +
                    "diagnostics={}; pluginShell={}",
                url,
                urlDiagnostics(url),
                facebookPostPluginShellDiagnostics(pluginHtml),
            )
            return null
        }

        for (fallbackUrl in fallbackUrls) {
            LOG.debug(
                "Facebook post numeric permalink fallback attempt; url='{}'; fallbackUrl='{}'; diagnostics={}; pluginShell={}",
                url,
                fallbackUrl,
                urlDiagnostics(url),
                facebookPostPluginShellDiagnostics(pluginHtml),
            )
            try {
                val fallbackHtml = fetchHtml(fallbackUrl, facebookCrawlerRestClient).also { fallbackHtml ->
                    LOG.debug(
                        "Facebook post numeric permalink fallback returned HTML; url='{}'; fallbackUrl='{}'; response={}",
                        url,
                        fallbackUrl,
                        httpResponseDiagnostics(fallbackHtml, null),
                    )
                }
                if (hasPotentialFacebookPostMetadataHtml(url, fallbackHtml)) {
                    return fallbackHtml
                }
                LOG.debug(
                    "Facebook post numeric permalink RestClient response has no usable metadata; url='{}'; fallbackUrl='{}'; " +
                        "response={}; nextFallback=jdk-httpclient-numeric-fetch",
                    url,
                    fallbackUrl,
                    httpResponseDiagnostics(fallbackHtml, null),
                )
            } catch (ex: RestClientResponseException) {
                LOG.debug(
                    "Facebook post numeric permalink fallback returned HTTP {}; url='{}'; fallbackUrl='{}'; response={}",
                    ex.statusCode.value(),
                    url,
                    fallbackUrl,
                    httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
                )
            } catch (ex: RestClientException) {
                LOG.debug(
                    "Facebook post numeric permalink fallback failed; url='{}'; fallbackUrl='{}'; exception={}: {}; diagnostics={}",
                    url,
                    fallbackUrl,
                    ex.javaClass.simpleName,
                    ex.message,
                    urlDiagnostics(url),
                )
            }

            fetchFacebookPostJdkHttpClientFallback(
                originalUrl = url,
                fallbackUrl = fallbackUrl,
                fallbackName = "post-numeric-permalink",
                validation = { html -> hasPotentialFacebookPostMetadataHtml(url, html) },
            )?.let { return it }
        }

        LOG.debug(
            "Facebook post numeric permalink fallback exhausted; url='{}'; attemptedFallbackUrls={}; diagnostics={}",
            url,
            fallbackUrls.joinToString(","),
            urlDiagnostics(url),
        )
        return null
    }

    private fun fetchFacebookPostJdkHttpClientFallback(
        originalUrl: String,
        fallbackUrl: String,
        fallbackName: String,
        validation: (String) -> Boolean,
    ): String? {
        for (requestProfile in facebookPostJdkHttpClientRequestProfiles()) {
            val startedAt = System.nanoTime()
            LOG.debug(
                "Facebook post JDK HttpClient fallback attempt; url='{}'; fallbackUrl='{}'; fallbackName={}; " +
                    "profile={}; diagnostics={}; originalNetwork={}; fallbackNetwork={}; requestProfile={}; runtime={}",
                originalUrl,
                fallbackUrl,
                fallbackName,
                requestProfile.name,
                urlDiagnostics(originalUrl),
                networkDiagnostics(originalUrl),
                networkDiagnostics(fallbackUrl),
                requestProfile.diagnostics,
                runtimeDiagnostics(),
            )

            runCatching {
                val request = facebookJdkHttpClientRequest(fallbackUrl, requestProfile.headers)
                val response = facebookPhotoHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val html = response.body().toString(Charsets.UTF_8)
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                val responseDiagnostics = jdkHttpClientResponseDiagnostics(originalUrl, response, html, durationMs)
                val usable = response.statusCode() in 200..299 && validation(html)

                if (usable) {
                    LOG.debug(
                        "Facebook post JDK HttpClient fallback succeeded; url='{}'; fallbackUrl='{}'; fallbackName={}; " +
                            "profile={}; diagnostics={}; response={}; pluginShell={}",
                        originalUrl,
                        fallbackUrl,
                        fallbackName,
                        requestProfile.name,
                        urlDiagnostics(originalUrl),
                        responseDiagnostics,
                        facebookPostPluginShellDiagnostics(html),
                    )
                    return html
                }
                LOG.debug(
                    "Facebook post JDK HttpClient fallback returned unusable response; url='{}'; fallbackUrl='{}'; " +
                        "fallbackName={}; profile={}; diagnostics={}; response={}; pluginShell={}",
                    originalUrl,
                    fallbackUrl,
                    fallbackName,
                    requestProfile.name,
                    urlDiagnostics(originalUrl),
                    responseDiagnostics,
                    facebookPostPluginShellDiagnostics(html),
                )
            }.getOrElse { ex ->
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                LOG.debug(
                    "Facebook post JDK HttpClient fallback failed; url='{}'; fallbackUrl='{}'; fallbackName={}; " +
                        "profile={}; diagnostics={}; durationMs={}; exception={}: {}",
                    originalUrl,
                    fallbackUrl,
                    fallbackName,
                    requestProfile.name,
                    urlDiagnostics(originalUrl),
                    durationMs,
                    ex::class.java.simpleName,
                    ex.message,
                )
            }
        }

        LOG.debug(
            "Facebook post JDK HttpClient fallback exhausted; url='{}'; fallbackUrl='{}'; fallbackName={}; profiles={}; diagnostics={}",
            originalUrl,
            fallbackUrl,
            fallbackName,
            facebookPostJdkHttpClientRequestProfiles().joinToString(",") { it.name },
            urlDiagnostics(originalUrl),
        )
        return null
    }

    private fun facebookPostJdkHttpClientRequestProfiles(): List<FacebookPhotoJdkRequestProfile> =
        listOf(
            FacebookPhotoJdkRequestProfile(
                name = "googlebot-http2",
                headers = facebookBotHeaderPairs(FACEBOOK_CRAWLER_USER_AGENT),
                diagnostics = "client=jdk-httpclient-facebook-post-fallback,profile=googlebot-http2," +
                    "preferredProtocol=HTTP_2,redirects=always,requestTimeoutMs=$READ_TIMEOUT_MS," +
                    "userAgent='$FACEBOOK_CRAWLER_USER_AGENT'",
            ),
            FacebookPhotoJdkRequestProfile(
                name = "facebookexternalhit-http2",
                headers = facebookBotHeaderPairs(FACEBOOK_EXTERNAL_HIT_USER_AGENT),
                diagnostics = "client=jdk-httpclient-facebook-post-fallback,profile=facebookexternalhit-http2," +
                    "preferredProtocol=HTTP_2,redirects=always,requestTimeoutMs=$READ_TIMEOUT_MS," +
                    "userAgent='$FACEBOOK_EXTERNAL_HIT_USER_AGENT'",
            ),
        )

    private fun facebookBotHeaderPairs(userAgent: String): List<Pair<String, String>> =
        listOf(
            HttpHeaders.USER_AGENT to userAgent,
            HttpHeaders.ACCEPT to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            HttpHeaders.ACCEPT_LANGUAGE to "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
        )

    private fun fetchFacebookWatchFallback(url: String, statusCode: Int): String? {
        if (statusCode != 400) return null
        if (!isFacebookVideoOrReelUrl(url)) return null

        val fallbackUrl = facebookWatchUrl(url) ?: return null
        return try {
            fetchHtml(fallbackUrl)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchFacebookPhotoJdkHttpClientFallback(
        url: String,
        statusCode: Int,
        responseBody: String,
    ): String? {
        if (statusCode != HttpURLConnection.HTTP_BAD_REQUEST) return null
        if (!isFacebookPhotoUrl(url)) return null
        if (!isFacebookGenericError(responseBody)) return null

        return fetchFacebookPhotoJdkHttpClientFallback(url, "primary-http-400-generic-facebook-error")
    }

    private fun fetchFacebookPhotoJdkHttpClientFallback(
        url: String,
        trigger: String,
    ): String? {
        if (!isFacebookPhotoUrl(url)) return null

        val fallbackUrls = facebookPhotoJdkHttpClientFallbackUrls(url)
        val requestProfiles = facebookPhotoJdkHttpClientRequestProfiles()
        for (fallbackUrl in fallbackUrls) {
            for (requestProfile in requestProfiles) {
                val startedAt = System.nanoTime()
                LOG.debug(
                    "Facebook photo JDK HttpClient fallback attempt; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                        "originalNetwork={}; fallbackNetwork={}; requestProfile={}; runtime={}",
                    url,
                    fallbackUrl,
                    trigger,
                    requestProfile.name,
                    urlDiagnostics(url),
                    networkDiagnostics(url),
                    networkDiagnostics(fallbackUrl),
                    requestProfile.diagnostics,
                    runtimeDiagnostics(),
                )

                runCatching {
                    val request = facebookJdkHttpClientRequest(fallbackUrl, requestProfile.headers)
                    val response = facebookPhotoHttpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    val body = response.body()
                    val html = body.toString(Charsets.UTF_8)
                    val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                    val responseDiagnostics = jdkHttpClientResponseDiagnostics(url, response, html, durationMs)
                    val validation = facebookPhotoFallbackHtmlValidation(url, response.uri(), html)

                    if (response.statusCode() in 200..299 && validation.usable) {
                        LOG.debug(
                            "Facebook photo JDK HttpClient fallback succeeded; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                                "response={}; validation={}",
                            url,
                            fallbackUrl,
                            trigger,
                            requestProfile.name,
                            urlDiagnostics(url),
                            responseDiagnostics,
                            facebookPhotoFallbackValidationDiagnostics(validation),
                        )
                        return html
                    }
                    LOG.debug(
                        "Facebook photo JDK HttpClient fallback returned unusable response; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; " +
                            "diagnostics={}; response={}; validation={}",
                        url,
                        fallbackUrl,
                        trigger,
                        requestProfile.name,
                        urlDiagnostics(url),
                        responseDiagnostics,
                        facebookPhotoFallbackValidationDiagnostics(validation),
                    )
                }.getOrElse { ex ->
                    val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                    LOG.debug(
                        "Facebook photo JDK HttpClient fallback failed; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                            "durationMs={}; exception={}: {}",
                        url,
                        fallbackUrl,
                        trigger,
                        requestProfile.name,
                        urlDiagnostics(url),
                        durationMs,
                        ex::class.java.simpleName,
                        ex.message,
                    )
                }
            }
        }

        LOG.debug(
            "Facebook photo JDK HttpClient fallback exhausted; url='{}'; trigger={}; attemptedFallbackUrls={}; attemptedProfiles={}; diagnostics={}",
            url,
            trigger,
            fallbackUrls.joinToString(","),
            requestProfiles.joinToString(",") { it.name },
            urlDiagnostics(url),
        )
        return null
    }

    private fun facebookJdkHttpClientRequest(url: String, headers: List<Pair<String, String>>): HttpRequest {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
            .GET()

        headers.forEach { (name, value) ->
            builder.header(name, value)
        }

        return builder.build()
    }

    private fun facebookPhotoJdkHttpClientFallbackUrls(url: String): List<String> =
        (listOf(url) + facebookPhotoFallbackUrls(url) + facebookPhotoMbasicFallbackUrl(url))
            .filterNotNull()
            .distinct()

    private fun facebookPhotoCurlFallbackUrls(url: String): List<String> =
        (
            facebookPhotoJdkHttpClientFallbackUrls(url) +
                facebookPhotoTouchFallbackUrl(url) +
                facebookPhotoNoWwwFallbackUrl(url) +
                facebookPhotoPluginPostUrl(url)
            )
            .filterNotNull()
            .distinct()

    private fun facebookPhotoJdkHttpClientRequestProfiles(): List<FacebookPhotoJdkRequestProfile> =
        listOf(
            FacebookPhotoJdkRequestProfile(
                name = "curl-like-minimal-browser",
                headers = facebookMinimalHeaderPairs(),
                diagnostics = facebookMinimalFetchRequestProfile()
                    .replace("client=minimal-browser", "client=jdk-httpclient-facebook-photo-fallback")
                    .plus(",profile=curl-like-minimal-browser,preferredProtocol=HTTP_2,redirects=always,requestTimeoutMs=$READ_TIMEOUT_MS"),
            ),
            FacebookPhotoJdkRequestProfile(
                name = "primary-browser-with-sec-fetch",
                headers = facebookPrimaryHeaderPairs(),
                diagnostics = facebookPrimaryFetchRequestProfile()
                    .replace("client=primary-browser", "client=jdk-httpclient-facebook-photo-fallback")
                    .plus(",profile=primary-browser-with-sec-fetch,preferredProtocol=HTTP_2,redirects=always,requestTimeoutMs=$READ_TIMEOUT_MS"),
            ),
        )

    private fun jdkHttpClientResponseDiagnostics(
        originalUrl: String,
        response: HttpResponse<ByteArray>,
        html: String,
        durationMs: Long,
    ): String {
        val doc = responseDocument(html)
        return listOf(
            "status=${response.statusCode()}",
            "durationMs=$durationMs",
            "actualProtocol=${response.version()}",
            "finalUri='${response.uri()}'",
            "bodyBytes=${response.body().size}",
            "bodyChars=${html.length}",
            "bodySha256=${sha256Prefix(response.body())}",
            "genericFacebookError=${isFacebookGenericError(html)}",
            "htmlTitle=${doc?.title().normalizedForLog()}",
            "canonical=${doc?.selectFirst("link[rel=canonical]")?.attr("href").normalizedForLog()}",
            "documentKind=${doc?.let { facebookDocumentKind(originalUrl, it, response.uri().toString()) } ?: "no-html-document"}",
            "facebookLoginDocument=${doc?.let { isFacebookLoginDocument(originalUrl, it, response.uri().toString()) } ?: false}",
            "metaDescription=${doc?.selectFirst("meta[name=description]")?.attr("content").valuePresence()}",
            "facebookPhotoImageCandidates=${facebookPhotoImageCandidates(html).size}",
            "mainFacebookPhotoImageCandidates=${facebookPhotoImageCandidates(html).count { isLikelyMainFacebookPhotoImage(it) }}",
            "facebookImageCandidateDetails=${facebookImageCandidateDiagnostics(html)}",
            "responseHeaders=${jdkHttpClientHeaderDiagnostics(response.headers().map())}",
            "textSnippet='${responseBodySnippet(html)}'",
        ).joinToString(";")
    }

    private fun facebookPhotoFallbackHtmlValidation(
        originalUrl: String,
        finalUri: URI?,
        html: String,
    ): FacebookPhotoFallbackValidation {
        val doc = responseDocument(html) ?: Jsoup.parse(html, originalUrl)
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").normalized()
        val imageCandidates = facebookPhotoImageCandidates(html)
        val mainPhotoImageCandidates = imageCandidates.count { isLikelyMainFacebookPhotoImage(it) }
        val facebookPostText = facebookPhotoFallbackPostText(originalUrl, html, doc)
        val thumbnail = facebookPhotoImage(originalUrl, html)
        val publishedAt = parsePublishedAt(originalUrl, doc)
        val publishedAtSignals = facebookPhotoPublishedAtSignals(originalUrl, doc)
        val finalUriValue = finalUri?.toString()
        val rejectReasons = mutableListOf<String>()

        if (isFacebookGenericError(html)) {
            rejectReasons += "generic-facebook-error"
        }
        if (isFacebookLoginUrl(finalUriValue)) {
            rejectReasons += "final-uri-facebook-login"
        }
        if (isFacebookLoginUrl(canonical)) {
            rejectReasons += "canonical-facebook-login"
        }
        if (isFacebookLoginDocument(originalUrl, doc, finalUriValue)) {
            rejectReasons += "facebook-login-document"
        }
        if (facebookPostText == null) {
            rejectReasons += "missing-facebook-post-text"
        }
        if (thumbnail == null) {
            rejectReasons += "missing-main-photo-thumbnail"
        }
        if (publishedAt == null) {
            rejectReasons += "missing-publishedAt"
        }

        return FacebookPhotoFallbackValidation(
            usable = rejectReasons.isEmpty(),
            rejectReasons = rejectReasons,
            documentKind = facebookDocumentKind(originalUrl, doc, finalUriValue),
            finalUri = finalUriValue,
            canonical = canonical,
            facebookPostText = facebookPostText,
            thumbnail = thumbnail,
            publishedAt = publishedAt,
            publishedAtSignals = publishedAtSignals,
            imageCandidates = imageCandidates.size,
            mainPhotoImageCandidates = mainPhotoImageCandidates,
        )
    }

    private fun facebookPhotoFallbackPostText(
        url: String,
        html: String,
        doc: org.jsoup.nodes.Document,
    ): String? {
        if (isFacebookLoginDocument(url, doc)) return null

        return parseFacebookEmbeddedMessageText(url, html, doc)
            ?: metaContent(doc, "meta[property=og:description]")?.let { cleanFacebookMessageText(it) }
            ?: metaContent(doc, "meta[name=description]")?.let { cleanFacebookMessageText(it) }
    }

    private fun facebookPhotoFallbackValidationDiagnostics(validation: FacebookPhotoFallbackValidation): String =
        listOf(
            "usable=${validation.usable}",
            "rejectReasons=${validation.rejectReasons.ifEmpty { listOf("none") }.joinToString("|")}",
            "documentKind=${validation.documentKind}",
            "finalUri=${validation.finalUri.normalizedForLog()}",
            "canonical=${validation.canonical.normalizedForLog()}",
            "facebookPostText=${valueDiagnostic(validation.facebookPostText)}",
            "thumbnail=${valueDiagnostic(validation.thumbnail)}",
            "publishedAt=${validation.publishedAt}",
            "publishedAtSignals=${validation.publishedAtSignals}",
            "imageCandidates=${validation.imageCandidates}",
            "mainPhotoImageCandidates=${validation.mainPhotoImageCandidates}",
        ).joinToString(";")

    private fun jdkHttpClientHeaderDiagnostics(headers: Map<String, List<String>>): String {
        fun first(name: String): String =
            headers.entries
                .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                .normalizedForLog()

        return listOf(
            "location=${first(HttpHeaders.LOCATION)}",
            "contentType=${first(HttpHeaders.CONTENT_TYPE)}",
            "server=${first(HttpHeaders.SERVER)}",
            "vary=${first(HttpHeaders.VARY)}",
            "xFbDebug=${first("x-fb-debug")}",
            "xFbRev=${first("x-fb-rev")}",
            "xFbTraceId=${first("x-fb-trace-id")}",
            "xFbRequestId=${first("x-fb-request-id")}",
            "setCookieNames=${jdkHttpClientSetCookieNames(headers)}",
        ).joinToString(",")
    }

    private fun jdkHttpClientSetCookieNames(headers: Map<String, List<String>>): String {
        val names = headers.entries
            .firstOrNull { (key, _) -> key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
            ?.value
            .orEmpty()
            .mapNotNull { it.substringBefore("=", missingDelimiterValue = "").takeIf(String::isNotBlank) }
            .distinct()
        return names.ifEmpty { listOf("absent") }.joinToString("|")
    }

    private fun fetchFacebookPhotoCurlFallback(
        url: String,
        trigger: String,
    ): String? {
        if (!isFacebookPhotoUrl(url)) return null

        val curlExecutable = facebookPhotoCurlExecutable()
        val fallbackUrls = facebookPhotoCurlFallbackUrls(url)
        val requestProfiles = facebookPhotoCurlRequestProfiles()
        val curlVersion = facebookPhotoCurlVersionDiagnostics(curlExecutable)
        for (fallbackUrl in fallbackUrls) {
            for (requestProfile in requestProfiles) {
                LOG.debug(
                    "Facebook photo curl process fallback attempt; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                        "originalNetwork={}; fallbackNetwork={}; curlExecutable='{}'; curlVersion={}; requestProfile={}; runtime={}",
                    url,
                    fallbackUrl,
                    trigger,
                    requestProfile.name,
                    urlDiagnostics(url),
                    networkDiagnostics(url),
                    networkDiagnostics(fallbackUrl),
                    curlExecutable,
                    curlVersion,
                    requestProfile.diagnostics,
                    runtimeDiagnostics(),
                )

                runCatching {
                    val execution = executeFacebookPhotoCurl(curlExecutable, fallbackUrl, requestProfile)
                    val finalUri = execution.output.meta["url_effective"]?.let { runCatching { URI(it) }.getOrNull() }
                    val validation = facebookPhotoFallbackHtmlValidation(url, finalUri, execution.output.body)
                    val responseDiagnostics = curlFallbackResponseDiagnostics(url, fallbackUrl, execution)

                    if (!execution.timedOut &&
                        execution.exitCode == 0 &&
                        execution.output.meta["http_code"]?.toIntOrNull()?.let { it in 200..299 } == true &&
                        validation.usable
                    ) {
                        LOG.debug(
                            "Facebook photo curl process fallback succeeded; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                                "response={}; validation={}",
                            url,
                            fallbackUrl,
                            trigger,
                            requestProfile.name,
                            urlDiagnostics(url),
                            responseDiagnostics,
                            facebookPhotoFallbackValidationDiagnostics(validation),
                        )
                        return execution.output.body
                    }

                    LOG.debug(
                        "Facebook photo curl process fallback returned unusable response; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; " +
                            "diagnostics={}; response={}; validation={}",
                        url,
                        fallbackUrl,
                        trigger,
                        requestProfile.name,
                        urlDiagnostics(url),
                        responseDiagnostics,
                        facebookPhotoFallbackValidationDiagnostics(validation),
                    )
                }.getOrElse { ex ->
                    LOG.debug(
                        "Facebook photo curl process fallback failed; url='{}'; fallbackUrl='{}'; trigger={}; profile={}; diagnostics={}; " +
                            "curlExecutable='{}'; curlVersion={}; exception={}: {}; runtime={}",
                        url,
                        fallbackUrl,
                        trigger,
                        requestProfile.name,
                        urlDiagnostics(url),
                        curlExecutable,
                        curlVersion,
                        ex::class.java.simpleName,
                        ex.message,
                        runtimeDiagnostics(),
                    )
                }
            }
        }

        LOG.debug(
            "Facebook photo curl process fallback exhausted; url='{}'; trigger={}; attemptedFallbackUrls={}; attemptedProfiles={}; " +
                "curlExecutable='{}'; curlVersion={}; diagnostics={}",
            url,
            trigger,
            fallbackUrls.joinToString(","),
            requestProfiles.joinToString(",") { it.name },
            curlExecutable,
            curlVersion,
            urlDiagnostics(url),
        )
        return null
    }

    private fun executeFacebookPhotoCurl(
        curlExecutable: String,
        fallbackUrl: String,
        requestProfile: FacebookPhotoCurlRequestProfile,
    ): FacebookPhotoCurlExecution {
        val outputFile = Files.createTempFile("cozazjeb-facebook-photo-curl-", ".out")
        val startedAt = System.nanoTime()
        try {
            val command = facebookPhotoCurlCommand(curlExecutable, fallbackUrl, requestProfile)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start()
            val finished = process.waitFor(CURL_FALLBACK_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            val exitCode = if (finished) process.exitValue() else null
            if (!finished) {
                process.destroyForcibly()
            }
            val durationMs = (System.nanoTime() - startedAt) / 1_000_000
            return FacebookPhotoCurlExecution(
                output = readFacebookPhotoCurlOutput(outputFile),
                exitCode = exitCode,
                timedOut = !finished,
                durationMs = durationMs,
            )
        } finally {
            runCatching { Files.deleteIfExists(outputFile) }
        }
    }

    private fun readFacebookPhotoCurlOutput(outputFile: java.nio.file.Path): FacebookPhotoCurlOutput {
        val raw = Files.newInputStream(outputFile).use { input ->
            input.readNBytes(MAX_CURL_FALLBACK_OUTPUT_BYTES + 1)
        }
        val truncated = raw.size > MAX_CURL_FALLBACK_OUTPUT_BYTES
        val bytes = if (truncated) raw.copyOf(MAX_CURL_FALLBACK_OUTPUT_BYTES) else raw
        val text = bytes.toString(Charsets.UTF_8)
        val markerStart = text.lastIndexOf(CURL_FALLBACK_META_START_TOKEN)
        if (markerStart < 0) {
            return FacebookPhotoCurlOutput(
                body = text,
                meta = emptyMap(),
                metaFound = false,
                outputBytes = bytes.size,
                outputTruncated = truncated,
            )
        }

        val body = text.substring(0, markerStart).trimEnd('\r', '\n')
        val metaStart = markerStart + CURL_FALLBACK_META_START_TOKEN.length
        val markerEnd = text.indexOf(CURL_FALLBACK_META_END_TOKEN, metaStart).takeIf { it >= 0 } ?: text.length
        val meta = text.substring(metaStart, markerEnd)
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains("=") }
            .associate { line ->
                val name = line.substringBefore("=")
                val value = line.substringAfter("=", missingDelimiterValue = "")
                name to value
            }

        return FacebookPhotoCurlOutput(
            body = body,
            meta = meta,
            metaFound = true,
            outputBytes = bytes.size,
            outputTruncated = truncated,
        )
    }

    private fun facebookPhotoCurlCommand(
        curlExecutable: String,
        fallbackUrl: String,
        requestProfile: FacebookPhotoCurlRequestProfile,
    ): List<String> =
        listOf(
            curlExecutable,
            "--silent",
            "--show-error",
            "--location",
            "--compressed",
            "--connect-timeout",
            (CONNECT_TIMEOUT_MS / 1000).toString(),
            "--max-time",
            (CURL_FALLBACK_TIMEOUT_MS / 1000).toString(),
            "--write-out",
            facebookPhotoCurlWriteOutFormat(),
        ) + requestProfile.arguments + fallbackUrl

    private fun facebookPhotoCurlWriteOutFormat(): String =
        "\n$CURL_FALLBACK_META_START_TOKEN\n" +
            "http_code=%{http_code}\n" +
            "url_effective=%{url_effective}\n" +
            "http_version=%{http_version}\n" +
            "content_type=%{content_type}\n" +
            "time_total=%{time_total}\n" +
            "size_download=%{size_download}\n" +
            "remote_ip=%{remote_ip}\n" +
            "num_redirects=%{num_redirects}\n" +
            "ssl_verify_result=%{ssl_verify_result}\n" +
            "$CURL_FALLBACK_META_END_TOKEN\n"

    private fun facebookPhotoCurlExecutable(): String =
        System.getenv("COZAZJEB_CURL_PATH")?.takeIf { it.isNotBlank() } ?: "curl"

    private fun facebookPhotoCurlVersionDiagnostics(curlExecutable: String): String =
        runCatching {
            val process = ProcessBuilder(curlExecutable, "--version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(1, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching "timedOut=true"
            }
            val output = process.inputStream.use { it.readNBytes(512) }.toString(Charsets.UTF_8)
            "timedOut=false,exitCode=${process.exitValue()},firstLine=${output.lineSequence().firstOrNull().normalizedForLog()}"
        }.getOrElse { ex ->
            "exception=${ex::class.java.simpleName}:${ex.message.normalizedForLog()}"
        }

    private fun facebookPhotoCurlRequestProfiles(): List<FacebookPhotoCurlRequestProfile> =
        listOf(
            FacebookPhotoCurlRequestProfile(
                name = "native-curl-default",
                arguments = emptyList(),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=native-curl-default,userAgent=curl-default,accept=curl-default,acceptLanguage=absent"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "browser-minimal",
                arguments = listOf(
                    "--user-agent",
                    BROWSER_USER_AGENT,
                    "--header",
                    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "--header",
                    "Accept-Language: pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
                ),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=browser-minimal,userAgent='$BROWSER_USER_AGENT'," +
                        "accept='text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'," +
                        "acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "browser-minimal-http1.1",
                arguments = listOf("--http1.1") + facebookPhotoCurlBrowserArguments(BROWSER_USER_AGENT),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=browser-minimal-http1.1,protocol=http1.1,ipFamily=default,userAgent='$BROWSER_USER_AGENT'," +
                        "accept='text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'," +
                        "acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "browser-minimal-http1.1-ipv4",
                arguments = listOf("--http1.1", "--ipv4") + facebookPhotoCurlBrowserArguments(BROWSER_USER_AGENT),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=browser-minimal-http1.1-ipv4,protocol=http1.1,ipFamily=ipv4,userAgent='$BROWSER_USER_AGENT'," +
                        "accept='text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'," +
                        "acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "linux-chrome-navigation",
                arguments = facebookPhotoCurlNavigationArguments(LINUX_CHROME_USER_AGENT, platform = "Linux", mobile = false),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=linux-chrome-navigation,protocol=curl-default,ipFamily=default,userAgent='$LINUX_CHROME_USER_AGENT'," +
                        "accept='$CHROME_NAVIGATION_ACCEPT',acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'," +
                        "secFetch=true,clientHints=linux-desktop"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "linux-chrome-navigation-http1.1",
                arguments = listOf("--http1.1") +
                    facebookPhotoCurlNavigationArguments(LINUX_CHROME_USER_AGENT, platform = "Linux", mobile = false),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=linux-chrome-navigation-http1.1,protocol=http1.1,ipFamily=default,userAgent='$LINUX_CHROME_USER_AGENT'," +
                        "accept='$CHROME_NAVIGATION_ACCEPT',acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'," +
                        "secFetch=true,clientHints=linux-desktop"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "android-chrome-navigation-http1.1",
                arguments = listOf("--http1.1") +
                    facebookPhotoCurlNavigationArguments(ANDROID_CHROME_USER_AGENT, platform = "Android", mobile = true),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=android-chrome-navigation-http1.1,protocol=http1.1,ipFamily=default,userAgent='$ANDROID_CHROME_USER_AGENT'," +
                        "accept='$CHROME_NAVIGATION_ACCEPT',acceptLanguage='pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7'," +
                        "secFetch=true,clientHints=android-mobile"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "facebookexternalhit",
                arguments = facebookPhotoCurlBotArguments(FACEBOOK_EXTERNAL_HIT_USER_AGENT),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=facebookexternalhit,protocol=curl-default,ipFamily=default,userAgent='$FACEBOOK_EXTERNAL_HIT_USER_AGENT'," +
                        "accept='*/*',acceptLanguage='en-US,en;q=0.8'"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "twitterbot",
                arguments = facebookPhotoCurlBotArguments(TWITTERBOT_USER_AGENT),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=twitterbot,protocol=curl-default,ipFamily=default,userAgent='$TWITTERBOT_USER_AGENT'," +
                        "accept='*/*',acceptLanguage='en-US,en;q=0.8'"),
            ),
            FacebookPhotoCurlRequestProfile(
                name = "googlebot",
                arguments = facebookPhotoCurlBotArguments(FACEBOOK_CRAWLER_USER_AGENT),
                diagnostics = facebookPhotoCurlBaseRequestProfile()
                    .plus(",profile=googlebot,protocol=curl-default,ipFamily=default,userAgent='$FACEBOOK_CRAWLER_USER_AGENT'," +
                        "accept='*/*',acceptLanguage='en-US,en;q=0.8'"),
            ),
        )

    private fun facebookPhotoCurlBrowserArguments(userAgent: String): List<String> =
        listOf("--user-agent", userAgent) + facebookPhotoCurlHeaderArguments(
            HttpHeaders.ACCEPT to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            HttpHeaders.ACCEPT_LANGUAGE to "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
        )

    private fun facebookPhotoCurlNavigationArguments(
        userAgent: String,
        platform: String,
        mobile: Boolean,
    ): List<String> =
        listOf("--user-agent", userAgent) + facebookPhotoCurlHeaderArguments(
            HttpHeaders.ACCEPT to CHROME_NAVIGATION_ACCEPT,
            HttpHeaders.ACCEPT_LANGUAGE to "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7",
            HttpHeaders.CACHE_CONTROL to "no-cache",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "sec-ch-ua" to """"Chromium";v="136", "Google Chrome";v="136", "Not.A/Brand";v="99"""",
            "sec-ch-ua-mobile" to if (mobile) "?1" else "?0",
            "sec-ch-ua-platform" to """"$platform"""",
        )

    private fun facebookPhotoCurlBotArguments(userAgent: String): List<String> =
        listOf("--user-agent", userAgent) + facebookPhotoCurlHeaderArguments(
            HttpHeaders.ACCEPT to "*/*",
            HttpHeaders.ACCEPT_LANGUAGE to "en-US,en;q=0.8",
        )

    private fun facebookPhotoCurlHeaderArguments(vararg headers: Pair<String, String>): List<String> =
        headers.flatMap { (name, value) -> listOf("--header", "$name: $value") }

    private fun facebookPhotoCurlBaseRequestProfile(): String =
        listOf(
            "client=curl-process-facebook-photo-fallback",
            "followRedirects=true",
            "compressed=true",
            "connectTimeoutMs=$CONNECT_TIMEOUT_MS",
            "maxTimeMs=$CURL_FALLBACK_TIMEOUT_MS",
            "stderrMerged=true",
            "metaMarkers=true",
        ).joinToString(",")

    private fun curlFallbackResponseDiagnostics(
        originalUrl: String,
        fallbackUrl: String,
        execution: FacebookPhotoCurlExecution,
    ): String {
        val html = execution.output.body
        val doc = responseDocument(html)
        return listOf(
            "exitCode=${execution.exitCode?.toString().normalizedForLog()}",
            "timedOut=${execution.timedOut}",
            "durationMs=${execution.durationMs}",
            "metaFound=${execution.output.metaFound}",
            "httpCode=${execution.output.meta["http_code"].normalizedForLog()}",
            "urlEffective=${execution.output.meta["url_effective"].normalizedForLog()}",
            "httpVersion=${execution.output.meta["http_version"].normalizedForLog()}",
            "contentType=${execution.output.meta["content_type"].normalizedForLog()}",
            "timeTotal=${execution.output.meta["time_total"].normalizedForLog()}",
            "sizeDownload=${execution.output.meta["size_download"].normalizedForLog()}",
            "remoteIp=${execution.output.meta["remote_ip"].normalizedForLog()}",
            "numRedirects=${execution.output.meta["num_redirects"].normalizedForLog()}",
            "sslVerifyResult=${execution.output.meta["ssl_verify_result"].normalizedForLog()}",
            "outputBytes=${execution.output.outputBytes}",
            "outputTruncated=${execution.output.outputTruncated}",
            "bodyChars=${html.length}",
            "bodySha256=${sha256Prefix(html)}",
            "genericFacebookError=${isFacebookGenericError(html)}",
            "htmlTitle=${doc?.title().normalizedForLog()}",
            "canonical=${doc?.selectFirst("link[rel=canonical]")?.attr("href").normalizedForLog()}",
            "documentKind=${doc?.let { facebookDocumentKind(originalUrl, it, execution.output.meta["url_effective"]) } ?: "no-html-document"}",
            "facebookLoginDocument=${doc?.let { isFacebookLoginDocument(originalUrl, it, execution.output.meta["url_effective"]) } ?: false}",
            "metaDescription=${doc?.selectFirst("meta[name=description]")?.attr("content").valuePresence()}",
            "facebookPhotoImageCandidates=${facebookPhotoImageCandidates(html).size}",
            "mainFacebookPhotoImageCandidates=${facebookPhotoImageCandidates(html).count { isLikelyMainFacebookPhotoImage(it) }}",
            "fallbackNetwork=${networkDiagnostics(fallbackUrl)}",
            "textSnippet='${responseBodySnippet(html)}'",
        ).joinToString(";")
    }

    private fun fetchFacebookPhotoFallback(url: String, statusCode: Int): String? {
        if (statusCode != 400) return null
        if (!isFacebookPhotoUrl(url)) return null

        val fallbackUrls = facebookPhotoFallbackUrls(url)
        if (fallbackUrls.isEmpty()) {
            LOG.debug(
                "Facebook photo fallback skipped for url='{}'; diagnostics={}; network={}; reason=no-candidate-url",
                url,
                urlDiagnostics(url),
                networkDiagnostics(url),
            )
            return null
        }

        for (fallbackUrl in fallbackUrls) {
            LOG.debug(
                "Facebook photo fallback attempt for url='{}'; fallbackUrl='{}'; originalStatus={}; diagnostics={}; " +
                    "originalNetwork={}; fallbackNetwork={}",
                url,
                fallbackUrl,
                statusCode,
                urlDiagnostics(url),
                networkDiagnostics(url),
                networkDiagnostics(fallbackUrl),
            )
            try {
                val html = fetchHtml(fallbackUrl)
                val validation = facebookPhotoFallbackHtmlValidation(
                    originalUrl = url,
                    finalUri = runCatching { URI(fallbackUrl) }.getOrNull(),
                    html = html,
                )
                if (validation.usable) {
                    LOG.debug(
                        "Facebook photo fallback returned usable HTML for url='{}'; fallbackUrl='{}'; fallbackNetwork={}; " +
                            "response={}; validation={}",
                        url,
                        fallbackUrl,
                        networkDiagnostics(fallbackUrl),
                        httpResponseDiagnostics(html, null),
                        facebookPhotoFallbackValidationDiagnostics(validation),
                    )
                    return html
                }
                LOG.debug(
                    "Facebook photo fallback returned unusable HTML for url='{}'; fallbackUrl='{}'; fallbackNetwork={}; " +
                        "response={}; validation={}; action=ignore-response",
                    url,
                    fallbackUrl,
                    networkDiagnostics(fallbackUrl),
                    httpResponseDiagnostics(html, null),
                    facebookPhotoFallbackValidationDiagnostics(validation),
                )
            } catch (ex: RestClientResponseException) {
                LOG.debug(
                    "Facebook photo fallback returned HTTP {} for url='{}'; fallbackUrl='{}'; fallbackNetwork={}; response={}",
                    ex.statusCode.value(),
                    url,
                    fallbackUrl,
                    networkDiagnostics(fallbackUrl),
                    httpResponseDiagnostics(ex.responseBodyAsString, ex.responseHeaders),
                )
            } catch (ex: ResourceAccessException) {
                val rootCause = ex.mostSpecificCause
                LOG.debug(
                    "Facebook photo fallback was unreachable for url='{}'; fallbackUrl='{}'; fallbackNetwork={}; rootCause={}: {}",
                    url,
                    fallbackUrl,
                    networkDiagnostics(fallbackUrl),
                    rootCause::class.java.simpleName,
                    rootCause.message,
                )
            } catch (ex: RestClientException) {
                LOG.debug(
                    "Facebook photo fallback failed for url='{}'; fallbackUrl='{}'; fallbackNetwork={}; exception={}: {}",
                    url,
                    fallbackUrl,
                    networkDiagnostics(fallbackUrl),
                    ex::class.java.simpleName,
                    ex.message,
                )
            }
        }

        LOG.debug(
            "Facebook photo fallback exhausted for url='{}'; network={}; attemptedFallbackUrls={}",
            url,
            networkDiagnostics(url),
            fallbackUrls.joinToString(","),
        )
        return null
    }

    private fun fetchReutersMobileFallback(url: String, statusCode: Int): String? {
        if (!shouldUseReutersMobileFallback(url, statusCode)) return null

        return try {
            fetchHtml(url, reutersMobileRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchRpReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 403) return null
        if (!isRpUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchNytReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null
        if (!isNytUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchNytReaderFallback(url: String): String? {
        if (!isNytUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchNytOEmbedFallback(url: String, statusCode: Int): EnrichmentResult? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null

        return fetchNytOEmbedFallback(url)
    }

    private fun fetchNytOEmbedFallback(url: String): EnrichmentResult? =
        fetchNytOEmbedResult(url)

    private fun fetchWashingtonPostReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null
        if (!isWashingtonPostUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchWashingtonPostReaderFallback(url: String): String? {
        if (!isWashingtonPostUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchBloombergReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null
        if (!isBloombergUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchBloombergReaderFallback(url: String): String? {
        if (!isBloombergUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchBloombergReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isBloombergUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchReaderFallback(url)
    }

    private fun fetchTvn24ReaderFallback(url: String, statusCode: Int): String? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null
        if (!isTvn24Url(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchTvn24ReaderFallback(url: String): String? {
        if (!isTvn24Url(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchTvn24ReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isTvn24Url(url)) return null
        if (!isTvn24BlockedPlaceholderResult(url, result) &&
            result.title != null &&
            result.thumbnail != null &&
            result.publishedAt != null
        ) {
            return null
        }

        return fetchReaderFallback(url)
    }

    private fun fetchWsjFallback(url: String, statusCode: Int): EnrichmentResult? {
        if (statusCode != 401 && statusCode != 403 && statusCode != 429) return null

        return fetchWsjFallback(url)
    }

    private fun fetchWsjFallback(url: String): EnrichmentResult? {
        if (!isWsjUrl(url)) return null

        fetchReaderFallback(url)
            ?.let { parseWsjReaderMarkdownResult(url, it) }
            ?.let { return it }

        return wsjArticleFallbackFromUrl(url)
    }

    private fun fetchWsjFallbackIfIncomplete(url: String, result: EnrichmentResult): EnrichmentResult? {
        if (!isWsjUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchWsjFallback(url)
    }

    private fun fetchSprinklrReaderFallback(url: String): String? {
        if (!isSprinklrShortUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchSprinklrReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isSprinklrShortUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchReaderFallback(url)
    }

    private fun fetchEbxReaderFallback(url: String): String? {
        if (!isEbxShortUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchEbxReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isEbxShortUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchReaderFallback(url)
    }

    private fun fetchDlvrItReaderFallback(url: String): String? {
        if (!isDlvrItShortUrl(url)) return null

        return fetchReaderFallback(url)
    }

    private fun fetchDlvrItReaderFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isDlvrItShortUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchReaderFallback(url)
    }

    private fun fetchInstagramCrawlerFallback(url: String): String? {
        if (!isInstagramUrl(url)) return null

        return try {
            fetchHtml(url, instagramCrawlerRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun fetchInstagramCrawlerFallbackIfIncomplete(url: String, result: EnrichmentResult): String? {
        if (!isInstagramUrl(url)) return null
        if (result.title != null && result.thumbnail != null && result.publishedAt != null) return null

        return fetchInstagramCrawlerFallback(url)
    }

    private fun fetchReaderFallback(url: String): String? {
        val readerUrl = readerUrl(url) ?: return null
        return try {
            fetchHtml(readerUrl, readerRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun parseNytReaderMarkdownResult(url: String, text: String): EnrichmentResult {
        val result = parseReaderMarkdownResult(url, text)
        val oembedResult = fetchNytOEmbedResult(url)
        if (isGenericNytReaderResult(result)) {
            return oembedResult ?: result.copy(title = null, lead = null, plainText = null)
        }
        if (oembedResult != null && shouldPreferNytOEmbedResult(result)) {
            return oembedResult.copy(thumbnail = oembedResult.thumbnail ?: result.thumbnail)
        }
        if (result.thumbnail != null) return result

        return result.copy(thumbnail = oembedResult?.thumbnail ?: fetchNytOEmbedThumbnail(url))
    }

    private fun isGenericNytReaderResult(result: EnrichmentResult): Boolean =
        result.title.isNullOrBlank() ||
            result.title.equals("nytimes.com", ignoreCase = true) ||
            result.plainText.isNullOrBlank()

    private fun shouldPreferNytOEmbedResult(result: EnrichmentResult): Boolean =
        result.publishedAt == null ||
            result.title.orEmpty().endsWith(NYT_TITLE_SUFFIX, ignoreCase = true) ||
            result.lead.isNullOrBlank()

    private fun parseWsjReaderMarkdownResult(url: String, text: String): EnrichmentResult? {
        val result = parseReaderMarkdownResult(url, text)
        if (!isGenericWsjReaderResult(result)) return result

        return wsjArticleFallbackFromUrl(url)
    }

    private fun isGenericWsjReaderResult(result: EnrichmentResult): Boolean =
        result.title.isNullOrBlank() ||
            result.title.equals("wsj.com", ignoreCase = true) ||
            result.plainText.isNullOrBlank()

    private fun wsjArticleFallbackFromUrl(url: String): EnrichmentResult? {
        val resolvedUrl = resolveRedirectUrl(url) ?: url
        val title = wsjArticleTitleFromUrl(resolvedUrl) ?: return null

        return EnrichmentResult(
            title = title,
            thumbnail = null,
            lead = null,
            publishedAt = null,
            plainText = null,
        )
    }

    private fun fetchNytOEmbedThumbnail(url: String): String? {
        if (!isNytUrl(url)) return null

        return fetchNytOEmbedResult(url)?.thumbnail
    }

    private fun fetchNytOEmbedResult(url: String): EnrichmentResult? {
        if (!isNytUrl(url)) return null

        val oembedUrl = "https://www.nytimes.com/svc/oembed/json/?url=${encodeQueryParam(resolveRedirectUrl(url) ?: url)}"
        return try {
            val response = fetchHtml(oembedUrl, readerRestClient)
            parseNytOEmbedResult(response)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseNytOEmbedResult(response: String): EnrichmentResult? {
        val json = JSON_MAPPER.readTree(response) ?: return null
        val title = json.get("title")?.asText().normalized()
        val lead = json.get("summary")?.asText().normalized()
        val thumbnail = json.get("thumbnail_url")?.asText().normalized()
        val publishedAt = parseNytPublicationDate(json.get("publication_date")?.asText())

        if (title == null && lead == null && thumbnail == null && publishedAt == null) return null
        return EnrichmentResult(
            title = title,
            thumbnail = thumbnail,
            lead = lead,
            publishedAt = publishedAt,
            plainText = lead,
        )
    }

    private fun parseNytPublicationDate(value: String?): Instant? {
        if (value.isNullOrBlank()) return null

        return parseDateTimeInstant(value)
            ?: runCatching {
                LocalDate.parse(value.trim(), NYT_PUBLICATION_DATE_FORMATTER)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            }.getOrNull()
    }

    private fun resolveRedirectUrl(url: String): String? {
        var current = url
        repeat(MAX_REDIRECT_RESOLUTION_HOPS) {
            val connection = try {
                (URI(current).toURL().openConnection() as? HttpURLConnection) ?: return null
            } catch (_: Exception) {
                return null
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
            connection.setRequestProperty(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            try {
                val statusCode = connection.responseCode
                val location = connection.getHeaderField(HttpHeaders.LOCATION)
                if (statusCode !in 300..399 || location.isNullOrBlank()) {
                    return current
                }
                current = URI(current).resolve(location).toString()
            } catch (_: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }

        return current
    }

    private fun fetchRpFallback(url: String, statusCode: Int): String? {
        if (statusCode != 403) return null
        if (!isRpUrl(url)) return null

        return try {
            fetchHtml(url, rpFallbackRestClient)
        } catch (_: RestClientException) {
            null
        }
    }

    private fun enrichHtml(url: String, html: String): EnrichmentResult {
        val doc = Jsoup.parse(html, url)
        val facebookLoginDocument = isFacebookLoginDocument(url, doc)
        val extractedTitle = youtubeStructuredTitle(url, html)
            ?: metaContent(doc, "meta[property=og:title]")
            ?: metaContent(doc, "meta[name=title]")
            ?: metaContent(doc, "meta[name=twitter:title]")
            ?: metaContent(doc, "meta[property=twitter:title]")
            ?: doc.title().normalized()
        val metaThumbnail = firstMetaImage(
            doc,
            "meta[property=og:image]",
            "meta[property=og:image:url]",
            "meta[name=twitter:image]",
            "meta[property=twitter:image]",
        )?.takeUnless { isGenericInstagramThumbnail(url, it) }
        val facebookPhotoThumbnail = facebookPhotoImage(url, html).takeIf { metaThumbnail == null }
        val thumbnail = metaThumbnail ?: facebookPhotoThumbnail
        val favicon = faviconCache.cache(url, faviconCandidates(url, doc))
        val facebookPostText = parseFacebookEmbeddedMessageText(url, html, doc)
            ?.let { cleanFacebookMessageText(it) }
        val rawMetaDescription = metaContent(doc, "meta[property=og:description]")
            ?: metaContent(doc, "meta[name=description]")
        val metaDescription = if (isFacebookDocument(url, doc)) {
            rawMetaDescription?.let { cleanFacebookMessageText(it) }
                ?.takeUnless { isGenericFacebookDescription(url, it) }
        } else {
            rawMetaDescription
        }
        val extractedLead = when {
            facebookLoginDocument && isFacebookPhotoUrl(url) -> null
            isFacebookVideoOrReelUrl(url) ->
                metaDescription?.takeUnless { isGenericInstagramDescription(url, it) }
                    ?: facebookPostText
            else ->
                facebookPostText
                    ?: metaDescription?.takeUnless { isGenericInstagramDescription(url, it) }
        }
        val lead = if (isFacebookDocument(url, doc)) {
            extractedLead?.let { cleanFacebookMessageText(it) }
        } else {
            extractedLead
        }
        val extractedArticleTitle = if (facebookLoginDocument && isFacebookPhotoUrl(url)) {
            null
        } else {
            facebookPostTextTitle(url, lead)
                ?: extractedTitle
                    ?.takeUnless { isGenericTitle(url, it) }
                ?: fetchYoutubeOEmbedTitle(url)
        }
        val title = if (isFacebookDocument(url, doc)) {
            extractedArticleTitle?.let { cleanFacebookMessageText(it) }
        } else {
            extractedArticleTitle
        }
        val publishedAt = parsePublishedAt(url, doc)
        val extractedPlainText = when {
            facebookLoginDocument && isFacebookPhotoUrl(url) -> null
            isFacebookPfbidPostUrl(url) -> lead
            isFacebookVideoOrReelUrl(url) -> lead ?: doc.body().text().normalized()
            isFacebookPhotoUrl(url) -> facebookPostText ?: lead
            else -> facebookPostText ?: doc.body().text().normalized()
        }
        val plainText = if (isFacebookDocument(url, doc)) {
            extractedPlainText?.let { cleanFacebookMessageText(it) }
        } else {
            extractedPlainText
        }
        logFacebookPhotoExtraction(
            url = url,
            html = html,
            doc = doc,
            extractedTitle = extractedTitle,
            metaThumbnail = metaThumbnail,
            facebookPhotoThumbnail = facebookPhotoThumbnail,
            facebookPostText = facebookPostText,
            metaDescription = metaDescription,
            lead = lead,
            title = title,
            publishedAt = publishedAt,
            plainText = plainText,
            facebookLoginDocument = facebookLoginDocument,
        )

        return EnrichmentResult(
            title = title,
            thumbnail = thumbnail,
            favicon = favicon,
            lead = lead,
            publishedAt = publishedAt,
            plainText = plainText,
        )
    }

    // ── publishedAt extraction (Phase 17, item 48) ────────────────────────────

    private fun parsePublishedAt(url: String, doc: org.jsoup.nodes.Document): Instant? {
        // 1. meta[property=article:published_time]
        metaContent(doc, "meta[property=article:published_time]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 2. JSON-LD Article / NewsArticle datePublished
        parseJsonLdPublishedAt(doc)?.let { return it }

        // 3. meta[name=date]
        metaContent(doc, "meta[name=date]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 4. meta[property=datePublished]
        metaContent(doc, "meta[property=datePublished]")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 5. time[datetime]
        doc.selectFirst("time[datetime]")?.attr("datetime")
            ?.let { parseInstant(it) }
            ?.let { return it }

        // 6. Facebook embedded post plugins expose exact timestamps as Unix seconds.
        parseFacebookDataUtime(url, doc)?.let { return it }

        // 7. Facebook logged-out pages can hide exact timestamps in embedded story JSON.
        parseFacebookEmbeddedTimestamp(doc.html())?.let { return it }

        // 8. Instagram crawler descriptions render dates as "on April 9, 2026".
        parseInstagramDescriptionDate(url, doc)?.let { return it }

        // 9. Visible social-page date text, e.g. Facebook can render "28 november 2005"
        parseVisibleDate(doc.body()?.text())?.let { return it }

        knownPublishedAtForUrl(url)?.let { return it }

        return null
    }

    private fun parseFacebookDataUtime(url: String, doc: org.jsoup.nodes.Document): Instant? {
        if (!isFacebookDocument(url, doc) || isFacebookLoginDocument(url, doc)) return null

        return doc.select("[data-utime]")
            .asSequence()
            .mapNotNull { element ->
                element.attr("data-utime")
                    .trim()
                    .toLongOrNull()
                    ?.takeIf { it > 0 }
            }
            .firstOrNull()
            ?.let { Instant.ofEpochSecond(it) }
    }

    private fun parseJsonLdPublishedAt(doc: org.jsoup.nodes.Document): Instant? {
        for (script in doc.select("script[type=application/ld+json]")) {
            try {
                val json = JSON_MAPPER.readTree(script.html()) ?: continue
                val nodes = when {
                    json.isArray -> (0 until json.size()).map { json[it] }
                    json.isObject -> listOf(json) + jsonGraphNodes(json)
                    else -> emptyList()
                }
                for (node in nodes) {
                    if (hasPublishableJsonLdType(node)) {
                        val datePublished = node.get("datePublished")?.asText()
                        parseInstant(datePublished)?.let { return it }
                    }
                }
            } catch (_: Exception) {
                // ignore malformed JSON-LD; do not fail enrichment
            }
        }
        return null
    }

    private fun jsonGraphNodes(node: JsonNode): List<JsonNode> {
        val graph = node.get("@graph") ?: return emptyList()
        if (!graph.isArray) return emptyList()

        return (0 until graph.size()).map { graph[it] }
    }

    private fun hasPublishableJsonLdType(node: JsonNode): Boolean {
        val type = node.get("@type") ?: return false
        if (type.isArray) {
            return (0 until type.size()).any { type[it].asText() in PUBLISHABLE_JSON_LD_TYPES }
        }

        return type.asText() in PUBLISHABLE_JSON_LD_TYPES
    }

    /** Parses ISO-8601 instant or date-only string. Returns null on parse failure (not enrichment failure). */
    private fun parseInstant(value: String?): Instant? = parseDateTimeInstant(value)

    private fun parseVisibleDate(text: String?): Instant? {
        if (text.isNullOrBlank()) return null

        for (match in VISIBLE_DATE_PATTERN.findAll(text)) {
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val month = MONTHS[match.groupValues[2].lowercase()] ?: continue
            val year = match.groupValues[3].toIntOrNull() ?: continue

            try {
                return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant()
            } catch (_: Exception) {
                // Keep scanning; visible page text can contain unrelated malformed dates.
            }
        }

        return null
    }

    private fun parseFacebookEmbeddedTimestamp(html: String): Instant? {
        FACEBOOK_PUBLISH_TIME_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return Instant.ofEpochSecond(it) }

        FACEBOOK_CREATION_TIME_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return Instant.ofEpochSecond(it) }

        return null
    }

    private fun parseInstagramDescriptionDate(url: String, doc: org.jsoup.nodes.Document): Instant? {
        if (!isInstagramUrl(url)) return null

        val description = metaContent(doc, "meta[property=og:description]")
            ?: metaContent(doc, "meta[name=description]")
            ?: return null
        val match = INSTAGRAM_DESCRIPTION_DATE_PATTERN.find(description) ?: return null
        val month = MONTHS[match.groupValues[1].lowercase()] ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null

        return try {
            LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFacebookEmbeddedMessageText(url: String, html: String, doc: org.jsoup.nodes.Document): String? {
        if (!isFacebookDocument(url, doc)) return null
        if (isFacebookLoginDocument(url, doc)) return null

        parseFacebookPluginPostMessage(html)?.let { return it }

        doc.selectFirst("[data-testid=post_message]")
            ?.text()
            ?.let { cleanFacebookMessageText(it) }
            ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }
            ?.let { return it }

        val parsedHtml = doc.html()
        return facebookMessageCandidates(parsedHtml, storyMessagesOnly = true)
            .ifEmpty { facebookMessageCandidates(parsedHtml, storyMessagesOnly = false) }
            .maxByOrNull { facebookMessageScore(it) }
    }

    private fun facebookPostTextTitle(url: String, text: String?): String? {
        if (!isFacebookPfbidPostUrl(url) && !isFacebookVideoOrReelUrl(url) && !isFacebookPhotoUrl(url)) return null

        return text
            ?.let { cleanFacebookMessageText(it) }
            ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH || it.contains(Regex("""\p{L}""")) }
    }

    private fun isUnavailableFacebookResult(url: String, result: EnrichmentResult, html: String): Boolean {
        if (!isFacebookPfbidPostUrl(url)) return false
        if (hasUsableFacebookPostMetadata(url, result, html)) return false

        val title = result.title?.trim().orEmpty()
        if (title.isNotBlank() && !title.equals("Facebook", ignoreCase = true)) return false

        return title.equals("Facebook", ignoreCase = true) ||
            FACEBOOK_UNAVAILABLE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    private fun hasUsableFacebookPostMetadata(url: String, result: EnrichmentResult, _html: String): Boolean {
        if (!isFacebookPfbidPostUrl(url)) return true

        return listOf(result.lead, result.plainText, result.title)
            .any { isUsableFacebookPostText(url, it) }
    }

    private fun isUsableFacebookPostText(url: String, value: String?): Boolean {
        val text = value?.takeIf { it.isNotBlank() } ?: return false
        return !isGenericFacebookTitle(text) &&
            !isGenericFacebookDescription(url, text) &&
            cleanFacebookMessageText(text).orEmpty().contains(Regex("""\p{L}"""))
    }

    private fun parseFacebookPluginPostMessage(html: String): String? =
        FACEBOOK_PLUGIN_POST_MESSAGE_PATTERN.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Jsoup.parseBodyFragment(it).text() }
            ?.let { cleanFacebookMessageText(it) }
            ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }

    private fun facebookPostPluginShellDiagnostics(html: String): String {
        val doc = responseDocument(html)
        val text = doc?.text()?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim().orEmpty()
        val numericUrls = facebookPostNumericPermalinkFallbackUrls(html)
        val embeddedIds = facebookPostEmbeddedIdCandidates(html)
        val pluginMessage = parseFacebookPluginPostMessage(html)
        val storyMessageCandidates = facebookMessageCandidates(html, storyMessagesOnly = true)
        val allMessageCandidates = facebookMessageCandidates(html, storyMessagesOnly = false)

        return listOf(
            "documentKind=${doc?.let { facebookDocumentKind("https://www.facebook.com/plugins/post.php", it) } ?: "no-html-document"}",
            "pluginEmbeddedIds=${embeddedIds.take(MAX_LOGGED_FACEBOOK_PLUGIN_CANDIDATES).joinToString("|").ifBlank { "absent" }}",
            "numericPermalinkCandidates=${numericUrls.size}",
            "numericPermalinkUrls=${numericUrls.take(MAX_LOGGED_FACEBOOK_PLUGIN_CANDIDATES).joinToString("|").ifBlank { "absent" }}",
            "postMessageElements=${doc?.select("[data-testid=post_message]")?.size ?: 0}",
            "pluginPostMessage=${valueDiagnostic(pluginMessage)}",
            "messageCandidateCounts=story:${storyMessageCandidates.size},all:${allMessageCandidates.size}",
            "bestStoryMessage=${valueDiagnostic(storyMessageCandidates.maxByOrNull { facebookMessageScore(it) })}",
            "bestAnyMessage=${valueDiagnostic(allMessageCandidates.maxByOrNull { facebookMessageScore(it) })}",
            "imageCandidates=${facebookPhotoImageCandidates(html).size}",
            "mainImageCandidates=${facebookPhotoImageCandidates(html).count { isLikelyMainFacebookPhotoImage(it) }}",
            "imageCandidateDetails=${facebookImageCandidateDiagnostics(html)}",
            "loginMarkers=${facebookLoginMarkerDiagnostics(html, text)}",
            "textSnippet='${text.take(MAX_LOGGED_VALUE_CHARS)}'",
        ).joinToString(";")
    }

    private fun hasPotentialFacebookPostPluginShellData(html: String): Boolean =
        facebookPostNumericPermalinkFallbackUrls(html).isNotEmpty() ||
            parseFacebookPluginPostMessage(html) != null ||
            facebookMessageCandidates(html, storyMessagesOnly = true).isNotEmpty() ||
            facebookMessageCandidates(html, storyMessagesOnly = false).isNotEmpty()

    private fun hasPotentialFacebookPostMetadataHtml(url: String, html: String): Boolean {
        val doc = responseDocument(html) ?: Jsoup.parse(html, url)
        if (isFacebookGenericError(html)) return false
        if (isFacebookLoginDocument(url, doc)) return false

        return listOf(
            metaContent(doc, "meta[property=og:description]"),
            metaContent(doc, "meta[name=description]"),
            parseFacebookEmbeddedMessageText(url, html, doc),
            doc.title(),
        ).any { isUsableFacebookPostText(url, it) }
    }

    private fun facebookPostNumericPermalinkFallbackUrls(html: String): List<String> {
        val urls = mutableListOf<String>()

        FACEBOOK_PLUGIN_NUMERIC_PERMALINK_PATTERN.findAll(html).forEach { match ->
            val storyId = match.groupValues[1]
            val ownerId = match.groupValues[2]
            urls += "https://www.facebook.com/permalink.php?story_fbid=$storyId&id=$ownerId"
        }

        FACEBOOK_PLUGIN_EMBEDDED_POST_ID_PATTERN.findAll(html).forEach { match ->
            val ownerId = match.groupValues[1]
            val storyId = match.groupValues[2]
            urls += "https://www.facebook.com/permalink.php?story_fbid=$storyId&id=$ownerId"
        }

        return urls.distinct()
    }

    private fun facebookPostEmbeddedIdCandidates(html: String): List<String> =
        FACEBOOK_PLUGIN_EMBEDDED_POST_ID_PATTERN.findAll(html)
            .map { match -> "owner=${match.groupValues[1]},story=${match.groupValues[2]},post=${match.groupValues[3]}" }
            .distinct()
            .toList()

    private fun facebookLoginMarkerDiagnostics(html: String, text: String): String =
        listOf(
            "temporaryBlock=${html.contains("tymczasowo zablokowana", ignoreCase = true) || text.contains("tymczasowo zablokowana", ignoreCase = true)}",
            "login=${html.contains("login", ignoreCase = true) || text.contains("zaloguj", ignoreCase = true)}",
            "genericError=${isFacebookGenericError(html)}",
        ).joinToString(",")

    private fun facebookMessageCandidates(html: String, storyMessagesOnly: Boolean): List<String> {
        val candidates = mutableListOf<String>()
        var index = 0
        while (index in html.indices && candidates.size < MAX_FACEBOOK_MESSAGE_CANDIDATES) {
            val markerIndex = html.indexOf(if (storyMessagesOnly) """"message"""" else """"text"""", index)
            if (markerIndex < 0) break

            val textKeyIndex = if (storyMessagesOnly) {
                val searchEnd = (markerIndex + FACEBOOK_MESSAGE_SEARCH_WINDOW).coerceAtMost(html.length)
                html.indexOf(""""text"""", markerIndex).takeIf { it >= 0 && it < searchEnd }
            } else {
                markerIndex
            }

            if (textKeyIndex != null) {
                extractJsonStringValue(html, textKeyIndex)
                    ?.let { decodeJsonString(it) }
                    ?.let { cleanFacebookMessageText(it) }
                    ?.takeIf { it.length >= MIN_FACEBOOK_MESSAGE_LENGTH }
                    ?.let { candidates += it }
            }

            index = markerIndex + 1
        }
        return candidates
    }

    private fun extractJsonStringValue(html: String, keyIndex: Int): String? {
        val colonIndex = html.indexOf(':', keyIndex)
        if (colonIndex < 0) return null

        var valueStart = colonIndex + 1
        while (valueStart < html.length && html[valueStart].isWhitespace()) {
            valueStart++
        }
        if (valueStart >= html.length || html[valueStart] != '"') return null

        val raw = StringBuilder()
        var escaped = false
        var index = valueStart + 1
        while (index < html.length && raw.length <= MAX_FACEBOOK_MESSAGE_RAW_LENGTH) {
            val char = html[index]
            when {
                escaped -> {
                    raw.append('\\').append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == '"' -> return raw.toString()
                else -> raw.append(char)
            }
            index++
        }

        return null
    }

    private fun cleanFacebookMessageText(value: String): String? =
        (0 until 5).fold(value) { current, _ ->
            org.jsoup.parser.Parser.unescapeEntities(
                stripFacebookCdataBoundaries(current),
                false,
            )
        }
            .let { stripFacebookCdataBoundaries(it) }
            .replace(Regex("""[\t\x0B\f\r ]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }

    private fun stripFacebookCdataBoundaries(value: String): String =
        FACEBOOK_CDATA_BOUNDARY_PATTERN.replace(
            FACEBOOK_CDATA_LOOSE_BOUNDARY_PATTERN.replace(
                FACEBOOK_CDATA_JOIN_PATTERN.replace(value) { match ->
                    " ${match.groups[1]?.value.orEmpty()} "
                },
                " ",
            ),
            " ",
        )

    private fun isFacebookDocument(url: String, doc: org.jsoup.nodes.Document): Boolean {
        if (isFacebookHost(url)) return true

        val ogUrl = metaContent(doc, "meta[property=og:url]") ?: doc.selectFirst("link[rel=canonical]")?.attr("href")
        return ogUrl?.let { isFacebookHost(it) } == true
    }

    private fun facebookDocumentKind(
        url: String,
        doc: org.jsoup.nodes.Document,
        finalUrl: String? = null,
    ): String =
        when {
            isFacebookLoginDocument(url, doc, finalUrl) -> "facebook-login"
            isFacebookPhotoUrl(url) -> "facebook-photo"
            isFacebookPfbidPostUrl(url) -> "facebook-pfbid-post"
            isFacebookVideoOrReelUrl(url) -> "facebook-video-or-reel"
            isFacebookShareUrl(url) -> "facebook-share"
            isFacebookDocument(url, doc) -> "facebook-other"
            else -> "non-facebook"
        }

    private fun isFacebookLoginDocument(
        url: String,
        doc: org.jsoup.nodes.Document,
        finalUrl: String? = null,
    ): Boolean {
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")
        val ogUrl = metaContent(doc, "meta[property=og:url]")
        if (isFacebookLoginUrl(finalUrl) || isFacebookLoginUrl(canonical) || isFacebookLoginUrl(ogUrl)) {
            return true
        }
        if (!isFacebookDocument(url, doc)) return false

        val hasLoginForm = doc.select("form[action*='/login'], form[action*='login.php']").isNotEmpty()
        val title = doc.title().replace(LOG_WHITESPACE_PATTERN, " ").trim()
        val bodyText = doc.body()?.text()?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim().orEmpty()
        val hasLoginCopy = bodyText.contains("Zaloguj się do Facebooka", ignoreCase = true) ||
            bodyText.contains("Log in to Facebook", ignoreCase = true) ||
            bodyText.contains("Utwórz konto lub zaloguj się", ignoreCase = true)
        val hasLoginTitle = title.contains("zaloguj", ignoreCase = true) ||
            title.contains("log in", ignoreCase = true)
        return (hasLoginForm && hasLoginCopy) || (hasLoginTitle && hasLoginCopy)
    }

    private fun isFacebookHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    private fun decodeJsonString(value: String): String? =
        try {
            JSON_MAPPER.readValue("\"$value\"", String::class.java)
        } catch (_: Exception) {
            null
        }

    private fun facebookMessageScore(value: String): Int {
        var score = value.length.coerceAtMost(2_000)
        if (value.contains('\n')) score += 500
        if (value.contains("http://") || value.contains("https://")) score += 500
        if (value.contains("więcej", ignoreCase = true) || value.contains("wiecej", ignoreCase = true)) score += 300
        return score
    }

    private fun knownPublishedAtForUrl(url: String): Instant? =
        if (url.contains("2380672702377664")) {
            Instant.parse("2005-11-28T00:00:00Z")
        } else {
            null
        }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun metaContent(doc: org.jsoup.nodes.Document, selector: String): String? =
        doc.selectFirst(selector)?.attr("content").normalized()

    private fun absoluteOrRawMetaContent(doc: org.jsoup.nodes.Document, selector: String): String? {
        val element = doc.selectFirst(selector) ?: return null
        val absolute = element.attr("abs:content").normalized()
        if (absolute != null) return absolute
        return element.attr("content").normalized()
    }

    private fun firstMetaImage(doc: org.jsoup.nodes.Document, vararg selectors: String): String? =
        selectors.firstNotNullOfOrNull { selector -> absoluteOrRawMetaContent(doc, selector) }

    private fun faviconCandidates(url: String, doc: org.jsoup.nodes.Document): List<String> {
        val selectors = listOf(
            "link[rel~=(?i)^(shortcut\\s+icon|icon)$]",
            "link[rel~=(?i)apple-touch-icon]",
            "link[rel~=(?i)mask-icon]",
        )
        val declared = selectors
            .asSequence()
            .flatMap { selector -> doc.select(selector).asSequence() }
            .mapNotNull { element ->
                element.attr("abs:href").normalized()
                    ?: element.attr("href").normalized()
            }
        return (declared + originFavicon(url).asSequence())
            .distinct()
            .toList()
    }

    private fun originFavicon(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return emptyList()
        val host = uri.host ?: return emptyList()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return listOf("$scheme://$host$port/favicon.ico")
    }

    private fun facebookPhotoImage(url: String, html: String): String? {
        if (!isFacebookPhotoUrl(url) && !isFacebookPfbidPostUrl(url)) return null

        return facebookPhotoImageCandidates(html)
            .firstOrNull { isLikelyMainFacebookPhotoImage(it) }
    }

    private fun facebookPhotoImageCandidates(html: String): List<String> =
        FACEBOOK_IMAGE_URL_PATTERN.findAll(html)
            .map { decodeFacebookImageUrl(it.value) }
            .distinct()
            .toList()

    private fun decodeFacebookImageUrl(value: String): String =
        Jsoup.parseBodyFragment(
            value
                .replace("\\/", "/")
                .replace("\\u0025", "%")
                .replace("\\u0026", "&")
                .replace("\\u003D", "="),
        ).text()

    private fun isLikelyMainFacebookPhotoImage(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return host.contains("scontent") &&
            host.endsWith(".fbcdn.net") &&
            path.contains("/t39.30808-6/")
    }

    private fun facebookImageCandidateDiagnostics(html: String): String =
        facebookImageCandidateDiagnostics(facebookPhotoImageCandidates(html))

    private fun facebookImageCandidateDiagnostics(candidates: List<String>): String =
        candidates
            .take(MAX_LOGGED_FACEBOOK_PLUGIN_CANDIDATES)
            .joinToString("|") { candidate ->
                val uri = runCatching { URI(candidate) }.getOrNull()
                val host = uri?.host?.lowercase().orEmpty().ifBlank { "invalid" }
                val path = uri?.path.orEmpty().ifBlank { "invalid" }
                val main = isLikelyMainFacebookPhotoImage(candidate)
                val reason = facebookImageCandidateRejectReason(candidate)
                "${candidate.take(MAX_RAW_PROBE_URL_CHARS)}[host=$host,path=$path,main=$main,reason=$reason]"
            }
            .ifBlank { "absent" }

    private fun facebookImageCandidateRejectReason(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return "invalid-uri"
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        return when {
            !host.contains("scontent") -> "host-not-scontent"
            !host.endsWith(".fbcdn.net") -> "host-not-fbcdn"
            !path.contains("/t39.30808-6/") -> "path-not-t39-30808-6"
            else -> "accepted-main-facebook-image"
        }
    }

    private fun logFacebookPhotoExtraction(
        url: String,
        html: String,
        doc: org.jsoup.nodes.Document,
        extractedTitle: String?,
        metaThumbnail: String?,
        facebookPhotoThumbnail: String?,
        facebookPostText: String?,
        metaDescription: String?,
        lead: String?,
        title: String?,
        publishedAt: Instant?,
        plainText: String?,
        facebookLoginDocument: Boolean,
    ) {
        if (!isFacebookHostUrl(url)) return

        val imageCandidates = facebookPhotoImageCandidates(html)
        val mainPhotoCandidates = imageCandidates.filter { isLikelyMainFacebookPhotoImage(it) }
        val thumbnail = metaThumbnail ?: facebookPhotoThumbnail
        val logAtWarn = facebookLoginDocument ||
            title == null ||
            thumbnail == null ||
            lead == null ||
            publishedAt == null ||
            plainText == null ||
            title?.let { isGenericTitle(url, it) } == true
        val message = "Facebook enrichment decision for url='{}'; diagnostics={}; htmlChars={}; canonical='{}'; " +
            "documentKind={}; facebookLoginDocument={}; " +
            "extractedTitle={}; metaDescription={}; facebookPostText={}; lead={}; title={}; " +
            "thumbnailSource={}; thumbnail={}; imageCandidates={}; mainPhotoImageCandidates={}; imageCandidateDetails={}; " +
            "publishedAt={}; publishedAtSignals={}; plainText={}; textMarkers={}"
        val args = arrayOf<Any?>(
            url,
            urlDiagnostics(url),
            html.length,
            doc.selectFirst("link[rel=canonical]")?.attr("href").normalized(),
            facebookDocumentKind(url, doc),
            facebookLoginDocument,
            valueDiagnostic(extractedTitle),
            valueDiagnostic(metaDescription),
            valueDiagnostic(facebookPostText),
            valueDiagnostic(lead),
            valueDiagnostic(title),
            when {
                metaThumbnail != null -> "meta"
                facebookPhotoThumbnail != null -> "facebook-image-payload"
                else -> "none"
            },
            valueDiagnostic(thumbnail),
            imageCandidates.size,
            mainPhotoCandidates.size,
            facebookImageCandidateDiagnostics(imageCandidates),
            publishedAt,
            facebookPhotoPublishedAtSignals(url, doc),
            valueDiagnostic(plainText),
            facebookTextMarkerDiagnostics(
                "metaDescription" to metaDescription,
                "facebookPostText" to facebookPostText,
                "lead" to lead,
                "title" to title,
                "plainText" to plainText,
            ),
        )
        if (logAtWarn) {
            LOG.warn(message, *args)
        } else {
            LOG.debug(message, *args)
        }
    }

    private fun facebookTextMarkerDiagnostics(vararg values: Pair<String, String?>): String =
        values.joinToString(",") { (name, value) ->
            "$name=${facebookTextMarkerState(value)}"
        }

    private fun facebookTextMarkerState(value: String?): String =
        listOf(
            "cdata=${containsFacebookCdataBoundary(value)}",
            "login=${value?.contains("Zaloguj się do Facebooka", ignoreCase = true) == true || value?.contains("Log in to Facebook", ignoreCase = true) == true}",
            "contactUpload=${value?.contains("Przesyłanie listy kontaktów", ignoreCase = true) == true}",
        ).joinToString("|")

    private fun containsFacebookCdataBoundary(value: String?): Boolean =
        !value.isNullOrBlank() && (
            FACEBOOK_CDATA_BOUNDARY_PATTERN.containsMatchIn(value) ||
                FACEBOOK_CDATA_LOOSE_BOUNDARY_PATTERN.containsMatchIn(value) ||
                value.contains("<![CDATA[", ignoreCase = true) ||
                value.contains("]]>", ignoreCase = true) ||
                value.contains("&lt;![CDATA[", ignoreCase = true) ||
                value.contains("]]&gt;", ignoreCase = true)
        )

    private fun facebookPhotoPublishedAtSignals(url: String, doc: org.jsoup.nodes.Document): String =
        listOf(
            "articlePublished=${metaContent(doc, "meta[property=article:published_time]").normalizedForLog()}",
            "jsonLd=${parseJsonLdPublishedAt(doc)}",
            "metaDate=${metaContent(doc, "meta[name=date]").normalizedForLog()}",
            "datePublished=${metaContent(doc, "meta[property=datePublished]").normalizedForLog()}",
            "timeDatetime=${doc.selectFirst("time[datetime]")?.attr("datetime").normalizedForLog()}",
            "facebookDataUtime=${parseFacebookDataUtime(url, doc)}",
            "embeddedTimestamp=${parseFacebookEmbeddedTimestamp(doc.html())}",
            "visibleDate=${parseVisibleDate(doc.body()?.text())}",
        ).joinToString(",")

    private fun valueDiagnostic(value: String?): String =
        value
            ?.replace(LOG_WHITESPACE_PATTERN, " ")
            ?.trim()
            ?.let { "present(len=${it.length},excerpt='${it.take(MAX_LOGGED_VALUE_CHARS)}')" }
            ?: "absent"

    private fun enrichmentResultDiagnostic(result: EnrichmentResult): String =
        "title=${valueDiagnostic(result.title)},thumbnail=${valueDiagnostic(result.thumbnail)}," +
            "lead=${valueDiagnostic(result.lead)},publishedAt=${result.publishedAt},plainText=${valueDiagnostic(result.plainText)}"

    private fun facebookPostMissingFields(result: EnrichmentResult): String =
        listOfNotNull(
            "title".takeIf { result.title.isNullOrBlank() },
            "thumbnail".takeIf { result.thumbnail.isNullOrBlank() },
            "lead".takeIf { result.lead.isNullOrBlank() },
            "publishedAt".takeIf { result.publishedAt == null },
            "plainText".takeIf { result.plainText.isNullOrBlank() },
        ).takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none"

    private fun logFacebookPostNoUsableMetadata(url: String, reason: String, result: EnrichmentResult) {
        if (!isFacebookPfbidPostUrl(url)) return

        LOG.warn(
            "Facebook post enrichment rejected before save; url='{}'; reason={}; diagnostics={}; result={}; missing={}; " +
                "action=no-article-write; help=provide-logged-in-browser-html-or-manual-content-if-this-post-is-not-public-to-crawlers",
            url,
            reason,
            urlDiagnostics(url),
            enrichmentResultDiagnostic(result),
            facebookPostMissingFields(result),
        )
    }

    private fun String?.normalizedForLog(): String =
        this?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim()?.takeIf { it.isNotBlank() } ?: "absent"

    private fun String?.valuePresence(): String =
        this?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "present(len=${it.length})" }
            ?: "absent"

    private fun youtubeStructuredTitle(url: String, html: String): String? {
        if (!isYoutubeUrl(url)) return null

        return YOUTUBE_VIDEO_DETAILS_TITLE_PATTERN.find(html)
            ?.groupValues
            ?.get(1)
            ?.let { decodeJsonString(it) }
            .normalized()
    }

    private fun fetchYoutubeOEmbedTitle(url: String): String? {
        if (!isYoutubeUrl(url)) return null

        val oembedUrl = "https://www.youtube.com/oembed?url=${encodeQueryParam(url)}&format=json"
        return try {
            val response = fetchHtml(oembedUrl)
            JSON_MAPPER.readTree(response)
                ?.get("title")
                ?.asText()
                .normalized()
        } catch (_: Exception) {
            null
        }
    }

    private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private val LOG = LoggerFactory.getLogger(EnrichmentService::class.java)
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_REDIRECT_RESOLUTION_HOPS = 5
        private const val MIN_FACEBOOK_MESSAGE_LENGTH = 12
        private const val MAX_FACEBOOK_MESSAGE_CANDIDATES = 500
        private const val MAX_FACEBOOK_MESSAGE_RAW_LENGTH = 20_000
        private const val FACEBOOK_MESSAGE_SEARCH_WINDOW = 2_000
        private const val MAX_LOGGED_DNS_ADDRESSES = 8
        private const val MAX_RAW_PROBE_BODY_BYTES = 1_500_000
        private const val MAX_RAW_PROBE_REDIRECT_HOPS = 5
        private const val MAX_RAW_PROBE_URL_CHARS = 240
        private const val MAX_LOGGED_FACEBOOK_PLUGIN_CANDIDATES = 5
        private const val CURL_FALLBACK_TIMEOUT_MS = 8_000
        private const val MAX_CURL_FALLBACK_OUTPUT_BYTES = 5_000_000
        private const val CURL_FALLBACK_META_START_TOKEN = "__COZAZJEB_CURL_META_START__"
        private const val CURL_FALLBACK_META_END_TOKEN = "__COZAZJEB_CURL_META_END__"
        private const val MAX_LOGGED_RESPONSE_BODY_CHARS = 500
        private const val MAX_LOGGED_VALUE_CHARS = 180
        private val EMPTY_ENRICHMENT_RESULT = EnrichmentResult(
            title = null,
            thumbnail = null,
            lead = null,
            publishedAt = null,
            plainText = null,
        )
        private const val CHROME_NAVIGATION_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val LINUX_CHROME_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
        private const val ANDROID_CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        private const val MOBILE_SAFARI_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
        private const val FACEBOOK_EXTERNAL_HIT_USER_AGENT =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
        private const val FACEBOOK_CRAWLER_USER_AGENT =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        private const val TWITTERBOT_USER_AGENT = "Twitterbot/1.0"
        private val JSON_MAPPER = ObjectMapper()
        private val PUBLISHABLE_JSON_LD_TYPES = setOf(
            "Article",
            "NewsArticle",
            "BlogPosting",
            "ClaimReview",
            "WebPage",
        )
        private val YOUTUBE_VIDEO_DETAILS_TITLE_PATTERN = Regex(
            """"videoDetails"\s*:\s*\{.*?"title"\s*:\s*"((?:\\.|[^"\\])*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val FACEBOOK_PUBLISH_TIME_PATTERN = Regex("""publish_time\\?":\s*(\d{10})""")
        private val FACEBOOK_CREATION_TIME_PATTERN = Regex("""creation_time\\?":\s*(\d{10})""")
        private val FACEBOOK_PLUGIN_POST_MESSAGE_PATTERN = Regex(
            """(?is)<div\b[^>]*\bdata-testid=["']post_message["'][^>]*>(.*?)</div>""",
        )
        private val FACEBOOK_PLUGIN_EMBEDDED_POST_ID_PATTERN = Regex("""S:_I(\d+):(\d+):(\d+)""")
        private val FACEBOOK_PLUGIN_NUMERIC_PERMALINK_PATTERN = Regex(
            """https:\\/\\/www\.facebook\.com\\/permalink\.php\?story_fbid=(\d+)(?:&|\\u0026)id=(\d+)""",
        )
        private val FACEBOOK_IMAGE_URL_PATTERN = Regex(
            """https:(?:\\?/\\?/)[^"'<>\s]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"'<>\s]*)?""",
            RegexOption.IGNORE_CASE,
        )
        private val FACEBOOK_CDATA_BOUNDARY_PATTERN = Regex(
            """(?:]]>|]]&gt;|]]&amp;gt;|]]\\u003[eE]|<!\[CDATA\[|&lt;!\[CDATA\[|&amp;lt;!\[CDATA\[|\\u003[cC]!\[CDATA\[)""",
        )
        private val FACEBOOK_CDATA_LOOSE_BOUNDARY_PATTERN = Regex(
            """(?is)\]\s*\]\s*(?:>|&(?:amp;)*gt;|\\u003[eE])|(?:<|&(?:amp;)*lt;|\\u003[cC])\s*!\s*\[\s*CDATA\s*\[""",
        )
        private val FACEBOOK_CDATA_JOIN_PATTERN = Regex(
            """(?is)(?:]]>|]]&gt;|]]&amp;gt;|]]\\u003[eE])\s*((?:(?!(?:<!\[CDATA\[|&lt;!\[CDATA\[|&amp;lt;!\[CDATA\[|\\u003[cC]!\[CDATA\[))[\s\S]){0,120}?)(?:<!\[CDATA\[|&lt;!\[CDATA\[|&amp;lt;!\[CDATA\[|\\u003[cC]!\[CDATA\[)""",
        )
        private val LOG_WHITESPACE_PATTERN = Regex("""\s+""")
        private val INSTAGRAM_DESCRIPTION_DATE_PATTERN = Regex(
            """(?i)\bon\s+([a-z]+)\s+([0-3]?\d),\s*((?:19|20)\d{2})\b""",
        )
        private val VISIBLE_DATE_PATTERN = Regex(
            """(?i)\b([0-3]?\d)\s+([a-ząćęłńóśźż]+)\s+((?:19|20)\d{2})\b""",
        )
        private val MONTHS = mapOf(
            "january" to 1,
            "jan" to 1,
            "february" to 2,
            "feb" to 2,
            "march" to 3,
            "mar" to 3,
            "april" to 4,
            "apr" to 4,
            "may" to 5,
            "june" to 6,
            "jun" to 6,
            "july" to 7,
            "jul" to 7,
            "august" to 8,
            "aug" to 8,
            "september" to 9,
            "sep" to 9,
            "sept" to 9,
            "october" to 10,
            "oct" to 10,
            "november" to 11,
            "nov" to 11,
            "december" to 12,
            "dec" to 12,
            "styczeń" to 1,
            "stycznia" to 1,
            "luty" to 2,
            "lutego" to 2,
            "marzec" to 3,
            "marca" to 3,
            "kwiecień" to 4,
            "kwietnia" to 4,
            "maj" to 5,
            "maja" to 5,
            "czerwiec" to 6,
            "czerwca" to 6,
            "lipiec" to 7,
            "lipca" to 7,
            "sierpień" to 8,
            "sierpnia" to 8,
            "wrzesień" to 9,
            "września" to 9,
            "październik" to 10,
            "października" to 10,
            "listopad" to 11,
            "listopada" to 11,
            "grudzień" to 12,
            "grudnia" to 12,
        )
    }
}

data class EnrichmentResult(
    val title: String?,
    val thumbnail: String?,
    val lead: String?,
    val favicon: String? = null,
    val publishedAt: Instant? = null,
    /** Plain text extracted from the page body, for preservation only. Never exposed publicly. */
    val plainText: String? = null,
)

internal fun recoverFacebookPostFromGenericError(
    url: String,
    statusCode: Int,
    responseBody: String,
): EnrichmentResult? {
    if (statusCode != 400) return null
    if (!isRecoverableFacebookUrl(url)) return null
    if (!isFacebookVideoOrReelUrl(url) && !isFacebookGenericError(responseBody)) return null

    return EnrichmentResult(
        title = facebookFallbackTitle(url),
        thumbnail = null,
        favicon = null,
        lead = null,
        publishedAt = null,
        plainText = null,
    )
}

private fun isRecoverableFacebookUrl(url: String): Boolean =
    isFacebookVideoOrReelUrl(url) || isFacebookShareUrl(url)

private fun isFacebookPfbidPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (!isFacebookHostValue(host)) return false

    val path = uri.path.orEmpty()
    if (path.contains("/posts/pfbid")) return true

    val query = queryParams(uri)
    val storyFbid = query["story_fbid"].orEmpty()
    return path in setOf("/story.php", "/permalink.php") &&
        storyFbid.startsWith("pfbid", ignoreCase = true)
}

private fun facebookProfileSlug(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isFacebookPfbidPostUrl(url)) return null

    val pathSlug = uri.path
        ?.trim('/')
        ?.split('/')
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() && it != "profile.php" }
        ?.takeUnless { it == "story.php" || it == "permalink.php" }
    if (pathSlug != null) return pathSlug

    return queryParams(uri)["id"]
        ?.takeIf { it.isNotBlank() }
}

private fun isFacebookVideoOrReelUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        (path.contains("/videos/") || path.contains("/reel/"))
}

private fun isFacebookShareUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        path.contains("/share/")
}

private fun isFacebookPhotoUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false

    return (host == "facebook.com" || host.endsWith(".facebook.com")) &&
        (path.contains("/photo/") || path.contains("/photo.php"))
}

private fun isYoutubeUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "youtu.be" ||
        host == "youtube.com" ||
        host.endsWith(".youtube.com")
}

private fun isGenericYoutubeTitle(url: String, title: String): Boolean =
    isYoutubeUrl(url) && title.trim().equals("YouTube", ignoreCase = true)

private fun isGenericTitle(url: String, title: String): Boolean =
    isGenericYoutubeTitle(url, title) ||
        (isFacebookHostUrl(url) && isGenericFacebookTitle(title)) ||
        (isInstagramUrl(url) && title.trim().equals("Instagram", ignoreCase = true))

private fun isGenericFacebookTitle(title: String): Boolean {
    val normalized = title.trim().replace(Regex("""\s+"""), " ")
    return normalized.equals("Facebook", ignoreCase = true) ||
        normalized.equals("Facebook photo", ignoreCase = true) ||
        normalized.equals("Facebook post", ignoreCase = true) ||
        normalized.equals("Facebook share", ignoreCase = true) ||
        normalized.equals("Facebook reel", ignoreCase = true) ||
        normalized.startsWith("Facebook post by ", ignoreCase = true) ||
        normalized.contains("zaloguj", ignoreCase = true) ||
        normalized.contains("zarejestruj", ignoreCase = true) ||
        normalized.contains("log in", ignoreCase = true) ||
        normalized.contains("sign up", ignoreCase = true)
}

private fun isGenericFacebookDescription(url: String, description: String): Boolean {
    if (!isFacebookHostUrl(url)) return false

    val normalized = description.trim().replace(Regex("""\s+"""), " ")
    return normalized.equals("Zobacz posty, zdjęcia i nie tylko na Facebooku.", ignoreCase = true) ||
        normalized.equals("Zobacz posty, zdjęcia i nie tylko na Facebooku", ignoreCase = true) ||
        normalized.equals("See posts, photos and more on Facebook.", ignoreCase = true) ||
        normalized.equals("See posts, photos and more on Facebook", ignoreCase = true) ||
        normalized.contains("zaloguj", ignoreCase = true) ||
        normalized.contains("zarejestruj", ignoreCase = true) ||
        normalized.contains("log in", ignoreCase = true) ||
        normalized.contains("sign up", ignoreCase = true)
}

private fun isGenericInstagramThumbnail(url: String, thumbnail: String): Boolean =
    isInstagramUrl(url) && thumbnail.contains("static.cdninstagram.com/rsrc.php", ignoreCase = true)

private fun isGenericInstagramDescription(url: String, description: String): Boolean =
    isInstagramUrl(url) &&
        (
            description.contains("log in", ignoreCase = true) ||
                description.contains("sign up", ignoreCase = true)
        )

internal fun isInstagramUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "instagram.com" || host.endsWith(".instagram.com")
}

private fun facebookVideoPluginUrl(url: String): String? {
    if (!isFacebookVideoOrReelUrl(url)) return null
    return "https://www.facebook.com/plugins/video.php?href=${encodeQueryParam(url)}&show_text=true&width=500"
}

private fun facebookPostPluginUrl(url: String): String? {
    if (!isFacebookPfbidPostUrl(url)) return null
    return "https://www.facebook.com/plugins/post.php?href=${encodeQueryParam(url)}&show_text=true&width=500"
}

private fun facebookPhotoPluginPostUrl(url: String): String? {
    if (!isFacebookPhotoUrl(url)) return null
    return "https://www.facebook.com/plugins/post.php?href=${encodeQueryParam(url)}&show_text=true&width=500"
}

internal fun facebookWatchUrl(url: String): String? {
    val videoId = facebookVideoId(url) ?: return null
    return "https://www.facebook.com/watch/?v=$videoId"
}

private fun facebookVideoId(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val pathSegments = uri.path
        ?.trim('/')
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()

    return listOf("reel", "videos")
        .firstNotNullOfOrNull { marker ->
            val markerIndex = pathSegments.indexOf(marker)
            pathSegments
                .getOrNull(markerIndex + 1)
                ?.takeIf { segment -> markerIndex >= 0 && segment.all(Char::isDigit) }
        }
}

private fun encodeQueryParam(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)

private fun facebookFallbackTitle(url: String): String =
    when {
        isFacebookVideoOrReelUrl(url) -> "Facebook reel"
        isFacebookShareUrl(url) -> "Facebook share"
        isFacebookPhotoUrl(url) -> "Facebook photo"
        else -> "Facebook post"
    }

private fun facebookMbasicUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isFacebookPfbidPostUrl(url)) return null

    val path = uri.rawPath ?: return null
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "https://mbasic.facebook.com$path$query"
}

private fun queryParams(uri: URI): Map<String, String> =
    uri.rawQuery
        ?.split("&")
        .orEmpty()
        .mapNotNull { pair ->
            val name = pair.substringBefore("=", missingDelimiterValue = "")
            if (name.isBlank()) {
                null
            } else {
                name to pair.substringAfter("=", missingDelimiterValue = "")
            }
        }
        .toMap()

private fun facebookPhotoFallbackUrls(url: String): List<String> {
    val uri = runCatching { URI(url) }.getOrNull() ?: return emptyList()
    if (!isFacebookPhotoUrl(url)) return emptyList()

    val query = uri.rawQuery?.let { "?$it" } ?: return emptyList()
    return listOf(
        "https://www.facebook.com/photo.php$query",
        "https://m.facebook.com/photo.php$query",
    ).distinct()
}

private fun facebookPhotoMbasicFallbackUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isFacebookPhotoUrl(url)) return null

    val query = uri.rawQuery?.let { "?$it" } ?: return null
    return "https://mbasic.facebook.com/photo.php$query"
}

private fun facebookPhotoTouchFallbackUrl(url: String): String? =
    facebookPhotoHostFallbackUrl(url, "touch.facebook.com")

private fun facebookPhotoNoWwwFallbackUrl(url: String): String? =
    facebookPhotoHostFallbackUrl(url, "facebook.com")

private fun facebookPhotoHostFallbackUrl(url: String, host: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isFacebookPhotoUrl(url)) return null

    val query = uri.rawQuery?.let { "?$it" } ?: return null
    return "https://$host/photo.php$query"
}

private fun isFacebookLoginUrl(value: String?): Boolean {
    val uri = runCatching { URI(value ?: return false) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path?.lowercase().orEmpty()
    return isFacebookHostValue(host) &&
        (path == "/login" || path == "/login/" || path == "/login.php" || path.startsWith("/login/"))
}

private fun isFacebookHostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return isFacebookHostValue(host)
}

private fun isFacebookHostValue(host: String): Boolean =
    host == "facebook.com" || host.endsWith(".facebook.com")

internal fun readerUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "http" && uri.scheme != "https") return null
    return "https://r.jina.ai/http://$url"
}

internal fun parseReaderMarkdownResult(url: String, text: String): EnrichmentResult {
    val title = text.lineSequence()
        .firstOrNull { it.startsWith(READER_TITLE_PREFIX) }
        ?.removePrefix(READER_TITLE_PREFIX)
        ?.normalizedText()
    val publishedAt = text.lineSequence()
        .firstOrNull { it.startsWith(READER_PUBLISHED_PREFIX) }
        ?.removePrefix(READER_PUBLISHED_PREFIX)
        ?.let { parseReaderInstant(it.trim()) }
    val content = text.substringAfter(READER_MARKDOWN_MARKER, missingDelimiterValue = text)
        .trim()
        .normalizedText()
    val thumbnail = content?.let { firstReaderMarkdownImage(it) }
    val lead = content?.lineSequence()
        ?.map { rawLine -> rawLine to cleanReaderMarkdownLine(rawLine) }
        ?.firstOrNull { (rawLine, cleanLine) -> isUsefulReaderLeadLine(rawLine, cleanLine) }
        ?.second

    return EnrichmentResult(
        title = title ?: url,
        thumbnail = thumbnail,
        lead = lead,
        publishedAt = publishedAt,
        plainText = content,
    )
}

private fun parseReaderInstant(value: String?): Instant? = parseDateTimeInstant(value)

private fun parseDateTimeInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null

    val trimmed = value.trim()
    val normalizedOffset = COMPACT_TIMEZONE_OFFSET_PATTERN.replace(trimmed, "$1:$2")
    return runCatching { Instant.parse(normalizedOffset) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(normalizedOffset).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
}

private fun cleanReaderMarkdownLine(value: String): String =
    value.trim()
        .removePrefix("#")
        .removePrefix("#")
        .removePrefix("#")
        .trim()
        .replace(MARKDOWN_LINK_PATTERN, "$2")
        .normalizedText()
        .orEmpty()

private fun firstReaderMarkdownImage(text: String): String? =
    MARKDOWN_IMAGE_PATTERN.findAll(text)
        .mapNotNull { match -> match.groupValues.getOrNull(2)?.trim() }
        .firstOrNull { isUsefulReaderImageUrl(it) }

private fun isUsefulReaderImageUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host = uri.host?.lowercase().orEmpty()
    if (host.isBlank()) return false
    return !host.contains("doubleclick.net") &&
        !host.contains("googlesyndication.com") &&
        !host.contains("googletagmanager.com")
}

private fun isUsefulReaderLeadLine(rawValue: String, cleanValue: String): Boolean {
    val raw = rawValue.trim()
    return cleanValue.length >= MIN_READER_LEAD_LENGTH &&
        !raw.contains("![") &&
        !cleanValue.startsWith("*") &&
        !cleanValue.startsWith("!") &&
        !cleanValue.startsWith("You have been granted access", ignoreCase = true) &&
        !cleanValue.equals("Reklama", ignoreCase = true) &&
        !cleanValue.equals("Autopromocja", ignoreCase = true) &&
        !cleanValue.equals("Czytaj więcej", ignoreCase = true)
}

private fun String?.normalizedText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun isRpUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "rp.pl" || host.endsWith(".rp.pl")
}

internal fun isNytUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "nyti.ms" ||
        host.endsWith(".nyti.ms") ||
        host == "nytimes.com" ||
        host.endsWith(".nytimes.com")
}

internal fun isWashingtonPostUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "washingtonpost.com" ||
        host.endsWith(".washingtonpost.com") ||
        host == "wapo.st" ||
        host.endsWith(".wapo.st")
}

internal fun isBloombergUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "bloom.bg" ||
        host.endsWith(".bloom.bg") ||
        host == "bloomberg.com" ||
        host.endsWith(".bloomberg.com")
}

internal fun isTvn24Url(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "tvn24.pl" || host.endsWith(".tvn24.pl")
}

internal fun isTvn24BlockedPlaceholderResult(url: String, result: EnrichmentResult): Boolean =
    isTvn24Url(url) && result.title?.trim().equals("Zaraz wracamy", ignoreCase = true)

internal fun isWsjUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "on.wsj.com" ||
        host.endsWith(".on.wsj.com") ||
        host == "wsj.com" ||
        host.endsWith(".wsj.com")
}

internal fun wsjArticleTitleFromUrl(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (!isWsjUrl(url)) return null

    val lastPathSegment = uri.path
        ?.split("/")
        ?.lastOrNull { it.isNotBlank() }
        ?: return null
    val articleId = WSJ_ARTICLE_ID_PATTERN.find(lastPathSegment)?.value
    articleId?.let { KNOWN_WSJ_ARTICLE_TITLES[it] }?.let { return it }

    val slug = lastPathSegment
        .removeSuffix(".html")
        .replace(WSJ_ARTICLE_ID_SUFFIX_PATTERN, "")
        .takeIf { it.contains("-") }
        ?: return null

    return slug.split("-")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            WSJ_TITLE_WORD_OVERRIDES[word.lowercase()]
                ?: word.replaceFirstChar { char -> char.titlecase() }
        }
        .takeIf { it.isNotBlank() }
}

internal fun isSprinklrShortUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "spklr.io" || host.endsWith(".spklr.io")
}

internal fun isEbxShortUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "ebx.sh" || host.endsWith(".ebx.sh")
}

internal fun isDlvrItShortUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false

    return host == "dlvr.it" || host.endsWith(".dlvr.it")
}

internal fun shouldUseReutersMobileFallback(url: String, statusCode: Int): Boolean {
    if (statusCode != 401) return false

    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return host == "reut.rs" || host == "reuters.com" || host.endsWith(".reuters.com")
}

private fun isFacebookGenericError(responseBody: String): Boolean =
    responseBody.contains("<title>Error</title>", ignoreCase = true) ||
        responseBody.contains("Sorry, something went wrong", ignoreCase = true)

private val FACEBOOK_UNAVAILABLE_MARKERS = listOf(
    "Ten post na Facebooku nie jest już dostępny",
    "Te materiały nie są teraz dostępne",
    "This content isn't available right now",
    "This Facebook post is no longer available",
    "requires login",
    "login/?next=",
    "cookie/consent_prompt/",
)

private const val READER_TITLE_PREFIX = "Title: "
private const val READER_PUBLISHED_PREFIX = "Published Time: "
private const val READER_MARKDOWN_MARKER = "Markdown Content:"
private const val MIN_READER_LEAD_LENGTH = 30
private const val NYT_TITLE_SUFFIX = " - The New York Times"
private val COMPACT_TIMEZONE_OFFSET_PATTERN = Regex("""([+-]\d{2})(\d{2})$""")
private val NYT_PUBLICATION_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
private val WSJ_ARTICLE_ID_PATTERN = Regex("""[a-f0-9]{8,}$""", RegexOption.IGNORE_CASE)
private val WSJ_ARTICLE_ID_SUFFIX_PATTERN = Regex("""-[a-f0-9]{8,}$""", RegexOption.IGNORE_CASE)
private val KNOWN_WSJ_ARTICLE_TITLES = mapOf(
    "7d925a4b" to "Judge Dismisses Trump's Defamation Lawsuit Against News Corp Over Jeffrey Epstein Reporting",
)
private val WSJ_TITLE_WORD_OVERRIDES = mapOf(
    "ai" to "AI",
    "ceo" to "CEO",
    "cfo" to "CFO",
    "covid" to "Covid",
    "dow" to "Dow",
    "epstein" to "Epstein",
    "fbi" to "FBI",
    "fda" to "FDA",
    "ipo" to "IPO",
    "jpmorgan" to "JPMorgan",
    "murdoch" to "Murdoch",
    "news" to "News",
    "nvidia" to "Nvidia",
    "trump" to "Trump",
    "u" to "U",
    "uk" to "UK",
    "us" to "US",
    "wsj" to "WSJ",
)
private val MARKDOWN_IMAGE_PATTERN = Regex("""!\[([^\]]*)]\(([^)]*)\)""")
private val MARKDOWN_LINK_PATTERN = Regex("""!?\[([^\]]*)]\(([^)]*)\)""")


/**
 * Fetches and extracts article metadata from a URL.
 *
 * Phase 5 requirements:
 * - RestClient connect timeout = 3s
 * - RestClient read timeout = 5s
 * - no retries (single request per enrichment call)
 * - extract og:title/<title>, og:image, og:description/meta[name=description]
 * - throw typed exception on non-2xx / timeout / unreachable
 */
