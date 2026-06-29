// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.contextmenu.SubMenu
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.router.QueryParameters
import com.vaadin.flow.server.VaadinServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportType

enum class CozadzbanTopBarPage {
    FEED,
    ARTICLE_PROPOSALS,
    NOTIFICATION_SETTINGS,
    MANAGE_USERS,
    ARTICLE_CONTENT_CACHE,
}

private const val TOP_BAR_MENU_BUTTON_SIZE = "34px"

fun Component.buildCozadzbanTopBar(
    currentPage: CozadzbanTopBarPage,
    canAccessUserPages: Boolean = true,
    isAdmin: Boolean,
    authButton: Button = buildDefaultLogoutButton(),
    onAddArticle: (() -> Unit)? = null,
    facebookImportJobService: FacebookImportJobService? = null,
    facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
    onStartFacebookImport: ((FacebookImportType) -> Unit)? = null,
    onStopFacebookImport: (() -> Unit)? = null,
): HorizontalLayout {
    val themeButton = buildSharedThemeToggleButton()
    val actions = HorizontalLayout(buildRssAnchor(), authButton, buildSharedMenu(
        currentPage = currentPage,
        isAdmin = isAdmin,
        themeButton = themeButton,
        canAccessUserPages = canAccessUserPages,
        onAddArticle = onAddArticle,
        facebookImportJobService = facebookImportJobService,
        facebookImportProperties = facebookImportProperties,
        onStartFacebookImport = onStartFacebookImport,
        onStopFacebookImport = onStopFacebookImport,
    ))
    actions.isPadding = false
    actions.isSpacing = true
    actions.defaultVerticalComponentAlignment = Alignment.CENTER
    actions.element.style.set("flex-shrink", "0")
    actions.element.style.set("padding-right", "0.25rem")

    val topBar = HorizontalLayout(buildSharedTitleGroup(), actions)
    topBar.addClassName("czj-top-bar")
    topBar.width = "100%"
    topBar.defaultVerticalComponentAlignment = Alignment.CENTER
    topBar.expand(topBar.getComponentAt(0))
    topBar.element.style.set("box-sizing", "border-box")
    topBar.element.style.set("height", "66px")
    topBar.element.style.set("padding", "0 1.5rem")
    topBar.element.style.set("background", "var(--czj-card-bg)")
    topBar.element.style.set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
    topBar.element.style.set("position", "fixed")
    topBar.element.style.set("top", "0")
    topBar.element.style.set("left", "0")
    topBar.element.style.set("right", "0")
    topBar.element.style.set("z-index", "1000")
    topBar.element.style.set("box-shadow", "0 2px 10px rgba(15, 23, 42, 0.08)")
    return topBar
}

fun Component.sharedContentOffset() {
    element.style.set("padding", "0")
    element.style.set("gap", "0")
}

fun sharedPageContent(vararg components: Component): com.vaadin.flow.component.orderedlayout.VerticalLayout {
    val content = com.vaadin.flow.component.orderedlayout.VerticalLayout(*components)
    content.addClassName("czj-admin-panel")
    content.isPadding = false
    content.isSpacing = true
    content.setWidthFull()
    content.element.style.set("box-sizing", "border-box")
    content.element.style.set("margin", "calc(66px + 1.25rem) 1.25rem 1.25rem")
    content.element.style.set("width", "calc(100% - 2.5rem)")
    return content
}

private fun Component.buildSharedTitleGroup(): Anchor {
    val logo = Image("/cozadzban-logo.png", "Co za dzban")
    logo.setWidth("46px")
    logo.setHeight("46px")
    logo.element.style.set("border-radius", "50%")
    logo.element.style.set("object-fit", "cover")
    logo.element.style.set("box-shadow", "0 1px 4px rgba(0,0,0,.26)")

    val brand = Span("Co za dzban")
    brand.element.style.set("font-size", "var(--lumo-font-size-xl)")
    brand.element.style.set("font-weight", "800")
    brand.element.style.set("white-space", "nowrap")

    val titleGroup = HorizontalLayout(logo, brand)
    titleGroup.isPadding = false
    titleGroup.isSpacing = true
    titleGroup.defaultVerticalComponentAlignment = Alignment.CENTER
    return Anchor("/", titleGroup).apply {
        addClassName("czj-brand-link")
        element.setAttribute("aria-label", "Go to main page")
        element.setAttribute("title", "Go to main page")
    }
}

private fun Component.buildSharedThemeToggleButton(): Button {
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

private fun Component.buildDefaultLogoutButton(): Button {
    val button = Button("Logout")
    button.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
    button.addClickListener {
        VaadinServletRequest.getCurrent().httpServletRequest.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        ui.ifPresent { it.page.setLocation("/") }
    }
    return button
}

private fun buildRssAnchor(): Anchor {
    val rssButton = Button("RSS", VaadinIcon.RSS.create())
    rssButton.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
    return Anchor("/rss").apply {
        setTarget("_blank")
        element.setAttribute("rel", "noopener noreferrer")
        add(rssButton)
    }
}

private fun Component.buildSharedMenu(
    currentPage: CozadzbanTopBarPage,
    isAdmin: Boolean,
    themeButton: Button,
    canAccessUserPages: Boolean,
    onAddArticle: (() -> Unit)?,
    facebookImportJobService: FacebookImportJobService?,
    facebookImportProperties: FacebookImportProperties,
    onStartFacebookImport: ((FacebookImportType) -> Unit)?,
    onStopFacebookImport: (() -> Unit)?,
): MenuBar {
    val menu = MenuBar()
    menu.addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE)
    menu.element.setAttribute("theme", "tertiary-inline czj-top-menu")
    val menuIcon = VaadinIcon.MENU.create()
    menuIcon.addClassName("czj-menu-trigger-icon")
    menuIcon.element.setAttribute("aria-hidden", "true")
    val menuTrigger = Span(menuIcon).apply {
        addClassName("czj-menu-trigger")
        element.style.set("align-items", "center")
        element.style.set("box-sizing", "border-box")
        element.style.set("display", "inline-flex")
        element.style.set("height", TOP_BAR_MENU_BUTTON_SIZE)
        element.style.set("justify-content", "center")
        element.style.set("line-height", "1")
        element.style.set("width", TOP_BAR_MENU_BUTTON_SIZE)
    }
    val root = menu.addItem(menuTrigger)
    root.element.setAttribute("aria-label", "Menu")
    root.element.setAttribute("title", "Menu")
    root.element.style.set("box-sizing", "border-box")
    root.element.style.set("height", TOP_BAR_MENU_BUTTON_SIZE)
    root.element.style.set("min-width", TOP_BAR_MENU_BUTTON_SIZE)
    root.element.style.set("width", TOP_BAR_MENU_BUTTON_SIZE)
    val subMenu = root.subMenu

    if (canAccessUserPages) {
        if (currentPage != CozadzbanTopBarPage.FEED) {
            subMenu.addIconItem("Feed", VaadinIcon.HOME) { ui.ifPresent { it.navigate("") } }
        }
        if (currentPage != CozadzbanTopBarPage.ARTICLE_PROPOSALS) {
            subMenu.addIconItem("Article proposals", VaadinIcon.LIST) { ui.ifPresent { it.navigate("article-proposals") } }
        }
        subMenu.addIconItem("Add article", VaadinIcon.PLUS) {
            if (onAddArticle != null) onAddArticle() else ui.ifPresent {
                it.navigate("", QueryParameters.of("addArticle", "1"))
            }
        }
        if (currentPage != CozadzbanTopBarPage.NOTIFICATION_SETTINGS) {
            subMenu.addIconItem("Notification settings", VaadinIcon.BELL) {
                ui.ifPresent { it.navigate("notification-settings") }
            }
        }
    }
    subMenu.addIconItem("Toggle dark mode", VaadinIcon.ADJUST) { themeButton.click() }

    if (isAdmin) {
        subMenu.addSeparator()
        subMenu.addItem("Admin").isEnabled = false
        if (currentPage != CozadzbanTopBarPage.MANAGE_USERS) {
            subMenu.addIconItem("Manage users", VaadinIcon.USERS) { ui.ifPresent { it.navigate("admin") } }
        }
        if (currentPage != CozadzbanTopBarPage.ARTICLE_CONTENT_CACHE) {
            subMenu.addIconItem("Article content cache", VaadinIcon.DATABASE) {
                ui.ifPresent { it.navigate("admin/article-content-cache") }
            }
        }
        if (facebookImportJobService != null) {
            val visibleImportTypes = facebookImportJobService.availableImportTypes().toMutableList()
            if (facebookImportProperties.apify.enabled && FacebookImportType.APIFY !in visibleImportTypes) {
                visibleImportTypes.add(FacebookImportType.APIFY)
            }
            if (visibleImportTypes.isEmpty()) {
                subMenu.addIconItem("Import Facebook unavailable", VaadinIcon.DOWNLOAD) {}.isEnabled = false
            } else {
                visibleImportTypes.forEach { importType ->
                    subMenu.addIconItem(manualImportMenuLabel(importType), VaadinIcon.DOWNLOAD) {
                        if (onStartFacebookImport != null) {
                            onStartFacebookImport(importType)
                        } else {
                            facebookImportJobService.startImport(importType)
                        }
                    }
                }
            }
            subMenu.addIconItem("Stop Facebook import", VaadinIcon.STOP) {
                if (onStopFacebookImport != null) {
                    onStopFacebookImport()
                } else {
                    facebookImportJobService.terminateImport()
                }
            }.isEnabled = facebookImportJobService.isImportRunning()
        }
    }

    return menu
}

private fun SubMenu.addIconItem(label: String, icon: VaadinIcon, action: () -> Unit): MenuItem {
    val row = HorizontalLayout(icon.create(), Span(label))
    row.addClassName("czj-menu-item-content")
    row.isPadding = false
    row.isSpacing = true
    row.defaultVerticalComponentAlignment = Alignment.CENTER
    return addItem(row) { action() }.apply {
        element.setAttribute("aria-label", label)
    }
}

private fun manualImportMenuLabel(importType: FacebookImportType): String =
    when (importType) {
        FacebookImportType.APIFY -> "Import Facebook with Apify"
        FacebookImportType.SELENIUM -> "Import Facebook with Selenium"
    }
