package cn.zzmllk.amadeus.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleOriginTest {
    @Test
    fun sameOriginNavigationStaysOnTheSelectedServerAndPort() {
        val origin = "http://127.0.0.2:39127/management.html"

        assertTrue(isSameConsoleOrigin(origin, "http://127.0.0.2:39127/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.3:39127/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.2:39128/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.1:39127/"))
        assertFalse(isSameConsoleOrigin(origin, "https://127.0.0.2:39127/"))
    }

    @Test
    fun searchResultLinksAreLimitedToHttpWebsites() {
        assertTrue(isExternalSearchResultUrl("https://www.example.org/article?q=search"))
        assertFalse(isExternalSearchResultUrl("https://name@www.example.org/"))
        assertFalse(isExternalSearchResultUrl("http://127.0.0.3:39127/"))
        assertFalse(isExternalSearchResultUrl("http://server.local/"))
        assertFalse(isExternalSearchResultUrl("https://[::1]/"))
        assertFalse(isExternalSearchResultUrl("intent://www.example.org/"))
    }

    @Test
    fun consoleUrlRejectsNonLoopbackHostsAndUserInfo() {
        assertTrue(isAllowedConsoleUrl("http://127.0.0.2:39127/"))
        assertTrue(isAllowedConsoleUrl("http://127.0.0.1:39127/"))
        assertFalse(isAllowedConsoleUrl("http://example.invalid:39127/"))
        assertFalse(isAllowedConsoleUrl("http://user@127.0.0.2:39127/"))
        assertFalse(isAllowedConsoleUrl("http://127.0.0.18:39127/"))
    }
}
