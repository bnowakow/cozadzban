// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal
import pl.bnowakowski.cozadzban.notifications.NotificationPreferenceInput
import pl.bnowakowski.cozadzban.notifications.NotificationPreferenceService
import pl.bnowakowski.cozadzban.notifications.PushoverDevices
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.security.AllowlistAuthorizationManager
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role

@Route("notification-settings")
@PageTitle("Notification settings")
@RolesAllowed("USER", "ADMIN")
@CssImport("./styles/cozadzban-feed.css")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-context-menu-overlay")
@CssImport(value = "./styles/cozadzban-context-menu-overlay.css", themeFor = "vaadin-menu-bar-overlay")
@CssImport(value = "./styles/cozadzban-menu-bar.css", themeFor = "vaadin-menu-bar")
@CssImport(value = "./styles/cozadzban-menu-bar-button.css", themeFor = "vaadin-menu-bar-button")
class NotificationSettingsView(
    private val preferenceService: NotificationPreferenceService,
    private val appUserRepository: AppUserRepository,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {

    private val currentUser = currentActiveUser()
    private val pushoverUserKey = PasswordField("Pushover user key")
    private val pushoverDevices = MultiSelectComboBox<String>("Pushover devices")
    private val proposalNotifications = Checkbox("Article proposal review notifications")
    private val autoApprovedNotifications = Checkbox("Facebook auto-approved article notifications")
    private val loginRequiredNotifications = Checkbox("Facebook login required notifications")
    private val status = Span()

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        addClassName("czj-admin-view")
        sharedContentOffset()

        val summary = preferenceService.summaryFor(currentUser)
        pushoverUserKey.setWidthFull()
        pushoverUserKey.placeholder = if (summary.pushoverConfigured) {
            "Configured key ending in ${summary.pushoverUserKeySuffix}"
        } else {
            "Paste your Pushover user key"
        }
        pushoverDevices.setWidthFull()
        pushoverDevices.placeholder = "All devices"
        pushoverDevices.setSelectedItemsOnTop(true)
        applyPushoverDeviceOptions(summary.availablePushoverDevices, summary.pushoverDevices)
        proposalNotifications.value = summary.facebookProposalsSubmittedEnabled
        autoApprovedNotifications.value = summary.facebookProposalsAutoApprovedEnabled
        loginRequiredNotifications.value = summary.facebookLoginRequiredEnabled
        loginRequiredNotifications.isVisible = currentUser.role == Role.ADMIN
        status.text = if (summary.pushoverConfigured) "Pushover configured" else "Pushover not configured"
        status.addClassName("czj-admin-muted")

        val saveButton = Button("Validate and save", VaadinIcon.CHECK.create()) {
            saveSettings()
        }
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        val loadDevicesButton = Button("Load devices", VaadinIcon.REFRESH.create()) {
            loadPushoverDevices()
        }
        loadDevicesButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val testButton = Button("Send test notification", VaadinIcon.BELL.create()) {
            sendTestNotification()
        }
        testButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val actions = HorizontalLayout(saveButton, loadDevicesButton, testButton)
        actions.defaultVerticalComponentAlignment = Alignment.CENTER

        val form = VerticalLayout(
            status,
            pushoverUserKey,
            pushoverDevices,
            proposalNotifications,
            autoApprovedNotifications,
            loginRequiredNotifications,
            actions,
        )
        form.addClassName("czj-admin-dialog-content")
        form.setWidthFull()
        form.maxWidth = "42rem"

        val pageTitle = Span("Notification settings").apply { addClassName("czj-admin-title") }
        pageTitle.element.style.set("padding", "1rem 1rem 0")
        val content = sharedPageContent(pageTitle, form)
        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.NOTIFICATION_SETTINGS,
                isAdmin = currentUser.role == Role.ADMIN,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
    }

    private fun topBar(): HorizontalLayout {
        val logo = Image("/cozadzban-logo.png", "Co za dzban")
        logo.addClassName("czj-admin-logo")
        val brand = Span("Co za dzban")
        brand.addClassName("czj-admin-brand")
        val title = H2("Notification settings")
        title.addClassName("czj-admin-title")
        val titleGroup = HorizontalLayout(logo, brand, title)
        titleGroup.addClassName("czj-admin-title-group")
        titleGroup.defaultVerticalComponentAlignment = Alignment.CENTER

        val feedButton = Button("Feed") { ui.ifPresent { it.navigate("") } }
        feedButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)

        val topBar = HorizontalLayout(titleGroup, feedButton, buildThemeToggleButton())
        topBar.addClassName("czj-admin-top-bar")
        topBar.width = "100%"
        topBar.defaultVerticalComponentAlignment = Alignment.CENTER
        topBar.expand(titleGroup)
        return topBar
    }

    private fun saveSettings() {
        try {
            val summary = preferenceService.save(
                currentUser,
                NotificationPreferenceInput(
                    pushoverUserKey = pushoverUserKey.value,
                    pushoverDevices = pushoverDevices.value,
                    facebookLoginRequiredEnabled = loginRequiredNotifications.value,
                    facebookProposalsSubmittedEnabled = proposalNotifications.value,
                    facebookProposalsAutoApprovedEnabled = autoApprovedNotifications.value,
                ),
            )
            pushoverUserKey.clear()
            pushoverUserKey.placeholder = "Configured key ending in ${summary.pushoverUserKeySuffix}"
            applyPushoverDeviceOptions(summary.availablePushoverDevices, summary.pushoverDevices)
            status.text = "Pushover configured"
            showSuccess("Notification settings saved")
        } catch (ex: Exception) {
            showError(ex.message ?: "Could not save notification settings")
        }
    }

    private fun loadPushoverDevices() {
        try {
            val devices = preferenceService.availablePushoverDevices(currentUser, pushoverUserKey.value)
            val selectedDevices = PushoverDevices.normalize(pushoverDevices.value).filter { it in devices }
            applyPushoverDeviceOptions(devices, selectedDevices)
            showSuccess("Pushover devices loaded")
        } catch (ex: Exception) {
            showError(ex.message ?: "Could not load Pushover devices")
        }
    }

    private fun sendTestNotification() {
        try {
            preferenceService.sendTest(currentUser)
            showSuccess("Test notification sent")
        } catch (ex: Exception) {
            showError(ex.message ?: "Could not send test notification")
        }
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

    private fun applyPushoverDeviceOptions(options: Collection<String>, selected: Collection<String>) {
        val selectedDevices = PushoverDevices.normalize(selected)
        pushoverDevices.setItems(PushoverDevices.normalize(options + selectedDevices))
        pushoverDevices.setValue(selectedDevices)
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
