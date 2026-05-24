// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class PushoverClientTest {

    @Test
    fun `validates user and sends notification`() {
        withRecordingServer(responseBody = """{"status":1,"devices":["iphone","mac"],"request":"ok"}""") { baseUrl, requests ->
            val client = client(baseUrl)

            val validation = client.validateUser("user-key", listOf("iphone", "mac"))
            client.send(
                PushoverMessage(
                    userKey = "user-key",
                    devices = listOf("iphone", "mac"),
                    title = "Title",
                    message = "Message",
                    url = "https://cozadzban.pl/article-proposals",
                    urlTitle = "Review",
                ),
            )

            assertEquals(listOf("/1/users/validate.json", "/1/messages.json"), requests.map { it.path })
            assertEquals("app-token", requests[0].form["token"])
            assertEquals("user-key", requests[0].form["user"])
            assertEquals(listOf("iphone", "mac"), validation.devices)
            assertEquals(null, requests[0].form["device"])
            assertEquals("iphone,mac", requests[1].form["device"])
            assertEquals("Title", requests[1].form["title"])
            assertEquals("Message", requests[1].form["message"])
        }
    }

    @Test
    fun `invalid user key response fails validation`() {
        withRecordingServer(responseBody = """{"status":0,"errors":["invalid user"]}""") { baseUrl, _ ->
            val client = client(baseUrl)

            val ex = assertThrows(PushoverException::class.java) {
                client.validateUser("bad-user", emptyList())
            }

            assertEquals("Pushover user validation failed: invalid user", ex.message)
        }
    }

    @Test
    fun `unknown selected device fails validation`() {
        withRecordingServer(responseBody = """{"status":1,"devices":["iphone"]}""") { baseUrl, _ ->
            val client = client(baseUrl)

            val ex = assertThrows(PushoverException::class.java) {
                client.validateUser("user-key", listOf("ipad"))
            }

            assertEquals("Pushover device not valid for user: ipad", ex.message)
        }
    }

    @Test
    fun `http error exposes Pushover validation error detail`() {
        withRecordingServer(status = 400, responseBody = """{"status":0,"errors":["invalid user"]}""") { baseUrl, _ ->
            val client = client(baseUrl)

            val ex = assertThrows(PushoverException::class.java) {
                client.validateUser("bad-user", emptyList())
            }

            assertEquals("Could not validate Pushover user: HTTP 400: invalid user", ex.message)
        }
    }

    @Test
    fun `http error exposes compact raw body when response shape is unexpected`() {
        withRecordingServer(status = 400, responseBody = """{"error":"bad request"}""") { baseUrl, _ ->
            val client = client(baseUrl)

            val ex = assertThrows(PushoverException::class.java) {
                client.validateUser("bad-user", emptyList())
            }

            assertTrue(ex.message?.contains("""{"error":"bad request"}""") == true)
        }
    }

    @Test
    fun `server error is wrapped`() {
        withRecordingServer(status = 500, responseBody = """{"status":0}""") { baseUrl, _ ->
            val client = client(baseUrl)

            assertThrows(PushoverException::class.java) {
                client.send(PushoverMessage("user-key", emptyList(), "Title", "Message"))
            }
        }
    }

    @Test
    fun `timeout is wrapped`() {
        withRecordingServer(responseBody = """{"status":1}""", delayMillis = 150) { baseUrl, _ ->
            val client = client(baseUrl, readTimeout = Duration.ofMillis(20))

            assertThrows(PushoverException::class.java) {
                client.validateUser("user-key", emptyList())
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
