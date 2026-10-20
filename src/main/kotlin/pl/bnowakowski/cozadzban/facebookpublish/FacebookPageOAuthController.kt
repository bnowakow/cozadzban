// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.bnowakowski.cozadzban.article.ArticleService
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

data class PendingFacebookAuthorization(val state: String, val language: String, val adminName: String, val startedAt: Instant)
data class PendingFacebookPageSelection(val language: String, val adminName: String, val pages: List<DiscoveredFacebookPage>, val createdAt: Instant)

@RestController
@RequestMapping("/facebook-pages/oauth")
class FacebookPageOAuthController(
    private val properties: FacebookPageProperties,
    private val graph: FacebookPageGraphClient,
) {
    private val random = SecureRandom()

    @GetMapping("/start")
    fun start(@RequestParam language: String, authentication: Authentication, session: HttpSession): ResponseEntity<Void> {
        require(properties.oauthConfigured) { "Facebook Page OAuth is not configured" }
        val normalizedLanguage = ArticleService.normalizeLanguage(language)
        val nonce = ByteArray(32).also(random::nextBytes)
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        session.setAttribute(AUTHORIZATION_SESSION_KEY, PendingFacebookAuthorization(state, normalizedLanguage, authentication.name, Instant.now()))
        session.removeAttribute(SELECTION_SESSION_KEY)
        return redirect(graph.authorizationUrl(state))
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
        authentication: Authentication,
        session: HttpSession,
    ): ResponseEntity<Void> {
        val pending = session.getAttribute(AUTHORIZATION_SESSION_KEY) as? PendingFacebookAuthorization
        session.removeAttribute(AUTHORIZATION_SESSION_KEY)
        val validState = pending != null && state != null && MessageDigest.isEqual(
            pending.state.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8),
        )
        if (!validState || pending!!.adminName != authentication.name || pending.startedAt.isBefore(Instant.now().minusSeconds(600))) {
            session.setAttribute(MESSAGE_SESSION_KEY, "Facebook connection expired or failed security validation. Please try again.")
            return redirect(SETTINGS_PATH)
        }
        if (error != null || code.isNullOrBlank()) {
            session.setAttribute(MESSAGE_SESSION_KEY, "Facebook authorization was cancelled or denied.")
            return redirect(SETTINGS_PATH)
        }
        try {
            val discovered = graph.discoverPages(code)
            val pages = discovered.pages.filter { it.canCreateContent }
            session.setAttribute(SELECTION_SESSION_KEY, PendingFacebookPageSelection(pending.language, authentication.name, pages, Instant.now()))
            val message = when {
                discovered.returnedPageCount == 0 && discovered.grantedPermissions != null &&
                    "pages_show_list" !in discovered.grantedPermissions ->
                    "Meta did not grant pages_show_list. Add it to this app's Login for Business configuration and authorize again, selecting the Page."
                discovered.returnedPageCount == 0 && discovered.grantedPermissions != null &&
                    "business_management" !in discovered.grantedPermissions ->
                    "Meta granted pages_show_list but returned no Pages. If the Page belongs to a business portfolio, add business_management and give this Facebook account a role in that portfolio, then authorize again."
                discovered.returnedPageCount == 0 ->
                    "Meta returned no Pages. Check Page access and select the Page in Meta authorization."
                discovered.pages.isEmpty() -> "Meta returned ${discovered.returnedPageCount} Page(s), but no usable Page access tokens. Check the login configuration's Page permissions and that you granted this app access to the Pages."
                pages.isEmpty() -> "Meta returned Pages, but none granted content creation access. Check your Page content task in Meta Business Suite."
                else -> "Choose a Page for ${pending.language}."
            }
            session.setAttribute(MESSAGE_SESSION_KEY, message)
        } catch (_: Exception) {
            session.setAttribute(MESSAGE_SESSION_KEY, "Facebook connection failed. Check Meta app permissions and try again.")
        }
        return redirect(SETTINGS_PATH)
    }

    private fun redirect(url: String): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build()

    companion object {
        const val SETTINGS_PATH = "/admin/facebook-pages"
        const val AUTHORIZATION_SESSION_KEY = "facebook-page-oauth-authorization"
        const val SELECTION_SESSION_KEY = "facebook-page-oauth-selection"
        const val MESSAGE_SESSION_KEY = "facebook-page-oauth-message"
    }
}
