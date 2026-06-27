// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.datetimepicker.DateTimePicker
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import com.vaadin.flow.component.virtuallist.VirtualList
import com.vaadin.flow.shared.Registration
import org.slf4j.LoggerFactory
import com.vaadin.flow.server.auth.AnonymousAllowed
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleInput
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.facebookimport.FacebookCandidateApproval
import pl.bnowakowski.cozadzban.facebookimport.FacebookCandidateApprovalDecision
import pl.bnowakowski.cozadzban.facebookimport.FacebookCandidateApprovalHandler
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalService
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatusFilter
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProgressSnapshot
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportRunStatus
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportTrigger
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import pl.bnowakowski.cozadzban.version.AppBuildProperties
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@Route("")
@AnonymousAllowed
@CssImport("./styles/cozadzban-feed.css")
@CssImport(value = "./styles/cozadzban-dialog-overlay.css", themeFor = "vaadin-dialog-overlay")
@CssImport(value = "./styles/cozadzban-confirm-dialog-overlay.css", themeFor = "vaadin-confirm-dialog-overlay")
class ArticleListView(
    private val articleRepository: ArticleRepository,
    private val articleContentRepository: ArticleContentRepository,
    private val articleService: ArticleService,
    private val facebookImportJobService: FacebookImportJobService,
    private val articleProposalService: FacebookArticleProposalService,
    private val appUserRepository: AppUserRepository,
    private val buildProperties: AppBuildProperties,
    private val languageFlagCache: LanguageFlagCache,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {

    private val feed = VirtualList<Article>()
    private val facebookImportHistoryPanel = Div()
    private val facebookImportProgressPanel = Div()
    private val articleProposalReviewPanel = Div()
    private var facebookImportProgressPollRegistration: Registration? = null
    private var stopFacebookImportButton: Button? = null
    private val shownFacebookImportFailureRunIds = mutableSetOf<String>()

    // Filter state — captured by dataProvider lambdas via `this`
    private var languageFilter: String? = null
    private var publishedFromFilter: Instant? = null
    private var publishedToFilter: Instant? = null
    private var createdFromFilter: Instant? = null
    private var createdToFilter: Instant? = null

    // Auth state — set in init before dataProvider is first invoked
    private var isAuthenticated = false
    private var authenticatedUser: AppUser? = null

    // Creator email cache: populated in batch per page fetch (keyed by createdByUserId)
    private val creatorCache = mutableMapOf<Long, String>()
    private val cacheDerivedTitleIds = mutableMapOf<Long, Boolean>()
    private var lastFacebookCreatedId: Long? = null

    private var dataProvider = createDataProvider()

    private fun createDataProvider() = DataProvider.fromCallbacks<Article>(
        { query ->
            val requestedLimit = query.limit.coerceAtLeast(1)
            val requestedOffset = query.offset
            val page = requestedOffset / requestedLimit
            val language = languageFilter
            val publishedFrom = publishedFromFilter
            val publishedTo = publishedToFilter
            val createdFrom = createdFromFilter
            val createdTo = createdToFilter

            val articles = articleRepository.findPage(
                page, requestedLimit, "publishedAt", "desc",
                language, publishedFrom, publishedTo, createdFrom, createdTo,
            )
            logFacebookPhotoFeedFetch(
                page = page,
                requestedLimit = requestedLimit,
                requestedOffset = requestedOffset,
                articles = articles,
            )

            if (isAuthenticated && articles.isNotEmpty()) {
                val ids = articles.map { it.createdByUserId }.distinct()
                appUserRepository.findAllById(ids).forEach { user ->
                    creatorCache[user.id!!] = user.email
                }
            }

            articles.stream()
        },
        { _ ->
            val language = languageFilter
            val publishedFrom = publishedFromFilter
            val publishedTo = publishedToFilter
            val createdFrom = createdFromFilter
            val createdTo = createdToFilter
            articleRepository.countFiltered(
                language, publishedFrom, publishedTo, createdFrom, createdTo,
            ).toInt()
        },
    )

    init {
        installCozadzbanThemeBootstrap()

        // Resolve auth state once; dataProvider callbacks read these fields lazily
        val auth = SecurityContextHolder.getContext().authentication
        isAuthenticated = auth != null && auth.isAuthenticated && auth.principal != "anonymousUser"
        if (isAuthenticated) {
            val email = when {
                auth?.principal is OAuth2AuthenticatedPrincipal ->
                    (auth.principal as OAuth2AuthenticatedPrincipal).attributes["email"] as? String
                else -> auth?.name
            }
            val normalizedEmail = AllowlistAuthorizationManager.normalizeEmail(email)
            authenticatedUser = normalizedEmail?.let { appUserRepository.findByEmail(it) }
        }

        setSizeFull()

        element.style.set("background", "var(--lumo-contrast-5pct)")
        element.style.set("padding", "0")
        element.style.set("gap", "0")

        val topBar = buildTopBar()
        val feedShell = VerticalLayout()
        feedShell.isPadding = false
        feedShell.isSpacing = false
        feedShell.setWidth("min(100%, 42rem)")
        feedShell.maxWidth = "42rem"
        feedShell.element.style.set("align-self", "center")
        feedShell.element.style.set("box-sizing", "border-box")
        feedShell.element.style.set("margin", "calc(66px + 1.35rem) auto 0")

        configureArticleProposalReviewPanel()
        configureFacebookImportHistoryPanel()
        configureFacebookImportProgressPanel()
        val filterBar = buildFilterBar()

        feed.setRenderer(ComponentRenderer { article -> buildArticleCard(article) })
        feed.setDataProvider(dataProvider)
        feed.setItemAccessibleNameGenerator { article -> visibleArticleTitle(article) ?: article.url }
        feed.width = "100%"
        feed.element.style.set("background", "transparent")
        feed.element.style.set("height", "100%")

        refreshData()
        refreshArticleProposalReviewPanel()
        refreshFacebookImportHistoryPanel()
        refreshFacebookImportProgressPanel()
        configureFacebookImportProgressPolling()
        feedShell.add(articleProposalReviewPanel, facebookImportHistoryPanel, facebookImportProgressPanel, filterBar, feed)
        feedShell.expand(feed)

        val versionBadge = buildVersionBadge()
        add(topBar, feedShell, versionBadge)
        expand(feedShell)
    }

    private fun buildTopBar(): HorizontalLayout {
        val logo = Image("/cozadzban-logo.png", "Co za dzban")
        logo.setWidth("46px")
        logo.setHeight("46px")
        logo.element.style.set("border-radius", "50%")
        logo.element.style.set("object-fit", "cover")
        logo.element.style.set("box-shadow", "0 1px 4px rgba(0,0,0,.26)")

        val brand = Span("Co za dzban")
        brand.element.style.set("font-size", "var(--lumo-font-size-xl)")
        brand.element.style.set("font-weight", "800")
        brand.element.style.set("white-space", "nowrap")

        val titleGroup = HorizontalLayout(logo, brand)
        titleGroup.isPadding = false
        titleGroup.isSpacing = true
        titleGroup.defaultVerticalComponentAlignment = Alignment.CENTER

        val rssButton = Button("RSS", VaadinIcon.RSS.create())
        rssButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
        val rssAnchor = Anchor("/rss")
        rssAnchor.setTarget("_blank")
        rssAnchor.element.setAttribute("rel", "noopener noreferrer")
        rssAnchor.add(rssButton)

        val themeButton = Button(VaadinIcon.ADJUST.create())
        themeButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
        themeButton.element.setAttribute("aria-label", "Toggle dark mode")
        themeButton.element.setAttribute("title", "Toggle dark mode")
        themeButton.addClickListener {
            ui.ifPresent { currentUi ->
                currentUi.page.executeJs(
                    """
                        function applyTheme(mode) {
                            const dark = mode === 'dark';
                            const root = document.documentElement;
                            const body = document.body;
                            if (dark) {
                                root.setAttribute('theme', 'dark');
                                body.setAttribute('theme', 'dark');
                            } else {
                                root.removeAttribute('theme');
                                body.removeAttribute('theme');
                            }
                        }
                        const current = document.documentElement.getAttribute('theme') || '';
                        const nextMode = current.split(/\s+/).includes('dark') ? 'light' : 'dark';
                        localStorage.setItem('cozadzban-theme', nextMode);
                        applyTheme(nextMode);
                    """.trimIndent(),
                )
            }
        }

        val actions = HorizontalLayout(rssAnchor, themeButton)
        actions.isPadding = false
        actions.isSpacing = true
        actions.defaultVerticalComponentAlignment = Alignment.CENTER
        actions.element.style.set("flex-shrink", "0")
        actions.element.style.set("padding-right", "0.25rem")

        if (isAuthenticated && authenticatedUser?.status == AppUserStatus.ACTIVE) {
            val notificationSettingsButton = Button(VaadinIcon.BELL.create())
            notificationSettingsButton.addThemeVariants(
                ButtonVariant.LUMO_SMALL,
                ButtonVariant.LUMO_TERTIARY,
                ButtonVariant.LUMO_ICON,
            )
            notificationSettingsButton.element.setAttribute("aria-label", "Notification settings")
            notificationSettingsButton.element.setAttribute("title", "Notification settings")
            val notificationSettingsLink = Anchor("/notification-settings")
            notificationSettingsLink.add(notificationSettingsButton)
            actions.add(notificationSettingsLink)

            val proposalsButton = Button("Article Proposals", VaadinIcon.LIST.create())
            proposalsButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            proposalsButton.addClickListener { ui.ifPresent { it.navigate("article-proposals") } }
            actions.add(proposalsButton)

            val addArticleButton = Button("Add Article", VaadinIcon.PLUS.create())
            addArticleButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
            addArticleButton.addClickListener { openAddArticleDialog() }
            actions.add(addArticleButton)

            if (authenticatedUser?.role == Role.ADMIN) {
                val stopFacebookImportButton = Button(VaadinIcon.STOP.create())
                this.stopFacebookImportButton = stopFacebookImportButton
                stopFacebookImportButton.addThemeVariants(
                    ButtonVariant.LUMO_SMALL,
                    ButtonVariant.LUMO_TERTIARY,
                    ButtonVariant.LUMO_ERROR,
                    ButtonVariant.LUMO_ICON,
                )
                val availableImportTypes = buildVisibleImportTypes()
                if (availableImportTypes.isNotEmpty()) {
                    availableImportTypes.forEach { importType ->
                        val label = when (importType) {
                            FacebookImportType.APIFY -> "Import Facebook Apify"
                            FacebookImportType.SELENIUM -> "Import Facebook Selenium"
                        }
                        val importFacebookButton = Button(label, VaadinIcon.DOWNLOAD.create())
                        importFacebookButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                        importFacebookButton.element.setAttribute("aria-label", label)
                        importFacebookButton.element.setAttribute("title", label)
                        importFacebookButton.addClickListener {
                            triggerFacebookImport(importType)
                            updateStopFacebookImportButton(stopFacebookImportButton)
                        }
                        actions.add(importFacebookButton)
                    }
                    updateStopFacebookImportButton(stopFacebookImportButton)
                    stopFacebookImportButton.addClickListener {
                        triggerFacebookImportTermination()
                        updateStopFacebookImportButton(stopFacebookImportButton)
                    }
                    actions.add(stopFacebookImportButton)
                } else {
                    val facebookImportUnavailableReason = "No Facebook import type is available"
                    val importFacebookButton = Button("Import Facebook", VaadinIcon.DOWNLOAD.create())
                    importFacebookButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                    importFacebookButton.isEnabled = false
                    importFacebookButton.element.setAttribute(
                        "aria-label",
                        "Import Facebook unavailable: $facebookImportUnavailableReason",
                    )
                    stopFacebookImportButton.isEnabled = false
                    stopFacebookImportButton.element.setAttribute(
                        "aria-label",
                        "Stop Facebook import unavailable: $facebookImportUnavailableReason",
                    )
                    // Wrap in Divs so the Vaadin tooltip overlay still triggers on hover
                    // — disabled buttons swallow pointer events.
                    val importWrapper = Div(importFacebookButton).apply {
                        element.style.set("display", "inline-flex")
                    }
                    val stopWrapper = Div(stopFacebookImportButton).apply {
                        element.style.set("display", "inline-flex")
                    }
                    Tooltip.forComponent(importWrapper).text = facebookImportUnavailableReason
                    Tooltip.forComponent(stopWrapper).text = facebookImportUnavailableReason
                    actions.add(importWrapper, stopWrapper)
                }

                val manageUsersButton = Button(VaadinIcon.USERS.create())
                manageUsersButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
                manageUsersButton.element.setAttribute("aria-label", "Manage users")
                manageUsersButton.element.setAttribute("title", "Manage users")
                val manageUsersLink = Anchor("/admin")
                manageUsersLink.add(manageUsersButton)
                actions.add(manageUsersLink)
            }
        }
        actions.add(buildAuthButton())

        val topBar = HorizontalLayout(titleGroup, actions)
        topBar.addClassName("czj-top-bar")
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(titleGroup)
        topBar.element.style.set("box-sizing", "border-box")
        topBar.element.style.set("height", "66px")
        topBar.element.style.set("padding", "0 1.5rem")
        topBar.element.style.set("background", "var(--czj-card-bg)")
        topBar.element.style.set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
        topBar.element.style.set("position", "fixed")
        topBar.element.style.set("top", "0")
        topBar.element.style.set("left", "0")
        topBar.element.style.set("right", "0")
        topBar.element.style.set("z-index", "1000")
        topBar.element.style.set("box-shadow", "0 2px 10px rgba(15, 23, 42, 0.08)")
        return topBar
    }

    private fun buildFilterBar(): Div {
        val allLanguagesLabel = "All"
        val topLanguages = articleRepository.findTopLanguages(3)
        val allLanguages = articleRepository.findDistinctLanguages()
        val filterBar = Div()
        filterBar.addClassName("czj-filter-bar")
        filterBar.setWidth("100%")
        filterBar.element.style.set("box-sizing", "border-box")
        filterBar.element.style.set("background", "var(--czj-card-bg)")
        filterBar.element.style.set("border", "1px solid var(--lumo-contrast-10pct)")
        filterBar.element.style.set("border-radius", "8px")
        filterBar.element.style.set("padding", "1rem 1.15rem")
        filterBar.element.style.set("margin-bottom", "1.35rem")
        filterBar.element.style.set("display", "flex")
        filterBar.element.style.set("flex-direction", "column")
        filterBar.element.style.set("gap", "1rem")

        val languageButtons = mutableMapOf<String?, Button>()

        fun updateLanguageButtons() {
            languageButtons.forEach { (language, button) ->
                button.removeThemeVariants(ButtonVariant.LUMO_PRIMARY)
                styleFilterChip(button, active = language == languageFilter)
            }
        }

        fun languageChip(label: String, language: String?): Button {
            val button = Button(label)
            languageFlagIcon(language)?.let { flag ->
                button.icon = flag
                button.addClassName("czj-language-filter-chip")
            }
            button.addThemeVariants(ButtonVariant.LUMO_SMALL)
            styleFilterChip(button, active = false)
            button.addClickListener {
                languageFilter = language
                updateLanguageButtons()
                refreshData()
            }
            languageButtons[language] = button
            return button
        }

        val languageRow = HorizontalLayout()
        languageRow.isPadding = false
        languageRow.isSpacing = true
        languageRow.defaultVerticalComponentAlignment = Alignment.CENTER
        languageRow.element.style.set("gap", "0.85rem")
        languageRow.add(filterLabel(VaadinIcon.GLOBE, "Language"))
        languageRow.add(languageChip(allLanguagesLabel, null))
        topLanguages.forEach { language -> languageRow.add(languageChip(language, language)) }
        if (allLanguages.size > topLanguages.size) {
            val more = Button(VaadinIcon.ELLIPSIS_DOTS_H.create())
            more.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON)
            styleFilterChip(more, active = false)
            more.element.setAttribute("aria-label", "More languages")
            more.element.setAttribute("title", "More languages")
            more.addClickListener { openLanguageDialog(allLanguages) { updateLanguageButtons() } }
            languageRow.add(more)
        }
        updateLanguageButtons()

        val publishedFromPicker = DateTimePicker("From")
        publishedFromPicker.setAriaLabel("Published from")
        publishedFromPicker.addClassName("czj-date-range-picker")
        publishedFromPicker.width = "10.5rem"
        val publishedToPicker = DateTimePicker("To")
        publishedToPicker.setAriaLabel("Published to")
        publishedToPicker.addClassName("czj-date-range-picker")
        publishedToPicker.width = "10.5rem"

        val dateFields = HorizontalLayout(publishedFromPicker, publishedToPicker)
        dateFields.addClassName("czj-date-range-fields")
        dateFields.isPadding = false
        dateFields.isSpacing = true
        dateFields.defaultVerticalComponentAlignment = Alignment.END
        dateFields.element.style.set("gap", "0.85rem")
        dateFields.element.style.set("flex-wrap", "wrap")
        dateFields.isVisible = false

        val publishedDateButton = Button("Published date", VaadinIcon.CALENDAR.create())
        publishedDateButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
        publishedDateButton.addClassName("czj-date-filter-button")
        publishedDateButton.element.style.set("font-weight", "700")
        publishedDateButton.element.style.set("color", "var(--lumo-secondary-text-color)")
        publishedDateButton.addClickListener { dateFields.isVisible = !dateFields.isVisible }

        val applyDatesButton = Button("Apply") {
            publishedFromFilter = publishedFromPicker.value?.toInstant(ZoneOffset.UTC)
            publishedToFilter = publishedToPicker.value?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        applyDatesButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
        applyDatesButton.addClassName("czj-date-range-action")

        val clearDatesButton = Button("Clear") {
            publishedFromPicker.clear()
            publishedToPicker.clear()
            publishedFromFilter = null
            publishedToFilter = null
            refreshData()
        }
        clearDatesButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
        clearDatesButton.addClassName("czj-date-range-action")
        dateFields.add(clearDatesButton, applyDatesButton)

        val publishedDateRow = HorizontalLayout(publishedDateButton, dateFields)
        publishedDateRow.isPadding = false
        publishedDateRow.isSpacing = true
        publishedDateRow.defaultVerticalComponentAlignment = Alignment.CENTER
        publishedDateRow.element.style.set("gap", "1.15rem")
        publishedDateRow.element.style.set("flex-wrap", "wrap")

        filterBar.add(languageRow, publishedDateRow)
        return filterBar
    }

    private fun configureFacebookImportHistoryPanel() {
        facebookImportHistoryPanel.addClassName("czj-facebook-import-history")
        facebookImportHistoryPanel.setWidth("100%")
        facebookImportHistoryPanel.isVisible = false
    }

    private fun configureFacebookImportProgressPanel() {
        facebookImportProgressPanel.addClassName("czj-facebook-import-progress")
        facebookImportProgressPanel.setWidth("100%")
        facebookImportProgressPanel.isVisible = false
    }

    private fun configureArticleProposalReviewPanel() {
        articleProposalReviewPanel.addClassName("czj-facebook-import-progress")
        articleProposalReviewPanel.setWidth("100%")
        articleProposalReviewPanel.isVisible = false
    }

    private fun configureFacebookImportProgressPolling() {
        if (!canViewFacebookImportProgress() && !canReviewArticleProposals()) return
        val currentUi = UI.getCurrent() ?: return
        currentUi.pollInterval = FACEBOOK_IMPORT_STATUS_POLL_INTERVAL_MS
        facebookImportProgressPollRegistration = currentUi.addPollListener {
            refreshArticleProposalReviewPanel()
            refreshFacebookImportHistoryPanel()
            refreshFacebookImportProgressPanel()
            stopFacebookImportButton?.let { updateStopFacebookImportButton(it) }
        }
        addDetachListener {
            facebookImportProgressPollRegistration?.remove()
            facebookImportProgressPollRegistration = null
        }
    }

    private fun refreshFacebookImportProgressPanel() {
        val progress = if (canViewFacebookImportProgress()) {
            facebookImportJobService.currentProgress()
        } else {
            null
        }
        if (progress == null) {
            facebookImportProgressPanel.isVisible = false
            facebookImportProgressPanel.removeAll()
            return
        }

        facebookImportProgressPanel.isVisible = true
        facebookImportProgressPanel.removeAll()
        facebookImportProgressPanel.add(buildFacebookImportProgressContent(progress))
        maybeShowFacebookImportFailure(progress)
    }

    private fun refreshFacebookImportHistoryPanel() {
        if (!canViewFacebookImportProgress()) {
            facebookImportHistoryPanel.isVisible = false
            facebookImportHistoryPanel.removeAll()
            return
        }

        val importTypes = buildVisibleImportTypes()
        if (importTypes.isEmpty()) {
            facebookImportHistoryPanel.isVisible = false
            facebookImportHistoryPanel.removeAll()
            return
        }

        facebookImportHistoryPanel.isVisible = true
        facebookImportHistoryPanel.removeAll()
        importTypes.forEach { importType ->
            facebookImportHistoryPanel.add(buildFacebookImportRunHistoryContent(importType))
        }
    }

    private fun buildFacebookImportRunHistoryContent(importType: FacebookImportType): Div {
        val content = Div()
        content.addClassName("czj-facebook-import-progress-content")

        val title = Span("${facebookImportTypeLabel(importType)} last runs")
        title.addClassName("czj-facebook-import-progress-title")

        val icon = VaadinIcon.CLOCK.create()
        icon.setSize("1.15rem")
        icon.color = "var(--lumo-primary-color)"

        val header = HorizontalLayout(icon, title)
        header.addClassName("czj-facebook-import-progress-header")
        header.isPadding = false
        header.isSpacing = true
        header.defaultVerticalComponentAlignment = Alignment.CENTER

        val metrics = Div()
        metrics.addClassName("czj-facebook-import-history-row")
        metrics.add(
            facebookImportHistoryMetric(
                "Manual",
                articleProposalService.latestRunTimestamp(importType, FacebookImportTrigger.MANUAL),
            ),
            facebookImportHistoryMetric("Automatic", articleProposalService.latestAutomaticRunTimestamp(importType)),
        )

        content.add(header)
        content.add(metrics)
        return content
    }

    private fun canViewFacebookImportProgress(): Boolean =
        isAuthenticated &&
            authenticatedUser?.status == AppUserStatus.ACTIVE &&
            authenticatedUser?.role == Role.ADMIN

    private fun canReviewArticleProposals(): Boolean =
        isAuthenticated &&
            authenticatedUser?.status == AppUserStatus.ACTIVE &&
            authenticatedUser?.role in setOf(Role.USER, Role.ADMIN)

    private fun refreshArticleProposalReviewPanel() {
        val pendingCount = if (canReviewArticleProposals()) {
            articleProposalService.count(FacebookArticleProposalStatusFilter.PENDING)
        } else {
            0L
        }
        if (pendingCount <= 0L) {
            articleProposalReviewPanel.isVisible = false
            articleProposalReviewPanel.removeAll()
            return
        }

        articleProposalReviewPanel.isVisible = true
        articleProposalReviewPanel.removeAll()
        articleProposalReviewPanel.add(buildArticleProposalReviewContent(pendingCount))
    }

    private fun buildArticleProposalReviewContent(pendingCount: Long): Div {
        val content = Div()
        content.addClassName("czj-facebook-import-progress-content")
        content.element.style.set("cursor", "pointer")
        content.element.setAttribute("role", "link")
        content.element.setAttribute("tabindex", "0")
        content.element.setAttribute("aria-label", "$pendingCount article proposals waiting for review")
        content.addClickListener { ui.ifPresent { currentUi -> currentUi.navigate("article-proposals") } }
        content.element.executeJs(
            """
                this.addEventListener("keydown", event => {
                    if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        this.click();
                    }
                });
            """.trimIndent(),
        )

        val icon = VaadinIcon.LIST.create()
        icon.setSize("1.15rem")
        icon.color = "var(--lumo-primary-color)"

        val title = Span("Article proposals waiting for review")
        title.addClassName("czj-facebook-import-progress-title")

        val count = Span("$pendingCount ${if (pendingCount == 1L) "proposal" else "proposals"}")
        count.addClassName("czj-facebook-import-progress-phase")

        val header = HorizontalLayout(icon, title, count)
        header.addClassName("czj-facebook-import-progress-header")
        header.isPadding = false
        header.isSpacing = true
        header.defaultVerticalComponentAlignment = Alignment.CENTER

        content.add(header)
        return content
    }

    private fun buildFacebookImportProgressContent(progress: FacebookImportProgressSnapshot): Div {
        val content = Div()
        content.addClassName("czj-facebook-import-progress-content")

        val icon = if (progress.status == FacebookImportRunStatus.FAILED) {
            VaadinIcon.WARNING.create()
        } else {
            VaadinIcon.DOWNLOAD.create()
        }
        icon.setSize("1.15rem")
        icon.color = if (progress.status == FacebookImportRunStatus.FAILED) {
            "var(--lumo-error-color)"
        } else {
            "var(--lumo-primary-color)"
        }

        val title = Span(
            when (progress.status) {
                FacebookImportRunStatus.FAILED -> "Facebook import failed"
                FacebookImportRunStatus.TERMINATED -> "Facebook import stopped"
                FacebookImportRunStatus.FINISHED -> "Facebook import finished"
                FacebookImportRunStatus.RUNNING -> "Facebook import is running"
            },
        )
        title.addClassName("czj-facebook-import-progress-title")

        val phase = Span(progress.phase?.takeIf { it.isNotBlank() } ?: "Running")
        phase.addClassName("czj-facebook-import-progress-phase")

        val header = HorizontalLayout(icon, title, phase)
        header.addClassName("czj-facebook-import-progress-header")
        header.isPadding = false
        header.isSpacing = true
        header.defaultVerticalComponentAlignment = Alignment.CENTER

        val metrics = Div()
        metrics.addClassName("czj-facebook-import-progress-metrics")
        metrics.add(
            facebookImportMetric("Running", formatDuration(Duration.between(progress.startedAt, Instant.now()))),
            facebookImportMetric("Pass", formatPass(progress)),
            facebookImportMetric("Matched posts", progress.matchedPostCount.toString()),
            facebookImportMetric("Already imported", progress.skippedExistingCount.toString()),
            facebookImportMetric("Sent", progress.submittedCount.toString()),
            facebookImportMetric("Phase", formatPhase(progress)),
            facebookImportMetric("Last updated", formatStatusInstant(progress.lastUpdatedAt)),
        )
        if (progress.failedCount > 0) {
            metrics.add(facebookImportMetric("Failed", progress.failedCount.toString()))
        }

        val detail = progress.detail?.takeIf { it.isNotBlank() }?.let {
            Span(it).apply { addClassName("czj-facebook-import-progress-detail") }
        }

        content.add(header)
        detail?.let { content.add(it) }
        content.add(metrics)
        return content
    }

    private fun maybeShowFacebookImportFailure(progress: FacebookImportProgressSnapshot) {
        if (progress.status != FacebookImportRunStatus.FAILED) return
        if (!shownFacebookImportFailureRunIds.add(progress.importRunId)) return
        val dialog = Dialog()
        dialog.headerTitle = "Facebook import failed"
        dialog.width = "min(42rem, 92vw)"

        val message = Span(progress.detail?.takeIf { it.isNotBlank() } ?: "The import failed. Check worker logs for details.")
        message.addClassName("czj-dialog-help-text")

        val closeButton = Button("Close") { dialog.close() }
        closeButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val content = VerticalLayout(message, closeButton)
        content.addClassName("czj-dialog-content")
        content.defaultHorizontalComponentAlignment = Alignment.START
        dialog.add(content)
        dialog.open()
    }

    private fun facebookImportMetric(labelText: String, valueText: String): Div {
        val label = Span(labelText)
        label.addClassName("czj-facebook-import-progress-label")
        val value = Span(valueText)
        value.addClassName("czj-facebook-import-progress-value")

        val metric = Div(label, value)
        metric.addClassName("czj-facebook-import-progress-metric")
        return metric
    }

    private fun facebookImportHistoryMetric(labelText: String, timestamp: Instant?): Div {
        val metric = facebookImportMetric(labelText, timestamp?.let(::formatRelativeInstant) ?: "never")
        timestamp?.let {
            val exactTimestamp = formatStatusInstant(it)
            metric.element.setProperty("title", exactTimestamp)
            metric.element.setAttribute("aria-label", "$labelText last run $exactTimestamp")
        }
        return metric
    }

    private fun formatRelativeInstant(instant: Instant): String {
        val duration = Duration.between(instant, Instant.now())
        val seconds = duration.seconds
        if (seconds < 0) return "just now"

        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        val weeks = days / 7
        val months = days / 30
        val years = days / 365

        return when {
            seconds < 45 -> "just now"
            seconds < 90 -> "1 minute ago"
            minutes < 45 -> "$minutes minutes ago"
            minutes < 90 -> "1 hour ago"
            hours < 22 -> "$hours hours ago"
            hours < 36 -> "yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "1 week ago"
            days < 30 -> "$weeks weeks ago"
            days < 60 -> "1 month ago"
            days < 365 -> "$months months ago"
            days < 730 -> "1 year ago"
            else -> "$years years ago"
        }
    }

    private fun formatPass(progress: FacebookImportProgressSnapshot): String =
        if (progress.passCount > 0) {
            "${progress.passIndex.coerceIn(1, progress.passCount)} of ${progress.passCount}"
        } else {
            "Preparing"
        }

    private fun formatPhase(progress: FacebookImportProgressSnapshot): String =
        if (progress.phaseCount > 0) {
            "${progress.phaseIndex.coerceIn(1, progress.phaseCount)} of ${progress.phaseCount}"
        } else {
            "Preparing"
        }

    private fun formatDuration(duration: Duration): String {
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun languageFlagIcon(language: String?): Span? {
        val path = languageFlagCache.flagPath(language) ?: return null
        val flag = Span()
        flag.addClassName("czj-language-flag")
        flag.element.style.set("background-image", "url('$path')")
        flag.element.style.set("background-size", "cover")
        flag.element.style.set("background-position", "center")
        flag.element.setAttribute("aria-hidden", "true")
        return flag
    }

    private fun styleFilterChip(button: Button, active: Boolean) {
        button.addClassName("czj-filter-chip")
        button.element.classList.set("czj-filter-chip-active", active)
    }

    private fun openLanguageDialog(languages: List<String>, afterSelection: () -> Unit) {
        val dialog = Dialog()
        dialog.headerTitle = "Languages"
        val content = VerticalLayout()
        content.isPadding = false
        content.width = "16rem"
        languages.forEach { language ->
            val button = Button(language) {
                languageFilter = language
                afterSelection()
                refreshData()
                dialog.close()
            }
            button.width = "100%"
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            content.add(button)
        }
        dialog.add(content)
        dialog.open()
    }

    private fun filterLabel(icon: VaadinIcon, text: String): HorizontalLayout {
        val iconComponent = icon.create()
        iconComponent.setSize("1.1rem")
        iconComponent.color = "var(--lumo-secondary-text-color)"
        iconComponent.element.style.set("margin-right", "0.35rem")
        val label = Span(text)
        label.element.style.set("font-size", "var(--lumo-font-size-s)")
        label.element.style.set("font-weight", "700")
        label.element.style.set("color", "var(--lumo-secondary-text-color)")
        val row = HorizontalLayout(iconComponent, label)
        row.isPadding = false
        row.isSpacing = true
        row.defaultVerticalComponentAlignment = Alignment.CENTER
        row.element.style.set("min-width", "8.75rem")
        row.element.style.set("gap", "0.35rem")
        return row
    }

    private fun buildArticleCard(article: Article): Div {
        val frame = Div()
        frame.addClassName("czj-article-frame")
        frame.width = "100%"
        frame.element.style.set("box-sizing", "border-box")
        frame.element.style.set("padding-bottom", "1.75rem")

        val card = Div()
        card.addClassName("czj-article-card")
        card.width = "100%"
        card.element.style.set("box-sizing", "border-box")
        card.element.style.set("background", "var(--lumo-base-color)")
        card.element.style.set("border", "1px solid var(--czj-card-border)")
        card.element.style.set("border-radius", "8px")
        card.element.style.set("overflow", "hidden")
        card.element.style.set("margin-bottom", "0")
        card.element.style.set("box-shadow", "var(--czj-card-shadow)")

        val link = Anchor(article.url)
        link.setTarget("_blank")
        link.element.setAttribute("rel", "noopener noreferrer")
        link.element.style.set("display", "block")
        link.element.style.set("color", "inherit")
        link.element.style.set("text-decoration", "none")

        val content = Div()
        article.thumbnail?.trim()?.takeIf { it.isNotBlank() }?.let { thumbnail ->
            val image = Image(thumbnail, article.title ?: "")
            image.width = "100%"
            image.element.style.set("display", "block")
            image.element.style.set("aspect-ratio", "1 / 1")
            image.element.style.set("object-fit", "cover")
            image.element.executeJs("this.addEventListener('error', () => this.style.display = 'none')")
            content.add(image)
        }

        val body = Div()
        body.addClassName("czj-article-body")
        body.element.style.set("display", "flex")
        body.element.style.set("flex-direction", "column")
        body.element.style.set("gap", "1.2rem")
        body.element.style.set("padding", "1.35rem 1.55rem 1.6rem")
        body.add(buildArticleMeta(article))
        visibleArticleTitle(article)?.let { body.add(buildArticleTitle(it)) }
        article.quote?.trim()?.takeIf { it.isNotBlank() }?.let { body.add(buildQuote(it)) }
        article.lead?.trim()?.takeIf { it.isNotBlank() }?.let { body.add(buildLead(it)) }
        content.add(body)
        link.add(content)
        card.add(link)

        if (isAuthenticated && authenticatedUser?.status == AppUserStatus.ACTIVE) {
            card.add(buildArticleActions(article))
        }
        frame.add(card)
        return frame
    }

    private fun buildArticleMeta(article: Article): HorizontalLayout {
        val row = HorizontalLayout(buildSourceIcon(article))
        row.addClassName("czj-article-meta")

        val source = Span(sourceName(article.url))
        source.element.style.set("font-size", "var(--lumo-font-size-m)")
        source.element.style.set("font-weight", "700")

        val dot = Span("•")
        dot.element.style.set("color", "var(--lumo-secondary-text-color)")

        val date = Span(article.publishedAt?.let { formatDate(it) } ?: "")
        date.element.style.set("font-size", "var(--lumo-font-size-xs)")
        date.element.style.set("color", "var(--lumo-secondary-text-color)")

        row.add(source, dot, date)
        article.sourceImportType
            ?.let(::facebookImportTypeLabel)
            ?.let { label ->
                val importDot = Span("•")
                importDot.element.style.set("color", "var(--lumo-secondary-text-color)")
                row.add(importDot, importSourceBadge(label))
            }
        row.isPadding = false
        row.isSpacing = true
        row.defaultVerticalComponentAlignment = Alignment.CENTER
        row.element.style.set("gap", "0.55rem")
        return row
    }

    private fun buildSourceIcon(article: Article): Div {
        val wrapper = Div()
        wrapper.addClassName("czj-source-icon")

        val fallback = Span(sourceName(article.url).firstOrNull()?.uppercaseChar()?.toString() ?: "?")
        fallback.addClassName("czj-source-icon-fallback")

        val faviconUrl = article.favicon?.takeIf { it.isNotBlank() } ?: fallbackFavicon(article.url)
        val favicon = Image(faviconUrl, "")
        favicon.addClassName("czj-source-favicon")
        favicon.element.executeJs("this.addEventListener('error', () => this.style.display = 'none')")

        wrapper.add(fallback, favicon)
        return wrapper
    }

    private fun visibleArticleTitle(article: Article): String? {
        val title = article.title?.trim()?.takeIf { it.isNotBlank() } ?: return article.url
        val articleId = article.id ?: return title
        val isCacheDerived = cacheDerivedTitleIds.getOrPut(articleId) {
            articleContentRepository.findById(articleId)
                .map { isCacheDerivedTitle(title, it.content) }
                .orElse(false)
        }
        return title.takeUnless { isCacheDerived }
    }

    private fun isCacheDerivedTitle(title: String, content: String): Boolean {
        val normalizedTitle = normalizeArticleTextForComparison(title)
        val normalizedContent = normalizeArticleTextForComparison(content)
        if (normalizedTitle == normalizedContent) return true

        if (!normalizedTitle.endsWith("...") && !normalizedTitle.endsWith("…")) return false
        return cacheDerivedTitlePrefixes(normalizedTitle).any { titlePrefix ->
            titlePrefix.length >= CACHE_DERIVED_TITLE_PREFIX_MIN_CHARS &&
                titlePrefix.length < normalizedContent.length &&
                normalizedContent.contains(titlePrefix)
        }
    }

    private fun normalizeArticleTextForComparison(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun cacheDerivedTitlePrefixes(title: String): Set<String> {
        val titlePrefix = title
            .removeSuffix("...")
            .removeSuffix("…")
            .trimEnd()
        return setOf(
            titlePrefix,
            titlePrefix.replace(LEADING_DECORATIVE_TEXT_PATTERN, ""),
        ).filter { it.isNotBlank() }.toSet()
    }

    private fun buildArticleTitle(text: String): Div {
        val title = Div()
        title.addClassName("czj-article-title")
        title.text = text
        title.element.style.set("font-size", "1.45rem")
        title.element.style.set("font-weight", "800")
        title.element.style.set("line-height", "1.28")
        title.element.style.set("overflow-wrap", "anywhere")
        return title
    }

    private fun buildQuote(text: String): Div {
        val quote = Div()
        quote.addClassName("czj-article-quote")
        quote.element.style.set("width", "92%")
        quote.element.style.set("box-sizing", "border-box")
        quote.element.style.set("margin", "0")
        quote.element.style.set("padding", "0.75rem 1rem")
        quote.element.style.set("border-left", "3px solid var(--lumo-primary-color)")
        quote.element.style.set("border-top", "1px solid var(--czj-quote-border, var(--lumo-primary-color))")
        quote.element.style.set("border-right", "1px solid var(--czj-quote-border, var(--lumo-primary-color))")
        quote.element.style.set("border-bottom", "1px solid var(--czj-quote-border, var(--lumo-primary-color))")
        quote.element.style.set("border-radius", "0 7px 7px 0")
        quote.element.style.set("background", "var(--czj-quote-bg)")

        val mark = Span("“")
        mark.element.style.set("font-size", "1.8rem")
        mark.element.style.set("line-height", "0")
        mark.element.style.set("font-weight", "800")
        mark.element.style.set("color", "var(--lumo-primary-color)")
        mark.element.style.set("margin-right", "var(--lumo-space-xs)")

        val quoteText = Span(text)
        quoteText.element.style.set("font-size", "var(--lumo-font-size-s)")
        quoteText.element.style.set("line-height", "1.5")
        quote.add(mark, quoteText)
        return quote
    }

    private fun buildLead(text: String): Div {
        val lead = Div()
        lead.addClassName("czj-article-lead")
        val displayText = frontendLeadText(text)
        if (displayText.length < text.trim().length) {
            lead.addClassName("czj-article-lead-truncated")
        }
        lead.text = displayText
        lead.element.style.set("font-size", "var(--lumo-font-size-m)")
        lead.element.style.set("line-height", "1.65")
        lead.element.style.set("color", "var(--lumo-secondary-text-color)")
        lead.element.style.set("overflow-wrap", "anywhere")
        return lead
    }

    private fun frontendLeadText(text: String): String {
        val normalized = normalizeArticleTextForComparison(text)
        if (normalized.length <= MAX_FRONTEND_LEAD_CHARS) return normalized

        val wordBoundary = normalized.lastIndexOf(' ', MAX_FRONTEND_LEAD_CHARS)
            .takeIf { it >= MIN_FRONTEND_LEAD_WORD_BOUNDARY_CHARS }
            ?: MAX_FRONTEND_LEAD_CHARS
        return normalized.take(wordBoundary).trimEnd() + "..."
    }

    private fun buildArticleActions(article: Article): HorizontalLayout {
        val editBtn = Button("Edit")
        editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
        editBtn.addClickListener { openEditArticleDialog(article) }

        val deleteBtn = Button("Delete")
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        deleteBtn.addClickListener { confirmDeleteArticle(article) }

        val row = HorizontalLayout(editBtn, deleteBtn)
        row.isPadding = false
        row.isSpacing = true
        row.defaultVerticalComponentAlignment = Alignment.CENTER
        row.element.style.set("border-top", "1px solid var(--lumo-contrast-10pct)")
        row.element.style.set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
        return row
    }

    private fun buildVersionBadge(): Div {
        val versionFooter = Span("v${buildProperties.displayVersion}")
        versionFooter.element.style.set("font-size", "10px")
        versionFooter.element.style.set("color", "var(--lumo-tertiary-text-color)")
        versionFooter.element.style.set("line-height", "1")

        val badge = Div(versionFooter)
        badge.addClassName("czj-version-badge")
        badge.element.style.set("position", "fixed")
        badge.element.style.set("left", "8px")
        badge.element.style.set("bottom", "4px")
        badge.element.style.set("z-index", "900")
        badge.element.style.set("padding", "0")
        badge.element.style.set("background", "transparent")
        badge.element.style.set("border", "0")
        badge.element.style.set("box-shadow", "none")
        badge.element.style.set("opacity", "0.58")
        return badge
    }

    private fun sourceName(url: String): String =
        runCatching { URI(url).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.ifBlank { null }
            ?: url

    private fun fallbackFavicon(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.takeIf { it == "http" || it == "https" } ?: return ""
        val host = uri.host ?: return ""
        return "$scheme://$host/favicon.ico"
    }

    private fun buildAuthButton(): Button {
        return if (isAuthenticated) {
            val button = Button("Logout")
            button.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            button.addClickListener { logoutAndRedirect() }
            button
        } else {
            val button = Button("Login")
            button.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            button.addClickListener { ui.ifPresent { it.page.setLocation("/auth/login") } }
            button
        }
    }

    private fun openAddArticleDialog() {
        val urlField = TextField("URL")
        urlField.isRequired = true
        urlField.width = "28rem"
        urlField.placeholder = "https://..."

        val languageLabel = Span("Language (BCP-47)")
        languageLabel.element.style.set("font-size", "var(--lumo-font-size-s)")
        languageLabel.element.style.set("font-weight", "500")
        languageLabel.element.style.set("color", "var(--lumo-secondary-text-color)")

        val languageField = TextField()
        languageField.isRequired = true
        languageField.width = "28rem"
        languageField.placeholder = "e.g. en, pl, de"
        languageField.setAriaLabel("Language (BCP-47)")

        val languageSuggestions = HorizontalLayout()
        languageSuggestions.isSpacing = true
        languageSuggestions.defaultVerticalComponentAlignment = Alignment.CENTER
        articleRepository.findTopLanguages(LANGUAGE_SUGGESTION_LIMIT).forEach { language ->
            val suggestionButton = Button(language)
            suggestionButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
            suggestionButton.addClickListener { languageField.value = language }
            languageSuggestions.add(suggestionButton)
        }
        val languageFieldGroup = if (languageSuggestions.componentCount > 0) {
            VerticalLayout(languageLabel, languageSuggestions, languageField)
        } else {
            VerticalLayout(languageLabel, languageField)
        }
        languageFieldGroup.isPadding = false
        languageFieldGroup.isSpacing = false
        languageFieldGroup.width = "28rem"

        val quoteField = TextArea("Quote (optional)")
        quoteField.width = "28rem"
        quoteField.maxHeight = "8rem"

        val publishedAtPicker = DateTimePicker("Published at (optional)")
        publishedAtPicker.width = "28rem"

        val submitButton = Button("Submit")
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val dialog = Dialog()
        dialog.headerTitle = "Add Article"
        dialog.addOpenedChangeListener { event ->
            if (event.isOpened) {
                urlField.focus()
            }
        }

        submitButton.addClickListener {
            val url = urlField.value?.trim().orEmpty()
            val language = languageField.value?.trim().orEmpty()
            val quote = quoteField.value?.trim()?.ifBlank { null }
            val publishedAt = publishedAtPicker.value?.toInstant(ZoneOffset.UTC)

            if (url.isBlank()) { showError("URL is required"); return@addClickListener }
            if (!url.matches(Regex("^https?://.+"))) { showError("URL must start with http:// or https://"); return@addClickListener }
            if (language.isBlank()) { showError("Language is required"); return@addClickListener }

            submitButton.isEnabled = false
            try {
                val creator = authenticatedUser
                logFacebookPhotoUiCreateAttempt(url, creator, language, quote, publishedAt)
                if (creator == null || creator.status != AppUserStatus.ACTIVE) {
                    showLoginOverlay(dialog)
                    return@addClickListener
                }
                val created = articleService.create(
                    ArticleInput(url = url, language = language, quote = quote, publishedAt = publishedAt),
                    creator.id!!,
                )
                logFacebookPhotoUiCreateResult(url, created)
                logFacebookPhotoUiPostCreateRecoveryDecision(url, created)
                if (isFacebookUrl(created.url)) {
                    lastFacebookCreatedId = created.id
                    logFacebookPhotoUiCreatedDbState(created.id, "after-create-before-refresh")
                }
                refreshData()
                logFacebookPhotoUiCreatedDbState(created.id, "after-refresh-request")
                dialog.close()
                showSuccess("Article added")
            } catch (ex: AccessDeniedException) {
                logFacebookPhotoUiCreateException(url, ex)
                showLoginOverlay(dialog)
            } catch (ex: Exception) {
                logFacebookPhotoUiCreateException(url, ex)
                showError(ex.message ?: "Failed to add article")
            } finally {
                submitButton.isEnabled = true
            }
        }
        submitButton.addClickShortcut(Key.ENTER).listenOn(dialog)

        cancelButton.addClickListener { dialog.close() }

        val actions = HorizontalLayout(submitButton, cancelButton)
        actions.defaultVerticalComponentAlignment = Alignment.END

        val fields = VerticalLayout(urlField, languageFieldGroup, quoteField, publishedAtPicker, actions)
        fields.addClassName("czj-dialog-content")
        dialog.add(fields)
        dialog.open()
    }

    private fun buildVisibleImportTypes(): List<FacebookImportType> {
        val visibleImportTypes = facebookImportJobService.availableImportTypes().toMutableList()
        if (facebookImportProperties.apify.enabled && FacebookImportType.APIFY !in visibleImportTypes) {
            visibleImportTypes.add(FacebookImportType.APIFY)
        }
        return visibleImportTypes
    }

    private fun triggerFacebookImport(importType: FacebookImportType) {
        try {
            facebookImportJobService.startImport(importType)
            refreshFacebookImportProgressPanel()
            showSuccess("Facebook import started")
        } catch (ex: Exception) {
            showError(ex.message ?: "Failed to start Facebook import")
        }
    }

    private fun facebookImportTypeLabel(importType: FacebookImportType): String =
        when (importType) {
            FacebookImportType.APIFY -> "Apify import"
            FacebookImportType.SELENIUM -> "Selenium import"
        }

    private fun facebookImportTypeLabel(importType: String): String? =
        runCatching { FacebookImportType.valueOf(importType) }
            .getOrNull()
            ?.let(::facebookImportTypeLabel)

    private fun importSourceBadge(label: String): Span =
        Span(label).apply {
            addClassName("czj-import-source-badge")
            element.setAttribute("title", label)
        }

    private fun triggerFacebookImportTermination() {
        try {
            facebookImportJobService.terminateImport()
            refreshFacebookImportProgressPanel()
            showSuccess("Facebook import stop requested")
        } catch (ex: Exception) {
            showError(ex.message ?: "Failed to stop Facebook import")
        }
    }

    private fun updateStopFacebookImportButton(button: Button) {
        val running = facebookImportJobService.isImportRunning()
        if (running) {
            button.isEnabled = true
            button.element.setAttribute("title", "Stop Facebook import")
            button.element.setAttribute("aria-label", "Stop Facebook import")
        } else {
            button.isEnabled = false
            button.element.setAttribute("title", "No Facebook import job is currently running")
            button.element.setAttribute(
                "aria-label",
                "Stop Facebook import unavailable: no Facebook import job is currently running",
            )
        }
    }

    private fun buildFacebookCandidateApprovalHandler(): FacebookCandidateApprovalHandler {
        val currentUi = ui.orElse(null) ?: UI.getCurrent() ?: throw
            IllegalStateException("Facebook import approval requires an active admin UI")
        currentUi.pollInterval = FACEBOOK_IMPORT_APPROVAL_POLL_INTERVAL_MS
        return FacebookCandidateApprovalHandler { candidates ->
            if (candidates.isEmpty()) return@FacebookCandidateApprovalHandler emptyList()
            val approvalFuture = CompletableFuture<List<FacebookCandidateApproval>>()
            currentUi.access {
                buildFacebookCandidateApprovalDialog(candidates, approvalFuture).open()
            }
            awaitFacebookCandidateApprovals(approvalFuture)
        }
    }

    private fun awaitFacebookCandidateApprovals(
        approvalFuture: CompletableFuture<List<FacebookCandidateApproval>>,
    ): List<FacebookCandidateApproval> =
        try {
            approvalFuture.get()
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        } catch (ex: ExecutionException) {
            throw ex.cause?.let { cause ->
                if (cause is RuntimeException) cause else IllegalStateException(cause)
            } ?: ex
        }

    private fun buildFacebookCandidateApprovalDialog(
        candidates: List<FacebookCandidateApproval>,
        approvalFuture: CompletableFuture<List<FacebookCandidateApproval>>,
    ): Dialog {
        val dialog = Dialog()
        dialog.headerTitle = "Approve Facebook import candidates"
        dialog.isCloseOnEsc = false
        dialog.isCloseOnOutsideClick = false
        dialog.setWidth("80vw")

        val rows = mutableListOf<FacebookCandidateApprovalRow>()
        val content = VerticalLayout()
        content.addClassNames("czj-dialog-content", "czj-facebook-approval-dialog")
        content.width = "100%"
        val header = facebookCandidateApprovalGridRow(
            facebookCandidateApprovalHeader("Candidate ID"),
            facebookCandidateApprovalHeader("Candidate URL"),
            facebookCandidateApprovalHeader("Source Facebook post"),
            facebookCandidateApprovalHeader("Language"),
            facebookCandidateApprovalHeader("Decision"),
        )
        header.addClassName("czj-facebook-approval-header-row")
        content.add(header)
        candidates.forEach { candidate ->
            val candidateId = Span(candidate.candidateId)
            candidateId.addClassName("czj-facebook-approval-id")
            candidateId.element.style.set("font-family", "monospace")
            candidateId.element.style.set("font-size", "var(--lumo-font-size-s)")
            candidateId.element.style.set("overflow-wrap", "anywhere")
            candidateId.element.style.set("word-break", "break-word")
            candidateId.element.style.set("white-space", "normal")

            val url = Anchor(candidate.url, candidate.url)
            url.addClassName("czj-facebook-approval-link")
            url.setTarget("_blank")
            url.element.setAttribute("rel", "noopener noreferrer")
            url.element.style.set("overflow-wrap", "anywhere")
            url.element.style.set("word-break", "break-all")
            url.element.style.set("white-space", "normal")
            url.element.style.set("line-height", "1.3")
            url.element.style.set("min-width", "0")

            val sourcePostUrl = candidate.sourcePostUrl
            val sourcePost: com.vaadin.flow.component.Component = if (sourcePostUrl.isNullOrBlank()) {
                Span("—")
            } else {
                Anchor(sourcePostUrl, sourcePostUrl).apply {
                    addClassName("czj-facebook-approval-link")
                    setTarget("_blank")
                    element.setAttribute("rel", "noopener noreferrer")
                }
            }
            sourcePost.element.style.set("overflow-wrap", "anywhere")
            sourcePost.element.style.set("word-break", "break-all")
            sourcePost.element.style.set("white-space", "normal")
            sourcePost.element.style.set("line-height", "1.3")
            sourcePost.element.style.set("min-width", "0")

            val language = TextField()
            language.value = candidate.language
            language.placeholder = "pl"
            language.setWidth("6rem")
            language.addClassName("czj-facebook-approval-language")
            language.element.style.set("min-width", "0")

            val decision = facebookCandidateDecisionGroup()
            decision.value = FacebookCandidateApprovalDecision.ACCEPT

            val row = facebookCandidateApprovalGridRow(candidateId, url, sourcePost, language, decision)
            row.addClassName("czj-facebook-approval-row")
            content.add(row)
            rows += FacebookCandidateApprovalRow(candidate, language, decision)
        }

        val submitButton = Button("Submit") {
            if (approvalFuture.isDone) return@Button
            approvalFuture.complete(
                rows.map { row ->
                    row.candidate.copy(
                        language = row.language.value.trim().ifBlank { row.candidate.language },
                        decision = row.decision.value ?: FacebookCandidateApprovalDecision.ACCEPT,
                    )
                },
            )
            dialog.close()
        }
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        submitButton.addClickShortcut(Key.ENTER)

        val actions = HorizontalLayout(submitButton)
        actions.addClassName("czj-facebook-approval-actions")
        actions.defaultVerticalComponentAlignment = Alignment.CENTER
        content.add(actions)
        dialog.add(content)
        return dialog
    }

    private fun facebookCandidateApprovalGridRow(
        candidateId: com.vaadin.flow.component.Component,
        url: com.vaadin.flow.component.Component,
        sourcePost: com.vaadin.flow.component.Component,
        language: com.vaadin.flow.component.Component,
        decision: com.vaadin.flow.component.Component,
    ): Div {
        val row = Div(candidateId, url, sourcePost, language, decision)
        row.addClassName("czj-facebook-approval-grid-row")
        row.width = "100%"
        row.element.style.set("display", "grid")
        row.element.style.set("grid-template-columns", "6rem minmax(22rem, 1fr) minmax(8rem, 12rem) 6rem 10.5rem")
        row.element.style.set("column-gap", "var(--lumo-space-m)")
        row.element.style.set("row-gap", "var(--lumo-space-s)")
        row.element.style.set("align-items", "center")
        return row
    }

    private fun facebookCandidateApprovalHeader(text: String): Span {
        val header = Span(text)
        header.addClassName("czj-facebook-approval-header")
        header.element.style.set("font-weight", "600")
        header.element.style.set("font-size", "var(--lumo-font-size-s)")
        header.element.style.set("color", "var(--lumo-secondary-text-color)")
        header.element.style.set("white-space", "normal")
        return header
    }

    private fun facebookCandidateDecisionGroup(): RadioButtonGroup<FacebookCandidateApprovalDecision> {
        val decision = RadioButtonGroup<FacebookCandidateApprovalDecision>()
        decision.setItems(FacebookCandidateApprovalDecision.ACCEPT, FacebookCandidateApprovalDecision.REJECT)
        decision.setRenderer(ComponentRenderer { item ->
            Span(
                when (item) {
                    FacebookCandidateApprovalDecision.ACCEPT -> "✓ Accept"
                    FacebookCandidateApprovalDecision.REJECT -> "✕ Reject"
                },
            ).apply {
                addClassName(
                    when (item) {
                        FacebookCandidateApprovalDecision.ACCEPT -> "czj-facebook-approval-accept"
                        FacebookCandidateApprovalDecision.REJECT -> "czj-facebook-approval-reject"
                    },
                )
            }
        })
        decision.setItemLabelGenerator {
            when (it) {
                FacebookCandidateApprovalDecision.ACCEPT -> "✓ Accept"
                FacebookCandidateApprovalDecision.REJECT -> "✕ Reject"
            }
        }
        decision.element.style.set("width", "10rem")
        decision.element.style.set("min-width", "10rem")
        decision.addClassName("czj-facebook-approval-decision")
        return decision
    }

    private data class FacebookCandidateApprovalRow(
        val candidate: FacebookCandidateApproval,
        val language: TextField,
        val decision: RadioButtonGroup<FacebookCandidateApprovalDecision>,
    )

    private fun confirmDeleteArticle(article: Article) {
        val dialog = ConfirmDialog()
        dialog.setHeader("Delete article")
        dialog.setText("Delete \"${article.title ?: article.url}\"? This cannot be undone.")
        dialog.setConfirmText("Delete")
        dialog.setConfirmButtonTheme("error primary")
        dialog.setCancelable(true)
        dialog.addConfirmListener {
            logFacebookPhotoUiDeleteAttempt(article)
            try {
                articleService.delete(article.id!!)
                logFacebookPhotoUiDeleteResult(article)
                refreshData()
            } catch (ex: Exception) {
                logFacebookPhotoUiDeleteException(article, ex)
                throw ex
            }
        }
        dialog.open()
    }

    private fun openEditArticleDialog(article: Article) {
        val languageField = TextField("Language (BCP-47)")
        languageField.width = "100%"
        languageField.value = article.language

        val quoteField = TextArea("Quote (optional)")
        quoteField.width = "100%"
        quoteField.minHeight = "7rem"
        quoteField.maxHeight = "12rem"
        quoteField.value = article.quote ?: ""

        val publishedAtPicker = DateTimePicker("Published at (optional — clear to remove)")
        publishedAtPicker.width = "22rem"
        publishedAtPicker.value = article.publishedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()

        val refreshPublishedAtButton = Button("Refresh published date")
        refreshPublishedAtButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        val publishedAtControls = HorizontalLayout(publishedAtPicker, refreshPublishedAtButton)
        publishedAtControls.defaultVerticalComponentAlignment = Alignment.END
        publishedAtControls.width = "100%"
        publishedAtControls.addClassName("czj-edit-article-published-controls")

        val contentField = TextArea("Cached content (optional — paste full text to override)")
        contentField.width = "100%"
        contentField.minHeight = "24rem"
        contentField.maxHeight = "42rem"
        contentField.value = articleService.getContent(article.id!!) ?: ""

        val submitButton = Button("Save")
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val dialog = Dialog()
        dialog.addClassName("czj-edit-article-dialog")
        dialog.width = "min(64rem, calc(100vw - 2rem))"
        dialog.maxHeight = "min(88vh, 56rem)"
        dialog.headerTitle = "Edit Article"

        refreshPublishedAtButton.addClickListener {
            refreshPublishedAtButton.isEnabled = false
            try {
                val updated = articleService.refreshPublishedAt(article.id!!)
                publishedAtPicker.value = updated.publishedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()
                refreshData()
                showSuccess("Published date refreshed")
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to refresh published date")
            } finally {
                refreshPublishedAtButton.isEnabled = true
            }
        }

        submitButton.addClickListener {
            val language = languageField.value?.trim().orEmpty()
            val quote = quoteField.value?.trim()?.ifBlank { null }
            // publishedAt key always present: null clears, value sets
            val publishedAt = publishedAtPicker.value?.toInstant(ZoneOffset.UTC)
            val content = contentField.value?.trim()

            if (language.isBlank()) { showError("Language is required"); return@addClickListener }

            submitButton.isEnabled = false
            try {
                val patch: Map<String, Any?> = mapOf(
                    "language" to language,
                    "quote" to quote,
                    "publishedAt" to publishedAt?.toString(),
                    "content" to content,
                )
                logFacebookPhotoUiPatchAttempt(article, patch)
                val updated = articleService.patch(article.id!!, patch)
                logFacebookPhotoUiPatchResult(updated, patch)
                refreshData()
                dialog.close()
                showSuccess("Article updated")
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to update article")
            } finally {
                submitButton.isEnabled = true
            }
        }

        cancelButton.addClickListener { dialog.close() }

        val actions = HorizontalLayout(submitButton, cancelButton)
        actions.defaultVerticalComponentAlignment = Alignment.END
        actions.addClassName("czj-edit-article-actions")

        val fields = VerticalLayout(languageField, quoteField, publishedAtControls, contentField, actions)
        fields.addClassNames("czj-dialog-content", "czj-edit-article-content")
        contentField.addClassName("czj-cache-content-field")
        dialog.add(fields)
        dialog.open()
    }

    private fun showLoginOverlay(dialog: Dialog) {
        dialog.removeAll()
        dialog.headerTitle = "Session Expired"
        val loginButton = Button("Login with Google")
        loginButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        loginButton.addClickListener { ui.ifPresent { it.page.setLocation("/auth/login") } }
        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        cancelButton.addClickListener { dialog.close() }
        val actions = HorizontalLayout(loginButton, cancelButton)
        actions.defaultVerticalComponentAlignment = Alignment.END
        dialog.add(VerticalLayout(Span("Your session has expired. Please log in again."), actions))
    }

    private fun showSuccess(message: String) {
        showNotification(message, 3000)
    }

    private fun showError(message: String) {
        showNotification(message, 4000, NotificationVariant.LUMO_ERROR)
    }

    private fun showNotification(message: String, duration: Int, variant: NotificationVariant? = null) {
        val notification = Notification()
        notification.duration = duration
        notification.position = Notification.Position.TOP_END
        if (variant != null) {
            notification.addThemeVariants(variant)
        }

        val closeButton = Button(VaadinIcon.CLOSE_SMALL.create())
        closeButton.element.setAttribute("aria-label", "Close notification")
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ICON)
        closeButton.addClickListener { notification.close() }

        val messageText = Span(message)
        val layout = HorizontalLayout(messageText, closeButton)
        layout.defaultVerticalComponentAlignment = Alignment.CENTER
        layout.expand(messageText)
        layout.width = "100%"
        notification.add(layout)
        notification.open()
    }

    private fun logoutAndRedirect() {
        VaadinServletRequest.getCurrent().httpServletRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        ui.ifPresent { it.page.setLocation("/") }
    }

    private fun refreshData() {
        dataProvider = createDataProvider()
        feed.setDataProvider(dataProvider)
        feed.dataCommunicator.reset()
        dataProvider.refreshAll()
        feed.element.executeJs("this.scrollToIndex && this.scrollToIndex(0)")
    }

    private fun logFacebookPhotoFeedFetch(
        page: Int,
        requestedLimit: Int,
        requestedOffset: Int,
        articles: List<Article>,
    ) {
        val facebookRows = articles
            .filter { isFacebookUrl(it.url) }
            .filter { isProblemFacebookRow(it) || it.id == lastFacebookCreatedId }
        if (facebookRows.isEmpty()) return

        LOG.debug(
            "Facebook UI feed fetch state; page={}; requestedLimit={}; requestedOffset={}; sort={}; " +
                "filters={}; lastFacebookCreatedId={}; rows={}",
            page,
            requestedLimit,
            requestedOffset,
            "publishedAt,desc",
            currentFilterDiagnostic(),
            lastFacebookCreatedId,
            facebookRows.joinToString(" | ") { facebookRowDiagnostic(it) },
        )
    }

    private fun logFacebookPhotoUiCreatedDbState(articleId: Long?, phase: String) {
        articleId ?: return
        val article = runCatching { articleRepository.findById(articleId).orElse(null) }.getOrNull() ?: return
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI created DB state {}; articleId={}; row={}",
            phase,
            articleId,
            facebookRowDiagnostic(article),
        )
    }

    private fun logFacebookPhotoUiCreateAttempt(
        url: String,
        creator: AppUser?,
        language: String,
        quote: String?,
        publishedAt: Instant?,
    ) {
        if (!isFacebookUrl(url)) return

        LOG.debug(
            "Facebook UI create attempt; url='{}'; kind={}; language='{}'; quote={}; publishedAt={}; creator={}; " +
                "request={}; contentInputAvailable=false; browserPostTextAvailable=false; postCreatePatchScheduled=false; " +
                "reason=ui-add-dialog-submits-url-language-quote-publishedAt-only",
            url,
            facebookUrlKind(url),
            language,
            valueDiagnostic(quote),
            publishedAt,
            creatorDiagnostic(creator),
            currentRequestDiagnostic(),
        )
    }

    private fun logFacebookPhotoUiCreateResult(inputUrl: String, article: Article) {
        if (!isFacebookUrl(inputUrl) && !isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI create result; inputUrl='{}'; kind={}; articleId={}; savedUrl='{}'; title={}; thumbnail={}; " +
                "lead={}; publishedAt={}; contentInputAvailable=false; postCreatePatchScheduled=false; " +
                "requiresManualContentPatchForRealTitle={}",
            inputUrl,
            facebookUrlKind(article.url),
            article.id,
            article.url,
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
            article.title == "Facebook photo" && article.thumbnail == null && article.lead == null && article.publishedAt == null,
        )
    }

    private fun logFacebookPhotoUiPostCreateRecoveryDecision(inputUrl: String, article: Article) {
        if (!isFacebookUrl(inputUrl) && !isFacebookUrl(article.url)) return

        val degraded = isProblemFacebookRow(article)
        if (!degraded) return

        LOG.warn(
            "Facebook UI post-create degraded decision; inputUrl='{}'; kind={}; articleId={}; savedUrl='{}'; " +
                "degraded=true; automaticPatchAvailable=false; patchAttempted=false; " +
                "problemReason={}; reason=add-dialog-has-no-content-thumbnail-or-browser-post-text-source; " +
                "availableSubmitFields=url,language,quote,publishedAt; missingSubmitFields=content,thumbnail,facebookPhotoImage,publishedAtFromFacebook; " +
                "manualPatchEndpoint='{}'; row={}; request={}",
            inputUrl,
            facebookUrlKind(article.url),
            article.id,
            article.url,
            facebookProblemReason(article),
            "/api/articles/${article.id}",
            facebookRowDiagnostic(article),
            currentRequestDiagnostic(),
        )
    }

    private fun logFacebookPhotoUiCreateException(inputUrl: String, ex: Exception) {
        if (!isFacebookUrl(inputUrl)) return

        val canonicalUrl = runCatching { ArticleService.canonicalizeUrl(inputUrl) }.getOrNull()
        val existing = canonicalUrl?.let { articleRepository.findByUrl(it) }

        LOG.warn(
            "Facebook UI create exception; inputUrl='{}'; kind={}; canonicalUrl='{}'; exception={}: {}; existingArticle={}; request={}",
            inputUrl,
            facebookUrlKind(canonicalUrl ?: inputUrl),
            canonicalUrl ?: "canonicalization-failed",
            ex.javaClass.simpleName,
            ex.message,
            existing?.let { facebookRowDiagnostic(it) } ?: "absent",
            currentRequestDiagnostic(),
        )
    }

    private fun logFacebookPhotoUiPatchAttempt(article: Article, patch: Map<String, Any?>) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI patch attempt; articleId={}; url='{}'; kind={}; existingTitle={}; patchKeys={}; content={}; " +
                "publishedAtPatch={}; request={}",
            article.id,
            article.url,
            facebookUrlKind(article.url),
            valueDiagnostic(article.title),
            patch.keys.sorted().joinToString(","),
            valueDiagnostic(patch["content"] as? String),
            patch["publishedAt"],
            currentRequestDiagnostic(),
        )
    }

    private fun logFacebookPhotoUiPatchResult(article: Article, patch: Map<String, Any?>) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI patch result; articleId={}; url='{}'; kind={}; savedTitle={}; thumbnail={}; lead={}; " +
                "publishedAt={}; contentPatch={}",
            article.id,
            article.url,
            facebookUrlKind(article.url),
            valueDiagnostic(article.title),
            valueDiagnostic(article.thumbnail),
            valueDiagnostic(article.lead),
            article.publishedAt,
            valueDiagnostic(patch["content"] as? String),
        )
    }

    private fun logFacebookPhotoUiDeleteAttempt(article: Article) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI delete attempt; row={}; request={}",
            facebookRowDiagnostic(article),
            currentRequestDiagnostic(),
        )
    }

    private fun logFacebookPhotoUiDeleteResult(article: Article) {
        if (!isFacebookUrl(article.url)) return

        LOG.debug(
            "Facebook UI delete result; articleId={}; url='{}'; kind={}; existsAfterDelete={}",
            article.id,
            article.url,
            facebookUrlKind(article.url),
            article.id?.let { articleRepository.existsById(it) },
        )
    }

    private fun logFacebookPhotoUiDeleteException(article: Article, ex: Exception) {
        if (!isFacebookUrl(article.url)) return

        LOG.warn(
            "Facebook UI delete exception; row={}; exception={}: {}; request={}",
            facebookRowDiagnostic(article),
            ex.javaClass.simpleName,
            ex.message,
            currentRequestDiagnostic(),
        )
    }

    private fun creatorDiagnostic(creator: AppUser?): String =
        creator?.let { "id=${it.id},email='${it.email}',status=${it.status},role=${it.role}" } ?: "absent"

    private fun facebookRowDiagnostic(article: Article): String =
        "id=${article.id},kind=${facebookUrlKind(article.url)},url='${article.url}',title=${valueDiagnostic(article.title)}," +
            "thumbnail=${valueDiagnostic(article.thumbnail)},lead=${valueDiagnostic(article.lead)}," +
            "publishedAt=${article.publishedAt},contentCache=${valueDiagnostic(article.id?.let { articleService.getContent(it) })}," +
            "problemReason=${facebookProblemReason(article)}"

    private fun isProblemFacebookRow(article: Article): Boolean =
        facebookProblemReason(article) != "none"

    private fun facebookProblemReason(article: Article): String {
        val reasons = mutableListOf<String>()
        if (isGenericFacebookTitle(article.title)) reasons += "generic-title"
        if (isFacebookLoginAccessTitle(article.title)) reasons += "login-or-access-title"
        if (article.thumbnail.isNullOrBlank()) reasons += "missing-thumbnail"
        if (article.publishedAt == null) reasons += "missing-publishedAt"
        if (article.lead.isNullOrBlank()) reasons += "missing-lead"
        if (article.id?.let { articleService.getContent(it) }.isNullOrBlank()) reasons += "missing-content-cache"
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none"
    }

    private fun currentFilterDiagnostic(): String =
        "language=${languageFilter ?: "absent"},publishedFrom=${publishedFromFilter ?: "absent"}," +
            "publishedTo=${publishedToFilter ?: "absent"},createdFrom=${createdFromFilter ?: "absent"}," +
            "createdTo=${createdToFilter ?: "absent"}"

    private fun currentRequestDiagnostic(): String {
        val request = VaadinServletRequest.getCurrent()?.httpServletRequest ?: return "absent"
        return "method=${request.method},uri=${request.requestURI},query=${request.queryString ?: "absent"}"
    }

    private fun isFacebookUrl(url: String?): Boolean {
        if (url == null) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false

        return host == "facebook.com" || host.endsWith(".facebook.com")
    }

    private fun facebookUrlKind(url: String?): String {
        if (!isFacebookUrl(url)) return "non-facebook"
        val path = runCatching { URI(url ?: return "invalid").path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            path.contains("/photo/") || path.contains("/photo.php") -> "photo"
            path.contains("/posts/") || path.contains("/permalink.php") || path.contains("/story.php") -> "post"
            path.contains("/videos/") || path.contains("/watch/") || path.contains("/reel/") -> "video-or-reel"
            path.contains("/share/") || path.contains("/shares/") -> "share"
            else -> "facebook-other"
        }
    }

    private fun isGenericFacebookTitle(title: String?): Boolean =
        title == "Facebook" ||
            title == "Facebook photo" ||
            title == "Facebook post" ||
            title == "Facebook share" ||
            title == "Facebook reel" ||
            title?.startsWith("Facebook post by ") == true

    private fun isFacebookLoginAccessTitle(title: String?): Boolean {
        val normalized = title?.replace(LOG_WHITESPACE_PATTERN, " ")?.trim().orEmpty()
        return normalized.contains("zaloguj", ignoreCase = true) ||
            normalized.contains("zarejestruj", ignoreCase = true) ||
            normalized.contains("log in", ignoreCase = true) ||
            normalized.contains("sign up", ignoreCase = true)
    }

    private fun valueDiagnostic(value: String?): String =
        value
            ?.replace(LOG_WHITESPACE_PATTERN, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "present(len=${it.length},excerpt='${it.take(MAX_LOGGED_VALUE_CHARS)}')" }
            ?: "absent"

    companion object {
        private val LOG = LoggerFactory.getLogger(ArticleListView::class.java)
        private val LOG_WHITESPACE_PATTERN = Regex("""\s+""")
        private val LEADING_DECORATIVE_TEXT_PATTERN = Regex("""^[^\p{L}\p{N}]+""")
        private const val MAX_LOGGED_VALUE_CHARS = 300
        private const val LANGUAGE_SUGGESTION_LIMIT = 3
        private const val CACHE_DERIVED_TITLE_PREFIX_MIN_CHARS = 40
        private const val MAX_FRONTEND_LEAD_CHARS = 1_200
        private const val MIN_FRONTEND_LEAD_WORD_BOUNDARY_CHARS = 900
        private const val FACEBOOK_IMPORT_APPROVAL_POLL_INTERVAL_MS = 1_000
        private const val FACEBOOK_IMPORT_STATUS_POLL_INTERVAL_MS = 7_000
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
        private val STATUS_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
        private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        private fun formatInstant(instant: Instant): String = DATE_FORMATTER.format(instant)
        private fun formatStatusInstant(instant: Instant): String = STATUS_DATE_FORMATTER.format(instant)
        private fun formatDate(instant: Instant): String = SHORT_DATE_FORMATTER.format(instant)
    }
}
