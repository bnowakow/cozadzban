// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Instant

@Component
class FacebookImportProposalClient(
    private val properties: FacebookImportProperties,
    private val proposalService: FacebookArticleProposalService,
) {
    fun existsByArticleUrl(url: String): Boolean =
        if (isRemoteConfigured()) {
            remoteClient()
                .get()
                .uri { builder ->
                    builder
                        .path(properties.targetProposalExistsPath)
                        .queryParam("url", url)
                        .build()
                }
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .retrieve()
                .body(FacebookProposalExistsResponse::class.java)
                ?.exists ?: true
        } else {
            proposalService.existsByArticleUrl(url)
        }

    fun submitBatch(request: FacebookProposalBatchRequest): FacebookProposalBatchResponse =
        if (isRemoteConfigured()) {
            remoteClient()
                .post()
                .uri(properties.targetProposalPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(request)
                .retrieve()
                .body(FacebookProposalBatchResponse::class.java)
                ?: throw IllegalStateException("Remote proposal API did not return a response")
        } else {
            proposalService.submitBatch(request)
        }

    fun completeRun(importRunId: String, request: FacebookImportRunCompletionRequest) {
        if (isRemoteConfigured()) {
            remoteClient()
                .post()
                .uri("${properties.targetRunPath}/{importRunId}/complete", importRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(request)
                .retrieve()
                .toBodilessEntity()
            proposalService.completeRun(importRunId, request)
        } else {
            proposalService.completeRun(importRunId, request)
        }
    }

    fun recordProgress(importRunId: String, request: FacebookImportProgressRequest) {
        if (isRemoteConfigured()) {
            remoteClient()
                .post()
                .uri("${properties.targetRunPath}/{importRunId}/progress", importRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(request)
                .retrieve()
                .toBodilessEntity()
            proposalService.recordProgress(importRunId, request)
        } else {
            proposalService.recordProgress(importRunId, request)
        }
    }

    fun recordLoginRequired(importRunId: String, request: FacebookImportLoginRequiredRequest) {
        if (isRemoteConfigured()) {
            remoteClient()
                .post()
                .uri("${properties.targetRunPath}/{importRunId}/login-required", importRunId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(request)
                .retrieve()
                .toBodilessEntity()
            proposalService.recordLoginRequired(importRunId, request)
        } else {
            proposalService.recordLoginRequired(importRunId, request)
        }
    }

    fun terminateAbandonedRunsOnStartup(startedAt: Instant = Instant.now()): List<String> {
        val request = FacebookImportStartupCleanupRequest(startedAt)
        return if (isRemoteConfigured()) {
            val response = remoteClient()
                .post()
                .uri("${properties.targetRunPath}/abandoned-startup-cleanup")
                .contentType(MediaType.APPLICATION_JSON)
                .header(properties.targetApiKeyHeader, properties.targetApiKey)
                .body(request)
                .retrieve()
                .body(FacebookImportStartupCleanupResponse::class.java)
                ?: FacebookImportStartupCleanupResponse(emptyList())
            proposalService.terminateAbandonedRunsOnStartup(startedAt)
            response.terminatedRunIds
        } else {
            proposalService.terminateAbandonedRunsOnStartup(startedAt)
        }
    }

    fun isRemoteConfigured(): Boolean =
        properties.targetApiBaseUrl.isNotBlank() && properties.targetApiKey.isNotBlank()

    private fun remoteClient(): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(properties.targetApiConnectTimeout)
                .build(),
        )
        requestFactory.setReadTimeout(properties.targetApiReadTimeout)
        return RestClient.builder()
            .baseUrl(properties.targetApiBaseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }
}
