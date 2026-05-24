// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.checkbox.Checkbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozadzban.notifications.NotificationPreferenceService
import pl.bnowakowski.cozadzban.notifications.NotificationPreferenceSummary
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.Role

class NotificationSettingsViewTest {
    private val preferenceService: NotificationPreferenceService = mock()
    private val appUserRepository: AppUserRepository = mock()

    @AfterEach
    fun tearDown() {
        UI.setCurrent(null)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `user sees proposal toggle but not login required toggle`() {
        val user = AppUser(2L, "user@example.com", Role.USER)
        authenticateAs(user.email, Role.USER)
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail(user.email)).thenReturn(user)
        whenever(preferenceService.summaryFor(user)).thenReturn(summary())

        val view = NotificationSettingsView(preferenceService, appUserRepository)

        val checkboxes = findComponents(view, Checkbox::class.java)
        assertTrue(checkboxes.any { it.label == "Article proposal review notifications" })
        assertFalse(checkboxes.single { it.label == "Facebook login required notifications" }.isVisible)
    }

    @Test
    fun `admin sees login required toggle`() {
        val admin = AppUser(1L, "admin@example.com", Role.ADMIN)
        authenticateAs(admin.email, Role.ADMIN)
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail(admin.email)).thenReturn(admin)
        whenever(preferenceService.summaryFor(admin)).thenReturn(summary())

        val view = NotificationSettingsView(preferenceService, appUserRepository)

        val checkboxes = findComponents(view, Checkbox::class.java)
        assertTrue(checkboxes.single { it.label == "Facebook login required notifications" }.isVisible)
    }

    private fun authenticateAs(email: String, role: Role) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                email,
                "n/a",
                listOf(SimpleGrantedAuthority("ROLE_${role.name}")),
            )
    }

    private fun summary(): NotificationPreferenceSummary =
        NotificationPreferenceSummary(
            pushoverConfigured = false,
            pushoverUserKeySuffix = null,
            pushoverDevice = null,
            facebookLoginRequiredEnabled = false,
            facebookProposalsSubmittedEnabled = false,
        )

    private fun <T : Component> findComponents(root: Component, type: Class<T>): List<T> {
        val found = mutableListOf<T>()

        fun walk(component: Component) {
            if (type.isInstance(component)) found.add(type.cast(component))
            component.children.forEach { walk(it) }
        }

        walk(root)
        return found
    }
}
