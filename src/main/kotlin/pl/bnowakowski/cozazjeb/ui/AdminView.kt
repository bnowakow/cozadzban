// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Paragraph
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
import com.vaadin.flow.server.VaadinServletRequest
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozazjeb.article.ArticleContent
import pl.bnowakowski.cozazjeb.article.ArticleContentRepository
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.article.ArticleService
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserInput
import pl.bnowakowski.cozazjeb.user.AppUserRolePatch
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.Role

@Route("admin")
@PageTitle("Admin")
@RolesAllowed("ADMIN")
class AdminView(
    private val appUserService: AppUserService,
    private val articleService: ArticleService,
    private val articleRepository: ArticleRepository,
    private val articleContentRepository: ArticleContentRepository,
) : VerticalLayout() {

    private val usersGrid = Grid(AppUser::class.java, false)
    private val contentGrid = Grid(ArticleContent::class.java, false)

    init {
        setSizeFull()

        val title = H2("Admin Panel")
        val addUserButton = buildAddUserButton()
        val manageArticlesButton = Button("Manage articles")
        manageArticlesButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        manageArticlesButton.addClickListener { ui.ifPresent { it.navigate("") } }
        val logoutButton = Button("Logout")
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        logoutButton.addClickListener { logoutAndRedirect() }

        val topBar = HorizontalLayout(title, addUserButton, manageArticlesButton, logoutButton)
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(title)

        configureGrid()
        refreshGrid()

        configureContentGrid()
        refreshContentGrid()

        add(topBar, usersGrid, H3("Article content cache"), contentGrid)
        expand(usersGrid)
    }

    private fun buildAddUserButton(): Button {
        val button = Button("Add user")
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        button.addClickListener { openAddUserDialog() }
        return button
    }

    private fun openAddUserDialog() {
        val emailField = TextField("Email")
        emailField.isRequired = true
        emailField.width = "24rem"

        val roleSelect = Select<Role>()
        roleSelect.label = "Role"
        roleSelect.setItems(Role.entries)
        roleSelect.value = Role.USER

        val submitButton = Button("Submit")
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val cancelButton = Button("Cancel")
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val dialog = Dialog()
        dialog.headerTitle = "Add user"

        submitButton.addClickListener {
            val email = emailField.value?.trim().orEmpty()
            val role = roleSelect.value ?: Role.USER

            if (email.isBlank()) {
                showError("Email is required")
                return@addClickListener
            }

            try {
                appUserService.create(AppUserInput(email = email, role = role))
                refreshGrid()
                dialog.close()
                showSuccess("User added")
            } catch (ex: Exception) {
                showError(ex.message ?: "Failed to add user")
            }
        }

        cancelButton.addClickListener { dialog.close() }

        val actions = HorizontalLayout(submitButton, cancelButton)
        actions.defaultVerticalComponentAlignment = Alignment.END

        dialog.add(VerticalLayout(emailField, roleSelect, actions))
        dialog.open()
    }

    private fun configureGrid() {
        usersGrid.setSizeFull()
        usersGrid.addColumn { it.id?.toString().orEmpty() }
            .setHeader("ID")
            .setAutoWidth(true)
        usersGrid.addColumn(AppUser::email)
            .setHeader("Email")
            .setFlexGrow(1)
        usersGrid.addColumn(AppUser::role)
            .setHeader("Role")
            .setAutoWidth(true)
        usersGrid.addColumn(AppUser::status)
            .setHeader("Status")
            .setAutoWidth(true)
        usersGrid.addColumn { it.createdAt?.toString().orEmpty() }
            .setHeader("Created At")
            .setAutoWidth(true)

        usersGrid.addComponentColumn { user -> roleEditor(user) }
            .setHeader("Update role")
            .setAutoWidth(true)
        usersGrid.addComponentColumn { user -> actionButtons(user) }
            .setHeader("Actions")
            .setAutoWidth(true)
    }

    private fun roleEditor(user: AppUser): HorizontalLayout {
        val select = Select<Role>()
        select.setItems(Role.entries)
        select.value = user.role
        select.addValueChangeListener { event ->
            if (!event.isFromClient) return@addValueChangeListener

            val id = user.id ?: return@addValueChangeListener
            val newRole = event.value ?: return@addValueChangeListener
            try {
                appUserService.updateRole(id, AppUserRolePatch(newRole))
                refreshGrid()
                showSuccess("Role updated")
            } catch (ex: Exception) {
                refreshGrid()
                showError(ex.message ?: "Failed to update role")
            }
        }

        return HorizontalLayout(select)
    }

    private fun actionButtons(user: AppUser): HorizontalLayout {
        val layout = HorizontalLayout()
        if (user.status == AppUserStatus.DELETED) {
            val restoreButton = Button("Restore")
            restoreButton.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_TERTIARY)
            restoreButton.addClickListener {
                val id = user.id ?: return@addClickListener
                try {
                    appUserService.restore(id)
                    refreshGrid()
                    showSuccess("User restored")
                } catch (ex: Exception) {
                    showError(ex.message ?: "Failed to restore user")
                }
            }
            layout.add(restoreButton)
        } else {
            layout.add(deleteButton(user))
        }
        return layout
    }

    private fun deleteButton(user: AppUser): Button {
        val button = Button("Delete")
        button.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        button.addClickListener {
            val id = user.id ?: return@addClickListener
            val dialog = ConfirmDialog(
                "Delete user",
                "Delete ${user.email}?",
                "Delete",
                {
                    try {
                        appUserService.delete(id)
                        refreshGrid()
                        showSuccess("User deleted")
                    } catch (ex: Exception) {
                        showError(ex.message ?: "Failed to delete user")
                    }
                },
                "Cancel",
                { },
            )
            dialog.open()
        }
        return button
    }

    private fun refreshGrid() {
        usersGrid.setItems(appUserService.list())
    }

    private fun configureContentGrid() {
        contentGrid.setSizeFull()
        contentGrid.addColumn { it.articleId.toString() }
            .setHeader("Article ID")
            .setAutoWidth(true)
        contentGrid.addColumn { entry ->
            articleRepository.findById(entry.articleId).map { it.url }.orElse("—")
        }
            .setHeader("Article URL")
            .setFlexGrow(1)
        contentGrid.addColumn { "${it.content.length} chars" }
            .setHeader("Size")
            .setAutoWidth(true)
        contentGrid.addColumn { if (it.truncated) "Yes" else "No" }
            .setHeader("Truncated")
            .setAutoWidth(true)
        contentGrid.addColumn { it.capturedAt?.toString() ?: "—" }
            .setHeader("Captured at")
            .setAutoWidth(true)
        contentGrid.addComponentColumn { entry -> contentActionButtons(entry) }
            .setHeader("Actions")
            .setAutoWidth(true)
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
        val textArea = TextArea("Content (read-only)")
        textArea.value = entry.content
        textArea.isReadOnly = true
        textArea.setSizeFull()
        textArea.minHeight = "400px"

        val truncatedNote = if (entry.truncated) {
            Paragraph("⚠ Content was truncated to 5 MB at capture time.")
        } else null

        val closeButton = Button("Close")
        closeButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val refreshButton = Button("Refresh cache from website")
        refreshButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val dialog = Dialog()
        dialog.headerTitle = "Preserved content — article ${entry.articleId}"
        dialog.setWidth("80vw")
        dialog.setHeight("80vh")
        val content = VerticalLayout(textArea)
        if (truncatedNote != null) content.addComponentAsFirst(truncatedNote)
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
        closeButton.addClickListener { dialog.close() }
        dialog.footer.add(refreshButton, closeButton)
        dialog.open()
    }

    private fun refreshContentGrid() {
        contentGrid.setItems(articleContentRepository.findAll().toList())
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
}
