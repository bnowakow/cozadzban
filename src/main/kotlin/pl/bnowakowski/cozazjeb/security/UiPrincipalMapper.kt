// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role

@Component
class UiPrincipalMapper(
    private val appUserRepository: AppUserRepository,
) {

    data class UiPrincipal(
        val email: String,
        val role: Role?,
        val allowlisted: Boolean,
    )

    fun resolve(authentication: Authentication?): UiPrincipal? {
        if (authentication == null || !authentication.isAuthenticated) return null

        val email = extractNormalizedEmail(authentication) ?: return null
        val role = appUserRepository.findByEmail(email)?.role

        return UiPrincipal(
            email = email,
            role = role,
            allowlisted = role != null,
        )
    }

    private fun extractNormalizedEmail(authentication: Authentication): String? {
        val email = when {
            authentication is JwtAuthenticationToken -> authentication.name
            authentication.principal is OAuth2AuthenticatedPrincipal -> {
                (authentication.principal as OAuth2AuthenticatedPrincipal)
                    .attributes["email"] as? String
            }
            else -> authentication.name
        }

        return AllowlistAuthorizationManager.normalizeEmail(email)
    }
}
