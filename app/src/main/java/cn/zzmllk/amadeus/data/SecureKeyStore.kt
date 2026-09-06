package cn.zzmllk.amadeus.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(context: Context) {
    private val keyFile = File(context.filesDir, "ssh_identity.enc")

    val hasKey: Boolean get() = keyFile.isFile && keyFile.length() > 20

    fun save(privateKey: ByteArray) {
        var ciphertext: ByteArray? = null
        var payload: ByteArray? = null
        try {
            require(privateKey.size in 64..1_048_576) { "私钥文件大小不正确。" }
            require(!looksLikePublicKey(privateKey)) {
                "你选择的是 SSH 公钥（通常带 .pub 后缀），请改选没有 .pub 后缀的私钥文件。"
            }
            require(SUPPORTED_PRIVATE_KEY_HEADERS.any { containsAscii(privateKey, it) }) {
                "选择的文件不是受支持的 SSH 私钥。请选择 OpenSSH、RSA、EC 或 PKCS#8 私钥。"
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(privateKey)
            ciphertext = encrypted
            val iv = cipher.iv
            payload = ByteBuffer.allocate(8 + iv.size + encrypted.size)
                .putInt(iv.size)
                .put(iv)
                .putInt(encrypted.size)
                .put(encrypted)
                .array()

            val atomicFile = AtomicFile(keyFile)
            val stream = atomicFile.startWrite()
            try {
                stream.write(payload)
                stream.flush()
                atomicFile.finishWrite(stream)
            } catch (error: Exception) {
                atomicFile.failWrite(stream)
                throw error
            }
        } finally {
            privateKey.fill(0)
            ciphertext?.fill(0)
            payload?.fill(0)
        }
    }

    fun read(): ByteArray {
        check(hasKey) { "尚未导入 SSH 私钥。" }
        val buffer = ByteBuffer.wrap(keyFile.readBytes())
        val ivSize = buffer.int
        require(ivSize in 12..32 && buffer.remaining() > ivSize + 4) { "私钥存储已损坏。" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val cipherSize = buffer.int
        require(cipherSize == buffer.remaining()) { "私钥存储长度不正确。" }
        val ciphertext = ByteArray(cipherSize).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun clear() {
        AtomicFile(keyFile).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun containsAscii(source: ByteArray, marker: String): Boolean {
        val expected = marker.toByteArray(Charsets.US_ASCII)
        if (source.size < expected.size) return false
        for (start in 0..source.size - expected.size) {
            var matches = true
            for (offset in expected.indices) {
                if (source[start + offset] != expected[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun looksLikePublicKey(source: ByteArray): Boolean {
        val prefix = source.take(128).toByteArray().toString(Charsets.US_ASCII).trimStart()
        return prefix.startsWith("ssh-") ||
            prefix.startsWith("ecdsa-") ||
            prefix.startsWith("sk-") ||
            prefix.startsWith("-----BEGIN PUBLIC KEY-----")
    }

    private companion object {
        const val KEY_ALIAS = "amadeus_ssh_identity_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val SUPPORTED_PRIVATE_KEY_HEADERS = listOf(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "-----BEGIN RSA PRIVATE KEY-----",
            "-----BEGIN EC PRIVATE KEY-----",
            "-----BEGIN DSA PRIVATE KEY-----",
            "-----BEGIN PRIVATE KEY-----",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----"
        )
    }
}
