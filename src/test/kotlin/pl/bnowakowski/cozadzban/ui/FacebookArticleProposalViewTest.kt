// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.select.Select
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposal
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalService
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatusFilter
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.Role
import java.time.Instant

class FacebookArticleProposalViewTest {
    private val proposalService: FacebookArticleProposalService = mock()
    private val appUserRepository: AppUserRepository = mock()

    @AfterEach
    fun tearDown() {
        UI.setCurrent(null)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `view loads pending proposals by default for logged user`() {
        authenticateAs("user@example.com")
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail("user@example.com")).thenReturn(
            AppUser(1L, "user@example.com", Role.USER),
        )
        whenever(proposalService.findPage(FacebookArticleProposalStatusFilter.PENDING, 0, 100))
            .thenReturn(listOf(proposal()))
        whenever(proposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(1L)

        val view = FacebookArticleProposalView(proposalService, appUserRepository)

        val selects = findComponents(view, Select::class.java)
        assertEquals(FacebookArticleProposalStatusFilter.PENDING, selects.single().value)
        verify(proposalService).findPage(FacebookArticleProposalStatusFilter.PENDING, 0, 100)
        verify(proposalService).count(FacebookArticleProposalStatusFilter.PENDING)
    }

    @Test
    fun `proposal links open in a new window`() {
        authenticateAs("user@example.com")
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail("user@example.com")).thenReturn(
            AppUser(1L, "user@example.com", Role.USER),
        )
        whenever(proposalService.findPage(FacebookArticleProposalStatusFilter.PENDING, 0, 100)).thenReturn(emptyList())
        whenever(proposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(0L)
        val view = FacebookArticleProposalView(proposalService, appUserRepository)
        val method = view.javaClass.getDeclaredMethod("externalLink", String::class.java)
        method.isAccessible = true

        val anchor = method.invoke(view, "https://example.com/story") as Anchor

        assertEquals("https://example.com/story", anchor.href)
        assertEquals("_blank", anchor.target.orElse(null))
        assertTrue(anchor.element.getAttribute("rel").contains("noopener"))
    }

    @Test
    fun `proposal rows show import source attribution`() {
        authenticateAs("user@example.com")
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail("user@example.com")).thenReturn(
            AppUser(1L, "user@example.com", Role.USER),
        )
        whenever(proposalService.findPage(FacebookArticleProposalStatusFilter.PENDING, 0, 100))
            .thenReturn(listOf(proposal().copy(importType = FacebookImportType.APIFY)))
        whenever(proposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(1L)

        val view = FacebookArticleProposalView(proposalService, appUserRepository)

        val badges = findComponents(view, Span::class.java)
            .filter { it.hasClassName("czj-import-source-badge") }
        assertTrue(badges.any { it.text == "Apify import" })
    }

    @Test
    fun `view uses main site top bar navigation`() {
        authenticateAs("user@example.com")
        UI.setCurrent(UI())
        whenever(appUserRepository.findByEmail("user@example.com")).thenReturn(
            AppUser(1L, "user@example.com", Role.USER),
        )
        whenever(proposalService.findPage(FacebookArticleProposalStatusFilter.PENDING, 0, 100)).thenReturn(emptyList())
        whenever(proposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(0L)

        val view = FacebookArticleProposalView(proposalService, appUserRepository)
        val buttons = findComponents(view, Button::class.java)

        assertTrue(buttons.any { it.text == "RSS" })
        assertTrue(buttons.any { it.text == "Logout" })
        assertTrue(findMenuItem(view, "Menu") != null)
        assertTrue(findMenuItem(view, "Feed") != null)
        assertTrue(findMenuItem(view, "Article proposals") == null)
        assertTrue(findMenuItem(view, "Notification settings") != null)
    }

    private fun authenticateAs(email: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                email,
                "n/a",
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun proposal(): FacebookArticleProposal =
        FacebookArticleProposal(
            id = 1L,
            candidateId = "candidate-1",
            importRunId = "run-1",
            articleUrl = "https://example.com/story",
            canonicalArticleUrl = "https://example.com/story",
            facebookPostUrl = "https://www.facebook.com/source/posts/1",
            guessedLanguage = "pl",
            correctedLanguage = null,
            status = null,
            articleId = null,
            decidedByUserId = null,
            decidedAt = null,
            submittedAt = Instant.parse("2026-05-24T10:00:00Z"),
            lastSeenAt = Instant.parse("2026-05-24T10:00:00Z"),
            logsCompressed = null,
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

    private fun findMenuItem(root: Component, text: String): MenuItem? =
        findComponents(root, MenuBar::class.java)
            .flatMap { menuBar -> menuBar.items.flatMap(::flattenMenuItem) }
            .firstOrNull { it.text == text || it.element.getAttribute("aria-label") == text }

    private fun flattenMenuItem(item: MenuItem): List<MenuItem> =
        listOf(item) + item.subMenu.items.flatMap(::flattenMenuItem)
}
