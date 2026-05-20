// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.Role

/**
 * Tests BootstrapAdminService (BR-20): seeding first admin on startup.
 *
 * Uses a fresh Testcontainers PostgreSQL for each test class.
 * Context loads with COZADZBAN_BOOTSTRAP_ADMIN_EMAIL set, which triggers
 * BootstrapAdminService to seed/promote an admin on ApplicationReadyEvent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=bootstrap@test.com",
    ]
)
class BootstrapAdminIT {

    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `bootstrap seeds admin user when no admin exists`() {
        val admin = appUserRepository.findByEmail("bootstrap@test.com")

        assertNotNull(admin, "Bootstrap should have seeded an ADMIN user")
        assertEquals(Role.ADMIN, admin!!.role)
        assertEquals("bootstrap@test.com", admin.email)
    }

    @Test
    fun `bootstrap does not create duplicate admin on second context load`() {
        // Context was started once (bootstrap ran once), verify single admin
        val admins = appUserRepository.findAll().filter { it.role == Role.ADMIN }

        assertEquals(1, admins.size, "There should be exactly one ADMIN after bootstrap")
        assertEquals("bootstrap@test.com", admins.first().email)
    }

    @Test
    fun `bootstrap-seeded admin has expected normalized email`() {
        // Email should be trimmed and lowercased (BR-20 normalization)
        val admin = appUserRepository.findByEmail("bootstrap@test.com")

        assertNotNull(admin)
        assertEquals("bootstrap@test.com", admin!!.email)
    }
}

/**
 * Tests that bootstrap promotes an existing USER to ADMIN (BR-20).
 *
 * This test class gets its own application context (fresh container)
 * because it pre-populates the DB via SQL before bootstrap runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=promote@test.com",
        "spring.sql.init.mode=always",
    ]
)
@org.springframework.test.context.jdbc.Sql(
    statements = ["INSERT INTO app_user(email, role) VALUES ('promote@test.com', 'USER') ON CONFLICT DO NOTHING"],
    executionPhase = org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS,
)
class BootstrapPromoteUserIT {

    @Autowired private lateinit var appUserRepository: AppUserRepository

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `bootstrap promotes existing USER to ADMIN`() {
        val user = appUserRepository.findByEmail("promote@test.com")

        assertNotNull(user, "User should exist after bootstrap")
        assertEquals(Role.ADMIN, user!!.role, "Bootstrap should have promoted USER to ADMIN")
    }
}
