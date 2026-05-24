// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object GzipTextCodec {
    fun compress(text: String?): ByteArray? {
        val value = text?.takeIf { it.isNotBlank() } ?: return null
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(value.toByteArray(StandardCharsets.UTF_8))
        }
        return output.toByteArray()
    }

    fun decompress(bytes: ByteArray?): String =
        bytes?.let {
            GZIPInputStream(ByteArrayInputStream(it)).use { gzip ->
                gzip.readBytes().toString(StandardCharsets.UTF_8)
            }
        }.orEmpty()
}
