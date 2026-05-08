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
        val article = articleRepository.save(
            Article(
                url = url,
                language = language,
                quote = input.quote,
                title = enrichment.title,
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
                publishedAt = publishedAt,
                createdByUserId = creatorId,
            )
        )
        val contentForCache = selectContentForCache(enrichment.plainText, enrichment.lead, enrichment.title)
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
        val article = articleRepository.save(
            existing.copy(
                url = url,
                language = language,
                quote = input.quote,
                title = enrichment.title,
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
                publishedAt = publishedAt,
            )
        )
        val contentForCache = selectContentForCache(enrichment.plainText, enrichment.lead, enrichment.title)
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
        val (newTitle, newThumbnail, newLead) = if (urlChanged) {
            if (articleRepository.existsByUrl(newUrl)) throw ArticleUrlConflictException(newUrl)
            val enrichment = enrichmentService.enrich(newUrl)
            Triple(enrichment.title, enrichment.thumbnail, enrichment.lead)
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
        }

        return saved
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

    private fun selectContentForCache(plainText: String?, lead: String?, title: String?): String? {
        val bodyCandidates = listOfNotNull(plainText, lead)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Prefer body-like content over title to avoid collapsing rich snippets into short page titles.
        if (bodyCandidates.isNotEmpty()) {
            return bodyCandidates.maxByOrNull { contentQualityScore(it) }
        }

        return title?.trim()?.takeIf { it.isNotBlank() }
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
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://$authority$path$query"
        }

        private fun parseSortParam(sort: String): Pair<String, String> {
            val parts = sort.split(",")
            val field = parts.getOrNull(0)?.trim() ?: "createdAt"
            val dir = parts.getOrNull(1)?.trim() ?: "desc"
            return Pair(field, dir)
        }
    }
}
