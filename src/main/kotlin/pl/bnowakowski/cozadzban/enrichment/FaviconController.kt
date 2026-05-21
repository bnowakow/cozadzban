// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@RestController
class FaviconController(
    @Value("\${cozadzban.favicon-cache-dir:data/favicons}") cacheDir: String,
) {
    private val cachePath: Path = Path.of(cacheDir).toAbsolutePath().normalize()

    @GetMapping("/favicons/{filename}")
    fun favicon(@PathVariable filename: String): ResponseEntity<FileSystemResource> {
        if (!FILENAME_PATTERN.matches(filename)) return ResponseEntity.notFound().build()

        val path = cachePath.resolve(filename).normalize()
        if (!path.startsWith(cachePath) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .contentType(mediaType(filename))
            .body(FileSystemResource(path))
    }

    private fun mediaType(filename: String): MediaType =
        when (filename.substringAfterLast('.', "").lowercase()) {
            "png" -> MediaType.IMAGE_PNG
            "jpg" -> MediaType.IMAGE_JPEG
            "gif" -> MediaType.IMAGE_GIF
            "webp" -> MediaType.parseMediaType("image/webp")
            "ico" -> MediaType.parseMediaType("image/x-icon")
            "svg" -> MediaType.parseMediaType("image/svg+xml")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }

    companion object {
        private val FILENAME_PATTERN = Regex("[a-f0-9]{64}\\.(png|jpg|gif|webp|ico|svg)")
    }
}
