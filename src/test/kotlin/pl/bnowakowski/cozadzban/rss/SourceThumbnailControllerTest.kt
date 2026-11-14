// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.rss

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.context.request.WebRequest
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.facebookpublish.CodexArticleImageGenerator
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import java.util.Optional

class SourceThumbnailControllerTest {
    @Test
    fun `serves a stored copy without fetching the failed source again`() {
        val article = Article(id = 7, url = "https://example.com/story", language = "en",
            thumbnail = "https://example.com/image.jpg", createdByUserId = 1)
        val articles: ArticleRepository = mock()
        val cache: SourceThumbnailCache = mock()
        whenever(articles.findById(7)).thenReturn(Optional.of(article))
        whenever(cache.loadOrFetch(article)).thenReturn(CachedSourceThumbnail(byteArrayOf(1, 2, 3), MediaType.IMAGE_JPEG, 1_000))
        val controller = SourceThumbnailController(articles, cache, mock<FacebookArticleImageGenerationRepository>(), mock<CodexArticleImageGenerator>())

        val response = controller.image(7, mock<WebRequest>())

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(MediaType.IMAGE_JPEG, response.headers.contentType)
        assertArrayEquals(byteArrayOf(1, 2, 3), response.body)
    }
}
