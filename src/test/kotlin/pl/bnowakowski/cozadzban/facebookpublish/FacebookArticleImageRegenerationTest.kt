// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository

class FacebookArticleImageRegenerationTest {
    @Test
    fun `manual regeneration stores normalized optional direction with the claimed job`() {
        val jobs: FacebookArticleImageGenerationRepository = mock()
        val generator: CodexArticleImageGenerator = mock()
        whenever(generator.isEnabled()).thenReturn(true)
        val scheduler = FacebookArticleImageGenerationScheduler(
            jobs, mock<ArticleRepository>(), mock<ArticleContentRepository>(), generator, mock<FacebookPageSyncRepository>(),
        )

        assertFalse(scheduler.regenerateNow(763, "  blue   background\nwith a chip "))

        verify(jobs).claimManualRegeneration(763, "blue background with a chip")
        assertThrows(IllegalArgumentException::class.java) { scheduler.regenerateNow(763, "x".repeat(501)) }
        scheduler.stopManualWorker()
    }
}
