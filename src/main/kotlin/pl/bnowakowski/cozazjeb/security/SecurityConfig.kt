
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
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
import org.springframework.security.core.Authentication
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val allowlist: AllowlistAuthorizationManager,
    private val uiRoleAuthoritiesMapper: UiRoleAuthoritiesMapper,
    private val machineToMachineAuthenticationFilter: MachineToMachineAuthenticationFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { csrf ->
                // API and RSS are stateless — no CSRF needed (BR-16)
                csrf.ignoringRequestMatchers("/api/**", "/rss", "/rss/**")
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
                auth.requestMatchers("/favicon.ico", "/favicons/**", "/cozazjeb-logo.png", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png", "/manifest.webmanifest", "/site.webmanifest", "/robots.txt").permitAll()

                // Session-backed UI identity endpoints (Phase 11)
                auth.requestMatchers(HttpMethod.GET, "/auth/me").authenticated()
                auth.requestMatchers(HttpMethod.POST, "/auth/logout").authenticated()

                // /actuator/health is always public (load balancer / k8s probes)
                auth.requestMatchers("/actuator/health").permitAll()
                // Public machine-readable API documentation.
                auth.requestMatchers(
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                ).permitAll()
                // Selected actuator endpoints require authenticated, allowlisted user.
                auth.requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**", "/actuator/env", "/actuator/env/**").access { authentication, _ ->
                    AuthorizationDecision(allowlist.checkSessionOrBearer(authentication.get()))
                }
                // Public REST read endpoints (BR-10)
                auth.requestMatchers(HttpMethod.GET, "/api/articles", "/api/articles/**", "/rss", "/rss/**").permitAll()

                // Protected article write endpoints: token must be valid and email allowlisted (BR-11, BR-13)
                // POST and PATCH also accept the dedicated machine credential used by the Facebook importer.
                auth.requestMatchers(HttpMethod.POST, "/api/articles").access { authentication, context ->
                    articleWriteAuthorizationDecision(authentication.get(), context.request, "POST /api/articles")
                }
                auth.requestMatchers(HttpMethod.PUT, "/api/articles/**").access(allowlist)
                auth.requestMatchers(HttpMethod.PATCH, "/api/articles/**").access { authentication, context ->
                    articleWriteAuthorizationDecision(authentication.get(), context.request, "PATCH /api/articles/**")
                }
                auth.requestMatchers(HttpMethod.DELETE, "/api/articles/**").access(allowlist)

                // Facebook import endpoints are ADMIN-only.
                auth.requestMatchers("/api/admin/facebook-import/**").access { authentication, _ ->
                    AuthorizationDecision(allowlist.checkAdmin(authentication.get()))
                }

                // Allowlist management endpoints are ADMIN-only (BR-17, BR-21)
                auth.requestMatchers("/api/users/**").access { authentication, _ ->
                    AuthorizationDecision(allowlist.checkAdmin(authentication.get()))
                }

                // Deny-by-default for remaining API/RSS paths not matched above.
                auth.requestMatchers("/api/**").denyAll()
                auth.requestMatchers("/rss", "/rss/**").denyAll()
                // Vaadin request matchers are contributed by the configurer above.
            }
            .exceptionHandling { exceptions ->
                // Scope JSON error responses to /api/** only.
                // Vaadin paths (heartbeat, UIDL) fall through to Vaadin's own session-expiry
                // handler so the client receives the correct response and can recover.
                val apiMatcher = org.springframework.security.web.util.matcher.RequestMatcher { req ->
                    req.requestURI.startsWith("/api/")
                }
                exceptions.defaultAuthenticationEntryPointFor(
                    org.springframework.security.web.AuthenticationEntryPoint { request, response, ex ->
                        logArticleWriteSecurityProblem(request, HttpStatus.UNAUTHORIZED, ex.localizedMessage)
                        writeProblem(response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                            ex.localizedMessage ?: "Authentication is required")
                    },
                    apiMatcher,
                )
                exceptions.defaultAccessDeniedHandlerFor(
                    org.springframework.security.web.access.AccessDeniedHandler { request, response, ex ->
                        logArticleWriteSecurityProblem(request, HttpStatus.FORBIDDEN, ex.localizedMessage)
                        writeProblem(response, HttpStatus.FORBIDDEN, "Forbidden",
                            ex.localizedMessage ?: "Access is denied")
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
        http.addFilterBefore(machineToMachineAuthenticationFilter, BearerTokenAuthenticationFilter::class.java)
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

    private fun articleWriteAuthorizationDecision(
        authentication: Authentication,
        request: HttpServletRequest,
        route: String,
    ): AuthorizationDecision {
        val userAllowed = allowlist.check(authentication)
        val machineAllowed = allowlist.checkMachine(authentication)
        val allowed = userAllowed || machineAllowed

        LOG.warn(
            "Article write authorization decision; route={}; method={}; uri={}; allowed={}; " +
                "userAllowed={}; machineAllowed={}; auth={}",
            route,
            request.method,
            request.requestURI,
            allowed,
            userAllowed,
            machineAllowed,
            authenticationDiagnostic(authentication),
        )

        return AuthorizationDecision(allowed)
    }

    private fun authenticationDiagnostic(authentication: Authentication): String =
        "name='${authentication.name}',authenticated=${authentication.isAuthenticated},type=${authentication.javaClass.simpleName}," +
            "authorities=${authentication.authorities.joinToString(",") { it.authority ?: "unknown" }}"

    private fun logArticleWriteSecurityProblem(request: HttpServletRequest, status: HttpStatus, detail: String?) {
        if (!isArticleWrite(request)) return

        LOG.warn(
            "Article write security problem; method={}; uri={}; status={}; detail='{}'",
            request.method,
            request.requestURI,
            status.value(),
            detail,
        )
    }

    private fun isArticleWrite(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PATCH") &&
            (request.requestURI == "/api/articles" || request.requestURI.startsWith("/api/articles/"))

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

    companion object {
        private val LOG = LoggerFactory.getLogger(SecurityConfig::class.java)
    }
}
