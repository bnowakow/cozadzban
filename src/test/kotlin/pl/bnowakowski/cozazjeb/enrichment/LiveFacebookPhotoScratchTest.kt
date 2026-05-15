// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.client.RestClient

@EnabledIfEnvironmentVariable(named = "RUN_LIVE_FACEBOOK_PHOTO_SCRATCH", matches = "true")
class LiveFacebookPhotoScratchTest {

    @Test
    fun `print live Facebook photo enrichment`() {
        val service = EnrichmentService(RestClient.builder())
        val fetchHtml = EnrichmentService::class.java.getDeclaredMethod("fetchHtml", String::class.java, RestClient::class.java)
        fetchHtml.isAccessible = true
        val restClientField = EnrichmentService::class.java.getDeclaredField("restClient")
        restClientField.isAccessible = true
        val restClient = restClientField.get(service) as RestClient
        listOf(
            "https://www.facebook.com/photo/?fbid=1386997413458759&set=a.473737708118072",
            "https://www.facebook.com/photo/?fbid=1306021968296170&set=a.567191575512550",
            "https://m.facebook.com/photo.php?fbid=1386997413458759&set=a.473737708118072",
            "https://mbasic.facebook.com/photo.php?fbid=1386997413458759&set=a.473737708118072",
        ).forEach { url ->
            val result = service.enrich(url)
            val html = fetchHtml.invoke(service, url, restClient) as String
            val imageMatches = Regex("""https:\\/\\/[^"]+?\.(?:jpg|jpeg|png|webp)(?:\?[^"]*)?""")
                .findAll(html)
                .map { it.value.replace("\\/", "/") }
                .filterNot { it.contains("static.xx.fbcdn.net/rsrc.php") }
                .distinct()
                .take(10)
                .toList()
            val fbidIndex = html.indexOf(url.substringAfter("fbid=").substringBefore("&"))
            val fbidContext = if (fbidIndex >= 0) {
                html.substring((fbidIndex - 300).coerceAtLeast(0), (fbidIndex + 700).coerceAtMost(html.length))
            } else {
                "<not-found>"
            }
            println(
                "LIVE_FB_PHOTO url=$url title=${result.title} thumbnail=${result.thumbnail} " +
                    "lead=${result.lead} publishedAt=${result.publishedAt} plainTextLength=${result.plainText?.length}",
            )
            println("LIVE_FB_PHOTO_IMAGE_MATCHES url=$url matches=$imageMatches")
            println("LIVE_FB_PHOTO_FBID_CONTEXT url=$url context=$fbidContext")
        }
    }
}
