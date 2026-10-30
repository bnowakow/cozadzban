// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.security

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import pl.bnowakowski.cozadzban.NO_DATABASE_AUTOCONFIGURATION
import pl.bnowakowski.cozadzban.article.ArticleContentRepository
import pl.bnowakowski.cozadzban.article.ArticleRepository
import pl.bnowakowski.cozadzban.article.ArticleService
import pl.bnowakowski.cozadzban.user.AppUser
import pl.bnowakowski.cozadzban.user.AppUserRepository
import pl.bnowakowski.cozadzban.user.AppUserStatus
import pl.bnowakowski.cozadzban.user.AppUserService
import pl.bnowakowski.cozadzban.user.Role

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    NO_DATABASE_AUTOCONFIGURATION,
    "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@session.test",
    "spring.security.oauth2.client.registration.google.client-id=test-client-id",
    "spring.security.oauth2.client.registration.google.client-secret=test-client-secret",
])
class UiSessionAuthorizationTest {
    @Autowired private lateinit var mvc: MockMvc
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @MockitoBean private lateinit var jdbc: NamedParameterJdbcTemplate
    @MockitoBean private lateinit var users: AppUserRepository
    @MockitoBean private lateinit var articles: ArticleRepository
    @MockitoBean private lateinit var articleContentRepository: ArticleContentRepository
    @MockitoBean private lateinit var articleService: ArticleService
    @MockitoBean private lateinit var userService: AppUserService

    private val admin = AppUser(1L, "admin@session.test", Role.ADMIN)

    @BeforeEach
    fun setup() {
        whenever(users.countByRoleAndStatus(Role.ADMIN, AppUserStatus.ACTIVE)).thenReturn(1L)
        whenever(users.findByEmail(admin.email)).thenReturn(admin)
    }

    @Test
    fun `unknown public pages have a helpful 404 rather than an authentication challenge`() {
        mvc.get("/missing-seo-test-page") { header("Accept", "text/html") }
            .andExpect {
                status { isNotFound() }
                content { contentTypeCompatibleWith("text/html") }
                content { string(org.hamcrest.Matchers.containsString("Page not found")) }
                header { doesNotExist("WWW-Authenticate") }
            }
    }

    @Test
    fun `unknown protected API paths retain authorization semantics`() {
        mvc.get("/api/missing-seo-test-page") { header("Accept", "text/html") }
            .andExpect { status { isUnauthorized() } }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin", "/admin/article-content-cache", "/admin/facebook-pages", "/"])
    fun `demoted admin cannot dispatch events from an existing view`(route: String) {
        assertRevoked(route, admin.copy(role = Role.USER))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/admin", "/admin/article-content-cache", "/admin/facebook-pages", "/"])
    fun `deleted admin cannot dispatch events from an existing view`(route: String) {
        assertRevoked(route, admin.copy(status = AppUserStatus.DELETED))
    }

    @Test
    fun `removed allowlist row revokes an existing session`() {
        assertRevoked("/admin", null)
    }

    @Test
    fun `bearer header cannot bypass revocation of an attached Vaadin session`() {
        val token = Jwt.withTokenValue("valid-jwt").header("alg", "RS256")
            .subject("google-subject").claim("email", admin.email).claim("email_verified", true).build()
        whenever(jwtDecoder.decode("valid-jwt")).thenReturn(token)
        assertRevoked("/admin", admin.copy(role = Role.USER), bearerToken = "valid-jwt")
    }

    @Test
    fun `unchanged active admin retains their session`() {
        val session = sessionFor(Role.ADMIN)
        repeat(2) {
            mvc.get("/auth/me") { this.session = session }.andExpect {
                status { isOk() }
                jsonPath("$.role") { value("ADMIN") }
            }
        }
        assertTrue(!session.isInvalid)
    }

    @Test
    fun `fresh login after demotion receives user permissions`() {
        whenever(users.findByEmail(admin.email)).thenReturn(admin.copy(role = Role.USER))
        mvc.get("/auth/me") { session = sessionFor(Role.USER) }.andExpect {
            status { isOk() }
            jsonPath("$.role") { value("USER") }
        }
    }

    private fun assertRevoked(route: String, changedUser: AppUser?, bearerToken: String? = null) {
        val session = sessionFor(Role.ADMIN)
        // Establish that the SAME authentication and session work before the database change.
        mvc.get("/auth/me") { this.session = session }.andExpect { status { isOk() } }
        whenever(users.findByEmail(admin.email)).thenReturn(changedUser)

        mvc.post(route) {
            this.session = session
            param("v-r", "uidl")
            if (bearerToken != null) header("Authorization", "Bearer $bearerToken")
            with(csrf())
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = "[]"
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("Your permissions changed. Please sign in again.") }
        }
        assertTrue(session.isInvalid)
        verifyNoInteractions(articleService, userService)
        mvc.get("/auth/me").andExpect { status { isUnauthorized() } }
    }

    private fun sessionFor(role: Role): MockHttpSession {
        val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
        val principal = DefaultOAuth2User(authorities, mapOf("sub" to "google-subject", "email" to admin.email), "sub")
        val authentication = OAuth2AuthenticationToken(principal, authorities, "google")
        return MockHttpSession().apply {
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(authentication))
        }
    }
}
