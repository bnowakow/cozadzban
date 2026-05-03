// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
@RequestMapping("/api/articles")
class ArticleController(private val articleService: ArticleService) {

    @GetMapping
    fun listArticles(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "createdAt,desc") sort: String,
    ): ArticlePage = articleService.findPage(page, size, sort)

    @GetMapping("/{id}")
    fun getArticle(@PathVariable id: Long): Article = articleService.findById(id)

    @PostMapping
    fun createArticle(@Valid @RequestBody input: ArticleInput): ResponseEntity<Article> {
        val article = articleService.create(input)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(article.id).toUri()
        return ResponseEntity.created(location).body(article)
    }

    @PutMapping("/{id}")
    fun replaceArticle(@PathVariable id: Long, @Valid @RequestBody input: ArticleInput): Article =
        articleService.replace(id, input)

    @PatchMapping("/{id}", consumes = ["application/merge-patch+json"])
    fun patchArticle(@PathVariable id: Long, @RequestBody patch: Map<String, Any?>): Article =
        articleService.patch(id, patch)

    @DeleteMapping("/{id}")
    fun deleteArticle(@PathVariable id: Long): ResponseEntity<Void> {
        articleService.delete(id)
        return ResponseEntity.noContent().build()
    }
}
