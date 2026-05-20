// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.bnowakowski.cozadzban.user.Role

@RestController
@RequestMapping("/auth")
class UiAuthController(
    private val uiPrincipalMapper: UiPrincipalMapper,
) {

    data class AuthMeResponse(
        val email: String,
        val role: Role?,
        val allowlisted: Boolean,
    )

    @GetMapping("/login")
    fun loginRedirect(response: HttpServletResponse) {
        response.sendRedirect("/oauth2/authorization/google")
    }

    @GetMapping("/me")
    fun me(authentication: Authentication?): ResponseEntity<AuthMeResponse> {
        val principal = uiPrincipalMapper.resolve(authentication)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return ResponseEntity.ok(
            AuthMeResponse(
                email = principal.email,
                role = principal.role,
                allowlisted = principal.allowlisted,
            ),
        )
    }

    @PostMapping("/logout")
    fun logout(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()

        val cookie = Cookie("JSESSIONID", "").apply {
            path = "/"
            maxAge = 0
            isHttpOnly = true
            secure = request.isSecure
        }
        response.addCookie(cookie)

        return ResponseEntity.noContent().build()
    }
}
