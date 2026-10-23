// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

@Component
class FacebookPageSyncGraphClient(private val properties: FacebookPageProperties) {
    private val json = ObjectMapper()
    private val client = RestClient.builder()
        .baseUrl(properties.graphBaseUrl.trimEnd('/'))
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(20_000) })
        .build()

    fun photoId(postId: String, token: String): String {
        val response = client.get().uri("/${properties.graphVersion}/$postId?fields=object_id")
            .headers { it.setBearerAuth(token) }.retrieve().body(String::class.java)
            ?: error("Meta returned an empty post response")
        return json.readTree(response).path("object_id").asText().takeIf { it.matches(Regex("[0-9]+")) }
            ?: error("The Page post has no editable photo object")
    }

    fun updatePhotoCaption(photoId: String, token: String, caption: String) {
        val body = LinkedMultiValueMap<String, String>().apply { add("caption", caption) }
        val response = client.post().uri("/${properties.graphVersion}/$photoId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
            .retrieve().body(String::class.java) ?: error("Meta returned an empty photo update response")
        require(json.readTree(response).path("success").asBoolean(false)) { "Meta did not confirm the photo update" }
    }

    fun deletePost(postId: String, token: String) {
        val response = client.delete().uri("/${properties.graphVersion}/$postId")
            .headers { it.setBearerAuth(token) }
            .retrieve().body(String::class.java) ?: error("Meta returned an empty delete response")
        require(json.readTree(response).path("success").asBoolean(false)) { "Meta did not confirm the post deletion" }
    }
}
