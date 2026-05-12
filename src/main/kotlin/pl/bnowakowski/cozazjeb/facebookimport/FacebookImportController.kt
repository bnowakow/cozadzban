// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/facebook-import")
class FacebookImportController(
    private val facebookProfileArticleImporter: FacebookProfileArticleImporter,
) {

    @PostMapping("/run")
    fun runImport(): ResponseEntity<Void> {
        facebookProfileArticleImporter.startImport()
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/terminate")
    fun terminateImport(): ResponseEntity<Void> {
        facebookProfileArticleImporter.terminateImport()
        return ResponseEntity.accepted().build()
    }
}
