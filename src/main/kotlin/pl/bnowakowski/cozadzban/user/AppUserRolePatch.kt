// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.user

data class AppUserRolePatch(
    val role: Role? = null,
    val status: AppUserStatus? = null,
)
