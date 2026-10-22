// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
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
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPublicationQueue
import pl.bnowakowski.cozadzban.facebookpublish.PendingFacebookPageSelection
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import java.net.URLEncoder
import java.time.Instant

@Route("admin/facebook-pages")
@PageTitle("Facebook Pages")
@RolesAllowed("ADMIN")
class FacebookPageSettingsView(
    private val service: FacebookPageConnectionService,
    private val properties: FacebookPageProperties,
    private val articleRepository: ArticleRepository,
    private val languageFlagCache: LanguageFlagCache,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
    private val publications: FacebookPublicationQueue? = null,
) : VerticalLayout() {
    private val articleLanguages = articleRepository.findDistinctLanguages()
    private val language = ComboBox<String>("Article language")
    private val configured = ComboBox<String>("Connected language")
    private val enabled = Checkbox("Publish new articles automatically")
    private val timeZone = TextField("Page time zone")
    private val monthlyLinkLimit = IntegerField("Links per month")
    private val minimumPostAgeHours = IntegerField("Minimum post age (hours)")
    private val schedulerIntervalMinutes = IntegerField("Scheduler interval (minutes)")
    private val pageLabel = Span()
    private val connect = Button("Connect a Facebook Page")
    private val candidates = ComboBox<String>("Choose authorized Page")
    private val candidatePages = mutableMapOf<String, DiscoveredFacebookPage>()
    private val publicationProblems = VerticalLayout()

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        sharedContentOffset()
        val form = VerticalLayout()
        form.setWidthFull()
        form.maxWidth = "44rem"
        form.add(Span("Facebook Page publishing settings"))
        form.add(Span("Publishing is disabled until a Page is connected and explicitly enabled."))
        if (!properties.oauthConfigured) {
            form.add(Span("Meta OAuth is not configured on this server. Set the app ID, app secret, Facebook Login for Business configuration ID, redirect URI and encryption key."))
        }
        language.setItems(articleLanguages)
        language.placeholder = "Select a language"
        language.setRenderer(ComponentRenderer { code: String ->
            HorizontalLayout().apply {
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
        })
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
        configured.addValueChangeListener { selection ->
            service.all().firstOrNull { it.language == selection.value }?.let(::populate)
        }
        candidates.setItemLabelGenerator { id -> candidatePages[id]?.name ?: id }
        val save = Button("Save Page settings") { saveSettings() }
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        val disconnect = Button("Disconnect Page") { disconnectSelected() }
        val select = Button("Use selected Page") { connectSelected() }
        form.add(
            HorizontalLayout(language, connect),
            candidates, select,
            configured, pageLabel, enabled, timeZone,
            monthlyLinkLimit, minimumPostAgeHours, schedulerIntervalMinutes,
            HorizontalLayout(save, disconnect),
        )
        form.add(Span("Publication problems"), Span("Uncertain results require checking the Facebook Page before retrying."), publicationProblems)
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
            return
        }
        if (pending.language !in articleLanguages) {
            session.removeAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY)
            candidates.isVisible = false
            notify("No articles currently use ${pending.language}; add an article before connecting that language.")
            return
        }
        language.value = pending.language
        candidatePages.clear()
        pending.pages.forEach { candidatePages[it.id] = it }
        candidates.setItems(candidatePages.keys)
        candidates.isVisible = candidatePages.isNotEmpty()
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

    private fun notify(message: String) { Notification.show(message, 5000, Notification.Position.MIDDLE) }
}
