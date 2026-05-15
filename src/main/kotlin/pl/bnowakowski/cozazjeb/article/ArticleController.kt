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
import org.slf4j.LoggerFactory
import pl.bnowakowski.cozazjeb.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.time.Instant
import java.net.URI

@RestController
@RequestMapping("/api/articles")
class ArticleController(
    private val articleService: ArticleService,
    private val appUserRepository: AppUserRepository,
) {

    @GetMapping(params = ["existsUrl"])
    fun articleExistsByUrl(@RequestParam existsUrl: String): ArticleUrlExistsResponse =
        ArticleUrlExistsResponse(articleService.existsByUrl(existsUrl))

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
        authentication: Authentication?,
    ): ArticleResponsePage {
        val articlePage = articleService.findPage(page, size, sort, language, publishedFrom, publishedTo, createdFrom, createdTo)
        val creatorMap: Map<Long, AppUser> = if (authentication != null && authentication.isAuthenticated) {
            val ids = articlePage.content.map { it.createdByUserId }.distinct()
            appUserRepository.findAllById(ids).associateBy { it.id!! }
        } else {
            emptyMap()
        }
        val content = articlePage.content.map { article ->
            ArticleResponse.from(article, creatorMap[article.createdByUserId])
        }
        return ArticleResponsePage(
            content = content,
            pageable = articlePage.pageable,
            totalElements = articlePage.totalElements,
            totalPages = articlePage.totalPages,
            size = articlePage.size,
            number = articlePage.number,
            numberOfElements = articlePage.numberOfElements,
            sort = articlePage.sort,
            first = articlePage.first,
            last = articlePage.last,
            empty = articlePage.empty,
        )
    }

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
        logFacebookPhotoCreateRequest(input, authentication)
        val email = AllowlistAuthorizationManager.normalizeEmail(authentication.name)!!
        val creator = appUserRepository.findByEmail(email)!!
        val article = articleService.create(input, creator.id!!)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(article.id).toUri()
        logFacebookPhotoCreateResponse(input, article, location.toString())
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
        logPatchRequest(id, patch, authentication)
        val article = articleService.patch(id, patch)
        val creator = resolveCreatorForResponse(article.createdByUserId, authentication)
        logPatchResponse(id, patch, article)
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

    private fun logFacebookPhotoCreateRequest(input: ArticleInput, authentication: Authentication) {
        if (!isFacebookUrl(input.url)) return

        LOG.debug(
            "Facebook article API create request received; url='{}'; kind={}; language='{}'; quote={}; publishedAt={}; auth={}",
            input.url,
            facebookUrlKind(input.url),
            input.language,
            valueDiagnostic(input.quote),
            input.publishedAt,
            authenticationDiagnostic(authentication),
        )
    }

    private fun logFacebookPhotoCreateResponse(input: ArticleInput, article: Article, location: String) {
        if (!isFacebookUrl(input.url) && !isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook article API create response ready; requestUrl='{}'; kind={}; articleId={}; location='{}'; " +
                "savedUrl='{}'; title={}; thumbnail={}; lead={}; publishedAt={}",
            input.url,
            facebookUrlKind(article.url),
            article.id,
            location,
            article.url,
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
        )
    }

    private fun logPatchRequest(id: Long, patch: Map<String, Any?>, authentication: Authentication) {
        if (!patch.containsKey("content") && !isFacebookUrl(patch["url"] as? String)) return

        LOG.debug(
            "Article API patch request received; articleId={}; patchKeys={}; content={}; urlPatch={}; " +
                "urlPatchKind={}; publishedAtPatch={}; auth={}",
            id,
            patch.keys.sorted().joinToString(","),
            valueDiagnostic(patch["content"] as? String),
            valueDiagnostic(patch["url"] as? String),
            facebookUrlKind(patch["url"] as? String),
            patch["publishedAt"],
            authenticationDiagnostic(authentication),
        )
    }

    private fun logPatchResponse(id: Long, patch: Map<String, Any?>, article: Article) {
        if (!patch.containsKey("content") && !isFacebookUrl(article.url)) return

        LOG.debug(
            "Article API patch response saved; articleId={}; url='{}'; kind={}; title={}; thumbnail={}; lead={}; " +
                "publishedAt={}; contentPatch={}",
            id,
            article.url,
            facebookUrlKind(article.url),
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
            valueDiagnostic(patch["content"] as? String),
        )
    }

    private fun authenticationDiagnostic(authentication: Authentication): String =
        "name='${authentication.name}',authenticated=${authentication.isAuthenticated},principal=${authentication.principal?.javaClass?.simpleName}"

    private fun isFacebookUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    private fun facebookUrlKind(url: String?): String {
        if (!isFacebookUrl(url)) return "non-facebook"
        val path = runCatching { URI(url ?: return "invalid").path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.contains("/photo/") || path.contains("/photo.php") -> "photo"
            path.contains("/posts/") || path.contains("/permalink.php") || path.contains("/story.php") -> "post"
            path.contains("/videos/") || path.contains("/watch/") || path.contains("/reel/") -> "video-or-reel"
            path.contains("/share/") || path.contains("/shares/") -> "share"
            else -> "facebook-other"
        }
    }

    private fun valueDiagnostic(value: String?): String =
        value
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val excerpt = if (it.length <= LOG_EXCERPT_LENGTH) it else it.take(LOG_EXCERPT_LENGTH) + "..."
                "present(len=${it.length},excerpt='$excerpt')"
            }
            ?: "absent"

    companion object {
        private val LOG = LoggerFactory.getLogger(ArticleController::class.java)
        private const val LOG_EXCERPT_LENGTH = 500
    }
}

data class ArticleUrlExistsResponse(
    val exists: Boolean,
)
