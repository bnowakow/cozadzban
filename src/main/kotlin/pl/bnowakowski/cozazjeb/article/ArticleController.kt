// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
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
import pl.bnowakowski.cozazjeb.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.time.Instant

@RestController
@RequestMapping("/api/articles")
class ArticleController(
    private val articleService: ArticleService,
    private val appUserRepository: AppUserRepository,
) {

    @GetMapping
    fun listArticles(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "createdAt,desc") sort: String,
        @RequestParam(required = false) language: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) publishedFrom: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) publishedTo: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) createdFrom: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) createdTo: Instant?,
    ): ArticlePage = articleService.findPage(page, size, sort, language, publishedFrom, publishedTo, createdFrom, createdTo)

    @GetMapping("/{id}")
    fun getArticle(@PathVariable id: Long, authentication: Authentication?): ArticleResponse {
        val article = articleService.findById(id)
        val creator = resolveCreatorForResponse(article.createdByUserId, authentication)
        return ArticleResponse.from(article, creator)
    }

    @PostMapping
    fun createArticle(
        @Valid @RequestBody input: ArticleInput,
        authentication: Authentication,
    ): ResponseEntity<ArticleResponse> {
        val email = AllowlistAuthorizationManager.normalizeEmail(authentication.name)!!
        val creator = appUserRepository.findByEmail(email)!!
        val article = articleService.create(input, creator.id!!)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(article.id).toUri()
        return ResponseEntity.created(location).body(ArticleResponse.from(article, creator))
    }

    @PutMapping("/{id}")
    fun replaceArticle(
        @PathVariable id: Long,
        @Valid @RequestBody input: ArticleInput,
        authentication: Authentication,
    ): ArticleResponse {
        val article = articleService.replace(id, input)
        val creator = resolveCreatorForResponse(article.createdByUserId, authentication)
        return ArticleResponse.from(article, creator)
    }

    @PatchMapping("/{id}", consumes = ["application/merge-patch+json"])
    fun patchArticle(
        @PathVariable id: Long,
        @RequestBody patch: Map<String, Any?>,
        authentication: Authentication,
    ): ArticleResponse {
        val article = articleService.patch(id, patch)
        val creator = resolveCreatorForResponse(article.createdByUserId, authentication)
        return ArticleResponse.from(article, creator)
    }

    @DeleteMapping("/{id}")
    fun deleteArticle(@PathVariable id: Long): ResponseEntity<Void> {
        articleService.delete(id)
        return ResponseEntity.noContent().build()
    }

    /** Returns creator info when authenticated; null for anonymous requests (BR-46). */
    private fun resolveCreatorForResponse(creatorId: Long, authentication: Authentication?): AppUser? {
        if (authentication == null || !authentication.isAuthenticated) return null
        return appUserRepository.findById(creatorId).orElse(null)
    }
}
