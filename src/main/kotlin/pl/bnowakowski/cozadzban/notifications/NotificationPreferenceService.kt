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
        val devices = PushoverDevices.normalize(input.pushoverDevices)
        val userKey = input.pushoverUserKey?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.let { encryptor.decrypt(it.pushoverUserKeyEncrypted) }
            ?: throw IllegalArgumentException("Pushover user key is required")

        val validation = pushoverClient.validateUser(userKey, devices)

        val saved = repository.upsert(
            appUserId = appUserId,
            pushoverUserKeyEncrypted = encryptor.encrypt(userKey),
            pushoverUserKeySuffix = keySuffix(userKey),
            pushoverDevices = devices,
            facebookLoginRequiredEnabled = input.facebookLoginRequiredEnabled && user.role == Role.ADMIN,
            facebookProposalsSubmittedEnabled = input.facebookProposalsSubmittedEnabled,
        )
        return saved.toSummary(validation.devices)
    }

    fun availablePushoverDevices(user: AppUser, pushoverUserKey: String?): List<String> {
        ensureActive(user)
        val existing = repository.findByUserId(user.idOrThrow())
        val userKey = pushoverUserKey?.trim()?.takeIf { it.isNotBlank() }
            ?: existing?.let { encryptor.decrypt(it.pushoverUserKeyEncrypted) }
            ?: throw IllegalArgumentException("Pushover user key is required")
        return pushoverClient.availableDevices(userKey)
    }

    fun sendTest(user: AppUser) {
        ensureActive(user)
        val preference = repository.findByUserId(user.idOrThrow())
            ?: throw IllegalArgumentException("Configure Pushover before sending a test notification")
        pushoverClient.send(
            PushoverMessage(
                userKey = encryptor.decrypt(preference.pushoverUserKeyEncrypted),
                devices = preference.pushoverDevices,
                title = "Co za dzban notifications",
                message = "Test notification from Co za dzban.",
                url = "https://cozadzban.pl",
                urlTitle = "Open Co za dzban",
            ),
        )
    }

    private fun NotificationPreference?.toSummary(
        availablePushoverDevices: List<String> = emptyList(),
    ): NotificationPreferenceSummary =
        NotificationPreferenceSummary(
            pushoverConfigured = this != null,
            pushoverUserKeySuffix = this?.pushoverUserKeySuffix,
            pushoverDevices = this?.pushoverDevices.orEmpty(),
            facebookLoginRequiredEnabled = this?.facebookLoginRequiredEnabled == true,
            facebookProposalsSubmittedEnabled = this?.facebookProposalsSubmittedEnabled == true,
            availablePushoverDevices = availablePushoverDevices,
        )

    private fun ensureActive(user: AppUser) {
        require(user.status == AppUserStatus.ACTIVE) { "Current user is not active" }
    }

    private fun AppUser.idOrThrow(): Long =
        id ?: throw IllegalArgumentException("Current user is not persisted")

    companion object {
        fun keySuffix(userKey: String): String =
            userKey.trim().takeLast(4).padStart(userKey.trim().length.coerceAtMost(4), '*')
    }
}
