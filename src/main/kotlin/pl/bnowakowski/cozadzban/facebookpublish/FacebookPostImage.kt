// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.stereotype.Component
import pl.bnowakowski.cozadzban.article.Article
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import javax.imageio.ImageIO

sealed interface FacebookPostImage {
    val source: String
    data class Thumbnail(val url: String) : FacebookPostImage { override val source = "THUMBNAIL" }
    data class Placeholder(val png: ByteArray) : FacebookPostImage { override val source = "PLACEHOLDER" }
}

/** Replace this component when article-text-based image generation is available. */
@Component
class FacebookPostImageProvider {
    fun forArticle(article: Article): FacebookPostImage {
        val uri = runCatching { URI(article.thumbnail?.trim().orEmpty()) }.getOrNull()
        if (uri != null && uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.host !in setOf("localhost", "127.0.0.1")) {
            return FacebookPostImage.Thumbnail(uri.toString())
        }
        return placeholder(article.language)
    }

    fun placeholder(language: String): FacebookPostImage.Placeholder {
        val canvas = BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB)
        val graphics = canvas.createGraphics()
        try {
            graphics.paint = GradientPaint(0f, 0f, Color(30, 36, 45), 1200f, 630f, Color(53, 64, 76))
            graphics.fillRect(0, 0, 1200, 630)
            graphics.color = Color(243, 107, 50)
            graphics.fillRoundRect(78, 92, 24, 446, 12, 12)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.font = Font("SansSerif", Font.BOLD, 96)
            graphics.color = Color.WHITE
            graphics.drawString("Co Za Dzban", 145, 326)
            graphics.font = Font("SansSerif", Font.PLAIN, 31)
            graphics.color = Color(233, 235, 238)
            graphics.drawString(if (language.startsWith("pl")) "Historie warte uwagi" else "Stories worth noticing", 150, 391)
        } finally {
            graphics.dispose()
        }
        return FacebookPostImage.Placeholder(ByteArrayOutputStream().also { ImageIO.write(canvas, "png", it) }.toByteArray())
    }
}
