// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageGenerationScheduler
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageQueueRow
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
    private val languageFlagCache: LanguageFlagCache,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val status = ComboBox<String>("Thumbnail status")
    private val grid = Grid(FacebookArticleImageStatusRow::class.java, false)
    private val queueGrid = Grid(FacebookArticleImageQueueRow::class.java, false)
    private val pageInfo = Span()
    private val pageNumber = PageNumberInput { target -> page = target; refresh() }
    private val previous = Button("Previous") { changePage(-1) }
    private val next = Button("Next") { changePage(1) }
    private val queueSummary = Span()
    private val queuePageInfo = Span()
    private val queuePageNumber = PageNumberInput { target -> queuePage = target; refreshQueue() }
    private val queuePrevious = Button("Previous") { changeQueuePage(-1) }
    private val queueNext = Button("Next") { changeQueuePage(1) }
    private var page = 0
    private var queuePage = 0

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        sharedContentOffset()
        status.setItems(STATUS_LABELS.keys)
        status.value = "All"
        status.addValueChangeListener { page = 0; refresh() }
        configureGrid()
        configureQueueGrid()

        val filters = HorizontalLayout(status, Button("Refresh") { refresh() })
        filters.setWidthFull()
        val navigation = HorizontalLayout(previous, pageInfo, pageNumber, next)
        val queueNavigation = HorizontalLayout(queuePrevious, queuePageInfo, queuePageNumber, queueNext)
        navigation.element.style.set("flex-wrap", "wrap")
        queueNavigation.element.style.set("flex-wrap", "wrap")
        val message = if (generation.isEnabled()) {
            "Select a status to review article thumbnails. Generate now starts one missing or failed illustration in the background; refresh to see its result."
        } else {
            "Image generation is disabled in server configuration. You can still review thumbnail status."
        }
        val content = sharedPageContent(
            Span("Article thumbnails"), Span(message), filters, grid, navigation,
            H2("Generation queue"), queueSummary,
            Span("Waiting jobs are ordered by next attempt time, then article ID, as the scheduler selects them. Jobs being generated or requiring manual retry are shown in the status table above."),
            queueGrid, queueNavigation,
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
        grid.addComponentColumn { row -> languageWithFlag(languageFlagCache, row.language) }.setHeader("Language").setAutoWidth(true)
        grid.addComponentColumn { row -> withTooltip(rowLabel(row)) }.setHeader("Status").setAutoWidth(true)
        grid.addComponentColumn { row -> thumbnail(row) }.setHeader("Thumbnail").setAutoWidth(true)
        grid.addColumn(FacebookArticleImageStatusRow::attemptCount).setHeader("Attempts").setAutoWidth(true)
        grid.addColumn { row ->
            if (row.status == "MISSING_THUMBNAIL") row.nextAttemptAt?.let(WarsawTime::dateTime) ?: "—" else "—"
        }
            .setHeader("Next attempt").setAutoWidth(true)
        grid.addColumn { it.updatedAt?.let(WarsawTime::dateTime) ?: "—" }
            .setHeader("Updated").setAutoWidth(true)
        grid.addColumn { it.lastError ?: "—" }.setHeader("Last error").setFlexGrow(1)
        grid.addComponentColumn { row ->
            Button("Generate now") { generateNow(row.articleId) }.apply {
                isEnabled = generation.isEnabled() && row.status in setOf("MISSING_THUMBNAIL", "FAILED")
            }
        }.setHeader("Action").setAutoWidth(true)
    }

    private fun configureQueueGrid() {
        queueGrid.setWidthFull()
        queueGrid.setHeight("28rem")
        queueGrid.setMinHeight("18rem")
        queueGrid.addClassName("czj-admin-grid")
        queueGrid.addColumn(FacebookArticleImageQueueRow::position)
            .setHeader("Queue #").setAutoWidth(true)
        queueGrid.addComponentColumn { row ->
            Anchor("/stories/${row.articleId}", "${row.articleId} · ${row.title?.take(90) ?: "Untitled"}").apply {
                element.setAttribute("router-ignore", true)
            }
        }.setHeader("Article").setFlexGrow(2)
        queueGrid.addComponentColumn { row -> languageWithFlag(languageFlagCache, row.language) }
            .setHeader("Language").setAutoWidth(true)
        queueGrid.addComponentColumn {
            withTooltip(waitingReason(FacebookArticleImageGenerationRepository.hasUsableSourceThumbnail(it.sourceThumbnail), it.pageRejectedSourceThumbnail))
        }.setHeader("Why").setAutoWidth(true)
        queueGrid.addColumn(FacebookArticleImageQueueRow::attemptCount)
            .setHeader("Attempts").setAutoWidth(true)
        queueGrid.addColumn { WarsawTime.dateTime(it.nextAttemptAt) }
            .setHeader("Eligible from").setAutoWidth(true)
        queueGrid.addColumn { it.lastError ?: "—" }
            .setHeader("Last error").setFlexGrow(1)
        queueGrid.addComponentColumn { row ->
            Button("Generate now") { generateNow(row.articleId) }.apply {
                isEnabled = generation.isEnabled()
            }
        }.setHeader("Action").setAutoWidth(true)
    }

    private fun thumbnail(row: FacebookArticleImageStatusRow): Component {
        val preview: Component = when (row.status) {
            "GENERATED", FacebookArticleImageGenerationRepository.GENERATED_FOR_FACEBOOK ->
                Image("/generated-images/${row.articleId}.png", "AI-generated illustration")
            "SOURCE_THUMBNAIL", "MISSING_THUMBNAIL", "GENERATING", "FAILED" -> row.sourceThumbnail
                ?.takeIf { FacebookArticleImageGenerationRepository.hasUsableSourceThumbnail(it) }
                ?.let { Image("/rss/image/${row.articleId}", "Source thumbnail") } ?: Span("—")
            else -> Span("—")
        }
        if (preview is Image) {
            preview.setWidth("96px")
            preview.setHeight("50px")
            preview.element.setAttribute("loading", "lazy")
            preview.element.style.set("object-fit", "cover")
            preview.element.style.set("border-radius", "4px")
        }
        return preview
    }

    private fun generateNow(articleId: Long) {
        val started = generation.generateNow(articleId)
        val notification = Notification.show(
            if (started) "Thumbnail generation started for article $articleId."
            else "Could not start generation. Another image may be in progress, or the article has no text to illustrate.",
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
        pageNumber.update(page, lastPage + 1)
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
        refreshQueue()
    }

    private fun refreshQueue() {
        val waiting = jobs.queuedCount()
        val generating = jobs.countStatuses("GENERATING")
        val lastPage = if (waiting == 0L) 0 else ((waiting - 1) / PAGE_SIZE).toInt()
        queuePage = queuePage.coerceAtMost(lastPage)
        queueGrid.setItems(jobs.queuePage(PAGE_SIZE, queuePage * PAGE_SIZE))
        queueSummary.text = "$waiting waiting · $generating generating"
        queuePageInfo.text = "Page ${queuePage + 1} of ${lastPage + 1} · $waiting waiting"
        queuePageNumber.update(queuePage, lastPage + 1)
        queuePrevious.isEnabled = queuePage > 0
        queueNext.isEnabled = queuePage < lastPage
    }

    private fun changePage(direction: Int) {
        page = (page + direction).coerceAtLeast(0)
        refresh()
    }

    private fun changeQueuePage(direction: Int) {
        queuePage = (queuePage + direction).coerceAtLeast(0)
        refreshQueue()
    }

    internal companion object {
        const val PAGE_SIZE = 50
        /** Filter labels; each row's own label says more on hover, see [rowLabel]. */
        val STATUS_LABELS = linkedMapOf(
            "All" to "",
            "Source thumbnail" to "SOURCE_THUMBNAIL",
            "Waiting" to "MISSING_THUMBNAIL",
            "Generating" to "GENERATING",
            "AI image" to "GENERATED",
            "AI for Facebook only" to FacebookArticleImageGenerationRepository.GENERATED_FOR_FACEBOOK,
            "Failed" to "FAILED",
        )

        /** A short status for the grid, with the full explanation shown on hover. */
        data class StatusLabel(val text: String, val description: String)

        fun rowLabel(row: FacebookArticleImageStatusRow): StatusLabel {
            val hasSource = FacebookArticleImageGenerationRepository.hasUsableSourceThumbnail(row.sourceThumbnail)
            return when (row.status) {
                "SOURCE_THUMBNAIL" -> StatusLabel("Source thumbnail", "Uses the article's own thumbnail on the site and Facebook.")
                "MISSING_THUMBNAIL" -> waitingReason(hasSource, row.pageRejectedSourceThumbnail).let {
                    StatusLabel("Waiting: ${it.text}", "Waiting for AI image: ${it.description}")
                }
                "GENERATING" -> StatusLabel("Generating", "Generating AI image.")
                "GENERATED" -> if (hasSource) {
                    StatusLabel("AI image (unused)", "AI image ready; the site shows the source thumbnail.")
                } else {
                    StatusLabel("AI image", "AI image ready; shown on the site because the article has no thumbnail.")
                }
                FacebookArticleImageGenerationRepository.GENERATED_FOR_FACEBOOK -> StatusLabel(
                    "AI for Facebook only",
                    "AI image for Facebook only: Facebook rejected the source thumbnail; the site shows the source thumbnail.",
                )
                "FAILED" -> StatusLabel("Failed", "AI image failed: retry with Generate now.")
                else -> StatusLabel(row.status, row.status)
            }
        }

        /** Why an article waits for an AI image even though the queue has one status for it. */
        fun waitingReason(hasSourceThumbnail: Boolean, pageRejectedSourceThumbnail: Boolean): StatusLabel = when {
            !hasSourceThumbnail -> StatusLabel("no thumbnail", "the article has no thumbnail.")
            pageRejectedSourceThumbnail -> StatusLabel(
                "Facebook rejected",
                "Facebook rejected the article thumbnail for the Page post; the site keeps showing it.",
            )
            else -> StatusLabel("redraw", "redrawing the AI image after the article changed.")
        }

        private fun withTooltip(label: StatusLabel): Span = Span(label.text).apply {
            element.setAttribute("title", label.description)
            element.style.set("cursor", "help")
            element.style.set("white-space", "nowrap")
        }
    }
}
