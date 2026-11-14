// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.rss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import pl.bnowakowski.cozadzban.article.Article
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.random.Random

class SourceThumbnailCacheTest {
    @TempDir lateinit var directory: Path

    private fun cache() = SourceThumbnailCache(mock(), RestClient.builder(), directory.toString())

    @Test
    fun `large source image becomes a smaller website copy`() {
        val image = BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)
        val random = Random(42)
        for (y in 0 until image.height) for (x in 0 until image.width) image.setRGB(x, y, random.nextInt())
        val original = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

        val compact = cache().compact(original, "png")

        assertTrue(compact.size < original.size)
        assertEquals(960, ImageIO.read(ByteArrayInputStream(compact)).width)
    }

    @Test
    fun `cached image remains usable without its source and changes with the URL`() {
        val url = "https://unreachable.invalid/photo.jpg"
        val article = Article(id = 7, url = "https://example.com/story", language = "en", thumbnail = url, createdByUserId = 1)
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()))
        Files.write(directory.resolve("$key.jpg"), bytes)

        assertEquals(MediaType.IMAGE_JPEG, cache().loadOrFetch(article)?.contentType)
        assertNotNull(cache().load(article))
        assertNull(cache().load(article.copy(thumbnail = "https://unreachable.invalid/other.jpg")))
    }
}
