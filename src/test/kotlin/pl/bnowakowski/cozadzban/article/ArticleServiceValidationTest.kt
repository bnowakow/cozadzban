// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.article

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.enrichment.EnrichmentService
import pl.bnowakowski.cozadzban.user.AppUserRepository
import java.util.Optional

class ArticleServiceValidationTest {
    private val articles: ArticleRepository = mock()
    private val enrichment: EnrichmentService = mock()
    private val content: ArticleContentRepository = mock()
    private val service = ArticleService(articles, enrichment, mock<AppUserRepository>(), content)

    @Test
    fun `invalid patches fail before enrichment or writes`() {
        whenever(articles.findById(1L)).thenReturn(Optional.of(
            Article(id = 1L, url = "https://example.com/story", language = "en", quote = "Keep me", createdByUserId = 7L),
        ))
        val invalidPatches = listOf(
            mapOf("quote" to 42),
            mapOf("quote" to listOf("invalid")),
            mapOf("quote" to "x".repeat(2001)),
            mapOf("content" to false),
            mapOf("content" to mapOf("text" to "invalid")),
            mapOf("language" to "en-abcdefgh-abcdefgh-ab"),
            mapOf("url" to "https://example.com/" + "x".repeat(2048)),
            mapOf("url" to "https:///missing-host"),
            mapOf("url" to "relative/path"),
            mapOf("url" to "ftp://example.com/story"),
            mapOf("url" to "https://example.com/bad path"),
        )
        invalidPatches.forEach { patch ->
            assertThrows(IllegalArgumentException::class.java, { service.patch(1L, patch) }, patch.toString().take(100))
        }
        verify(articles, never()).save(any())
        verifyNoInteractions(enrichment, content)
    }

    @Test
    fun `invalid create URLs fail before queries enrichment or writes`() {
        listOf("relative", "https:///missing-host", "https://example.com/bad path", "ftp://example.com").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                service.create(ArticleInput(url = url, language = "en"), 7L)
            }
        }
        verifyNoInteractions(articles, enrichment, content)
    }

    @Test
    fun `documented maximum lengths remain valid`() {
        val url = "https://example.com/".let { it + "x".repeat(2048 - it.length) }
        assertEquals(url, ArticleService.canonicalizeUrl(url))
        val language = "en-abcdefgh-abcdefgh"
        val quote = "x".repeat(2000)
        whenever(articles.findById(1L)).thenReturn(Optional.of(
            Article(id = 1L, url = url, language = "en", createdByUserId = 7L),
        ))
        whenever(articles.save(any())).thenAnswer { it.arguments[0] as Article }

        val saved = service.patch(1L, mapOf("language" to language, "quote" to quote))

        assertEquals(language, saved.language)
        assertEquals(quote, saved.quote)
        verifyNoInteractions(enrichment, content)
    }

    @Test
    fun `explicit null still clears quote and preserved content`() {
        whenever(articles.findById(1L)).thenReturn(Optional.of(
            Article(id = 1L, url = "https://example.com/story", language = "en", quote = "Clear me", createdByUserId = 7L),
        ))
        whenever(articles.save(any())).thenAnswer { it.arguments[0] as Article }

        val saved = service.patch(1L, mapOf("quote" to null, "content" to null))

        assertNull(saved.quote)
        verify(content).deleteByArticleId(1L)
        verifyNoInteractions(enrichment)
    }
}
