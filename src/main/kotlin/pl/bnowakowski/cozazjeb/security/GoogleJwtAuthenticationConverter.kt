// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Converts a Google OIDC JWT into a Spring Security authentication token.
 *
 * Validates:
 * - `email` claim is present (BR-12)
 * - `email_verified` claim is true (BR-12)
 *
 * Sets the principal name to the `email` claim so that
 * [AllowlistAuthorizationManager] can look up the user by email.
 * Grants a single synthetic authority `ROLE_BEARER` — actual role
 * checks are DB-backed via [AllowlistAuthorizationManager].
 */
class GoogleJwtAuthenticationConverter : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val email = jwt.getClaimAsString("email")
            ?: throw OAuth2AuthenticationException(
                OAuth2Error("invalid_token", "email claim is required", null)
            )

        val emailVerified = jwt.getClaim<Any>("email_verified")
        if (emailVerified != true) {
            throw OAuth2AuthenticationException(
                OAuth2Error("invalid_token", "email_verified must be true", null)
            )
        }

        return JwtAuthenticationToken(
            jwt,
            listOf(SimpleGrantedAuthority("ROLE_BEARER")),
            email,
        )
    }
}
