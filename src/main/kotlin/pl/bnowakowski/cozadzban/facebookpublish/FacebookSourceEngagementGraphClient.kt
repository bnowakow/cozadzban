// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Instant

data class FacebookSourceReactionMeasurement(val graphPostId: String, val reactionCount: Long)

class FacebookSourcePostNotFoundException : RuntimeException("Post not found in the public Page feed")

/** Reads aggregate counts only; it never requests people who reacted. */
@Component
class FacebookSourceEngagementGraphClient(private val properties: FacebookSourceProperties) {
    private val json = ObjectMapper()
    private val client = RestClient.builder()
        .baseUrl(properties.graphBaseUrl.trimEnd('/'))
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(10_000) })
        .build()

    fun measure(
        reference: FacebookSourcePostReference,
        publishedAt: Instant?,
        knownGraphPostId: String?,
    ): FacebookSourceReactionMeasurement {
        require(properties.configured) { "Source Meta app is not configured" }
        val candidateId = knownGraphPostId ?: reference.directGraphId
        if (candidateId != null) {
            try {
                return readPost(candidateId)
            } catch (ex: RestClientResponseException) {
                // Public Page posts can be readable on the Page edge even when Meta
                // disallows direct Post-node reads with an app token.
                if (ex.statusCode.value() !in setOf(400, 403, 404)) throw ex
            }
        }
        return findPagePost(reference, publishedAt, knownGraphPostId)
    }

    private fun readPost(graphPostId: String): FacebookSourceReactionMeasurement {
        require(graphPostId.matches(Regex("[0-9]+_[0-9]+"))) { "Invalid Graph post ID" }
        val response = client.get().uri { builder ->
            builder.pathSegment(properties.graphVersion, graphPostId)
                .queryParam("fields", FIELDS).build()
        }.headers { it.setBearerAuth(properties.appToken) }
            .retrieve().body(String::class.java) ?: error("Meta returned an empty post response")
        val node = json.readTree(response)
        require(node.path("id").asText() == graphPostId) { "Meta returned a different Graph post ID" }
        return FacebookSourceReactionMeasurement(graphPostId, count(node))
    }

    private fun findPagePost(
        reference: FacebookSourcePostReference,
        publishedAt: Instant?,
        knownGraphPostId: String?,
    ): FacebookSourceReactionMeasurement {
        val pageIdentifier = knownGraphPostId?.substringBefore('_')
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: reference.pageIdentifier
        var after: String? = null
        repeat(MAX_PAGE_REQUESTS) {
            val currentAfter = after
            val response = client.get().uri { builder ->
                builder.pathSegment(properties.graphVersion, pageIdentifier, "posts")
                    .queryParam("fields", FIELDS)
                    .queryParam("limit", 100)
                    .apply {
                        if (publishedAt != null) {
                            queryParam("since", publishedAt.minusSeconds(3 * 86_400).epochSecond)
                            queryParam("until", publishedAt.plusSeconds(3 * 86_400).epochSecond)
                        }
                        if (currentAfter != null) queryParam("after", currentAfter)
                    }.build()
            }.headers { it.setBearerAuth(properties.appToken) }
                .retrieve().body(String::class.java) ?: error("Meta returned an empty Page posts response")
            val root = json.readTree(response)
            val posts = root.path("data")
            require(posts.isArray) { "Meta did not return a Page posts list" }
            posts.firstOrNull { matches(reference, knownGraphPostId, it) }?.let { post ->
                val graphId = post.path("id").asText()
                require(graphId.matches(Regex("[0-9]+_[0-9]+"))) { "Meta returned an invalid Graph post ID" }
                return FacebookSourceReactionMeasurement(graphId, count(post))
            }
            val next = root.path("paging").path("next").asText("")
            val nextAfter = root.path("paging").path("cursors").path("after").asText("")
            if (next.isBlank() || nextAfter.isBlank() || nextAfter == after) throw FacebookSourcePostNotFoundException()
            after = nextAfter
        }
        throw FacebookSourcePostNotFoundException()
    }

    private fun matches(reference: FacebookSourcePostReference, knownGraphPostId: String?, post: JsonNode): Boolean {
        val id = post.path("id").asText("")
        if (knownGraphPostId != null && id == knownGraphPostId) return true
        if (reference.postIdentifier.all(Char::isDigit) && id.substringAfterLast('_') == reference.postIdentifier) {
            return true
        }
        val permalink = post.path("permalink_url").asText("")
        return FacebookSourcePostReference.fromUrl(permalink)?.postIdentifier == reference.postIdentifier
    }

    private fun count(post: JsonNode): Long {
        val value = post.path("reactions").path("summary").path("total_count")
        require(value.isIntegralNumber && value.asLong() >= 0) { "Meta did not return a reaction total" }
        return value.asLong()
    }

    companion object {
        private const val FIELDS = "id,permalink_url,reactions.limit(0).summary(total_count)"
        private const val MAX_PAGE_REQUESTS = 5
    }
}
