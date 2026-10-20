// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

data class DiscoveredFacebookPage(val id: String, val name: String, val tasks: Set<String>, val token: String)

@Component
class FacebookPageGraphClient(private val properties: FacebookPageProperties) {
    private val client = RestClient.builder().baseUrl(properties.graphBaseUrl.trimEnd('/')).build()
    private val json = ObjectMapper()

    fun authorizationUrl(state: String): String = UriComponentsBuilder
        .fromUriString("${properties.authorizationBaseUrl.trimEnd('/')}/${properties.graphVersion}/dialog/oauth")
        .queryParam("client_id", properties.appId)
        .queryParam("redirect_uri", properties.redirectUri)
        .queryParam("response_type", "code")
        .queryParam("config_id", properties.configurationId)
        .queryParam("state", state)
        .build().encode().toUriString()

    fun discoverPages(code: String): List<DiscoveredFacebookPage> {
        val shortToken = exchangeCode(code)
        val longToken = exchangeLongLivedToken(shortToken)
        val result = mutableListOf<DiscoveredFacebookPage>()
        var cursor: String? = null
        repeat(10) {
            val url = UriComponentsBuilder.fromPath("/${properties.graphVersion}/me/accounts")
                .queryParam("fields", "id,name,access_token,tasks")
                .queryParam("limit", 100)
                .apply { cursor?.let { queryParam("after", it) } }
                .build().encode().toUriString()
            val body = client.get().uri(url).headers { it.setBearerAuth(longToken) }
                .retrieve().body(String::class.java) ?: error("Empty Meta Pages response")
            val root = json.readTree(body)
            root.path("data").forEach { item ->
                val id = item.path("id").asText()
                val name = item.path("name").asText()
                val token = item.path("access_token").asText()
                if (id.isNotBlank() && name.isNotBlank() && token.isNotBlank()) {
                    val tasks = item.path("tasks").map { task -> task.asText() }.toSet()
                    result.add(DiscoveredFacebookPage(id, name, tasks, token))
                }
            }
            cursor = root.path("paging").path("cursors").path("after").asText().takeIf { it.isNotBlank() }
            if (cursor == null) return result
        }
        error("Meta returned too many Page result pages")
    }

    private fun exchangeCode(code: String): String {
        val body = LinkedMultiValueMap<String, String>().apply {
            add("client_id", properties.appId)
            add("client_secret", properties.appSecret)
            add("redirect_uri", properties.redirectUri)
            add("code", code)
        }
        val response = client.post().uri("/${properties.graphVersion}/oauth/access_token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
            .retrieve().body(String::class.java) ?: error("Empty Meta token response")
        return json.readTree(response).path("access_token").asText().takeIf { it.isNotBlank() }
            ?: error("Meta did not return a user token")
    }

    private fun exchangeLongLivedToken(shortToken: String): String {
        val body = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "fb_exchange_token")
            add("client_id", properties.appId)
            add("client_secret", properties.appSecret)
            add("fb_exchange_token", shortToken)
        }
        val response = client.post().uri("/${properties.graphVersion}/oauth/access_token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body)
            .retrieve().body(String::class.java) ?: error("Empty Meta long-lived token response")
        return json.readTree(response).path("access_token").asText().takeIf { it.isNotBlank() }
            ?: error("Meta did not return a long-lived user token")
    }
}
