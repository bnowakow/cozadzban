// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException
import pl.bnowakowski.cozadzban.ui.WarsawTime
import java.time.Instant

@Component
class FacebookSourceReactionScheduler(
    private val properties: FacebookSourceProperties,
    private val repository: FacebookSourceReactionRepository,
    private val graph: FacebookSourceEngagementGraphClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = ObjectMapper()
    @Volatile private var accessRetryAt: Instant = Instant.EPOCH

    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    fun refreshDue() {
        if (!properties.configured || Instant.now().isBefore(accessRetryAt)) return
        val targets = repository.due(Instant.now(), 5)
        targets.forEach { target ->
            val now = Instant.now()
            val reference = FacebookSourcePostReference.fromUrl(target.sourceUrl)
            if (reference == null) {
                repository.record(target, FacebookSourceReactionStatus.UNSUPPORTED, null, null,
                    "This Facebook URL is not a Page post permalink", now, now.plusSeconds(30L * 86_400))
                return@forEach
            }
            try {
                val measurement = graph.measure(reference, target.publishedAt, target.graphPostId)
                repository.record(target, FacebookSourceReactionStatus.SUCCESS, measurement.reactionCount,
                    measurement.graphPostId, null, now, now.plusSeconds(3_600))
            } catch (ex: Exception) {
                val error = safeError(ex)
                repository.record(target, FacebookSourceReactionStatus.FAILED, null, target.graphPostId,
                    error, now, now.plusSeconds(6 * 3_600))
                log.warn("Source Facebook reaction check failed for article {} at {}: {}",
                    target.articleId, WarsawTime.forLog(now), error)
                if (ex is RestClientResponseException && accessDenied(ex)) {
                    accessRetryAt = now.plusSeconds(6 * 3_600)
                    return
                }
            }
        }
    }

    private fun safeError(ex: Exception): String = when (ex) {
        is RestClientResponseException -> {
            val code = metaErrorCode(ex)
            if (code == null) "Meta HTTP ${ex.statusCode.value()}"
            else "Meta error $code (HTTP ${ex.statusCode.value()})"
        }
        is FacebookSourcePostNotFoundException -> ex.message ?: "Post not found in the public Page feed"
        is IllegalArgumentException -> ex.message?.take(150) ?: "Invalid Meta response"
        else -> ex.javaClass.simpleName
    }

    private fun accessDenied(ex: RestClientResponseException): Boolean =
        ex.statusCode.value() in setOf(401, 403) || metaErrorCode(ex) in setOf(10, 190, 200, 283)

    private fun metaErrorCode(ex: RestClientResponseException): Int? =
        runCatching { json.readTree(ex.responseBodyAsString).path("error").path("code") }
            .getOrNull()?.takeIf { it.isIntegralNumber }?.asInt()
}
