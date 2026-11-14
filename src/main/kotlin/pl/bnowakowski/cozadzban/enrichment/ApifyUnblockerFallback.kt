// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A single HTTPS request through Apify's direct UNBLOCKER proxy. */
internal object ApifyUnblockerFallback {
    private val log = LoggerFactory.getLogger(ApifyUnblockerFallback::class.java)
    private val mapper = ObjectMapper()
    private val apiClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var cachedPassword: Pair<Instant, String?>? = null

    fun fetch(url: String, token: String, apiBaseUrl: String): ApifyWebFetchFallback.Page? {
        if (token.isBlank() || !ApifyWebFetchFallback.validPublicUrl(url)) return null
        val password = proxyPassword(token, apiBaseUrl) ?: return null
        val bodyFile = try {
            Files.createTempFile("cozadzban-unblocker-", ".html")
        } catch (_: Exception) {
            return null
        }
        try {
            val process = ProcessBuilder(
                "curl", "--silent", "--show-error", "--location", "--max-redirs", "2",
                "--proto", "=https", "--proto-redir", "=https", "--connect-timeout", "5",
                "--max-time", "30", "--max-filesize", "2000000",
                "--proxy", "http://proxy.apify.com:8000", "--config", "-",
                "--output", bodyFile.toString(),
                "--write-out", "%{http_code}\n%{url_effective}\n%{content_type}", url,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            // Keep the rotated proxy password out of argv, environment variables, and logs.
            process.outputStream.bufferedWriter().use { writer ->
                writer.write("proxy-user = \"groups-UNBLOCKER:${password.replace("\\", "\\\\").replace("\"", "\\\"")}\"\n")
            }
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                log.info("Apify direct Unblocker timed out for '{}'", url)
                return null
            }
            val output = process.inputStream.bufferedReader().readText().lines()
            if (process.exitValue() != 0 || (output.firstOrNull()?.toIntOrNull() ?: 0) !in 200..299) {
                log.info("Apify direct Unblocker failed for '{}'; status={}", url, output.firstOrNull())
                return null
            }
            if (!output.getOrNull(2).orEmpty().lowercase().startsWith("text/html")) return null
            if (Files.size(bodyFile) > 2_000_000) return null
            val page = ApifyWebFetchFallback.validateHtmlPage(url, output.getOrNull(1).orEmpty(), Files.readString(bodyFile))
            if (page == null) log.info("Apify direct Unblocker returned no usable article for '{}'", url)
            return page
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (ex: Exception) {
            log.info("Apify direct Unblocker unavailable for '{}'; error={}", url, ex.javaClass.simpleName)
            return null
        } finally {
            runCatching { Files.deleteIfExists(bodyFile) }
        }
    }

    private fun proxyPassword(token: String, apiBaseUrl: String): String? {
        cachedPassword?.takeIf { Duration.between(it.first, Instant.now()) < Duration.ofMinutes(10) }?.let { return it.second }
        val password = try {
            val request = HttpRequest.newBuilder(URI("${apiBaseUrl.trimEnd('/')}/v2/users/me"))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer $token").GET().build()
            val response = apiClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null
            val data = mapper.readTree(response.body()).path("data")
            val features = data.path("plan").path("enabledPlatformFeatures")
            if (features.isArray && features.none { it.asText() == "PROXY_EXTERNAL_ACCESS" }) {
                log.info("Apify direct Unblocker unavailable: account has no external proxy access")
                null
            } else data.path("proxy").path("password").asText("")
                .takeIf { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (ex: Exception) {
            log.info("Could not read Apify proxy access; error={}", ex.javaClass.simpleName)
            null
        }
        cachedPassword = Instant.now() to password
        return password
    }
}
