// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.http.client.MultipartBodyBuilder

data class PublishedFacebookPhoto(val postId: String?, val photoId: String?, val permalink: String?)

@Component
class FacebookPhotoPublisher(private val properties: FacebookPageProperties) {
    private val json = ObjectMapper()
    private val client = RestClient.builder()
        .baseUrl(properties.graphBaseUrl.trimEnd('/'))
        .requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(5_000); setReadTimeout(20_000) })
        .build()

    fun publish(pageId: String, token: String, caption: String, image: FacebookPostImage): PublishedFacebookPhoto {
        val body = MultipartBodyBuilder().apply {
            part("caption", caption)
            part("published", "true")
            when (image) {
                is FacebookPostImage.Thumbnail -> part("url", image.url)
                is FacebookPostImage.Placeholder -> part("source", object : ByteArrayResource(image.png) {
                    override fun getFilename() = "co-za-dzban.png"
                }).contentType(MediaType.IMAGE_PNG)
            }
        }.build()
        val response = client.post().uri("/${properties.graphVersion}/$pageId/photos")
            .headers { it.setBearerAuth(token) }
            .contentType(MediaType.MULTIPART_FORM_DATA).body(body)
            .retrieve().body(String::class.java) ?: error("Meta returned an empty photo response")
        val root = json.readTree(response)
        val photoId = root.path("id").asText().takeIf { it.isNotBlank() }
        val postId = root.path("post_id").asText().takeIf { it.isNotBlank() }
            ?: photoId?.let { resolvePostId(it, token) }
        val permalink = postId?.let { resolvePermalink(it, pageId, token) }
        return PublishedFacebookPhoto(postId, photoId, permalink)
    }

    private fun resolvePostId(photoId: String, token: String): String? = runCatching {
        val response = client.get().uri("/${properties.graphVersion}/$photoId?fields=page_story_id")
            .headers { it.setBearerAuth(token) }.retrieve().body(String::class.java)
        json.readTree(response).path("page_story_id").asText().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun resolvePermalink(postId: String, pageId: String, token: String): String {
        val fromGraph = runCatching {
            val response = client.get().uri("/${properties.graphVersion}/$postId?fields=permalink_url")
                .headers { it.setBearerAuth(token) }.retrieve().body(String::class.java)
            json.readTree(response).path("permalink_url").asText().takeIf { it.startsWith("https://www.facebook.com/") }
        }.getOrNull()
        return fromGraph ?: "https://www.facebook.com/permalink.php?story_fbid=${postId.substringAfterLast('_')}&id=$pageId"
    }
}
