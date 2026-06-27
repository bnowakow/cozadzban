// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import pl.bnowakowski.cozadzban.article.ArticleService
import java.net.URI
import java.net.http.HttpClient
import java.time.Instant
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "app.facebook-import.api", name = ["enabled"], havingValue = "true")
class FacebookApiArticleImporter(
    private val properties: FacebookImportProperties,
    private val proposalClient: FacebookImportProposalClient,
) : FacebookImportRunner {
    override val importType: FacebookImportType = FacebookImportType.API

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val stateLock = Any()
    private val candidateSequence = AtomicLong()

    @Volatile private var activeImportThread: Thread? = null
    @Volatile private var activeImportStartedAt: Instant? = null
    @Volatile private var latestProgressSnapshot: FacebookImportProgressSnapshot? = null

    override fun unavailableReason(): String? {
        if (!properties.api.enabled) {
            return "app.facebook-import.api.enabled must be true"
        }
        if (properties.api.userAccessToken.isBlank()) {
            return "APP_FACEBOOK_IMPORT_API_USER_ACCESS_TOKEN must be set"
        }
        if (properties.targetApiBaseUrl.isNotBlank() != properties.targetApiKey.isNotBlank()) {
            return "Remote Facebook import is misconfigured: set both APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL and APP_FACEBOOK_IMPORT_TARGET_API_KEY"
        }
        return null
    }

    override fun newImportRunId(generatedAt: Instant): String =
        "facebook-api-import-${RUN_ID_FORMATTER.format(generatedAt.atOffset(ZoneOffset.UTC))}"

    override fun runImport(importRunId: String, trigger: FacebookImportTrigger) {
        unavailableReason()?.let { throw IllegalArgumentException(it) }
        val currentThread = Thread.currentThread()
        synchronized(stateLock) {
            val activeThread = activeImportThread
            if (activeThread?.isAlive == true && activeThread !== currentThread) {
                throw FacebookImportAlreadyRunningException()
            }
            activeImportThread = currentThread
        }

        val summary = ApiImportSummary()
        val startedAt = Instant.now()
        activeImportStartedAt = startedAt
        latestProgressSnapshot = null
        var completionStatus = FacebookImportRunStatus.FINISHED
        var completionLogs = ""
        logger.info("Facebook API import {} started trigger={}", importRunId, trigger)
        reportProgress(importRunId, FacebookImportProgressPhase.STARTING, summary, force = true)
        try {
            runImportInternal(importRunId, summary)
            if (summary.failed > 0) completionStatus = FacebookImportRunStatus.FAILED
            completionLogs = summary.logsWith(
                "Facebook API import finished: ${summary.discovered} discovered, ${summary.submitted} submitted, " +
                    "${summary.skippedExisting} skipped existing, ${summary.failed} failed.",
            )
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            completionStatus = FacebookImportRunStatus.TERMINATED
            completionLogs = summary.logsWith("Facebook API import was terminated.")
            logger.info("Facebook API import {} was interrupted", importRunId)
            throw ex
        } catch (ex: Exception) {
            completionStatus = FacebookImportRunStatus.FAILED
            completionLogs = summary.logsWith("Facebook API import failed: ${failureMessage(ex)}")
            logger.warn("Facebook API import {} failed", importRunId, ex)
            throw ex
        } finally {
            completeRunSafely(importRunId, completionStatus, summary, completionLogs)
            synchronized(stateLock) {
                if (activeImportThread === currentThread) {
                    activeImportThread = null
                }
            }
        }
    }

    override fun terminateImport() {
        val thread = synchronized(stateLock) {
            activeImportThread?.takeIf { it.isAlive } ?: throw FacebookImportNotRunningException()
        }
        thread.interrupt()
    }

    override fun isImportRunning(): Boolean =
        synchronized(stateLock) { activeImportThread?.isAlive == true }

    override fun currentProgressSnapshot(): FacebookImportProgressSnapshot? =
        latestProgressSnapshot

    private fun runImportInternal(importRunId: String, summary: ApiImportSummary) {
        val maxPages = properties.api.maxPages.coerceAtLeast(1)
        var nextUrl: String? = null
        for (pageIndex in 1..maxPages) {
            throwIfInterrupted()
            reportProgress(
                importRunId,
                FacebookImportProgressPhase.COLLECTING_POSTS,
                summary,
                passIndex = pageIndex,
                passCount = maxPages,
                detail = "Fetching Facebook Graph API page $pageIndex/$maxPages",
                force = true,
            )
            val page = fetchPostsPage(nextUrl)
            val proposals = page.posts.flatMap { post -> proposalsFromPost(importRunId, post) }
            summary.discovered += proposals.size
            val batchLogs = "Facebook API page $pageIndex/$maxPages produced ${proposals.size} proposal candidate(s)."
            summary.recordWorkerLogs(batchLogs)
            reportProgress(
                importRunId,
                FacebookImportProgressPhase.SENDING_PROPOSALS,
                summary,
                passIndex = pageIndex,
                passCount = maxPages,
                force = true,
            )
            if (proposals.isNotEmpty()) {
                val response = proposalClient.submitBatch(
                    FacebookProposalBatchRequest(
                        importRunId = importRunId,
                        importType = importType,
                        passIndex = pageIndex,
                        passCount = maxPages,
                        proposals = proposals,
                        logs = batchLogs,
                    ),
                )
                summary.submitted += response.submitted
                summary.skippedExisting += response.skippedExisting
            }
            nextUrl = page.nextUrl
            if (nextUrl.isNullOrBlank()) break
        }
    }

    private fun fetchPostsPage(nextUrl: String?): ApiPostsPage {
        val response = if (nextUrl.isNullOrBlank()) {
            restClient()
                .get()
                .uri { builder ->
                    builder
                        .path("/{version}/{profileId}/posts")
                        .queryParam("limit", properties.api.pageLimit.coerceIn(1, 100))
                        .queryParam("fields", GRAPH_FIELDS)
                        .build(cleanPath(properties.api.version), cleanPath(properties.api.profileId))
                }
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.api.userAccessToken}")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<String>()
        } else {
            restClient()
                .get()
                .uri(URI(nextUrl))
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.api.userAccessToken}")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body<String>()
        } ?: "{}"
        val root = objectMapper.readTree(response)
        val posts = root.path("data").mapNotNull { node ->
            val id = node.path("id").asText("").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ApiPost(
                id = id,
                message = node.path("message").asText(""),
                permalinkUrl = node.path("permalink_url").asText("").takeIf { it.isNotBlank() },
                attachments = node.path("attachments").path("data").toList(),
            )
        }
        val next = root.path("paging").path("next").asText("").takeIf { it.isNotBlank() }
        return ApiPostsPage(posts, next)
    }

    private fun proposalsFromPost(importRunId: String, post: ApiPost): List<FacebookProposalSubmission> {
        val haystack = listOf(post.message, attachmentText(post.attachments)).joinToString("\n")
        if (!containsMarker(haystack)) return emptyList()
        return extractUrls(post)
            .filter { isImportableUrl(it) }
            .distinctBy { ArticleService.canonicalizeUrl(it) }
            .map { url ->
                FacebookProposalSubmission(
                    candidateId = "$importRunId-api-${candidateSequence.incrementAndGet()}",
                    articleUrl = url,
                    facebookPostUrl = post.permalinkUrl,
                    language = normalizedLanguage(),
                    logs = "source=facebook-graph-api\npostId=${post.id}\nfacebookPostUrl=${post.permalinkUrl ?: "<none>"}",
                )
            }
    }

    private fun extractUrls(post: ApiPost): List<String> =
        buildList {
            addAll(URL_REGEX.findAll(post.message).map { it.value.trimEnd('.', ',', ')', ']') })
            post.attachments.forEach { attachment ->
                attachment.path("url").asText("").takeIf { it.isNotBlank() }?.let(::add)
                attachment.path("target").path("url").asText("").takeIf { it.isNotBlank() }?.let(::add)
                attachment.path("unshimmed_url").asText("").takeIf { it.isNotBlank() }?.let(::add)
            }
        }

    private fun attachmentText(attachments: List<JsonNode>): String =
        attachments.joinToString("\n") { attachment ->
            listOf(
                attachment.path("title").asText(""),
                attachment.path("description").asText(""),
                attachment.path("url").asText(""),
            ).joinToString("\n")
        }

    private fun containsMarker(text: String): Boolean =
        listOf(properties.markerPhrase, properties.translatedMarkerPhrase)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { text.lowercase().contains(it) }

    private fun isImportableUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme in setOf("http", "https") &&
                !uri.host.orEmpty().contains("facebook.com", ignoreCase = true)
        }.getOrDefault(false)

    private fun normalizedLanguage(): String =
        runCatching { ArticleService.normalizeLanguage(properties.language) }.getOrDefault("pl")

    private fun reportProgress(
        importRunId: String,
        phase: FacebookImportProgressPhase,
        summary: ApiImportSummary,
        passIndex: Int = 0,
        passCount: Int = 0,
        detail: String? = null,
        force: Boolean = false,
    ) {
        val now = Instant.now()
        val request = FacebookImportProgressRequest(
            importType = importType,
            phase = phase.label,
            detail = detail,
            phaseIndex = phase.phaseIndex,
            phaseCount = FACEBOOK_IMPORT_PROGRESS_PHASE_COUNT,
            passIndex = passIndex,
            passCount = passCount,
            matchedPostCount = summary.discovered,
            submittedCount = summary.submitted,
            skippedExistingCount = summary.skippedExisting,
            failedCount = summary.failed,
            occurredAt = now,
        )
        latestProgressSnapshot = FacebookImportProgressSnapshot(
            importRunId = importRunId,
            status = FacebookImportRunStatus.RUNNING,
            startedAt = activeImportStartedAt ?: now,
            lastUpdatedAt = now,
            phase = request.phase,
            detail = request.detail,
            phaseIndex = request.phaseIndex,
            phaseCount = request.phaseCount,
            passIndex = request.passIndex,
            passCount = request.passCount,
            matchedPostCount = request.matchedPostCount,
            submittedCount = request.submittedCount,
            skippedExistingCount = request.skippedExistingCount,
            failedCount = request.failedCount,
        )
        if (force) {
            runCatching { proposalClient.recordProgress(importRunId, request) }
                .onFailure { ex -> logger.warn("Facebook API import {} could not report progress", importRunId, ex) }
        }
    }

    private fun completeRunSafely(
        importRunId: String,
        status: FacebookImportRunStatus,
        summary: ApiImportSummary,
        logs: String,
    ) {
        runCatching {
            proposalClient.completeRun(
                importRunId,
                FacebookImportRunCompletionRequest(
                    status = status,
                    importType = importType,
                    discoveredCount = summary.discovered,
                    submittedCount = summary.submitted,
                    skippedExistingCount = summary.skippedExisting,
                    failedCount = summary.failed,
                    logs = logs,
                ),
            )
        }.onFailure { ex ->
            logger.warn("Facebook API import {} could not record terminal status {}", importRunId, status, ex)
        }
    }

    private fun restClient(): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(properties.api.connectTimeout)
                .build(),
        )
        requestFactory.setReadTimeout(properties.api.readTimeout)
        return RestClient.builder()
            .baseUrl(properties.api.baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Facebook API import interrupted")
    }

    private fun cleanPath(value: String): String =
        value.trim().trim('/')

    private fun failureMessage(ex: Throwable): String =
        ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName

    private data class ApiPost(
        val id: String,
        val message: String,
        val permalinkUrl: String?,
        val attachments: List<JsonNode>,
    )

    private data class ApiPostsPage(
        val posts: List<ApiPost>,
        val nextUrl: String?,
    )

    private data class ApiImportSummary(
        var discovered: Int = 0,
        var submitted: Int = 0,
        var skippedExisting: Int = 0,
        var failed: Int = 0,
        private val logs: MutableList<String> = mutableListOf(),
    ) {
        fun recordWorkerLogs(value: String) {
            if (value.isNotBlank()) logs += value
        }

        fun logsWith(message: String): String =
            (logs + message).joinToString("\n")
    }

    private companion object {
        const val GRAPH_FIELDS = "id,message,created_time,permalink_url,attachments{url,target,unshimmed_url,title,description}"
        val RUN_ID_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val URL_REGEX: Regex = Regex("""https?://[^\s<>"']+""")
    }
}
