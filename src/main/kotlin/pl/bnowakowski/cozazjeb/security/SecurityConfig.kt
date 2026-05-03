// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(private val env: Environment) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                // /actuator/health is always public (load balancer / k8s probes)
                auth.requestMatchers("/actuator/health").permitAll()
                // /actuator/metrics and /actuator/info are public in local, authenticated in prod
                if (env.activeProfiles.contains("local")) {
                    auth.requestMatchers("/actuator/metrics", "/actuator/info").permitAll()
                } else {
                    auth.requestMatchers("/actuator/metrics", "/actuator/info").authenticated()
                }
                // Public REST read endpoints
                auth.requestMatchers(HttpMethod.GET, "/api/articles", "/api/articles/**", "/api/feed").permitAll()
                // Vaadin internal paths handled by the configurer below
            }
            .with(VaadinSecurityConfigurer.vaadin()) { vaadin ->
                vaadin
                    .enableAuthorizedRequestsConfiguration(true)
                    .enableNavigationAccessControl(true)
            }
        return http.build()
    }

    @Bean
    fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val grantedAuthoritiesConverter = JwtGrantedAuthoritiesConverter().apply {
            setAuthoritiesClaimName("roles")
            setAuthorityPrefix("ROLE_")
        }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter)
        }
    }
}
