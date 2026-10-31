// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.article

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Shortens over-long titles, newest first, a few per minute. New articles are picked up on the next
 * run and older ones work through the backlog. The original title stays in article_title_shortening.
 */
@Component
class ArticleTitleShorteningScheduler(
    private val shortener: ArticleTitleShortener,
    private val jobs: ArticleTitleShorteningRepository,
    private val articles: ArticleRepository,
    private val contents: ArticleContentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var pausedUntil: Instant = Instant.EPOCH

    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    fun shortenPending() {
        if (!shortener.isEnabled() || Instant.now().isBefore(pausedUntil)) return
        val reapplied = jobs.reapplyShortened()
        if (reapplied > 0) log.info("Restored {} shortened titles after the long ones came back", reapplied)
        repeat(BATCH_SIZE) {
            val job = jobs.claim(shortener.maxLength, Instant.now().plus(LEASE)) ?: return
            if (!process(job)) return
        }
    }

    /** Returns false when the batch should stop because the models are usage limited. */
    internal fun process(job: ArticleTitleShorteningJob): Boolean {
        try {
            val article = articles.findById(job.articleId).orElse(null) ?: return true
            if (article.title != job.originalTitle) return true
            val content = contents.findById(job.articleId).orElse(null)
            when (val result = shortener.shorten(article, content)) {
                is TitleShorteningResult.Shortened -> {
                    if (jobs.complete(job, result.title, result.tool)) {
                        log.info("Shortened title of article {} with {} ({} -> {} chars)",
                            job.articleId, result.tool, job.originalTitle.length, result.title.length)
                    }
                }
                is TitleShorteningResult.UsageLimited -> {
                    jobs.pause(job, result.retryAt, result.reason)
                    pausedUntil = result.retryAt
                    log.warn("Title shortening paused until {} ({})", result.retryAt, result.reason)
                    return false
                }
                is TitleShorteningResult.Failed -> {
                    jobs.fail(job, MAX_ATTEMPTS, Instant.now().plus(RETRY_DELAY.multipliedBy(job.attemptCount + 1L)), result.reason)
                    log.warn("Title of article {} not shortened ({})", job.articleId, result.reason)
                }
            }
        } catch (ex: Exception) {
            jobs.fail(job, MAX_ATTEMPTS, Instant.now().plus(RETRY_DELAY), ex.javaClass.simpleName)
            log.warn("Title shortening failed for article {} ({})", job.articleId, ex.javaClass.simpleName)
        }
        return true
    }

    private companion object {
        const val BATCH_SIZE = 5
        const val MAX_ATTEMPTS = 3
        val LEASE: Duration = Duration.ofMinutes(30)
        val RETRY_DELAY: Duration = Duration.ofMinutes(15)
    }
}
