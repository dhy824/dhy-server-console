package cn.zzmllk.amadeus.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class StoredIdentityTest {
    private class Vault : IdentityCipherVault {
        val keys = mutableMapOf<String, SecretKey>()
        var creates = 0
        var lookups = 0
        var decryptFailures = 0
        var permanentFailure = false
        var createFailure = false
        override fun existing(alias: String): SecretKey? { lookups++; return keys[alias] }
        override fun create(alias: String): SecretKey {
            creates++
            if (createFailure) throw IdentityKeyException("generation failed")
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it }
        }
        override fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext)
            return cipher.iv to encrypted
        }
        override fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            if (permanentFailure) throw IdentityKeyException("permanent key failure")
            if (decryptFailures-- > 0) throw IdentityKeyException("temporarily unavailable", true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }
    private class Fixture {
        val vault = Vault()
        var payload = byteArrayOf()
        var writes = 0
        val pauses = mutableListOf<Long>()
        val original = "-----BEGIN OPENSSH PRIVATE KEY-----\nfixture-not-an-ssh-key".toByteArray()
        val storage = StoredIdentity(vault, { payload.copyOf() }, { writes++; payload = it.copyOf() }, pauses::add)
        fun legacy() {
            val key = vault.create(LEGACY_IDENTITY_ALIAS)
            val (iv, ciphertext) = vault.encrypt(key, original)
            payload = ByteBuffer.allocate(8 + iv.size + ciphertext.size)
                .putInt(iv.size).put(iv).putInt(ciphertext.size).put(ciphertext).array()
            vault.creates = 0
        }
    }

    @Test fun healthyLegacyCiphertextIsReadWithoutMigrationOrKeyGeneration() {
        val f = Fixture(); f.legacy()
        assertArrayEquals(f.original, f.storage.read())
        assertEquals(0, f.vault.creates); assertEquals(0, f.writes)
    }
    @Test fun missingHardwareKeyNeverCreatesAWrongReplacementDuringRead() {
        val f = Fixture(); f.legacy(); f.vault.keys.clear(); val before = f.payload.copyOf()
        try { f.storage.read(); fail() } catch (e: IdentityKeyException) { assertTrue(e.message!!.contains("更换私钥")) }
        assertEquals(0, f.vault.creates); assertEquals(0, f.writes); assertArrayEquals(before, f.payload)
    }
    @Test fun explicitImportRecoversMissingOldKeyAndWipesInput() {
        val f = Fixture(); f.legacy(); f.vault.keys.clear(); val input = f.original.copyOf()
        f.storage.replace(input)
        assertTrue(input.all { it == 0.toByte() }); assertArrayEquals(f.original, f.storage.read())
        assertNotEquals(LEGACY_IDENTITY_ALIAS, IdentityRecord.decode(f.payload).alias)
    }
    @Test fun explicitImportDoesNotNeedTheBrokenOldKeyAndKeepsIt() {
        val f = Fixture(); f.legacy(); val old = f.vault.keys[LEGACY_IDENTITY_ALIAS]
        f.storage.replace(f.original.copyOf()); assertSame(old, f.vault.keys[LEGACY_IDENTITY_ALIAS])
        assertArrayEquals(f.original, f.storage.read())
    }
    @Test fun transientErrorsHaveBoundedRetriesWithNewOperations() {
        val f = Fixture(); f.legacy(); f.vault.decryptFailures = 2
        assertArrayEquals(f.original, f.storage.read()); assertEquals(listOf(80L, 160L), f.pauses)
        assertEquals(3, f.vault.lookups)
    }
    @Test fun permanentErrorsAreNotRetriedOrReplaced() {
        val f = Fixture(); f.legacy(); f.vault.permanentFailure = true
        try { f.storage.read(); fail() } catch (_: IdentityKeyException) { }
        assertTrue(f.pauses.isEmpty()); assertEquals(0, f.writes); assertEquals(0, f.vault.creates)
    }
    @Test fun retryBudgetStopsAfterThreeAttempts() {
        val f = Fixture(); f.legacy(); f.vault.decryptFailures = 20
        try { f.storage.read(); fail() } catch (_: IdentityKeyException) { }
        assertEquals(3, f.vault.lookups); assertEquals(2, f.pauses.size)
    }
    @Test fun failedImportPreservesOldPayloadAndZeroesPlaintext() {
        val f = Fixture(); f.legacy(); val old = f.payload.copyOf(); f.vault.createFailure = true
        val input = f.original.copyOf()
        try { f.storage.replace(input); fail() } catch (_: IdentityKeyException) { }
        assertArrayEquals(old, f.payload); assertTrue(input.all { it == 0.toByte() }); assertEquals(0, f.writes)
    }
    @Test fun ambiguousCommitDoesNotDeleteKeyNeededByAlreadyCommittedCiphertext() {
        val f = Fixture(); f.legacy()
        val storage = StoredIdentity(f.vault, { f.payload.copyOf() }, { f.payload = it.copyOf(); error("ambiguous commit") })
        try { storage.replace(f.original.copyOf()); fail() } catch (_: IllegalStateException) { }
        assertArrayEquals(f.original, f.storage.read())
    }
    @Test fun corruptedAuthenticationTagPreservesStorageAndOffersRecovery() {
        val f = Fixture(); f.legacy(); f.payload[f.payload.lastIndex] = (f.payload.last().toInt() xor 1).toByte()
        val old = f.payload.copyOf()
        try { f.storage.read(); fail() } catch (e: IdentityKeyException) { assertTrue(e.message!!.contains("更换私钥")) }
        assertArrayEquals(old, f.payload); assertEquals(0, f.writes)
    }
    @Test fun malformedOrForeignAliasCannotAddressOtherKeystoreKeys() {
        val f = Fixture(); f.legacy()
        for (payload in listOf(byteArrayOf(), ByteArray(128), ByteArray(MAX_IDENTITY_PAYLOAD + 1))) {
            try { IdentityRecord.decode(payload); fail() } catch (_: IdentityKeyException) { }
        }
        assertFalse(ownedIdentityAlias("other-app-key"))
        assertFalse(ownedIdentityAlias("amadeus_ssh_identity_v2_invalid"))
    }
}
