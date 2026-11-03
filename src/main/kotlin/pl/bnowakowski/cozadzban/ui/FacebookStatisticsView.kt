// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleStatistics
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageStatisticsRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookSourcePostReference
import pl.bnowakowski.cozadzban.facebookpublish.FacebookSourceProperties
import pl.bnowakowski.cozadzban.facebookpublish.FacebookSourceReactionRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookSourceReactionQueueRow
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache
import java.time.Instant

@Route("admin/facebook-statistics")
@PageTitle("Story and Facebook statistics")
@RolesAllowed("ADMIN")
@JsModule("@vaadin/combo-box/src/vaadin-combo-box.js")
@JsModule("@vaadin/grid/src/vaadin-grid.js")
@Uses(ComboBox::class)
@Uses(Grid::class)
class FacebookStatisticsView(
    private val statistics: FacebookPageStatisticsRepository,
    articles: ArticleRepository,
    private val languageFlagCache: LanguageFlagCache,
    private val facebookSourceProperties: FacebookSourceProperties,
    private val sourceReactions: FacebookSourceReactionRepository,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val language = ComboBox<String>("Language")
    private val languageFlagPrefix = Span()
    private val sort = ComboBox<String>("Sort by")
    private val pageSummary = VerticalLayout()
    private val grid = Grid(FacebookArticleStatistics::class.java, false)
    private val pageInfo = Span()
    private val pageNumber = PageNumberInput { target -> page = target; refreshArticles() }
    private val previous = Button("Previous") { changePage(-1) }
    private val next = Button("Next") { changePage(1) }
    private val queueSummary = Span()
    private val queueGrid = Grid(FacebookSourceReactionQueueRow::class.java, false)
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
        language.setItems(listOf("All") + articles.findDistinctLanguages())
        language.setRenderer(ComponentRenderer { code: String ->
            if (code == "All") HorizontalLayout(Span(code)).apply { isPadding = false } else languageWithFlag(languageFlagCache, code)
        })
        languageFlagPrefix.isVisible = false
        language.setPrefixComponent(languageFlagPrefix)
        language.value = "All"
        sort.setItems("Story views", "Our FB reactions", "Source FB reactions", "Newest")
        sort.value = "Story views"
        language.addValueChangeListener {
            languageFlagPrefix.removeAll()
            val selected = it.value?.takeUnless { code -> code == "All" }
            languageFlagPrefix.isVisible = selected != null
            if (selected != null) languageFlagPrefix.add(languageFlag(languageFlagCache, selected))
            page = 0
            refreshArticles()
        }
        sort.addValueChangeListener { page = 0; refreshArticles() }
        configureGrid()
        configureQueueGrid()
        val retryAll = Button("Retry all failed source checks") { retryAllFailed() }
        retryAll.isEnabled = facebookSourceProperties.configured
        val filters = HorizontalLayout(language, sort, Button("Refresh") { refreshAll() }, retryAll)
        filters.setWidthFull()
        filters.element.style.set("flex-wrap", "wrap")
        filters.element.style.set("align-items", "flex-end")
        pageSummary.isPadding = false
        val navigation = paginationNavigation(previous, pageInfo, pageNumber, next)
        val queueNavigation = paginationNavigation(queuePrevious, queuePageInfo, queuePageNumber, queueNext)
        val queueToolbar = HorizontalLayout(queueSummary, Button("Refresh queue") { refreshQueue() }).apply {
            defaultVerticalComponentAlignment = Alignment.CENTER
            element.style.set("flex-wrap", "wrap")
        }
        val content = sharedPageContent(
            Span("Story and Facebook statistics"),
            Span("Story views are browser page loads after bot filtering. Our FB reactions are the latest successful count and remain the link-selection signal. Source FB reactions are measured separately for public Page post sources."),
            Span(if (facebookSourceProperties.configured)
                "Source reaction checks run in the background; Refresh displays saved results. Failed checks become eligible for another attempt after six hours; use Retry to queue them sooner. Public Page access depends on Meta approval."
                else "Source reaction checks are off until the separate Meta app ID and secret are configured."),
            Span("Current Pages"), pageSummary, filters, grid, navigation,
            H2("Source reaction check queue"),
            Span("All languages: first checks, failed retries, and due refreshes. Scheduled retries show when they become eligible."),
            queueToolbar, queueGrid, queueNavigation,
        )
        content.element.style.set("min-height", "0")
        content.element.style.set("overflow-y", "auto")
        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.FACEBOOK_STATISTICS,
                isAdmin = true,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
        expand(content)
        refreshAll()
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
        }.setHeader("Article").setAutoWidth(true).setFlexGrow(2)
        grid.addComponentColumn { row -> languageWithFlag(languageFlagCache, row.language) }.setHeader("Language").setAutoWidth(true)
        grid.addColumn(FacebookArticleStatistics::storyViews).setHeader("Views total").setAutoWidth(true)
        grid.addColumn(FacebookArticleStatistics::storyViews7Days).setHeader("Views 7 days").setAutoWidth(true)
        grid.addColumn { it.reactionCount?.toString() ?: "—" }.setHeader("Our FB reactions").setAutoWidth(true)
        grid.addColumn { it.reactionMeasuredAt?.let(WarsawTime::dateTime) ?: "—" }.setHeader("Our reactions measured").setAutoWidth(true)
        grid.addComponentColumn { row ->
            if (FacebookSourcePostReference.fromUrl(row.sourceUrl) == null) Span("—")
            else Anchor(row.sourceUrl, "Open original").apply { setTarget("_blank") }
        }.setHeader("Source post").setAutoWidth(true)
        grid.addColumn { row ->
            if (FacebookSourcePostReference.fromUrl(row.sourceUrl) == null) "—"
            else row.sourceReactionCount?.toString() ?: "—"
        }.setHeader("Source FB reactions").setAutoWidth(true)
        grid.addColumn { it.sourceReactionMeasuredAt?.let(WarsawTime::dateTime) ?: "—" }
            .setHeader("Source measured").setAutoWidth(true)
        grid.addComponentColumn { row ->
            val status = Span(sourceStatus(row))
            if (!facebookSourceProperties.configured || row.sourceReactionStatus != "FAILED" ||
                row.sourceReactionNextAttemptAt?.isAfter(Instant.now()) != true) status
            else HorizontalLayout(status, Button("Retry") { retryFailed(row.articleId) }).apply {
                isPadding = false
                defaultVerticalComponentAlignment = Alignment.CENTER
                element.style.set("flex-wrap", "wrap")
            }
        }.setHeader("Source status").setAutoWidth(true)
        grid.addColumn { it.publicationStatus ?: "No Page post" }.setHeader("Post status").setAutoWidth(true)
        grid.addColumn { it.linkCommentStatus ?: "—" }.setHeader("Link comment").setAutoWidth(true)
        grid.addComponentColumn { row ->
            row.postUrl?.let { Anchor(it, "Open post") } ?: Span("—")
        }.setHeader("Facebook").setAutoWidth(true)
    }

    private fun configureQueueGrid() {
        queueGrid.setWidthFull()
        queueGrid.setHeight("22rem")
        queueGrid.setMinHeight("14rem")
        queueGrid.addClassName("czj-admin-grid")
        queueGrid.addComponentColumn { row ->
            Anchor("/stories/${row.articleId}", "${row.articleId} · ${row.title?.take(90) ?: "Untitled"}").apply {
                element.setAttribute("router-ignore", true)
            }
        }.setHeader("Article").setAutoWidth(true).setFlexGrow(2)
        queueGrid.addComponentColumn { row -> languageWithFlag(languageFlagCache, row.language) }
            .setHeader("Language").setAutoWidth(true)
        queueGrid.addComponentColumn { row -> Anchor(row.sourceUrl, "Open original").apply { setTarget("_blank") } }
            .setHeader("Source post").setAutoWidth(true)
        queueGrid.addColumn { row -> queueStatus(row) }.setHeader("Check").setAutoWidth(true)
        queueGrid.addColumn { row ->
            row.nextAttemptAt?.takeIf { it.isAfter(Instant.now()) }?.let(WarsawTime::dateTime) ?: "Now"
        }.setHeader("Eligible at").setAutoWidth(true)
        queueGrid.addColumn { row -> row.lastAttemptAt?.let(WarsawTime::dateTime) ?: "—" }
            .setHeader("Last attempt").setAutoWidth(true)
        queueGrid.addComponentColumn { row ->
            Span(row.error ?: "—").apply {
                element.style.set("display", "block")
                element.style.set("white-space", "normal")
                element.style.set("overflow-wrap", "anywhere")
            }
        }.setHeader("Last error").setWidth("20rem").setFlexGrow(1)
        queueGrid.addComponentColumn { row ->
            if (facebookSourceProperties.configured && row.status == "FAILED" &&
                row.nextAttemptAt?.isAfter(Instant.now()) == true) {
                Button("Queue retry") { retryFailed(row.articleId) }
            } else Span("—")
        }.setHeader("Action").setAutoWidth(true)
    }

    private fun refreshAll() {
        pageSummary.removeAll()
        val pages = statistics.pageStatistics()
        if (pages.isEmpty()) pageSummary.add(Span("No Facebook Pages are connected."))
        pages.forEach { item ->
            pageSummary.add(Span(
                "${item.pageName} (${flaggedLanguageText(languageFlagCache, item.language)}): ${item.publishedPosts} published posts · " +
                    "${item.storyViews} story views (${item.storyViews7Days} in 7 days) · " +
                    "${item.latestReactionTotal} latest reactions · " +
                    "${item.confirmedLinksThisMonth}/${item.monthlyLinkLimit} confirmed links this month",
            ))
        }
        refreshArticles()
        refreshQueue()
    }

    private fun queueStatus(row: FacebookSourceReactionQueueRow): String = when {
        row.status == null -> "First fetch queued"
        row.status == "FAILED" && row.nextAttemptAt?.isAfter(Instant.now()) == true -> "Retry scheduled"
        row.status == "FAILED" -> "Retry queued"
        row.status == "SUCCESS" -> "Refresh queued"
        else -> "Recheck queued"
    }

    private fun sourceStatus(row: FacebookArticleStatistics): String = when {
        FacebookSourcePostReference.fromUrl(row.sourceUrl) == null -> "—"
        !facebookSourceProperties.configured -> "Not configured"
        row.sourceReactionStatus == "SUCCESS" -> "Measured"
        row.sourceReactionStatus == "FAILED" &&
            row.sourceReactionNextAttemptAt?.isAfter(Instant.now()) == false ->
            "Retry queued · previous error: ${row.sourceReactionError ?: "unknown error"}"
        row.sourceReactionStatus == "FAILED" -> "Check failed: ${row.sourceReactionError ?: "unknown error"}"
        row.sourceReactionStatus == "UNSUPPORTED" -> "Unsupported link"
        else -> "Pending"
    }

    private fun retryFailed(articleId: Long) {
        val queued = sourceReactions.retryFailed(articleId)
        Notification.show(
            if (queued) "Source check queued for article $articleId."
            else "This source check is already queued or is no longer failed.",
            5000, Notification.Position.MIDDLE,
        )
        refreshArticles()
        refreshQueue()
    }

    private fun retryAllFailed() {
        val queued = sourceReactions.retryAllFailed()
        Notification.show(
            if (queued == 0) "No waiting failed source checks to queue."
            else "Queued $queued failed source checks. The background worker checks them in batches of up to five.",
            5000, Notification.Position.MIDDLE,
        )
        refreshArticles()
        refreshQueue()
    }

    private fun refreshArticles() {
        val selectedLanguage = language.value?.takeUnless { it == "All" }.orEmpty()
        val selectedSort = when (sort.value) {
            "Our FB reactions" -> "REACTIONS"
            "Source FB reactions" -> "SOURCE_REACTIONS"
            "Newest" -> "NEWEST"
            else -> "VIEWS"
        }
        val total = statistics.articleCount(selectedLanguage)
        val lastPage = if (total == 0L) 0 else ((total - 1) / PAGE_SIZE).toInt()
        page = page.coerceAtMost(lastPage)
        grid.setItems(statistics.articleStatistics(selectedLanguage, selectedSort, PAGE_SIZE, page * PAGE_SIZE))
        pageInfo.text = "Page ${page + 1} of ${lastPage + 1} · $total articles"
        pageNumber.update(page, lastPage + 1)
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
    }

    private fun changePage(direction: Int) {
        page = (page + direction).coerceAtLeast(0)
        refreshArticles()
    }

    private fun refreshQueue() {
        val now = Instant.now()
        val total = sourceReactions.queueCount(now)
        val lastPage = if (total == 0L) 0 else ((total - 1) / QUEUE_PAGE_SIZE).toInt()
        queuePage = queuePage.coerceAtMost(lastPage)
        queueGrid.setItems(sourceReactions.queuePage(now, QUEUE_PAGE_SIZE, queuePage * QUEUE_PAGE_SIZE))
        queueSummary.text = if (total == 0L) "No source reaction checks are waiting."
            else "$total articles awaiting a source check or retry"
        queuePageInfo.text = "Page ${queuePage + 1} of ${lastPage + 1} · $total queued"
        queuePageNumber.update(queuePage, lastPage + 1)
        queuePrevious.isEnabled = queuePage > 0
        queueNext.isEnabled = queuePage < lastPage
    }

    private fun changeQueuePage(direction: Int) {
        queuePage = (queuePage + direction).coerceAtLeast(0)
        refreshQueue()
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val QUEUE_PAGE_SIZE = 25
    }
}
