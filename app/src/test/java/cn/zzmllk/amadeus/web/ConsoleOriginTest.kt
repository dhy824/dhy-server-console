package cn.zzmllk.amadeus.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleOriginTest {
    @Test
    fun sameOriginNavigationStaysOnTheSelectedServerAndPort() {
        val origin = "http://127.0.0.2:39127/v0/resource/plugins/freetier/console"

        assertTrue(isSameConsoleOrigin(origin, "http://127.0.0.2:39127/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.3:39127/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.2:39128/#/settings"))
        assertFalse(isSameConsoleOrigin(origin, "http://127.0.0.1:39127/"))
        assertFalse(isSameConsoleOrigin(origin, "https://127.0.0.2:39127/"))
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
