// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class StorySitemapController(private val articles: ArticleRepository) {
    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): ResponseEntity<String> {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<?xml-stylesheet type=\"text/xsl\" href=\"/sitemap.xsl\"?>\n")
            append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
            listOf(
                "https://cozadzban.pl/",
                "https://cozadzban.pl/?lang=pl",
                "https://cozadzban.pl/?lang=en",
                "https://cozadzban.pl/privacy-policy.html",
                "https://cozadzban.pl/user-data-deletion.html",
            )
                .forEach { url -> append("<url><loc>${url.replace("&", "&amp;")}</loc></url>") }
            articles.findForRss(null).forEach { article ->
                article.id?.let { id ->
                    append("<url><loc>https://cozadzban.pl/stories/$id</loc></url>")
                }
            }
            append("</urlset>")
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml)
    }
}
