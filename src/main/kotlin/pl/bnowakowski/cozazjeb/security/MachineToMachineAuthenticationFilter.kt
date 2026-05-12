// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

@Component
class MachineToMachineAuthenticationFilter(
    private val properties: MachineToMachineProperties,
) : OncePerRequestFilter() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled || !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKey = request.getHeader(properties.headerName)
        if (apiKey == null) {
            logger.debug("M2M auth header {} not present for {} {}", properties.headerName, request.method, request.requestURI)
            filterChain.doFilter(request, response)
            return
        }

        if (properties.apiKey.isBlank() || properties.principalEmail.isBlank()) {
            logger.debug("M2M auth configured to accept header {} on {} {}, but server api key or principal email is blank", properties.headerName, request.method, request.requestURI)
            filterChain.doFilter(request, response)
            return
        }

        if (apiKey == properties.apiKey) {
            logger.debug("M2M auth accepted header {} on {} {} for {}", properties.headerName, request.method, request.requestURI, properties.principalEmail)
            val auth = MachineToMachineAuthenticationToken(properties.principalEmail)
            SecurityContextHolder.getContext().authentication = auth
        } else {
            logger.debug("M2M auth header {} present but value did not match on {} {}", properties.headerName, request.method, request.requestURI)
        }

        filterChain.doFilter(request, response)
    }
}
