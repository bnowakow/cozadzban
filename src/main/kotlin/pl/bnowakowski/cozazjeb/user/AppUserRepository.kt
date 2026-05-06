// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

import org.springframework.data.repository.CrudRepository

interface AppUserRepository : CrudRepository<AppUser, Long> {
    fun findByEmail(email: String): AppUser?
    fun countByRole(role: Role): Long
    fun countByRoleAndStatus(role: Role, status: AppUserStatus): Long
}
