// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

/** Append operator guidance to both the bounded title and bounded article text in image prompts. */
internal fun appendImageDirection(base: String, note: String?): String {
    val direction = note.orEmpty()
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)
    return if (direction.isEmpty()) base else "$base | Image direction: $direction"
}
