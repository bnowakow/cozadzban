// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingRequestWrapper
import java.nio.charset.Charset
import kotlin.math.roundToLong

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ArticleWriteRequestLoggingFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !isArticleWrite(request)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val wrappedRequest = ContentCachingRequestWrapper(request, MAX_CACHED_REQUEST_BYTES)
        val started = System.nanoTime()
        logExchangeStarted(request)
        var thrown: Throwable? = null
        try {
            filterChain.doFilter(wrappedRequest, response)
        } catch (ex: Throwable) {
            thrown = ex
            throw ex
        } finally {
            val durationMs = ((System.nanoTime() - started) / 1_000_000.0).roundToLong()
            val message =
                "Article write HTTP exchange; method={}; uri={}; query={}; status={}; durationMs={}; " +
                    "requestContentType={}; requestContentLength={}; cachedRequestBody={}; responseLocation={}; " +
                    "m2mHeaderPresent={}; authorizationHeaderPresent={}; importRequestId={}; authAfterChain={}; exception={}"
            val args = arrayOf(
                request.method,
                request.requestURI,
                request.queryString ?: "absent",
                response.status,
                durationMs,
                request.contentType ?: "absent",
                request.contentLengthLong,
                bodyDiagnostic(wrappedRequest),
                response.getHeader("Location") ?: "absent",
                request.getHeader(MACHINE_HEADER_NAME) != null,
                request.getHeader("Authorization") != null,
                importRequestId(request),
                authenticationDiagnostic(),
                thrown?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "absent",
            )
            if (durationMs >= SLOW_ARTICLE_WRITE_WARN_MS || thrown != null || importRequestId(request) != "absent") {
                LOG.warn(message, *args)
            } else {
                LOG.debug(message, *args)
            }
        }
    }

    private fun logExchangeStarted(request: HttpServletRequest) {
        val message = "Article write HTTP exchange started; method={}; uri={}; query={}; requestContentType={}; " +
            "requestContentLength={}; m2mHeaderPresent={}; authorizationHeaderPresent={}; importRequestId={}; " +
            "remoteAddr={}; forwardedFor={}"
        val args = arrayOf(
            request.method,
            request.requestURI,
            request.queryString ?: "absent",
            request.contentType ?: "absent",
            request.contentLengthLong,
            request.getHeader(MACHINE_HEADER_NAME) != null,
            request.getHeader("Authorization") != null,
            importRequestId(request),
            request.remoteAddr ?: "absent",
            request.getHeader("X-Forwarded-For") ?: "absent",
        )
        if (importRequestId(request) != "absent") {
            LOG.info(message, *args)
        } else {
            LOG.debug(message, *args)
        }
    }

    private fun isArticleWrite(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PATCH") &&
            (request.requestURI == "/api/articles" || request.requestURI.startsWith("/api/articles/"))

    private fun importRequestId(request: HttpServletRequest): String =
        request.getHeader(IMPORT_REQUEST_ID_HEADER)?.takeIf { it.isNotBlank() } ?: "absent"

    private fun bodyDiagnostic(request: ContentCachingRequestWrapper): String {
        val bytes = request.contentAsByteArray
        if (bytes.isEmpty()) {
            return "not-cached-or-empty"
        }

        val charset = runCatching {
            Charset.forName(request.characterEncoding ?: Charsets.UTF_8.name())
        }.getOrDefault(Charsets.UTF_8)
        val text = String(bytes, charset)
        val keys = jsonKeys(text)
        if (keys.isNotEmpty()) {
            return "json(keys=${keys.joinToString(",")};" +
                "url=${valueDiagnostic(jsonStringField(text, JSON_URL_PATTERN))};" +
                "content=${valueDiagnostic(jsonStringField(text, JSON_CONTENT_PATTERN))};" +
                "publishedAt=${jsonPublishedAtDiagnostic(text)})"
        }

        return "raw(len=${text.length},excerpt='${text.cleanForLog().take(LOG_EXCERPT_LENGTH)}')"
    }

    private fun jsonKeys(text: String): Set<String> {
        if (!text.trimStart().startsWith("{")) return emptySet()
        return JSON_KEY_PATTERN
            .findAll(text)
            .map { unescapeJsonString(it.groupValues[1]) }
            .toSortedSet()
    }

    private fun jsonStringField(text: String, pattern: Regex): String? =
        pattern.find(text)
            ?.groupValues
            ?.get(1)
            ?.let { unescapeJsonString(it) }

    private fun jsonPublishedAtDiagnostic(text: String): String =
        JSON_PUBLISHED_AT_PATTERN.find(text)
            ?.let {
                if (it.groupValues[1] == "null") {
                    "null"
                } else {
                    valueDiagnostic(unescapeJsonString(it.groupValues[2]))
                }
            }
            ?: "absent"

    private fun unescapeJsonString(value: String): String =
        value
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

    private fun authenticationDiagnostic(): String {
        val authentication = SecurityContextHolder.getContext().authentication ?: return "absent"
        return "name='${authentication.name}',authenticated=${authentication.isAuthenticated}," +
            "type=${authentication.javaClass.simpleName}"
    }

    private fun valueDiagnostic(value: String?): String =
        value
            ?.cleanForLog()
            ?.takeIf { it.isNotBlank() }
            ?.let { "present(len=${it.length},excerpt='${it.take(LOG_EXCERPT_LENGTH)}')" }
            ?: "absent"

    private fun String.cleanForLog(): String =
        replace(LOG_WHITESPACE_PATTERN, " ").trim()

    companion object {
        private val LOG = LoggerFactory.getLogger(ArticleWriteRequestLoggingFilter::class.java)
        private val LOG_WHITESPACE_PATTERN = Regex("""\s+""")
        private val JSON_KEY_PATTERN = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:")
        private val JSON_URL_PATTERN = Regex("\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private val JSON_CONTENT_PATTERN = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        private val JSON_PUBLISHED_AT_PATTERN = Regex("\"publishedAt\"\\s*:\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")")
        private const val LOG_EXCERPT_LENGTH = 500
        private const val MAX_CACHED_REQUEST_BYTES = 32 * 1024
        private const val MACHINE_HEADER_NAME = "X-CoZaDzban-M2M-Key"
        private const val IMPORT_REQUEST_ID_HEADER = "X-CoZaDzban-Import-Request-Id"
        private const val SLOW_ARTICLE_WRITE_WARN_MS = 30_000
    }
}
