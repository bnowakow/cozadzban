// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.router.Route
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozazjeb.article.ArticleContentRepository
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.Role
import java.time.Instant

class AdminViewTest {

    private val appUserService: AppUserService = mock()
    private val articleRepository: ArticleRepository = mock()
    private val articleContentRepository: ArticleContentRepository = mock()

    @Test
    fun `admin view has expected route and role guard`() {
        val route = AdminView::class.java.getAnnotation(Route::class.java)
        val rolesAllowed = AdminView::class.java.getAnnotation(RolesAllowed::class.java)

        assertNotNull(route)
        assertEquals("admin", route.value)
        assertNotNull(rolesAllowed)
        assertTrue(rolesAllowed.value.contains("ADMIN"), "Expected ADMIN in @RolesAllowed")
    }

    @Test
    fun `admin view loads users on init`() {
        whenever(appUserService.list()).thenReturn(
            listOf(
                AppUser(
                    id = 1L,
                    email = "admin@example.com",
                    role = Role.ADMIN,
                    createdAt = Instant.parse("2026-05-05T00:00:00Z"),
                ),
            ),
        )
        whenever(articleContentRepository.findAll()).thenReturn(emptyList())

        AdminView(appUserService, articleRepository, articleContentRepository)

        verify(appUserService).list()
    }

    @Test
    fun `admin view contains logout and add user controls`() {
        whenever(appUserService.list()).thenReturn(emptyList())
        whenever(articleContentRepository.findAll()).thenReturn(emptyList())

        val view = AdminView(appUserService, articleRepository, articleContentRepository)
        val buttons = findComponents(view, Button::class.java)

        assertTrue(buttons.any { it.text == "Logout" })
        assertTrue(buttons.any { it.text == "Add user" })
    }

    @Test
    fun `admin view contains add user button`() {
        whenever(appUserService.list()).thenReturn(emptyList())
        whenever(articleContentRepository.findAll()).thenReturn(emptyList())

        val view = AdminView(appUserService, articleRepository, articleContentRepository)

        val buttons = findComponents(view, Button::class.java)
        assertTrue(buttons.any { it.text == "Add user" })
    }

    private fun <T : Component> findComponents(root: Component, type: Class<T>): List<T> {
        val found = mutableListOf<T>()

        fun walk(component: Component) {
            if (type.isInstance(component)) {
                found.add(type.cast(component))
            }
            component.children.forEach { walk(it) }
        }

        walk(root)
        return found
    }
}
