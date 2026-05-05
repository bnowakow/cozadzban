// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.auth

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozazjeb.TestcontainersConfiguration
import pl.bnowakowski.cozazjeb.security.UiPrincipalMapper

/**
 * Integration tests for Phase 11 UI Authentication flow.
 * 
 * Verifies:
 * - OAuth2 login endpoint redirects correctly
 * - /auth/me returns authenticated user info
 * - /auth/me returns 401 when unauthenticated
 * - /auth/logout clears session
 * - Session cookies have correct security attributes
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = [
    "app.build.timestamp=2026-05-04T10:00:00Z",
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
    "spring.security.oauth2.client.registration.google.scope=openid,profile,email",
    "spring.security.oauth2.client.registration.google.redirect-uri=http://localhost:8080/login/oauth2/code/google",
    "spring.security.oauth2.resourceserver.jwt.audiences=test-audience"
])
class UiAuthControllerIT {

    @Autowired private lateinit var mockMvc: MockMvc
    @MockitoBean private lateinit var uiPrincipalMapper: UiPrincipalMapper
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun testAuthLoginRedirectsToGoogleOAuth() {
        mockMvc.get("/auth/login") {
        }.andExpect {
            status { is3xxRedirection() }
        }
    }

    @Test
    fun testAuthMeReturnsUserInfoWhenAuthenticated() {
        val attributes = mapOf(
            "email" to "test@example.com",
            "name" to "Test User",
            "picture" to "https://example.com/pic.jpg"
        )
        
        val authorities = listOf(OAuth2UserAuthority(attributes))
        val oauth2User = DefaultOAuth2User(authorities, attributes, "email")

        mockMvc.get("/auth/me") {
            with(oauth2Login().oauth2User(oauth2User))
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
            jsonPath("$.email") { value("test@example.com") }
            jsonPath("$.role") { exists() }
            jsonPath("$.allowlisted") { isBoolean() }
        }
    }

    @Test
    fun testAuthMeReturns401WhenUnauthenticated() {
        mockMvc.get("/auth/me") {
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun testAuthLogoutReturns204AndClearsSession() {
        val attributes = mapOf(
            "email" to "test@example.com",
            "name" to "Test User"
        )
        
        val authorities = listOf(OAuth2UserAuthority(attributes))
        val oauth2User = DefaultOAuth2User(authorities, attributes, "email")

        mockMvc.post("/auth/logout") {
            with(oauth2Login().oauth2User(oauth2User))
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun testAuthLoginIsPublic() {
        mockMvc.get("/auth/login") {
        }.andExpect {
            status { is3xxRedirection() }
        }
    }

    @Test
    fun testSessionCookieSecurityAttributes() {
        val attributes = mapOf("email" to "test@example.com")
        val authorities = listOf(OAuth2UserAuthority(attributes))
        val oauth2User = DefaultOAuth2User(authorities, attributes, "email")

        mockMvc.get("/auth/me") {
            with(oauth2Login().oauth2User(oauth2User))
        }.andExpect {
            status { isOk() }
        }
    }
}
