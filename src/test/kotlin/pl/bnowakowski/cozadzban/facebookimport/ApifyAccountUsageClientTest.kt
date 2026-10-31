// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class ApifyAccountUsageClientTest {

    @Test
    fun `reads remaining usage for the current cycle and caches it briefly`() {
        val requests = AtomicInteger()
        val authorization = mutableListOf<String?>()
        withApify(200, LIMITS_JSON, requests, authorization) { baseUrl ->
            val clock = MutableClock(Instant.parse("2026-10-31T10:00:00Z"))
            val client = ApifyAccountUsageClient(properties(baseUrl), clock)

            val usage = client.current()!!

            assertEquals(BigDecimal("5"), usage.limitUsd)
            assertEquals(BigDecimal("2.207510074805215"), usage.usedUsd)
            assertEquals(0, BigDecimal("2.792489925194785").compareTo(usage.remainingUsd))
            assertEquals(Instant.parse("2026-10-27T00:00:00Z"), usage.cycleStartAt)
            assertEquals(Instant.parse("2026-11-26T23:59:59.999Z"), usage.cycleEndAt)
            assertEquals(listOf<String?>("Bearer token"), authorization)

            client.current()
            assertEquals(1, requests.get(), "a second read within the cache window must not call Apify")
            clock.advance(Duration.ofMinutes(6))
            client.current()
            assertEquals(2, requests.get())
            client.fresh()
            assertEquals(3, requests.get(), "fresh() always calls Apify")
        }
    }

    @Test
    fun `remaining usage never goes below zero`() {
        val usage = ApifyAccountUsage(BigDecimal("5"), BigDecimal("5.31"), null, null)
        assertEquals(BigDecimal.ZERO, usage.remainingUsd)
    }

    @Test
    fun `returns null without a token or when Apify fails`() {
        val requests = AtomicInteger()
        withApify(500, """{"error":{"type":"internal-server-error"}}""", requests) { baseUrl ->
            assertNull(ApifyAccountUsageClient(properties(baseUrl, token = " ")).current())
            assertEquals(0, requests.get(), "no token means no request")
            assertNull(ApifyAccountUsageClient(properties(baseUrl)).current())
        }
    }

    private fun properties(baseUrl: String, token: String = "token") = FacebookImportProperties(
        apify = FacebookImportProperties.Apify(apiToken = token, baseUrl = baseUrl),
    )

    private fun withApify(
        status: Int,
        body: String,
        requests: AtomicInteger,
        authorization: MutableList<String?> = mutableListOf(),
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v2/users/me/limits") { exchange ->
            requests.incrementAndGet()
            authorization += exchange.requestHeaders.getFirst("Authorization")
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
    }

    companion object {
        val LIMITS_JSON = """
            {"data":{
              "monthlyUsageCycle":{"startAt":"2026-10-27T00:00:00.000Z","endAt":"2026-11-26T23:59:59.999Z"},
              "limits":{"maxMonthlyUsageUsd":5},
              "current":{"monthlyUsageUsd":2.207510074805215}
            }}
        """.trimIndent()
    }
}
