// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.datetimepicker.DateTimePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import com.vaadin.flow.server.auth.AnonymousAllowed
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleInput
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Route("")
@AnonymousAllowed
class ArticleListView(
    private val articleRepository: ArticleRepository,
    private val articleService: ArticleService,
    private val appUserRepository: AppUserRepository,
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
            HorizontalLayout(title, rssAnchor, addArticleButton, authButton)
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
            publishedFromFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val publishedToPicker = DateTimePicker("Published to")
        publishedToPicker.addValueChangeListener { event ->
            publishedToFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val createdFromPicker = DateTimePicker("Created from")
        createdFromPicker.addValueChangeListener { event ->
            createdFromFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshData()
        }
        val createdToPicker = DateTimePicker("Created to")
        createdToPicker.addValueChangeListener { event ->
            createdToFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshData()
        }

        val controlsRow2 = HorizontalLayout(publishedFromPicker, publishedToPicker, createdFromPicker, createdToPicker)
        controlsRow2.defaultVerticalComponentAlignment = Alignment.END

        // ── Grid columns (Item 53) ─────────────────────────────────────────────
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

        // Title as a link
        grid.addComponentColumn { article ->
            val anchor = Anchor(article.url, article.title ?: article.url)
            anchor.setTarget("_blank")
            anchor.element.setAttribute("rel", "noopener noreferrer")
            anchor
        }
            .setHeader("Title")
            .setKey("title")
            .setSortable(true)
            .setFlexGrow(1)

        grid.addColumn(Article::language)
            .setHeader("Language")
            .setKey("language")
            .setSortable(true)
            .setAutoWidth(true)

        grid.addColumn { article -> article.publishedAt?.let { formatInstant(it) } ?: "" }
            .setHeader("Published")
            .setKey("publishedAt")
            .setSortable(true)
            .setAutoWidth(true)

        grid.addColumn { article -> article.createdAt?.let { formatInstant(it) } ?: "" }
            .setHeader("Created")
            .setKey("createdAt")
            .setSortable(true)
            .setAutoWidth(true)

        grid.addColumn(Article::id)
            .setHeader("ID")
            .setKey("id")
            .setSortable(true)
            .setAutoWidth(true)

        // Creator column — authenticated users only (Item 53)
        if (isAuthenticated) {
            grid.addColumn { article -> creatorCache[article.createdByUserId] ?: "" }
                .setHeader("Creator")
                .setAutoWidth(true)
        }

        // Edit button column — active authenticated users only
        if (isAuthenticated && authenticatedUser?.status == AppUserStatus.ACTIVE) {
            grid.addComponentColumn { article ->
                val editBtn = Button("Edit")
                editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                editBtn.addClickListener { openEditArticleDialog(article) }
                editBtn
            }.setAutoWidth(true).setFlexGrow(0)
        }

        grid.addItemClickListener { event ->
            grid.element.executeJs("window.open($0, '_blank', 'noopener')", event.item.url)
        }

        grid.dataProvider = dataProvider
        grid.setPageSize(pageSize)
        grid.setSizeFull()

        refreshData()
        add(topBar, controlsRow1, controlsRow2, grid)
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

        val languageField = TextField("Language (BCP-47)")
        languageField.isRequired = true
        languageField.width = "28rem"
        languageField.placeholder = "e.g. en, pl, de"

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
                if (creator == null || creator.status != AppUserStatus.ACTIVE) {
                    showLoginOverlay(dialog)
                    return@addClickListener
                }
                articleService.create(
                    ArticleInput(url = url, language = language, quote = quote, publishedAt = publishedAt),
                    creator.id!!,
                )
                refreshData()
                dialog.close()
                showSuccess("Article added")
            } catch (ex: AccessDeniedException) {
                showLoginOverlay(dialog)
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to add article")
            } finally {
                submitButton.isEnabled = true
            }
        }

        cancelButton.addClickListener { dialog.close() }

        val actions = HorizontalLayout(submitButton, cancelButton)
        actions.defaultVerticalComponentAlignment = Alignment.END

        dialog.add(VerticalLayout(urlField, languageField, quoteField, publishedAtPicker, actions))
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
        publishedAtPicker.width = "28rem"
        publishedAtPicker.value = article.publishedAt?.atOffset(ZoneOffset.UTC)?.toLocalDateTime()

        val submitButton = Button("Save")
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val dialog = Dialog()
        dialog.headerTitle = "Edit Article"

        submitButton.addClickListener {
            val language = languageField.value?.trim().orEmpty()
            val quote = quoteField.value?.trim()?.ifBlank { null }
            // publishedAt key always present: null clears, value sets
            val publishedAt = publishedAtPicker.value?.toInstant(ZoneOffset.UTC)

            if (language.isBlank()) { showError("Language is required"); return@addClickListener }

            submitButton.isEnabled = false
            try {
                val patch: Map<String, Any?> = mapOf(
                    "language" to language,
                    "quote" to quote,
                    "publishedAt" to publishedAt?.toString(),
                )
                articleService.patch(article.id!!, patch)
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

        dialog.add(VerticalLayout(languageField, quoteField, publishedAtPicker, actions))
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
        Notification.show(message, 3000, Notification.Position.TOP_END)
    }

    private fun showError(message: String) {
        val notification = Notification.show(message, 4000, Notification.Position.TOP_END)
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR)
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

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

        private fun formatInstant(instant: Instant): String = DATE_FORMATTER.format(instant)
    }
}
