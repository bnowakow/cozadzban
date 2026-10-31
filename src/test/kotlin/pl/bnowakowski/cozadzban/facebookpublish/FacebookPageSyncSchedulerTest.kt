// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.time.Instant
import java.util.Optional

class FacebookPageSyncSchedulerTest {
    private val repository: FacebookPageSyncRepository = mock()
    private val pages: FacebookPageConnectionService = mock()
    private val articles: ArticleRepository = mock()
    private val graph: FacebookPageSyncGraphClient = mock()
    private val images: FacebookArticleImageGenerationRepository = mock()
    private val generator: CodexArticleImageGenerator = mock()
    private val scheduler = FacebookPageSyncScheduler(repository, pages, articles, graph, images, generator)

    private fun job(replacementRequired: Boolean, imageSource: String = "PLACEHOLDER") = FacebookSyncJob(
        id = 7, publicationId = 3, articleId = 538, pageId = "page-1", language = "pl", postId = "post-1",
        photoId = "photo-1", action = "UPDATE", imageSource = imageSource, replacementRequired = replacementRequired,
    )

    private fun setUp(job: FacebookSyncJob) {
        whenever(repository.claim()).thenReturn(job, null)
        whenever(pages.tokenForEnabledLanguage("pl")).thenReturn("token")
        whenever(pages.all()).thenReturn(listOf(
            FacebookPageConnection("pl", "page-1", "Page", true, "Europe/Warsaw", 5, 24, 60, Instant.EPOCH, Instant.EPOCH),
        ))
        whenever(articles.findById(538)).thenReturn(Optional.of(
            Article(id = 538, url = "https://example.com/a", language = "pl", title = "Tytuł", lead = "Lead", createdByUserId = 1),
        ))
    }

    @Test
    fun `replacing a placeholder with a generated image adds the AI disclosure to the caption`() {
        setUp(job(replacementRequired = true))
        whenever(images.status(538)).thenReturn("GENERATED")
        whenever(generator.cached(538)).thenReturn(byteArrayOf(1, 2, 3))
        whenever(graph.replacePostPhoto(eq("page-1"), eq("post-1"), eq("token"), any())).thenReturn("photo-2")

        scheduler.synchronize()

        val caption = argumentCaptor<String>()
        verify(graph).updatePostMessage(eq("post-1"), eq("token"), caption.capture())
        assertTrue(caption.firstValue.endsWith("Ilustracja wygenerowana przez AI."), caption.firstValue)
        verify(repository).confirm(7, "UPDATE", "photo-2")
    }

    @Test
    fun `a text-only edit keeps the caption for the current image`() {
        setUp(job(replacementRequired = false))

        scheduler.synchronize()

        val caption = argumentCaptor<String>()
        verify(graph).updatePostMessage(eq("post-1"), eq("token"), caption.capture())
        assertFalse(caption.firstValue.contains("wygenerowana przez AI"))
        verify(graph, never()).replacePostPhoto(any(), any(), any(), any())
    }

    @Test
    fun `without a generated image the text is updated and the photo is left for manual review`() {
        setUp(job(replacementRequired = true))
        whenever(images.status(538)).thenReturn("FAILED")

        scheduler.synchronize()

        val caption = argumentCaptor<String>()
        verify(graph).updatePostMessage(eq("post-1"), eq("token"), caption.capture())
        assertFalse(caption.firstValue.contains("wygenerowana przez AI"))
        verify(graph, never()).replacePostPhoto(any(), any(), any(), any())
        verify(repository).manualReplacementRequired(eq(7), any())
    }
}
