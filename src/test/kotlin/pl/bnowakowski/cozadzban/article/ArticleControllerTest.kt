// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.article

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import pl.bnowakowski.cozadzban.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozadzban.enrichment.EnrichmentException
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.Role
import pl.bnowakowski.cozadzban.user.AppUserStatus
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
    @TestPropertySource(
        properties = [
            "app.build.timestamp=2026-05-04T10:00:00Z",
            "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@article-controller.test",
            "app.machine-auth.enabled=true",
            "app.machine-auth.header-name=X-CoZaDzban-M2M-Key",
            "app.machine-auth.api-key=test-machine-key",
            "app.machine-auth.principal-email=bot@cozadzban.pl",
            NO_DATABASE_AUTOCONFIGURATION,
        ],
    )
class ArticleControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository
    @MockitoBean private lateinit var articleService: ArticleService

    private val userEmail = "user@test.com"
    private val adminEmail = "admin@test.com"
    private val strangerEmail = "stranger@test.com"
    private val machineEmail = "bot@cozadzban.pl"

    private val sampleArticle = Article(
        id = 1L,
        url = "https://example.com/article",
        language = "en",
        title = "Test Article",
        lead = "A test lead",
        thumbnail = null,
        favicon = "https://example.com/favicon.ico",
        quote = null,
        aiSummary = null,
        createdByUserId = 1L,
        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
    )

    private val samplePage = ArticlePage(
        content = listOf(sampleArticle),
        pageable = PageableInfo(0, 20, 0L, true, false, SortInfo(true, false)),
        totalElements = 1L,
        totalPages = 1,
        size = 20,
        number = 0,
        numberOfElements = 1,
        sort = SortInfo(true, false),
        first = true,
        last = true,
        empty = false,
    )

    @BeforeEach
    fun setup() {
        // BootstrapAdminService skips when admin count >= 1
        whenever(appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(AppUser(1L, userEmail, Role.USER))
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(AppUser(2L, adminEmail, Role.ADMIN))
        whenever(appUserRepository.findByEmail(machineEmail)).thenReturn(AppUser(3L, machineEmail, Role.USER))
        whenever(appUserRepository.findByEmail(strangerEmail)).thenReturn(null)
    }

    // ─── GET /api/articles ────────────────────────────────────────────────────

    @Test
    fun `GET articles returns 200 with article page`() {
        whenever(articleService.findPage(eq(0), eq(20), eq("createdAt,desc"), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(samplePage)

        mockMvc.get("/api/articles")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.content[0].id") { value(1) }
                jsonPath("$.totalElements") { value(1) }
                jsonPath("$.content[0].url") { value("https://example.com/article") }
                jsonPath("$.content[0].favicon") { value("https://example.com/favicon.ico") }
            }
    }

    @Test
    fun `GET articles returns 400 on invalid sort field`() {
        whenever(articleService.findPage(any(), any(), eq("badField,desc"), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenThrow(IllegalArgumentException("Invalid sort field 'badField'"))

        mockMvc.get("/api/articles?sort=badField,desc")
            .andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
    }

    // ─── GET /api/articles/{id} ───────────────────────────────────────────────

    @Test
    fun `GET article by id returns 200`() {
        whenever(articleService.findById(1L)).thenReturn(sampleArticle)

        mockMvc.get("/api/articles/1")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.id") { value(1) }
                jsonPath("$.language") { value("en") }
            }
    }

    @Test
    fun `GET article exists by url returns duplicate state`() {
        whenever(articleService.existsByUrl("https://example.com/article")).thenReturn(true)

        mockMvc.get("/api/articles?existsUrl=https://example.com/article")
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
                jsonPath("$.exists") { value(true) }
            }
    }

    @Test
    fun `GET article by id returns 404 when not found`() {
        whenever(articleService.findById(999L)).thenThrow(NoSuchElementException("Article 999 not found"))

        mockMvc.get("/api/articles/999")
            .andExpect {
                status { isNotFound() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            }
    }

    // ─── POST /api/articles ───────────────────────────────────────────────────

    @Test
    fun `POST article returns 201 for allowlisted user`() {
        whenever(articleService.create(any(), any())).thenReturn(sampleArticle)

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.url") { value("https://example.com/article") }
        }
    }

    @Test
    fun `POST article returns 201 for machine to machine api key`() {
        whenever(articleService.create(any(), any())).thenReturn(sampleArticle)

        mockMvc.post("/api/articles") {
            header("X-CoZaDzban-M2M-Key", "test-machine-key")
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
        }
    }

    @Test
    fun `POST article returns 400 on missing url`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"language":"en"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `POST article returns 400 on invalid url scheme`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"ftp://example.com/article","language":"en"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST article returns 400 on blank language`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":""}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST article returns 401 without token`() {
        mockMvc.post("/api/articles") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `POST article returns 403 for non-allowlisted user`() {
        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(strangerEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST article returns 409 on url conflict`() {
        whenever(articleService.create(any(), any()))
            .thenThrow(ArticleUrlConflictException("https://example.com/article"))

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozadzban.pl/problems/article-url-conflict") }
        }
    }

    @Test
    fun `POST article returns 422 on enrichment failure`() {
        whenever(articleService.create(any(), any()))
            .thenThrow(EnrichmentException("unreachable", EnrichmentException.Reason.UNREACHABLE))

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    // ─── PUT /api/articles/{id} ───────────────────────────────────────────────

    @Test
    fun `PUT article returns 200`() {
        whenever(articleService.replace(eq(1L), any())).thenReturn(sampleArticle)

        mockMvc.put("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
        }
    }

    @Test
    fun `PUT article returns 401 without token`() {
        mockMvc.put("/api/articles/1") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `PUT article returns 404 when not found`() {
        whenever(articleService.replace(eq(999L), any()))
            .thenThrow(NoSuchElementException("Article 999 not found"))

        mockMvc.put("/api/articles/999") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `PUT article returns 409 on url conflict`() {
        whenever(articleService.replace(eq(1L), any()))
            .thenThrow(ArticleUrlConflictException("https://example.com/article"))

        mockMvc.put("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `PUT article returns 422 on enrichment failure`() {
        whenever(articleService.replace(eq(1L), any()))
            .thenThrow(EnrichmentException("non-2xx", EnrichmentException.Reason.NON_2XX, 503))

        mockMvc.put("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }

    // ─── PATCH /api/articles/{id} ─────────────────────────────────────────────

    @Test
    fun `PATCH article returns 200`() {
        whenever(articleService.patch(eq(1L), any())).thenReturn(sampleArticle)

        mockMvc.patch("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"language":"pl"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
        }
    }

    @Test
    fun `PATCH article returns 200 for machine to machine api key`() {
        whenever(articleService.patch(eq(1L), any())).thenReturn(sampleArticle)

        mockMvc.patch("/api/articles/1") {
            header("X-CoZaDzban-M2M-Key", "test-machine-key")
            contentType = MediaType("application", "merge-patch+json")
            content = """{"content":"remote imported text"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(1) }
        }
    }

    @Test
    fun `PATCH article returns 400 when url is null`() {
        whenever(articleService.patch(eq(1L), any()))
            .thenThrow(IllegalArgumentException("url must not be null"))

        mockMvc.patch("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"url":null}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `PATCH article returns 401 without token`() {
        mockMvc.patch("/api/articles/1") {
            contentType = MediaType("application", "merge-patch+json")
            content = """{"language":"pl"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `PATCH article returns 404 when not found`() {
        whenever(articleService.patch(eq(999L), any()))
            .thenThrow(NoSuchElementException("Article 999 not found"))

        mockMvc.patch("/api/articles/999") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"language":"pl"}"""
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `PATCH article returns 409 on url conflict`() {
        whenever(articleService.patch(eq(1L), any()))
            .thenThrow(ArticleUrlConflictException("https://example.com/existing"))

        mockMvc.patch("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"url":"https://example.com/existing"}"""
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `PATCH article returns 422 on enrichment failure`() {
        whenever(articleService.patch(eq(1L), any()))
            .thenThrow(EnrichmentException("timeout", EnrichmentException.Reason.TIMEOUT))

        mockMvc.patch("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType("application", "merge-patch+json")
            content = """{"url":"https://example.com/new"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }

    // ─── DELETE /api/articles/{id} ────────────────────────────────────────────

    @Test
    fun `DELETE article returns 204`() {
        mockMvc.delete("/api/articles/1") {
            with(jwt().jwt { it.subject(userEmail) })
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `DELETE article returns 401 without token`() {
        mockMvc.delete("/api/articles/1")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `DELETE article returns 403 for non-allowlisted user`() {
        mockMvc.delete("/api/articles/1") {
            with(jwt().jwt { it.subject(strangerEmail) })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `DELETE article returns 404 when not found`() {
        whenever(articleService.delete(999L))
            .thenThrow(NoSuchElementException("Article 999 not found"))

        mockMvc.delete("/api/articles/999") {
            with(jwt().jwt { it.subject(userEmail) })
        }.andExpect {
            status { isNotFound() }
        }
    }
}
