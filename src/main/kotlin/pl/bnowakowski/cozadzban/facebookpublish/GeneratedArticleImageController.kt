// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
class GeneratedArticleImageController(
    private val jobs: FacebookArticleImageGenerationRepository,
    private val generator: CodexArticleImageGenerator,
) {
    @GetMapping("/generated-images/{id}.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun image(@PathVariable id: Long): ResponseEntity<ByteArray> {
        if (jobs.status(id) != "GENERATED") return ResponseEntity.notFound().build()
        val png = generator.cached(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
            .body(png)
    }
}
