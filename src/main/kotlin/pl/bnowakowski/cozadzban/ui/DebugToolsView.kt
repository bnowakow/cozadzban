// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.RolesAllowed
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportJobService
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportProperties
import pl.bnowakowski.cozadzban.facebookpublish.FacebookPostLookupService

@Route("admin/debug-tools")
@PageTitle("Debug tools")
@RolesAllowed("ADMIN")
@CssImport("./styles/cozadzban-feed.css")
class DebugToolsView(
    private val postLookup: FacebookPostLookupService,
    private val facebookImportJobService: FacebookImportJobService? = null,
    private val facebookImportProperties: FacebookImportProperties = FacebookImportProperties(),
) : VerticalLayout() {
    private val postUrl = TextField("Facebook post URL")
    private val result = VerticalLayout()

    init {
        installCozadzbanThemeBootstrap()
        setSizeFull()
        sharedContentOffset()

        postUrl.setWidthFull()
        postUrl.placeholder = "https://www.facebook.com/permalink.php?story_fbid=...&id=..."
        postUrl.isClearButtonVisible = true
        val find = Button("Find article") { showMatch() }
        find.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        val form = HorizontalLayout(postUrl, find)
        form.setWidthFull()
        form.defaultVerticalComponentAlignment = Alignment.END
        form.expand(postUrl)

        result.isPadding = false
        val content = sharedPageContent(
            H2("Debug tools"),
            Span("Find the Co Za Dzban story published from a Facebook post. The publisher article is shown when its URL is stored in the article record."),
            form,
            result,
        )
        add(
            buildCozadzbanTopBar(
                currentPage = CozadzbanTopBarPage.DEBUG_TOOLS,
                isAdmin = true,
                facebookImportJobService = facebookImportJobService,
                facebookImportProperties = facebookImportProperties,
            ),
            content,
        )
        expand(content)
    }

    private fun showMatch() {
        result.removeAll()
        try {
            val match = postLookup.findArticle(postUrl.value)
            if (match == null) {
                result.add(Span("No matching article was found. A private post or an unavailable Facebook embed may prevent lookup."))
                return
            }
            result.add(Span(match.title ?: "Article ${match.articleId}"))
            result.add(Anchor(match.storyUrl, "Co Za Dzban story ${match.articleId}"))
            val original = match.originalArticleUrl
            if (original != null) {
                result.add(Anchor(original, "Publisher article").apply {
                    element.setAttribute("target", "_blank")
                    element.setAttribute("rel", "noopener noreferrer")
                })
            } else {
                result.add(Span("Publisher article link: not available in the stored record."))
            }
        } catch (ex: IllegalArgumentException) {
            result.add(Span(ex.message ?: "Enter a valid Facebook post URL"))
        }
    }
}
