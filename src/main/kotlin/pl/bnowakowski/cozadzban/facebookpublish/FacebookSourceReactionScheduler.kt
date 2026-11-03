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

    @Scheduled(initialDelay = 90_000, fixedDelay = 60_000)
    fun refreshDue() {
        if (!properties.configured) return
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
            }
        }
    }

    private fun safeError(ex: Exception): String = when (ex) {
        is RestClientResponseException -> {
            val code = runCatching { json.readTree(ex.responseBodyAsString).path("error").path("code") }
                .getOrNull()?.takeIf { it.isIntegralNumber }?.asInt()
            if (code == null) "Meta HTTP ${ex.statusCode.value()}"
            else "Meta error $code (HTTP ${ex.statusCode.value()})"
        }
        is FacebookSourcePostNotFoundException -> ex.message ?: "Post not found in the public Page feed"
        is IllegalArgumentException -> ex.message?.take(150) ?: "Invalid Meta response"
        else -> ex.javaClass.simpleName
    }
}
