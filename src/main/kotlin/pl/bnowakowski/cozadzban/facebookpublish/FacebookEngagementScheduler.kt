// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

@Component
class FacebookEngagementScheduler(
    private val pages: FacebookPageConnectionService,
    private val repository: FacebookEngagementRepository,
    private val graph: FacebookEngagementGraphClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    fun evaluate() {
        repository.recoverInterruptedComments()
        pages.all().filter { it.enabled }.forEach { page ->
            try {
                val token = pages.tokenForEnabledLanguage(page.language) ?: return@forEach
                val cutoff = Instant.now().minusSeconds(page.schedulerIntervalMinutes.toLong() * 60)
                repository.dueReactions(page.pageId, cutoff).forEach { target ->
                    try {
                        repository.recordReaction(target.publicationId, graph.reactionCount(target.postId, token), null)
                    } catch (ex: Exception) {
                        repository.recordReaction(target.publicationId, null, safeError(ex))
                    }
                }
                if (repository.dueReactions(page.pageId, cutoff, 1).isEmpty()) {
                    repository.reserveHighestRanked(page, Instant.now())
                }
            } catch (ex: Exception) {
                log.warn("Facebook engagement evaluation failed for Page {}: {}", page.pageId, safeError(ex))
            }
        }
        pages.all().filter { it.enabled }.forEach { page ->
          for (index in 0 until 10) {
            val attempt = repository.claimComment(page, Instant.now()) ?: break
            val token = try { pages.tokenForEnabledLanguage(attempt.language) } catch (_: Exception) { null }
            val activePage = pages.all().firstOrNull {
                it.language == attempt.language && it.pageId == attempt.pageId && it.enabled
            }
            if (token == null || activePage == null) {
                repository.markCommentOutcome(attempt.id, "FAILED", "The connected Page or token changed; reconnect and retry.")
                continue
            }
            try {
                val commentId = graph.addComment(attempt.postId, token, attempt.storyUrl)
                repository.confirmComment(attempt.id, commentId)
                log.info("Added article link comment for Facebook post {}", attempt.postId)
            } catch (ex: RestClientResponseException) {
                repository.markCommentOutcome(attempt.id,
                    if (ex.statusCode.is4xxClientError && ex.statusCode.value() != 429) "FAILED" else "NEEDS_RECONCILIATION",
                    safeError(ex))
            } catch (ex: Exception) {
                repository.markCommentOutcome(attempt.id, "NEEDS_RECONCILIATION", safeError(ex))
            }
          }
        }
    }

    private fun safeError(ex: Exception): String = when (ex) {
        is RestClientResponseException -> "Meta HTTP ${ex.statusCode.value()}"
        else -> ex.javaClass.simpleName
    }
}
