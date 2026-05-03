// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val env: Environment,
    private val allowlist: AllowlistAuthorizationManager,
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
                    .enableCsrfConfiguration(false) // we configure CSRF ourselves above
                    .enableAuthorizedRequestsConfiguration(true)
                    .enableNavigationAccessControl(true)
            }
            .authorizeHttpRequests { auth ->
                // /actuator/health is always public (load balancer / k8s probes)
                auth.requestMatchers("/actuator/health").permitAll()
                // /actuator/metrics and /actuator/info are public in local, authenticated in prod
                if (env.activeProfiles.contains("local")) {
                    auth.requestMatchers("/actuator/metrics", "/actuator/info").permitAll()
                } else {
                    auth.requestMatchers("/actuator/metrics", "/actuator/info").authenticated()
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
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt -> jwt.jwtAuthenticationConverter(GoogleJwtAuthenticationConverter()) }
            }
        return http.build()
    }

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
