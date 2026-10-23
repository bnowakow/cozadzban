// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.dependency.JsModule
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleStatistics
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPageStatisticsRepository
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties

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
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val language = ComboBox<String>("Language")
    private val sort = ComboBox<String>("Sort by")
    private val pageSummary = VerticalLayout()
    private val grid = Grid(FacebookArticleStatistics::class.java, false)
    private val pageInfo = Span()
    private val previous = Button("Previous") { changePage(-1) }
    private val next = Button("Next") { changePage(1) }
    private var page = 0

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        sharedContentOffset()
        language.setItems(listOf("All") + articles.findDistinctLanguages())
        language.value = "All"
        sort.setItems("Story views", "Facebook reactions", "Newest")
        sort.value = "Story views"
        language.addValueChangeListener { page = 0; refreshArticles() }
        sort.addValueChangeListener { page = 0; refreshArticles() }
        configureGrid()
        val filters = HorizontalLayout(language, sort, Button("Refresh") { refreshAll() })
        filters.setWidthFull()
        pageSummary.isPadding = false
        val navigation = HorizontalLayout(previous, pageInfo, next)
        val content = sharedPageContent(
            Span("Story and Facebook statistics"),
            Span("Story views are browser page loads after bot filtering. Facebook reactions are the latest successful count; they remain the link-selection signal."),
            Span("Current Pages"), pageSummary, filters, grid, navigation,
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
        grid.addColumn(FacebookArticleStatistics::language).setHeader("Language").setAutoWidth(true)
        grid.addColumn(FacebookArticleStatistics::storyViews).setHeader("Views total").setAutoWidth(true)
        grid.addColumn(FacebookArticleStatistics::storyViews7Days).setHeader("Views 7 days").setAutoWidth(true)
        grid.addColumn { it.reactionCount?.toString() ?: "—" }.setHeader("FB reactions").setAutoWidth(true)
        grid.addColumn { it.reactionMeasuredAt?.toString()?.take(16) ?: "—" }.setHeader("Reactions measured (UTC)").setAutoWidth(true)
        grid.addColumn { it.publicationStatus ?: "No Page post" }.setHeader("Post status").setAutoWidth(true)
        grid.addColumn { it.linkCommentStatus ?: "—" }.setHeader("Link comment").setAutoWidth(true)
        grid.addComponentColumn { row ->
            row.postUrl?.let { Anchor(it, "Open post") } ?: Span("—")
        }.setHeader("Facebook").setAutoWidth(true)
    }

    private fun refreshAll() {
        pageSummary.removeAll()
        val pages = statistics.pageStatistics()
        if (pages.isEmpty()) pageSummary.add(Span("No Facebook Pages are connected."))
        pages.forEach { item ->
            pageSummary.add(Span(
                "${item.pageName} (${item.language}): ${item.publishedPosts} published posts · " +
                    "${item.storyViews} story views (${item.storyViews7Days} in 7 days) · " +
                    "${item.latestReactionTotal} latest reactions · " +
                    "${item.confirmedLinksThisMonth}/${item.monthlyLinkLimit} confirmed links this month",
            ))
        }
        refreshArticles()
    }

    private fun refreshArticles() {
        val selectedLanguage = language.value?.takeUnless { it == "All" }.orEmpty()
        val selectedSort = when (sort.value) {
            "Facebook reactions" -> "REACTIONS"
            "Newest" -> "NEWEST"
            else -> "VIEWS"
        }
        val total = statistics.articleCount(selectedLanguage)
        val lastPage = if (total == 0L) 0 else ((total - 1) / PAGE_SIZE).toInt()
        page = page.coerceAtMost(lastPage)
        grid.setItems(statistics.articleStatistics(selectedLanguage, selectedSort, PAGE_SIZE, page * PAGE_SIZE))
        pageInfo.text = "Page ${page + 1} of ${lastPage + 1} · $total articles"
        previous.isEnabled = page > 0
        next.isEnabled = page < lastPage
    }

    private fun changePage(direction: Int) {
        page = (page + direction).coerceAtLeast(0)
        refreshArticles()
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
