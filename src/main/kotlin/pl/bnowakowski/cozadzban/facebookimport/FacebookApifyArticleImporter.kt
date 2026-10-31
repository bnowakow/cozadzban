// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.MissingNode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import pl.bnowakowski.cozadzban.article.ArticleService
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "app.facebook-import.apify", name = ["enabled"], havingValue = "true")
class FacebookApifyArticleImporter(
    private val properties: FacebookImportProperties,
    private val proposalClient: FacebookImportProposalClient,
    private val accountUsage: ApifyAccountUsageClient? = null,
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
            if (ex is ApifyCreditsExhaustedException) {
                summary.creditsExhausted = true
                summary.usage = ex.usage ?: summary.usage
            }
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
        val items = fetchApifyItems(summary)
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
            summary.autoApproved += response.autoApproved
            summary.failed += response.failed
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

    private fun fetchApifyItems(summary: ApifyImportSummary): List<JsonNode> {
        ensureCreditsAvailable(summary)
        val client = apifyClient()
        var run = try {
            client
                .post()
                .uri(startActorUri())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apify.apiToken}")
                .body(apifyInput())
                .retrieve()
                .body<String>()
                ?.let { objectMapper.readTree(it).path("data") }
                ?: throw IllegalStateException("Apify did not return an actor run")
        } catch (ex: RestClientResponseException) {
            val errorType = apifyErrorType(ex.responseBodyAsString)
            if (ex.statusCode.value() == 402 || errorType in CREDIT_ERROR_TYPES) {
                val usage = accountUsage?.fresh()
                throw ApifyCreditsExhaustedException(
                    "Apify refused to start the run (HTTP ${ex.statusCode.value()}" +
                        "${errorType?.let { ", $it" } ?: ""}): ${apifyErrorMessage(ex.responseBodyAsString) ?: "usage limit reached"}." +
                        usageSuffix(usage),
                    usage,
                    ex,
                )
            }
            throw ex
        }
        val actorRunId = run.path("id").asText().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Apify did not return an actor run ID")
        summary.apifyActorRunId = actorRunId
        val deadline = Instant.now().plus(properties.apify.readTimeout)
        while (run.path("status").asText() !in TERMINAL_ACTOR_STATUSES) {
            throwIfInterrupted()
            if (Instant.now().isAfter(deadline)) throw IllegalStateException("Apify actor run $actorRunId timed out")
            run = getActorRun(client, actorRunId, waitForFinish = true)
        }
        // Apify says the first terminal response can contain preliminary costs.
        val settledRun = runCatching {
            Thread.sleep(10_000)
            getActorRun(client, actorRunId, waitForFinish = false)
        }.onFailure { ex ->
            if (ex is InterruptedException) throw ex
            logger.warn("Apify actor run {} cost refresh failed", actorRunId, ex)
        }.getOrDefault(run)
        summary.apifyCostUsd = settledRun.path("usageTotalUsd")
            .takeIf { it.isNumber }
            ?.decimalValue()
        if (summary.apifyCostUsd == null) logger.warn("Apify actor run {} did not report a cost", actorRunId)
        logger.info("Facebook Apify actor run {} status={} costUsd={}", actorRunId, settledRun.path("status").asText(), summary.apifyCostUsd)
        if (settledRun.path("status").asText() != "SUCCEEDED") {
            val status = settledRun.path("status").asText()
            val statusMessage = settledRun.path("statusMessage").asText("").takeIf { it.isNotBlank() }
            val reason = "Apify actor run $actorRunId ended with status $status" +
                (statusMessage?.let { ": $it" } ?: "")
            // A run cut off by the usage limit looks like any other failure; check the balance to tell.
            val usage = accountUsage?.fresh()
            if (usage != null && usage.remainingUsd < requiredCreditUsd()) {
                throw ApifyCreditsExhaustedException("$reason. Apify credits are exhausted.${usageSuffix(usage)}", usage)
            }
            throw IllegalStateException(reason)
        }
        val datasetId = settledRun.path("defaultDatasetId").asText().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Apify actor run $actorRunId did not return a dataset ID")
        val response = client.get()
            .uri(datasetItemsUri(datasetId))
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apify.apiToken}")
            .retrieve()
            .body<String>() ?: "[]"
        val root = objectMapper.readTree(response)
        return when {
            root.isArray -> root.toList()
            root.has("items") && root.path("items").isArray -> root.path("items").toList()
            else -> emptyList()
        }
    }

    /** Fails fast, before Apify charges anything, when the cycle's remaining credit can't cover a run. */
    private fun ensureCreditsAvailable(summary: ApifyImportSummary) {
        val usage = accountUsage?.fresh() ?: return
        summary.usage = usage
        val required = requiredCreditUsd()
        if (usage.remainingUsd < required) {
            throw ApifyCreditsExhaustedException(
                "Apify credits are exhausted: ${usd(usage.remainingUsd)} left, a run needs up to ${usd(required)}." +
                    usageSuffix(usage),
                usage,
            )
        }
        logger.info("Apify credit left {} of {} this cycle", usd(usage.remainingUsd), usd(usage.limitUsd))
    }

    /** Credit a run may need: the configured cost cap, or the estimate if that is higher. */
    private fun requiredCreditUsd(): BigDecimal =
        BigDecimal.valueOf(maxOf(properties.apify.maxCostUsd, estimatedMaxCostUsd()))

    private fun apifyErrorType(body: String?): String? =
        runCatching { objectMapper.readTree(body.orEmpty()).path("error").path("type").asText("") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun apifyErrorMessage(body: String?): String? =
        runCatching { objectMapper.readTree(body.orEmpty()).path("error").path("message").asText("") }
            .getOrNull()?.takeIf { it.isNotBlank() }

    private fun usageSuffix(usage: ApifyAccountUsage?): String =
        usage?.let {
            " Used ${usd(it.usedUsd)} of ${usd(it.limitUsd)} this cycle" +
                (it.cycleEndAt?.let { end -> "; resets after ${DateTimeFormatter.ISO_LOCAL_DATE.format(end.atOffset(ZoneOffset.UTC))}" } ?: "") +
                "."
        } ?: ""

    private fun usd(value: BigDecimal): String = "$" + value.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun apifyInput(): Map<String, Any> =
        mapOf(
            "captionText" to properties.apify.captionText,
            "onlyPostsNewerThan" to properties.apify.onlyPostsNewerThan,
            "resultsLimit" to properties.apify.resultsLimit.coerceAtLeast(1),
            "startUrls" to listOf(mapOf("url" to properties.apify.profileUrl)),
        )

    private fun startActorUri(): URI {
        val baseUrl = properties.apify.baseUrl.trimEnd('/')
        val actorId = URLEncoder.encode(properties.apify.actorId.replace("/", "~"), StandardCharsets.UTF_8)
        return URI.create("$baseUrl/v2/acts/$actorId/runs?waitForFinish=60")
    }

    private fun getActorRun(client: RestClient, actorRunId: String, waitForFinish: Boolean): JsonNode {
        val baseUrl = properties.apify.baseUrl.trimEnd('/')
        val runId = URLEncoder.encode(actorRunId, StandardCharsets.UTF_8)
        val wait = if (waitForFinish) "?waitForFinish=60" else ""
        val response = client.get()
            .uri(URI.create("$baseUrl/v2/actor-runs/$runId$wait"))
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${properties.apify.apiToken}")
            .retrieve()
            .body<String>() ?: throw IllegalStateException("Apify actor run $actorRunId returned no details")
        return objectMapper.readTree(response).path("data")
    }

    private fun datasetItemsUri(datasetId: String): URI {
        val baseUrl = properties.apify.baseUrl.trimEnd('/')
        val id = URLEncoder.encode(datasetId, StandardCharsets.UTF_8)
        return URI.create("$baseUrl/v2/datasets/$id/items")
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
        val externalUrls = (anchorHrefUrls + textUrls)
            .filter { isImportableUrl(it) }
            .distinctBy { ArticleService.canonicalizeUrl(it) }
            .toList()
        if (externalUrls.isNotEmpty()) return externalUrls

        return sharedFacebookPostUrls(item)
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
            importType = importType,
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
                    autoApprovedCount = summary.autoApproved,
                    skippedExistingCount = summary.skippedExisting,
                    failedCount = summary.failed,
                    statusDetail = detail,
                    logs = logs,
                    apifyActorRunId = summary.apifyActorRunId,
                    apifyCostUsd = summary.apifyCostUsd,
                    apifyCreditsExhausted = summary.creditsExhausted,
                    apifyRemainingUsd = summary.usage?.remainingUsd,
                    apifyUsageCycleEndAt = summary.usage?.cycleEndAt,
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

    private fun sharedFacebookPostUrls(item: JsonNode): Sequence<String> =
        item.path("sharedPost").takeIf { it.isObject }
            ?.let { sharedPost ->
                val sharedPostUrl = firstString(sharedPost, "url", "postUrl", "topLevelUrl", "link")
                val mediaUrl = firstString(sharedPost.path("media").firstOrNull() ?: MissingNode.getInstance(), "url")
                sequenceOf(sharedPostUrl ?: mediaUrl)
                    .filterNotNull()
                    .filter { isFacebookPostUrl(it) }
            }
            ?: emptySequence()

    private fun isFacebookPostUrl(url: String): Boolean =
        runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty()
            uri.scheme in setOf("http", "https") &&
                (host == "facebook.com" || host.endsWith(".facebook.com")) &&
                (FACEBOOK_POST_PATH_REGEX.matches(path) || path == "/photo/" || path == "/photo.php")
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
        var autoApproved: Int = 0,
        var skippedExisting: Int = 0,
        var failed: Int = 0,
        var apifyActorRunId: String? = null,
        var apifyCostUsd: BigDecimal? = null,
        var creditsExhausted: Boolean = false,
        var usage: ApifyAccountUsage? = null,
        private val logs: MutableList<String> = mutableListOf(),
    ) {
        fun recordWorkerLogs(value: String) {
            if (value.isNotBlank()) logs += value
        }

        fun logsWith(message: String): String =
            (logs + message).joinToString("\n")
    }

    private companion object {
        val TERMINAL_ACTOR_STATUSES = setOf("SUCCEEDED", "FAILED", "TIMED-OUT", "ABORTED")

        /** Apify run-start error types that mean the account cannot pay for the run. */
        val CREDIT_ERROR_TYPES = setOf(
            "not-enough-usage-to-run-paid-actor",
            "monthly-usage-limit-too-low",
            "limit-reached",
            "failed-to-charge-user",
            "no-payment-method-available",
            "apify-plan-required-to-use-paid-actor",
            "x402-payment-required",
        )
        const val PRICE_PER_RESULT_USD = 0.002
        val RUN_ID_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val ANCHOR_HREF_REGEX: Regex = Regex("""<a\b[^>]*\bhref\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        val HTML_TAG_REGEX: Regex = Regex("""<[^>]+>""")
        val URL_REGEX: Regex = Regex("""https?://[^\s<>'\")]+""")
        val FACEBOOK_POST_PATH_REGEX: Regex = Regex("""/[^/]+/posts/[^/]+/?""")
    }
}

/** The Apify account has no usage left this billing cycle for another run. */
class ApifyCreditsExhaustedException(
    message: String,
    val usage: ApifyAccountUsage?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
