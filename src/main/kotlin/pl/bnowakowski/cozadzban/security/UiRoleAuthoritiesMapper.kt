// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus

@Component
class UiRoleAuthoritiesMapper(
    private val appUserRepository: AppUserRepository,
) : GrantedAuthoritiesMapper {

    override fun mapAuthorities(authorities: MutableCollection<out GrantedAuthority>): MutableCollection<out GrantedAuthority> {
        val mapped = mutableSetOf<GrantedAuthority>()
        mapped.addAll(authorities)

        val email = extractEmail(authorities)
        val role = email
            ?.let(AllowlistAuthorizationManager::normalizeEmail)
            ?.let(appUserRepository::findByEmail)
            ?.takeIf { it.status == AppUserStatus.ACTIVE }
            ?.role

        if (role != null) {
            mapped.add(SimpleGrantedAuthority("ROLE_${role.name}"))
        }

        return mapped.toMutableList()
    }

    private fun extractEmail(authorities: Collection<out GrantedAuthority>?): String? {
        if (authorities.isNullOrEmpty()) return null

        for (authority in authorities) {
            when (authority) {
                is OidcUserAuthority -> {
                    val fromUserInfo = authority.userInfo?.email
                    val fromClaims = authority.idToken?.getClaimAsString("email")
                    val candidate = fromUserInfo ?: fromClaims
                    if (!candidate.isNullOrBlank()) return candidate
                }
                is OAuth2UserAuthority -> {
                    val candidate = authority.attributes["email"] as? String
                    if (!candidate.isNullOrBlank()) return candidate
                }
            }
        }

        return null
    }
}
