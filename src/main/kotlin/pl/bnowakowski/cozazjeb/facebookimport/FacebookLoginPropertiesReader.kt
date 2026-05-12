// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.facebookimport

import java.util.Properties

// Copied from facebook-post-commenter and made optional so normal app startup does not need the file.
class FacebookLoginPropertiesReader(fileName: String = "facebook.properties") {
    private val properties = Properties()

    init {
        this::class.java.classLoader.getResourceAsStream(fileName)?.use {
            properties.load(it)
        }
    }

    fun getProperty(key: String): String? = properties.getProperty(key)
}
