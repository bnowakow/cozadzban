// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class MachineToMachineAuthenticationFilterTest {

    private val filterChain: FilterChain = mock()

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `matching api key installs machine authentication`() {
        val filter = MachineToMachineAuthenticationFilter(
            MachineToMachineProperties(
                enabled = true,
                headerName = "X-CoZaZjeb-M2M-Key",
                apiKey = "secret",
                principalEmail = "bot@cozazjeb.pl",
            ),
        )

        val request = MockHttpServletRequest("POST", "/api/articles").apply {
            addHeader("X-CoZaZjeb-M2M-Key", "secret")
        }

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        val auth = SecurityContextHolder.getContext().authentication
        assertTrue(auth is MachineToMachineAuthenticationToken)
        assertTrue(auth?.isAuthenticated == true)
        verify(filterChain).doFilter(eq(request), org.mockito.kotlin.any())
    }
}
