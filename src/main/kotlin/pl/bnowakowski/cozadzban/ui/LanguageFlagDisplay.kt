// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache

internal fun languageFlag(cache: LanguageFlagCache, language: String): Component {
    val path = cache.flagPath(language)
    return if (path != null) {
        Image(path, "").apply {
            setWidth("1.5rem")
            setHeight("1rem")
            element.setAttribute("aria-hidden", "true")
        }
    } else {
        Span(cache.flagEmoji(language)).apply {
            element.setAttribute("aria-hidden", "true")
        }
    }
}

internal fun languageWithFlag(cache: LanguageFlagCache, language: String): HorizontalLayout =
    HorizontalLayout(languageFlag(cache, language), Span(language)).apply {
        isPadding = false
        defaultVerticalComponentAlignment = Alignment.CENTER
    }

internal fun flaggedLanguageText(cache: LanguageFlagCache, language: String): String =
    "${cache.flagEmoji(language)} $language"
