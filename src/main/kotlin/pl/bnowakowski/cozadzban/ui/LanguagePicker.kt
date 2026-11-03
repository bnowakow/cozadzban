// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.renderer.ComponentRenderer
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.enrichment.LanguageFlagCache

internal class LanguagePicker(
    flagCache: LanguageFlagCache,
    usedLanguages: List<String>,
    initialLanguage: String,
) : VerticalLayout() {
    private val addNewOption = "Add a new language…"
    private val existingLanguages = (usedLanguages + initialLanguage)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    private val languageChoice = ComboBox<String>("Language")
    private val newLanguageField = TextField("New language (BCP-47)")

    init {
        isPadding = false
        isSpacing = false
        setWidthFull()

        languageChoice.setWidthFull()
        languageChoice.setItems(existingLanguages + addNewOption)
        languageChoice.setRenderer(ComponentRenderer { code: String ->
            if (code == addNewOption) HorizontalLayout(Span(code))
            else languageWithFlag(flagCache, code)
        })
        val selectedFlag = Span()
        languageChoice.setPrefixComponent(selectedFlag)
        languageChoice.addValueChangeListener { event ->
            languageChoice.isInvalid = false
            newLanguageField.isInvalid = false
            val selected = event.value?.takeUnless { it == addNewOption }
            selectedFlag.removeAll()
            selectedFlag.isVisible = selected != null
            if (selected != null) selectedFlag.add(languageFlag(flagCache, selected))
            newLanguageField.isVisible = event.value == addNewOption
        }

        newLanguageField.setWidthFull()
        newLanguageField.placeholder = "e.g. en, pl, de"
        newLanguageField.isVisible = false
        newLanguageField.addValueChangeListener { newLanguageField.isInvalid = false }
        add(languageChoice, newLanguageField)
        if (initialLanguage.isNotBlank()) languageChoice.value = initialLanguage
    }

    fun selectedLanguage(): String {
        val selected = languageChoice.value ?: run {
            languageChoice.errorMessage = "Select a language"
            languageChoice.isInvalid = true
            throw IllegalArgumentException("Select a language")
        }
        val requested = if (selected == addNewOption) {
            newLanguageField.value.trim().ifBlank {
                newLanguageField.errorMessage = "Enter a new language"
                newLanguageField.isInvalid = true
                throw IllegalArgumentException("Enter a new language")
            }
        } else {
            selected
        }
        val normalized = try {
            ArticleService.normalizeLanguage(requested)
        } catch (ex: IllegalArgumentException) {
            if (selected == addNewOption) {
                newLanguageField.errorMessage = ex.message ?: "Invalid language"
                newLanguageField.isInvalid = true
            } else {
                languageChoice.errorMessage = ex.message ?: "Invalid language"
                languageChoice.isInvalid = true
            }
            throw ex
        }
        if (selected == addNewOption && normalized in existingLanguages) {
            val message = "This language is already used; select it from the list"
            newLanguageField.errorMessage = message
            newLanguageField.isInvalid = true
            throw IllegalArgumentException(message)
        }
        return normalized
    }

    fun setReadOnly(readOnly: Boolean) {
        languageChoice.isReadOnly = readOnly
        newLanguageField.isReadOnly = readOnly
    }
}
