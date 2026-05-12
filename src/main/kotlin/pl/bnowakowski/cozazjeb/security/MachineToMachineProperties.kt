// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.machine-auth")
data class MachineToMachineProperties(
    val enabled: Boolean = false,
    val headerName: String = "X-CoZaZjeb-M2M-Key",
    val apiKey: String = "",
    val principalEmail: String = "",
)
