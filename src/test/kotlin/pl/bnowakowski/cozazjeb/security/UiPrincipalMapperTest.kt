// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role
import java.time.Instant

/**
 * Phase 14 step 33 — UiPrincipalMapper unit tests.
 *
 * Verifies:
 * - Email is normalized (trimmed + lowercased) before allowlist lookup
 * - Allowlisted users get correct role and allowlisted=true
 * - Non-allowlisted users get allowlisted=false with null role
 * - Unauthenticated/null authentication returns null
 */
class UiPrincipalMapperTest {

    private val appUserRepository: AppUserRepository = mock()
    private val mapper = UiPrincipalMapper(appUserRepository)

    private fun jwtAuth(email: String): JwtAuthenticationToken {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(email)
            .claim("email", email)
            .claim("email_verified", true)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
        return JwtAuthenticationToken(jwt, emptyList(), email)
    }

    // ─── Email normalization ───────────────────────────────────────────────────

    @Test
    fun `normalizes email to lowercase`() {
        whenever(appUserRepository.findByEmail("user@test.com"))
            .thenReturn(AppUser(1L, "user@test.com", Role.USER))

        val principal = mapper.resolve(jwtAuth("USER@TEST.COM"))

        assertNotNull(principal)
        assertEquals("user@test.com", principal!!.email)
    }

    @Test
    fun `trims whitespace from email before lookup`() {
        whenever(appUserRepository.findByEmail("user@test.com"))
            .thenReturn(AppUser(1L, "user@test.com", Role.USER))

        val principal = mapper.resolve(jwtAuth("  user@test.com  "))

        assertNotNull(principal)
        assertEquals("user@test.com", principal!!.email)
    }

    @Test
    fun `normalizes mixed-case email with whitespace`() {
        whenever(appUserRepository.findByEmail("admin@example.com"))
            .thenReturn(AppUser(2L, "admin@example.com", Role.ADMIN))

        val principal = mapper.resolve(jwtAuth("  ADMIN@Example.COM  "))

        assertNotNull(principal)
        assertEquals("admin@example.com", principal!!.email)
        assertEquals(Role.ADMIN, principal.role)
        assertTrue(principal.allowlisted)
    }

    // ─── Allowlist resolution ──────────────────────────────────────────────────

    @Test
    fun `allowlisted USER email resolves correct role and allowlisted=true`() {
        whenever(appUserRepository.findByEmail("user@test.com"))
            .thenReturn(AppUser(1L, "user@test.com", Role.USER))

        val principal = mapper.resolve(jwtAuth("user@test.com"))

        assertNotNull(principal)
        assertEquals(Role.USER, principal!!.role)
        assertTrue(principal.allowlisted)
    }

    @Test
    fun `allowlisted ADMIN email resolves correct role and allowlisted=true`() {
        whenever(appUserRepository.findByEmail("admin@test.com"))
            .thenReturn(AppUser(2L, "admin@test.com", Role.ADMIN))

        val principal = mapper.resolve(jwtAuth("admin@test.com"))

        assertNotNull(principal)
        assertEquals(Role.ADMIN, principal!!.role)
        assertTrue(principal.allowlisted)
    }

    @Test
    fun `non-allowlisted email returns allowlisted=false with null role`() {
        whenever(appUserRepository.findByEmail("stranger@test.com")).thenReturn(null)

        val principal = mapper.resolve(jwtAuth("stranger@test.com"))

        assertNotNull(principal)
        assertNull(principal!!.role)
        assertFalse(principal.allowlisted)
    }

    // ─── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `null authentication returns null`() {
        assertNull(mapper.resolve(null))
    }
}
