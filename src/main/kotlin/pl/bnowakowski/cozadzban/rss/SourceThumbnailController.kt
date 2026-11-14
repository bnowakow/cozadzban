// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.rss

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.facebookpublish.CodexArticleImageGenerator
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import java.net.URI
import java.time.Duration

/** Serves the local, compact site copy; a failed refresh can still use its last good copy. */
@RestController
class SourceThumbnailController(
    private val articles: ArticleRepository,
    private val cache: SourceThumbnailCache,
    private val imageJobs: FacebookArticleImageGenerationRepository,
    private val generatedImages: CodexArticleImageGenerator,
) {
    @GetMapping("/source-images/{id}")
    fun image(@PathVariable id: Long, request: WebRequest): ResponseEntity<ByteArray> {
        val article = articles.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val image = cache.loadOrFetch(article)
        if (image == null) {
            if (imageJobs.status(id) == "GENERATED") {
                val target = generatedImages.webImageUrl(id) ?: "/generated-images/$id.jpg"
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build()
            }
            // A browser may still reach a source that blocked the server's request.
            article.thumbnail?.trim()
                ?.takeIf(FacebookArticleImageGenerationRepository::hasUsableSourceThumbnail)
                ?.let { return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(it)).build() }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
        val cacheControl = CacheControl.maxAge(Duration.ofHours(6)).cachePublic()
        if (request.checkNotModified(image.modifiedAt)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cacheControl).build()
        }
        return ResponseEntity.ok()
            .contentType(image.contentType)
            .cacheControl(cacheControl)
            .lastModified(image.modifiedAt)
            .body(image.bytes)
    }
}
