// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/facebook-import")
class FacebookImportController(
    private val facebookImportJobService: FacebookImportJobService,
) {

    @PostMapping("/run")
    fun runImport(): ResponseEntity<Void> {
        facebookImportJobService.startImport()
        return ResponseEntity.accepted().build()
    }

    @GetMapping("/progress")
    fun progress(): ResponseEntity<FacebookImportProgressSnapshot> {
        val progress = facebookImportJobService.currentProgress()
        return if (progress == null) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.ok(progress)
        }
    }

    @PostMapping("/terminate")
    fun terminateImport(): ResponseEntity<Void> {
        facebookImportJobService.terminateImport()
        return ResponseEntity.accepted().build()
    }
}
