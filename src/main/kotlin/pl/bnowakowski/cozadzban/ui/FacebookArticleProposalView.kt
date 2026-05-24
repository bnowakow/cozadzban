// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
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
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposal
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalService
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatus
import pl.bnowakowski.cozadzban.facebookimport.FacebookArticleProposalStatusFilter
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus

@Route("article-proposals")
@PageTitle("Article proposals")
@RolesAllowed("USER", "ADMIN")
@CssImport("./styles/cozadzban-feed.css")
@CssImport(value = "./styles/cozadzban-dialog-overlay.css", themeFor = "vaadin-dialog-overlay")
class FacebookArticleProposalView(
    private val proposalService: FacebookArticleProposalService,
    private val appUserRepository: AppUserRepository,
) : VerticalLayout() {

    private val proposalsGrid = VerticalLayout()
    private val statusFilter = Select<FacebookArticleProposalStatusFilter>()
    private val countLabel = Span()

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")

        statusFilter.setItems(*FacebookArticleProposalStatusFilter.entries.toTypedArray())
        statusFilter.value = FacebookArticleProposalStatusFilter.PENDING
        statusFilter.setItemLabelGenerator {
            when (it) {
                FacebookArticleProposalStatusFilter.PENDING -> "Pending"
                FacebookArticleProposalStatusFilter.ACCEPTED -> "Accepted"
                FacebookArticleProposalStatusFilter.REJECTED -> "Rejected"
                FacebookArticleProposalStatusFilter.FAILED -> "Failed"
                FacebookArticleProposalStatusFilter.ALL -> "All"
            }
        }
        statusFilter.label = "Status"
        statusFilter.addValueChangeListener {
            if (it.isFromClient) refreshGrid()
        }

        configureGrid()

        val topBar = HorizontalLayout(
            buildTitleGroup(),
            statusFilter,
            countLabel,
            Button("Feed").apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY)
                addClickListener { ui.ifPresent { currentUi -> currentUi.navigate("") } }
            },
            buildThemeToggleButton(),
        )
        topBar.addClassName("czj-admin-top-bar")
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(topBar.getComponentAt(0))

        add(topBar, proposalsGrid)
        expand(proposalsGrid)
        refreshGrid()
    }

    private fun buildTitleGroup(): HorizontalLayout {
        val logo = Image("/cozadzban-logo.png", "Co za dzban")
        logo.addClassName("czj-admin-logo")
        val brand = Span("Co za dzban")
        brand.addClassName("czj-admin-brand")
        val title = H2("Article proposals")
        title.addClassName("czj-admin-title")
        val titleGroup = HorizontalLayout(logo, brand, title)
        titleGroup.addClassName("czj-admin-title-group")
        titleGroup.defaultVerticalComponentAlignment = Alignment.CENTER
        return titleGroup
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
        val proposals = proposalService.findPage(filter, page = 0, size = 100)
        val count = proposalService.count(filter)
        countLabel.text = "$count ${if (count == 1L) "proposal" else "proposals"}"

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
                gridCell("Submitted"),
                gridCell("Status"),
                gridCell("Language"),
                gridCell("Article URL"),
                gridCell("Facebook post"),
                gridCell("Candidate ID"),
                gridCell("Import run"),
                gridCell("Actions"),
            )
        }

    private fun proposalGridRow(proposal: FacebookArticleProposal): Div =
        proposalGridRowBase().apply {
            add(
                gridCell(proposal.submittedAt.toString()),
                gridCell(statusLabel(proposal.status)),
                gridCell(proposal.effectiveLanguage),
                gridCell(externalLink(proposal.articleUrl)),
                gridCell(proposal.facebookPostUrl?.let(::externalLink) ?: Span("-")),
                gridCell(proposal.candidateId),
                gridCell(proposal.importRunId),
                gridCell(
                    Button("Review") { openReviewDialog(proposal.id) }
                        .apply { addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY) },
                ),
            )
        }

    private fun proposalGridRowBase(): Div =
        Div().apply {
            element.style.set("display", "grid")
            element.style.set(
                "grid-template-columns",
                "minmax(11rem, 13rem) minmax(5rem, 7rem) minmax(4rem, 5rem) minmax(18rem, 1.4fr) minmax(14rem, 1fr) minmax(13rem, 14rem) minmax(16rem, 17rem) minmax(6rem, 7rem)",
            )
            element.style.set("min-width", "96rem")
            element.style.set("width", "100%")
        }

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

        val acceptButton = Button("Accept", VaadinIcon.CHECK.create()) {
            try {
                proposalService.accept(proposalId, language.value.trim(), currentUser.id!!)
                dialog.close()
                refreshGrid()
                showSuccess("Proposal accepted")
            } catch (ex: Exception) {
                refreshGrid()
                showError(ex.message ?: "Failed to accept proposal")
            }
        }
        acceptButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SUCCESS)
        acceptButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED

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
        rejectButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        rejectButton.isEnabled = proposal.status != FacebookArticleProposalStatus.ACCEPTED

        val closeButton = Button("Close") { dialog.close() }
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val actions = HorizontalLayout(acceptButton, rejectButton, closeButton)
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
                            localStorage.setItem('cozadzban-theme', nextMode);
                        })();
                    """.trimIndent(),
                )
            }
        }
        return button
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
}
