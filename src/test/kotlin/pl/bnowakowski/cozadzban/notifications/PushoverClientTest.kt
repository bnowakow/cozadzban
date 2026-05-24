// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class PushoverClientTest {

    @Test
    fun `validates user and sends notification`() {
        withRecordingServer(responseBody = """{"status":1,"request":"ok"}""") { baseUrl, requests ->
            val client = client(baseUrl)

            client.validateUser("user-key", "iphone")
            client.send(
                PushoverMessage(
                    userKey = "user-key",
                    device = "iphone",
                    title = "Title",
                    message = "Message",
                    url = "https://cozadzban.pl/article-proposals",
                    urlTitle = "Review",
                ),
            )

            assertEquals(listOf("/1/users/validate.json", "/1/messages.json"), requests.map { it.path })
            assertEquals("app-token", requests[0].form["token"])
            assertEquals("user-key", requests[0].form["user"])
            assertEquals("iphone", requests[0].form["device"])
            assertEquals("Title", requests[1].form["title"])
            assertEquals("Message", requests[1].form["message"])
        }
    }

    @Test
    fun `invalid user key response fails validation`() {
        withRecordingServer(responseBody = """{"status":0,"errors":["invalid user"]}""") { baseUrl, _ ->
            val client = client(baseUrl)

            assertThrows(PushoverException::class.java) {
                client.validateUser("bad-user", null)
            }
        }
    }

    @Test
    fun `server error is wrapped`() {
        withRecordingServer(status = 500, responseBody = """{"status":0}""") { baseUrl, _ ->
            val client = client(baseUrl)

            assertThrows(PushoverException::class.java) {
                client.send(PushoverMessage("user-key", null, "Title", "Message"))
            }
        }
    }

    @Test
    fun `timeout is wrapped`() {
        withRecordingServer(responseBody = """{"status":1}""", delayMillis = 150) { baseUrl, _ ->
            val client = client(baseUrl, readTimeout = Duration.ofMillis(20))

            assertThrows(PushoverException::class.java) {
                client.validateUser("user-key", null)
            }
        }
    }

    private fun client(
        baseUrl: String,
        readTimeout: Duration = Duration.ofSeconds(1),
    ): PushoverClient =
        PushoverClient(
            NotificationProperties(
                enabled = true,
                pushover = NotificationProperties.Pushover(
                    appToken = "app-token",
                    baseUrl = baseUrl,
                    connectTimeout = Duration.ofMillis(100),
                    readTimeout = readTimeout,
                ),
            ),
            RestClient.builder(),
        )

    private fun withRecordingServer(
        status: Int = 200,
        responseBody: String,
        delayMillis: Long = 0,
        block: (String, List<RecordedRequest>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(RecordedRequest(exchange.requestURI.path, decodeForm(body)))
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}", requests)
        } finally {
            server.stop(0)
        }
    }

    private fun decodeForm(body: String): Map<String, String> =
        body.split("&")
            .filter { it.isNotBlank() }
            .associate {
                val parts = it.split("=", limit = 2)
                URLDecoder.decode(parts[0], Charsets.UTF_8) to
                    URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
            }

    private data class RecordedRequest(
        val path: String,
        val form: Map<String, String>,
    )
}
