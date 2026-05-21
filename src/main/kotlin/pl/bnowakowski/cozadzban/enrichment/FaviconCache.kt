// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.enrichment

interface FaviconCache {
    fun cache(articleUrl: String, candidates: List<String>): String?
}

object NoopFaviconCache : FaviconCache {
    override fun cache(articleUrl: String, candidates: List<String>): String? = null
}
