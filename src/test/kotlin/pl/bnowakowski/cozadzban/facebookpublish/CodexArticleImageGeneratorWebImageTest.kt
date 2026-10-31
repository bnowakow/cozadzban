// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Random
import javax.imageio.ImageIO

class CodexArticleImageGeneratorWebImageTest {
    @TempDir
    lateinit var assetDir: Path

    private val generator by lazy { CodexArticleImageGenerator(false, 180, assetDir.toString()) }

    @Test
    fun `web image is a smaller JPEG copy and the original PNG is kept unchanged`() {
        val png = writePng(7, seed = 1)
        val original = Files.readAllBytes(png)

        val jpeg = generator.webImage(7)

        assertNotNull(jpeg)
        assertEquals(0xFF.toByte(), jpeg!![0])
        assertEquals(0xD8.toByte(), jpeg[1])
        assertTrue(jpeg.size < original.size, "JPEG ${jpeg.size} bytes should be smaller than PNG ${original.size}")
        val decoded = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(1200, decoded.width)
        assertEquals(630, decoded.height)
        assertArrayEquals(original, Files.readAllBytes(png))
        assertArrayEquals(original, generator.storedPng(7))
        assertTrue(Files.isRegularFile(assetDir.resolve("article-7.jpg")))
    }

    @Test
    fun `web image is reused until the PNG changes`() {
        val png = writePng(8, seed = 2)
        Files.setLastModifiedTime(png, FileTime.from(Instant.parse("2026-10-01T00:00:00Z")))
        val first = generator.webImage(8)!!
        val jpgPath = assetDir.resolve("article-8.jpg")
        // A marker proves the second call reads the stored copy instead of converting again.
        Files.write(jpgPath, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(jpgPath, Files.getLastModifiedTime(png))
        assertArrayEquals(byteArrayOf(1, 2, 3), generator.webImage(8))

        writePng(8, seed = 3)
        Files.setLastModifiedTime(png, FileTime.from(Instant.parse("2026-10-02T00:00:00Z")))
        val rebuilt = generator.webImage(8)!!

        assertEquals(0xFF.toByte(), rebuilt[0])
        assertTrue(!rebuilt.contentEquals(first), "regenerated PNG must produce a new JPEG")
        assertEquals(Files.getLastModifiedTime(png), Files.getLastModifiedTime(jpgPath))
    }

    @Test
    fun `version and URL follow the stored PNG and are absent without one`() {
        assertNull(generator.imageVersion(9))
        assertNull(generator.webImageUrl(9))
        assertNull(generator.webImage(9))
        assertNull(generator.storedPng(9))

        val png = writePng(9, seed = 4)
        Files.setLastModifiedTime(png, FileTime.fromMillis(1_760_000_000_000))

        assertEquals(1_760_000_000_000, generator.imageVersion(9))
        assertEquals("/generated-images/9.jpg?v=1760000000000", generator.webImageUrl(9))
    }

    private fun writePng(articleId: Long, seed: Long): Path {
        // Noise keeps the PNG large, like real generated illustrations.
        val image = BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB)
        val random = Random(seed)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, random.nextInt(0xFFFFFF))
        }
        val path = assetDir.resolve("article-$articleId.png")
        ImageIO.write(image, "png", path.toFile())
        return path
    }
}
