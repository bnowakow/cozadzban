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
) {

    @Transactional(readOnly = true)
    fun findById(id: Long): Article =
        articleRepository.findById(id).orElseThrow { NoSuchElementException("Article $id not found") }

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
        return articleRepository.save(
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
    }

    fun replace(id: Long, input: ArticleInput): Article {
        val existing = findById(id)
        val url = canonicalizeUrl(input.url)
        val language = normalizeLanguage(input.language)
        if (url != existing.url && articleRepository.existsByUrl(url)) throw ArticleUrlConflictException(url)
        val enrichment = enrichmentService.enrich(url)
        val publishedAt = input.publishedAt ?: enrichment.publishedAt
        return articleRepository.save(
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
    }

    fun patch(id: Long, patch: Map<String, Any?>): Article {
        val existing = findById(id)

        val urlPresent = patch.containsKey("url")
        val languagePresent = patch.containsKey("language")
        val quotePresent = patch.containsKey("quote")
        val publishedAtPresent = patch.containsKey("publishedAt")

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

        return articleRepository.save(
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
    }

    fun delete(id: Long) {
        if (!articleRepository.existsById(id)) throw NoSuchElementException("Article $id not found")
        articleRepository.deleteById(id)
    }

    companion object {
        private val BCP47_PATTERN = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*\$")

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
