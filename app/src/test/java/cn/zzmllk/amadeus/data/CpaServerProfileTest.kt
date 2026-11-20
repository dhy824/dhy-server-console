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
    fun searchProfileRoundTripsWithItsOwnLoopbackAndService() {
        val cpa = profile(localHostOctet = 2)
        val search = profile(name = "Search", localHostOctet = 3).copy(
            configuredServices = setOf(ServerService.SEARXNG)
        )
        val restored = CpaProfileCodec.decode(CpaProfileCodec.encode(listOf(cpa, search)))
        val console = requireNotNull(ProfileConsole.forProfile(restored.last()))

        assertEquals(listOf(cpa, search), restored)
        assertEquals(ProfileConsole.CPA, ProfileConsole.forProfile(restored.first()))
        assertEquals(ProfileConsole.SEARXNG, console)
        assertEquals(18888, console.remotePort)
        assertEquals("/preferences", console.probePath)
        assertEquals("http://127.0.0.3:39127/", ProfileConsolePage.SEARXNG_HOME.localUrl(restored.last().localHost, 39127))
        assertFalse(ServerService.SEARXNG in aliyunServices)
        assertFalse(restored.last().supports(ServerService.CPA))
        assertTrue(runCatching {
            CpaProfileCodec.validate(listOf(search.copy(configuredServices = setOf(ServerService.SEARXNG, ServerService.CPA))))
        }.isFailure)
    }

    @Test
    fun directLinksStayOnTheSelectedProfileTunnelAndService() {
        val host = "127.0.0.3"
        val port = 39211

        assertEquals("http://$host:$port/preferences", ProfileConsolePage.SEARXNG_PREFERENCES.localUrl(host, port))
        assertEquals("http://$host:$port/stats", ProfileConsolePage.SEARXNG_STATS.localUrl(host, port))
        assertEquals(ProfileConsolePage.SEARXNG_PREFERENCES, ProfileConsolePage.forPath(ProfileConsole.SEARXNG, "/preferences"))
        assertEquals(ProfileConsolePage.SEARXNG_STATS, ProfileConsolePage.forPath(ProfileConsole.SEARXNG, "/stats"))
        assertEquals(ProfileConsolePage.CPA, ProfileConsolePage.forPath(ProfileConsole.CPA, CPA_MANAGEMENT_PATH))
        assertEquals(18889, ProfileConsole.SEARXNG_CONFIG.remotePort)
        assertEquals("/healthz", ProfileConsole.SEARXNG_CONFIG.probePath)
        assertEquals(ProfileConsolePage.SEARXNG_SETTINGS, ProfileConsolePage.forPath(ProfileConsole.SEARXNG_CONFIG, "/"))
        assertEquals(null, ProfileConsolePage.forPath(ProfileConsole.SEARXNG_CONFIG, "/preferences"))
        assertEquals(null, ProfileConsolePage.forPath(ProfileConsole.CPA, "/preferences"))
        assertEquals(null, ProfileConsolePage.forPath(ProfileConsole.SEARXNG, CPA_MANAGEMENT_PATH))
    }

    @Test
    fun consoleRouteIsTheCliProxyManagementPage() {
        assertEquals("/management.html", CPA_MANAGEMENT_PATH)
    }
}
