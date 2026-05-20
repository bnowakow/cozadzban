// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.rss

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.bnowakowski.cozadzban.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozadzban.article.Article
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.Role
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Optional

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@rss-controller.test",
        NO_DATABASE_AUTOCONFIGURATION,
    ],
)
class RssControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var namedParameterJdbcTemplate: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository
    @MockitoBean private lateinit var articleService: ArticleService

    private val sampleArticles = listOf(
        Article(
            id = 1L,
            url = "https://example.com/article-one",
            language = "en",
            title = "Article One",
            lead = "Lead for article one",
            thumbnail = "https://example.com/thumb-one.jpg?width=640&height=360",
            quote = null,
            aiSummary = null,
            createdByUserId = 1L,
            createdAt = Instant.parse("2026-05-04T10:00:00Z"),
        ),
        Article(
            id = 2L,
            url = "https://example.com/article-two",
            language = "pl",
            title = "Article Two",
            lead = "Lead for article two",
            thumbnail = null,
            quote = null,
            aiSummary = null,
            createdByUserId = 1L,
            createdAt = Instant.parse("2026-05-03T10:00:00Z"),
        ),
    )

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
    }

    @Test
    fun `GET rss returns 200 with application rss+xml content type`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(sampleArticles)

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith("application/rss+xml") }
            }
    }

    @Test
    fun `GET rss response contains required channel elements`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(sampleArticles)

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("<title>Co za dzban</title>"))
                    string(org.hamcrest.Matchers.containsString("""<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/">"""))
                    string(org.hamcrest.Matchers.containsString("<link>https://cozadzban.pl</link>"))
                    string(org.hamcrest.Matchers.containsString("<description>fucked up news</description>"))
                    string(org.hamcrest.Matchers.containsString("<generator>cozadzban.pl</generator>"))
                    string(org.hamcrest.Matchers.containsString("<lastBuildDate>"))
                    string(org.hamcrest.Matchers.containsString("<language>en</language>"))
                }
            }
    }

    @Test
    fun `GET rss response contains article items`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(sampleArticles)

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("<title>Article One</title>"))
                    string(org.hamcrest.Matchers.containsString("<link>https://example.com/article-one</link>"))
                    string(org.hamcrest.Matchers.containsString("<guid isPermaLink=\"false\">1</guid>"))
                }
            }
    }

    @Test
    fun `GET rss with lang filter passes filter to repository`() {
        whenever(articleRepository.findForRss("pl")).thenReturn(listOf(sampleArticles[1]))

        mockMvc.get("/rss?lang=pl")
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("<language>pl</language>"))
                    string(org.hamcrest.Matchers.containsString("<title>Article Two</title>"))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<title>Article One</title>")))
                }
            }
    }

    @Test
    fun `GET rss response includes media thumbnail for articles with thumbnail`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(sampleArticles)

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("""<media:thumbnail url="https://cozadzban.pl/rss/image/1" />"""))
                    string(org.hamcrest.Matchers.containsString("""<media:content url="https://cozadzban.pl/rss/image/1" medium="image" />"""))
                }
            }
    }

    @Test
    fun `GET rss image proxies article thumbnail as image`() {
        withImageServer("image/jpeg", byteArrayOf(1, 2, 3, 4)) { thumbnailUrl ->
            whenever(articleRepository.findById(1L)).thenReturn(
                Optional.of(sampleArticles[0].copy(thumbnail = thumbnailUrl)),
            )

            mockMvc.get("/rss/image/1")
                .andExpect {
                    status { isOk() }
                    content { contentType("image/jpeg") }
                    content { bytes(byteArrayOf(1, 2, 3, 4)) }
                }
        }
    }

    @Test
    fun `GET rss image rejects non image upstream response`() {
        withImageServer("text/html", "<html>not an image</html>".toByteArray()) { thumbnailUrl ->
            whenever(articleRepository.findById(1L)).thenReturn(
                Optional.of(sampleArticles[0].copy(thumbnail = thumbnailUrl)),
            )

            mockMvc.get("/rss/image/1")
                .andExpect {
                    status { isBadGateway() }
                }
        }
    }

    @Test
    fun `GET rss returns 200 for empty feed`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(emptyList())

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("<channel>"))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<item>")))
                }
            }
    }

    @Test
    fun `GET rss is public - no auth required`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(emptyList())

        // Call without any authentication token
        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
            }
    }

    // ─── Phase 21 / Item 66 — RSS creator omission ────────────────────────────

    @Test
    fun `RSS feed does not expose creator email or user data`() {
        whenever(articleRepository.findForRss(isNull())).thenReturn(sampleArticles)

        mockMvc.get("/rss")
            .andExpect {
                status { isOk() }
                content {
                    // None of the user/creator-related fields should appear in the RSS XML
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("createdByUserId")))
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("created_by_user_id")))
                    // The article URLs should appear but no email addresses
                    string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.matchesPattern(".*\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b.*")))
                }
            }
    }

    // ─── Phase 21 / Item 67 — RSS discovery link in index.html ───────────────

    @Test
    fun `index html contains RSS alternate link for feed discovery`() {
        val indexHtml = java.io.File("src/main/frontend/index.html").readText()
        assert(indexHtml.contains("""rel="alternate"""")) {
            "index.html should contain rel=\"alternate\" for RSS discovery"
        }
        assert(indexHtml.contains("""type="application/rss+xml"""")) {
            "index.html should contain type=\"application/rss+xml\" for RSS discovery"
        }
        assert(indexHtml.contains("""href="/rss"""")) {
            "index.html should contain href=\"/rss\" for RSS discovery"
        }
    }

    private fun withImageServer(contentType: String, body: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/thumbnail.jpg") { exchange ->
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}/thumbnail.jpg")
        } finally {
            server.stop(0)
        }
    }
}
