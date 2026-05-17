// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

@Service
class DownloadedFaviconCache(
    restClientBuilder: RestClient.Builder,
    @Value("\${cozazjeb.favicon-cache-dir:data/favicons}") cacheDir: String,
) : FaviconCache {

    private val cachePath: Path = Path.of(cacheDir).toAbsolutePath().normalize()
    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.5")
        .build()

    override fun cache(articleUrl: String, candidates: List<String>): String? {
        val normalized = candidates
            .asSequence()
            .mapNotNull { normalizeCandidate(articleUrl, it) }
            .distinct()
            .toList()
        if (normalized.isEmpty()) return null

        try {
            Files.createDirectories(cachePath)
        } catch (ex: IOException) {
            LOG.debug("Favicon cache directory unavailable; path={}", cachePath, ex)
            return null
        }
        for (candidate in normalized) {
            val cached = cacheCandidate(candidate)
            if (cached != null) return cached
        }
        return null
    }

    private fun normalizeCandidate(articleUrl: String, candidate: String): URI? {
        val base = runCatching { URI(articleUrl) }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(candidate).normalize() }.getOrNull() ?: return null
        val scheme = resolved.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        return resolved
    }

    private fun cacheCandidate(uri: URI): String? {
        return try {
            val body = restClient.get()
                .uri(uri)
                .retrieve()
                .body(ByteArray::class.java)
                ?: return null
            if (body.isEmpty() || body.size > MAX_FAVICON_BYTES) return null

            val extension = faviconExtension(uri, body) ?: return null
            val filename = "${sha256(uri.toString().toByteArray(Charsets.UTF_8) + body)}.$extension"
            val target = cachePath.resolve(filename).normalize()
            if (!target.startsWith(cachePath)) return null
            if (!Files.exists(target)) {
                Files.write(target, body)
            }
            "/favicons/$filename"
        } catch (ex: RestClientException) {
            LOG.debug("Favicon download failed; uri={}", uri, ex)
            null
        } catch (ex: IllegalArgumentException) {
            LOG.debug("Favicon candidate rejected; uri={}", uri, ex)
            null
        } catch (ex: IOException) {
            LOG.debug("Favicon cache write failed; uri={}; path={}", uri, cachePath, ex)
            null
        }
    }

    private fun faviconExtension(uri: URI, body: ByteArray): String? =
        when {
            body.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
            body.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
            body.startsWithAscii("GIF87a") || body.startsWithAscii("GIF89a") -> "gif"
            body.startsWithAscii("RIFF") && body.size >= 12 && body.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp"
            body.startsWith(0x00, 0x00, 0x01, 0x00) || body.startsWith(0x00, 0x00, 0x02, 0x00) -> "ico"
            body.asTextStart().contains("<svg", ignoreCase = true) -> "svg"
            else -> extensionFromPath(uri)
        }?.takeIf { it in ALLOWED_EXTENSIONS }

    private fun extensionFromPath(uri: URI): String? =
        uri.path
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it == "jpeg") "jpg" else it }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

    private fun ByteArray.startsWithAscii(expected: String): Boolean =
        size >= expected.length && copyOfRange(0, expected.length).toString(Charsets.US_ASCII) == expected

    private fun ByteArray.asTextStart(): String =
        copyOfRange(0, minOf(size, 256)).toString(Charsets.UTF_8)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_FAVICON_BYTES = 512 * 1024
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val ALLOWED_EXTENSIONS = setOf("png", "jpg", "gif", "webp", "ico", "svg")
        private val LOG = LoggerFactory.getLogger(DownloadedFaviconCache::class.java)
    }
}
