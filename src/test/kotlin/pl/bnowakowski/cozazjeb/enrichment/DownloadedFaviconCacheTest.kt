// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.enrichment

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class DownloadedFaviconCacheTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `downloads favicon candidate and returns local cached URL`() {
        withFaviconServer(PNG_BYTES) { baseUrl ->
            val cache = DownloadedFaviconCache(RestClient.builder(), tempDir.toString())

            val result = cache.cache("$baseUrl/article", listOf("/favicon.png"))

            assertNotNull(result)
            assertTrue(result!!.startsWith("/favicons/"))
            assertTrue(result.endsWith(".png"))
            assertArrayEquals(PNG_BYTES, Files.readAllBytes(tempDir.resolve(result.removePrefix("/favicons/"))))
        }
    }

    @Test
    fun `falls back to later candidate when first favicon is not an image`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/not-image") { exchange ->
            val body = "not an image".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/favicon.ico") { exchange ->
            exchange.sendResponseHeaders(200, ICO_BYTES.size.toLong())
            exchange.responseBody.use { it.write(ICO_BYTES) }
        }

        try {
            server.start()
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val cache = DownloadedFaviconCache(RestClient.builder(), tempDir.toString())

            val result = cache.cache("$baseUrl/article", listOf("$baseUrl/not-image", "$baseUrl/favicon.ico"))

            assertNotNull(result)
            assertTrue(result!!.endsWith(".ico"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `returns null when no candidate is usable`() {
        val cache = DownloadedFaviconCache(RestClient.builder(), tempDir.toString())

        assertNull(cache.cache("https://example.com/article", listOf("javascript:alert(1)")))
        assertEquals(0L, Files.list(tempDir).use { it.count() })
    }

    private fun withFaviconServer(bytes: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/favicon.png") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        try {
            server.start()
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        private val ICO_BYTES = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x01, 0x00)
    }
}
