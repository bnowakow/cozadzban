// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.http.client.MultipartBodyBuilder

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

    fun updatePostMessage(postId: String, token: String, caption: String) {
        val body = LinkedMultiValueMap<String, String>().apply { add("message", caption) }
        val response = client.post().uri("/${properties.graphVersion}/$postId")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
            .retrieve().body(String::class.java) ?: error("Meta returned an empty post update response")
        require(json.readTree(response).path("success").asBoolean(false)) { "Meta did not confirm the post update" }
    }

    /** Upload a private Page photo, then ask Meta to replace the existing post attachment. */
    fun replacePostPhoto(pageId: String, postId: String, token: String, png: ByteArray): String {
        val upload = MultipartBodyBuilder().apply {
            part("published", "false")
            part("source", object : ByteArrayResource(png) {
                override fun getFilename() = "co-za-dzban-updated-illustration.png"
            }).contentType(MediaType.IMAGE_PNG)
        }.build()
        val uploadResponse = client.post().uri("/${properties.graphVersion}/$pageId/photos")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.MULTIPART_FORM_DATA).body(upload)
            .retrieve().body(String::class.java) ?: error("Meta returned an empty photo upload response")
        val photoId = json.readTree(uploadResponse).path("id").asText()
            .takeIf { it.matches(Regex("[0-9]+")) } ?: error("Meta did not return an uploaded photo ID")
        try {
            val body = LinkedMultiValueMap<String, String>().apply {
                add("attached_media[0]", "{\"media_fbid\":\"$photoId\"}")
            }
            val response = client.post().uri("/${properties.graphVersion}/$postId")
                .headers { it.setBearerAuth(token) }
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
                .retrieve().body(String::class.java) ?: error("Meta returned an empty attachment update response")
            require(json.readTree(response).path("success").asBoolean(false)) {
                "Meta did not confirm the attachment update"
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode.is4xxClientError) {
                runCatching {
                    client.delete().uri("/${properties.graphVersion}/$photoId")
                        .headers { it.setBearerAuth(token) }.retrieve().toBodilessEntity()
                }
            }
            throw ex
        }
        return photoId
    }

    fun deletePost(postId: String, token: String) {
        val response = client.delete().uri("/${properties.graphVersion}/$postId")
            .headers { it.setBearerAuth(token) }
            .retrieve().body(String::class.java) ?: error("Meta returned an empty delete response")
        require(json.readTree(response).path("success").asBoolean(false)) { "Meta did not confirm the post deletion" }
    }
}
