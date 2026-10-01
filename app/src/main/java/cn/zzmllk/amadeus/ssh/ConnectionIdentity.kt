package cn.zzmllk.amadeus.ssh

import cn.zzmllk.amadeus.data.AppConfig
import java.security.MessageDigest

/** Includes endpoint, ports, pinned server identity and imported key revision. */
fun connectionIdentity(config: AppConfig, keyRevision: String): String {
    val parts = listOf(config.host, config.sshPort.toString(), config.user,
        config.apiLocalPort.toString(), config.astrBotLocalPort.toString(),
        config.trustedHostKeyType, config.trustedHostKey, keyRevision)
    val encoded = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
}
