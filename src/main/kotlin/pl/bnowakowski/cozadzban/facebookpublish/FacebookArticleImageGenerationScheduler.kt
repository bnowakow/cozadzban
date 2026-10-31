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
    private val pageSync: FacebookPageSyncRepository,
    private val claude: ClaudeSvgArticleImageGenerator? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val manualExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "facebook-image-manual").apply { isDaemon = true }
    }

    /** True when Codex or the Claude fallback can illustrate articles. */
    fun isEnabled(): Boolean = generator.isEnabled() || claude?.isEnabled() == true

    /** Start one article immediately without blocking the Vaadin request. */
    fun generateNow(articleId: Long): Boolean {
        if (!isEnabled()) return false
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
        if (!isEnabled()) return
        jobs.recoverInterrupted(MAX_ATTEMPTS)
        val missingContext = jobs.failMissingContext()
        if (missingContext > 0) log.info("Skipped {} image jobs with no article text", missingContext)
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
            when (val result = generateWithFallback(article, content)) {
                is ArticleImageGenerationResult.Generated -> {
                    when (jobs.complete(job.articleId)) {
                        "GENERATED" -> {
                            pageSync.markImageReplacementRequired(job.articleId)
                            log.info("Generated Facebook illustration for article {}", job.articleId)
                        }
                        "MISSING_THUMBNAIL" -> log.info(
                            "Article {} changed during image generation; queued another illustration", job.articleId)
                    }
                }
                is ArticleImageGenerationResult.UsageLimited -> {
                    jobs.pauseForUsageLimit(job.articleId, result.retryAt, result.reason)
                    log.warn("Facebook illustration paused for article {} ({})", job.articleId, result.reason)
                }
                is ArticleImageGenerationResult.Failed -> {
                    if (result.retryable) jobs.fail(job.articleId, MAX_ATTEMPTS, result.reason)
                    else jobs.failPermanent(job.articleId, result.reason)
                    log.warn("Facebook illustration not generated for article {} ({})", job.articleId, result.reason)
                }
            }
        } catch (ex: Exception) {
            jobs.fail(job.articleId, MAX_ATTEMPTS, ex.javaClass.simpleName)
            log.warn("Facebook illustration generation failed for article {} ({})", job.articleId, ex.javaClass.simpleName)
        }
    }

    /**
     * Codex first; when it can't deliver, Claude draws an SVG fallback. A Codex usage limit still
     * pauses the queue if Claude also fails, so the job keeps its attempts. When both are usage
     * limited, the earlier reset wins.
     */
    private fun generateWithFallback(
        article: pl.bnowakowski.cozadzban.article.Article,
        content: pl.bnowakowski.cozadzban.article.ArticleContent?,
    ): ArticleImageGenerationResult {
        val fallback = claude?.takeIf { it.isEnabled() }
        if (!generator.isEnabled()) {
            return fallback?.generate(article, content)
                ?: ArticleImageGenerationResult.Failed("Image generation is disabled.", retryable = false)
        }
        val codex = generator.generate(article, content)
        if (codex is ArticleImageGenerationResult.Generated || fallback == null) return codex
        if (codex is ArticleImageGenerationResult.Failed && codex.reason == MISSING_IMAGE_CONTEXT_REASON) return codex
        log.info("Codex could not illustrate article {} ({}); trying Claude", article.id, describe(codex))
        val claudeResult = fallback.generate(article, content)
        return when {
            claudeResult is ArticleImageGenerationResult.Generated -> claudeResult
            codex is ArticleImageGenerationResult.UsageLimited && claudeResult is ArticleImageGenerationResult.UsageLimited ->
                ArticleImageGenerationResult.UsageLimited(
                    "${codex.reason} ${claudeResult.reason}".take(200),
                    minOf(codex.retryAt, claudeResult.retryAt),
                )
            codex is ArticleImageGenerationResult.UsageLimited -> codex
            codex is ArticleImageGenerationResult.Failed ->
                codex.copy(reason = "${codex.reason} Claude: ${describe(claudeResult)}".take(200))
            else -> codex
        }
    }

    private fun describe(result: ArticleImageGenerationResult): String = when (result) {
        is ArticleImageGenerationResult.Generated -> "generated"
        is ArticleImageGenerationResult.Failed -> result.reason
        is ArticleImageGenerationResult.UsageLimited -> result.reason
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
