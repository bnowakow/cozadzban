// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A Facebook post permalink, excluding reels, photos, Groups and profile-only links. */
data class FacebookSourcePostReference(val pageIdentifier: String, val postIdentifier: String) {
    val directGraphId: String?
        get() = if (pageIdentifier.all(Char::isDigit) && postIdentifier.all(Char::isDigit)) {
            "${pageIdentifier}_${postIdentifier}"
        } else null

    companion object {
        private val pagePart = Regex("[A-Za-z0-9._-]{1,100}")
        private val postPart = Regex("(?:[0-9]{1,30}|pfbid[A-Za-z0-9_-]{1,200})")

        fun fromUrl(url: String): FacebookSourcePostReference? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "facebook.com" && !host.endsWith(".facebook.com")) return null

            val path = uri.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
            val pair = when {
                path.size == 3 && path[1] == "posts" -> path[0] to path[2]
                path.size == 1 && path[0] in setOf("story.php", "permalink.php") -> {
                    val query = runCatching {
                        uri.rawQuery?.split('&')?.mapNotNull { item ->
                            val name = item.substringBefore('=', "")
                            val value = item.substringAfter('=', "")
                            if (name.isBlank() || value.isBlank()) null
                            else name to URLDecoder.decode(value, StandardCharsets.UTF_8)
                        }?.toMap()
                    }.getOrNull() ?: return null
                    (query["id"] ?: return null) to (query["story_fbid"] ?: return null)
                }
                else -> return null
            }
            if (!pagePart.matches(pair.first) || !postPart.matches(pair.second)) return null
            if (pair.first in setOf("groups", "share", "reel", "watch")) return null
            return FacebookSourcePostReference(pair.first, pair.second)
        }
    }
}
