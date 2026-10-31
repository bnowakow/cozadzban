// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import javax.imageio.ImageIO

class GeneratedArticleImageControllerTest {
    @TempDir
    lateinit var assetDir: Path

    private val jobs: FacebookArticleImageGenerationRepository = mock()
    private lateinit var mvc: MockMvc
    private val version = 1_760_000_000_000L

    @BeforeEach
    fun setUp() {
        val generator = CodexArticleImageGenerator(false, 180, assetDir.toString())
        mvc = MockMvcBuilders.standaloneSetup(GeneratedArticleImageController(jobs, generator)).build()
        val png = assetDir.resolve("article-5.png")
        ImageIO.write(BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB), "png", png.toFile())
        Files.setLastModifiedTime(png, FileTime.fromMillis(version))
        whenever(jobs.status(5)).thenReturn("GENERATED")
    }

    @Test
    fun `versioned web image is a JPEG cached for a year`() {
        val result = mvc.get("/generated-images/5.jpg?v=$version").andReturn().response

        assertEquals(200, result.status)
        assertEquals("image/jpeg", result.contentType)
        val cacheControl = result.getHeader("Cache-Control").orEmpty()
        assertTrue(cacheControl.contains("max-age=31536000"), cacheControl)
        assertTrue(cacheControl.contains("immutable"), cacheControl)
        assertTrue(cacheControl.contains("public"), cacheControl)
        assertEquals(0xFF.toByte(), result.contentAsByteArray[0])
    }

    @Test
    fun `unversioned or stale-version image gets a short cache and Last-Modified`() {
        for (url in listOf("/generated-images/5.png", "/generated-images/5.jpg?v=1")) {
            val result = mvc.get(url).andReturn().response
            assertEquals(200, result.status, url)
            val cacheControl = result.getHeader("Cache-Control").orEmpty()
            assertTrue(cacheControl.contains("max-age=600"), "$url: $cacheControl")
            assertTrue(!cacheControl.contains("immutable"), "$url: $cacheControl")
            assertTrue(result.getHeader("Last-Modified").orEmpty().isNotBlank(), url)
        }
        assertEquals("image/png", mvc.get("/generated-images/5.png").andReturn().response.contentType)
    }

    @Test
    fun `revalidation of an unchanged image returns 304 without a body`() {
        val lastModified = mvc.get("/generated-images/5.png").andReturn().response.getHeader("Last-Modified")!!

        val result = mvc.get("/generated-images/5.png") { header("If-Modified-Since", lastModified) }
            .andReturn().response

        assertEquals(304, result.status)
        assertEquals(0, result.contentAsByteArray.size)
    }

    @Test
    fun `images that are not generated return 404`() {
        whenever(jobs.status(6)).thenReturn("FAILED")
        assertEquals(404, mvc.get("/generated-images/6.jpg").andReturn().response.status)
        whenever(jobs.status(7)).thenReturn("GENERATED")
        assertEquals(404, mvc.get("/generated-images/7.png").andReturn().response.status)
    }
}
