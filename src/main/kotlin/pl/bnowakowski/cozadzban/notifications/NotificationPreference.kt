// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import pl.bnowakowski.cozadzban.user.Role
import java.time.Instant

enum class NotificationProvider {
    PUSHOVER,
}

data class NotificationPreference(
    val appUserId: Long,
    val provider: NotificationProvider,
    val pushoverUserKeyEncrypted: String,
    val pushoverUserKeySuffix: String,
    val pushoverDevices: List<String>,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
    val facebookProposalsAutoApprovedEnabled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class NotificationRecipient(
    val appUserId: Long,
    val email: String,
    val role: Role,
    val pushoverUserKeyEncrypted: String,
    val pushoverDevices: List<String>,
)

data class NotificationPreferenceInput(
    val pushoverUserKey: String?,
    val pushoverDevices: Collection<String>,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
    val facebookProposalsAutoApprovedEnabled: Boolean,
)

data class NotificationPreferenceSummary(
    val pushoverConfigured: Boolean,
    val pushoverUserKeySuffix: String?,
    val pushoverDevices: List<String>,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
    val facebookProposalsAutoApprovedEnabled: Boolean,
    val availablePushoverDevices: List<String> = emptyList(),
)

internal object PushoverDevices {
    fun normalize(devices: Collection<String>): List<String> =
        devices
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    fun parse(value: String?): List<String> =
        normalize(value?.split(",") ?: emptyList())

    fun format(devices: Collection<String>): String? =
        normalize(devices).joinToString(",").takeIf { it.isNotBlank() }
}
