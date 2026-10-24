// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinSession
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import pl.bnowakowski.cozadzban.facebookpublish.DiscoveredFacebookPage
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageConnection
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageConnectionService
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageOAuthController
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageProperties
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageSettings
import pl.bnowakowski.cozadzban.facebookpublish.FacebookEngagementRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageSyncRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPublicationQueue
import pl.bnowakowski.cozadzban.facebookpublish.PendingFacebookPageSelection
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import java.net.URLEncoder
import java.time.Instant

@Route("admin/facebook-pages")
@PageTitle("Facebook Pages")
@RolesAllowed("ADMIN")
@CssImport("./styles/cozadzban-feed.css")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-context-menu-overlay")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-menu-bar-overlay")
@CssImport(value = "./styles/cozadzban-menu-bar.css", themeFor = "vaadin-menu-bar")
@CssImport(value = "./styles/cozadzban-menu-bar-button.css", themeFor = "vaadin-menu-bar-button")
class FacebookPageSettingsView(
    private val service: FacebookPageConnectionService,
    private val properties: FacebookPageProperties,
    private val articleRepository: ArticleRepository,
    private val languageFlagCache: LanguageFlagCache,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
    private val publications: FacebookPublicationQueue? = null,
    private val engagement: FacebookEngagementRepository? = null,
    private val pageSync: FacebookPageSyncRepository? = null,
) : VerticalLayout() {
    private val articleLanguages = articleRepository.findDistinctLanguages()
    private val language = ComboBox<String>("Article language")
    private val configured = ComboBox<String>("Connected language")
    private val configuredFlag = Span()
    private val enabled = Checkbox("Publish new articles automatically")
    private val timeZone = TextField("Page time zone")
    private val monthlyLinkLimit = IntegerField("Links per month")
    private val minimumPostAgeHours = IntegerField("Minimum post age (hours)")
    private val schedulerIntervalMinutes = IntegerField("Scheduler interval (minutes)")
    private val pageLabel = Span()
    private val connect = Button("Connect a Facebook Page")
    private val candidates = ComboBox<String>("Choose authorized Page")
    private val selectCandidate = Button("Use selected Page") { connectSelected() }
    private val candidatePages = mutableMapOf<String, DiscoveredFacebookPage>()
    private val publicationProblems = VerticalLayout()
    private val commentProblems = VerticalLayout()
    private val engagementActivity = VerticalLayout()
    private val engagementPageInfo = Span()
    private val engagementPrevious = Button("Previous") { changeEngagementPage(-1) }
    private val engagementNext = Button("Next") { changeEngagementPage(1) }
    private var engagementPage = 0
    private val syncProblems = VerticalLayout()

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")
        sharedContentOffset()
        val form = VerticalLayout()
        form.addClassName("czj-settings-body")
        form.setWidthFull()
        form.maxWidth = "76rem"
        val statistics = Button("View story and Facebook statistics") {
            ui.ifPresent { it.navigate("admin/facebook-statistics") }
        }
        statistics.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        val header = VerticalLayout(
            H2("Facebook Page publishing"),
            Span("Connect a Page, choose when articles are published, and review delivery problems."),
            statistics,
        )
        header.addClassName("czj-settings-header")
        form.add(header)
        val connectionCard = settingsCard("Connect a Page", "Choose an article language and authorize a Facebook Page.")
        if (!properties.oauthConfigured) {
            connectionCard.add(Span("Meta OAuth is not configured on this server. Set the app ID, app secret, Facebook Login for Business configuration ID, redirect URI and encryption key."))
        }
        language.setItems(articleLanguages)
        language.placeholder = "Select a language"
        language.setRenderer(ComponentRenderer { code: String -> languageOption(code) })
        if (articleLanguages.isEmpty()) {
            language.helperText = "Add an article before connecting a Facebook Page."
        }
        connect.isEnabled = properties.oauthConfigured && articleLanguages.isNotEmpty()
        language.addValueChangeListener { connect.isEnabled = properties.oauthConfigured && it.value != null }
        connect.addClickListener {
            try {
                val normalized = ArticleService.normalizeLanguage(language.value ?: return@addClickListener notify("Select an article language"))
                val query = URLEncoder.encode(normalized, Charsets.UTF_8)
                ui.ifPresent { it.page.setLocation("/facebook-pages/oauth/start?language=$query") }
            } catch (ex: IllegalArgumentException) {
                notify(ex.message ?: "Invalid language")
            }
        }
        configured.setRenderer(ComponentRenderer { code: String -> languageOption(code) })
        configuredFlag.isVisible = false
        configured.setPrefixComponent(configuredFlag)
        configured.addValueChangeListener { selection ->
            updateConnectedFlag(selection.value)
            service.all().firstOrNull { it.language == selection.value }?.let(::populate)
        }
        candidates.setItemLabelGenerator { id -> candidatePages[id]?.name ?: id }
        val save = Button("Save Page settings") { saveSettings() }
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        val disconnect = Button("Disconnect Page") { disconnectSelected() }
        val connectActions = HorizontalLayout(language, connect).apply { addClassName("czj-settings-actions") }
        connectionCard.add(connectActions, candidates, selectCandidate)
        val connectedSettingsCard = settingsCard("Connected Page settings", "Publishing starts only after you enable a connected Page and save these settings.")
        pageLabel.addClassName("czj-settings-status")
        val fields = HorizontalLayout(timeZone, monthlyLinkLimit, minimumPostAgeHours, schedulerIntervalMinutes)
        fields.addClassName("czj-settings-fields")
        fields.setWidthFull()
        val settingsActions = HorizontalLayout(save, disconnect).apply { addClassName("czj-settings-actions") }
        connectedSettingsCard.add(configured, pageLabel, enabled, fields, settingsActions)
        form.add(connectionCard, connectedSettingsCard)
        form.add(settingsCard("Publication problems", "Check uncertain results on the Facebook Page before retrying.").apply { add(publicationProblems) })
        form.add(settingsCard("Link comment problems", "Check uncertain comments on the Facebook post before retrying.").apply { add(commentProblems) })
        val engagementNavigation = HorizontalLayout(engagementPrevious, engagementPageInfo, engagementNext)
        engagementNavigation.addClassName("czj-settings-actions")
        form.add(settingsCard("Recent Facebook engagement", "Published posts and their latest reaction checks, newest first.").apply {
            add(Button("Refresh engagement") { refreshEngagementActivity() }, engagementActivity, engagementNavigation)
        })
        form.add(settingsCard("Post update and deletion problems", "Review failed or uncertain changes before retrying.").apply { add(syncProblems) })
        listOf(publicationProblems, commentProblems, engagementActivity, syncProblems).forEach {
            it.addClassName("czj-settings-list")
        }
        val content = sharedPageContent(form)
        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.FACEBOOK_PAGES,
                isAdmin = true,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
        refreshConnections()
        readPendingSelection()
        refreshPublicationProblems()
        refreshCommentProblems()
        refreshEngagementActivity()
        refreshSyncProblems()
    }

    private fun readPendingSelection() {
        val session = VaadinSession.getCurrent().session
        val message = session.getAttribute(FacebookPageOAuthController.MESSAGE_SESSION_KEY) as? String
        session.removeAttribute(FacebookPageOAuthController.MESSAGE_SESSION_KEY)
        if (message != null) notify(message)
        val pending = session.getAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY) as? PendingFacebookPageSelection
        val adminName = SecurityContextHolder.getContext().authentication?.name
        if (pending == null || pending.adminName != adminName || pending.createdAt.isBefore(Instant.now().minusSeconds(600))) {
            session.removeAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY)
            candidates.isVisible = false
            selectCandidate.isVisible = false
            return
        }
        if (pending.language !in articleLanguages) {
            session.removeAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY)
            candidates.isVisible = false
            selectCandidate.isVisible = false
            notify("No articles currently use ${pending.language}; add an article before connecting that language.")
            return
        }
        language.value = pending.language
        candidatePages.clear()
        pending.pages.forEach { candidatePages[it.id] = it }
        candidates.setItems(candidatePages.keys)
        candidates.isVisible = candidatePages.isNotEmpty()
        selectCandidate.isVisible = candidates.isVisible
    }

    private fun connectSelected() {
        val session = VaadinSession.getCurrent().session
        val pending = session.getAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY) as? PendingFacebookPageSelection
        val page = candidatePages[candidates.value]
        val adminName = SecurityContextHolder.getContext().authentication?.name
        if (pending == null || pending.adminName != adminName || pending.createdAt.isBefore(Instant.now().minusSeconds(600)) || page == null) {
            notify("Authorize and select a Page first")
            return
        }
        try {
            val connected = service.connect(pending.language, page)
            session.removeAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY)
            candidatePages.clear()
            candidates.isVisible = false
            selectCandidate.isVisible = false
            refreshConnections(connected.language)
            notify("Connected ${connected.pageName}. Publishing remains disabled until you enable and save it.")
        } catch (_: Exception) {
            notify("Could not connect Page. Check that it is not already assigned to another language.")
        }
    }

    private fun saveSettings() {
        val selected = configured.value ?: return notify("Select a connected language")
        try {
            val saved = service.saveSettings(
                selected,
                FacebookPageSettings(
                    enabled.value, timeZone.value.trim(), monthlyLinkLimit.value ?: 5,
                    minimumPostAgeHours.value ?: 24, schedulerIntervalMinutes.value ?: 60,
                ),
            )
            populate(saved)
            notify("Saved settings for ${saved.pageName}")
        } catch (_: Exception) {
            notify("Could not save Page settings. Check the values and try again.")
        }
    }

    private fun disconnectSelected() {
        val selected = configured.value ?: return notify("Select a connected language")
        service.disconnect(selected)
        refreshConnections()
        notify("Disconnected $selected")
    }

    private fun refreshConnections(selectLanguage: String? = null) {
        val pages = service.all()
        configured.setItems(pages.map { it.language })
        configured.value = pages.firstOrNull { it.language == selectLanguage }?.language ?: pages.firstOrNull()?.language
        if (pages.isEmpty()) pageLabel.text = "No Pages connected"
    }

    private fun languageOption(code: String): HorizontalLayout = HorizontalLayout().apply {
        isPadding = false
        defaultVerticalComponentAlignment = Alignment.CENTER
        languageFlagCache.flagPath(code)?.let { path ->
            add(Image(path, "Flag for $code").apply {
                setWidth("1.5rem")
                setHeight("1rem")
            })
        }
        add(Span(code))
    }

    private fun updateConnectedFlag(code: String?) {
        configuredFlag.removeAll()
        val path = languageFlagCache.flagPath(code)
        configuredFlag.isVisible = path != null
        if (path != null) {
            configuredFlag.add(Image(path, "").apply {
                setWidth("1.5rem")
                setHeight("1rem")
            })
        }
    }

    private fun populate(page: FacebookPageConnection) {
        pageLabel.text = "${page.pageName} (${page.pageId}) — ${if (page.enabled) "enabled" else "disabled"}"
        enabled.value = page.enabled
        timeZone.value = page.timeZone
        monthlyLinkLimit.value = page.monthlyLinkLimit
        minimumPostAgeHours.value = page.minimumPostAgeHours
        schedulerIntervalMinutes.value = page.schedulerIntervalMinutes
    }

    private fun refreshPublicationProblems() {
        publicationProblems.removeAll()
        val queue = publications ?: return
        val problems = queue.recentProblems()
        if (problems.isEmpty()) {
            publicationProblems.add(Span("No publication problems."))
            return
        }
        problems.forEach { job ->
            val row = VerticalLayout()
            row.addClassName("czj-settings-problem")
            row.isPadding = false
            row.add(Span("Article ${job.articleId} · ${job.language} · ${job.status} · ${job.lastError ?: "No Page was enabled when this article was added."}"))
            when (job.status) {
                "UNCONFIGURED" -> row.add(Button("Retry with enabled Page") {
                    if (queue.retryUnconfigured(job.id)) refreshPublicationProblems()
                    else notify("Enable a Page for ${job.language} and save its settings before retrying")
                })
                "FAILED" -> row.add(Button("Retry rejected post") {
                    if (queue.retryFailed(job.id)) refreshPublicationProblems()
                })
                "NEEDS_RECONCILIATION" -> {
                    val postId = TextField("Confirmed Meta Page post ID")
                    postId.placeholder = "${job.pageId ?: "pageId"}_postId"
                    val permalink = TextField("Confirmed Facebook post URL")
                    row.add(postId, permalink, Button("Record existing post") {
                        try {
                            if (queue.confirmAfterManualReview(job.id, postId.value.trim(), permalink.value.trim())) {
                                refreshPublicationProblems()
                            } else notify("Page post ID does not match the connected Page")
                        } catch (ex: Exception) { notify(ex.message ?: "Could not record post") }
                    })
                    val verifiedAbsent = Checkbox("I checked the Page and confirmed no post was published")
                    val retry = Button("Retry after review") {
                        if (verifiedAbsent.value && queue.retryAfterManualReview(job.id)) refreshPublicationProblems()
                    }
                    retry.isEnabled = false
                    verifiedAbsent.addValueChangeListener { retry.isEnabled = it.value }
                    row.add(verifiedAbsent, retry)
                }
            }
            publicationProblems.add(row)
        }
    }

    private fun refreshCommentProblems() {
        commentProblems.removeAll()
        val repository = engagement ?: return
        val problems = repository.recentCommentProblems()
        if (problems.isEmpty()) {
            commentProblems.add(Span("No link comment problems."))
            return
        }
        problems.forEach { problem ->
            val row = VerticalLayout()
            row.addClassName("czj-settings-problem")
            row.isPadding = false
            row.add(Span("Article ${problem.articleId} · ${problem.language} · ${problem.status} · ${problem.lastError ?: "Unknown error"}"))
            if (problem.status == "FAILED") {
                row.add(Button("Retry rejected comment") {
                    val page = service.all().firstOrNull { it.language == problem.language && it.enabled }
                    if (page != null && repository.retryFailedComment(problem.id, page, Instant.now())) refreshCommentProblems()
                    else notify("Enable the Page and check its remaining monthly link budget before retrying")
                })
            } else {
                val commentId = TextField("Confirmed Meta comment ID")
                row.add(commentId, Button("Record existing comment") {
                    try {
                        if (repository.confirmReviewedComment(problem.id, commentId.value.trim())) refreshCommentProblems()
                        else notify("This comment is no longer awaiting review")
                    } catch (ex: IllegalArgumentException) { notify(ex.message ?: "Invalid comment ID") }
                })
                val verifiedAbsent = Checkbox("I checked the Facebook post and confirmed the link comment is absent")
                val retry = Button("Retry after review") {
                    if (verifiedAbsent.value && repository.retryReviewedAbsent(problem.id)) refreshCommentProblems()
                }
                retry.isEnabled = false
                verifiedAbsent.addValueChangeListener { retry.isEnabled = it.value }
                row.add(verifiedAbsent, retry)
            }
            commentProblems.add(row)
        }
    }

    private fun refreshEngagementActivity() {
        engagementActivity.removeAll()
        val repository = engagement ?: return
        val total = repository.publishedActivityCount()
        val lastPage = if (total == 0L) 0 else ((total - 1) / ENGAGEMENT_PAGE_SIZE).toInt()
        engagementPage = engagementPage.coerceAtMost(lastPage)
        val rows = repository.recentActivity(ENGAGEMENT_PAGE_SIZE, engagementPage * ENGAGEMENT_PAGE_SIZE)
        engagementPageInfo.text = "Page ${engagementPage + 1} of ${lastPage + 1} · $total posts"
        engagementPrevious.isEnabled = engagementPage > 0
        engagementNext.isEnabled = engagementPage < lastPage
        if (rows.isEmpty()) {
            engagementActivity.add(Span("No published Facebook posts yet."))
            return
        }
        rows.forEach { item ->
            val reactions = when (item.reactionStatus) {
                "SUCCESS" -> "${item.reactionCount ?: 0} reactions"
                "FAILED" -> "reaction read failed"
                else -> "reactions not measured"
            }
            val row = HorizontalLayout(
                Anchor("/stories/${item.articleId}", "Article ${item.articleId}").apply {
                    element.setAttribute("router-ignore", true)
                },
                Span("· ${item.language} · $reactions · link: ${item.commentStatus ?: "not selected"}"),
            )
            row.addClassName("czj-settings-activity")
            item.postUrl?.let { row.add(Anchor(it, "Facebook post")) }
            engagementActivity.add(row)
        }
    }

    private fun changeEngagementPage(direction: Int) {
        engagementPage = (engagementPage + direction).coerceAtLeast(0)
        refreshEngagementActivity()
    }

    private fun refreshSyncProblems() {
        syncProblems.removeAll()
        val repository = pageSync ?: return
        val problems = repository.recentProblems()
        if (problems.isEmpty()) {
            syncProblems.add(Span("No post update or deletion problems."))
            return
        }
        problems.forEach { problem ->
            val row = VerticalLayout()
            row.addClassName("czj-settings-problem")
            row.isPadding = false
            row.add(Span("Article ${problem.articleId} · ${problem.language} · ${problem.action} · ${problem.status} · ${problem.lastError ?: "Unknown error"}"))
            when (problem.status) {
                "FAILED" -> {
                    row.add(Button("Retry rejected ${problem.action.lowercase()}") {
                        if (repository.retryReviewed(problem.id)) refreshSyncProblems()
                    })
                    if (problem.action == "DELETE") {
                        val gone = Checkbox("I checked Facebook and confirmed this post is already absent")
                        val confirm = Button("Record deleted post") {
                            if (gone.value && repository.confirmReviewed(problem.id)) refreshSyncProblems()
                        }
                        confirm.isEnabled = false
                        gone.addValueChangeListener { confirm.isEnabled = it.value }
                        row.add(gone, confirm)
                    }
                }
                "NEEDS_RECONCILIATION" -> {
                    val completed = Checkbox("I checked Facebook and confirmed this ${problem.action.lowercase()} completed")
                    val confirm = Button("Record completed action") {
                        if (completed.value && repository.confirmReviewed(problem.id)) refreshSyncProblems()
                    }
                    confirm.isEnabled = false
                    completed.addValueChangeListener { confirm.isEnabled = it.value }
                    val notCompleted = Checkbox("I checked Facebook and confirmed this ${problem.action.lowercase()} did not happen")
                    val retry = Button("Retry after review") {
                        if (notCompleted.value && repository.retryReviewed(problem.id)) refreshSyncProblems()
                    }
                    retry.isEnabled = false
                    notCompleted.addValueChangeListener { retry.isEnabled = it.value }
                    row.add(completed, confirm, notCompleted, retry)
                }
                "REPLACEMENT_REQUIRED" -> row.add(Span("The existing Facebook post remains associated with this article. Replace its image manually if needed."))
            }
            syncProblems.add(row)
        }
    }

    private fun notify(message: String) { Notification.show(message, 5000, Notification.Position.MIDDLE) }

    private fun settingsCard(title: String, description: String): VerticalLayout = VerticalLayout(
        H3(title), Span(description).apply { addClassName("czj-settings-description") },
    ).apply { addClassName("czj-settings-card") }
    private companion object {
        const val ENGAGEMENT_PAGE_SIZE = 20
    }
}
