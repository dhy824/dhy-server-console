package cn.zzmllk.amadeus.ssh

import android.util.Base64
import android.os.SystemClock
import cn.zzmllk.amadeus.data.AppConfig
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.security.MessageDigest

data class HostProbe(
    val keyType: String,
    val encodedKey: String,
    val fingerprint: String
)

object SshEngine {
    fun subscriptionRequest(config: AppConfig, privateKey: ByteArray, request: String): String {
        require(request.length <= 12000) { "订阅请求过长。" }
        val session = connect(config, privateKey)
        val channel = session.openChannel("exec") as ChannelExec
        val output = BoundedOutput(262_144)
        val errors = BoundedOutput(65_536)
        return try {
            channel.setCommand("/usr/local/sbin/mihomo-subscriptions")
            channel.setInputStream(ByteArrayInputStream((request + "\n").toByteArray(Charsets.UTF_8)))
            channel.setOutputStream(output, true)
            channel.setErrStream(errors, true)
            channel.connect(10_000)
            val deadline = SystemClock.elapsedRealtime() + 900_000
            while (!channel.isClosed && SystemClock.elapsedRealtime() < deadline) {
                check(!output.exceeded && !errors.exceeded) { "服务器响应超过大小上限，请刷新核对结果。" }
                Thread.sleep(50)
            }
            check(channel.isClosed) { "等待超时，服务器可能仍在处理；请刷新核对结果。" }
            check(!output.exceeded && !errors.exceeded) { "服务器响应超过大小上限，请刷新核对结果。" }
            check(channel.exitStatus == 0) { "订阅管理连接失败，请检查服务器组件和 SSH 设置。" }
            output.text()
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    fun connect(config: AppConfig, privateKey: ByteArray, allowUntrusted: Boolean = false): Session {
        validate(config)
        val jsch = JSch()
        if (!allowUntrusted) {
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            jsch.setHostKeyRepository(PinnedHostKeyRepository(config))
        }
        try {
            jsch.addIdentity("amadeus-android", privateKey, null, null)
        } finally {
            privateKey.fill(0)
        }
        return jsch.getSession(config.user, config.host, config.sshPort).apply {
            setConfig("PreferredAuthentications", "publickey")
            setConfig("StrictHostKeyChecking", if (allowUntrusted) "no" else "yes")
            // Keep JSch's complete conventional default set. Only optional post-quantum KEX is
            // omitted from the mobile build; current OpenSSH ECDH/X25519/DH and AES choices remain.
            setConfig(
                "kex",
                "curve25519-sha256,curve25519-sha256@libssh.org," +
                    "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
                    "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512," +
                    "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"
            )
            setConfig(
                "cipher.s2c",
                "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr"
            )
            setConfig(
                "cipher.c2s",
                "aes128-gcm@openssh.com,aes256-gcm@openssh.com,aes128-ctr,aes192-ctr,aes256-ctr"
            )
            serverAliveInterval = 15_000
            serverAliveCountMax = 3
            connect(15_000)
        }
    }

    fun probe(config: AppConfig, privateKey: ByteArray): HostProbe {
        val session = connect(config, privateKey, allowUntrusted = true)
        return try {
            val hostKey = session.hostKey ?: error("服务器未返回主机密钥。")
            val raw = Base64.decode(hostKey.key, Base64.DEFAULT)
            HostProbe(
                keyType = hostKey.type,
                encodedKey = Base64.encodeToString(raw, Base64.NO_WRAP),
                fingerprint = sha256Fingerprint(raw)
            )
        } finally {
            session.disconnect()
        }
    }

    fun runCommand(config: AppConfig, privateKey: ByteArray, command: String): String {
        require(command.isNotBlank()) { "命令不能为空。" }
        require(command.length <= 4096) { "命令过长。" }
        val session = connect(config, privateKey)
        val channel = session.openChannel("exec") as ChannelExec
        val standard = BoundedOutput(524_288)
        val error = BoundedOutput(65_536)
        return try {
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setOutputStream(standard, true)
            channel.setErrStream(error, true)
            channel.connect(10_000)
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (!channel.isClosed && SystemClock.elapsedRealtime() < deadline) {
                check(!standard.exceeded && !error.exceeded) { "命令输出超过大小上限，已关闭本机通道。" }
                Thread.sleep(40)
            }
            if (!channel.isClosed) throw IllegalStateException("远程命令执行超时。")
            check(!standard.exceeded && !error.exceeded) { "命令输出超过大小上限，已关闭本机通道。" }
            val stdout = standard.text().trimEnd()
            val stderr = error.text().trimEnd()
            val combined = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
            if (channel.exitStatus != 0) {
                throw IllegalStateException(
                    if (combined.isBlank()) "远程命令失败，退出代码 ${channel.exitStatus}。"
                    else "$combined\n退出代码 ${channel.exitStatus}。"
                )
            }
            combined.ifBlank { "命令执行完成，没有输出。" }
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }

    private fun validate(config: AppConfig) {
        require(config.host.matches(Regex("^[A-Za-z0-9.-]+$"))) { "服务器地址格式不正确。" }
        require(config.user.matches(Regex("^[A-Za-z0-9._-]+$"))) { "SSH 用户名格式不正确。" }
        require(config.sshPort in 1..65535) { "SSH 端口无效。" }
    }

    private fun sha256Fingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

}

private class PinnedHostKeyRepository(config: AppConfig) : HostKeyRepository {
    private val expected = Base64.decode(config.trustedHostKey, Base64.DEFAULT)
    private val expectedType = config.trustedHostKeyType

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.CHANGED
        return if (MessageDigest.isEqual(expected, key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "Amadeus pinned host key"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
