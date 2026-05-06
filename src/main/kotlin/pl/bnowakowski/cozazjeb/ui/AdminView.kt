// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
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
@PageTitle("Admin")
@RolesAllowed("ADMIN")
class AdminView(
    private val appUserService: AppUserService,
) : VerticalLayout() {

    private val usersGrid = Grid(AppUser::class.java, false)

    init {
        setSizeFull()

        val title = H2("Admin Panel")
        val addUserButton = buildAddUserButton()
        val logoutButton = Button("Logout")
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        logoutButton.addClickListener { logoutAndRedirect() }

        val topBar = HorizontalLayout(title, addUserButton, logoutButton)
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(title)

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

    private fun logoutAndRedirect() {
        VaadinServletRequest.getCurrent().httpServletRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        ui.ifPresent { it.page.setLocation("/") }
    }

    private fun showSuccess(message: String) {
        Notification.show(message, 3000, Notification.Position.TOP_END)
    }

    private fun showError(message: String) {
        val notification = Notification.show(message, 4000, Notification.Position.TOP_END)
        notification.addThemeVariants(NotificationVariant.LUMO_ERROR)
    }
}
