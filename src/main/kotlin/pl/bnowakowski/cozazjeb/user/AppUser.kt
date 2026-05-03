// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

enum class Role { USER, ADMIN }

@Table("app_user")
data class AppUser(
    @Id val id: Long? = null,
    val email: String,
    val role: Role,
    @ReadOnlyProperty val createdAt: Instant? = null,
)
