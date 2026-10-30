// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.rss

import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.net.URI
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

data class SavedMetaImage(val bytes: ByteArray, val contentType: MediaType, val extension: String)

/** Keeps Meta-hosted source thumbnails on the shared data volume after their remote URLs expire. */
@Service
class MetaSourceImageCache(
    private val articles: ArticleRepository,
    restClientBuilder: RestClient.Builder,
    @Value("\${app.meta-source-image-cache-dir:data/meta-source-images}") cacheDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directory = Path.of(cacheDir).toAbsolutePath().normalize()
    private val client = restClientBuilder
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3_000)
            setReadTimeout(8_000)
        })
        .defaultHeader(HttpHeaders.USER_AGENT, GOOGLEBOT_USER_AGENT)
        .defaultHeader(HttpHeaders.ACCEPT, "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8,text/html;q=0.5")
        .build()

    fun supports(url: String): Boolean = isMetaImageUrl(url)

    fun loadOrFetch(article: Article): SavedMetaImage? {
        val url = article.thumbnail?.trim()?.takeIf(::supports) ?: return null
        val key = hash(url)
        read(key)?.let { return it }

        val candidates = sequenceOf(url) + recoveryUrls(article, url)
        for (candidate in candidates.distinct()) {
            val image = download(candidate) ?: continue
            save(key, image)
            return image
        }
        markFailed(key)
        log.warn("Meta source image unavailable for article {}", article.id)
        return null
    }

    /** Save a few images per minute even if nobody opens the article before a signed URL expires. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    fun backfill() {
        var attempted = 0
        for (article in articles.findForRss(null)) {
            val url = article.thumbnail?.trim()?.takeIf(::supports) ?: continue
            val key = hash(url)
            if (hasSaved(key) || recentlyFailed(key)) continue
            loadOrFetch(article)
            if (++attempted == 3) break
        }
    }

    private fun recoveryUrls(article: Article, original: String): Sequence<String> = sequence {
        val source = runCatching { URI(article.url) }.getOrNull() ?: return@sequence
        if (isInstagramPageUrl(source)) {
            val preview = previewImage(source)
            if (preview != null) yield(preview)
            return@sequence
        }
        if (!isFacebookPageUrl(source)) return@sequence
        val photoId = source.rawQuery.orEmpty().split('&')
            .firstOrNull { it.substringBefore('=') == "fbid" }
            ?.substringAfter('=', "")
            ?.takeIf { it.matches(Regex("[0-9]{5,25}")) }
        val path = source.path.orEmpty()
        if (photoId != null && path.trimEnd('/') in setOf("/photo", "/photo.php")) {
            yield("https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=$photoId")
            val preview = previewImage(source)
            if (preview != null) yield(preview)
            return@sequence
        }
        if (path.startsWith("/reel/") || "/videos/" in path) {
            val preview = previewImage(source)
            if (preview != null) yield(preview)
            return@sequence
        }

        val pluginUrl = "https://www.facebook.com/plugins/post.php?href=" +
            URLEncoder.encode(article.url, Charsets.UTF_8) + "&show_text=true&width=500"
        val html = runCatching { client.get().uri(URI.create(pluginUrl)).retrieve().body(String::class.java) }.getOrNull()
        val images = html?.let { Jsoup.parse(it, pluginUrl).select("img.scaledImageFitWidth[src], img.scaledImage[src]")
            .map { image -> image.absUrl("src") }
            .filter(::isMetaImageUrl)
            .distinct()
        }.orEmpty()
        val originalFilename = runCatching { URI(original).path.substringAfterLast('/') }.getOrNull()
        val matching = images.filter { candidate ->
            originalFilename?.takeIf { it.isNotBlank() } ==
                runCatching { URI(candidate).path.substringAfterLast('/') }.getOrNull()
        }
        val selected = when {
            matching.isNotEmpty() -> matching
            images.size == 1 -> images
            else -> emptyList()
        }
        yieldAll(selected)
        val preview = previewImage(source)
        if (preview != null) yield(preview)
    }

    private fun previewImage(page: URI): String? {
        val html = runCatching { client.get().uri(page).retrieve().body(String::class.java) }.getOrNull()
            ?: return null
        return Jsoup.parse(html, page.toString()).selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf(::isMetaImageUrl)
    }

    private fun download(url: String): SavedMetaImage? = try {
        client.get().uri(URI.create(url)).exchange { _, response ->
            if (!response.statusCode.is2xxSuccessful) return@exchange null
            val bytes = response.body.readNBytes(MAX_IMAGE_BYTES + 1)
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@exchange null
            classify(bytes)
        }
    } catch (_: Exception) {
        null
    }

    private fun classify(bytes: ByteArray): SavedMetaImage? {
        val kind = when {
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> MediaType.IMAGE_JPEG to "jpg"
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> MediaType.IMAGE_PNG to "png"
            bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> MediaType.IMAGE_GIF to "gif"
            bytes.startsWithAscii("RIFF") && bytes.size >= 12 &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" ->
                MediaType.parseMediaType("image/webp") to "webp"
            bytes.size >= 12 && bytes.copyOfRange(4, 12).toString(Charsets.US_ASCII) == "ftypavif" ->
                MediaType.parseMediaType("image/avif") to "avif"
            else -> return null
        }
        return SavedMetaImage(bytes, kind.first, kind.second)
    }

    private fun read(key: String): SavedMetaImage? {
        for (extension in EXTENSIONS) {
            val path = directory.resolve("$key.$extension")
            if (!Files.isRegularFile(path)) continue
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: continue
            classify(bytes)?.takeIf { it.extension == extension }?.let { return it }
        }
        return null
    }

    private fun hasSaved(key: String): Boolean = EXTENSIONS.any { Files.isRegularFile(directory.resolve("$key.$it")) }

    private fun save(key: String, image: SavedMetaImage) {
        var temporary: Path? = null
        try {
            Files.createDirectories(directory)
            val file = Files.createTempFile(directory, "$key-", ".tmp")
            temporary = file
            Files.write(file, image.bytes)
            val destination = directory.resolve("$key.${image.extension}")
            try {
                Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(directory.resolve("$key.failed"))
        } catch (ex: Exception) {
            log.warn("Could not save Meta source image for cache key {}: {}", key, ex.javaClass.simpleName)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun markFailed(key: String) {
        runCatching {
            Files.createDirectories(directory)
            Files.write(directory.resolve("$key.failed"), byteArrayOf())
        }
    }

    private fun recentlyFailed(key: String): Boolean = runCatching {
        Files.getLastModifiedTime(directory.resolve("$key.failed")).toInstant()
            .isAfter(Instant.now().minus(Duration.ofHours(24)))
    }.getOrDefault(false)

    private fun hash(url: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)))

    private fun ByteArray.startsWith(vararg expected: Int): Boolean =
        size >= expected.size && expected.indices.all { this[it].toInt() and 0xFF == expected[it] }

    private fun ByteArray.startsWithAscii(expected: String): Boolean =
        size >= expected.length && copyOfRange(0, expected.length).toString(Charsets.US_ASCII) == expected

    private companion object {
        const val MAX_IMAGE_BYTES = 15 * 1024 * 1024
        val EXTENSIONS = listOf("jpg", "png", "gif", "webp", "avif")
        const val GOOGLEBOT_USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

        fun isMetaImageUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                (host == "lookaside.fbsbx.com" || host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
                    host == "cdninstagram.com" || host.endsWith(".cdninstagram.com"))
        }

        fun isFacebookPageUrl(uri: URI): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                (host == "facebook.com" || host.endsWith(".facebook.com"))
        }

        fun isInstagramPageUrl(uri: URI): Boolean {
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) &&
                (host == "instagram.com" || host.endsWith(".instagram.com"))
        }
    }
}
