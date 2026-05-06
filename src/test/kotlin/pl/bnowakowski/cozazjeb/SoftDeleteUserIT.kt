// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentResult
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentService
import pl.bnowakowski.cozazjeb.user.AppUserInput
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserRolePatch
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.LastAdminRequiredException
import pl.bnowakowski.cozazjeb.user.Role
import org.junit.jupiter.api.Assertions.assertThrows
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt

/**
 * Phase 21 / Item 65 — Auth/user tests: soft-deleted users and admin invariant.
 *
 * Verifies:
 * - Soft-deleted users cannot create articles (403)
 * - Admins can restore a deleted user
 * - The last active ADMIN cannot be deleted
 * - The last active ADMIN cannot be demoted to USER
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZAZJEB_BOOTSTRAP_ADMIN_EMAIL=admin@softdelete.test",
    ],
)
class SoftDeleteUserIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var appUserService: AppUserService
    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var enrichmentService: EnrichmentService

    private val adminEmail = "admin@softdelete.test"

    // ─── Soft-delete blocks writes ────────────────────────────────────────────

    @Test
    fun `soft-deleted user is forbidden from creating articles`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(title = "T", thumbnail = null, lead = null),
        )

        val user = appUserService.create(AppUserInput("victim@softdelete.test", Role.USER))
        appUserService.delete(user.id!!)

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject("victim@softdelete.test") })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/softdelete-${System.nanoTime()}","language":"en"}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    // ─── User restoration ─────────────────────────────────────────────────────

    @Test
    fun `admin can restore a soft-deleted user`() {
        val user = appUserService.create(AppUserInput("restore@softdelete.test", Role.USER))
        appUserService.delete(user.id!!)

        var fetched = appUserRepository.findById(user.id!!).orElseThrow()
        assertEquals(AppUserStatus.DELETED, fetched.status, "User should be DELETED after soft-delete")

        appUserService.restore(user.id!!)

        fetched = appUserRepository.findById(user.id!!).orElseThrow()
        assertEquals(AppUserStatus.ACTIVE, fetched.status, "User should be ACTIVE after restore")
    }

    @Test
    fun `restored user can create articles again`() {
        whenever(enrichmentService.enrich(any())).thenReturn(
            EnrichmentResult(title = "T", thumbnail = null, lead = null),
        )

        val user = appUserService.create(AppUserInput("comeback@softdelete.test", Role.USER))
        appUserService.delete(user.id!!)
        appUserService.restore(user.id!!)

        mockMvc.post("/api/articles") {
            with(jwt().jwt { it.subject("comeback@softdelete.test") })
            contentType = MediaType.APPLICATION_JSON
            content = """{"url":"https://example.com/comeback-${System.nanoTime()}","language":"en"}"""
        }.andExpect {
            status { isCreated() }
        }
    }

    // ─── Last-admin invariant ─────────────────────────────────────────────────

    @Test
    fun `last active admin cannot be deleted`() {
        val admin = appUserRepository.findByEmail(adminEmail)!!

        assertThrows(LastAdminRequiredException::class.java) {
            appUserService.delete(admin.id!!)
        }

        // Admin must still be ACTIVE after the rejected delete
        val reloaded = appUserRepository.findById(admin.id!!).orElseThrow()
        assertEquals(AppUserStatus.ACTIVE, reloaded.status)
    }

    @Test
    fun `last active admin cannot be demoted to USER`() {
        val admin = appUserRepository.findByEmail(adminEmail)!!

        assertThrows(LastAdminRequiredException::class.java) {
            appUserService.updateRole(admin.id!!, AppUserRolePatch(Role.USER))
        }

        // Admin role must be unchanged
        val reloaded = appUserRepository.findById(admin.id!!).orElseThrow()
        assertEquals(Role.ADMIN, reloaded.role)
    }

    @Test
    fun `admin can be deleted once there is another active admin`() {
        val secondAdmin = appUserService.create(AppUserInput("second@softdelete.test", Role.ADMIN))

        val mainAdmin = appUserRepository.findByEmail(adminEmail)!!
        appUserService.delete(mainAdmin.id!!)

        val reloaded = appUserRepository.findById(mainAdmin.id!!).orElseThrow()
        assertEquals(AppUserStatus.DELETED, reloaded.status)

        // Restore for other tests in this context
        appUserService.restore(mainAdmin.id!!)
        appUserService.delete(secondAdmin.id!!)
    }
}
