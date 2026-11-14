// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.rss

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

data class CachedSourceThumbnail(val bytes: ByteArray, val contentType: MediaType, val modifiedAt: Long)

/** Stores a compact site copy on the shared data volume, keyed by the current source URL. */
@Service
class SourceThumbnailCache(
    private val metaImages: MetaSourceImageCache,
    restClientBuilder: RestClient.Builder,
    @Value("\${app.source-thumbnail-cache-dir:/app/data/source-thumbnails}") cacheDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directory = Path.of(cacheDir).toAbsolutePath().normalize()
    private val client = restClientBuilder
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(3_000)
            setReadTimeout(8_000)
        })
        .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
        .defaultHeader(HttpHeaders.ACCEPT, "image/avif,image/webp,image/png,image/jpeg,image/gif,image/*;q=0.8")
        .build()

    fun load(article: Article): CachedSourceThumbnail? {
        val url = article.thumbnail?.trim()?.takeIf(FacebookArticleImageGenerationRepository::hasUsableSourceThumbnail)
            ?: return null
        val key = key(url)
        for ((extension, type) in IMAGE_TYPES) {
            val path = directory.resolve("$key.$extension")
            val cached = runCatching {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) !in 1L..MAX_IMAGE_BYTES) null
                else Files.readAllBytes(path).takeIf { detect(it)?.second == extension }
                    ?.let { CachedSourceThumbnail(it, type, Files.getLastModifiedTime(path).toMillis()) }
            }.getOrNull()
            if (cached != null) return cached
        }
        return null
    }

    fun loadOrFetch(article: Article): CachedSourceThumbnail? {
        load(article)?.let { return it }
        runCatching { refresh(article) }
        return load(article)
    }

    /** A failed refresh leaves the last good copy available for readers. */
    fun refresh(article: Article): String? {
        val url = article.thumbnail?.trim()?.takeIf(FacebookArticleImageGenerationRepository::hasUsableSourceThumbnail)
            ?: return "Invalid image URL"
        val result = if (metaImages.supports(url)) {
            val image = metaImages.loadOrFetch(article)
            if (image == null) null to "Source image unavailable" else image.bytes to null
        } else {
            download(url)
        }
        val original = result.first ?: return result.second ?: "Source image unavailable"
        val detected = detect(original) ?: return "Response is not a supported image"
        val compact = compact(original, detected.second)
        val selected = detect(compact) ?: return "Could not prepare image copy"
        return try {
            val key = key(url)
            Files.createDirectories(directory)
            val destination = directory.resolve("$key.${selected.second}")
            val temporary = Files.createTempFile(directory, "$key-", ".tmp")
            try {
                Files.write(temporary, compact)
                try {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                IMAGE_TYPES.keys.filter { it != selected.second }.forEach { Files.deleteIfExists(directory.resolve("$key.$it")) }
            } finally {
                Files.deleteIfExists(temporary)
            }
            null
        } catch (ex: Exception) {
            log.warn("Could not cache source thumbnail for article {} ({})", article.id, ex.javaClass.simpleName)
            ex.javaClass.simpleName
        }
    }

    private fun download(url: String): Pair<ByteArray?, String?> = try {
        client.get().uri(url).exchange { _, response ->
            if (!response.statusCode.is2xxSuccessful) return@exchange null to "HTTP ${response.statusCode.value()}"
            val bytes = response.body.readNBytes(MAX_IMAGE_BYTES + 1)
            when {
                bytes.isEmpty() -> null to "Empty image response"
                bytes.size > MAX_IMAGE_BYTES -> null to "Image too large"
                detect(bytes) == null -> null to "Response is not a supported image"
                else -> bytes to null
            }
        }
    } catch (ex: Exception) {
        null to ex.javaClass.simpleName
    }

    internal fun compact(original: ByteArray, extension: String): ByteArray {
        if (extension !in setOf("jpg", "png", "gif")) return original
        return runCatching {
            val image = ImageIO.createImageInputStream(ByteArrayInputStream(original)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@runCatching original
                try {
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return@runCatching original
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
            val scale = minOf(1.0, WEB_WIDTH.toDouble() / image.width)
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = canvas.createGraphics()
            try {
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(image, 0, 0, width, height, null)
            } finally {
                graphics.dispose()
            }
            val output = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            try {
                ImageIO.createImageOutputStream(output).use { stream ->
                    writer.output = stream
                    val parameters = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = 0.78f
                    }
                    writer.write(null, IIOImage(canvas, null, null), parameters)
                }
            } finally {
                writer.dispose()
            }
            output.toByteArray().takeIf { it.size < original.size } ?: original
        }.getOrDefault(original)
    }

    private fun detect(bytes: ByteArray): Pair<MediaType, String>? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            MediaType.IMAGE_JPEG to "jpg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
            MediaType.IMAGE_PNG to "png"
        bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
            MediaType.IMAGE_GIF to "gif"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" ->
            MediaType.parseMediaType("image/webp") to "webp"
        bytes.size >= 12 && String(bytes, 4, 8, Charsets.US_ASCII) == "ftypavif" ->
            MediaType.parseMediaType("image/avif") to "avif"
        else -> null
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 10_000_000
        private const val MAX_PIXELS = 20_000_000L
        private const val WEB_WIDTH = 960
        private val IMAGE_TYPES = linkedMapOf(
            "jpg" to MediaType.IMAGE_JPEG, "png" to MediaType.IMAGE_PNG,
            "gif" to MediaType.IMAGE_GIF, "webp" to MediaType.parseMediaType("image/webp"),
            "avif" to MediaType.parseMediaType("image/avif"),
        )

        fun webUrl(articleId: Long, sourceUrl: String): String =
            "/source-images/$articleId?v=${key(sourceUrl.trim()).take(16)}"

        private fun key(url: String): String = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        )
    }
}
