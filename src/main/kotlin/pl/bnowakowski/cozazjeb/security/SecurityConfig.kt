
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.Customizer
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.AuthenticationEntryPoint
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val allowlist: AllowlistAuthorizationManager,
    private val uiRoleAuthoritiesMapper: UiRoleAuthoritiesMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { csrf ->
                // API and RSS are stateless — no CSRF needed (BR-16)
                csrf.ignoringRequestMatchers("/api/**", "/rss")
                // Vaadin UI routes retain CSRF protection
            }
            .with(VaadinSecurityConfigurer.vaadin()) { vaadin ->
                vaadin
                    // Keep Vaadin internal CSRF handling enabled for UIDL requests.
                    // API/RSS CSRF exceptions are configured in csrf { ... } above.
                    .enableCsrfConfiguration(true)
                    .enableAuthorizedRequestsConfiguration(true)
                    .enableNavigationAccessControl(true)
            }
            .authorizeHttpRequests { auth ->
                // OAuth2 login and callback endpoints must stay public.
                auth.requestMatchers("/auth/login", "/oauth2/**", "/login/oauth2/**").permitAll()
                // Browser-discovered site assets must stay public (e.g. after OAuth redirects).
                auth.requestMatchers("/favicon.ico", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/manifest.webmanifest", "/site.webmanifest", "/robots.txt").permitAll()

                // Session-backed UI identity endpoints (Phase 11)
                auth.requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                auth.requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()

                // /actuator/health is always public (load balancer / k8s probes)
                auth.requestMatchers("/actuator/health").permitAll()
                // Selected actuator endpoints require authenticated, allowlisted user.
                auth.requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**", "/actuator/env", "/actuator/env/**").access { authentication, _ ->
                    AuthorizationDecision(allowlist.checkSessionOrBearer(authentication.get()))
                }
                // Public REST read endpoints (BR-10)
                auth.requestMatchers(HttpMethod.GET, "/api/articles", "/api/articles/**", "/rss").permitAll()

                // Protected article write endpoints: token must be valid and email allowlisted (BR-11, BR-13)
                auth.requestMatchers(HttpMethod.POST, "/api/articles").access(allowlist)
                auth.requestMatchers(HttpMethod.PUT, "/api/articles/**").access(allowlist)
                auth.requestMatchers(HttpMethod.PATCH, "/api/articles/**").access(allowlist)
                auth.requestMatchers(HttpMethod.DELETE, "/api/articles/**").access(allowlist)

                // Allowlist management endpoints are ADMIN-only (BR-17, BR-21)
                auth.requestMatchers("/api/users/**").access { authentication, _ ->
                    AuthorizationDecision(allowlist.checkAdmin(authentication.get()))
                }

                // Deny-by-default for remaining API/RSS paths not matched above.
                auth.requestMatchers("/api/**").denyAll()
                auth.requestMatchers("/rss").denyAll()
                // Vaadin request matchers are contributed by the configurer above.
            }
            .exceptionHandling { exceptions ->
                // Scope JSON error responses to /api/** only.
                // Vaadin paths (heartbeat, UIDL) fall through to Vaadin's own session-expiry
                // handler so the client receives the correct response and can recover.
                val apiMatcher = org.springframework.security.web.util.matcher.RequestMatcher {
                    it.requestURI.startsWith("/api/")
                }
                exceptions.defaultAuthenticationEntryPointFor(
                    AuthenticationEntryPoint { _, response, ex ->
                        writeProblem(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                            ex.message ?: "Authentication is required")
                    },
                    apiMatcher,
                )
                exceptions.defaultAccessDeniedHandlerFor(
                    AccessDeniedHandler { _, response, ex ->
                        writeProblem(response, HttpStatus.FORBIDDEN, "Forbidden",
                            ex.message ?: "Access is denied")
                    },
                    apiMatcher,
                )
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(GoogleJwtAuthenticationConverter()) }
            }
            .oauth2Login { oauth2 ->
                oauth2.userInfoEndpoint { userInfo ->
                    userInfo.userAuthoritiesMapper(uiRoleAuthoritiesMapper)
                }
            }
        return http.build()
    }

    private fun writeProblem(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        val pd = ProblemDetail.forStatusAndDetail(status, detail)
        pd.title = title

        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(
            """{"type":"${pd.type}","title":"${jsonEscape(pd.title ?: title)}","status":${status.value()},"detail":"${jsonEscape(pd.detail ?: detail)}"}"""
        )
    }

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = listOf("https://cozazjeb.pl")
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type")
            allowCredentials = false
            maxAge = 600L
        }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", config)
            registerCorsConfiguration("/rss", config)
        }
    }
}
