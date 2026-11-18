package cn.zzmllk.amadeus.data

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CpaSshKeyGeneratorTest {
    @Test
    fun generatedEd25519PrivateKeyIsOpenSshAndLoadsInJsch() {
        val id = UUID.randomUUID().toString()
        val generated = CpaSshKeyGenerator.generate(id)
        try {
            assertTrue(generated.publicKey.startsWith("ssh-ed25519 "))
            assertTrue(generated.publicKey.endsWith("amadeus-cpa-${id.take(8)}"))

            JSch().addIdentity("generated-cpa", generated.privateKey, null, null)
        } finally {
            generated.privateKey.fill(0)
        }
    }

    @Test
    fun eachProfileGetsAnIndependentPublicKey() {
        val first = CpaSshKeyGenerator.generate(UUID.randomUUID().toString())
        val second = CpaSshKeyGenerator.generate(UUID.randomUUID().toString())
        try {
            assertTrue(first.publicKey != second.publicKey)
        } finally {
            first.privateKey.fill(0)
            second.privateKey.fill(0)
        }
    }

    @Test
    fun publicKeyFingerprintUsesOpenSshSha256Format() {
        val generated = CpaSshKeyGenerator.generate(UUID.randomUUID().toString())
        try {
            assertTrue(CpaSshKeyGenerator.fingerprint(generated.publicKey).matches(Regex("^SHA256:[A-Za-z0-9+/]{43}$")))
        } finally {
            generated.privateKey.fill(0)
        }
    }
}
