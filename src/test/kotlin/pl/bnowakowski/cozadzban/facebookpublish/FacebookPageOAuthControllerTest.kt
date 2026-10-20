// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

class FacebookPageOAuthControllerTest {
    private val properties = FacebookPageProperties(
        appId = "app-id", appSecret = "app-secret",
        redirectUri = "https://cozadzban.pl/facebook-pages/oauth/callback",
        encryptionKey = "0123456789abcdef",
    )
    private val graph = mock<FacebookPageGraphClient>()
    private val controller = FacebookPageOAuthController(properties, graph)
    private val admin = UsernamePasswordAuthenticationToken("admin@example.com", "", listOf())

    @Test
    fun `invalid callback state is rejected without exchanging code`() {
        val session = MockHttpSession()
        session.setAttribute(
            FacebookPageOAuthController.AUTHORIZATION_SESSION_KEY,
            PendingFacebookAuthorization("expected", "pl", admin.name, java.time.Instant.now()),
        )

        val response = controller.callback("code", "wrong", null, admin, session)

        assertEquals(FacebookPageOAuthController.SETTINGS_PATH, response.headers.location.toString())
        assertNull(session.getAttribute(FacebookPageOAuthController.AUTHORIZATION_SESSION_KEY))
        assertNull(session.getAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY))
        assertNotNull(session.getAttribute(FacebookPageOAuthController.MESSAGE_SESSION_KEY))
        verifyNoInteractions(graph)
    }

    @Test
    fun `callback is bound to initiating admin`() {
        val session = MockHttpSession()
        session.setAttribute(
            FacebookPageOAuthController.AUTHORIZATION_SESSION_KEY,
            PendingFacebookAuthorization("expected", "pl", "other@example.com", java.time.Instant.now()),
        )

        controller.callback("code", "expected", null, admin, session)

        assertNull(session.getAttribute(FacebookPageOAuthController.SELECTION_SESSION_KEY))
        verifyNoInteractions(graph)
    }

    @Test
    fun `Page authorization URL carries no app secret`() {
        val url = FacebookPageGraphClient(properties).authorizationUrl("state-value")
        assertFalse(url.contains("app-secret"))
        assertFalse(url.contains("access_token"))
    }
}
