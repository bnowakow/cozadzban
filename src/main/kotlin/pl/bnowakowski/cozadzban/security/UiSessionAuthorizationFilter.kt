// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role

/** Revoke stale UI privileges before any Vaadin navigation or UIDL event is dispatched. */
class UiSessionAuthorizationFilter(
    private val users: AppUserRepository,
) : OncePerRequestFilter() {
    private val logout = SecurityContextLogoutHandler()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestAuthentication = SecurityContextHolder.getContext().authentication
        // A bearer header can replace the current authentication while an old Vaadin UI
        // remains attached to the HTTP session. Validate that session before UI dispatch too.
        val authentication = if (requestAuthentication is OAuth2AuthenticationToken) {
            requestAuthentication
        } else if (!request.requestURI.removePrefix(request.contextPath).startsWith("/api/")) {
            (request.getSession(false)?.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                as? SecurityContext)?.authentication as? OAuth2AuthenticationToken
        } else {
            null
        }
        if (authentication is OAuth2AuthenticationToken && authentication.isAuthenticated) {
            val sessionRoles = authentication.authorities.map { it.authority }.toSet()
                .intersect(Role.entries.map { "ROLE_${it.name}" }.toSet())
            if (sessionRoles.isNotEmpty()) {
                val email = AllowlistAuthorizationManager.normalizeEmail(authentication.principal?.getAttribute("email"))
                val user = email?.let(users::findByEmail)
                val currentRole = user?.takeIf { it.status == AppUserStatus.ACTIVE }?.role
                    ?.let { "ROLE_${it.name}" }
                if (currentRole == null || sessionRoles != setOf(currentRole)) {
                    logout.logout(request, response, authentication)
                    response.status = HttpServletResponse.SC_UNAUTHORIZED
                    response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                    response.writer.write(
                        """{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Your permissions changed. Please sign in again."}""",
                    )
                    return
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
