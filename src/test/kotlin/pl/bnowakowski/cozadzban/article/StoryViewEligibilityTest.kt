package pl.bnowakowski.cozadzban.article

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

class StoryViewEligibilityTest {
    @Test
    fun `counts a normal browser document navigation`() {
        assertTrue(StoryViewEligibility.shouldCount(browserRequest()))
    }

    @Test
    fun `does not count crawler or social preview requests`() {
        for (agent in listOf("Googlebot/2.1", "facebookexternalhit/1.1", "Mozilla/5.0 HeadlessChrome/120")) {
            assertFalse(StoryViewEligibility.shouldCount(browserRequest(userAgent = agent)))
        }
    }

    @Test
    fun `does not count non document requests`() {
        assertFalse(StoryViewEligibility.shouldCount(browserRequest(method = "HEAD")))
        assertFalse(StoryViewEligibility.shouldCount(browserRequest(accept = "application/json")))
        assertFalse(StoryViewEligibility.shouldCount(browserRequest(destination = "image")))
    }

    private fun browserRequest(
        method: String = "GET",
        accept: String = "text/html,application/xhtml+xml",
        userAgent: String = "Mozilla/5.0 (X11; Linux x86_64) Firefox/120.0",
        destination: String = "document",
    ) = MockHttpServletRequest(method, "/stories/42").apply {
        addHeader("Accept", accept)
        addHeader("User-Agent", userAgent)
        addHeader("Sec-Fetch-Dest", destination)
        addHeader("Sec-Fetch-Mode", "navigate")
    }
}
