// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationResult
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import java.util.function.Supplier

/**
 * Authorization manager that checks:
 * 1. The request has a valid JWT authentication (produced by [GoogleJwtAuthenticationConverter]).
 * 2. The normalized email (trim + lowercase) exists in the [app_user] table (BR-13).
 * 3. Optionally, the user has the required [requiredRole].
 *
 * Usage via method security:
 *   @PreAuthorize("@allowlist.check(authentication)")        — any allowlisted user (USER or ADMIN)
 *   @PreAuthorize("@allowlist.checkAdmin(authentication)")   — ADMIN only
 */
@Component("allowlist")
class AllowlistAuthorizationManager(
    private val appUserRepository: AppUserRepository,
) : AuthorizationManager<RequestAuthorizationContext> {

    /**
     * Checks that the authenticated principal exists in the allowlist with any role.
     * Used by [AuthorizationManager] SPI (e.g. for HTTP security rules).
     */
    override fun authorize(
        authentication: Supplier<out Authentication>,
        context: RequestAuthorizationContext,
    ): AuthorizationResult = AuthorizationDecision(isAllowlisted(authentication.get(), null))

    /**
     * Returns true if [auth] has a verified, allowlisted email.
     * If [requiredRole] is non-null, the user's role must match.
     */
    fun check(auth: Authentication, requiredRole: Role? = null): Boolean =
        isAllowlisted(auth, requiredRole)

    /** Convenience SpEL target: checks USER or ADMIN role. */
    fun check(auth: Authentication): Boolean = isAllowlisted(auth, null)

    /** Convenience SpEL target: checks ADMIN role only. */
    fun checkAdmin(auth: Authentication): Boolean = isAllowlisted(auth, Role.ADMIN)

    /**
     * Checks allowlist membership for article writes performed through the machine-to-machine
     * importer. This accepts either the usual JWT flow or the dedicated machine credential.
     */
    fun checkMachine(auth: Authentication, requiredRole: Role? = null): Boolean =
        isAllowlistedMachine(auth, requiredRole)

    /**
     * Checks allowlist membership for either bearer-token auth or OAuth2 login session auth.
     * This is used for UI/session-protected routes like selected actuator endpoints.
     */
    fun checkSessionOrBearer(auth: Authentication): Boolean =
        isAllowlistedSessionOrBearer(auth, null)

    // ─────────────────────────────────────────────────────────

    private fun isAllowlisted(auth: Authentication?, requiredRole: Role?): Boolean {
        if (auth == null || !auth.isAuthenticated) return false
        if (auth !is JwtAuthenticationToken) return false

        val email = normalizeEmail(auth.name) ?: return false
        val user = appUserRepository.findByEmail(email) ?: return false
        if (user.status != AppUserStatus.ACTIVE) return false

        return requiredRole == null || user.role == requiredRole
    }

    private fun isAllowlistedMachine(auth: Authentication?, requiredRole: Role?): Boolean {
        if (auth == null || !auth.isAuthenticated) return false
        if (auth !is JwtAuthenticationToken && auth !is MachineToMachineAuthenticationToken) return false

        val email = normalizeEmail(auth.name) ?: return false
        val user = appUserRepository.findByEmail(email) ?: return false
        if (user.status != AppUserStatus.ACTIVE) return false

        return requiredRole == null || user.role == requiredRole
    }

    private fun isAllowlistedSessionOrBearer(auth: Authentication?, requiredRole: Role?): Boolean {
        if (auth == null || !auth.isAuthenticated) return false

        val email = when {
            auth is JwtAuthenticationToken -> normalizeEmail(auth.name)
            auth is MachineToMachineAuthenticationToken -> normalizeEmail(auth.name)
            auth.principal is OAuth2AuthenticatedPrincipal -> {
                normalizeEmail((auth.principal as OAuth2AuthenticatedPrincipal).attributes["email"] as? String)
            }
            else -> normalizeEmail(auth.name)
        } ?: return false

        val user = appUserRepository.findByEmail(email) ?: return false
        if (user.status != AppUserStatus.ACTIVE) return false
        return requiredRole == null || user.role == requiredRole
    }

    companion object {
        fun normalizeEmail(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.trim().lowercase()
        }
    }
}
