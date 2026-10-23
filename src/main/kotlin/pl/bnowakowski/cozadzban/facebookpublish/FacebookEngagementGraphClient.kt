// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

@Component
class FacebookEngagementGraphClient(private val properties: FacebookPageProperties) {
    private val json = ObjectMapper()
    private val client = RestClient.builder()
        .baseUrl(properties.graphBaseUrl.trimEnd('/'))
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(20_000) })
        .build()

    fun reactionCount(postId: String, token: String): Long {
        val response = client.get()
            .uri("/${properties.graphVersion}/$postId?fields=reactions.limit(0).summary(total_count)")
            .headers { it.setBearerAuth(token) }.retrieve().body(String::class.java)
            ?: error("Meta returned an empty reaction response")
        val count = json.readTree(response).path("reactions").path("summary").path("total_count")
        require(count.isIntegralNumber && count.asLong() >= 0) { "Meta did not return a reaction total" }
        return count.asLong()
    }

    fun addComment(postId: String, token: String, storyUrl: String): String {
        val body = LinkedMultiValueMap<String, String>().apply { add("message", storyUrl) }
        val response = client.post().uri("/${properties.graphVersion}/$postId/comments")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
            .retrieve().body(String::class.java) ?: error("Meta returned an empty comment response")
        return json.readTree(response).path("id").asText().takeIf { it.isNotBlank() }
            ?: error("Meta accepted the comment without returning its ID")
    }
}
