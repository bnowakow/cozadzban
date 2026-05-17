// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dependency.CssImport
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
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozazjeb.user.AppUser
import pl.bnowakowski.cozazjeb.user.AppUserInput
import pl.bnowakowski.cozazjeb.user.AppUserRolePatch
import pl.bnowakowski.cozazjeb.user.AppUserService
import pl.bnowakowski.cozazjeb.user.AppUserStatus
import pl.bnowakowski.cozazjeb.user.Role

@Route("admin")
@PageTitle("Manage users")
@RolesAllowed("ADMIN")
@CssImport("./styles/cozazjeb-feed.css")
@CssImport(value = "./styles/cozazjeb-dialog-overlay.css", themeFor = "vaadin-dialog-overlay")
@CssImport(value = "./styles/cozazjeb-confirm-dialog-overlay.css", themeFor = "vaadin-confirm-dialog-overlay")
@CssImport(value = "./styles/cozazjeb-select-overlay.css", themeFor = "vaadin-select-overlay")
class AdminView(
    private val appUserService: AppUserService,
) : VerticalLayout() {

    private val usersGrid = Grid(AppUser::class.java, false)

    init {
        installCozazjebThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")

        val titleGroup = buildAdminTitleGroup("Manage users")
        val addUserButton = buildAddUserButton()
        val contentCacheButton = Button("Article content cache")
        contentCacheButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        contentCacheButton.addClickListener { ui.ifPresent { it.navigate("admin/article-content-cache") } }
        val feedButton = Button("Feed")
        feedButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        feedButton.addClickListener { ui.ifPresent { it.navigate("") } }
        val themeButton = buildThemeToggleButton()
        val logoutButton = Button("Logout")
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        logoutButton.addClickListener { logoutAndRedirect() }

        val topBar = HorizontalLayout(titleGroup, addUserButton, contentCacheButton, feedButton, themeButton, logoutButton)
        topBar.addClassName("czj-admin-top-bar")
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(titleGroup)

        configureGrid()
        refreshGrid()

        add(topBar, usersGrid)
        expand(usersGrid)
    }

    private fun buildAddUserButton(): Button {
        val button = Button("Add user")
        button.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        button.addClickListener { openAddUserDialog() }
        return button
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

        val content = VerticalLayout(emailField, roleSelect, actions)
        content.addClassName("czj-admin-dialog-content")
        dialog.add(content)
        dialog.open()
    }

    private fun configureGrid() {
        usersGrid.addClassName("czj-admin-grid")
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
