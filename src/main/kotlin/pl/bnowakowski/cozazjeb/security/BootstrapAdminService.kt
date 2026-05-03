// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role

/**
 * Seeds the first ADMIN user on startup if none exist (BR-20).
 *
 * Logic:
 * 1. Count ADMIN rows — if ≥ 1, skip entirely (env var ignored).
 * 2. If 0 ADMINs and [bootstrapAdminEmail] is blank/null → fail fast.
 * 3. If email is present but not a valid-looking address → fail fast.
 * 4. If email already exists as USER → promote to ADMIN.
 * 5. Otherwise → insert a new ADMIN row.
 */
@Service
class BootstrapAdminService(
    private val appUserRepository: AppUserRepository,
    @Value("\${COZAZJEB_BOOTSTRAP_ADMIN_EMAIL:}") private val bootstrapAdminEmail: String,
) {

    private val log = LoggerFactory.getLogger(BootstrapAdminService::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (appUserRepository.countByRole(Role.ADMIN) > 0) {
            log.info("Bootstrap skipped — at least one ADMIN already exists")
            return
        }

        val email = AllowlistAuthorizationManager.normalizeEmail(bootstrapAdminEmail)
        if (email.isNullOrBlank()) {
            throw IllegalStateException(
                "No ADMIN users exist and COZAZJEB_BOOTSTRAP_ADMIN_EMAIL is missing or empty. " +
                    "Set this environment variable to the first admin's email."
            )
        }
        if (!looksLikeEmail(email)) {
            throw IllegalStateException(
                "COZAZJEB_BOOTSTRAP_ADMIN_EMAIL '$email' is not a valid email address."
            )
        }

        val existing = appUserRepository.findByEmail(email)
        if (existing != null) {
            appUserRepository.save(existing.copy(role = Role.ADMIN))
            log.info("Bootstrap: promoted existing user '{}' to ADMIN", email)
        } else {
            appUserRepository.save(AppUser(email = email, role = Role.ADMIN))
            log.info("Bootstrap: created ADMIN user '{}'", email)
        }
    }

    /** Minimal structural check — full validation is at the API boundary. */
    private fun looksLikeEmail(value: String): Boolean =
        value.contains('@') && value.indexOf('@') > 0 && value.length <= 254
}
