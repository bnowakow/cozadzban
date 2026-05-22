// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.component.textfield.TextField
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleContent
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import pl.bnowakowski.cozadzban.facebookimport.FacebookCandidateApproval
import pl.bnowakowski.cozadzban.facebookimport.FacebookCandidateApprovalDecision
import pl.bnowakowski.cozadzban.facebookimport.FacebookProfileArticleImporter
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import pl.bnowakowski.cozadzban.version.AppBuildProperties
import java.util.Optional
import java.util.concurrent.CompletableFuture

class ArticleListViewTest {

    private val articleRepository: ArticleRepository = mock()
    private val articleContentRepository: ArticleContentRepository = mock()
    private val articleService: ArticleService = mock()
    private val facebookProfileArticleImporter: FacebookProfileArticleImporter = mock()
    private val appUserRepository: AppUserRepository = mock()
    private val buildProperties = AppBuildProperties(
        version = "0.8.0",
        commit = "abc12345",
    )
    private val languageFlagCache: LanguageFlagCache = mock()

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
        whenever(facebookProfileArticleImporter.facebookImportUnavailableReason()).thenReturn(null)

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val buttons = findComponents(view, Button::class.java)
        val importButton = buttons.firstOrNull { it.text == "Import Facebook Posts" }
        val stopButton = findFacebookStopButton(view)

        assertTrue(buttons.any { it.text == "Add Article" })
        assertTrue(importButton != null, "Expected admin import button to be present")
        assertTrue(importButton!!.isEnabled, "Expected configured import button to be enabled")
        assertTrue(stopButton != null, "Expected admin stop import button to be present")
        assertFalse(stopButton!!.isEnabled, "Expected stop button to be disabled when no import is running")

        UI.setCurrent(UI())
        importButton.click()

        verify(facebookProfileArticleImporter).startImport(any())
    }

    @Test
    fun `admin users can stop a running facebook import`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        whenever(facebookProfileArticleImporter.facebookImportUnavailableReason()).thenReturn(null)
        whenever(facebookProfileArticleImporter.isImportRunning()).thenReturn(true)

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val stopButton = findFacebookStopButton(view)

        assertTrue(stopButton != null, "Expected admin stop import button to be present")
        assertTrue(stopButton!!.isEnabled, "Expected stop button to be enabled while import is running")

        stopButton.click()

        verify(facebookProfileArticleImporter).terminateImport()
    }

    @Test
    fun `admin users see disabled facebook import button when importer user is missing`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        whenever(facebookProfileArticleImporter.facebookImportUnavailableReason()).thenReturn(
            "app.facebook-import.username must point to an existing app user",
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val buttons = findComponents(view, Button::class.java)
        val importButton = buttons.firstOrNull { it.text == "Import Facebook Posts" }
        val stopButton = findFacebookStopButton(view)

        assertTrue(importButton != null, "Expected admin import button to be present")
        assertFalse(importButton!!.isEnabled, "Expected misconfigured import button to be disabled")
        assertEquals(
            "app.facebook-import.username must point to an existing app user",
            Tooltip.forComponent(importButton.parent.get()).text,
        )
        assertTrue(stopButton != null, "Expected admin stop import button to be present")
        assertFalse(stopButton!!.isEnabled, "Expected misconfigured stop button to be disabled")
        assertEquals(
            "app.facebook-import.username must point to an existing app user",
            Tooltip.forComponent(stopButton.parent.get()).text,
        )

        importButton.click()
        stopButton.click()

        verify(facebookProfileArticleImporter, never()).startImport(any())
        verify(facebookProfileArticleImporter, never()).terminateImport()
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
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val buttons = findComponents(view, Button::class.java)

        assertTrue(buttons.any { it.text == "Add Article" })
        assertFalse(buttons.any { it.text == "Import Facebook Posts" })
        assertTrue(findFacebookStopButton(view) == null)
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
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
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
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val spans = findComponents(view, Span::class.java)

        assertTrue(spans.any { it.text == "v0.8.0+abc12345" })
        assertFalse(spans.any { it.text.startsWith("Cookies: necessary session/auth cookies") })
    }

    @Test
    fun `polish and english language filter chips show flags`() {
        stubArticles(topLanguages = listOf("pl", "en"))
        whenever(languageFlagCache.flagPath("pl")).thenReturn("/flags/pl.svg")
        whenever(languageFlagCache.flagPath("en")).thenReturn("/flags/us.svg")

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val buttons = findComponents(view, Button::class.java)
        val flagSpans = findComponents(view, Span::class.java).filter { it.hasClassName("czj-language-flag") }
        val backgrounds = flagSpans.map { it.element.style.get("background-image").orEmpty() }

        assertTrue(buttons.any { it.text == "pl" })
        assertTrue(buttons.any { it.text == "en" })
        assertTrue(backgrounds.any { it.contains("/flags/pl.svg") })
        assertTrue(backgrounds.any { it.contains("/flags/us.svg") })
    }

    @Test
    fun `article card hides thumbnail image when browser reports load error`() {
        stubArticles()
        val article = Article(
            id = 40L,
            url = "https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0cmzW1Mr2ZtDhdBUVJEJWVzXNHRfkr3g8RbAEG5rhtV8ZCMzG9jXjZXFPQJeNNfFdl",
            language = "pl",
            title = "Title",
            thumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1496190555209039",
            createdByUserId = 1L,
        )
        whenever(articleContentRepository.findById(40L)).thenReturn(Optional.empty())

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val image = findComponents(card, Image::class.java)
            .single { it.src == article.thumbnail }

        assertEquals(article.thumbnail, image.src)
    }

    @Test
    fun `article card hides title when it matches cached article text`() {
        stubArticles()
        val article = Article(
            id = 42L,
            url = "https://www.facebook.com/photo/?fbid=1287889633490862&set=a.358353523111149",
            language = "pl",
            title = "Same text as cached content",
            lead = "Visible lead",
            createdByUserId = 1L,
        )
        whenever(articleContentRepository.findById(42L)).thenReturn(
            Optional.of(
                ArticleContent(
                    articleId = 42L,
                    content = " Same text as cached content ",
                    truncated = true,
                ),
            ),
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val divTexts = findComponents(card, com.vaadin.flow.component.html.Div::class.java).map { it.text }

        assertFalse(divTexts.contains("Same text as cached content"))
        assertTrue(divTexts.contains("Visible lead"))
    }

    @Test
    fun `article card hides ellipsis shortened title when cached text starts with title prefix`() {
        stubArticles()
        val title = "To chyba najlepsze podsumowanie co sie stanie jak na Polske ktos napadnie..."
        val article = Article(
            id = 43L,
            url = "https://www.facebook.com/photo/?fbid=26699459673045248&set=a.174617535956156",
            language = "pl",
            title = title,
            lead = "Visible lead",
            createdByUserId = 1L,
        )
        whenever(articleContentRepository.findById(43L)).thenReturn(
            Optional.of(
                ArticleContent(
                    articleId = 43L,
                    content = "To chyba najlepsze podsumowanie co sie stanie jak na Polske ktos napadnie i co dalej.",
                ),
            ),
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val divTexts = findComponents(card, com.vaadin.flow.component.html.Div::class.java).map { it.text }

        assertFalse(divTexts.contains(title))
        assertTrue(divTexts.contains("Visible lead"))
    }

    @Test
    fun `article card hides ellipsis shortened title when cached text contains title prefix without leading emoji`() {
        stubArticles()
        val title = "💉 Amerykanski Narodowy Instytut Onkologiczny, Memorial Sloan Kettering Cancer Center, Mayo Clinic oraz wielu innych eks..."
        val article = Article(
            id = 44L,
            url = "https://www.facebook.com/DEMAGOG/posts/pfbid037WsMCu5fbaMFwwNvW3uAv85zyupQDrXPBE5LfPwHbxqMkmLXKwaSBgJgrBgwXhc2l",
            language = "pl",
            title = title,
            lead = "Visible lead",
            createdByUserId = 1L,
        )
        whenever(articleContentRepository.findById(44L)).thenReturn(
            Optional.of(
                ArticleContent(
                    articleId = 44L,
                    content = "Online status indicator Active Demagog · Amerykanski Narodowy Instytut Onkologiczny, Memorial Sloan Kettering Cancer Center, Mayo Clinic oraz wielu innych ekspertow twierdzi.",
                ),
            ),
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val divTexts = findComponents(card, com.vaadin.flow.component.html.Div::class.java).map { it.text }

        assertFalse(divTexts.contains(title))
        assertTrue(divTexts.contains("Visible lead"))
    }

    @Test
    fun `article card caps very long lead text for frontend display`() {
        stubArticles()
        val longLead = List(900) { "word$it" }.joinToString(" ")
        val article = Article(
            id = 45L,
            url = "https://www.facebook.com/photo/?fbid=1287889633490862&set=a.358353523111149",
            language = "pl",
            title = "Distinct title",
            lead = longLead,
            createdByUserId = 1L,
        )
        whenever(articleContentRepository.findById(45L)).thenReturn(Optional.empty())

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookProfileArticleImporter,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val lead = findComponents(card, Div::class.java)
            .single { it.hasClassName("czj-article-lead") }

        assertTrue(lead.text.length <= 1_203)
        assertTrue(lead.text.endsWith("..."))
        assertTrue(lead.hasClassName("czj-article-lead-truncated"))
        assertFalse(lead.text.contains("word899"))
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
                "publishedAt",
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

    private fun findFacebookStopButton(root: Component): Button? =
        findComponents(root, Button::class.java)
            .firstOrNull { it.element.getAttribute("aria-label")?.startsWith("Stop Facebook import") == true }
}
