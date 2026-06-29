// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.ClickEvent
import com.vaadin.flow.component.ComponentUtil
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.Location
import com.vaadin.flow.router.QueryParameters
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
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalService
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatusFilter
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProgressSnapshot
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportRunStatus
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import pl.bnowakowski.cozadzban.version.AppBuildProperties
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

class ArticleListViewTest {

    private val articleRepository: ArticleRepository = mock()
    private val articleContentRepository: ArticleContentRepository = mock()
    private val articleService: ArticleService = mock()
    private val facebookImportJobService: FacebookImportJobService = mock()
    private val articleProposalService: FacebookArticleProposalService = mock()
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
        whenever(facebookImportJobService.availableImportTypes()).thenReturn(listOf(FacebookImportType.SELENIUM))
        whenever(facebookImportJobService.startImport(FacebookImportType.SELENIUM)).thenReturn("run-1")

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val importItem = findMenuItem(view, "Import Facebook with Selenium")
        val stopItem = findMenuItem(view, "Stop Facebook import")

        assertTrue(findMenuItem(view, "Menu") != null)
        assertTrue(findMenuItem(view, "Article proposals") != null)
        assertTrue(findMenuItem(view, "Add article") != null)
        assertTrue(importItem != null, "Expected admin import menu item to be present")
        assertTrue(importItem!!.isEnabled, "Expected configured import menu item to be enabled")
        assertTrue(stopItem != null, "Expected admin stop import menu item to be present")
        assertFalse(stopItem!!.isEnabled, "Expected stop item to be disabled when no import is running")

        UI.setCurrent(UI())
        clickMenuItem(importItem)

        verify(facebookImportJobService).startImport(FacebookImportType.SELENIUM)
    }

    @Test
    fun `top bar logo and brand link to main page`() {
        stubArticles()

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val brandLink = findComponents(view, Anchor::class.java)
            .single { it.element.getAttribute("aria-label") == "Go to main page" }

        assertEquals("/", brandLink.href)
        assertTrue(findComponents(brandLink, Image::class.java).any { it.alt.orElse(null) == "Co za dzban" })
        assertTrue(findComponents(brandLink, Span::class.java).any { it.text == "Co za dzban" })
    }

    @Test
    fun `admin users see apify import button and can trigger it`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        whenever(facebookImportJobService.availableImportTypes()).thenReturn(
            listOf(FacebookImportType.APIFY, FacebookImportType.SELENIUM),
        )
        whenever(facebookImportJobService.startImport(FacebookImportType.APIFY)).thenReturn("run-apify")

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val apifyItem = findMenuItem(view, "Import Facebook with Apify")

        assertTrue(apifyItem != null, "Expected admin Apify import menu item to be present")
        assertTrue(findMenuItem(view, "Import Facebook with Selenium") != null)

        UI.setCurrent(UI())
        clickMenuItem(apifyItem!!)

        verify(facebookImportJobService).startImport(FacebookImportType.APIFY)
    }

    @Test
    fun `facebook import started message names import type`() {
        stubArticles()
        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("facebookImportStartedMessage", FacebookImportType::class.java)
        method.isAccessible = true

        assertEquals("Selenium import started", method.invoke(view, FacebookImportType.SELENIUM))
        assertEquals("Apify import started", method.invoke(view, FacebookImportType.APIFY))
    }

    @Test
    fun `admin users see apify import button when feature flag is enabled even if service does not report it as available`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        whenever(facebookImportJobService.availableImportTypes()).thenReturn(listOf(FacebookImportType.SELENIUM))
        whenever(facebookImportJobService.startImport(FacebookImportType.APIFY)).thenReturn("run-apify")

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
            FacebookImportProperties(apify = FacebookImportProperties.Apify(enabled = true)),
        )
        val apifyItem = findMenuItem(view, "Import Facebook with Apify")

        assertTrue(apifyItem != null, "Expected admin Apify import menu item to be present when feature flag is enabled")

        UI.setCurrent(UI())
        clickMenuItem(apifyItem!!)

        verify(facebookImportJobService).startImport(FacebookImportType.APIFY)
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
        whenever(facebookImportJobService.availableImportTypes()).thenReturn(listOf(FacebookImportType.SELENIUM))
        whenever(facebookImportJobService.isImportRunning()).thenReturn(true)

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val stopItem = findMenuItem(view, "Stop Facebook import")

        assertTrue(stopItem != null, "Expected admin stop import menu item to be present")
        assertTrue(stopItem!!.isEnabled, "Expected stop item to be enabled while import is running")

        UI.setCurrent(UI())
        clickMenuItem(stopItem)

        verify(facebookImportJobService).terminateImport()
    }

    @Test
    fun `admin users see running facebook import progress above filters`() {
        val adminEmail = "admin@example.com"
        authenticateAs(adminEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(adminEmail)).thenReturn(
            AppUser(1L, adminEmail, Role.ADMIN, AppUserStatus.ACTIVE),
        )
        whenever(facebookImportJobService.currentProgress()).thenReturn(
            FacebookImportProgressSnapshot(
                importRunId = "run-progress",
                importType = FacebookImportType.APIFY,
                status = FacebookImportRunStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(125),
                lastUpdatedAt = Instant.parse("2026-05-24T10:15:30Z"),
                phase = "Sending proposals",
                detail = "Facebook import discovery pass 2/4 scroll 3/3",
                phaseIndex = 8,
                phaseCount = 8,
                passIndex = 2,
                passCount = 3,
                matchedPostCount = 9,
                submittedCount = 4,
                skippedExistingCount = 5,
                failedCount = 0,
            ),
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val progressPanel = findComponents(view, Div::class.java)
            .firstOrNull { it.hasClassName("czj-facebook-import-progress") && it.isVisible }
        val spans = findComponents(view, Span::class.java).map { it.text }

        assertTrue(progressPanel != null, "Expected Facebook import progress panel to be present")
        assertTrue(progressPanel!!.isVisible, "Expected Facebook import progress panel to be visible")
        assertTrue(spans.contains("Facebook import is running (Apify)"))
        assertTrue(spans.contains("Sending proposals"))
        assertTrue(spans.contains("Facebook import discovery pass 2/4 scroll 3/3"))
        assertTrue(spans.contains("Matched posts"))
        assertTrue(spans.contains("9"))
        assertTrue(spans.contains("Already imported"))
        assertTrue(spans.contains("5"))
        assertTrue(spans.contains("Sent"))
        assertTrue(spans.contains("4"))
        assertTrue(spans.contains("Phase"))
        assertTrue(spans.contains("8 of 8"))
        assertTrue(spans.contains("Last updated"))
        assertTrue(spans.contains("2026-05-24 10:15:30 UTC"))
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
        whenever(facebookImportJobService.availableImportTypes()).thenReturn(emptyList())

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val importItem = findMenuItem(view, "Import Facebook unavailable")
        val stopItem = findMenuItem(view, "Stop Facebook import")

        assertTrue(importItem != null, "Expected admin import unavailable menu item to be present")
        assertFalse(importItem!!.isEnabled, "Expected misconfigured import item to be disabled")
        assertTrue(stopItem != null, "Expected admin stop import menu item to be present")
        assertFalse(stopItem!!.isEnabled, "Expected misconfigured stop item to be disabled")

        verify(facebookImportJobService, never()).startImport(FacebookImportType.APIFY)
        verify(facebookImportJobService, never()).startImport(FacebookImportType.SELENIUM)
        verify(facebookImportJobService, never()).terminateImport()
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
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        assertTrue(findMenuItem(view, "Menu") != null)
        assertTrue(findMenuItem(view, "Article proposals") != null)
        assertTrue(findMenuItem(view, "Add article") != null)
        assertFalse(findMenuItem(view, "Import Facebook with Selenium") != null)
        assertTrue(findMenuItem(view, "Stop Facebook import") == null)
    }

    @Test
    fun `add article navigation request opens add article dialog`() {
        val userEmail = "user@example.com"
        authenticateAs(userEmail)
        val ui = UI()
        UI.setCurrent(ui)
        stubArticles()
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(
            AppUser(2L, userEmail, Role.USER, AppUserStatus.ACTIVE),
        )
        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val event: AfterNavigationEvent = mock()
        whenever(event.location).thenReturn(Location("", QueryParameters.of("addArticle", "1")))

        view.afterNavigation(event)

        val field = view.javaClass.getDeclaredField("activeAddArticleDialog")
        field.isAccessible = true
        val dialog = field.get(view) as? Dialog
        assertTrue(dialog?.headerTitle == "Add Article" && dialog.isOpened)
    }

    @Test
    fun `anonymous users do not see protected article proposal menu item`() {
        stubArticles()

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        assertTrue(findMenuItem(view, "Menu") != null)
        assertTrue(findMenuItem(view, "Article proposals") == null)
        assertTrue(findMenuItem(view, "Add article") == null)
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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
    fun `article card shows facebook import source attribution`() {
        stubArticles()
        val article = Article(
            id = 41L,
            url = "https://example.com/from-facebook",
            language = "pl",
            title = "Imported article",
            createdByUserId = 1L,
            sourceImportType = FacebookImportType.APIFY.name,
        )

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )
        val method = view.javaClass.getDeclaredMethod("buildArticleCard", Article::class.java)
        method.isAccessible = true
        val card = method.invoke(view, article) as Component

        val importBadges = findComponents(card, Span::class.java)
            .filter { it.hasClassName("czj-import-source-badge") }

        assertTrue(importBadges.any { it.text == "Apify import" })
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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
            facebookImportJobService,
            articleProposalService,
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


    @Test
    fun `logged reviewers see pending article proposal review notice above filters`() {
        val userEmail = "user@example.com"
        authenticateAs(userEmail)
        UI.setCurrent(UI())
        stubArticles()
        whenever(appUserRepository.findByEmail(userEmail)).thenReturn(
            AppUser(2L, userEmail, Role.USER, AppUserStatus.ACTIVE),
        )
        whenever(articleProposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(3L)

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val visiblePanels = findComponents(view, Div::class.java)
            .filter { it.hasClassName("czj-facebook-import-progress") && it.isVisible }
        val spans = findComponents(view, Span::class.java).map { it.text }

        assertEquals(1, visiblePanels.size)
        assertTrue(spans.contains("Article proposals waiting for review"))
        assertTrue(spans.contains("3 proposals"))
    }


    @Test
    fun `anonymous users do not see pending article proposal review notice`() {
        stubArticles()
        whenever(articleProposalService.count(FacebookArticleProposalStatusFilter.PENDING)).thenReturn(3L)

        val view = ArticleListView(
            articleRepository,
            articleContentRepository,
            articleService,
            facebookImportJobService,
            articleProposalService,
            appUserRepository,
            buildProperties,
            languageFlagCache,
        )

        val spans = findComponents(view, Span::class.java).map { it.text }

        assertFalse(spans.contains("Article proposals waiting for review"))
        assertFalse(spans.contains("3 proposals"))
        verify(articleProposalService, never()).count(any())
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

    private fun findMenuItem(root: Component, text: String): MenuItem? =
        findComponents(root, MenuBar::class.java)
            .flatMap { menuBar -> menuBar.items.flatMap(::flattenMenuItem) }
            .firstOrNull { it.text == text || it.element.getAttribute("aria-label") == text }

    private fun flattenMenuItem(item: MenuItem): List<MenuItem> =
        listOf(item) + item.subMenu.items.flatMap(::flattenMenuItem)

    private fun clickMenuItem(item: MenuItem) {
        ComponentUtil.fireEvent(item, ClickEvent(item))
    }
}
