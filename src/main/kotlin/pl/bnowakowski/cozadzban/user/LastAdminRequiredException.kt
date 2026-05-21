// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.user

class LastAdminRequiredException : RuntimeException(
    "Operation would leave the system without an ADMIN user"
)
