// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AppUserInput(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,

    val role: Role,
)

