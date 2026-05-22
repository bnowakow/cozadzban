// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Service
class LanguageFlagCache(
    restClientBuilder: RestClient.Builder,
    @Value("\${cozadzban.language-flag-cache-dir:data/flags}") cacheDir: String,
) {

    private val cachePath: Path = Path.of(cacheDir).toAbsolutePath().normalize()
    private val restClient: RestClient = restClientBuilder
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setReadTimeout(READ_TIMEOUT_MS)
            }
        )
        .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "image/svg+xml,image/*;q=0.8,*/*;q=0.5")
        .build()

    // Languages we've already failed to fetch this process — avoid hammering flagcdn.
    private val misses = ConcurrentHashMap.newKeySet<String>()

    fun flagPath(language: String?): String? {
        val normalized = language?.trim()?.lowercase() ?: return null
        if (!LANGUAGE_PATTERN.matches(normalized)) return null
        val country = LANGUAGE_TO_COUNTRY[normalized] ?: return null
        val filename = "$country.svg"
        val target = cachePath.resolve(filename).normalize()
        if (!target.startsWith(cachePath)) return null
        if (Files.isRegularFile(target)) return "/flags/$filename"
        if (normalized in misses) return null
        return download(country, target, normalized)
    }

    private fun download(country: String, target: Path, language: String): String? {
        try {
            Files.createDirectories(cachePath)
            val body = restClient.get()
                .uri("https://flagcdn.com/$country.svg")
                .retrieve()
                .body(ByteArray::class.java)
            if (body == null || body.isEmpty() || body.size > MAX_FLAG_BYTES) {
                misses.add(language)
                return null
            }
            if (!body.asTextStart().contains("<svg", ignoreCase = true)) {
                misses.add(language)
                return null
            }
            val tmp = Files.createTempFile(cachePath, country, ".svg.tmp")
            Files.write(tmp, body)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return "/flags/${target.fileName}"
        } catch (ex: RestClientResponseException) {
            LOG.debug(
                "Flag download failed; language={}; country={}; status={}; exception={}: {}",
                language,
                country,
                ex.statusCode.value(),
                ex.javaClass.simpleName,
                ex.message,
            )
            misses.add(language)
            return null
        } catch (ex: RestClientException) {
            LOG.debug(
                "Flag download failed; language={}; country={}; exception={}: {}",
                language,
                country,
                ex.javaClass.simpleName,
                ex.message,
            )
            misses.add(language)
            return null
        } catch (ex: IOException) {
            LOG.debug(
                "Flag cache write failed; language={}; country={}; path={}; exception={}: {}",
                language,
                country,
                cachePath,
                ex.javaClass.simpleName,
                ex.message,
            )
            misses.add(language)
            return null
        }
    }

    private fun ByteArray.asTextStart(): String =
        copyOfRange(0, minOf(size, 256)).toString(Charsets.UTF_8)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_FLAG_BYTES = 256 * 1024
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private val LANGUAGE_PATTERN = Regex("[a-z]{2,3}")
        private val LOG = LoggerFactory.getLogger(LanguageFlagCache::class.java)

        // ISO 639-1/-3 language code → ISO 3166-1 alpha-2 country code used by flagcdn.com.
        // Picks the most common country for each language. "en" → "us" preserves the existing
        // US-stripes rendering; switch to "gb" if you'd rather show the Union Jack.
        private val LANGUAGE_TO_COUNTRY: Map<String, String> = mapOf(
            "ar" to "sa",
            "be" to "by",
            "bg" to "bg",
            "ca" to "es",
            "cs" to "cz",
            "da" to "dk",
            "de" to "de",
            "el" to "gr",
            "en" to "us",
            "es" to "es",
            "et" to "ee",
            "fa" to "ir",
            "fi" to "fi",
            "fr" to "fr",
            "ga" to "ie",
            "he" to "il",
            "hi" to "in",
            "hr" to "hr",
            "hu" to "hu",
            "id" to "id",
            "is" to "is",
            "it" to "it",
            "ja" to "jp",
            "ko" to "kr",
            "lt" to "lt",
            "lv" to "lv",
            "mk" to "mk",
            "ms" to "my",
            "nl" to "nl",
            "no" to "no",
            "pl" to "pl",
            "pt" to "pt",
            "ro" to "ro",
            "ru" to "ru",
            "sk" to "sk",
            "sl" to "si",
            "sq" to "al",
            "sr" to "rs",
            "sv" to "se",
            "th" to "th",
            "tr" to "tr",
            "uk" to "ua",
            "vi" to "vn",
            "zh" to "cn",
        )
    }
}
