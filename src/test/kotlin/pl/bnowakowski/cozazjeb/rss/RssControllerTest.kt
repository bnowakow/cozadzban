// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.rss

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import pl.bnowakowski.cozazjeb.article.Article
import pl.bnowakowski.cozazjeb.article.ArticleRepository
import pl.bnowakowski.cozazjeb.user.AppUserRepository
import pl.bnowakowski.cozazjeb.user.Role
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = ["app.build.timestamp=2026-05-04T10:00:00Z"])
class RssControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var appUserRepository: AppUserRepository
    @MockitoBean private lateinit var articleRepository: ArticleRepository

    private val sampleArticles = listOf(
        Article(
            id = 1L,
            url = "https://example.com/article-one",
            language = "en",
            title = "Article One",
            lead = "Lead for article one",
            thumbnail = null,
            quote = null,
            aiSummary = null,
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
            createdAt = Instant.parse("2026-05-03T10:00:00Z"),
        ),
    )

    @BeforeEach
    fun setup() {
        whenever(appUserRepository.countByRole(Role.ADMIN)).thenReturn(1L)
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
                    string(org.hamcrest.Matchers.containsString("<title>Co za zjeb</title>"))
                    string(org.hamcrest.Matchers.containsString("<link>https://cozazjeb.pl</link>"))
                    string(org.hamcrest.Matchers.containsString("<description>fucked up news</description>"))
                    string(org.hamcrest.Matchers.containsString("<generator>cozazjeb.pl</generator>"))
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
}
