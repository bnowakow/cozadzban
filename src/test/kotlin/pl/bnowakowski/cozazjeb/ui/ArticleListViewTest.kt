// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.TextField
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.facebookimport.FacebookCandidateApproval
import pl.bnowakowski.cozazjeb.facebookimport.FacebookCandidateApprovalDecision
import pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.Role
import pl.bnowakowski.cozazjeb.version.AppBuildProperties
import java.util.concurrent.CompletableFuture

class ArticleListViewTest {

    private val articleRepository: ArticleRepository = mock()
    private val articleService: ArticleService = mock()
    private val facebookProfileArticleImporter: FacebookProfileArticleImporter = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val buildProperties = AppBuildProperties(
        version = "0.8.0",
        commit = "abc12345",
    )

    @AfterEach
    fun tearDown() {
        UI.setCurrent(null)
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `admin users see the facebook import button and can trigger it`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )

        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )
        val buttons = findComponents(view, Button::class.java)
        val importButton = buttons.firstOrNull { it.text == "Import Facebook Posts" }

        assertTrue(buttons.any { it.text == "Add Article" })
        assertTrue(importButton != null, "Expected admin import button to be present")

        importButton!!.click()

        verify(facebookProfileArticleImporter).startImport(any())
    }

    @Test
    fun `non-admin users do not see the facebook import button`() {
        val userEmail = "user@example.com"
        authenticateAs(userEmail)
        stubArticles()
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(
            AppUser(2L, userEmail, Role.USER, AppUserStatus.ACTIVE),
        )

        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )

        val buttons = findComponents(view, Button::class.java)

        assertTrue(buttons.any { it.text == "Add Article" })
        assertFalse(buttons.any { it.text == "Import Facebook Posts" })
    }

    @Test
    fun `facebook approval dialog lists candidates with accept selected by default`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )
        val method = view.javaClass.getDeclaredMethod(
            "buildFacebookCandidateApprovalDialog",
            List::class.java,
            CompletableFuture::class.java,
        )
        method.isAccessible = true

        val approvalFuture = CompletableFuture<List<FacebookCandidateApproval>>()
        val dialog = method.invoke(
            view,
            listOf(
                FacebookCandidateApproval(
                    url = "https://example.com/post",
                    language = "pl",
                    candidateId = "facebook-import-candidate-42",
                    sourcePostUrl = "https://www.facebook.com/source/posts/123",
                ),
            ),
            approvalFuture,
        ) as Dialog

        val anchors = findComponents(dialog, Anchor::class.java)
        val spans = findComponents(dialog, Span::class.java)
        val languages = findComponents(dialog, TextField::class.java)
        val decisions = findComponents(dialog, RadioButtonGroup::class.java)
            .filterIsInstance<RadioButtonGroup<FacebookCandidateApprovalDecision>>()
        val buttons = findComponents(dialog, Button::class.java)

        assertTrue(anchors.any { it.href == "https://example.com/post" })
        assertTrue(anchors.any { it.href == "https://www.facebook.com/source/posts/123" })
        assertTrue(spans.any { it.text == "facebook-import-candidate-42" })
        assertTrue(spans.any { it.text == "Candidate ID" })
        assertTrue(spans.any { it.text == "Candidate URL" })
        assertTrue(spans.any { it.text == "Source Facebook post" })
        assertTrue(spans.any { it.text == "Language" })
        assertTrue(spans.any { it.text == "Decision" })
        assertEquals("pl", languages.single().value)
        assertEquals(FacebookCandidateApprovalDecision.ACCEPT, decisions.single().value)
        assertTrue(buttons.any { it.text == "Submit" })

        languages.single().value = "en"
        buttons.single { it.text == "Submit" }.click()

        assertEquals("en", approvalFuture.get().single().language)
    }

    @Test
    fun `page shows fixed app version badge`() {
        stubArticles()

        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )

        val spans = findComponents(view, Span::class.java)

        assertTrue(spans.any { it.text == "v0.8.0+abc12345" })
        assertFalse(spans.any { it.text.startsWith("Cookies: necessary session/auth cookies") })
    }

    @Test
    fun `polish and english language filter chips show flags`() {
        stubArticles(topLanguages = listOf("pl", "en"))

        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )

        val buttons = findComponents(view, Button::class.java)
        val spans = findComponents(view, Span::class.java)

        assertTrue(buttons.any { it.text == "pl" })
        assertTrue(buttons.any { it.text == "en" })
        assertTrue(spans.any { it.hasClassName("czj-language-flag-pl") })
        assertTrue(spans.any { it.hasClassName("czj-language-flag-en") })
    }

    @Test
    fun `long article lead is trimmed with visible notice`() {
        stubArticles()
        val view = ArticleListView(
            articleRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
        )
        val method = view.javaClass.getDeclaredMethod("buildLead", String::class.java)
        method.isAccessible = true
        val longLead = List(260) { "word$it" }.joinToString(" ")

        val lead = method.invoke(view, longLead) as Div

        val spans = findComponents(lead, Span::class.java)
        val renderedLead = spans.single { it.hasClassName("czj-article-lead-text") }.text
        assertTrue(renderedLead.length <= 1_203)
        assertTrue(renderedLead.endsWith("..."))
        assertTrue(spans.any { it.text == "Lead trimmed because it was too long to show." })
    }

    private fun authenticateAs(email: String) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                email,
                "n/a",
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    private fun stubArticles(topLanguages: List<String> = emptyList()) {
        whenever(
            articleRepository.findPage(
                0,
                20,
                "createdAt",
                "desc",
                null,
                null,
                null,
                null,
                null,
            ),
        ).thenReturn(emptyList())
        whenever(articleRepository.countFiltered(null, null, null, null, null)).thenReturn(0L)
        whenever(articleRepository.findTopLanguages(3)).thenReturn(topLanguages)
        whenever(articleRepository.findDistinctLanguages()).thenReturn(emptyList())
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
