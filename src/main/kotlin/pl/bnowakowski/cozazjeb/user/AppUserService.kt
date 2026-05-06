// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozazjeb.security.AllowlistAuthorizationManager

@Service
@Transactional
class AppUserService(
    private val appUserRepository: AppUserRepository,
) {

    @Transactional(readOnly = true)
    fun list(): List<AppUser> =
        appUserRepository.findAll().sortedBy { it.id ?: Long.MAX_VALUE }

    fun create(input: AppUserInput): AppUser {
        val normalizedEmail = normalizeEmail(input.email)
        if (appUserRepository.findByEmail(normalizedEmail) != null) {
            throw AllowlistEmailConflictException(normalizedEmail)
        }

        return appUserRepository.save(
            AppUser(
                email = normalizedEmail,
                role = input.role,
            )
        )
    }

    fun delete(id: Long): AppUser {
        val existing = appUserRepository.findById(id)
            .orElseThrow { NoSuchElementException("User $id not found") }

        if (existing.status == AppUserStatus.DELETED) return existing

        if (existing.role == Role.ADMIN &&
            appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE) <= 1
        ) {
            throw LastAdminRequiredException()
        }

        return appUserRepository.save(existing.copy(status = AppUserStatus.DELETED))
    }

    fun restore(id: Long): AppUser {
        val existing = appUserRepository.findById(id)
            .orElseThrow { NoSuchElementException("User $id not found") }

        if (existing.status == AppUserStatus.ACTIVE) return existing
        return appUserRepository.save(existing.copy(status = AppUserStatus.ACTIVE))
    }

    fun updateRole(id: Long, patch: AppUserRolePatch): AppUser {
        val existing = appUserRepository.findById(id)
            .orElseThrow { NoSuchElementException("User $id not found") }

        if (existing.status == AppUserStatus.DELETED) {
            throw NoSuchElementException("User $id not found")
        }

        if (existing.role == Role.ADMIN && patch.role != Role.ADMIN &&
            appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE) <= 1
        ) {
            throw LastAdminRequiredException()
        }

        if (existing.role == patch.role) return existing
        return appUserRepository.save(existing.copy(role = patch.role))
    }

    private fun normalizeEmail(email: String): String =
        AllowlistAuthorizationManager.normalizeEmail(email)
            ?: throw IllegalArgumentException("email must not be blank")
}
