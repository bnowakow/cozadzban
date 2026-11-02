// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.textfield.IntegerField

/** A one-based page entry shared by the site's paginated views. */
internal fun interface PageNavigator : java.io.Serializable {
    fun navigate(page: Int)
}

internal class PageNumberInput(private val navigate: PageNavigator) : HorizontalLayout() {
    private val number = IntegerField()
    private var pageCount = 1

    init {
        isPadding = false
        defaultVerticalComponentAlignment = Alignment.CENTER
        number.setWidth("5rem")
        number.element.setAttribute("aria-label", "Page number")
        number.setMin(1)
        val goButton = Button("Go") { go() }
        goButton.addClickShortcut(Key.ENTER).listenOn(number)
        add(Span("Go to page"), number, goButton)
    }

    fun update(currentPage: Int, totalPages: Int) {
        pageCount = totalPages.coerceAtLeast(1)
        number.setMax(pageCount)
        number.value = currentPage + 1
        number.isInvalid = false
    }

    private fun go() {
        val requested = number.value
        if (requested == null || requested !in 1..pageCount) {
            number.errorMessage = "Enter a page from 1 to $pageCount"
            number.isInvalid = true
            return
        }
        number.isInvalid = false
        navigate.navigate(requested - 1)
    }
}
