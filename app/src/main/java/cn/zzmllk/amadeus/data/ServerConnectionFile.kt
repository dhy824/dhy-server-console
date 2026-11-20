package cn.zzmllk.amadeus.data

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

internal class ServerConnectionFile(val profile: CpaServerProfile, val privateKey: ByteArray) {
    companion object {
        private val marker = "AMADEUS-SSH/1".encodeToByteArray()
        private val openssh = "-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray()
        private val fields = setOf("name", "host", "port", "user", "service", "hostKey")
        private val ed25519Prefix = byteArrayOf(0, 0, 0, 11) +
            "ssh-ed25519".encodeToByteArray() + byteArrayOf(0, 0, 0, 32)

        fun decode(bytes: ByteArray): ServerConnectionFile {
            val firstLine = bytes.indexOf(10.toByte())
            require(firstLine == marker.size && marker.indices.all { bytes[it] == marker[it] }) {
                "请选择维护端提供的服务器连接文件。"
            }
            val metadataEnd = (firstLine + 1 until minOf(bytes.size, 8192))
                .firstOrNull { bytes[it] == 10.toByte() }
                ?: throw IllegalArgumentException("连接文件的档案信息不完整。")
            val start = metadataEnd + 1
            require(bytes.size - start in 64..16384 && openssh.indices.all { bytes[start + it] == openssh[it] }) {
                "连接文件缺少可用的 SSH 私钥。"
            }
            val parsed = try {
                JSONObject(bytes.decodeToString(firstLine + 1, metadataEnd, throwOnInvalidSequence = true))
            } catch (_: JSONException) {
                throw IllegalArgumentException("连接文件的档案格式无法读取。")
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("连接文件的档案编码无效。")
            }
            require(parsed.keys().asSequence().toSet() == fields && parsed.opt("port") is Int) {
                "连接文件的档案信息不完整。"
            }
            val service = when (parsed.opt("service")) {
                "cpa" -> ServerService.CPA
                "searxng" -> ServerService.SEARXNG
                else -> throw IllegalArgumentException("连接文件的服务类型不受支持。")
            }
            val hostKey = parsed.opt("hostKey") as? String ?: ""
            val wire = runCatching { Base64.getDecoder().decode(hostKey) }.getOrNull()
            require(wire != null && wire.size == 51 && ed25519Prefix.indices.all { wire[it] == ed25519Prefix[it] }) {
                "连接文件的服务器主机指纹无效。"
            }
            val profile = CpaServerProfile(
                name = (parsed.opt("name") as? String).orEmpty(),
                host = (parsed.opt("host") as? String).orEmpty(),
                sshPort = parsed.getInt("port"),
                user = (parsed.opt("user") as? String).orEmpty(),
                trustedHostKey = hostKey,
                trustedHostKeyType = "ssh-ed25519",
                configuredServices = setOf(service)
            )
            CpaProfileCodec.validate(listOf(profile))
            return ServerConnectionFile(profile, bytes.copyOfRange(start, bytes.size))
        }
    }
}
