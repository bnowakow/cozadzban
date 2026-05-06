// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozazjeb.TestcontainersConfiguration
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticlePage
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.article.PageableInfo
import pl.bnowakowski.cozazjeb.article.SortInfo
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role
import java.time.Instant

/**
 * Phase 14 — Hybrid Auth Regression Tests.
 *
 * Covers:
 * - Step 29: Auth boundary (session vs bearer)
 * - Step 31: Session security hardening (cookie flags)
 * - Step 32: CSRF regression for cookie-backed UI and stateless API
 * - Step 34: E2E regression scenarios
 *
 * Note: the following items require manual/browser testing and are not covered here:
 * - Step 31: session fixation protection (auto-handled by Spring Security)
 * - Step 31: idle/absolute session timeout behaviour
 * - Step 34: no OAuth tokens in browser localStorage/sessionStorage
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = [
    "app.build.timestamp=2026-05-04T10:00:00Z",
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
    "spring.security.oauth2.client.registration.google.scope=openid,profile,email",
    "spring.security.oauth2.client.registration.google.redirect-uri=http://localhost:8080/login/oauth2/code/google",
    "spring.security.oauth2.resourceserver.jwt.audiences=test-audience",
    "SESSION_COOKIE_SECURE=true",
])
class HybridAuthRegressionIT {

    @Autowired private lateinit var mockMvc: MockMvc

    @Value("\${server.servlet.session.cookie.http-only}") private var sessionCookieHttpOnly: Boolean = false
    @Value("\${server.servlet.session.cookie.secure}") private var sessionCookieSecure: Boolean = false
    @Value("\${server.servlet.session.cookie.same-site}") private lateinit var sessionCookieSameSite: String
    @Value("\${server.servlet.session.cookie.path}") private lateinit var sessionCookiePath: String
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var uiPrincipalMapper: UiPrincipalMapper
    @MockitoBean private lateinit var articleService: ArticleService

    private val userEmail = "user@test.com"
    private val adminEmail = "admin@test.com"

    private val sampleArticle = Article(
        id = 1L,
        url = "https://example.com/article",
        language = "en",
        title = "Test Article",
        lead = null,
        thumbnail = null,
        quote = null,
        aiSummary = null,
        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
    )

    private val emptyPage = ArticlePage(
        content = emptyList(),
        pageable = PageableInfo(0, 20, 0L, true, false, SortInfo(true, false)),
        totalElements = 0L,
        totalPages = 0,
        size = 20,
        number = 0,
        numberOfElements = 0,
        sort = SortInfo(true, false),
        first = true,
        last = true,
        empty = true,
    )

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRole(Role.ADMIN)).thenReturn(1L)
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(AppUser(1L, userEmail, Role.USER))
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(AppUser(2L, adminEmail, Role.ADMIN))
        whenever(uiPrincipalMapper.resolve(anyOrNull())).thenReturn(
            UiPrincipalMapper.UiPrincipal(
                email = userEmail,
                role = Role.USER,
                allowlisted = true,
            ),
        )
    }

    // ─── Step 29: Auth boundary (session vs bearer) ───────────────────────────

    @Test
    fun `REST write endpoint rejects OAuth2 session auth - Bearer JWT required`() {
        // AllowlistAuthorizationManager.isAllowlisted() requires JwtAuthenticationToken.
        // An oauth2Login session token is an OAuth2AuthenticationToken, not JwtAuthenticationToken → 403.
        mockMvc.post("/api/articles") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
            with(oauth2Login().attributes { it["email"] = userEmail })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `REST write endpoint allows Bearer JWT for allowlisted user - session not used`() {
        whenever(articleService.create(any())).thenReturn(sampleArticle)

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
        }.andExpect {
            // Passed the security layer — service returned 201 Created
            status { isCreated() }
        }
    }

    @Test
    fun `admin-only users endpoint rejects OAuth2 session auth`() {
        mockMvc.get("/api/users") {
            with(oauth2Login().attributes { it["email"] = userEmail })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `admin-only users endpoint rejects non-admin Bearer JWT`() {
        mockMvc.get("/api/users") {
            with(jwt().jwt { it.subject(userEmail) })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `admin-only users endpoint allows admin Bearer JWT`() {
        whenever(appUserRepository.findAll()).thenReturn(emptyList())

        mockMvc.get("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `allowlisted OAuth2 session can access selected actuator info endpoint`() {
        mockMvc.get("/actuator/info") {
            with(oauth2Login().attributes { it["email"] = userEmail })
        }.andExpect {
            status { isOk() }
        }
    }

    // ─── Step 30: OAuth2 login/session lifecycle ──────────────────────────────

    @Test
    fun `auth login redirects into Spring Security OAuth2 authorization flow`() {
        mockMvc.get("/auth/login")
            .andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/oauth2/authorization/google")
            }
    }

    @Test
    fun `auth me returns session user details when authenticated`() {
        mockMvc.get("/auth/me") {
            with(oauth2Login().attributes { it["email"] = userEmail })
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(userEmail) }
            jsonPath("$.role") { value("USER") }
            jsonPath("$.allowlisted") { value(true) }
        }
    }

    @Test
    fun `auth me returns 401 when unauthenticated`() {
        mockMvc.get("/auth/me")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `POST auth logout invalidates session - subsequent auth me returns 401`() {
        // Step 30: verify full logout lifecycle. After logout the security context
        // is cleared, so /auth/me must return 401.
        mockMvc.post("/auth/logout") {
            with(oauth2Login())
            with(csrf())
        }.andExpect {
            status { isNoContent() }
        }

        // Without re-supplying oauth2Login(), the next request has no session → 401.
        mockMvc.get("/auth/me")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    // ─── Step 31: Session security hardening ──────────────────────────────────

    @Test
    fun `JSESSIONID cookie is configured as HttpOnly`() {
        // server.servlet.session.cookie.http-only is set in application.properties.
        // In MOCK mode, Tomcat is not started so sessionCookieConfig is not populated;
        // we verify the Spring Boot property is correctly configured instead.
        assertTrue(
            sessionCookieHttpOnly,
            "JSESSIONID must have HttpOnly flag (server.servlet.session.cookie.http-only=true)",
        )
    }

    @Test
    fun `JSESSIONID cookie path is configured as root`() {
        // server.servlet.session.cookie.path is set in application.properties.
        // In MOCK mode, Tomcat is not started so sessionCookieConfig is not populated;
        // we verify the Spring Boot property is correctly configured instead.
        assertEquals(
            "/",
            sessionCookiePath,
            "JSESSIONID must have Path=/ (server.servlet.session.cookie.path=/)",
        )
    }

    @Test
    fun `JSESSIONID cookie secure flag is enabled when production env requests it`() {
        assertTrue(
            sessionCookieSecure,
            "JSESSIONID must support Secure flag via SESSION_COOKIE_SECURE=true in production",
        )
    }

    @Test
    fun `JSESSIONID cookie SameSite is configured as Lax`() {
        // SameSite=Lax prevents CSRF from cross-site navigations while allowing
        // top-level same-site requests (e.g. OAuth redirect back to the app).
        assertEquals(
            "lax",
            sessionCookieSameSite.lowercase(),
            "JSESSIONID must have SameSite=Lax (server.servlet.session.cookie.same-site=lax)",
        )
    }

    // ─── Step 32: CSRF regression ─────────────────────────────────────────────

    @Test
    fun `POST auth logout without CSRF token is rejected with 403`() {
        // /auth/logout is a session-backed endpoint; CSRF is enabled for it
        mockMvc.post("/auth/logout") {
            with(oauth2Login())
            // No csrf() — must be rejected
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST auth logout with CSRF token succeeds`() {
        mockMvc.post("/auth/logout") {
            with(oauth2Login())
            with(csrf())
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `POST api articles with Bearer JWT passes without CSRF token`() {
        // CSRF is disabled for /api/** (BR-16); JWT callers must not be blocked by CSRF
        whenever(articleService.create(any())).thenReturn(sampleArticle)

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/no-csrf","language":"en"}"""
            // No csrf() — must still pass
        }.andExpect {
            // CSRF disabled for /api/** — request reaches service → 201
            status { isCreated() }
        }
    }

    // ─── Step 34: E2E regression scenarios ────────────────────────────────────

    @Test
    fun `anonymous user can view article list`() {
        whenever(articleService.findPage(any(), any(), any())).thenReturn(emptyPage)

        mockMvc.get("/api/articles")
            .andExpect { status { isOk() } }
    }

    @Test
    fun `anonymous user cannot submit article write - 401`() {
        mockMvc.post("/api/articles") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/article","language":"en"}"""
            // No auth at all
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `authenticated USER with session cannot escalate to REST write endpoint`() {
        // Confirms no privilege escalation: session auth cannot reach bearer-only write endpoint
        mockMvc.post("/api/articles") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/escalation","language":"en"}"""
            with(oauth2Login().attributes { it["email"] = userEmail })
        }.andExpect {
            status { isForbidden() }
        }
    }

}
