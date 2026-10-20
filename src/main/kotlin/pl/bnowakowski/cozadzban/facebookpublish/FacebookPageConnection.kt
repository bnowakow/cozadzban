// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import java.time.Instant

data class FacebookPageConnection(
    val language: String,
    val pageId: String,
    val pageName: String,
    val enabled: Boolean,
    val timeZone: String,
    val monthlyLinkLimit: Int,
    val minimumPostAgeHours: Int,
    val schedulerIntervalMinutes: Int,
    val connectedAt: Instant,
    val updatedAt: Instant,
)

data class FacebookPageSettings(
    val enabled: Boolean,
    val timeZone: String,
    val monthlyLinkLimit: Int,
    val minimumPostAgeHours: Int,
    val schedulerIntervalMinutes: Int,
)

data class AvailableFacebookPage(val id: String, val name: String, val tasks: Set<String>)
