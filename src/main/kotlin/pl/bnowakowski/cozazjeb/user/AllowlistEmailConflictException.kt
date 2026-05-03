// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

class AllowlistEmailConflictException(val email: String) : RuntimeException(
    "User with email '$email' is already allowlisted"
)
