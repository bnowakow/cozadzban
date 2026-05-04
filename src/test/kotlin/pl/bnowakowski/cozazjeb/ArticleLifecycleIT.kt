// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentResult
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZAZJEB_BOOTSTRAP_ADMIN_EMAIL=admin@lifecycle.test",
    ]
)
class ArticleLifecycleIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder

    // EnrichmentService is mocked to avoid real HTTP calls in tests
    @MockitoBean private lateinit var enrichmentService: EnrichmentService

    private val adminEmail = "admin@lifecycle.test"

    private val mockEnrichment = EnrichmentResult(
        title = "Mocked Title",
        thumbnail = "https://example.com/thumb.jpg",
        lead = "Mocked lead text",
    )

    // ─── Full article lifecycle ───────────────────────────────────────────────

    @Test
    fun `article lifecycle - create, read, update, delete`() {
        whenever(enrichmentService.enrich(any())).thenReturn(mockEnrichment)

        // 1. CREATE
        val createResponse = mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/lifecycle-test-${System.nanoTime()}","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("Mocked Title") }
            jsonPath("$.language") { value("en") }
            jsonPath("$.id") { exists() }
        }.andReturn()

        val createdId = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(createResponse.response.contentAsString)
            .get("id").asLong()

        assertNotNull(createdId)

        // 2. GET by ID
        mockMvc.get("/api/articles/$createdId")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(createdId) }
                jsonPath("$.title") { value("Mocked Title") }
            }

        // 3. PUT (replace)
        mockMvc.put("/api/articles/$createdId") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/lifecycle-replaced-${System.nanoTime()}","language":"pl"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.language") { value("pl") }
        }

        // 4. PATCH (partial update)
        mockMvc.patch("/api/articles/$createdId") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"language":"de"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.language") { value("de") }
        }

        // 5. DELETE
        mockMvc.delete("/api/articles/$createdId") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isNoContent() }
        }

        // 6. GET after delete returns 404
        mockMvc.get("/api/articles/$createdId")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET articles list is publicly accessible without auth`() {
        mockMvc.get("/api/articles")
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { isArray() }
            }
    }

    @Test
    fun `create article returns 409 on duplicate url`() {
        whenever(enrichmentService.enrich(any())).thenReturn(mockEnrichment)

        val url = "https://example.com/duplicate-url-${System.nanoTime()}"

        // First create
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"$url","language":"en"}"""
        }.andExpect { status { isCreated() } }

        // Second create with same URL → 409
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"$url","language":"pl"}"""
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozazjeb.pl/problems/article-url-conflict") }
        }
    }

    @Test
    fun `create article returns 400 on validation failure`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"not-a-url","language":"en"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `article pagination returns correct page metadata`() {
        mockMvc.get("/api/articles?page=0&size=5")
            .andExpect {
                status { isOk() }
                jsonPath("$.pageable.pageSize") { value(5) }
                jsonPath("$.pageable.pageNumber") { value(0) }
                jsonPath("$.size") { value(5) }
            }
    }

    @Test
    fun `db schema has expected tables after Flyway migration`() {
        val articleCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'article'",
            Long::class.java
        )
        val userCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'app_user'",
            Long::class.java
        )
        assertEquals(1L, articleCount, "article table should exist")
        assertEquals(1L, userCount, "app_user table should exist")
    }
}
