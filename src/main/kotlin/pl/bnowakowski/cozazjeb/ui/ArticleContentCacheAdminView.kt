// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.datetimepicker.DateTimePicker
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleContent
import pl.bnowakowski.cozazjeb.article.ArticleContentRepository
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import java.time.Instant
import java.time.ZoneOffset

@Route("admin/article-content-cache")
@PageTitle("Article content cache")
@RolesAllowed("ADMIN")
@CssImport("./styles/cozazjeb-feed.css")
@CssImport(value = "./styles/cozazjeb-dialog-overlay.css", themeFor = "vaadin-dialog-overlay")
@CssImport(value = "./styles/cozazjeb-confirm-dialog-overlay.css", themeFor = "vaadin-confirm-dialog-overlay")
class ArticleContentCacheAdminView(
    private val articleService: ArticleService,
    private val articleRepository: ArticleRepository,
    private val articleContentRepository: ArticleContentRepository,
) : VerticalLayout() {

    private val contentGrid = Grid(ArticleContent::class.java, false)
    private val contentArticleCache = mutableMapOf<Long, Article>()
    private val contentTotalInfo = Span()

    private var contentArticleIdFilter: Long? = null
    private var contentArticleUrlFilter: String? = null
    private var contentPublishedFromFilter: Instant? = null
    private var contentPublishedToFilter: Instant? = null
    private var contentCreatedFromFilter: Instant? = null
    private var contentCreatedToFilter: Instant? = null

    private val contentDataProvider = DataProvider.fromCallbacks<ArticleContent>(
        { query ->
            val requestedLimit = query.limit.coerceAtLeast(1)
            val requestedOffset = query.offset
            val page = requestedOffset / requestedLimit
            val entries = articleContentRepository.findPage(
                page = page,
                size = requestedLimit,
                articleId = contentArticleIdFilter,
                articleUrl = contentArticleUrlFilter,
                publishedFrom = contentPublishedFromFilter,
                publishedTo = contentPublishedToFilter,
                createdFrom = contentCreatedFromFilter,
                createdTo = contentCreatedToFilter,
            )
            populateContentArticleCache(entries)
            entries.stream()
        },
        { _ ->
            val count = articleContentRepository.countFiltered(
                articleId = contentArticleIdFilter,
                articleUrl = contentArticleUrlFilter,
                publishedFrom = contentPublishedFromFilter,
                publishedTo = contentPublishedToFilter,
                createdFrom = contentCreatedFromFilter,
                createdTo = contentCreatedToFilter,
            ).toInt()
            contentTotalInfo.text = "$count cache entries"
            count
        },
    )

    init {
        installCozazjebThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")

        val titleGroup = buildAdminTitleGroup("Article content cache")
        val usersButton = Button("Manage users")
        usersButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        usersButton.addClickListener { ui.ifPresent { it.navigate("admin") } }
        val feedButton = Button("Feed")
        feedButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        feedButton.addClickListener { ui.ifPresent { it.navigate("") } }
        val themeButton = buildThemeToggleButton()
        val logoutButton = Button("Logout")
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        logoutButton.addClickListener { logoutAndRedirect() }

        val topBar = HorizontalLayout(titleGroup, usersButton, feedButton, themeButton, logoutButton)
        topBar.addClassName("czj-admin-top-bar")
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(titleGroup)

        configureContentGrid()
        val contentFilters = buildContentFilters()
        val contentPanel = VerticalLayout(contentFilters, contentGrid)
        contentPanel.addClassName("czj-admin-panel")
        contentPanel.isPadding = false
        contentPanel.isSpacing = true
        contentPanel.setSizeFull()
        contentPanel.expand(contentGrid)

        add(topBar, contentPanel)
        expand(contentPanel)
    }

    private fun buildAdminTitleGroup(pageTitle: String): HorizontalLayout {
        val logo = Image("/cozazjeb-logo.png", "Co za zjeb")
        logo.addClassName("czj-admin-logo")

        val brand = Span("Co za zjeb")
        brand.addClassName("czj-admin-brand")

        val title = H2(pageTitle)
        title.addClassName("czj-admin-title")

        val titleGroup = HorizontalLayout(logo, brand, title)
        titleGroup.addClassName("czj-admin-title-group")
        titleGroup.isPadding = false
        titleGroup.isSpacing = true
        titleGroup.defaultVerticalComponentAlignment = Alignment.CENTER
        return titleGroup
    }

    private fun buildThemeToggleButton(): Button {
        val button = Button(VaadinIcon.ADJUST.create())
        button.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
        button.element.setAttribute("aria-label", "Toggle dark mode")
        button.element.setAttribute("title", "Toggle dark mode")
        button.addClickListener {
            ui.ifPresent { currentUi ->
                currentUi.page.executeJs(
                    """
                        (function() {
                            const root = document.documentElement;
                            const body = document.body;
                            const current = root.getAttribute('theme') || '';
                            const nextMode = current.includes('dark') ? 'light' : 'dark';
                            if (nextMode === 'dark') {
                                root.setAttribute('theme', 'dark');
                                body.setAttribute('theme', 'dark');
                            } else {
                                root.removeAttribute('theme');
                                body.removeAttribute('theme');
                            }
                            localStorage.setItem('cozazjeb-theme', nextMode);
                        })();
                    """.trimIndent(),
                )
            }
        }
        return button
    }

    private fun configureContentGrid() {
        contentGrid.addClassName("czj-admin-grid")
        contentGrid.setSizeFull()
        contentGrid.setPageSize(50)
        contentGrid.setDataProvider(contentDataProvider)
        contentGrid.addColumn { it.articleId.toString() }
            .setHeader("Article ID")
            .setAutoWidth(true)
        contentGrid.addColumn { entry ->
            contentArticleCache[entry.articleId]?.url ?: "-"
        }
            .setHeader("Article URL")
            .setFlexGrow(1)
        contentGrid.addColumn { entry -> contentArticleCache[entry.articleId]?.publishedAt?.toString() ?: "-" }
            .setHeader("Published at")
            .setAutoWidth(true)
        contentGrid.addColumn { entry -> contentArticleCache[entry.articleId]?.createdAt?.toString() ?: "-" }
            .setHeader("Article created")
            .setAutoWidth(true)
        contentGrid.addColumn { "${it.content.length} chars" }
            .setHeader("Size")
            .setAutoWidth(true)
        contentGrid.addColumn { if (it.truncated) "Yes" else "No" }
            .setHeader("Truncated")
            .setAutoWidth(true)
        contentGrid.addColumn { it.capturedAt?.toString() ?: "-" }
            .setHeader("Captured at")
            .setAutoWidth(true)
        contentGrid.addComponentColumn { entry -> contentActionButtons(entry) }
            .setHeader("Actions")
            .setAutoWidth(true)
    }

    private fun buildContentFilters(): VerticalLayout {
        val articleIdField = TextField("Article ID")
        articleIdField.valueChangeMode = ValueChangeMode.LAZY
        articleIdField.placeholder = "Exact ID"
        articleIdField.addValueChangeListener { event ->
            val value = event.value?.trim().orEmpty()
            contentArticleIdFilter = if (value.isBlank()) null else value.toLongOrNull() ?: NO_MATCHING_ARTICLE_ID
            refreshContentGrid()
        }

        val articleUrlField = TextField("Article URL")
        articleUrlField.valueChangeMode = ValueChangeMode.LAZY
        articleUrlField.placeholder = "Contains..."
        articleUrlField.width = "28rem"
        articleUrlField.addValueChangeListener { event ->
            contentArticleUrlFilter = event.value?.trim()?.takeIf { it.isNotBlank() }
            refreshContentGrid()
        }

        val publishedFromPicker = DateTimePicker("Published from")
        publishedFromPicker.addValueChangeListener { event ->
            contentPublishedFromFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshContentGrid()
        }

        val publishedToPicker = DateTimePicker("Published to")
        publishedToPicker.addValueChangeListener { event ->
            contentPublishedToFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshContentGrid()
        }

        val createdFromPicker = DateTimePicker("Created from")
        createdFromPicker.addValueChangeListener { event ->
            contentCreatedFromFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshContentGrid()
        }

        val createdToPicker = DateTimePicker("Created to")
        createdToPicker.addValueChangeListener { event ->
            contentCreatedToFilter = event.value?.toInstant(ZoneOffset.UTC)
            refreshContentGrid()
        }

        val clearButton = Button("Clear filters") {
            articleIdField.clear()
            articleUrlField.clear()
            publishedFromPicker.clear()
            publishedToPicker.clear()
            createdFromPicker.clear()
            createdToPicker.clear()
        }
        clearButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)

        val searchRow = HorizontalLayout(articleIdField, articleUrlField, contentTotalInfo)
        searchRow.width = "100%"
        searchRow.isSpacing = true
        searchRow.defaultVerticalComponentAlignment = Alignment.END
        contentTotalInfo.addClassName("czj-admin-muted")

        val dateRow = HorizontalLayout(
            publishedFromPicker,
            publishedToPicker,
            createdFromPicker,
            createdToPicker,
            clearButton,
        )
        dateRow.width = "100%"
        dateRow.isSpacing = true
        dateRow.defaultVerticalComponentAlignment = Alignment.END

        val filters = VerticalLayout(searchRow, dateRow)
        filters.addClassName("czj-admin-filters")
        filters.isPadding = false
        filters.isSpacing = true
        filters.width = "100%"
        return filters
    }

    private fun contentActionButtons(entry: ArticleContent): HorizontalLayout {
        val viewButton = Button("View")
        viewButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
        viewButton.addClickListener { openContentDialog(entry) }

        val purgeButton = Button("Purge")
        purgeButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
        purgeButton.addClickListener {
            val dialog = ConfirmDialog(
                "Purge content",
                "Delete preserved content for article ${entry.articleId}?",
                "Purge",
                {
                    articleContentRepository.deleteByArticleId(entry.articleId)
                    refreshContentGrid()
                    showSuccess("Content purged")
                },
                "Cancel",
                { },
            )
            dialog.open()
        }

        return HorizontalLayout(viewButton, purgeButton)
    }

    private fun openContentDialog(entry: ArticleContent) {
        val articleUrl = articleRepository.findById(entry.articleId).map { it.url }.orElse("-")
        val urlField = TextField("Article URL")
        urlField.value = articleUrl
        urlField.isReadOnly = true
        urlField.width = "100%"

        val textArea = TextArea("Content")
        textArea.value = entry.content
        textArea.setSizeFull()
        textArea.minHeight = "400px"
        textArea.element.classList.set("czj-admin-content-truncated", entry.truncated)

        val closeButton = Button("Close")
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val refreshButton = Button("Refresh cache from website")
        refreshButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        val saveButton = Button("Save content")
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val dialog = Dialog()
        dialog.headerTitle = "Preserved content - article ${entry.articleId}"
        dialog.setWidth("80vw")
        dialog.setHeight("80vh")
        val content = VerticalLayout(urlField, textArea)
        content.addClassName("czj-admin-content-dialog")
        content.setSizeFull()
        dialog.add(content)
        refreshButton.addClickListener {
            refreshButton.isEnabled = false
            try {
                val refreshed = articleService.refreshContentCache(entry.articleId)
                textArea.value = refreshed.content
                refreshContentGrid()
                showSuccess("Content cache refreshed")
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to refresh content cache")
            } finally {
                refreshButton.isEnabled = true
            }
        }
        saveButton.addClickListener {
            saveButton.isEnabled = false
            try {
                val saved = articleService.replaceContentCache(entry.articleId, textArea.value)
                textArea.value = saved.content
                refreshContentGrid()
                showSuccess("Content cache saved")
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to save content cache")
            } finally {
                saveButton.isEnabled = true
            }
        }
        closeButton.addClickListener { dialog.close() }
        dialog.footer.add(saveButton, refreshButton, closeButton)
        dialog.open()
    }

    private fun refreshContentGrid() {
        contentArticleCache.clear()
        contentDataProvider.refreshAll()
    }

    private fun populateContentArticleCache(entries: List<ArticleContent>) {
        val missingIds = entries.map { it.articleId }
            .filterNot { contentArticleCache.containsKey(it) }
            .distinct()
        if (missingIds.isEmpty()) return

        articleRepository.findAllById(missingIds).forEach { article ->
            val id = article.id ?: return@forEach
            contentArticleCache[id] = article
        }
    }

    private fun logoutAndRedirect() {
        VaadinServletRequest.getCurrent().httpServletRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        ui.ifPresent { it.page.setLocation("/") }
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

    private companion object {
        const val NO_MATCHING_ARTICLE_ID = -1L
    }
}
