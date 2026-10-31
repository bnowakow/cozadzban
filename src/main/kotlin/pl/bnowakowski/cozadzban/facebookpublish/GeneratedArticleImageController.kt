// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.WebRequest
import java.time.Duration

/**
 * Serves generated article images. The PNG is the full-quality original also used for Facebook;
 * the JPEG is a much smaller copy for the website. A `v` parameter matching the current image
 * version marks the URL as immutable; unversioned URLs get a short cache plus Last-Modified so
 * browsers revalidate with a cheap 304 instead of downloading the image again.
 */
@RestController
class GeneratedArticleImageController(
    private val jobs: FacebookArticleImageGenerationRepository,
    private val generator: CodexArticleImageGenerator,
) {
    @GetMapping("/generated-images/{id}.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun image(
        @PathVariable id: Long,
        @RequestParam(name = "v", required = false) version: Long?,
        request: WebRequest,
    ): ResponseEntity<ByteArray> = serve(id, version, request, MediaType.IMAGE_PNG) { generator.storedPng(id) }

    @GetMapping("/generated-images/{id}.jpg", produces = [MediaType.IMAGE_JPEG_VALUE])
    fun webImage(
        @PathVariable id: Long,
        @RequestParam(name = "v", required = false) version: Long?,
        request: WebRequest,
    ): ResponseEntity<ByteArray> = serve(id, version, request, MediaType.IMAGE_JPEG) { generator.webImage(id) }

    private fun serve(
        id: Long,
        requestedVersion: Long?,
        request: WebRequest,
        contentType: MediaType,
        load: () -> ByteArray?,
    ): ResponseEntity<ByteArray> {
        if (jobs.status(id) != "GENERATED") return ResponseEntity.notFound().build()
        val currentVersion = generator.imageVersion(id) ?: return ResponseEntity.notFound().build()
        val cacheControl = if (requestedVersion == currentVersion) {
            CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
        } else {
            CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic()
        }
        if (request.checkNotModified(currentVersion)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).cacheControl(cacheControl).build()
        }
        val body = load() ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(contentType)
            .cacheControl(cacheControl)
            .lastModified(currentVersion)
            .body(body)
    }
}
