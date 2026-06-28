// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozadzban.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserService
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-24T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@facebook-proposals.test",
        "app.machine-auth.enabled=true",
        "app.machine-auth.header-name=X-CoZaDzban-M2M-Key",
        "app.machine-auth.api-key=secret",
        "app.machine-auth.principal-email=facebook-import-bot@cozadzban.pl",
        "app.facebook-import.enabled=false",
        "app.facebook-import.selenium.enabled=false",
        "app.facebook-import.apify.enabled=false",
        "app.facebook-import.target-api-base-url=",
        "app.facebook-import.target-api-key=",
        NO_DATABASE_AUTOCONFIGURATION,
    ],
)
class FacebookArticleProposalControllerTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository
    @MockitoBean private lateinit var articleService: ArticleService
    @MockitoBean private lateinit var articleContentRepository: ArticleContentRepository
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var facebookProfileArticleImporter: FacebookProfileArticleImporter
    @MockitoBean private lateinit var proposalService: FacebookArticleProposalService

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
        whenever(appUserRepository.findByEmail("facebook-import-bot@cozadzban.pl")).thenReturn(
            AppUser(10L, "facebook-import-bot@cozadzban.pl", Role.USER),
        )
        whenever(appUserRepository.findByEmail("admin@test.com")).thenReturn(
            AppUser(1L, "admin@test.com", Role.ADMIN),
        )
    }

    @Test
    fun `proposal exists endpoint accepts machine credential`() {
        whenever(proposalService.existsByArticleUrl("https://example.com/story")).thenReturn(true)

        mockMvc.get("/api/facebook-import/proposals/exists") {
            header("X-CoZaDzban-M2M-Key", "secret")
            param("url", "https://example.com/story")
        }.andExpect {
            status { isOk() }
            jsonPath("$.exists") { value(true) }
        }
    }

    @Test
    fun `proposal batch endpoint accepts machine credential`() {
        whenever(proposalService.submitBatch(any())).thenReturn(
            FacebookProposalBatchResponse("run-1", submitted = 1, skippedExisting = 0),
        )

        mockMvc.post("/api/facebook-import/proposals") {
            header("X-CoZaDzban-M2M-Key", "secret")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "importRunId": "run-1",
                  "passIndex": 1,
                  "passCount": 1,
                  "proposals": [
                    {
                      "candidateId": "candidate-1",
                      "articleUrl": "https://example.com/story",
                      "facebookPostUrl": "https://www.facebook.com/source/posts/1",
                      "language": "pl",
                      "logs": "candidate logs"
                    }
                  ]
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.submitted") { value(1) }
            jsonPath("$.skippedExisting") { value(0) }
        }
    }

    @Test
    fun `proposal endpoint rejects normal bearer auth`() {
        mockMvc.get("/api/facebook-import/proposals/exists") {
            with(jwt().jwt {
                it.subject("admin@test.com")
                it.claim("email", "admin@test.com")
                it.claim("email_verified", true)
            })
            param("url", "https://example.com/story")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `login required endpoint accepts machine credential`() {
        mockMvc.post("/api/facebook-import/runs/run-1/login-required") {
            header("X-CoZaDzban-M2M-Key", "secret")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "trigger": "SCHEDULED",
                  "profileUrl": "https://www.facebook.com/profile",
                  "detectedAt": "2026-05-24T10:00:00Z"
                }
            """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }

        verify(proposalService).recordLoginRequired(eq("run-1"), any())
    }

    @Test
    fun `progress endpoint accepts machine credential`() {
        mockMvc.post("/api/facebook-import/runs/run-1/progress") {
            header("X-CoZaDzban-M2M-Key", "secret")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "phase": "Sending proposals",
                  "phaseIndex": 8,
                  "phaseCount": 8,
                  "passIndex": 2,
                  "passCount": 3,
                  "matchedPostCount": 5,
                  "submittedCount": 2,
                  "skippedExistingCount": 1,
                  "failedCount": 0,
                  "occurredAt": "2026-05-24T10:00:00Z"
                }
            """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }

        verify(proposalService).recordProgress(eq("run-1"), any())
    }

    @Test
    fun `startup cleanup endpoint accepts machine credential`() {
        whenever(proposalService.terminateAbandonedRunsOnStartup(any())).thenReturn(listOf("run-abandoned"))

        mockMvc.post("/api/facebook-import/runs/abandoned-startup-cleanup") {
            header("X-CoZaDzban-M2M-Key", "secret")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "startedAt": "2026-05-24T10:00:00Z"
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.terminatedRunIds[0]") { value("run-abandoned") }
        }

        verify(proposalService).terminateAbandonedRunsOnStartup(any())
    }

    @Test
    fun `login required endpoint rejects normal bearer auth`() {
        mockMvc.post("/api/facebook-import/runs/run-1/login-required") {
            with(jwt().jwt {
                it.subject("admin@test.com")
                it.claim("email", "admin@test.com")
                it.claim("email_verified", true)
            })
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "trigger": "SCHEDULED",
                  "profileUrl": "https://www.facebook.com/profile"
                }
            """.trimIndent()
        }.andExpect {
            status { isForbidden() }
        }
    }
}
