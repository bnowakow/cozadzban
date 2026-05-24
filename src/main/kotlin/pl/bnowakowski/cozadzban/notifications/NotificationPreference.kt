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
    val pushoverDevice: String?,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class NotificationRecipient(
    val appUserId: Long,
    val email: String,
    val role: Role,
    val pushoverUserKeyEncrypted: String,
    val pushoverDevice: String?,
)

data class NotificationPreferenceInput(
    val pushoverUserKey: String?,
    val pushoverDevice: String?,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
)

data class NotificationPreferenceSummary(
    val pushoverConfigured: Boolean,
    val pushoverUserKeySuffix: String?,
    val pushoverDevice: String?,
    val facebookLoginRequiredEnabled: Boolean,
    val facebookProposalsSubmittedEnabled: Boolean,
)
