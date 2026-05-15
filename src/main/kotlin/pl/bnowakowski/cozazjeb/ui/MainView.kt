// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.datetimepicker.DateTimePicker
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
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
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import org.slf4j.LoggerFactory
import com.vaadin.flow.server.auth.AnonymousAllowed
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleInput
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.facebookimport.FacebookCandidateApproval
import pl.bnowakowski.cozazjeb.facebookimport.FacebookCandidateApprovalDecision
import pl.bnowakowski.cozazjeb.facebookimport.FacebookCandidateApprovalHandler
import pl.bnowakowski.cozazjeb.facebookimport.FacebookProfileArticleImporter
import pl.bnowakowski.cozazjeb.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.Role
import pl.bnowakowski.cozazjeb.version.AppBuildProperties
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

@Route("")
@AnonymousAllowed
class ArticleListView(
    private val articleRepository: ArticleRepository,
    private val articleService: ArticleService,
    private val facebookProfileArticleImporter: FacebookProfileArticleImporter,
    private val appUserRepository: AppUserRepository,
    private val buildProperties: AppBuildProperties,
) : VerticalLayout() {

    private val pageSizes = listOf(10, 20, 40, 60, 80, 100)
    private var pageSize = 20

    private val totalInfo = Span()
    private val grid = Grid(Article::class.java, false)

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
    private var lastFacebookCreatedId: Long? = null

    private val dataProvider = DataProvider.fromCallbacks<Article>(
        { query ->
            val requestedLimit = query.limit.coerceAtLeast(1)
            val requestedOffset = query.offset
            val page = requestedOffset / requestedLimit

            val sortOrder = query.sortOrders.firstOrNull()
            val sortField = sortOrder?.sorted ?: "createdAt"
            val sortDirection = if (sortOrder?.direction == SortDirection.ASCENDING) "asc" else "desc"

            val articles = articleRepository.findPage(
                page, requestedLimit, sortField, sortDirection,
                languageFilter, publishedFromFilter, publishedToFilter, createdFromFilter, createdToFilter,
            )
            logFacebookPhotoGridFetch(
                page = page,
                requestedLimit = requestedLimit,
                requestedOffset = requestedOffset,
                sortField = sortField,
                sortDirection = sortDirection,
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
            articleRepository.countFiltered(
                languageFilter, publishedFromFilter, publishedToFilter, createdFromFilter, createdToFilter,
            ).toInt()
        },
    )

    init {
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

        // ── Top bar ───────────────────────────────────────────────────────────
        val title = H1("Co za zjeb")

        val rssAnchor = Anchor("/rss", "RSS Feed")
        rssAnchor.setTarget("_blank")
        rssAnchor.element.setAttribute("rel", "noopener noreferrer")
        rssAnchor.element.style.set("color", "var(--lumo-primary-color)")

        val authButton = buildAuthButton()

        val topBar = if (isAuthenticated && authenticatedUser?.status == AppUserStatus.ACTIVE) {
            val addArticleButton = Button("Add Article")
            addArticleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addArticleButton.addClickListener { openAddArticleDialog() }
            if (authenticatedUser?.role == Role.ADMIN) {
                val importFacebookButton = Button("Import Facebook Posts", VaadinIcon.DOWNLOAD.create())
                importFacebookButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                importFacebookButton.addClickListener { triggerFacebookImport() }

                val manageUsersButton = Button("Manage users")
                manageUsersButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
                val manageUsersLink = Anchor("/admin")
                manageUsersLink.add(manageUsersButton)
                HorizontalLayout(title, rssAnchor, addArticleButton, importFacebookButton, manageUsersLink, authButton)
            } else {
                HorizontalLayout(title, rssAnchor, addArticleButton, authButton)
            }
        } else {
            HorizontalLayout(title, rssAnchor, authButton)
        }
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(title)

        // ── Filter controls ───────────────────────────────────────────────────
        val pageSizeSelect = Select<Int>()
        pageSizeSelect.label = "Page size"
        pageSizeSelect.setItems(pageSizes)
        pageSizeSelect.value = pageSize
        pageSizeSelect.addValueChangeListener { event ->
            pageSize = event.value ?: 20
            grid.setPageSize(pageSize)
            refreshData()
        }

        val allLanguagesLabel = "All"
        val languageSelect = Select<String>()
        languageSelect.label = "Language"
        val languages = mutableListOf(allLanguagesLabel)
        languages.addAll(articleRepository.findDistinctLanguages())
        languageSelect.setItems(languages)
        languageSelect.value = allLanguagesLabel
        languageSelect.addValueChangeListener { event ->
            languageFilter = event.value?.takeIf { it != allLanguagesLabel }
            refreshData()
        }

        val controlsRow1 = HorizontalLayout(pageSizeSelect, languageSelect, totalInfo)
        controlsRow1.defaultVerticalComponentAlignment = Alignment.END

        val publishedFromPicker = DateTimePicker("Published from")
        publishedFromPicker.addValueChangeListener { event ->
            val new = event.value
            if (new != null && new.toLocalDate() != event.oldValue?.toLocalDate()) {
                publishedFromPicker.value = new.toLocalDate().atStartOfDay()
                return@addValueChangeListener
            }
            publishedFromFilter = new?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val publishedToPicker = DateTimePicker("Published to")
        publishedToPicker.addValueChangeListener { event ->
            val new = event.value
            if (new != null && new.toLocalDate() != event.oldValue?.toLocalDate()) {
                publishedToPicker.value = new.toLocalDate().atStartOfDay()
                return@addValueChangeListener
            }
            publishedToFilter = new?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val createdFromPicker = DateTimePicker("Created from")
        createdFromPicker.addValueChangeListener { event ->
            val new = event.value
            if (new != null && new.toLocalDate() != event.oldValue?.toLocalDate()) {
                createdFromPicker.value = new.toLocalDate().atStartOfDay()
                return@addValueChangeListener
            }
            createdFromFilter = new?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val createdToPicker = DateTimePicker("Created to")
        createdToPicker.addValueChangeListener { event ->
            val new = event.value
            if (new != null && new.toLocalDate() != event.oldValue?.toLocalDate()) {
                createdToPicker.value = new.toLocalDate().atStartOfDay()
                return@addValueChangeListener
            }
            createdToFilter = new?.toInstant(ZoneOffset.UTC)
            refreshData()
        }

        val clearFiltersButton = Button("Clear filters") {
            languageSelect.value = allLanguagesLabel
            publishedFromPicker.clear()
            publishedToPicker.clear()
            createdFromPicker.clear()
            createdToPicker.clear()
            // filter state vars are reset by each picker's/select's own listener above
        }
        clearFiltersButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)

        val controlsRow2 = HorizontalLayout(publishedFromPicker, publishedToPicker, createdFromPicker, createdToPicker, clearFiltersButton)
        controlsRow2.defaultVerticalComponentAlignment = Alignment.END

        // ── Grid columns (Item 53) ─────────────────────────────────────────────
        // Allow rows to expand for multi-line title cells
        grid.element.style.set("--lumo-size-l", "auto")

        // Thumbnail preview — small image linked to article URL
        grid.addComponentColumn { article ->
            val src = article.thumbnail
            if (!src.isNullOrBlank()) {
                val img = Image(src, article.title ?: "")
                img.maxHeight = "48px"
                img.maxWidth = "80px"
                img.element.style.set("object-fit", "cover")
                val anchor = Anchor(article.url, "")
                anchor.setTarget("_blank")
                anchor.element.setAttribute("rel", "noopener noreferrer")
                anchor.add(img)
                anchor
            } else {
                Span()
            }
        }
            .setHeader("Thumbnail")
            .setAutoWidth(true)
            .setFlexGrow(0)

        // Title as a link — 3-line clamp so more text is visible in a wider column
        grid.addComponentColumn { article ->
            val textDiv = Div()
            textDiv.text = article.title ?: article.url
            textDiv.element.style.set("display", "-webkit-box")
            textDiv.element.style.set("-webkit-line-clamp", "3")
            textDiv.element.style.set("-webkit-box-orient", "vertical")
            textDiv.element.style.set("overflow", "hidden")
            textDiv.element.style.set("white-space", "normal")
            textDiv.element.style.set("word-break", "break-word")
            val anchor = Anchor(article.url, "")
            anchor.setTarget("_blank")
            anchor.element.setAttribute("rel", "noopener noreferrer")
            anchor.add(textDiv)
            anchor
        }
            .setHeader("Title")
            .setKey("title")
            .setSortProperty("title")
            .setSortable(true)
            .setFlexGrow(3)

        grid.addColumn(Article::language)
            .setHeader("Lang")
            .setKey("language")
            .setSortProperty("language")
            .setSortable(true)
            .setWidth("60px")
            .setFlexGrow(0)

        grid.addColumn { article -> article.publishedAt?.let { formatDate(it) } ?: "" }
            .setHeader("Published")
            .setKey("publishedAt")
            .setSortProperty("publishedAt")
            .setSortable(true)
            .setWidth("100px")
            .setFlexGrow(0)

        grid.addColumn { article -> article.createdAt?.let { formatDate(it) } ?: "" }
            .setHeader("Created")
            .setKey("createdAt")
            .setSortProperty("createdAt")
            .setSortable(true)
            .setWidth("100px")
            .setFlexGrow(0)

        if (isAuthenticated) {
            grid.addColumn(Article::id)
                .setHeader("ID")
                .setKey("id")
                .setSortProperty("id")
                .setSortable(true)
                .setWidth("70px")
                .setFlexGrow(0)
        }

        // Creator column — authenticated users only (Item 53)
        if (isAuthenticated) {
            grid.addColumn { article -> creatorCache[article.createdByUserId] ?: "" }
                .setHeader("Creator")
                .setAutoWidth(true)
        }

        // Edit / Delete column — active authenticated users only
        if (isAuthenticated && authenticatedUser?.status == AppUserStatus.ACTIVE) {
            grid.addComponentColumn { article ->
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
                row
            }
                .setAutoWidth(true)
                .setFlexGrow(0)
                .setKey("actions")
        }

        grid.addItemClickListener { event ->
            if (event.column?.key == "actions") return@addItemClickListener
            grid.element.executeJs("window.open($0, '_blank', 'noopener')", event.item.url)
        }

        grid.dataProvider = dataProvider
        grid.setPageSize(pageSize)
        grid.setSizeFull()

        refreshData()
        val versionFooter = Span("v${buildProperties.displayVersion}")
        versionFooter.element.style.set("font-size", "var(--lumo-font-size-xs)")
        versionFooter.element.style.set("color", "var(--lumo-tertiary-text-color)")
        versionFooter.element.style.set("align-self", "center")
        versionFooter.element.style.set("padding-bottom", "var(--lumo-space-xs)")

        add(topBar, controlsRow1, controlsRow2, grid, versionFooter)
        expand(grid)
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
        dialog.add(fields)
        dialog.open()
    }

    private fun triggerFacebookImport() {
        try {
            facebookProfileArticleImporter.startImport(buildFacebookCandidateApprovalHandler())
            showSuccess("Facebook import started")
        } catch (ex: Exception) {
            showError(ex.message ?: "Failed to start Facebook import")
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

        val rows = mutableListOf<Pair<FacebookCandidateApproval, RadioButtonGroup<FacebookCandidateApprovalDecision>>>()
        val content = VerticalLayout()
        content.width = "100%"
        val header = facebookCandidateApprovalGridRow(
            facebookCandidateApprovalHeader("Candidate ID"),
            facebookCandidateApprovalHeader("Candidate URL"),
            facebookCandidateApprovalHeader("Source Facebook post"),
            facebookCandidateApprovalHeader("Language"),
            facebookCandidateApprovalHeader("Decision"),
        )
        content.add(header)
        candidates.forEach { candidate ->
            val candidateId = Span(candidate.candidateId)
            candidateId.element.style.set("font-family", "monospace")
            candidateId.element.style.set("font-size", "var(--lumo-font-size-s)")
            candidateId.element.style.set("overflow-wrap", "anywhere")
            candidateId.element.style.set("word-break", "break-word")
            candidateId.element.style.set("white-space", "normal")

            val url = Anchor(candidate.url, candidate.url)
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
                    setTarget("_blank")
                    element.setAttribute("rel", "noopener noreferrer")
                }
            }
            sourcePost.element.style.set("overflow-wrap", "anywhere")
            sourcePost.element.style.set("word-break", "break-all")
            sourcePost.element.style.set("white-space", "normal")
            sourcePost.element.style.set("line-height", "1.3")
            sourcePost.element.style.set("min-width", "0")

            val language = Span(candidate.language)
            language.element.style.set("text-align", "center")

            val decision = facebookCandidateDecisionGroup()
            decision.value = FacebookCandidateApprovalDecision.ACCEPT

            val row = facebookCandidateApprovalGridRow(candidateId, url, sourcePost, language, decision)
            content.add(row)
            rows += candidate to decision
        }

        val submitButton = Button("Submit") {
            if (approvalFuture.isDone) return@Button
            approvalFuture.complete(
                rows.map { (candidate, decision) ->
                    candidate.copy(decision = decision.value ?: FacebookCandidateApprovalDecision.ACCEPT)
                },
            )
            dialog.close()
        }
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        submitButton.addClickShortcut(Key.ENTER)

        val actions = HorizontalLayout(submitButton)
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
        row.width = "100%"
        row.element.style.set("display", "grid")
        row.element.style.set("grid-template-columns", "6rem minmax(22rem, 1fr) minmax(8rem, 12rem) 4.5rem 10.5rem")
        row.element.style.set("column-gap", "var(--lumo-space-m)")
        row.element.style.set("align-items", "center")
        return row
    }

    private fun facebookCandidateApprovalHeader(text: String): Span {
        val header = Span(text)
        header.element.style.set("font-weight", "600")
        header.element.style.set("font-size", "var(--lumo-font-size-s)")
        header.element.style.set("color", "var(--lumo-secondary-text-color)")
        header.element.style.set("white-space", "normal")
        return header
    }

    private fun facebookCandidateDecisionGroup(): RadioButtonGroup<FacebookCandidateApprovalDecision> {
        val decision = RadioButtonGroup<FacebookCandidateApprovalDecision>()
        decision.setItems(FacebookCandidateApprovalDecision.ACCEPT, FacebookCandidateApprovalDecision.REJECT)
        decision.setItemLabelGenerator {
            when (it) {
                FacebookCandidateApprovalDecision.ACCEPT -> "✓ Accept"
                FacebookCandidateApprovalDecision.REJECT -> "✕ Reject"
            }
        }
        decision.element.style.set("width", "10rem")
        decision.element.style.set("min-width", "10rem")
        decision.element.style.set("--vaadin-radio-button-label-color", "var(--lumo-success-text-color)")
        decision.addValueChangeListener { event ->
            val color = when (event.value) {
                FacebookCandidateApprovalDecision.REJECT -> "var(--lumo-error-text-color)"
                else -> "var(--lumo-success-text-color)"
            }
            decision.element.style.set("--vaadin-radio-button-label-color", color)
        }
        return decision
    }

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
        languageField.width = "28rem"
        languageField.value = article.language

        val quoteField = TextArea("Quote (optional)")
        quoteField.width = "28rem"
        quoteField.maxHeight = "8rem"
        quoteField.value = article.quote ?: ""

        val publishedAtPicker = DateTimePicker("Published at (optional — clear to remove)")
        publishedAtPicker.width = "18rem"
        publishedAtPicker.value = article.publishedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()

        val refreshPublishedAtButton = Button("Refresh published date")
        refreshPublishedAtButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        val publishedAtControls = HorizontalLayout(publishedAtPicker, refreshPublishedAtButton)
        publishedAtControls.defaultVerticalComponentAlignment = Alignment.END
        publishedAtControls.width = "28rem"

        val contentField = TextArea("Cached content (optional — paste full text to override)")
        contentField.width = "28rem"
        contentField.minHeight = "10rem"
        contentField.maxHeight = "20rem"
        contentField.value = articleService.getContent(article.id!!) ?: ""

        val submitButton = Button("Save")
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val dialog = Dialog()
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

        dialog.add(VerticalLayout(languageField, quoteField, publishedAtControls, contentField, actions))
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
        val totalElements = articleRepository.countFiltered(
            languageFilter, publishedFromFilter, publishedToFilter, createdFromFilter, createdToFilter,
        )
        totalInfo.text = "Total articles: $totalElements"
        dataProvider.refreshAll()
    }

    private fun logFacebookPhotoGridFetch(
        page: Int,
        requestedLimit: Int,
        requestedOffset: Int,
        sortField: String,
        sortDirection: String,
        articles: List<Article>,
    ) {
        val facebookRows = articles
            .filter { isFacebookUrl(it.url) }
            .filter { isProblemFacebookRow(it) || it.id == lastFacebookCreatedId }
        if (facebookRows.isEmpty()) return

        LOG.debug(
            "Facebook UI grid fetch state; page={}; requestedLimit={}; requestedOffset={}; sort={}; " +
                "filters={}; lastFacebookCreatedId={}; rows={}",
            page,
            requestedLimit,
            requestedOffset,
            "$sortField,$sortDirection",
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
        private const val MAX_LOGGED_VALUE_CHARS = 300
        private const val LANGUAGE_SUGGESTION_LIMIT = 3
        private const val FACEBOOK_IMPORT_APPROVAL_POLL_INTERVAL_MS = 1_000
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
        private val SHORT_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        private fun formatInstant(instant: Instant): String = DATE_FORMATTER.format(instant)
        private fun formatDate(instant: Instant): String = SHORT_DATE_FORMATTER.format(instant)
    }
}
