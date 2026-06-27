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
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "app.facebook-import.apify", name = ["enabled"], havingValue = "true")
class FacebookApifyArticleImporter(
    private val properties: FacebookImportProperties,
    private val proposalClient: FacebookImportProposalClient,
) : FacebookImportRunner {
    override val importType: FacebookImportType = FacebookImportType.APIFY

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val stateLock = Any()
    private val candidateSequence = AtomicLong()

    @Volatile private var activeImportThread: Thread? = null
    @Volatile private var activeImportStartedAt: Instant? = null
    @Volatile private var latestProgressSnapshot: FacebookImportProgressSnapshot? = null

    override fun unavailableReason(): String? {
        if (!properties.apify.enabled) return "app.facebook-import.apify.enabled must be true"
        if (properties.apify.apiToken.isBlank()) return "APP_FACEBOOK_IMPORT_APIFY_API_TOKEN must be set"
        if (properties.apify.resultsLimit <= 0) return "APP_FACEBOOK_IMPORT_APIFY_RESULTS_LIMIT must be positive"
        if (properties.apify.maxCostUsd <= 0.0) return "APP_FACEBOOK_IMPORT_APIFY_MAX_COST_USD must be positive"
        if (estimatedMaxCostUsd() > properties.apify.maxCostUsd) {
            val reason = "APP_FACEBOOK_IMPORT_APIFY_RESULTS_LIMIT=${properties.apify.resultsLimit} exceeds " +
                "APP_FACEBOOK_IMPORT_APIFY_MAX_COST_USD=${properties.apify.maxCostUsd}"
            logger.warn(reason)
            return reason
        }
        return null
    }

    override fun newImportRunId(generatedAt: Instant): String =
        "facebook-apify-import-${RUN_ID_FORMATTER.format(generatedAt.atOffset(ZoneOffset.UTC))}"

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
        val summary = ApifyImportSummary()
        val startedAt = Instant.now()
        activeImportStartedAt = startedAt
        latestProgressSnapshot = null
        var completionStatus = FacebookImportRunStatus.FINISHED
        var completionDetail: String? = null
        var completionLogs = ""
        logger.info("Facebook Apify import {} started trigger={}", importRunId, trigger)
        reportProgress(importRunId, trigger, FacebookImportProgressPhase.STARTING, summary, force = true)
        try {
            runImportInternal(importRunId, trigger, summary)
            if (summary.failed > 0) completionStatus = FacebookImportRunStatus.FAILED
            completionLogs = summary.logsWith(
                "Facebook Apify import finished: ${summary.discovered} discovered, ${summary.submitted} submitted, " +
                    "${summary.skippedExisting} skipped existing, ${summary.failed} failed.",
            )
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            completionStatus = FacebookImportRunStatus.TERMINATED
            completionDetail = "Facebook Apify import was terminated."
            completionLogs = summary.logsWith(completionDetail)
            logger.info("Facebook Apify import {} was interrupted", importRunId)
            throw ex
        } catch (ex: Exception) {
            completionStatus = FacebookImportRunStatus.FAILED
            summary.failed += 1
            completionDetail = "Facebook Apify import failed: ${failureMessage(ex)}"
            completionLogs = summary.logsWith(completionDetail)
            logger.warn("Facebook Apify import {} failed", importRunId, ex)
            throw ex
        } finally {
            completeRunSafely(importRunId, completionStatus, summary, completionLogs, completionDetail, trigger)
            synchronized(stateLock) {
                if (activeImportThread === currentThread) activeImportThread = null
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

    private fun runImportInternal(importRunId: String, trigger: FacebookImportTrigger, summary: ApifyImportSummary) {
        throwIfInterrupted()
        reportProgress(importRunId, trigger, FacebookImportProgressPhase.COLLECTING_POSTS, summary, detail = "Running Apify actor", force = true)
        val items = fetchApifyItems()
        val proposals = items.flatMap { item -> proposalsFromItem(importRunId, item) }
            .distinctBy { ArticleService.canonicalizeUrl(it.articleUrl) }
        summary.discovered = proposals.size
        val logs = "Facebook Apify actor ${properties.apify.actorId} returned ${items.size} item(s), " +
            "${proposals.size} proposal candidate(s)."
        summary.recordWorkerLogs(logs)
        reportProgress(importRunId, trigger, FacebookImportProgressPhase.SENDING_PROPOSALS, summary, force = true)
        if (proposals.isNotEmpty()) {
            val response = proposalClient.submitBatch(
                FacebookProposalBatchRequest(
                    importRunId = importRunId,
                    importType = importType,
                    trigger = trigger,
                    passIndex = 1,
                    passCount = 1,
                    proposals = proposals,
                    logs = logs,
                ),
            )
            summary.submitted += response.submitted
            summary.skippedExisting += response.skippedExisting
        }
        logger.info(
            "Facebook Apify import {} summary: {} total posts returned, {} marker-phrase matches, {} existing posts, {} proposals submitted",
            importRunId,
            items.size,
            proposals.size,
            summary.skippedExisting,
            summary.submitted,
        )
    }

    private fun fetchApifyItems(): List<JsonNode> {
        val response = apifyClient()
            .post()
            .uri(runSyncDatasetItemsUri())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apify.apiToken}")
            .body(apifyInput())
            .retrieve()
            .body<String>() ?: "[]"
        val root = objectMapper.readTree(response)
        return when {
            root.isArray -> root.toList()
            root.has("items") && root.path("items").isArray -> root.path("items").toList()
            else -> emptyList()
        }
    }

    private fun apifyInput(): Map<String, Any> =
        mapOf(
            "captionText" to properties.apify.captionText,
            "onlyPostsNewerThan" to properties.apify.onlyPostsNewerThan,
            "resultsLimit" to properties.apify.resultsLimit.coerceAtLeast(1),
            "startUrls" to listOf(mapOf("url" to properties.apify.profileUrl)),
        )

    private fun runSyncDatasetItemsUri(): URI {
        val baseUrl = properties.apify.baseUrl.trimEnd('/')
        val actorId = URLEncoder.encode(properties.apify.actorId.replace("/", "~"), StandardCharsets.UTF_8)
        return URI.create("$baseUrl/v2/acts/$actorId/run-sync-get-dataset-items")
    }

    private fun proposalsFromItem(importRunId: String, item: JsonNode): List<FacebookProposalSubmission> {
        val allText = textualValues(item).joinToString("\n")
        if (!containsMarker(allText)) return emptyList()
        val facebookPostUrl = firstString(item, "url", "postUrl", "facebookUrl", "link")?.takeIf { isFacebookUrl(it) }
        return proposalUrlsFromItem(item)
            .map { url ->
                FacebookProposalSubmission(
                    candidateId = "$importRunId-apify-${candidateSequence.incrementAndGet()}",
                    articleUrl = url,
                    facebookPostUrl = facebookPostUrl,
                    language = normalizedLanguage(),
                    logs = "source=apify\nactorId=${properties.apify.actorId}\nfacebookPostUrl=${facebookPostUrl ?: "<none>"}",
                )
            }.toList()
    }

    internal fun proposalUrlsFromItem(item: JsonNode): List<String> {
        val allText = textualValues(item).joinToString("\n")
        val anchorHrefUrls = ANCHOR_HREF_REGEX.findAll(allText)
            .map { it.groupValues[1].trimEnd('.', ',', ')', ']', '"', '\'') }
        val visibleText = HTML_TAG_REGEX.replace(allText, " ")
        val textUrls = URL_REGEX.findAll(visibleText)
            .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'') }
        return (anchorHrefUrls + textUrls)
            .filter { isImportableUrl(it) }
            .distinctBy { ArticleService.canonicalizeUrl(it) }
            .toList()
    }

    private fun reportProgress(
        importRunId: String,
        trigger: FacebookImportTrigger,
        phase: FacebookImportProgressPhase,
        summary: ApifyImportSummary,
        detail: String? = null,
        force: Boolean = false,
    ) {
        val now = Instant.now()
        val request = FacebookImportProgressRequest(
            importType = importType,
            trigger = trigger,
            phase = phase.label,
            detail = detail,
            phaseIndex = phase.phaseIndex,
            phaseCount = FACEBOOK_IMPORT_PROGRESS_PHASE_COUNT,
            passIndex = 1,
            passCount = 1,
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
                .onFailure { ex -> logger.warn("Facebook Apify import {} could not report progress", importRunId, ex) }
        }
    }

    private fun completeRunSafely(
        importRunId: String,
        status: FacebookImportRunStatus,
        summary: ApifyImportSummary,
        logs: String,
        detail: String?,
        trigger: FacebookImportTrigger,
    ) {
        runCatching {
            proposalClient.completeRun(
                importRunId,
                FacebookImportRunCompletionRequest(
                    status = status,
                    importType = importType,
                    trigger = trigger,
                    discoveredCount = summary.discovered,
                    submittedCount = summary.submitted,
                    skippedExistingCount = summary.skippedExisting,
                    failedCount = summary.failed,
                    statusDetail = detail,
                    logs = logs,
                ),
            )
        }.onFailure { ex ->
            logger.warn("Facebook Apify import {} could not record terminal status {}", importRunId, status, ex)
        }
    }

    private fun apifyClient(): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(properties.apify.connectTimeout).build(),
        )
        requestFactory.setReadTimeout(properties.apify.readTimeout)
        return RestClient.builder().requestFactory(requestFactory).build()
    }

    private fun textualValues(node: JsonNode): List<String> =
        when {
            node.isTextual -> listOf(node.asText())
            node.isArray -> node.flatMap { textualValues(it) }
            node.isObject -> node.properties().asSequence().flatMap { textualValues(it.value).asSequence() }.toList()
            else -> emptyList()
        }

    private fun firstString(node: JsonNode, vararg fieldNames: String): String? =
        fieldNames.firstNotNullOfOrNull { fieldName ->
            node.path(fieldName).asText("").takeIf { it.isNotBlank() }
        }

    private fun containsMarker(text: String): Boolean =
        listOf(properties.markerPhrase, properties.translatedMarkerPhrase)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .any { text.lowercase().contains(it) }

    private fun isImportableUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            uri.scheme in setOf("http", "https") && !isFacebookUrl(url) && !isMediaOrThumbnailUrl(url)
        }.getOrDefault(false)

    private fun isFacebookUrl(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().contains("facebook.com", ignoreCase = true) }.getOrDefault(false)

    private fun isMediaOrThumbnailUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        return isFacebookMediaCdnHost(host)
    }

    private fun isFacebookMediaCdnHost(host: String): Boolean =
        host == "fbcdn.net" ||
            host.endsWith(".fbcdn.net") ||
            host == "cdninstagram.com" ||
            host.endsWith(".cdninstagram.com")

    private fun normalizedLanguage(): String =
        runCatching { ArticleService.normalizeLanguage(properties.language) }.getOrDefault("pl")

    private fun estimatedMaxCostUsd(): Double =
        properties.apify.resultsLimit.coerceAtLeast(0) * PRICE_PER_RESULT_USD

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Facebook Apify import interrupted")
    }

    private fun failureMessage(ex: Throwable): String =
        ex.message?.takeIf { it.isNotBlank() } ?: ex.javaClass.simpleName

    private data class ApifyImportSummary(
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
        const val PRICE_PER_RESULT_USD = 0.002
        val RUN_ID_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val ANCHOR_HREF_REGEX: Regex = Regex("""<a\b[^>]*\bhref\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        val HTML_TAG_REGEX: Regex = Regex("""<[^>]+>""")
        val URL_REGEX: Regex = Regex("""https?://[^\s<>'\")]+""")
    }
}
