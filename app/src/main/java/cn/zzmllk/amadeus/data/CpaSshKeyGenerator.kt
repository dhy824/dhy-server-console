package cn.zzmllk.amadeus.data

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal data class GeneratedCpaKeyPair(val privateKey: ByteArray, val publicKey: String)

internal object CpaSshKeyGenerator {
    fun generate(profileId: String): GeneratedCpaKeyPair {
        val profileTag = UUID.fromString(profileId).toString().take(8)
        var privateBlob: ByteArray? = null
        var publicBlob: ByteArray? = null
        try {
            val generator = Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(SecureRandom()))
            }
            val pair = generator.generateKeyPair()
            val privateParameters = pair.private as Ed25519PrivateKeyParameters
            val publicParameters = pair.public as Ed25519PublicKeyParameters
            privateBlob = OpenSSHPrivateKeyUtil.encodePrivateKey(privateParameters)
            publicBlob = OpenSSHPublicKeyUtil.encodePublicKey(publicParameters)
            val privateKey = armorOpenSshPrivateKey(privateBlob)
            val publicKey = "ssh-ed25519 ${Base64.getEncoder().withoutPadding().encodeToString(publicBlob)} amadeus-cpa-$profileTag"
            check(startsWithAscii(privateKey, "-----BEGIN OPENSSH PRIVATE KEY-----")) {
                "手机生成的私钥格式不受支持。"
            }
            check(publicKey.matches(Regex("^ssh-ed25519 [A-Za-z0-9+/]+={0,2} amadeus-cpa-[0-9a-f]{8}$"))) {
                "手机生成的 SSH 公钥格式无效。"
            }
            return GeneratedCpaKeyPair(privateKey, publicKey)
        } catch (error: Exception) {
            throw IllegalStateException("无法在手机生成 CPA 档案密钥。", error)
        } finally {
            privateBlob?.fill(0)
            publicBlob?.fill(0)
        }
    }

    fun fingerprint(publicKey: String): String {
        val fields = publicKey.trim().split(Regex("\\s+"))
        require(fields.size == 3 && fields[0] == "ssh-ed25519") { "SSH 公钥格式无效。" }
        val raw = try { java.util.Base64.getDecoder().decode(fields[1]) }
        catch (error: IllegalArgumentException) { throw IllegalArgumentException("SSH 公钥格式无效。", error) }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private class SensitiveOutputStream : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }

    private fun armorOpenSshPrivateKey(blob: ByteArray): ByteArray {
        val base64 = Base64.getMimeEncoder(70, byteArrayOf('\n'.code.toByte()))
            .encode(blob)
        val output = SensitiveOutputStream()
        return try {
            output.write("-----BEGIN OPENSSH PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII))
            output.write(base64)
            output.write("\n-----END OPENSSH PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII))
            output.toByteArray()
        } finally {
            base64.fill(0)
            output.wipe()
        }
    }

    private fun startsWithAscii(value: ByteArray, prefix: String): Boolean {
        val expected = prefix.toByteArray(Charsets.US_ASCII)
        return value.size >= expected.size && expected.indices.all { value[it] == expected[it] }
    }
}
