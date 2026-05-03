// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.provider.SortDirection
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.auth.AnonymousAllowed
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleRepository

@Route("")
@AnonymousAllowed
class ArticleListView(
    private val articleRepository: ArticleRepository,
) : VerticalLayout() {

    private val pageSizes = listOf(10, 20, 40, 60, 80, 100)
    private var pageSize = 20

    private val totalInfo = Span()

    private val grid = Grid(Article::class.java, false)

    private val dataProvider = DataProvider.fromCallbacks<Article>(
        { query ->
            val requestedLimit = query.limit.coerceAtLeast(1)
            val requestedOffset = query.offset
            val page = requestedOffset / requestedLimit

            val sortOrder = query.sortOrders.firstOrNull()
            val sortField = sortOrder?.sorted ?: "createdAt"
            val sortDirection = if (sortOrder?.direction == SortDirection.ASCENDING) "asc" else "desc"

            articleRepository.findPage(page, requestedLimit, sortField, sortDirection).stream()
        },
        { _ -> articleRepository.count().toInt() },
    )

    init {
        setSizeFull()

        val title = H1("Co za zjeb")

        val pageSizeSelect = Select<Int>()
        pageSizeSelect.label = "Page size"
        pageSizeSelect.setItems(pageSizes)
        pageSizeSelect.value = pageSize
        pageSizeSelect.addValueChangeListener { event ->
            pageSize = event.value ?: 20
            grid.setPageSize(pageSize)
            refreshData()
        }

        val controls = HorizontalLayout(pageSizeSelect, totalInfo)
        controls.defaultVerticalComponentAlignment = Alignment.END

        grid.addColumn(Article::id)
            .setHeader("ID")
            .setKey("id")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn { it.createdAt?.toString().orEmpty() }
            .setHeader("Created")
            .setKey("createdAt")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn(Article::language)
            .setHeader("Language")
            .setKey("language")
            .setSortable(true)
            .setAutoWidth(true)
        grid.addColumn(Article::title)
            .setHeader("Title")
            .setKey("title")
            .setSortable(true)
            .setFlexGrow(1)
        grid.addColumn(Article::url)
            .setHeader("URL")
            .setKey("url")
            .setSortable(true)
            .setFlexGrow(1)

        grid.addItemClickListener { event ->
            grid.element.executeJs("window.open($0, '_blank', 'noopener')", event.item.url)
        }

        grid.dataProvider = dataProvider
        grid.setPageSize(pageSize)
        grid.setAllRowsVisible(true)
        grid.setSizeFull()

        refreshData()
        add(title, controls, grid)
        expand(grid)
    }

    private fun refreshData() {
        val totalElements = articleRepository.count()
        totalInfo.text = "Total articles: $totalElements"
        dataProvider.refreshAll()
    }
}
