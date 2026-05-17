// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArticleInputTest {

    @Test
    fun `facebook import marker quote is removed regardless of case whitespace or translation`() {
        assertNull(" Co\u00A0za   ZJEB ".withoutFacebookImportMarkerQuote())
        assertNull("Facebook shell Co za zjeb What a fucker Hide Original".withoutFacebookImportMarkerQuote())
        assertNull("WHAT   A   FUCKER".withoutFacebookImportMarkerQuote())
    }

    @Test
    fun `ordinary quote is preserved and trimmed`() {
        assertEquals("ordinary article quote", " ordinary article quote ".withoutFacebookImportMarkerQuote())
    }
}
