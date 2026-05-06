// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.analytics

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.analytics")
data class AnalyticsProperties(
    /** Google Analytics 4 Measurement ID, e.g. "G-XXXXXXXXXX". Blank = disabled. */
    val googleMeasurementId: String = "",
    /** StatCounter project ID. Blank = disabled. */
    val statcounterProjectId: String = "",
    /** StatCounter security code. Required when statcounterProjectId is set. */
    val statcounterSecurityId: String = "",
) {
    val isGoogleEnabled: Boolean get() = googleMeasurementId.isNotBlank()
    val isStatcounterEnabled: Boolean get() = statcounterProjectId.isNotBlank() && statcounterSecurityId.isNotBlank()
    val isAnyEnabled: Boolean get() = isGoogleEnabled || isStatcounterEnabled
}
