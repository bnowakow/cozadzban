// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

class MachineToMachineAuthenticationToken(
    private val principalEmail: String,
) : AbstractAuthenticationToken(listOf(SimpleGrantedAuthority("ROLE_MACHINE"))) {

    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = "N/A"

    override fun getPrincipal(): Any = principalEmail

    override fun getName(): String = principalEmail
}
