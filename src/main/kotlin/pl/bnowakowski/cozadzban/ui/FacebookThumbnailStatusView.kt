// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationScheduler
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageStatusRow

@Route("admin/facebook-thumbnails")
@PageTitle("Article thumbnails")
@RolesAllowed("ADMIN")
@JsModule("@vaadin/combo-box/src/vaadin-combo-box.js")
@JsModule("@vaadin/grid/src/vaadin-grid.js")
@Uses(ComboBox::class)
@Uses(Grid::class)
class FacebookThumbnailStatusView(
    private val jobs: FacebookArticleImageGenerationRepository,
    private val generation: FacebookArticleImageGenerationScheduler,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val status = ComboBox<String>("Thumbnail status")
    private val grid = Grid(FacebookArticleImageStatusRow::class.java, false)
    private val pageInfo = Span()
    private val previous = Button("Previous") { changePage(-1) }
    private val next = Button("Next") { changePage(1) }
    private var page = 0

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        sharedContentOffset()
        status.setItems(STATUS_LABELS.keys)
        status.value = "All"
        status.addValueChangeListener { page = 0; refresh() }
        configureGrid()

        val filters = HorizontalLayout(status, Button("Refresh") { refresh() })
        filters.setWidthFull()
        val navigation = HorizontalLayout(previous, pageInfo, next)
        val message = if (generation.isEnabled()) {
            "Select a status to review article thumbnails. Generate now starts one missing or failed illustration in the background; refresh to see its result."
        } else {
            "Image generation is disabled in server configuration. You can still review thumbnail status."
        }
        val content = sharedPageContent(
            Span("Article thumbnails"), Span(message), filters, grid, navigation,
        )
        content.element.style.set("min-height", "0")
        content.element.style.set("overflow-y", "auto")
        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.FACEBOOK_THUMBNAILS,
                isAdmin = true,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
        expand(content)
        refresh()
    }

    private fun configureGrid() {
        grid.setWidthFull()
        grid.setHeight("32rem")
        grid.setMinHeight("20rem")
        grid.addClassName("czj-admin-grid")
        grid.addComponentColumn { row ->
            Anchor("/stories/${row.articleId}", "${row.articleId} · ${row.title?.take(90) ?: "Untitled"}").apply {
                element.setAttribute("router-ignore", true)
            }
        }.setHeader("Article").setFlexGrow(2)
        grid.addColumn(FacebookArticleImageStatusRow::language).setHeader("Language").setAutoWidth(true)
        grid.addColumn { labelFor(it.status) }.setHeader("Status").setAutoWidth(true)
        grid.addComponentColumn { row -> thumbnail(row) }.setHeader("Thumbnail").setAutoWidth(true)
        grid.addColumn(FacebookArticleImageStatusRow::attemptCount).setHeader("Attempts").setAutoWidth(true)
        grid.addColumn { row ->
            if (row.status == "MISSING_THUMBNAIL") row.nextAttemptAt?.toString()?.take(16) ?: "—" else "—"
        }
            .setHeader("Next attempt (UTC)").setAutoWidth(true)
        grid.addColumn { it.updatedAt?.toString()?.take(16) ?: "—" }
            .setHeader("Updated (UTC)").setAutoWidth(true)
        grid.addColumn { it.lastError ?: "—" }.setHeader("Last error").setFlexGrow(1)
        grid.addComponentColumn { row ->
            Button("Generate now") { generateNow(row.articleId) }.apply {
                isEnabled = generation.isEnabled() && row.status in setOf("MISSING_THUMBNAIL", "FAILED")
            }
        }.setHeader("Action").setAutoWidth(true)
    }

    private fun thumbnail(row: FacebookArticleImageStatusRow) = when (row.status) {
        "GENERATED" -> Image("/generated-images/${row.articleId}.png", "AI-generated illustration")
        "SOURCE_THUMBNAIL" -> row.sourceThumbnail?.let { Image(it, "Source thumbnail") } ?: Span("—")
        else -> Span("—")
    }.apply {
        if (this is Image) {
            setWidth("96px")
            setHeight("50px")
            element.style.set("object-fit", "cover")
            element.style.set("border-radius", "4px")
        }
    }

    private fun generateNow(articleId: Long) {
        val started = generation.generateNow(articleId)
        val notification = Notification.show(
            if (started) "Thumbnail generation started for article $articleId."
            else "Could not start generation. Another image may be in progress; refresh and try again.",
        )
        if (!started) notification.addThemeVariants(NotificationVariant.LUMO_ERROR)
        refresh()
    }

    private fun refresh() {
        val selected = STATUS_LABELS[status.value] ?: ""
        val total = jobs.countStatuses(selected)
        val lastPage = if (total == 0L) 0 else ((total - 1) / PAGE_SIZE).toInt()
        page = page.coerceAtMost(lastPage)
        grid.setItems(jobs.statusPage(selected, PAGE_SIZE, page * PAGE_SIZE))
        pageInfo.text = "Page ${page + 1} of ${lastPage + 1} · $total articles"
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
    }

    private fun changePage(direction: Int) {
        page = (page + direction).coerceAtLeast(0)
        refresh()
    }

    private fun labelFor(value: String) = STATUS_LABELS.entries.firstOrNull { it.value == value }?.key ?: value

    private companion object {
        const val PAGE_SIZE = 50
        val STATUS_LABELS = linkedMapOf(
            "All" to "",
            "Source thumbnail" to "SOURCE_THUMBNAIL",
            "Missing thumbnail" to "MISSING_THUMBNAIL",
            "Generating" to "GENERATING",
            "AI generated" to "GENERATED",
            "Failed" to "FAILED",
        )
    }
}
