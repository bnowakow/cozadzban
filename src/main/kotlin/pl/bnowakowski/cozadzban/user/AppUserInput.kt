// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AppUserInput(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,

    val role: Role,

    val status: AppUserStatus = AppUserStatus.ACTIVE,
)
