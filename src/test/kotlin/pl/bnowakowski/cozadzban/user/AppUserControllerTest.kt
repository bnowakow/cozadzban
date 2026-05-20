// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.user

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import pl.bnowakowski.cozadzban.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@app-user-controller.test",
        NO_DATABASE_AUTOCONFIGURATION,
    ],
)
class AppUserControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository
    @MockitoBean private lateinit var articleService: ArticleService
    @MockitoBean private lateinit var appUserService: AppUserService

    private val adminEmail = "admin@test.com"
    private val userEmail = "user@test.com"
    private val strangerEmail = "stranger@test.com"

    private val sampleUser = AppUser(
        id = 1L,
        email = userEmail,
        role = Role.USER,
        createdAt = Instant.parse("2026-05-04T10:00:00Z"),
    )

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(AppUser(2L, adminEmail, Role.ADMIN))
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(AppUser(1L, userEmail, Role.USER))
        whenever(appUserRepository.findByEmail(strangerEmail)).thenReturn(null)
    }

    // ─── GET /api/users ───────────────────────────────────────────────────────

    @Test
    fun `GET users returns 200 for admin`() {
        whenever(appUserService.list()).thenReturn(listOf(sampleUser))

        mockMvc.get("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$[0].id") { value(1) }
            jsonPath("$[0].email") { value(userEmail) }
        }
    }

    @Test
    fun `GET users returns 401 without token`() {
        mockMvc.get("/api/users")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `GET users returns 403 for non-admin user`() {
        mockMvc.get("/api/users") {
            with(jwt().jwt { it.subject(userEmail) })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `GET users returns 403 for non-allowlisted user`() {
        mockMvc.get("/api/users") {
            with(jwt().jwt { it.subject(strangerEmail) })
        }.andExpect {
            status { isForbidden() }
        }
    }

    // ─── POST /api/users ──────────────────────────────────────────────────────

    @Test
    fun `POST users returns 201 for admin`() {
        whenever(appUserService.create(any())).thenReturn(sampleUser)

        mockMvc.post("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"user@test.com","role":"USER"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(1) }
            jsonPath("$.email") { value(userEmail) }
        }
    }

    @Test
    fun `POST users returns 400 on invalid email`() {
        mockMvc.post("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"not-an-email","role":"USER"}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }

    @Test
    fun `POST users returns 400 on blank email`() {
        mockMvc.post("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"","role":"USER"}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `POST users returns 401 without token`() {
        mockMvc.post("/api/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"user@test.com","role":"USER"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `POST users returns 403 for non-admin`() {
        mockMvc.post("/api/users") {
            with(jwt().jwt { it.subject(userEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"other@test.com","role":"USER"}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `POST users returns 409 on duplicate email`() {
        whenever(appUserService.create(any()))
            .thenThrow(AllowlistEmailConflictException(userEmail))

        mockMvc.post("/api/users") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"user@test.com","role":"USER"}"""
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozadzban.pl/problems/allowlist-email-conflict") }
        }
    }

    // ─── DELETE /api/users/{id} ───────────────────────────────────────────────

    @Test
    fun `DELETE user returns 204 for admin`() {
        mockMvc.delete("/api/users/1") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `DELETE user returns 401 without token`() {
        mockMvc.delete("/api/users/1")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `DELETE user returns 403 for non-admin`() {
        mockMvc.delete("/api/users/1") {
            with(jwt().jwt { it.subject(userEmail) })
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `DELETE user returns 404 when not found`() {
        whenever(appUserService.delete(eq(999L)))
            .thenThrow(NoSuchElementException("User 999 not found"))

        mockMvc.delete("/api/users/999") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `DELETE user returns 409 when last admin`() {
        whenever(appUserService.delete(eq(2L)))
            .thenThrow(LastAdminRequiredException())

        mockMvc.delete("/api/users/2") {
            with(jwt().jwt { it.subject(adminEmail) })
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("https://cozadzban.pl/problems/last-admin-required") }
        }
    }

    // ─── PATCH /api/users/{id} ────────────────────────────────────────────────

    @Test
    fun `PATCH user role returns 200 for admin`() {
        val promoted = sampleUser.copy(role = Role.ADMIN)
        whenever(appUserService.updateRole(eq(1L), any())).thenReturn(promoted)

        mockMvc.patch("/api/users/1") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"ADMIN"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("ADMIN") }
        }
    }

    @Test
    fun `PATCH user role returns 400 on missing role`() {
        mockMvc.patch("/api/users/1") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PATCH user role returns 401 without token`() {
        mockMvc.patch("/api/users/1") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"ADMIN"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `PATCH user role returns 404 when not found`() {
        whenever(appUserService.updateRole(eq(999L), any()))
            .thenThrow(NoSuchElementException("User 999 not found"))

        mockMvc.patch("/api/users/999") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"USER"}"""
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `PATCH user role returns 409 when would remove last admin`() {
        whenever(appUserService.updateRole(eq(2L), any()))
            .thenThrow(LastAdminRequiredException())

        mockMvc.patch("/api/users/2") {
            with(jwt().jwt { it.subject(adminEmail) })
            contentType = MediaType.APPLICATION_JSON
            content = """{"role":"USER"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("https://cozadzban.pl/problems/last-admin-required") }
        }
    }
}
