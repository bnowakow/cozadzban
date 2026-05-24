// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role

@Service
@Transactional
class NotificationPreferenceService(
    private val repository: NotificationPreferenceRepository,
    private val encryptor: PushoverUserKeyEncryptor,
    private val pushoverClient: PushoverClient,
) {
    @Transactional(readOnly = true)
    fun summaryFor(user: AppUser): NotificationPreferenceSummary {
        ensureActive(user)
        return repository.findByUserId(user.idOrThrow()).toSummary()
    }

    fun save(user: AppUser, input: NotificationPreferenceInput): NotificationPreferenceSummary {
        ensureActive(user)
        val appUserId = user.idOrThrow()
        val existing = repository.findByUserId(appUserId)
        val device = input.pushoverDevice.normalizedDevice()
        val userKey = input.pushoverUserKey?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.let { encryptor.decrypt(it.pushoverUserKeyEncrypted) }
            ?: throw IllegalArgumentException("Pushover user key is required")

        pushoverClient.validateUser(userKey, device)

        val saved = repository.upsert(
            appUserId = appUserId,
            pushoverUserKeyEncrypted = encryptor.encrypt(userKey),
            pushoverUserKeySuffix = keySuffix(userKey),
            pushoverDevice = device,
            facebookLoginRequiredEnabled = input.facebookLoginRequiredEnabled && user.role == Role.ADMIN,
            facebookProposalsSubmittedEnabled = input.facebookProposalsSubmittedEnabled,
        )
        return saved.toSummary()
    }

    fun sendTest(user: AppUser) {
        ensureActive(user)
        val preference = repository.findByUserId(user.idOrThrow())
            ?: throw IllegalArgumentException("Configure Pushover before sending a test notification")
        pushoverClient.send(
            PushoverMessage(
                userKey = encryptor.decrypt(preference.pushoverUserKeyEncrypted),
                device = preference.pushoverDevice,
                title = "Co za dzban notifications",
                message = "Test notification from Co za dzban.",
                url = "https://cozadzban.pl",
                urlTitle = "Open Co za dzban",
            ),
        )
    }

    private fun NotificationPreference?.toSummary(): NotificationPreferenceSummary =
        NotificationPreferenceSummary(
            pushoverConfigured = this != null,
            pushoverUserKeySuffix = this?.pushoverUserKeySuffix,
            pushoverDevice = this?.pushoverDevice,
            facebookLoginRequiredEnabled = this?.facebookLoginRequiredEnabled == true,
            facebookProposalsSubmittedEnabled = this?.facebookProposalsSubmittedEnabled == true,
        )

    private fun ensureActive(user: AppUser) {
        require(user.status == AppUserStatus.ACTIVE) { "Current user is not active" }
    }

    private fun AppUser.idOrThrow(): Long =
        id ?: throw IllegalArgumentException("Current user is not persisted")

    private fun String?.normalizedDevice(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }

    companion object {
        fun keySuffix(userKey: String): String =
            userKey.trim().takeLast(4).padStart(userKey.trim().length.coerceAtMost(4), '*')
    }
}
