// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/facebook-import")
class FacebookArticleProposalController(
    private val proposalService: FacebookArticleProposalService,
) {
    @GetMapping("/proposals/exists")
    fun exists(@RequestParam url: String): FacebookProposalExistsResponse =
        FacebookProposalExistsResponse(proposalService.existsByArticleUrl(url))

    @PostMapping("/proposals")
    fun submitBatch(@RequestBody request: FacebookProposalBatchRequest): FacebookProposalBatchResponse =
        proposalService.submitBatch(request)

    @PostMapping("/runs/{importRunId}/complete")
    fun completeRun(
        @PathVariable importRunId: String,
        @RequestBody request: FacebookImportRunCompletionRequest,
    ): ResponseEntity<Void> {
        proposalService.completeRun(importRunId, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/runs/{importRunId}/progress")
    fun recordProgress(
        @PathVariable importRunId: String,
        @RequestBody request: FacebookImportProgressRequest,
    ): ResponseEntity<Void> {
        proposalService.recordProgress(importRunId, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/runs/{importRunId}/login-required")
    fun recordLoginRequired(
        @PathVariable importRunId: String,
        @RequestBody request: FacebookImportLoginRequiredRequest,
    ): ResponseEntity<Void> {
        proposalService.recordLoginRequired(importRunId, request)
        return ResponseEntity.noContent().build()
    }
}
