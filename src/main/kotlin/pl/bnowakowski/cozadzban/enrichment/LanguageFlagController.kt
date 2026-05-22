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
class LanguageFlagController(
    @Value("\${cozadzban.language-flag-cache-dir:data/flags}") cacheDir: String,
) {
    private val cachePath: Path = Path.of(cacheDir).toAbsolutePath().normalize()

    @GetMapping("/flags/{filename}")
    fun flag(@PathVariable filename: String): ResponseEntity<FileSystemResource> {
        if (!FILENAME_PATTERN.matches(filename)) return ResponseEntity.notFound().build()

        val path = cachePath.resolve(filename).normalize()
        if (!path.startsWith(cachePath) || !Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS).cachePublic())
            .contentType(MediaType.parseMediaType("image/svg+xml"))
            .body(FileSystemResource(path))
    }

    companion object {
        private val FILENAME_PATTERN = Regex("[a-z]{2}\\.svg")
    }
}
