// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
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
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozazjeb.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.article.ArticleContentRepository
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.Role

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        NO_DATABASE_AUTOCONFIGURATION,
    ],
)
class FacebookImportControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository
    @MockitoBean private lateinit var articleService: ArticleService
    @MockitoBean private lateinit var articleContentRepository: ArticleContentRepository
    @MockitoBean private lateinit var appUserService: AppUserService
    @MockitoBean private lateinit var facebookProfileArticleImporter: FacebookProfileArticleImporter

    private val adminEmail = "admin@test.com"
    private val userEmail = "user@test.com"

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(AppUser(1L, adminEmail, Role.ADMIN))
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(AppUser(2L, userEmail, Role.USER))
    }

    @Test
    fun `run endpoint returns 202 for admin bearer token`() {
        doNothing().whenever(facebookProfileArticleImporter).startImport()

        mockMvc.post("/api/admin/facebook-import/run") {
            with(jwt().jwt {
                it.subject(adminEmail)
                it.claim("email", adminEmail)
                it.claim("email_verified", true)
            })
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isAccepted() }
        }
    }

    @Test
    fun `run endpoint returns 401 without token`() {
        mockMvc.post("/api/admin/facebook-import/run")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `run endpoint returns 403 for non-admin bearer token`() {
        mockMvc.post("/api/admin/facebook-import/run") {
            with(jwt().jwt {
                it.subject(userEmail)
                it.claim("email", userEmail)
                it.claim("email_verified", true)
            })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `run endpoint returns 409 when import is already active`() {
        doThrow(FacebookImportAlreadyRunningException()).whenever(facebookProfileArticleImporter).startImport()

        mockMvc.post("/api/admin/facebook-import/run") {
            with(jwt().jwt {
                it.subject(adminEmail)
                it.claim("email", adminEmail)
                it.claim("email_verified", true)
            })
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozazjeb.pl/problems/facebook-import-busy") }
        }
    }

    @Test
    fun `terminate endpoint returns 202 for admin bearer token`() {
        doNothing().whenever(facebookProfileArticleImporter).terminateImport()

        mockMvc.post("/api/admin/facebook-import/terminate") {
            with(jwt().jwt {
                it.subject(adminEmail)
                it.claim("email", adminEmail)
                it.claim("email_verified", true)
            })
        }.andExpect {
            status { isAccepted() }
        }
    }

    @Test
    fun `terminate endpoint returns 409 when nothing is running`() {
        doThrow(FacebookImportNotRunningException()).whenever(facebookProfileArticleImporter).terminateImport()

        mockMvc.post("/api/admin/facebook-import/terminate") {
            with(jwt().jwt {
                it.subject(adminEmail)
                it.claim("email", adminEmail)
                it.claim("email_verified", true)
            })
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozazjeb.pl/problems/facebook-import-not-running") }
        }
    }
}
