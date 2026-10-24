// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Checks every minute; the database allows only one image-generation claim per ten minutes. */
@Component
class FacebookArticleImageGenerationScheduler(
    private val jobs: FacebookArticleImageGenerationRepository,
    private val articles: ArticleRepository,
    private val contents: ArticleContentRepository,
    private val generator: CodexArticleImageGenerator,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val manualExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "facebook-image-manual").apply { isDaemon = true }
    }

    fun isEnabled(): Boolean = generator.isEnabled()

    /** Start one article immediately without blocking the Vaadin request. */
    fun generateNow(articleId: Long): Boolean {
        if (!generator.isEnabled()) return false
        val job = jobs.claimManual(articleId) ?: return false
        return try {
            manualExecutor.execute { process(job) }
            true
        } catch (_: RejectedExecutionException) {
            jobs.fail(articleId, MAX_ATTEMPTS, "Image worker is stopping.")
            false
        }
    }

    @PreDestroy
    fun stopManualWorker() {
        manualExecutor.shutdownNow()
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    fun generateOne() {
        if (!generator.isEnabled()) return
        jobs.recoverInterrupted(MAX_ATTEMPTS)
        val job = jobs.claim() ?: return
        process(job)
    }

    private fun process(job: FacebookArticleImageJob) {
        try {
            val article = articles.findById(job.articleId).orElse(null)
            if (article == null) {
                jobs.fail(job.articleId, MAX_ATTEMPTS, "Article no longer exists.")
                return
            }
            val content = contents.findById(job.articleId).orElse(null)
            if (generator.generate(article, content) == null) {
                jobs.fail(job.articleId, MAX_ATTEMPTS, "Codex did not produce a usable image.")
                return
            }
            if (jobs.complete(job.articleId)) {
                log.info("Generated Facebook illustration for article {}", job.articleId)
            }
        } catch (ex: Exception) {
            jobs.fail(job.articleId, MAX_ATTEMPTS, ex.javaClass.simpleName)
            log.warn("Facebook illustration generation failed for article {} ({})", job.articleId, ex.javaClass.simpleName)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
