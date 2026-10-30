// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.ui

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document

/** JSON-LD contains only public information also presented on the page. */
object SearchStructuredData {
    private val json = ObjectMapper()

    fun append(doc: Document, value: Map<String, Any>) {
        // JSON strings can contain HTML's script terminator; JSON escaping must also
        // protect the surrounding HTML raw-text element.
        val serialized = json.writeValueAsString(value)
            .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
        doc.head().appendElement("script").attr("type", "application/ld+json")
            .appendChild(DataNode(serialized))
    }
}
