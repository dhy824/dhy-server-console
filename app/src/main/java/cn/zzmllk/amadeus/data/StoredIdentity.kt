package cn.zzmllk.amadeus.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

const val LEGACY_IDENTITY_ALIAS = "amadeus_ssh_identity_v1"
private const val IDENTITY_MAGIC = 0x414d4b32
const val MAX_IDENTITY_PAYLOAD = 1_048_768
private val CURRENT_ALIAS = Regex("amadeus_ssh_identity_v2_[a-f0-9]{32}")
fun ownedIdentityAlias(alias: String) = alias == LEGACY_IDENTITY_ALIAS || CURRENT_ALIAS.matches(alias)

class IdentityKeyException(message: String, val retryable: Boolean = false, cause: Throwable? = null) :
    IllegalStateException(message, cause)

data class IdentityRecord(val alias: String, val iv: ByteArray, val ciphertext: ByteArray) {
    fun encode(): ByteArray {
        require(CURRENT_ALIAS.matches(alias) && iv.size in 12..32 && ciphertext.size in 16..1_048_592)
        val name = alias.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(16 + name.size + iv.size + ciphertext.size)
            .putInt(IDENTITY_MAGIC).putInt(name.size).put(name)
            .putInt(iv.size).put(iv).putInt(ciphertext.size).put(ciphertext).array()
    }

    companion object {
        fun decode(payload: ByteArray): IdentityRecord {
            try {
                require(payload.size in 21..MAX_IDENTITY_PAYLOAD)
                val buffer = ByteBuffer.wrap(payload)
                val first = buffer.int
                val alias: String
                val ivSize: Int
                if (first == IDENTITY_MAGIC) {
                    val size = buffer.int
                    require(size in 1..128 && buffer.remaining() > size + 8)
                    alias = ByteArray(size).also(buffer::get).toString(Charsets.US_ASCII)
                    require(CURRENT_ALIAS.matches(alias))
                    ivSize = buffer.int
                } else {
                    alias = LEGACY_IDENTITY_ALIAS
                    ivSize = first
                }
                require(ivSize in 12..32 && buffer.remaining() > ivSize + 4)
                val iv = ByteArray(ivSize).also(buffer::get)
                val size = buffer.int
                require(size in 16..1_048_592 && size == buffer.remaining())
                return IdentityRecord(alias, iv, ByteArray(size).also(buffer::get))
            } catch (error: Exception) {
                throw IdentityKeyException("本机私钥存储损坏，请在设置中点“更换私钥”，重新导入原 SSH 私钥。", cause = error)
            }
        }
    }
}

interface IdentityCipherVault {
    fun existing(alias: String): SecretKey?
    fun create(alias: String): SecretKey
    fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray
}

/** Read never generates keys or mutates ciphertext. Replacement requires a new user import. */
class StoredIdentity(
    private val vault: IdentityCipherVault,
    private val readPayload: () -> ByteArray,
    private val writePayload: (ByteArray) -> Unit,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    private fun <T> bounded(block: () -> T): T {
        repeat(3) { attempt ->
            try { return block() } catch (error: IdentityKeyException) {
                if (!error.retryable || attempt == 2) throw error
                pause(80L shl attempt)
            }
        }
        error("unreachable")
    }

    fun read(): ByteArray {
        val payload = readPayload()
        try {
            val record = IdentityRecord.decode(payload)
            try {
                return bounded {
                    val key = vault.existing(record.alias) ?: throw IdentityKeyException(
                        "本机加密密钥已丢失，请在设置中点“更换私钥”，重新导入原 SSH 私钥；服务器配置和登录数据无需清除。")
                    vault.decrypt(key, record.iv, record.ciphertext)
                }
            } catch (error: AEADBadTagException) {
                throw IdentityKeyException("本机私钥无法解密，请在设置中点“更换私钥”，重新导入原 SSH 私钥。", cause = error)
            } finally {
                record.iv.fill(0); record.ciphertext.fill(0)
            }
        } finally { payload.fill(0) }
    }

    fun replace(plaintext: ByteArray) {
        var payload: ByteArray? = null
        var probe: ByteArray? = null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val alias = "amadeus_ssh_identity_v2_" + UUID.randomUUID().toString().replace("-", "")
            val key = bounded { vault.create(alias) }
            val encrypted = bounded { vault.encrypt(key, plaintext) }
            iv = encrypted.first; ciphertext = encrypted.second
            probe = bounded { vault.decrypt(key, encrypted.first, encrypted.second) }
            check(MessageDigest.isEqual(plaintext, probe)) { "本机加密存储自检失败，原私钥未替换。" }
            payload = IdentityRecord(alias, encrypted.first, encrypted.second).encode()
            writePayload(payload)
            // Keep keys on ambiguous write failures; deleting one could strand committed ciphertext.
        } finally {
            plaintext.fill(0); probe?.fill(0); payload?.fill(0); iv?.fill(0); ciphertext?.fill(0)
        }
    }
}
