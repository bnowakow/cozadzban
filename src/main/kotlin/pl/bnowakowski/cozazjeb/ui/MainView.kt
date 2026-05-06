// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.grid.Grid
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
import pl.bnowakowski.cozazjeb.user.AppUserRepository

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

    private val dataProvider = DataProvider.fromCallbacks<Article>(
        { query ->
            val requestedLimit = query.limit.coerceAtLeast(1)
            val requestedOffset = query.offset
            val page = requestedOffset / requestedLimit

            val sortOrder = query.sortOrders.firstOrNull()
            val sortField = sortOrder?.sorted ?: "createdAt"
            val sortDirection = if (sortOrder?.direction == SortDirection.ASCENDING) "asc" else "desc"

            articleRepository.findPage(page, requestedLimit, sortField, sortDirection).stream()
        },
        { _ -> articleRepository.count().toInt() },
    )

    init {
        setSizeFull()

        val title = H1("Co za zjeb")

        val authButton = buildAuthButton()
        val isAuthenticated = authButton.text == "Logout"

        val topBar = if (isAuthenticated) {
            val addArticleButton = Button("Add Article")
            addArticleButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addArticleButton.addClickListener { openAddArticleDialog() }
            HorizontalLayout(title, addArticleButton, authButton)
        } else {
            HorizontalLayout(title, authButton)
        }
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(title)

        val pageSizeSelect = Select<Int>()
        pageSizeSelect.label = "Page size"
        pageSizeSelect.setItems(pageSizes)
        pageSizeSelect.value = pageSize
        pageSizeSelect.addValueChangeListener { event ->
            pageSize = event.value ?: 20
            grid.setPageSize(pageSize)
            refreshData()
        }

        val controls = HorizontalLayout(pageSizeSelect, totalInfo)
        controls.defaultVerticalComponentAlignment = Alignment.END

        grid.addColumn(Article::id)
            .setHeader("ID")
            .setKey("id")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn { it.createdAt?.toString().orEmpty() }
            .setHeader("Created")
            .setKey("createdAt")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn(Article::language)
            .setHeader("Language")
            .setKey("language")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn(Article::title)
            .setHeader("Title")
            .setKey("title")
            .setSortable(true)
            .setFlexGrow(1)
        grid.addColumn(Article::url)
            .setHeader("URL")
            .setKey("url")
            .setSortable(true)
            .setFlexGrow(1)

        grid.addItemClickListener { event ->
            grid.element.executeJs("window.open($0, '_blank', 'noopener')", event.item.url)
        }

        grid.dataProvider = dataProvider
        grid.setPageSize(pageSize)
        grid.setSizeFull()

        refreshData()
        add(topBar, controls, grid)
        expand(grid)
    }

    private fun buildAuthButton(): Button {
        val auth = SecurityContextHolder.getContext().authentication
        val isAuthenticated = auth != null && auth.isAuthenticated && auth.principal != "anonymousUser"
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

            if (url.isBlank()) { showError("URL is required"); return@addClickListener }
            if (!url.matches(Regex("^https?://.+"))) { showError("URL must start with http:// or https://"); return@addClickListener }
            if (language.isBlank()) { showError("Language is required"); return@addClickListener }

            submitButton.isEnabled = false
            try {
                val auth = SecurityContextHolder.getContext().authentication
                val email = when {
                    auth?.principal is OAuth2AuthenticatedPrincipal ->
                        (auth.principal as OAuth2AuthenticatedPrincipal).attributes["email"] as? String
                    else -> auth?.name
                }
                val normalizedEmail = AllowlistAuthorizationManager.normalizeEmail(email)
                val creator = normalizedEmail?.let { appUserRepository.findByEmail(it) }
                if (creator == null) {
                    showLoginOverlay(dialog)
                    return@addClickListener
                }
                articleService.create(ArticleInput(url = url, language = language, quote = quote), creator.id!!)
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

        dialog.add(VerticalLayout(urlField, languageField, quoteField, actions))
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
        val totalElements = articleRepository.count()
        totalInfo.text = "Total articles: $totalElements"
        dataProvider.refreshAll()
    }
}
