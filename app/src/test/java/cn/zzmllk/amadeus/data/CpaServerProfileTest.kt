package cn.zzmllk.amadeus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CpaServerProfileTest {
    private fun profile(
        id: String = UUID.randomUUID().toString(),
        name: String = "Osaka",
        host: String = "example.invalid",
        sshPort: Int = 22,
        user: String = "ubuntu",
        key: String = "",
        keyType: String = "",
        localHostOctet: Int? = null
    ) = CpaServerProfile(id, name, host, sshPort, user, key, keyType, localHostOctet)

    @Test
    fun profileCodecPreservesEndpointAndIndependentHostPin() {
        val original = profile(key = "cHVibGljLWtleQ==", keyType = "ssh-ed25519", localHostOctet = 2)

        val decoded = CpaProfileCodec.decode(CpaProfileCodec.encode(listOf(original)))

        assertEquals(listOf(original), decoded)
        assertEquals("ssh-ed25519", decoded.single().toAppConfig().trustedHostKeyType)
    }

    @Test
    fun legacyProfilesDefaultToCpaOnlyAndConfiguredServicesRoundTrip() {
        val oracle = profile().copy(configuredServices = setOf(ServerService.CPA))
        val expanded = profile(name = "Oracle", localHostOctet = 3).copy(
            configuredServices = setOf(ServerService.CPA, ServerService.ASTRBOT)
        )

        assertEquals(setOf(ServerService.CPA), CpaProfileCodec.decode(CpaProfileCodec.encode(listOf(oracle))).single().configuredServices)
        assertEquals(expanded, CpaProfileCodec.decode(CpaProfileCodec.encode(listOf(expanded))).single())
        assertTrue(oracle.supports(ServerService.CPA))
        assertFalse(oracle.supports(ServerService.ASTRBOT))
    }

    @Test
    fun profileSavedByThePreviousVersionRemainsCpaOnly() {
        val legacyJson = """[{"id":"${UUID.randomUUID()}","name":"Oracle","host":"example.invalid","sshPort":22,"user":"ubuntu","trustedHostKey":"","trustedHostKeyType":"","localHostOctet":2,"publicKey":"","apiLocalPort":18317,"astrBotLocalPort":16185}]"""
        val restored = CpaProfileCodec.decode(legacyJson).single()

        assertEquals(setOf(ServerService.CPA), restored.configuredServices)
        assertTrue(restored.supports(ServerService.CPA))
        assertFalse(restored.supports(ServerService.SUBSCRIPTIONS))
    }

    @Test
    fun changedEndpointDropsTheOldHostPinButRenameKeepsIt() {
        val original = profile(key = "cHVibGljLWtleQ==", keyType = "ssh-ed25519", localHostOctet = 2)

        val renamed = CpaProfileCodec.normalize(original.copy(name = " Osaka 1 "), original)
        val changedHost = CpaProfileCodec.normalize(original.copy(host = "another.invalid"), original)
        val changedPort = CpaProfileCodec.normalize(original.copy(sshPort = 2222), original)

        assertEquals("Osaka 1", renamed.name)
        assertTrue(renamed.hasTrustedHost)
        assertFalse(changedHost.hasTrustedHost)
        assertFalse(changedPort.hasTrustedHost)
        assertEquals(null, changedHost.localHostOctet)
        assertEquals(null, changedPort.localHostOctet)

        val rotated = CpaProfileCodec.assignLocalHosts(listOf(changedHost), setOf(original.localHostOctet!!))
        assertNotEquals(original.localHostOctet, rotated.single().localHostOctet)
    }

    @Test
    fun generatedPublicKeyPersistsAndEndpointChangeClearsIt() {
        val publicKey = "ssh-ed25519 cHVibGljLWtleQ== amadeus-cpa-12345678"
        val original = profile(localHostOctet = 2).copy(publicKey = publicKey)
        val decoded = CpaProfileCodec.decode(CpaProfileCodec.encode(listOf(original))).single()
        val moved = CpaProfileCodec.normalize(decoded.copy(host = "another.invalid"), decoded)

        assertEquals(publicKey, decoded.publicKey)
        assertEquals("", moved.publicKey)
        assertEquals(null, moved.localHostOctet)
    }

    @Test
    fun invalidOrDuplicateProfilesAreRejected() {
        val first = profile()
        val duplicateName = profile(name = "OSAKA")

        assertTrue(runCatching { CpaProfileCodec.validate(listOf(first, duplicateName)) }.isFailure)
        assertTrue(runCatching { CpaProfileCodec.validate(listOf(profile(host = "https://example.invalid"))) }.isFailure)
        assertTrue(runCatching { CpaProfileCodec.validate(listOf(profile(sshPort = 70000))) }.isFailure)
        assertTrue(runCatching { CpaProfileCodec.validate(listOf(profile(key = "partial"))) }.isFailure)
    }

    @Test
    fun profilesGetDistinctLoopbackOriginsAndRetiredOriginsAreNotReused() {
        val first = profile()
        val second = profile(name = "Tokyo")
        val assigned = CpaProfileCodec.assignLocalHosts(listOf(first, second), emptySet())
        val firstOctet = assigned.first().localHostOctet!!
        val third = profile(name = "Nagoya")
        val afterDelete = CpaProfileCodec.assignLocalHosts(listOf(assigned.last(), third), setOf(firstOctet))

        assertEquals(2, assigned.map { it.localHostOctet }.distinct().size)
        assertNotEquals(firstOctet, afterDelete.last().localHostOctet)
        assertEquals("127.0.0.${assigned.first().localHostOctet}", assigned.first().localHost)
    }

    @Test
    fun consoleRouteIsTheFixedPluginPage() {
        assertEquals("/v0/resource/plugins/freetier/console", CPA_CONSOLE_PATH)
    }
}
