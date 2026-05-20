// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

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

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled || !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val apiKey = request.getHeader(properties.headerName)
        if (apiKey == null) {
            logArticleWriteDecision(request, headerPresent = false, serverConfigured = serverConfigured(), headerMatched = false)
            log.debug("M2M auth header {} not present for {} {}", properties.headerName, request.method, request.requestURI)
            filterChain.doFilter(request, response)
            return
        }

        if (properties.apiKey.isBlank() || properties.principalEmail.isBlank()) {
            logArticleWriteDecision(request, headerPresent = true, serverConfigured = false, headerMatched = false)
            log.debug("M2M auth configured to accept header {} on {} {}, but server api key or principal email is blank", properties.headerName, request.method, request.requestURI)
            filterChain.doFilter(request, response)
            return
        }

        if (apiKey == properties.apiKey) {
            logArticleWriteDecision(request, headerPresent = true, serverConfigured = true, headerMatched = true)
            log.debug("M2M auth accepted header {} on {} {} for {}", properties.headerName, request.method, request.requestURI, properties.principalEmail)
            val auth = MachineToMachineAuthenticationToken(properties.principalEmail)
            SecurityContextHolder.getContext().authentication = auth
        } else {
            logArticleWriteDecision(request, headerPresent = true, serverConfigured = true, headerMatched = false)
            log.debug("M2M auth header {} present but value did not match on {} {}", properties.headerName, request.method, request.requestURI)
        }

        filterChain.doFilter(request, response)
    }

    private fun logArticleWriteDecision(
        request: HttpServletRequest,
        headerPresent: Boolean,
        serverConfigured: Boolean,
        headerMatched: Boolean,
    ) {
        if (!isArticleWrite(request)) return

        log.warn(
            "M2M auth article write decision; method={}; uri={}; headerName='{}'; headerPresent={}; " +
                "serverConfigured={}; headerMatched={}; principalEmailConfigured={}",
            request.method,
            request.requestURI,
            properties.headerName,
            headerPresent,
            serverConfigured,
            headerMatched,
            properties.principalEmail.isNotBlank(),
        )
    }

    private fun serverConfigured(): Boolean =
        properties.apiKey.isNotBlank() && properties.principalEmail.isNotBlank()

    private fun isArticleWrite(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PATCH") &&
            (request.requestURI == "/api/articles" || request.requestURI.startsWith("/api/articles/"))
}
