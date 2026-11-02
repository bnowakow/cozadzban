// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.bnowakowski.cozadzban.facebookpublish.FacebookArticleImageStatusRow
import pl.bnowakowski.cozadzban.ui.FacebookThumbnailStatusView.Companion.StatusLabel

class FacebookThumbnailStatusLabelTest {
    private val thumbnail = "https://lookaside.fbsbx.com/lookaside/crawler/media/?media_id=1"

    private fun row(status: String, thumbnail: String? = null, pageRejected: Boolean = false) = FacebookArticleImageStatusRow(
        articleId = 574,
        title = "Title",
        language = "pl",
        sourceThumbnail = thumbnail,
        status = status,
        attemptCount = 0,
        nextAttemptAt = null,
        lastError = null,
        updatedAt = null,
        pageRejectedSourceThumbnail = pageRejected,
    )

    private fun label(row: FacebookArticleImageStatusRow) = FacebookThumbnailStatusView.rowLabel(row)

    @Test
    fun `waiting rows say briefly why the article needs an AI image, with details on hover`() {
        assertEquals(
            StatusLabel("Waiting: no thumbnail", "Waiting for AI image: the article has no thumbnail."),
            label(row("MISSING_THUMBNAIL")),
        )
        assertEquals(
            StatusLabel(
                "Waiting: Facebook rejected",
                "Waiting for AI image: Facebook rejected the article thumbnail for the Page post; the site keeps showing it.",
            ),
            label(row("MISSING_THUMBNAIL", thumbnail, pageRejected = true)),
        )
        assertEquals("Waiting: redraw", label(row("MISSING_THUMBNAIL", thumbnail)).text)
    }

    @Test
    fun `other statuses are short, with the full explanation on hover`() {
        assertEquals("Source thumbnail", label(row("SOURCE_THUMBNAIL")).text)
        assertEquals("Generating", label(row("GENERATING")).text)
        assertEquals(StatusLabel("AI image", "AI image ready; shown on the site because the article has no thumbnail."), label(row("GENERATED")))
        assertEquals(
            StatusLabel("AI image (unused)", "AI image ready; the site shows the source thumbnail."),
            label(row("GENERATED", "https://example.com/photo.jpg")),
        )
        assertEquals(
            StatusLabel(
                "AI for Facebook only",
                "AI image for Facebook only: Facebook rejected the source thumbnail; the site shows the source thumbnail.",
            ),
            label(row("GENERATED_FOR_FACEBOOK", thumbnail, pageRejected = true)),
        )
        assertEquals(StatusLabel("Failed", "AI image failed: retry with Generate now."), label(row("FAILED")))
    }
}
