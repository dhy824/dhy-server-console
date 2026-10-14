package cn.zzmllk.amadeus.data

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.KeyStoreException as AndroidKeyStoreException
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException

class SecureKeyStore(context: Context) {
    private val keyFile = File(context.filesDir, "ssh_identity.enc")
    private val previousFile = File(context.filesDir, "ssh_identity.previous.enc")
    private val storage = StoredIdentity(AndroidVault(), ::readPayload, ::writePayload)

    val hasKey: Boolean get() = synchronized(STORAGE_LOCK) {
        listOf(keyFile, File(keyFile.path + ".bak")).any { it.isFile && it.length() in 21L..MAX_IDENTITY_PAYLOAD.toLong() }
    }
    val revision: String get() = synchronized(STORAGE_LOCK) {
        if (!hasKey) return@synchronized "missing"
        val payload = readPayload()
        try { MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) } }
        finally { payload.fill(0) }
    }

    fun save(privateKey: ByteArray) {
        synchronized(STORAGE_LOCK) {
        try {
            require(privateKey.size in 64..1_048_576) { "私钥文件大小不正确。" }
            require(!looksLikePublicKey(privateKey)) {
                "你选择的是 SSH 公钥（通常带 .pub 后缀），请改选没有 .pub 后缀的私钥文件。"
            }
            require(SUPPORTED_PRIVATE_KEY_HEADERS.any { containsAscii(privateKey, it) }) {
                "选择的文件不是受支持的 SSH 私钥。请选择 OpenSSH、RSA、EC 或 PKCS#8 私钥。"
            }

            storage.replace(privateKey)
        } finally { privateKey.fill(0) }
        }
    }

    private fun atomicWrite(file: File, payload: ByteArray) {
            val atomicFile = AtomicFile(file)
            val stream = atomicFile.startWrite()
            try {
                stream.write(payload)
                stream.flush()
                atomicFile.finishWrite(stream)
            } catch (error: Exception) {
                atomicFile.failWrite(stream)
                throw error
            }
    }

    private fun readPayload(): ByteArray {
        check(hasKey) { "尚未导入 SSH 私钥。" }
        return try { AtomicFile(keyFile).readFully() }
        catch (error: Exception) { throw IdentityKeyException("无法读取本机私钥存储，请在设置中重新导入原 SSH 私钥。", cause = error) }
    }

    private fun writePayload(payload: ByteArray) {
        if (hasKey) {
            val old = readPayload()
            try { atomicWrite(previousFile, old) } finally { old.fill(0) }
        }
        atomicWrite(keyFile, payload)
        val committed = readPayload()
        try { check(MessageDigest.isEqual(committed, payload)) { "私钥保存校验失败，请重试导入；原加密备份已保留。" } }
        finally { committed.fill(0) }
    }

    fun read(): ByteArray = synchronized(STORAGE_LOCK) { storage.read() }

    fun clear() = synchronized(STORAGE_LOCK) {
        AtomicFile(keyFile).delete()
        AtomicFile(previousFile).delete()
        AndroidVault().clearOwned()
    }

    private class AndroidVault : IdentityCipherVault {
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        private fun <T> access(block: () -> T): T {
            try { return block() } catch (error: Exception) {
                val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
                val platform = chain.filterIsInstance<AndroidKeyStoreException>().firstOrNull()
                val code = if (Build.VERSION.SDK_INT >= 33) platform?.numericErrorCode else null
                val retry = Build.VERSION.SDK_INT >= 33 && platform?.isTransientFailure == true &&
                    platform.retryPolicy == AndroidKeyStoreException.RETRY_WITH_EXPONENTIAL_BACKOFF
                val message = when {
                    chain.any { it is AEADBadTagException || it is KeyPermanentlyInvalidatedException } ->
                        "本机加密密钥已失效，请在设置中点“更换私钥”，重新导入原 SSH 私钥。"
                    Build.VERSION.SDK_INT >= 33 && platform?.requiresUserAuthentication() == true ->
                        "本机密钥库需要解锁，请解锁手机后重新打开通道。"
                    retry -> "本机密钥库暂时不可用，请稍后重新打开通道。"
                    else -> "本机密钥库无法读取或使用密钥；请重启手机后重试，仍失败时在设置中重新导入原 SSH 私钥。"
                }
                throw IdentityKeyException(message + (code?.let { "（Keystore 错误 $it）" } ?: ""), retry, error)
            }
        }
        override fun existing(alias: String): SecretKey? = access { store().getKey(alias, null) as? SecretKey }
        override fun create(alias: String): SecretKey = access {
        val keyStore = store()
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return@access it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
        }
        override fun encrypt(key: SecretKey, plaintext: ByteArray) = access {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv to cipher.doFinal(plaintext)
        }
        override fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray) = access {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }
        fun clearOwned() = access {
            val keyStore = store()
            keyStore.aliases().toList().filter(::ownedIdentityAlias).forEach(keyStore::deleteEntry)
        }
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
        val STORAGE_LOCK = Any()
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
