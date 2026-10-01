package cn.zzmllk.amadeus.ssh

import cn.zzmllk.amadeus.data.AppConfig
import org.junit.Assert.*
import org.junit.Test

class ConnectionContractsTest {
    private val config = AppConfig("example.invalid", 22, "fixture", 18317, 16185, "fixture-key", "ssh-ed25519")

    @Test fun outputIsBoundedEvenWhenThePeerKeepsSending() {
        val output = BoundedOutput(16)
        repeat(10_000) { output.write(ByteArray(1024) { 65 }) }
        assertTrue(output.exceeded)
        assertEquals(16, output.size())
        assertEquals("A".repeat(16), output.text())
    }

    @Test fun exactLimitIsAllowedUntilAnotherByteArrives() {
        val output = BoundedOutput(4)
        output.write("test".toByteArray())
        assertFalse(output.exceeded)
        output.write(65)
        assertTrue(output.exceeded)
        assertEquals("test", output.text())
    }

    @Test fun credentialsTrustAndPortsInvalidateConnectionReuse() {
        val original = connectionIdentity(config, "revision-one")
        assertEquals(original, connectionIdentity(config.copy(), "revision-one"))
        assertNotEquals(original, connectionIdentity(config, "revision-two"))
        assertNotEquals(original, connectionIdentity(config.copy(trustedHostKey = "new-key"), "revision-one"))
        assertNotEquals(original, connectionIdentity(config.copy(user = "another"), "revision-one"))
        assertNotEquals(original, connectionIdentity(config.copy(host = "another.invalid"), "revision-one"))
        assertNotEquals(original, connectionIdentity(config.copy(apiLocalPort = 19000), "revision-one"))
    }
}
