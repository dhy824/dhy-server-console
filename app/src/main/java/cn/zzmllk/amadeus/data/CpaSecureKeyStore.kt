package cn.zzmllk.amadeus.data

import android.content.Context
import android.os.Build
import android.security.KeyStoreException as AndroidKeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CpaSecureKeyStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "cpa_ssh_identities")

    fun hasKey(profileId: String): Boolean = synchronized(STORAGE_LOCK) {
        val file = atomicFile(profileId).baseFile
        listOf(file, File(file.path + ".bak"), previousFile(profileId).baseFile).any {
            it.isFile && it.length() in 21L..MAX_IDENTITY_PAYLOAD.toLong()
        }
    }

    fun revision(profileId: String): String = synchronized(STORAGE_LOCK) {
        if (!hasKey(profileId)) "missing" else {
            val bytes = readPayload(profileId)
            try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
            finally { bytes.fill(0) }
        }
    }

    fun save(profileId: String, privateKey: ByteArray) = synchronized(STORAGE_LOCK) {
        try {
            require(privateKey.size in 64..1_048_576) { "私钥文件大小不正确。" }
            require(!looksLikePublicKey(privateKey)) { "你选择的是 SSH 公钥（通常带 .pub 后缀），请改选对应的私钥文件。" }
            require(SUPPORTED_PRIVATE_KEY_HEADERS.any { containsAscii(privateKey, it) }) {
                "选择的文件不是受支持的 SSH 私钥。请选择 OpenSSH、RSA、EC 或 PKCS#8 私钥。"
            }
            if (hasKey(profileId)) {
                val oldPayload = readPayload(profileId)
                try { atomicWrite(previousFile(profileId), oldPayload) } finally { oldPayload.fill(0) }
            }
            val alias = aliasFor(profileId)
            val vault = CpaVault()
            val key = vault.create(alias)
            val (iv, ciphertext) = vault.encrypt(key, privateKey)
            try {
                val probe = vault.decrypt(key, iv, ciphertext)
                try { check(MessageDigest.isEqual(privateKey, probe)) { "私钥保存自检失败，原加密备份已保留。" } }
                finally { probe.fill(0) }
                val payload = IdentityRecord(alias, iv, ciphertext).encode()
                try { atomicWrite(profileId, payload) } finally { payload.fill(0) }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            privateKey.fill(0)
        }
    }

    fun read(profileId: String): ByteArray = synchronized(STORAGE_LOCK) {
        try { readEncryptedFile(atomicFile(profileId), profileId) }
        catch (primaryError: Exception) {
            try { readEncryptedFile(previousFile(profileId), profileId) }
            catch (backupError: Exception) {
                throw IdentityKeyException(
                    "此 CPA 档案的主私钥和加密备份均无法解密，请重新导入对应的 SSH 私钥。",
                    cause = backupError
                )
            }
        }
    }

    private fun readEncryptedFile(file: AtomicFile, profileId: String): ByteArray {
        val payload = try { file.readFully() }
        catch (error: Exception) { throw IdentityKeyException("无法读取此 CPA 档案的加密私钥文件。", cause = error) }
        return try {
            val record = IdentityRecord.decode(payload)
            try {
                require(record.alias == aliasFor(profileId) && ownedCpaIdentityAlias(record.alias)) { "CPA 档案私钥标识无效。" }
                CpaVault().decrypt(CpaVault().existing(record.alias)
                    ?: throw IdentityKeyException("此 CPA 档案的 Android Keystore 密钥已丢失，请重新导入对应的 SSH 私钥。"),
                    record.iv, record.ciphertext)
            } finally {
                record.iv.fill(0)
                record.ciphertext.fill(0)
            }
        } finally { payload.fill(0) }
    }

    fun delete(profileId: String) = synchronized(STORAGE_LOCK) {
        val atomic = atomicFile(profileId)
        atomic.delete()
        previousFile(profileId).delete()
        val alias = aliasFor(profileId)
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias) && ownedCpaIdentityAlias(alias)) keyStore.deleteEntry(alias)
        } catch (error: Exception) {
            throw IdentityKeyException("无法移除此 CPA 档案的 Android Keystore 密钥。", cause = error)
        }
    }

    private fun atomicFile(profileId: String): AtomicFile {
        profileUuid(profileId)
        if (!directory.exists()) check(directory.mkdirs()) { "无法创建 CPA 私钥存储。" }
        return AtomicFile(File(directory, "$profileId.enc"))
    }

    private fun previousFile(profileId: String): AtomicFile =
        AtomicFile(File(directory, "${profileUuid(profileId)}.previous.enc"))

    private fun readPayload(profileId: String): ByteArray = try {
        atomicFile(profileId).readFully()
    } catch (primaryError: Exception) {
        try { previousFile(profileId).readFully() }
        catch (backupError: Exception) {
            throw IdentityKeyException("无法读取此 CPA 档案的加密私钥，请重新导入对应的 SSH 私钥。", cause = backupError)
        }
    }

    private fun atomicWrite(profileId: String, payload: ByteArray) {
        atomicWrite(atomicFile(profileId), payload)
    }

    private fun atomicWrite(file: AtomicFile, payload: ByteArray) {
        val stream = file.startWrite()
        try {
            stream.write(payload)
            stream.flush()
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        val committed = file.readFully()
        try { check(MessageDigest.isEqual(committed, payload)) { "私钥保存校验失败，请重试导入；旧加密私钥已保留。" } }
        finally { committed.fill(0) }
    }

    private class CpaVault {
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun <T> access(block: () -> T): T {
            try { return block() } catch (error: Exception) {
                val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
                val platform = chain.filterIsInstance<AndroidKeyStoreException>().firstOrNull()
                val retry = Build.VERSION.SDK_INT >= 33 && platform?.isTransientFailure == true &&
                    platform.retryPolicy == AndroidKeyStoreException.RETRY_WITH_EXPONENTIAL_BACKOFF
                val message = when {
                    chain.any { it is AEADBadTagException || it is KeyPermanentlyInvalidatedException } ->
                        "CPA 档案私钥加密密钥已失效，请重新导入对应的 SSH 私钥。"
                    Build.VERSION.SDK_INT >= 33 && platform?.requiresUserAuthentication() == true ->
                        "本机密钥库需要解锁，请解锁手机后重新打开 CPA 控制台。"
                    retry -> "本机密钥库暂时不可用，请稍后重试。"
                    else -> "本机密钥库无法读取或使用 CPA 档案私钥。"
                }
                throw IdentityKeyException(message, retry, error)
            }
        }

        fun existing(alias: String): SecretKey? = access {
            require(ownedCpaIdentityAlias(alias))
            store().getKey(alias, null) as? SecretKey
        }

        fun create(alias: String): SecretKey = access {
            require(ownedCpaIdentityAlias(alias))
            val keyStore = store()
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return@access it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }

        fun encrypt(key: SecretKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> = access {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv to cipher.doFinal(plaintext)
        }

        fun decrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray = access {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }
    }

    private fun profileUuid(value: String): String = UUID.fromString(value).toString()
    private fun aliasFor(profileId: String): String = "amadeus_cpa_ssh_v1_" + profileUuid(profileId).replace("-", "")

    private fun containsAscii(source: ByteArray, marker: String): Boolean {
        val expected = marker.toByteArray(Charsets.US_ASCII)
        if (source.size < expected.size) return false
        for (start in 0..source.size - expected.size) {
            if (expected.indices.all { source[start + it] == expected[it] }) return true
        }
        return false
    }

    private fun looksLikePublicKey(source: ByteArray): Boolean {
        val prefix = source.take(128).toByteArray().toString(Charsets.US_ASCII).trimStart()
        return prefix.startsWith("ssh-") || prefix.startsWith("ecdsa-") || prefix.startsWith("sk-") ||
            prefix.startsWith("-----BEGIN PUBLIC KEY-----")
    }

    private companion object {
        val STORAGE_LOCK = Any()
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
