// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role

class UiRoleAuthoritiesMapperTest {

    private val appUserRepository: AppUserRepository = mock()
    private val mapper = UiRoleAuthoritiesMapper(appUserRepository)

    @Test
    fun `maps ROLE_ADMIN for allowlisted admin email`() {
        whenever(appUserRepository.findByEmail("admin@example.com"))
            .thenReturn(AppUser(id = 1L, email = "admin@example.com", role = Role.ADMIN))

        val authorities = mutableListOf(
            OAuth2UserAuthority(mapOf("email" to "admin@example.com")),
        )

        val mapped = mapper.mapAuthorities(authorities)

        assertTrue(mapped.any { it.authority == "ROLE_ADMIN" })
    }

    @Test
    fun `maps ROLE_USER for allowlisted user email`() {
        whenever(appUserRepository.findByEmail("user@example.com"))
            .thenReturn(AppUser(id = 2L, email = "user@example.com", role = Role.USER))

        val authorities = mutableListOf(
            OAuth2UserAuthority(mapOf("email" to "user@example.com")),
        )

        val mapped = mapper.mapAuthorities(authorities)

        assertTrue(mapped.any { it.authority == "ROLE_USER" })
    }

    @Test
    fun `keeps existing authorities when email is not allowlisted`() {
        whenever(appUserRepository.findByEmail("unknown@example.com")).thenReturn(null)

        val authorities = mutableListOf(
            OAuth2UserAuthority(mapOf("email" to "unknown@example.com")),
            SimpleGrantedAuthority("SCOPE_openid"),
        )

        val mapped = mapper.mapAuthorities(authorities)

        assertTrue(mapped.any { it.authority == "SCOPE_openid" })
        assertTrue(mapped.none { it.authority == "ROLE_ADMIN" || it.authority == "ROLE_USER" })
    }

    @Test
    fun `does not map role for soft deleted allowlisted email`() {
        whenever(appUserRepository.findByEmail("deleted@example.com"))
            .thenReturn(AppUser(id = 3L, email = "deleted@example.com", role = Role.ADMIN, status = AppUserStatus.DELETED))

        val authorities = mutableListOf(
            OAuth2UserAuthority(mapOf("email" to "deleted@example.com")),
            SimpleGrantedAuthority("SCOPE_openid"),
        )

        val mapped = mapper.mapAuthorities(authorities)

        assertTrue(mapped.any { it.authority == "SCOPE_openid" })
        assertTrue(mapped.none { it.authority == "ROLE_ADMIN" || it.authority == "ROLE_USER" })
    }
}
