package cn.zzmllk.amadeus.data

import android.content.Context

data class ActiveServerConnection(
    val profileId: String?,
    val name: String,
    val config: AppConfig,
    val localHost: String,
    val keyRevision: String,
    val hasKey: Boolean,
    val configuredServices: Set<ServerService>
) {
    fun supports(service: ServerService): Boolean = service in configuredServices
}

/** Resolves the selected server without moving or rewriting the legacy default credentials. */
class ActiveServerStore(context: Context) {
    private val defaults = ConfigStore(context)
    private val defaultKey = SecureKeyStore(context)
    private val profiles = CpaProfileStore(context)
    private val profileKeys = CpaSecureKeyStore(context)

    fun current(): ActiveServerConnection = forProfile(profiles.currentActiveServerId())

    fun forProfile(profileId: String?): ActiveServerConnection {
        val profile = profileId?.let(profiles::find)
        if (profileId != null && profile == null) error("所选服务器档案不存在，请重新选择。")
        return if (profile == null) {
            val config = defaults.current()
            ActiveServerConnection(
                profileId = null,
                name = "阿里云",
                config = config,
                localHost = "127.0.0.1",
                keyRevision = activeServerKeyRevision(null, defaultKey.revision),
                hasKey = defaultKey.hasKey,
                configuredServices = aliyunServices
            )
        } else {
            ActiveServerConnection(
                profileId = profile.id,
                name = profile.name,
                config = profile.toAppConfig(),
                localHost = profile.localHost,
                keyRevision = activeServerKeyRevision(profile.id, profileKeys.revision(profile.id)),
                hasKey = profileKeys.hasKey(profile.id),
                configuredServices = profile.configuredServices
            )
        }
    }

    fun readKey(connection: ActiveServerConnection): ByteArray {
        check(current() == connection) { "当前服务器或私钥已变化，请重新选择后重试。" }
        return connection.profileId?.let(profileKeys::read) ?: defaultKey.read()
    }
}

internal val aliyunServices = ServerService.entries.toSet() - ServerService.CPA

internal fun activeServerKeyRevision(profileId: String?, keyRevision: String): String =
    if (profileId == null) keyRevision else "$profileId:$keyRevision"
