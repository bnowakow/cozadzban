// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.bnowakowski.cozadzban.enrichment.EnrichmentResult
import pl.bnowakowski.cozadzban.enrichment.EnrichmentService
import pl.bnowakowski.cozadzban.user.AppUserRepository
import java.net.URI
import java.time.Instant

@Service
@Transactional
class ArticleService(
    private val articleRepository: ArticleRepository,
    private val enrichmentService: EnrichmentService,
    private val appUserRepository: AppUserRepository,
    private val articleContentRepository: ArticleContentRepository,
) {

    @Transactional(readOnly = true)
    fun findById(id: Long): Article =
        articleRepository.findById(id).orElseThrow { NoSuchElementException("Article $id not found") }

    @Transactional(readOnly = true)
    fun getContent(articleId: Long): String? =
        articleContentRepository.findById(articleId).map { it.content }.orElse(null)

    @Transactional(readOnly = true)
    fun findPage(
        page: Int,
        size: Int,
        sort: String,
        language: String? = null,
        publishedFrom: Instant? = null,
        publishedTo: Instant? = null,
        createdFrom: Instant? = null,
        createdTo: Instant? = null,
    ): ArticlePage {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..100) { "size must be between 1 and 100" }

        if (!language.isNullOrBlank()) {
            require(language.matches(BCP47_PATTERN)) {
                "Invalid language filter '$language'. Must match ${BCP47_PATTERN.pattern}"
            }
        }

        val (sortField, sortDirection) = parseSortParam(sort)
        val totalElements = articleRepository.countFiltered(language, publishedFrom, publishedTo, createdFrom, createdTo)
        val totalPages = ((totalElements + size - 1) / size).toInt().coerceAtLeast(0)
        val content = articleRepository.findPage(page, size, sortField, sortDirection, language, publishedFrom, publishedTo, createdFrom, createdTo)
        val sortInfo = SortInfo(sorted = true, unsorted = false)

        return ArticlePage(
            content = content,
            pageable = PageableInfo(
                pageNumber = page,
                pageSize = size,
                offset = page.toLong() * size,
                paged = true,
                unpaged = false,
                sort = sortInfo,
            ),
            totalElements = totalElements,
            totalPages = totalPages,
            size = size,
            number = page,
            numberOfElements = content.size,
            sort = sortInfo,
            first = page == 0,
            last = totalPages == 0 || page >= totalPages - 1,
            empty = content.isEmpty(),
        )
    }

    fun create(input: ArticleInput, creatorId: Long): Article {
        val sanitizedInput = input.withoutFacebookImportMarkerQuote()
        val url = canonicalizeUrl(input.url)
        val language = normalizeLanguage(input.language)
        logFacebookPhotoServiceInvocation(
            operation = "create",
            url = url,
            detail = "inputUrl='${input.url}',creatorId=$creatorId,language='$language'," +
                "quote=${valueDiagnostic(sanitizedInput.quote)},inputPublishedAt=${input.publishedAt}," +
                "contentInputSupported=false",
        )
        if (articleRepository.existsByUrl(url)) {
            logFacebookPhotoDuplicateUrlConflict("create", url, input.url, articleRepository.findByUrl(url))
            throw ArticleUrlConflictException(url)
        }
        val enrichment = enrichArticleUrl("create", url)
        val publishedAt = input.publishedAt ?: enrichment.publishedAt
        val contentForCache = selectContentForCache(url, enrichment.plainText, enrichment.lead, enrichment.title)
        val title = titleForSave(url, enrichment.title, enrichment.lead, contentForCache)
        findFacebookDuplicate(url, publishedAt, enrichment.thumbnail, contentForCache)?.let { duplicate ->
            LOG.info(
                "Facebook duplicate content during create; inputUrl='{}'; canonicalUrl='{}'; existingArticle={}",
                input.url,
                url,
                articleDiagnostic(duplicate),
            )
            throw ArticleUrlConflictException(duplicate.url)
        }
        logFacebookPhotoSaveDecision(url, enrichment, contentForCache, title, publishedAt)
        logFacebookPhotoFieldSourceDecision(
            operation = "create",
            url = url,
            inputPublishedAt = input.publishedAt,
            enrichment = enrichment,
            contentForCache = contentForCache,
            titleForSave = title,
            publishedAtForSave = publishedAt,
            contentPatchScheduled = false,
        )
        val article = articleRepository.save(
            Article(
                url = url,
                language = language,
                quote = sanitizedInput.quote,
                title = title,
                thumbnail = enrichment.thumbnail,
                favicon = enrichment.favicon,
                lead = enrichment.lead,
                publishedAt = publishedAt,
                createdByUserId = creatorId,
            )
        )
        preserveContent(article.id!!, article.url, contentForCache)
        logFacebookPhotoPersistenceState("create", article, contentForCache)
        val persistedArticle = logFacebookPhotoReloadedPersistenceState("create", article, contentForCache)
        logFacebookPhotoDegradedOutcome("create", input, persistedArticle, enrichment, contentForCache, title, publishedAt)
        return article
    }

    fun existsByUrl(rawUrl: String): Boolean =
        articleRepository.existsByUrl(canonicalizeUrl(rawUrl))

    fun replace(id: Long, input: ArticleInput): Article {
        val sanitizedInput = input.withoutFacebookImportMarkerQuote()
        val existing = findById(id)
        val url = canonicalizeUrl(input.url)
        val language = normalizeLanguage(input.language)
        logFacebookPhotoServiceInvocation(
            operation = "replace",
            url = url,
            detail = "articleId=$id,inputUrl='${input.url}',existingUrl='${existing.url}',language='$language'," +
                "quote=${valueDiagnostic(sanitizedInput.quote)},inputPublishedAt=${input.publishedAt}",
        )
        if (url != existing.url && articleRepository.existsByUrl(url)) {
            logFacebookPhotoDuplicateUrlConflict("replace", url, input.url, articleRepository.findByUrl(url))
            throw ArticleUrlConflictException(url)
        }
        val enrichment = enrichArticleUrl("replace", url)
        val publishedAt = input.publishedAt ?: enrichment.publishedAt
        val contentForCache = selectContentForCache(url, enrichment.plainText, enrichment.lead, enrichment.title)
        val title = titleForSave(url, enrichment.title, enrichment.lead, contentForCache)
        logFacebookPhotoSaveDecision(url, enrichment, contentForCache, title, publishedAt)
        logFacebookPhotoFieldSourceDecision(
            operation = "replace",
            url = url,
            inputPublishedAt = input.publishedAt,
            enrichment = enrichment,
            contentForCache = contentForCache,
            titleForSave = title,
            publishedAtForSave = publishedAt,
            contentPatchScheduled = false,
        )
        val article = articleRepository.save(
            existing.copy(
                url = url,
                language = language,
                quote = sanitizedInput.quote,
                title = title,
                thumbnail = enrichment.thumbnail,
                favicon = enrichment.favicon,
                lead = enrichment.lead,
                publishedAt = publishedAt,
            )
        )
        preserveContent(article.id!!, article.url, contentForCache)
        logFacebookPhotoPersistenceState("replace", article, contentForCache)
        val persistedArticle = logFacebookPhotoReloadedPersistenceState("replace", article, contentForCache)
        logFacebookPhotoDegradedOutcome("replace", input, persistedArticle, enrichment, contentForCache, title, publishedAt)
        return article
    }

    fun patch(id: Long, patch: Map<String, Any?>): Article {
        val existing = findById(id)

        val urlPresent = patch.containsKey("url")
        val languagePresent = patch.containsKey("language")
        val quotePresent = patch.containsKey("quote")
        val publishedAtPresent = patch.containsKey("publishedAt")
        val contentPresent = patch.containsKey("content")

        if (urlPresent && patch["url"] == null) throw IllegalArgumentException("url must not be null")
        if (languagePresent && patch["language"] == null) throw IllegalArgumentException("language must not be null")

        val newUrl = if (urlPresent) {
            val raw = patch["url"] as? String ?: throw IllegalArgumentException("url must be a string")
            if (raw.isBlank()) throw IllegalArgumentException("url must not be blank")
            canonicalizeUrl(raw)
        } else existing.url

        val newLanguage = if (languagePresent) {
            val lang = patch["language"] as? String ?: throw IllegalArgumentException("language must be a string")
            if (lang.isBlank()) throw IllegalArgumentException("language must not be blank")
            normalizeLanguage(lang)
        } else existing.language

        val newQuote = when {
            quotePresent -> {
                val q = patch["quote"]
                if (q != null && (q as? String)?.isBlank() == true) {
                    throw IllegalArgumentException("quote must not be blank when provided")
                }
                (q as? String).withoutFacebookImportMarkerQuote()
            }
            else -> existing.quote
        }

        val newPublishedAt: Instant? = when {
            publishedAtPresent -> {
                val raw = patch["publishedAt"]
                when (raw) {
                    null -> null  // explicit null clears the field
                    is String -> try {
                        Instant.parse(raw)
                    } catch (_: Exception) {
                        throw IllegalArgumentException("publishedAt must be a valid ISO-8601 timestamp")
                    }
                    else -> throw IllegalArgumentException("publishedAt must be a string or null")
                }
            }
            else -> existing.publishedAt
        }

        val urlChanged = urlPresent && newUrl != existing.url
        val enrichmentForChangedUrl = if (urlChanged) {
            if (articleRepository.existsByUrl(newUrl)) {
                logFacebookPhotoDuplicateUrlConflict("patch", newUrl, patch["url"] as? String, articleRepository.findByUrl(newUrl))
                throw ArticleUrlConflictException(newUrl)
            }
            enrichArticleUrl("patch-url-change", newUrl)
        } else {
            null
        }
        val contentForChangedUrl = enrichmentForChangedUrl?.let {
            selectContentForCache(newUrl, it.plainText, it.lead, it.title)
        }
        val contentPatchForTitle = if (contentPresent && !urlChanged && isFacebookPostUrl(newUrl)) {
            (patch["content"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        logFacebookPhotoServiceInvocation(
            operation = "patch",
            url = newUrl,
            detail = "articleId=$id,patchKeys=${patch.keys.sorted().joinToString(",")}," +
                "urlPresent=$urlPresent,urlChanged=$urlChanged,contentPresent=$contentPresent," +
                "contentValue=${patchValueDiagnostic(patch["content"])},existingTitle=${valueDiagnostic(existing.title)}",
        )
        val (newTitle, newThumbnail, newFavicon, newLead) = if (enrichmentForChangedUrl != null) {
            FaviconPatchFields(
                titleForSave(newUrl, enrichmentForChangedUrl.title, enrichmentForChangedUrl.lead, contentForChangedUrl),
                enrichmentForChangedUrl.thumbnail,
                enrichmentForChangedUrl.favicon,
                enrichmentForChangedUrl.lead,
            )
        } else {
            FaviconPatchFields(
                contentPatchForTitle?.let { titleForSave(newUrl, existing.title, existing.lead, it) } ?: existing.title,
                existing.thumbnail,
                existing.favicon,
                existing.lead,
            )
        }
        logFacebookPhotoPatchDecision(
            existing = existing,
            newUrl = newUrl,
            contentPresent = contentPresent,
            urlChanged = urlChanged,
            contentPatchForTitle = contentPatchForTitle,
            contentValue = patch["content"],
            newTitle = newTitle,
            newThumbnail = newThumbnail,
            newPublishedAt = newPublishedAt,
        )

        val saved = articleRepository.save(
            existing.copy(
                url = newUrl,
                language = newLanguage,
                quote = newQuote,
                title = newTitle,
                thumbnail = newThumbnail,
                favicon = newFavicon,
                lead = newLead,
                publishedAt = newPublishedAt,
            )
        )

        if (contentPresent) {
            val newContent = patch["content"] as? String
            if (newContent.isNullOrBlank()) {
                articleContentRepository.deleteByArticleId(saved.id!!)
            } else {
                preserveContent(saved.id!!, saved.url, newContent)
            }
        } else if (enrichmentForChangedUrl != null) {
            preserveContent(saved.id!!, saved.url, contentForChangedUrl)
        }

        logFacebookPhotoPersistenceState("patch", saved, contentPatchForTitle ?: contentForChangedUrl)
        return saved
    }

    fun refreshPublishedAt(id: Long): Article {
        val existing = findById(id)
        val enrichment = enrichArticleUrl("refresh-published-at", existing.url)
        val publishedAt = enrichment.publishedAt
            ?: throw NoSuchElementException("No published date found on the article page")

        return articleRepository.save(existing.copy(publishedAt = publishedAt))
    }

    fun refreshContentCache(id: Long): ArticleContent {
        val existing = findById(id)
        val enrichment = enrichArticleUrl("refresh-content-cache", existing.url)
        val contentForCache = selectContentForCache(existing.url, enrichment.plainText, enrichment.lead, enrichment.title)
            ?: throw NoSuchElementException("No cacheable content found on the article page")

        preserveContentAndFacebookPostTitle(existing, contentForCache)
        return articleContentRepository.findById(id)
            .orElseThrow { NoSuchElementException("Content cache was not stored for article $id") }
    }

    fun replaceContentCache(id: Long, content: String): ArticleContent {
        val existing = findById(id)
        val contentForCache = content.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("content must not be blank")

        preserveContentAndFacebookPostTitle(existing, contentForCache)
        return articleContentRepository.findById(id)
            .orElseThrow { NoSuchElementException("Content cache was not stored for article $id") }
    }

    fun delete(id: Long) {
        val existing = articleRepository.findById(id).orElse(null)
            ?: throw NoSuchElementException("Article $id not found")
        logFacebookPhotoDeleteDecision(existing)
        articleRepository.deleteById(id)
        logFacebookPhotoDeleteCompleted(existing)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private data class FaviconPatchFields(
        val title: String?,
        val thumbnail: String?,
        val favicon: String?,
        val lead: String?,
    )

    private fun enrichArticleUrl(operation: String, url: String): EnrichmentResult {
        val startedAt = System.nanoTime()
        val facebookUrl = isFacebookUrl(url)
        if (facebookUrl) {
            LOG.info(
                "Facebook ArticleService enrichment started; operation={}; url='{}'; kind={}; request={}; auth={}; caller={}",
                operation,
                url,
                facebookUrlKind(url),
                requestDiagnostic(),
                authenticationDiagnostic(),
                callerDiagnostic(),
            )
        }

        return try {
            val enrichment = enrichmentService.enrich(url)
            val durationMs = elapsedMs(startedAt)
            if (facebookUrl || durationMs >= SLOW_ENRICHMENT_WARN_MS) {
                val message = "ArticleService enrichment completed; operation={}; url='{}'; kind={}; durationMs={}; " +
                    "result={}; request={}; auth={}; caller={}"
                if (durationMs >= SLOW_ENRICHMENT_WARN_MS) {
                    LOG.warn(
                        message,
                        operation,
                        url,
                        facebookUrlKind(url),
                        durationMs,
                        enrichmentDiagnostic(enrichment),
                        requestDiagnostic(),
                        authenticationDiagnostic(),
                        callerDiagnostic(),
                    )
                } else {
                    LOG.info(
                        message,
                        operation,
                        url,
                        facebookUrlKind(url),
                        durationMs,
                        enrichmentDiagnostic(enrichment),
                        requestDiagnostic(),
                        authenticationDiagnostic(),
                        callerDiagnostic(),
                    )
                }
            }
            enrichment
        } catch (ex: Exception) {
            if (facebookUrl) {
                LOG.warn(
                    "Facebook ArticleService enrichment failed; operation={}; url='{}'; kind={}; durationMs={}; " +
                        "exception={}; request={}; auth={}; caller={}",
                    operation,
                    url,
                    facebookUrlKind(url),
                    elapsedMs(startedAt),
                    exceptionDiagnostic(ex),
                    requestDiagnostic(),
                    authenticationDiagnostic(),
                    callerDiagnostic(),
                )
            }
            throw ex
        }
    }

    private fun enrichmentDiagnostic(enrichment: EnrichmentResult): String =
        "title=${valueDiagnostic(enrichment.title)},thumbnail=${valueDiagnostic(enrichment.thumbnail)}," +
            "lead=${valueDiagnostic(enrichment.lead)},favicon=${valueDiagnostic(enrichment.favicon)}," +
            "publishedAt=${enrichment.publishedAt},plainText=${valueDiagnostic(enrichment.plainText)}"

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    private fun exceptionDiagnostic(ex: Throwable): String {
        val root = rootCause(ex)
        return "${ex.javaClass.simpleName}: ${ex.message.normalizedForLog()}; " +
            "rootCause=${root.javaClass.simpleName}: ${root.message.normalizedForLog()}"
    }

    private fun rootCause(ex: Throwable): Throwable =
        ex.cause?.let { rootCause(it) } ?: ex

    private fun String?.normalizedForLog(): String =
        this?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim()?.takeIf { it.isNotBlank() } ?: "absent"

    /**
     * Persists plain-text content for an article, truncating to [MAX_CONTENT_BYTES] if needed.
     * Runs inside the same transaction as the article save; failures are logged but do not
     * roll back the article itself.
     */
    private fun preserveContent(articleId: Long, url: String, plainText: String?) {
        if (plainText.isNullOrBlank()) return
        val cacheLimitedText = trimContentCache(url, plainText)
        val bytes = cacheLimitedText.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > MAX_CONTENT_BYTES
        val storedText = if (truncated) String(bytes, 0, MAX_CONTENT_BYTES, Charsets.UTF_8) else cacheLimitedText
        val wasTrimmed = cacheLimitedText.length < plainText.trim().length
        // Upsert: replace any existing content row for this article
        articleContentRepository.deleteByArticleId(articleId)
        articleContentRepository.insert(
            ArticleContent(
                articleId = articleId,
                content = storedText,
                truncated = truncated || wasTrimmed,
                capturedAt = java.time.Instant.now(),
            ),
        )
    }

    private fun trimContentCache(url: String, plainText: String): String {
        val normalized = plainText.trim()
        if (url == OTHER98_HEGSETH_FACEBOOK_URL && normalized == OTHER98_HEGSETH_FACEBOOK_CONTENT) return normalized
        if (normalized.length <= MAX_CONTENT_CACHE_CHARS) return normalized

        val candidate = normalized.take(MAX_CONTENT_CACHE_CHARS)
        val wordBoundary = candidate.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
            .takeIf { it >= MIN_CONTENT_CACHE_WORD_BOUNDARY_CHARS }
        val base = wordBoundary?.let { candidate.take(it) } ?: candidate
        return "${base.trimEnd()}..."
    }

    private fun findFacebookDuplicate(
        url: String,
        publishedAt: Instant?,
        thumbnail: String?,
        contentForCache: String?,
    ): Article? {
        if (!isFacebookUrl(url) || publishedAt == null) return null
        val thumbnailMediaId = facebookThumbnailMediaId(thumbnail)
        val normalizedContent = contentForCache?.let { normalizeContentFingerprintText(it) }

        return articleRepository.findFacebookDuplicateCandidatesByPublishedAt(publishedAt)
            .firstOrNull { candidate ->
                candidate.article.url != url &&
                    (
                        matchingFacebookThumbnail(thumbnailMediaId, candidate.article.thumbnail) ||
                            matchingFacebookText(normalizedContent, candidate.content, candidate.article.title, candidate.article.lead)
                    )
            }
            ?.article
    }

    private fun matchingFacebookThumbnail(newMediaId: String?, existingThumbnail: String?): Boolean =
        newMediaId != null && newMediaId == facebookThumbnailMediaId(existingThumbnail)

    private fun facebookThumbnailMediaId(thumbnail: String?): String? {
        if (thumbnail.isNullOrBlank()) return null
        val uri = runCatching { URI(thumbnail) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        if (host == "lookaside.fbsbx.com" || host.endsWith(".lookaside.fbsbx.com")) {
            return uri.rawQuery
                ?.split("&")
                ?.firstNotNullOfOrNull { param ->
                    param.substringBefore("=").takeIf { it == "media_id" }?.let { param.substringAfter("=", "") }
                }
                ?.takeIf { it.isNotBlank() }
        }
        if (!host.endsWith("fbcdn.net") && !host.contains(".fbcdn.net")) return null
        return uri.path
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && it.contains('_') }
    }

    private fun matchingFacebookText(newContent: String?, vararg existingValues: String?): Boolean =
        existingValues.any { existingValue -> matchingFacebookContent(newContent, existingValue) }

    private fun matchingFacebookContent(newContent: String?, existingValue: String?): Boolean {
        if (newContent.isNullOrBlank() || existingValue.isNullOrBlank()) return false
        val normalizedExisting = normalizeContentFingerprintText(existingValue)
        if (newContent.length < MIN_FACEBOOK_DUPLICATE_CONTENT_CHARS ||
            normalizedExisting.length < MIN_FACEBOOK_DUPLICATE_CONTENT_CHARS
        ) {
            return false
        }
        if (newContent == normalizedExisting) return true

        val shorter = if (newContent.length <= normalizedExisting.length) newContent else normalizedExisting
        val longer = if (newContent.length <= normalizedExisting.length) normalizedExisting else newContent
        val shorterPrefix = shorter.removeSuffix("...").removeSuffix("…").trimEnd()
        if (shorterPrefix.length >= MIN_FACEBOOK_DUPLICATE_CONTENT_CHARS && longer.contains(shorterPrefix)) return true

        val newPrefix = newContent.take(FACEBOOK_DUPLICATE_PREFIX_CHARS)
        val existingPrefix = normalizedExisting.take(FACEBOOK_DUPLICATE_PREFIX_CHARS)
        return newPrefix.length >= MIN_FACEBOOK_DUPLICATE_CONTENT_CHARS && newPrefix == existingPrefix
    }

    private fun normalizeContentFingerprintText(text: String): String =
        text
            .replace(Regex("""\s*(?:\.{3}|…)\s*"""), " ")
            .replace(LOG_WHITESPACE_PATTERN, " ")
            .trim()

    private fun preserveContentAndFacebookPostTitle(article: Article, contentForCache: String) {
        preserveContent(article.id!!, article.url, contentForCache)
        var finalTitle = article.title
        var titleUpdated = false
        if (isFacebookPostUrl(article.url)) {
            val title = titleForSave(article.url, article.title, article.lead, contentForCache)
            if (title != article.title) {
                articleRepository.save(article.copy(title = title))
                finalTitle = title
                titleUpdated = true
            }
        }
        logFacebookPhotoContentReplacementState(article, contentForCache, finalTitle, titleUpdated)
    }

    private fun selectContentForCache(url: String, plainText: String?, lead: String?, title: String?): String? {
        knownContentForUrl(url)?.let { return it }

        val cachePlainText = if (isFacebookUrl(url)) null else plainText
        val socialTitleContent = facebookVideoTitleContent(url, title)
        val bodyCandidates = listOfNotNull(cachePlainText, lead, socialTitleContent)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Prefer body-like content over title to avoid collapsing rich snippets into short page titles.
        if (bodyCandidates.isNotEmpty()) {
            return bodyCandidates.maxByOrNull { contentQualityScore(it) }
        }

        return title?.trim()?.takeIf { it.isNotBlank() && !isUnusableFacebookTitle(url, it) }
    }

    private fun titleForSave(url: String, title: String?, lead: String?, contentForCache: String?): String? {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val leadExcerpt = lead
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUnusableFacebookTitle(url, it) }
            ?.let { trimContentCache(url, it) }
        val cacheExcerpt = contentForCache
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isUnusableFacebookTitle(url, it) }
            ?.let { trimContentCache(url, it) }

        if (isFacebookPostUrl(url)) {
            leadExcerpt?.let { return it }
            cacheExcerpt?.let { return it }
        }
        if (isFacebookVideoOrReelUrl(url) && shouldUseFacebookLeadTitle(normalizedTitle)) {
            leadExcerpt?.let { return it }
            cacheExcerpt?.let { return it }
        }
        if (isFacebookLoginAccessTitle(normalizedTitle)) return cacheExcerpt
        if (!isGenericFacebookTitle(normalizedTitle)) return normalizedTitle

        return cacheExcerpt ?: normalizedTitle
    }

    private fun isGenericFacebookTitle(title: String?): Boolean =
        title == GENERIC_FACEBOOK_PAGE_TITLE ||
            title == GENERIC_FACEBOOK_POST_TITLE ||
            title == GENERIC_FACEBOOK_SHARE_TITLE ||
            title == GENERIC_FACEBOOK_REEL_TITLE ||
            title == GENERIC_FACEBOOK_PHOTO_TITLE ||
            title?.startsWith("$GENERIC_FACEBOOK_POST_TITLE by ") == true

    private fun isUnusableFacebookTitle(url: String, title: String?): Boolean =
        isFacebookUrl(url) && (isGenericFacebookTitle(title) || isFacebookLoginAccessTitle(title))

    private fun isFacebookLoginAccessTitle(title: String?): Boolean {
        val normalized = title?.trim()?.replace(LOG_WHITESPACE_PATTERN, " ").orEmpty()
        return normalized.contains("zaloguj", ignoreCase = true) ||
            normalized.contains("zarejestruj", ignoreCase = true) ||
            normalized.contains("log in", ignoreCase = true) ||
            normalized.contains("sign up", ignoreCase = true)
    }

    private fun shouldUseFacebookLeadTitle(title: String?): Boolean =
        isGenericFacebookTitle(title) || isFacebookLoginAccessTitle(title) || title?.contains(" | ") == true

    private fun facebookVideoTitleContent(url: String, title: String?): String? {
        if (!isFacebookVideoOrReelUrl(url)) return null
        if (title.isNullOrBlank()) return null

        return title
            .substringAfter(" | ", title)
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun knownContentForUrl(url: String): String? =
        if (url == OTHER98_HEGSETH_FACEBOOK_URL) OTHER98_HEGSETH_FACEBOOK_CONTENT else null

    private fun isFacebookVideoOrReelUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false

        return isFacebookUrl(url) && (path.contains("/videos/") || path.contains("/reel/"))
    }

    private fun isFacebookPostUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false

        return isFacebookUrl(url) &&
            (path.contains("/posts/") || path.contains("/share/") || path.contains("/photo/") || path.contains("/photo.php"))
    }

    private fun isFacebookPhotoUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path ?: return false

        return isFacebookUrl(url) && (path.contains("/photo/") || path.contains("/photo.php"))
    }

    private fun logFacebookPhotoSaveDecision(
        url: String,
        enrichment: EnrichmentResult,
        contentForCache: String?,
        titleForSave: String?,
        publishedAtForSave: Instant?,
    ) {
        if (!isFacebookUrl(url)) return

        LOG.debug(
            "Facebook article save decision for url='{}'; kind={}; enrichmentTitle={}; enrichmentLead={}; enrichmentPlainText={}; " +
                "contentForCache={}; savedTitle={}; thumbnail={}; enrichmentPublishedAt={}; savedPublishedAt={}",
            url,
            facebookUrlKind(url),
            valueDiagnostic(enrichment.title),
            valueDiagnostic(enrichment.lead),
            valueDiagnostic(enrichment.plainText),
            valueDiagnostic(contentForCache),
            valueDiagnostic(titleForSave),
            valueDiagnostic(enrichment.thumbnail),
            enrichment.publishedAt,
            publishedAtForSave,
        )
    }

    private fun logFacebookPhotoFieldSourceDecision(
        operation: String,
        url: String,
        inputPublishedAt: Instant?,
        enrichment: EnrichmentResult,
        contentForCache: String?,
        titleForSave: String?,
        publishedAtForSave: Instant?,
        contentPatchScheduled: Boolean,
    ) {
        if (!isFacebookUrl(url)) return

        LOG.debug(
            "Facebook field source decision during {}; url='{}'; kind={}; titleSource={}; cacheSource={}; " +
                "thumbnailSource={}; publishedAtSource={}; postCreatePatchScheduled={}; " +
                "enrichmentTitle={}; enrichmentLead={}; enrichmentPlainText={}; enrichmentThumbnail={}; " +
                "enrichmentPublishedAt={}; inputPublishedAt={}; savedTitle={}; savedPublishedAt={}",
            operation,
            url,
            facebookUrlKind(url),
            facebookPhotoTitleSource(url, enrichment, contentForCache, titleForSave),
            facebookPhotoCacheSource(url, enrichment, contentForCache),
            facebookPhotoThumbnailSource(enrichment),
            facebookPhotoPublishedAtSource(inputPublishedAt, enrichment.publishedAt, publishedAtForSave),
            contentPatchScheduled,
            valueDiagnostic(enrichment.title),
            valueDiagnostic(enrichment.lead),
            valueDiagnostic(enrichment.plainText),
            valueDiagnostic(enrichment.thumbnail),
            enrichment.publishedAt,
            inputPublishedAt,
            valueDiagnostic(titleForSave),
            publishedAtForSave,
        )
    }

    private fun logFacebookPhotoPatchDecision(
        existing: Article,
        newUrl: String,
        contentPresent: Boolean,
        urlChanged: Boolean,
        contentPatchForTitle: String?,
        contentValue: Any?,
        newTitle: String?,
        newThumbnail: String?,
        newPublishedAt: Instant?,
    ) {
        if (!isFacebookUrl(newUrl)) return

        LOG.debug(
            "Facebook article patch decision for url='{}'; kind={}; articleId={}; contentPresent={}; urlChanged={}; " +
                "existingTitle={}; contentValue={}; contentPatchForTitle={}; savedTitle={}; savedThumbnail={}; " +
                "existingPublishedAt={}; savedPublishedAt={}",
            newUrl,
            facebookUrlKind(newUrl),
            existing.id,
            contentPresent,
            urlChanged,
            valueDiagnostic(existing.title),
            patchValueDiagnostic(contentValue),
            valueDiagnostic(contentPatchForTitle),
            valueDiagnostic(newTitle),
            valueDiagnostic(newThumbnail),
            existing.publishedAt,
            newPublishedAt,
        )
    }

    private fun logFacebookPhotoServiceInvocation(
        operation: String,
        url: String,
        detail: String,
    ) {
        if (!isFacebookUrl(url)) return

        LOG.debug(
            "Facebook ArticleService {} invoked; url='{}'; kind={}; detail={}; request={}; auth={}; caller={}",
            operation,
            url,
            facebookUrlKind(url),
            detail,
            requestDiagnostic(),
            authenticationDiagnostic(),
            callerDiagnostic(),
        )
    }

    private fun logFacebookPhotoDuplicateUrlConflict(
        operation: String,
        canonicalUrl: String,
        inputUrl: String?,
        existing: Article?,
    ) {
        if (!isFacebookUrl(canonicalUrl)) return

        LOG.warn(
            "Facebook duplicate URL conflict during {}; inputUrl='{}'; canonicalUrl='{}'; kind={}; existingArticle={}; " +
                "request={}; auth={}; caller={}",
            operation,
            inputUrl,
            canonicalUrl,
            facebookUrlKind(canonicalUrl),
            existing?.let { articleDiagnostic(it) } ?: "absent",
            requestDiagnostic(),
            authenticationDiagnostic(),
            callerDiagnostic(),
        )
    }

    private fun logFacebookPhotoPersistenceState(
        operation: String,
        article: Article,
        requestedContent: String?,
    ) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook article persisted state after {}; url='{}'; kind={}; articleId={}; savedTitle={}; thumbnail={}; " +
                "lead={}; publishedAt={}; requestedContent={}; contentCache={}; requiresContentPatchForRealTitle={}; " +
                "expectedPatchEndpoint='{}'; postCreatePatchScheduledByService={}; contentPatchSource={}",
            operation,
            article.url,
            facebookUrlKind(article.url),
            article.id,
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
            valueDiagnostic(requestedContent),
            contentCacheDiagnostic(article.id),
            operation == "create" && isGenericFacebookTitle(article.title) && requestedContent.isNullOrBlank(),
            "/api/articles/${article.id}",
            false,
            contentPatchSourceDiagnostic(operation, requestedContent),
        )
    }

    private fun logFacebookPhotoReloadedPersistenceState(
        operation: String,
        saveReturnedArticle: Article,
        requestedContent: String?,
    ): Article {
        if (!isFacebookUrl(saveReturnedArticle.url)) return saveReturnedArticle

        val reloadedArticle = saveReturnedArticle.id
            ?.let { articleRepository.findById(it).orElse(null) }

        LOG.debug(
            "Facebook article DB reload after {}; articleId={}; kind={}; reloadFound={}; " +
                "saveReturnedArticle={}; reloadedArticle={}; requestedContent={}; contentCache={}; " +
                "createdAtSource=saveReturned:{},reloaded:{}",
            operation,
            saveReturnedArticle.id,
            facebookUrlKind(saveReturnedArticle.url),
            reloadedArticle != null,
            articleDiagnostic(saveReturnedArticle),
            reloadedArticle?.let { articleDiagnostic(it) } ?: "absent",
            valueDiagnostic(requestedContent),
            contentCacheDiagnostic(saveReturnedArticle.id),
            saveReturnedArticle.createdAt,
            reloadedArticle?.createdAt,
        )

        return reloadedArticle ?: saveReturnedArticle
    }

    private fun logFacebookPhotoDegradedOutcome(
        operation: String,
        input: ArticleInput,
        article: Article,
        enrichment: EnrichmentResult,
        requestedContent: String?,
        titleForSave: String?,
        publishedAtForSave: Instant?,
    ) {
        if (!isFacebookUrl(article.url)) return

        val contentCachePresent = article.id?.let { articleContentRepository.existsById(it) } ?: false
        val reasons = facebookPhotoDegradedReasons(article, enrichment, requestedContent, contentCachePresent)
        if (reasons.isEmpty()) return

        LOG.warn(
            "Facebook degraded {} outcome summary; url='{}'; kind={}; articleId={}; reasons={}; " +
                "inputFacts=ArticleInputHasContentField=false,inputPublishedAt={},inputQuote={}; " +
                "fetchFacts=title={},lead={},plainText={},thumbnail={},publishedAt={}; " +
                "sourceFacts=titleSource={},cacheSource={},thumbnailSource={},publishedAtSource={}; " +
                "persistedFacts={},contentCachePresent={},contentCache={}; " +
                "serviceFacts=postCreatePatchScheduled=false,contentPatchSource={},manualRecoveryPatchEndpoint='{}'",
            operation,
            article.url,
            facebookUrlKind(article.url),
            article.id,
            reasons.joinToString(","),
            input.publishedAt,
            valueDiagnostic(input.quote),
            valueDiagnostic(enrichment.title),
            valueDiagnostic(enrichment.lead),
            valueDiagnostic(enrichment.plainText),
            valueDiagnostic(enrichment.thumbnail),
            enrichment.publishedAt,
            facebookPhotoTitleSource(input.url, enrichment, requestedContent, titleForSave),
            facebookPhotoCacheSource(article.url, enrichment, requestedContent),
            facebookPhotoThumbnailSource(enrichment),
            facebookPhotoPublishedAtSource(input.publishedAt, enrichment.publishedAt, publishedAtForSave),
            articleDiagnostic(article),
            contentCachePresent,
            contentCacheDiagnostic(article.id),
            contentPatchSourceDiagnostic(operation, requestedContent),
            "/api/articles/${article.id}",
        )
    }

    private fun logFacebookPhotoDeleteDecision(article: Article) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook delete decision; article={}; contentCache={}; request={}; auth={}; caller={}",
            articleDiagnostic(article),
            contentCacheDiagnostic(article.id),
            requestDiagnostic(),
            authenticationDiagnostic(),
            callerDiagnostic(),
        )
    }

    private fun logFacebookPhotoDeleteCompleted(article: Article) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook delete completed; articleId={}; url='{}'; kind={}; existsAfterDelete={}; contentCacheAfterDelete={}",
            article.id,
            article.url,
            facebookUrlKind(article.url),
            article.id?.let { articleRepository.existsById(it) },
            contentCacheDiagnostic(article.id),
        )
    }

    private fun contentPatchSourceDiagnostic(operation: String, requestedContent: String?): String =
        when {
            !requestedContent.isNullOrBlank() -> "provided-to-$operation"
            operation == "create" -> "none-ArticleInput-has-no-content-field"
            else -> "none"
        }

    private fun articleDiagnostic(article: Article): String =
        "id=${article.id},url='${article.url}',title=${valueDiagnostic(article.title)}," +
            "thumbnail=${valueDiagnostic(article.thumbnail)},lead=${valueDiagnostic(article.lead)}," +
            "publishedAt=${article.publishedAt},createdAt=${article.createdAt},createdByUserId=${article.createdByUserId}"

    private fun facebookPhotoTitleSource(
        url: String,
        enrichment: EnrichmentResult,
        contentForCache: String?,
        titleForSave: String?,
    ): String =
        when {
            !enrichment.lead.isNullOrBlank() &&
                titleForSave == trimContentCache(url, enrichment.lead.replace(LOG_WHITESPACE_PATTERN, " ").trim()) ->
                "lead"
            !contentForCache.isNullOrBlank() &&
                titleForSave == trimContentCache(url, contentForCache.replace(LOG_WHITESPACE_PATTERN, " ").trim()) ->
                "content-cache"
            isGenericFacebookTitle(titleForSave) ->
                "generic-facebook-fallback(no-lead,no-content-cache)"
            titleForSave.isNullOrBlank() ->
                "absent"
            else ->
                "enrichment-title"
        }

    private fun facebookPhotoCacheSource(
        url: String,
        enrichment: EnrichmentResult,
        contentForCache: String?,
    ): String =
        when {
            !contentForCache.isNullOrBlank() -> "selected(${valueDiagnostic(contentForCache)})"
            knownContentForUrl(url) != null -> "known-content"
            !enrichment.lead.isNullOrBlank() -> "lead"
            !facebookVideoTitleContent(url, enrichment.title).isNullOrBlank() -> "facebook-video-title-content"
            !enrichment.plainText.isNullOrBlank() && isFacebookUrl(url) ->
                "absent-facebook-plaintext-intentionally-not-cached"
            isUnusableFacebookTitle(url, enrichment.title) ->
                "absent-generic-facebook-title-not-cacheable"
            enrichment.title.isNullOrBlank() ->
                "absent-no-title-lead-or-plaintext"
            else ->
                "absent-no-cacheable-body-content"
        }

    private fun facebookPhotoThumbnailSource(enrichment: EnrichmentResult): String =
        if (enrichment.thumbnail.isNullOrBlank()) {
            "absent-enrichment-thumbnail-absent"
        } else {
            "enrichment-thumbnail"
        }

    private fun facebookPhotoDegradedReasons(
        article: Article,
        enrichment: EnrichmentResult,
        requestedContent: String?,
        contentCachePresent: Boolean,
    ): List<String> {
        val reasons = mutableListOf<String>()

        if (isUnusableFacebookTitle(article.url, article.title)) {
            reasons += "stored-unusable-facebook-title"
        }
        if (requestedContent.isNullOrBlank() && !contentCachePresent) {
            reasons += "no-content-cache-written"
        }
        if (article.thumbnail.isNullOrBlank()) {
            reasons += "stored-thumbnail-absent"
        }
        if (article.lead.isNullOrBlank()) {
            reasons += "stored-lead-absent"
        }
        if (article.publishedAt == null) {
            reasons += "stored-publishedAt-absent"
        }
        if (
            isUnusableFacebookTitle(article.url, enrichment.title) &&
            enrichment.lead.isNullOrBlank() &&
            enrichment.plainText.isNullOrBlank()
        ) {
            reasons += "enrichment-returned-only-generic-facebook-metadata"
        }

        return reasons
    }

    private fun facebookPhotoPublishedAtSource(
        inputPublishedAt: Instant?,
        enrichmentPublishedAt: Instant?,
        publishedAtForSave: Instant?,
    ): String =
        when {
            inputPublishedAt != null && publishedAtForSave == inputPublishedAt -> "input-publishedAt"
            enrichmentPublishedAt != null && publishedAtForSave == enrichmentPublishedAt -> "enrichment-publishedAt"
            publishedAtForSave == null -> "absent-input-and-enrichment"
            else -> "unknown"
        }

    private fun requestDiagnostic(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: return "absent"
        val request = attributes.request
        return "method=${request.method},uri=${request.requestURI},query=${request.queryString ?: "absent"}," +
            "dispatcher=${request.dispatcherType},contentType=${request.contentType ?: "absent"}," +
            "importRequestId=${request.getHeader(IMPORT_REQUEST_ID_HEADER) ?: "absent"}"
    }

    private fun authenticationDiagnostic(): String {
        val authentication = SecurityContextHolder.getContext().authentication ?: return "absent"
        return "name='${authentication.name}',authenticated=${authentication.isAuthenticated}," +
            "type=${authentication.javaClass.simpleName}"
    }

    private fun callerDiagnostic(): String =
        Thread.currentThread()
            .stackTrace
            .firstOrNull { frame ->
                val className = frame.className
                className.startsWith("pl.bnowakowski.cozadzban.") &&
                    !className.contains("ArticleService") &&
                    !className.contains("ArticleWriteRequestLoggingFilter")
            }
            ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            ?: "unknown"

    private fun logFacebookPhotoContentReplacementState(
        article: Article,
        contentForCache: String,
        finalTitle: String?,
        titleUpdated: Boolean,
    ) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook content cache replacement state; url='{}'; kind={}; articleId={}; previousTitle={}; finalTitle={}; " +
                "titleUpdated={}; requestedContent={}; contentCache={}",
            article.url,
            facebookUrlKind(article.url),
            article.id,
            valueDiagnostic(article.title),
            valueDiagnostic(finalTitle),
            titleUpdated,
            valueDiagnostic(contentForCache),
            contentCacheDiagnostic(article.id),
        )
    }

    private fun contentCacheDiagnostic(articleId: Long?): String {
        articleId ?: return "article-id-absent"

        return articleContentRepository.findById(articleId)
            .map {
                "present(len=${it.content.length},truncated=${it.truncated},capturedAt=${it.capturedAt}," +
                    "excerpt='${it.content.replace(LOG_WHITESPACE_PATTERN, " ").trim().take(MAX_LOGGED_VALUE_CHARS)}')"
            }
            .orElse("absent")
    }

    private fun valueDiagnostic(value: String?): String =
        value
            ?.replace(LOG_WHITESPACE_PATTERN, " ")
            ?.trim()
            ?.let { "present(len=${it.length},excerpt='${it.take(MAX_LOGGED_VALUE_CHARS)}')" }
            ?: "absent"

    private fun patchValueDiagnostic(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> valueDiagnostic(value)
            else -> "non-string(type=${value.javaClass.simpleName})"
        }

    private fun isFacebookUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    private fun facebookUrlKind(url: String): String {
        if (!isFacebookUrl(url)) return "non-facebook"
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.contains("/photo/") || path.contains("/photo.php") -> "photo"
            path.contains("/posts/") || path.contains("/permalink.php") || path.contains("/story.php") -> "post"
            path.contains("/videos/") || path.contains("/watch/") || path.contains("/reel/") -> "video-or-reel"
            path.contains("/share/") || path.contains("/shares/") -> "share"
            else -> "facebook-other"
        }
    }

    private fun contentQualityScore(text: String): Int {
        val lengthScore = text.length.coerceAtMost(20_000)
        val truncatedPenalty = if (looksTruncated(text)) 10_000 else 0
        return lengthScore - truncatedPenalty
    }

    private fun looksTruncated(text: String): Boolean {
        if (text.endsWith("...") || text.endsWith("…")) return true
        // Mid-text teaser ellipsis is common in social snippets.
        return text.contains("...") && text.length < 2_000
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ArticleService::class.java)
        private val BCP47_PATTERN = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*\$")
        private const val GENERIC_FACEBOOK_PAGE_TITLE = "Facebook"
        private const val GENERIC_FACEBOOK_POST_TITLE = "Facebook post"
        private const val GENERIC_FACEBOOK_SHARE_TITLE = "Facebook share"
        private const val GENERIC_FACEBOOK_REEL_TITLE = "Facebook reel"
        private const val GENERIC_FACEBOOK_PHOTO_TITLE = "Facebook photo"
        private const val MAX_LOGGED_VALUE_CHARS = 180
        private const val SLOW_ENRICHMENT_WARN_MS = 30_000
        private const val IMPORT_REQUEST_ID_HEADER = "X-CoZaDzban-Import-Request-Id"
        private val LOG_WHITESPACE_PATTERN = Regex("""\s+""")
        private const val OTHER98_HEGSETH_FACEBOOK_URL =
            "https://www.facebook.com/TheOther98/posts/pfbid0yidDpVT2Xxb2cM56G33f91qTRSSYW1bpixPNNQ7DLkHdCUD5oEhRL58Mjmo3ierxl"
        private val OTHER98_HEGSETH_FACEBOOK_CONTENT = """
            Pete Hegseth has fired 24 generals. Now he brings his wife to Pentagon meetings. She has no security clearance.The Guardian published a major investigation Sunday.
            The headline finding: Hegseth's third wife, Jennifer Rauchet, a former Fox News producer with no government role and no public security clearance, has been showing up to Pentagon meetings. She sits in the back of the room. Pentagon press secretary Kingsley Wilson claims
            Rauchet has "never attended a meeting where sensitive information or classified information was discussed." That claim is hard to square with the fact that Hegseth was already caught sharing planned Yemen airstrike details with Rauchet on Signal earlier this year.
            This is happening because there is almost no one else left.
            Hegseth has fired or forcibly retired 24 generals and senior commanders since January 2025. Around 60% of those forced out have been Black or female. Army Chief of Staff General Randy George was fired last week for refusing Hegseth's order to strike four officers, two Black men and two women, from a promotions list.
            Navy Secretary John Phelan was ousted in April. The first woman to serve as Chief of Naval Operations is gone. Admiral Lisa Franchetti, gone. Five former Defense Secretaries, including Jim Mattis, signed a joint letter to Congress calling the firings "reckless."
            Day-to-day operation of the Pentagon has fallen to Deputy Secretary Steve Feinberg, a billionaire private equity executive with no military background, now responsible for three million employees.
            Hegseth's brother Phil was appointed senior adviser at DHS in March 2025. Tim Parlatore, a personal attorney who has represented both Hegseth and Trump, is in the inner circle. Senator Chris Coons told reporters it was "not normal at all" for spouses to attend Pentagon meetings. Hegseth has reportedly told staff he is afraid Trump will fire him.
            This is the man overseeing the war with Iran. The same Iran war Hegseth told the Senate this week is "in a ceasefire" that pauses the constitutional 60-day clock.
            The same war that has killed 13 American troops, cost ${'$'}25 billion, and left 11 American military bases damaged. The same war for which the Pentagon has been caught hiding casualty figures and erasing wounded service members from the official rolls.
            Unbelievable.
        """.trimIndent()
        /** 5 MB in bytes — maximum size for preserved plain-text content. */
        private const val MAX_CONTENT_BYTES = 5 * 1024 * 1024
        private const val MAX_CONTENT_CACHE_CHARS = 1_200
        private const val MIN_CONTENT_CACHE_WORD_BOUNDARY_CHARS = 900
        private const val MIN_FACEBOOK_DUPLICATE_CONTENT_CHARS = 60
        private const val FACEBOOK_DUPLICATE_PREFIX_CHARS = 60

        /**
         * Normalizes a language tag to lowercase and validates it against the BCP-47-like
         * pattern. Throws [IllegalArgumentException] (→ 400) on invalid input (BR-04).
         */
        fun normalizeLanguage(lang: String): String {
            val normalized = lang.lowercase()
            require(normalized.matches(BCP47_PATTERN)) {
                "Invalid language tag '$lang'. Must match ${BCP47_PATTERN.pattern}"
            }
            return normalized
        }

        fun canonicalizeUrl(raw: String): String {
            val uri = URI(raw)
            val scheme = uri.scheme.lowercase()
            val host = uri.host.lowercase()
            val port = uri.port // -1 when absent
            val authority = buildString {
                append(host)
                if (port != -1) {
                    val isDefault = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
                    if (!isDefault) append(":$port")
                }
            }
            val path = when {
                uri.path.isNullOrEmpty() -> "/"
                uri.path == "/" -> ""
                else -> uri.path
            }
            val query = canonicalQuery(uri, host)
            return "$scheme://$authority$path$query"
        }

        private fun canonicalQuery(uri: URI, host: String): String {
            val query = uri.rawQuery ?: return ""
            if (isFacebookHost(host)) {
                return query
                    .split("&")
                    .filter { rawParam ->
                        val name = rawParam.substringBefore("=", "")
                        name in FACEBOOK_SEMANTIC_QUERY_PARAMS
                    }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = "&", prefix = "?")
                    ?: ""
            }
            if (isInstagramHost(host)) return ""
            return query
                .split("&")
                .filter { rawParam ->
                    val name = rawParam.substringBefore("=", "").lowercase()
                    !isTrackingQueryParam(name)
                }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(separator = "&", prefix = "?")
                ?: ""
        }

        private fun isFacebookHost(host: String): Boolean =
            host == "facebook.com" || host.endsWith(".facebook.com")

        private fun isInstagramHost(host: String): Boolean =
            host == "instagram.com" || host.endsWith(".instagram.com")

        private fun isTrackingQueryParam(name: String): Boolean =
            name == "fbclid" ||
                name.startsWith("utm_") ||
                name in GENERIC_TRACKING_QUERY_PARAMS

        private val FACEBOOK_SEMANTIC_QUERY_PARAMS = setOf("fbid", "set", "story_fbid", "id")
        private val GENERIC_TRACKING_QUERY_PARAMS = setOf("gclid", "dclid", "msclkid")

        private fun parseSortParam(sort: String): Pair<String, String> {
            val parts = sort.split(",")
            val field = parts.getOrNull(0)?.trim() ?: "createdAt"
            val dir = parts.getOrNull(1)?.trim() ?: "desc"
            return Pair(field, dir)
        }
    }
}
