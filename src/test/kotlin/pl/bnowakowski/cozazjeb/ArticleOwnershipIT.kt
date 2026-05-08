// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pl.bnowakowski.cozazjeb.article.ArticleContentRepository
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentResult
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.AppUserInput
import pl.bnowakowski.cozazjeb.user.Role
import java.time.Instant

/**
 * Phase 21 / Item 66 — Article tests: creator, language, publishedAt, filters, sorting.
 *
 * Verifies:
 * - Creator is assigned from authenticated user on create
 * - Creator is absent from JSON responses (JsonIgnore)
 * - PUT/PATCH cannot change createdByUserId
 * - Language normalization (uppercase → lowercase, BCP-47 subtags)
 * - Language validation (invalid tags → 400)
 * - Manual publishedAt on create overrides enrichment
 * - Enrichment publishedAt is used when not manually provided
 * - PATCH can set and clear publishedAt
 * - Filter by language returns only matching articles
 * - Filter by publishedFrom excludes articles published before the cutoff
 * - Filter by createdAt range works
 * - Sorting by publishedAt, createdAt, id
 * - Sort by unknown field returns 400
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZAZJEB_BOOTSTRAP_ADMIN_EMAIL=admin@ownership.test",
    ],
)
class ArticleOwnershipIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var appUserService: AppUserService
    @Autowired private lateinit var articleRepository: ArticleRepository
    @Autowired private lateinit var articleContentRepository: ArticleContentRepository

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var enrichmentService: EnrichmentService

    private val adminEmail = "admin@ownership.test"

    private val baseEnrichment = EnrichmentResult(
        title = "Mocked Title",
        thumbnail = "https://example.com/thumb.jpg",
        lead = "Mocked lead",
    )

    @BeforeEach
    fun setupEnrichment() {
        whenever(enrichmentService.enrich(any())).thenReturn(baseEnrichment)
    }

    private fun uniqueUrl(tag: String = "") = "https://ownership.test/${tag.ifBlank { "a" }}-${System.nanoTime()}"

    private fun createArticle(lang: String = "en", url: String = uniqueUrl(), publishedAt: String? = null): Long {
        val body = buildString {
            append("""{"url":"$url","language":"$lang"""")
            if (publishedAt != null) append(""","publishedAt":"$publishedAt"""")
            append("}")
        }
        val response = mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }.andReturn()
        return ObjectMapper().readTree(response.response.contentAsString).get("id").asLong()
    }

    // ─── Creator assignment ───────────────────────────────────────────────────

    @Test
    fun `article creator is set to the authenticated user on create`() {
        val adminUser = appUserRepository.findByEmail(adminEmail)!!
        val id = createArticle()

        val saved = articleRepository.findById(id).orElseThrow()
        assertEquals(adminUser.id, saved.createdByUserId)
    }

    @Test
    fun `cache is stored from lead when plainText is empty`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = "Facebook Title",
                thumbnail = null,
                lead = "Facebook lead preview",
                plainText = null,
            ),
        )

        val id = createArticle(url = uniqueUrl("fb-fallback"))

        val content = articleContentRepository.findById(id).orElseThrow()
        assertEquals("Facebook lead preview", content.content)
    }

    @Test
    fun `cache prefers rich lead over truncated plainText teaser`() {
        val teaser = "Pete Hegseth has fired 24 generals. Now he brings his wife to Pentagon meetings..."
        val fullLead = "Pete Hegseth has fired 24 generals. Now he brings his wife to Pentagon meetings. " +
            "The Guardian published a major investigation Sunday. " +
            "The headline finding: Hegseth's third wife, Jennifer Rauchet, has been showing up to Pentagon meetings."

        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(
                title = "Some title",
                thumbnail = null,
                lead = fullLead,
                plainText = teaser,
            ),
        )

        val id = createArticle(url = uniqueUrl("fb-rich-lead"))

        val content = articleContentRepository.findById(id).orElseThrow()
        assertEquals(fullLead, content.content)
    }

    @Test
    fun `article JSON response does not expose createdByUserId`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl()}","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.createdByUserId") { doesNotExist() }
        }
    }

    @Test
    fun `PUT article does not change creator`() {
        val adminUser = appUserRepository.findByEmail(adminEmail)!!
        val id = createArticle()

        // Create a second user and attempt to pass their id in the PUT body (should be ignored)
        val other = appUserService.create(AppUserInput("other@ownership.test", Role.USER))

        mockMvc.put("/api/articles/$id") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl()}","language":"pl","createdByUserId":${other.id}}"""
        }.andExpect { status { isOk() } }

        val updated = articleRepository.findById(id).orElseThrow()
        assertEquals(adminUser.id, updated.createdByUserId, "Creator must remain unchanged after PUT")
    }

    @Test
    fun `PATCH article does not change creator`() {
        val adminUser = appUserRepository.findByEmail(adminEmail)!!
        val id = createArticle()

        val other = appUserService.create(AppUserInput("patchother@ownership.test", Role.USER))

        mockMvc.patch("/api/articles/$id") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"language":"de","createdByUserId":${other.id}}"""
        }.andExpect { status { isOk() } }

        val updated = articleRepository.findById(id).orElseThrow()
        assertEquals(adminUser.id, updated.createdByUserId, "Creator must remain unchanged after PATCH")
    }

    // ─── Language normalization ───────────────────────────────────────────────

    @Test
    fun `language tag is normalized to lowercase on create`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("lang-upper")}","language":"EN"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.language") { value("en") }
        }
    }

    @Test
    fun `BCP-47 subtag is normalized to lowercase on create`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("lang-bcp47")}","language":"PT-BR"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.language") { value("pt-br") }
        }
    }

    @Test
    fun `language tag longer than three base chars returns 400`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("lang-long")}","language":"english"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `single-char language tag returns 400`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("lang-one")}","language":"e"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `language tag with special chars returns 400`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("lang-special")}","language":"en_US"}"""
        }.andExpect { status { isBadRequest() } }
    }

    // ─── Publication date ─────────────────────────────────────────────────────

    @Test
    fun `manual publishedAt overrides enrichment value on create`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            baseEnrichment.copy(publishedAt = Instant.parse("2025-01-01T00:00:00Z")),
        )

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("pub-override")}","language":"en","publishedAt":"2024-06-15T12:00:00Z"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.publishedAt") { value("2024-06-15T12:00:00Z") }
        }
    }

    @Test
    fun `enriched publishedAt is stored when not provided manually`() {
        val enrichedDate = "2025-03-10T08:00:00Z"
        whenever(enrichmentService.enrich(any())).thenReturn(
            baseEnrichment.copy(publishedAt = Instant.parse(enrichedDate)),
        )

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("pub-enriched")}","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.publishedAt") { value(enrichedDate) }
        }
    }

    @Test
    fun `article without enriched publishedAt has null publishedAt`() {
        // baseEnrichment has no publishedAt
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"${uniqueUrl("pub-null")}","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            // ArticleResponse uses @JsonInclude(NON_NULL) — null fields are omitted
            jsonPath("$.publishedAt") { doesNotExist() }
        }
    }

    @Test
    fun `PATCH can set publishedAt`() {
        val id = createArticle(url = uniqueUrl("pub-set"))

        mockMvc.patch("/api/articles/$id") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"publishedAt":"2026-01-01T00:00:00Z"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.publishedAt") { value("2026-01-01T00:00:00Z") }
        }
    }

    @Test
    fun `PATCH can clear publishedAt with explicit null`() {
        val id = createArticle(url = uniqueUrl("pub-clear"), publishedAt = "2025-06-01T10:00:00Z")

        mockMvc.patch("/api/articles/$id") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"publishedAt":null}"""
        }.andExpect {
            status { isOk() }
            // ArticleResponse uses @JsonInclude(NON_NULL) — null fields are omitted
            jsonPath("$.publishedAt") { doesNotExist() }
        }
    }

    // ─── Filters ─────────────────────────────────────────────────────────────

    @Test
    fun `language filter returns only articles matching that language`() {
        // Create one article in a language unlikely used by other tests in this context
        createArticle(lang = "fi", url = uniqueUrl("filter-fi"))

        mockMvc.get("/api/articles?language=fi")
            .andExpect {
                status { isOk() }
                // Every returned article must have language "fi"
                jsonPath("$.content[?(@.language != 'fi')]") { isEmpty() }
                // At least one result
                jsonPath("$.totalElements") { value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)) }
            }
    }

    @Test
    fun `publishedFrom filter excludes articles published before the cutoff`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            baseEnrichment.copy(publishedAt = Instant.parse("2020-06-01T00:00:00Z")),
        )
        val id = createArticle(url = uniqueUrl("filter-pubfrom"))

        // Filter from 2025 — this article published in 2020 should be absent
        mockMvc.get("/api/articles?publishedFrom=2025-01-01T00:00:00Z")
            .andExpect {
                status { isOk() }
                jsonPath("$.content[?(@.id == $id)]") { isEmpty() }
            }
    }

    @Test
    fun `publishedTo filter excludes articles published after the cutoff`() {
        val futureDate = "2030-01-01T00:00:00Z"
        val id = createArticle(url = uniqueUrl("filter-pubto"), publishedAt = futureDate)

        // Filter to 2026 — this article published in 2030 should be absent
        mockMvc.get("/api/articles?publishedTo=2026-12-31T23:59:59Z")
            .andExpect {
                status { isOk() }
                jsonPath("$.content[?(@.id == $id)]") { isEmpty() }
            }
    }

    // ─── Sorting ──────────────────────────────────────────────────────────────

    @Test
    fun `sort by publishedAt desc is accepted`() {
        mockMvc.get("/api/articles?sort=publishedAt,desc")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `sort by createdAt asc is accepted`() {
        mockMvc.get("/api/articles?sort=createdAt,asc")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `sort by id asc is accepted`() {
        mockMvc.get("/api/articles?sort=id,asc")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `sort by id asc returns articles in ascending id order`() {
        // Create a couple of articles to have at least two in the DB
        createArticle(url = uniqueUrl("sort1"))
        createArticle(url = uniqueUrl("sort2"))

        val response = mockMvc.get("/api/articles?sort=id,asc&size=100")
            .andExpect { status { isOk() } }
            .andReturn()

        val content = ObjectMapper().readTree(response.response.contentAsString).get("content")
        val ids = (0 until content.size()).map { content[it].get("id").asLong() }
        assertEquals(ids.sorted(), ids, "Articles should be sorted by id ascending")
    }

    @Test
    fun `sort by unknown field returns 400`() {
        mockMvc.get("/api/articles?sort=creatorEmail,asc")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sort by title desc is accepted`() {
        mockMvc.get("/api/articles?sort=title,desc")
            .andExpect { status { isOk() } }
    }

    // ─── Phase 21 / Item 66 — Thumbnail extraction ────────────────────────────

    @Test
    fun `thumbnail from og image is stored and returned in article response`() {
        val expectedThumb = "https://example.com/og-image.jpg"
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(title = "Thumb Title", thumbnail = expectedThumb, lead = "Some lead"),
        )

        val id = createArticle()

        mockMvc.get("/api/articles/$id")
            .andExpect {
                status { isOk() }
                jsonPath("$.thumbnail") { value(expectedThumb) }
            }
    }

    @Test
    fun `article without og image has null thumbnail`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(title = "No Thumb", thumbnail = null, lead = null),
        )

        val id = createArticle()

        mockMvc.get("/api/articles/$id")
            .andExpect {
                status { isOk() }
                jsonPath("$.thumbnail") { doesNotExist() }
            }
    }
}
