// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import java.net.URI

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
    fun findPage(page: Int, size: Int, sort: String): ArticlePage {
        require(page >= 0) { "page must be >= 0" }
        require(size in 1..100) { "size must be between 1 and 100" }

        val (sortField, sortDirection) = parseSortParam(sort)
        val totalElements = articleRepository.count()
        val totalPages = ((totalElements + size - 1) / size).toInt().coerceAtLeast(0)
        val content = articleRepository.findPage(page, size, sortField, sortDirection)
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
        if (articleRepository.existsByUrl(url)) throw ArticleUrlConflictException(url)
        val enrichment = enrichmentService.enrich(url)
        return articleRepository.save(
            Article(
                url = url,
                language = input.language,
                quote = input.quote,
                title = enrichment.title,
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
                createdByUserId = creatorId,
            )
        )
    }

    fun replace(id: Long, input: ArticleInput): Article {
        val existing = findById(id)
        val url = canonicalizeUrl(input.url)
        if (url != existing.url && articleRepository.existsByUrl(url)) throw ArticleUrlConflictException(url)
        val enrichment = enrichmentService.enrich(url)
        return articleRepository.save(
            existing.copy(
                url = url,
                language = input.language,
                quote = input.quote,
                title = enrichment.title,
                thumbnail = enrichment.thumbnail,
                lead = enrichment.lead,
            )
        )
    }

    fun patch(id: Long, patch: Map<String, Any?>): Article {
        val existing = findById(id)

        val urlPresent = patch.containsKey("url")
        val languagePresent = patch.containsKey("language")
        val quotePresent = patch.containsKey("quote")

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
            lang
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
            )
        )
    }

    fun delete(id: Long) {
        if (!articleRepository.existsById(id)) throw NoSuchElementException("Article $id not found")
        articleRepository.deleteById(id)
    }

    companion object {
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
            val path = uri.path ?: ""
            val effectivePath = if (path == "/") "" else path
            return buildString {
                append("$scheme://$authority$effectivePath")
                uri.rawQuery?.let { if (it.isNotEmpty()) append("?$it") }
                // fragment is intentionally omitted (BR-14)
            }
        }

        private fun parseSortParam(sort: String): Pair<String, String> {
            val parts = sort.split(",")
            val field = parts[0].trim()
            val direction = if (parts.size > 1) parts[1].trim() else "desc"
            return field to direction
        }
    }
}
