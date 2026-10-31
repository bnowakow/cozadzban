// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns an untrusted, model-written SVG into a 1200x630 PNG. Only plain vector shapes are accepted:
 * no scripts, text, embedded images, foreign content, styles, event handlers or references outside
 * the document. Anything else is rejected rather than cleaned, and Batik never loads external
 * resources.
 */
internal object SvgIllustrationRenderer {
    const val WIDTH = 1200
    const val HEIGHT = 630
    private const val MAX_SVG_CHARS = 400_000

    private val ALLOWED_ELEMENTS = setOf(
        "svg", "g", "defs", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
        "linearGradient", "radialGradient", "stop", "clipPath", "mask", "pattern", "use", "title", "desc",
    )
    private val LOCAL_URL = Regex("""url\(\s*#[A-Za-z_][\w.-]*\s*\)""")

    /** The SVG document in a model response, from the first `<svg` to the last `</svg>`. */
    fun extract(response: String): String? {
        val start = response.indexOf("<svg")
        val end = response.lastIndexOf("</svg>")
        if (start < 0 || end < start) return null
        return response.substring(start, end + "</svg>".length).takeIf { it.length <= MAX_SVG_CHARS }
    }

    /** The reason [svg] is unsafe or unusable, or null when it may be rendered. */
    fun rejectionReason(svg: String): String? {
        if (svg.length > MAX_SVG_CHARS) return "SVG is too large"
        if (svg.contains("<!DOCTYPE", ignoreCase = true) || svg.contains("<!ENTITY", ignoreCase = true)) {
            return "SVG declares a DOCTYPE or entity"
        }
        val document = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(svg)))
        }.getOrElse { return "SVG is not well-formed XML" }
        val root = document.documentElement
        if (root.localName != "svg") return "Root element is not svg"
        return checkElement(root)
    }

    private fun checkElement(element: Element): String? {
        val name = element.localName ?: element.tagName
        if (name !in ALLOWED_ELEMENTS) return "Element <$name> is not allowed"
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i)
            val attributeName = (attribute.localName ?: attribute.nodeName).lowercase()
            val value = attribute.nodeValue.orEmpty()
            if (attribute.nodeName.startsWith("xmlns")) continue
            if (attributeName.startsWith("on") || attributeName == "style") return "Attribute $attributeName is not allowed"
            if (attributeName == "href" && !value.startsWith("#")) return "Only in-document references are allowed"
            if (value.contains("url(", ignoreCase = true) && LOCAL_URL.replace(value, "").contains("url(", ignoreCase = true)) {
                return "Only in-document url() references are allowed"
            }
            if (value.contains("javascript:", ignoreCase = true) || value.contains("data:", ignoreCase = true)) {
                return "Script or data URLs are not allowed"
            }
        }
        var child = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> checkElement(child as Element)?.let { return it }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE ->
                    if (name !in setOf("title", "desc") && child.nodeValue.isNotBlank()) return "Text content is not allowed"
                Node.COMMENT_NODE -> Unit
                else -> return "Unsupported XML node"
            }
            child = child.nextSibling
        }
        return null
    }

    /** Renders a safe SVG to a 1200x630 PNG, or returns null when it can't be rendered. */
    fun render(svg: String): ByteArray? {
        if (rejectionReason(svg) != null) return null
        return runCatching {
            val transcoder = PNGTranscoder().apply {
                addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, WIDTH.toFloat())
                addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, HEIGHT.toFloat())
                addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
                addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, false)
            }
            val output = ByteArrayOutputStream()
            transcoder.transcode(TranscoderInput(StringReader(svg)), TranscoderOutput(output))
            output.toByteArray().takeIf { png ->
                val image = ImageIO.read(ByteArrayInputStream(png))
                image != null && image.width == WIDTH && image.height == HEIGHT
            }
        }.getOrNull()
    }
}
