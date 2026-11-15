// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookimport

import java.net.HttpURLConnection
import java.net.URI

/** Resolves known short links before proposal deduplication and article enrichment. */
internal object ArticleShortLinkResolver {
    fun isShortLink(url: String): Boolean =
        runCatching { URI(url).host?.lowercase() in setOf("bit.ly", "bitly.com") }.getOrDefault(false)

    fun resolve(url: String, redirect: (URI) -> URI? = ::redirectLocation): String? {
        if (!isShortLink(url)) return null
        var current = runCatching { URI(url) }.getOrNull() ?: return null
        repeat(4) {
            if (!isShortLink(current.toString()) || current.scheme !in setOf("http", "https")) return null
            val next = redirect(current) ?: return null
            if (next.scheme != "https" || next.host.isNullOrBlank()) return null
            if (!isShortLink(next.toString())) return next.toString()
            current = next
        }
        return null
    }

    private fun redirectLocation(uri: URI): URI? {
        for (method in listOf("HEAD", "GET")) {
            val connection = try {
                uri.toURL().openConnection() as HttpURLConnection
            } catch (_: Exception) {
                return null
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 3_000
            connection.readTimeout = 5_000
            try {
                if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: continue
                    return runCatching { uri.resolve(location) }.getOrNull()
                }
            } catch (_: Exception) {
                // A server may reject HEAD while still permitting GET.
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
