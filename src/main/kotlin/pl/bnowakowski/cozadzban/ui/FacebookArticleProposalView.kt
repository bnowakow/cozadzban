// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposal
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalService
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatus
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatusFilter
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

@Route("article-proposals")
@PageTitle("Article proposals")
@RolesAllowed("USER", "ADMIN")
@CssImport("./styles/cozadzban-feed.css")
@CssImport(value = "./styles/cozadzban-dialog-overlay.css", themeFor = "vaadin-dialog-overlay")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-context-menu-overlay")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-menu-bar-overlay")
@CssImport(value = "./styles/cozadzban-menu-bar.css", themeFor = "vaadin-menu-bar")
@CssImport(value = "./styles/cozadzban-menu-bar-button.css", themeFor = "vaadin-menu-bar-button")
class FacebookArticleProposalView(
    private val proposalService: FacebookArticleProposalService,
    private val appUserRepository: AppUserRepository,
    private val languageFlagCache: LanguageFlagCache,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val submittedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(WarsawTime.ZONE)

    private val proposalsGrid = VerticalLayout()
    private val statusFilter = Select<FacebookArticleProposalStatusFilter>()
    private val countLabel = Span()
    private val pageInfo = Span()
    private val pageNumber = PageNumberInput { target -> page = target; refreshGrid() }
    private val previous = Button("Previous") { page--; refreshGrid() }
    private val next = Button("Next") { page++; refreshGrid() }
    private var page = 0

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")
        element.style.set("padding", "0")
        element.style.set("gap", "0")

        statusFilter.setItems(*FacebookArticleProposalStatusFilter.entries.toTypedArray())
        statusFilter.value = FacebookArticleProposalStatusFilter.PENDING
        statusFilter.setItemLabelGenerator {
            when (it) {
                FacebookArticleProposalStatusFilter.PENDING -> "Pending"
                FacebookArticleProposalStatusFilter.ACCEPTED -> "Accepted"
                FacebookArticleProposalStatusFilter.REJECTED -> "Rejected"
                FacebookArticleProposalStatusFilter.FAILED -> "Failed"
                FacebookArticleProposalStatusFilter.ALREADY_EXISTS -> "Already exists"
                FacebookArticleProposalStatusFilter.ALL -> "All"
            }
        }
        statusFilter.label = "Status"
        statusFilter.addValueChangeListener {
            if (it.isFromClient) {
                page = 0
                refreshGrid()
            }
        }

        configureGrid()

        val navigation = HorizontalLayout(previous, pageInfo, pageNumber, next)
        navigation.defaultVerticalComponentAlignment = Alignment.CENTER
        navigation.element.style.set("flex-wrap", "wrap")
        val content = VerticalLayout(buildProposalToolbar(), proposalsGrid, navigation)
        content.addClassName("czj-admin-panel")
        content.isPadding = false
        content.isSpacing = true
        content.setWidthFull()
        content.element.style.set("box-sizing", "border-box")
        content.element.style.set("margin", "calc(66px + 1.25rem) 1.25rem 1.25rem")
        content.element.style.set("width", "calc(100% - 2.5rem)")
        content.expand(proposalsGrid)

        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.ARTICLE_PROPOSALS,
                isAdmin = currentActiveUser().role == Role.ADMIN,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
        expand(content)
        refreshGrid()
    }

    private fun buildProposalToolbar(): HorizontalLayout {
        val title = Span("Article proposals")
        title.addClassName("czj-admin-title")
        val toolbar = HorizontalLayout(title, statusFilter, countLabel)
        toolbar.defaultVerticalComponentAlignment = Alignment.CENTER
        toolbar.setWidthFull()
        toolbar.expand(title)
        toolbar.element.style.set("padding", "1rem 1rem 0")
        return toolbar
    }

    private fun configureGrid() {
        proposalsGrid.addClassName("czj-admin-grid")
        proposalsGrid.setWidthFull()
        proposalsGrid.setMinHeight("24rem")
        proposalsGrid.isPadding = false
        proposalsGrid.isSpacing = false
    }

    private fun refreshGrid() {
        val filter = statusFilter.value ?: FacebookArticleProposalStatusFilter.PENDING
        val count = proposalService.count(filter)
        val lastPage = if (count == 0L) 0 else ((count - 1) / PAGE_SIZE).toInt()
        page = page.coerceIn(0, lastPage)
        val proposals = proposalService.findPage(filter, page = page, size = PAGE_SIZE)
        countLabel.text = "$count ${if (count == 1L) "proposal" else "proposals"}"
        pageInfo.text = "Page ${page + 1} of ${lastPage + 1}"
        pageNumber.update(page, lastPage + 1)
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage

        proposalsGrid.removeAll()
        proposalsGrid.add(proposalGridHeaderRow())
        if (proposals.isEmpty()) {
            proposalsGrid.add(
                Span(if (count > 0) "No rows loaded for this filter." else "No proposals for this filter.").apply {
                    addClassName("czj-admin-muted")
                    element.style.set("padding", "1rem")
                },
            )
            return
        }
        proposals.forEach { proposalsGrid.add(proposalGridRow(it)) }
    }

    private fun proposalGridHeaderRow(): Div =
        proposalGridRowBase().apply {
            addClassName("czj-proposal-grid-header")
            add(
                gridCell("Actions"),
                gridCell("Submitted"),
                gridCell("Status"),
                gridCell("Language"),
                gridCell("Article URL"),
                gridCell("Facebook post"),
                gridCell("Import type"),
                gridCell("Candidate ID"),
                gridCell("Import run"),
            )
        }

    private fun proposalGridRow(proposal: FacebookArticleProposal): Div =
        proposalGridRowBase().apply {
            add(
                gridCell(
                    Button("Review") { openReviewDialog(proposal.id) }
                        .apply { addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY) },
                ),
                gridCell(formatSubmittedAt(proposal.submittedAt)),
                gridCell(statusLabel(proposal.status)),
                gridCell(languageWithFlag(languageFlagCache, proposal.effectiveLanguage)),
                gridCell(externalLink(proposal.articleUrl)),
                gridCell(proposal.facebookPostUrl?.let(::externalLink) ?: Span("-")),
                gridCell(importSourceBadge(facebookImportTypeLabel(proposal.importType))),
                gridCell(copyablePrefixedId(proposal.candidateId, "facebook-import-candidate-")),
                gridCell(copyablePrefixedId(proposal.importRunId, "facebook-import-")),
            )
        }

    private fun proposalGridRowBase(): Div =
        Div().apply {
            element.style.set("display", "grid")
            element.style.set(
                "grid-template-columns",
                "minmax(6rem, 7rem) minmax(9rem, 10rem) minmax(5rem, 7rem) minmax(6.5rem, 7.5rem) minmax(16rem, 1.2fr) minmax(16rem, 1.2fr) minmax(7rem, 8rem) minmax(7rem, 8rem) minmax(7rem, 8rem)",
            )
            element.style.set("min-width", "86rem")
            element.style.set("width", "100%")
        }

    private fun formatSubmittedAt(submittedAt: Instant): String =
        submittedAtFormatter.format(submittedAt)

    private fun gridCell(value: String): Div =
        gridCell(Span(value))

    private fun gridCell(value: Component): Div =
        Div(value).apply {
            element.style.set("border-bottom", "1px solid var(--czj-border)")
            element.style.set("box-sizing", "border-box")
            element.style.set("overflow", "hidden")
            element.style.set("padding", "0.6rem 0.75rem")
            element.style.set("text-overflow", "ellipsis")
        }

    private fun facebookImportTypeLabel(importType: FacebookImportType): String =
        when (importType) {
            FacebookImportType.APIFY -> "Apify import"
            FacebookImportType.SELENIUM -> "Selenium import"
        }

    private fun importSourceBadge(label: String): Span =
        Span(label).apply {
            addClassName("czj-import-source-badge")
            element.setAttribute("title", label)
        }

    private fun copyablePrefixedId(value: String, prefix: String): Component {
        val displayedValue = Span(value.removePrefix(prefix)).apply {
            element.style.set("overflow", "hidden")
            element.style.set("text-overflow", "ellipsis")
            element.style.set("white-space", "nowrap")
        }
        val copyButton = Button(VaadinIcon.COPY.create()).apply {
            addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON)
            element.setAttribute("aria-label", "Copy full ID")
            element.setAttribute("title", "Copy full ID")
            element.setProperty("copyValue", value)
            element.setAttribute("onclick", "navigator.clipboard.writeText(this.copyValue)")
            addClickListener { showSuccess("Copied ID") }
        }
        return HorizontalLayout(displayedValue, copyButton).apply {
            defaultVerticalComponentAlignment = Alignment.CENTER
            isPadding = false
            isSpacing = true
            setWidthFull()
            expand(displayedValue)
        }
    }

    private fun openReviewDialog(proposalId: Long) {
        val proposal = proposalService.findById(proposalId)
        val logs = proposalService.logsFor(proposalId)
        val currentUser = currentActiveUser()

        val dialog = Dialog()
        dialog.headerTitle = "Review article proposal"
        dialog.setWidth("min(92vw, 60rem)")

        val articleLink = labeled("Article", externalLink(proposal.articleUrl))
        val facebookLink = labeled("Facebook post", proposal.facebookPostUrl?.let(::externalLink) ?: Span("-"))
        val language = TextField("Language")
        language.value = proposal.effectiveLanguage
        language.width = "12rem"

        val logArea = TextArea("Logs")
        logArea.value = logs
        logArea.isReadOnly = true
        logArea.setWidthFull()
        logArea.height = "16rem"

        val actionStatus = Span().apply {
            addClassName("czj-admin-muted")
        }

        val acceptButton = Button("Accept", VaadinIcon.CHECK.create())
        val rejectButton = Button("Reject", VaadinIcon.CLOSE_SMALL.create()) {
            try {
                proposalService.reject(proposalId, language.value.trim(), currentUser.id!!)
                dialog.close()
                refreshGrid()
                showSuccess("Proposal rejected")
            } catch (ex: Exception) {
                refreshGrid()
                showError(ex.message ?: "Failed to reject proposal")
            }
        }
        acceptButton.addClickListener {
            val currentUi = UI.getCurrent()
            val languageValue = language.value.trim()
            acceptButton.isEnabled = false
            rejectButton.isEnabled = false
            language.isReadOnly = true
            actionStatus.text = "Accepting proposal..."

            CompletableFuture
                .supplyAsync {
                    proposalService.accept(proposalId, languageValue, currentUser.id!!)
                }
                .whenComplete { updated, failure ->
                    currentUi.access {
                        if (failure == null) {
                            dialog.close()
                            refreshGrid()
                            showSuccess(
                                if (updated.status == FacebookArticleProposalStatus.ALREADY_EXISTS) {
                                    "Proposal marked as already existing"
                                } else {
                                    "Proposal accepted"
                                },
                            )
                        } else {
                            acceptButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED
                            rejectButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED
                            language.isReadOnly = false
                            actionStatus.text = ""
                            refreshGrid()
                            showError(failure.cause?.message ?: failure.message ?: "Failed to accept proposal")
                        }
                    }
                }
        }
        acceptButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS)
        acceptButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED

        rejectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        rejectButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED

        val closeButton = Button("Close") { dialog.close() }
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val actions = HorizontalLayout(acceptButton, rejectButton, closeButton, actionStatus)
        actions.defaultVerticalComponentAlignment = Alignment.CENTER

        val content = VerticalLayout(articleLink, facebookLink, language, logArea, actions)
        content.addClassName("czj-admin-dialog-content")
        content.setWidthFull()
        dialog.add(content)
        dialog.open()
    }

    private fun currentActiveUser(): AppUser {
        val auth = SecurityContextHolder.getContext().authentication
        val email = when {
            auth?.principal is OAuth2AuthenticatedPrincipal ->
                (auth.principal as OAuth2AuthenticatedPrincipal).attributes["email"] as? String
            else -> auth?.name
        }
        val normalized = AllowlistAuthorizationManager.normalizeEmail(email)
            ?: throw IllegalStateException("Current user email is unavailable")
        val user = appUserRepository.findByEmail(normalized)
            ?: throw IllegalStateException("Current user is not allowlisted")
        check(user.status == AppUserStatus.ACTIVE) { "Current user is not active" }
        return user
    }

    private fun labeled(label: String, value: Component): HorizontalLayout {
        val labelText = Span(label)
        labelText.element.style.set("font-weight", "600")
        labelText.element.style.set("min-width", "7rem")
        val row = HorizontalLayout(labelText, value)
        row.defaultVerticalComponentAlignment = Alignment.CENTER
        row.setWidthFull()
        row.expand(value)
        return row
    }

    private fun externalLink(url: String): Anchor =
        Anchor(url, url).apply {
            setTarget("_blank")
            element.setAttribute("rel", "noopener noreferrer")
            element.style.set("overflow-wrap", "anywhere")
            element.style.set("word-break", "break-all")
        }

    private fun statusLabel(status: FacebookArticleProposalStatus?): String =
        when (status) {
            null -> "Pending"
            FacebookArticleProposalStatus.ACCEPTED -> "Accepted"
            FacebookArticleProposalStatus.REJECTED -> "Rejected"
            FacebookArticleProposalStatus.FAILED -> "Failed"
            FacebookArticleProposalStatus.ALREADY_EXISTS -> "Already exists"
        }

    private fun showSuccess(message: String) {
        showNotification(message, 3000)
    }

    private fun showError(message: String) {
        showNotification(message, 4500, NotificationVariant.LUMO_ERROR)
    }

    private fun showNotification(message: String, duration: Int, variant: NotificationVariant? = null) {
        val notification = Notification()
        notification.duration = duration
        notification.position = Notification.Position.TOP_END
        if (variant != null) notification.addThemeVariants(variant)
        notification.add(Span(message))
        notification.open()
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
