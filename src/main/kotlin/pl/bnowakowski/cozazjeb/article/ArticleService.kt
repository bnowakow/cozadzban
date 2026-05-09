// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository
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
        val url = canonicalizeUrl(input.url)
        val language = normalizeLanguage(input.language)
        if (articleRepository.existsByUrl(url)) throw ArticleUrlConflictException(url)
        val enrichment = enrichmentService.enrich(url)
        val publishedAt = input.publishedAt ?: enrichment.publishedAt
        val contentForCache = selectContentForCache(url, enrichment.plainText, enrichment.lead, enrichment.title)
        val article = articleRepository.save(
            Article(
                url = url,
                language = language,
                quote = input.quote,
                title = titleForSave(url, enrichment.title, enrichment.lead, contentForCache),
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
                publishedAt = publishedAt,
                createdByUserId = creatorId,
            )
        )
        preserveContent(article.id!!, contentForCache)
        return article
    }

    fun replace(id: Long, input: ArticleInput): Article {
        val existing = findById(id)
        val url = canonicalizeUrl(input.url)
        val language = normalizeLanguage(input.language)
        if (url != existing.url && articleRepository.existsByUrl(url)) throw ArticleUrlConflictException(url)
        val enrichment = enrichmentService.enrich(url)
        val publishedAt = input.publishedAt ?: enrichment.publishedAt
        val contentForCache = selectContentForCache(url, enrichment.plainText, enrichment.lead, enrichment.title)
        val article = articleRepository.save(
            existing.copy(
                url = url,
                language = language,
                quote = input.quote,
                title = titleForSave(url, enrichment.title, enrichment.lead, contentForCache),
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
                publishedAt = publishedAt,
            )
        )
        preserveContent(article.id!!, contentForCache)
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
                q as? String
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
            if (articleRepository.existsByUrl(newUrl)) throw ArticleUrlConflictException(newUrl)
            enrichmentService.enrich(newUrl)
        } else {
            null
        }
        val contentForChangedUrl = enrichmentForChangedUrl?.let {
            selectContentForCache(newUrl, it.plainText, it.lead, it.title)
        }
        val (newTitle, newThumbnail, newLead) = if (enrichmentForChangedUrl != null) {
            Triple(
                titleForSave(newUrl, enrichmentForChangedUrl.title, enrichmentForChangedUrl.lead, contentForChangedUrl),
                enrichmentForChangedUrl.thumbnail,
                enrichmentForChangedUrl.lead,
            )
        } else {
            Triple(existing.title, existing.thumbnail, existing.lead)
        }

        val saved = articleRepository.save(
            existing.copy(
                url = newUrl,
                language = newLanguage,
                quote = newQuote,
                title = newTitle,
                thumbnail = newThumbnail,
                lead = newLead,
                publishedAt = newPublishedAt,
            )
        )

        if (contentPresent) {
            val newContent = patch["content"] as? String
            if (newContent.isNullOrBlank()) {
                articleContentRepository.deleteByArticleId(saved.id!!)
            } else {
                preserveContent(saved.id!!, newContent)
            }
        } else if (enrichmentForChangedUrl != null) {
            preserveContent(saved.id!!, contentForChangedUrl)
        }

        return saved
    }

    fun refreshPublishedAt(id: Long): Article {
        val existing = findById(id)
        val enrichment = enrichmentService.enrich(existing.url)
        val publishedAt = enrichment.publishedAt
            ?: throw NoSuchElementException("No published date found on the article page")

        return articleRepository.save(existing.copy(publishedAt = publishedAt))
    }

    fun refreshContentCache(id: Long): ArticleContent {
        val existing = findById(id)
        val enrichment = enrichmentService.enrich(existing.url)
        val contentForCache = selectContentForCache(existing.url, enrichment.plainText, enrichment.lead, enrichment.title)
            ?: throw NoSuchElementException("No cacheable content found on the article page")

        preserveContent(id, contentForCache)
        return articleContentRepository.findById(id)
            .orElseThrow { NoSuchElementException("Content cache was not stored for article $id") }
    }

    fun delete(id: Long) {
        if (!articleRepository.existsById(id)) throw NoSuchElementException("Article $id not found")
        articleRepository.deleteById(id)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Persists plain-text content for an article, truncating to [MAX_CONTENT_BYTES] if needed.
     * Runs inside the same transaction as the article save; failures are logged but do not
     * roll back the article itself.
     */
    private fun preserveContent(articleId: Long, plainText: String?) {
        if (plainText.isNullOrBlank()) return
        val bytes = plainText.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > MAX_CONTENT_BYTES
        val storedText = if (truncated) String(bytes, 0, MAX_CONTENT_BYTES, Charsets.UTF_8) else plainText
        // Upsert: replace any existing content row for this article
        articleContentRepository.deleteByArticleId(articleId)
        articleContentRepository.insert(ArticleContent(articleId = articleId, content = storedText, truncated = truncated, capturedAt = java.time.Instant.now()))
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

        return title?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun titleForSave(url: String, title: String?, lead: String?, contentForCache: String?): String? {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val leadExcerpt = lead
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isGenericFacebookTitle(it) }
            ?.let { excerpt(it, ARTICLE_TITLE_EXCERPT_LENGTH) }
        val cacheExcerpt = contentForCache
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isGenericFacebookTitle(it) }
            ?.let { excerpt(it, ARTICLE_TITLE_EXCERPT_LENGTH) }

        if (isFacebookPostUrl(url)) {
            leadExcerpt?.let { return it }
            cacheExcerpt?.let { return it }
        }
        if (isFacebookVideoOrReelUrl(url) && shouldUseFacebookLeadTitle(normalizedTitle)) {
            leadExcerpt?.let { return it }
            cacheExcerpt?.let { return it }
        }
        if (!isGenericFacebookTitle(normalizedTitle)) return normalizedTitle

        return cacheExcerpt ?: normalizedTitle
    }

    private fun isGenericFacebookTitle(title: String?): Boolean =
        title == GENERIC_FACEBOOK_PAGE_TITLE ||
            title == GENERIC_FACEBOOK_POST_TITLE ||
            title == GENERIC_FACEBOOK_REEL_TITLE

    private fun shouldUseFacebookLeadTitle(title: String?): Boolean =
        isGenericFacebookTitle(title) || title?.contains(" | ") == true

    private fun excerpt(text: String, maxLength: Int): String =
        if (text.length <= maxLength) {
            text
        } else {
            text.take(maxLength).trimEnd() + "..."
        }

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

        return isFacebookUrl(url) && path.contains("/posts/")
    }

    private fun isFacebookUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        return host == "facebook.com" || host.endsWith(".facebook.com")
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
        private val BCP47_PATTERN = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*\$")
        private const val GENERIC_FACEBOOK_PAGE_TITLE = "Facebook"
        private const val GENERIC_FACEBOOK_POST_TITLE = "Facebook post"
        private const val GENERIC_FACEBOOK_REEL_TITLE = "Facebook reel"
        private const val ARTICLE_TITLE_EXCERPT_LENGTH = 120
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

        /**
         * Normalizes a language tag to lowercase and validates it against the BCP-47-like
         * pattern. Throws [IllegalArgumentException] (→ 400) on invalid input (BR-51).
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
            val query = if (isTrackingOnlySocialHost(host)) "" else uri.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://$authority$path$query"
        }

        private fun isTrackingOnlySocialHost(host: String): Boolean =
            isFacebookHost(host) || isInstagramHost(host)

        private fun isFacebookHost(host: String): Boolean =
            host == "facebook.com" || host.endsWith(".facebook.com")

        private fun isInstagramHost(host: String): Boolean =
            host == "instagram.com" || host.endsWith(".instagram.com")

        private fun parseSortParam(sort: String): Pair<String, String> {
            val parts = sort.split(",")
            val field = parts.getOrNull(0)?.trim() ?: "createdAt"
            val dir = parts.getOrNull(1)?.trim() ?: "desc"
            return Pair(field, dir)
        }
    }
}
