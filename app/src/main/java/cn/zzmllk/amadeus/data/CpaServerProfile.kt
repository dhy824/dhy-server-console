package cn.zzmllk.amadeus.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Base64
import java.util.UUID

const val CPA_CONSOLE_PATH = "/v0/resource/plugins/freetier/console"

enum class ServerService(val storageKey: String, val displayName: String) {
    CPA("cpa", "CPA"),
    TERMINAL("terminal", "终端"),
    API("api", "反代 API"),
    ASTRBOT("astrbot", "AstrBot"),
    ZAKO("zako", "Zako"),
    SUBSCRIPTIONS("subscriptions", "订阅管理"),
    SPEED_TEST("speed_test", "节点测速"),
    REVIEW("review", "审核"),
    DISTILLATION("distillation", "蒸馏"),
    UPDATES("updates", "服务器更新")
}

data class CpaServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val sshPort: Int = 22,
    val user: String,
    val trustedHostKey: String = "",
    val trustedHostKeyType: String = "",
    val localHostOctet: Int? = null,
    val publicKey: String = "",
    val apiLocalPort: Int = 18317,
    val astrBotLocalPort: Int = 16185,
    val configuredServices: Set<ServerService> = setOf(ServerService.CPA)
) {
    val hasTrustedHost: Boolean
        get() = trustedHostKey.isNotBlank() && trustedHostKeyType.isNotBlank()
    val localHost: String
        get() = "127.0.0.${localHostOctet ?: error("CPA 档案回环地址尚未分配。") }"
    fun supports(service: ServerService): Boolean = service in configuredServices

    fun toAppConfig() = AppConfig(
        host = host,
        sshPort = sshPort,
        user = user,
        apiLocalPort = apiLocalPort,
        astrBotLocalPort = astrBotLocalPort,
        trustedHostKey = trustedHostKey,
        trustedHostKeyType = trustedHostKeyType
    )
}

object CpaProfileCodec {
    const val MAX_PROFILES = 16

    fun encode(profiles: List<CpaServerProfile>): String {
        validate(profiles)
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("host", profile.host)
                    put("sshPort", profile.sshPort)
                    put("user", profile.user)
                    put("trustedHostKey", profile.trustedHostKey)
                    put("trustedHostKeyType", profile.trustedHostKeyType)
                    put("localHostOctet", profile.localHostOctet ?: JSONObject.NULL)
                    put("publicKey", profile.publicKey)
                    put("apiLocalPort", profile.apiLocalPort)
                    put("astrBotLocalPort", profile.astrBotLocalPort)
                    put("configuredServices", JSONArray().apply {
                        profile.configuredServices.forEach { put(it.storageKey) }
                    })
                })
            }
        }.toString()
    }

    fun decode(encoded: String?): List<CpaServerProfile> {
        if (encoded.isNullOrBlank()) return emptyList()
        val profiles = JSONArray(encoded).let { rows ->
            List(rows.length()) { index ->
                rows.getJSONObject(index).let { row ->
                    CpaServerProfile(
                        id = row.getString("id"),
                        name = row.getString("name"),
                        host = row.getString("host"),
                        sshPort = row.getInt("sshPort"),
                        user = row.getString("user"),
                        trustedHostKey = row.optString("trustedHostKey"),
                        trustedHostKeyType = row.optString("trustedHostKeyType"),
                        localHostOctet = row.optInt("localHostOctet", -1).takeIf { it in 2..17 },
                        publicKey = row.optString("publicKey"),
                        apiLocalPort = row.optInt("apiLocalPort", 18317),
                        astrBotLocalPort = row.optInt("astrBotLocalPort", 16185),
                        configuredServices = row.optJSONArray("configuredServices")?.let { services ->
                            List(services.length()) { services.optString(it) }
                                .mapNotNull { key -> ServerService.entries.firstOrNull { it.storageKey == key } }
                                .toSet()
                        } ?: setOf(ServerService.CPA)
                    )
                }
            }
        }
        validate(profiles)
        return profiles
    }

    fun normalize(profile: CpaServerProfile, previous: CpaServerProfile?): CpaServerProfile {
        val endpointChanged = previous != null &&
            (previous.host != profile.host.trim() || previous.sshPort != profile.sshPort)
        return profile.copy(
            name = profile.name.trim(),
            host = profile.host.trim(),
            user = profile.user.trim(),
            apiLocalPort = profile.apiLocalPort.coerceIn(1024, 65535),
            astrBotLocalPort = profile.astrBotLocalPort.coerceIn(1024, 65535),
            trustedHostKey = if (endpointChanged) "" else profile.trustedHostKey,
            trustedHostKeyType = if (endpointChanged) "" else profile.trustedHostKeyType,
            publicKey = if (endpointChanged) "" else profile.publicKey,
            localHostOctet = when {
                endpointChanged -> null
                previous != null -> previous.localHostOctet
                else -> profile.localHostOctet
            }
        )
    }

    fun assignLocalHosts(profiles: List<CpaServerProfile>, reserved: Set<Int>): List<CpaServerProfile> {
        val used = profiles.mapNotNull { it.localHostOctet }.toMutableSet()
        require(used.size == profiles.mapNotNull { it.localHostOctet }.size) { "CPA 档案回环地址重复。" }
        return profiles.map { profile ->
            if (profile.localHostOctet != null) profile else {
                val address = (2..17).firstOrNull { it !in used && it !in reserved }
                    ?: error("CPA 独立回环地址已用完。为保护各服务器的登录隔离，不会复用已删除档案的地址。")
                used += address
                profile.copy(localHostOctet = address)
            }
        }
    }

    fun validate(profiles: List<CpaServerProfile>) {
        require(profiles.size <= MAX_PROFILES) { "最多保存 $MAX_PROFILES 个 CPA 服务器档案。" }
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        val localHosts = mutableSetOf<Int>()
        profiles.forEach { profile ->
            require(runCatching { UUID.fromString(profile.id) }.isSuccess) { "CPA 档案标识无效。" }
            require(profile.name.trim().length in 1..64 && profile.name.none(Char::isISOControl)) {
                "服务器名称需为 1 到 64 个字符。"
            }
            require(profile.host.matches(Regex("^[A-Za-z0-9.-]{1,253}$"))) { "服务器地址格式不正确。" }
            require(profile.sshPort in 1..65535) { "SSH 端口无效。" }
            require(profile.apiLocalPort in 1024..65535) { "API 本机端口无效。" }
            require(profile.astrBotLocalPort in 1024..65535) { "AstrBot 本机端口无效。" }
            require(profile.apiLocalPort != profile.astrBotLocalPort) { "API 与 AstrBot 本机端口不能相同。" }
            require(profile.localHostOctet == null || profile.localHostOctet in 2..17) { "CPA 档案回环地址无效。" }
            require(profile.user.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "SSH 用户名格式不正确。" }
            require(profile.publicKey.length <= 8192 && profile.publicKey.none(Char::isISOControl)) {
                "SSH 公钥文本无效。"
            }
            if (profile.publicKey.isNotBlank()) {
                require(profile.publicKey.matches(Regex("^ssh-ed25519 [A-Za-z0-9+/]+={0,2} amadeus-cpa-[0-9a-f]{8}$"))) {
                    "此 CPA 档案的登记公钥格式无效。"
                }
            }
            require(profile.trustedHostKey.length <= 8192 && profile.trustedHostKeyType.length <= 80) {
                "服务器主机指纹数据无效。"
            }
            require((profile.trustedHostKey.isBlank() && profile.trustedHostKeyType.isBlank()) || profile.hasTrustedHost) {
                "服务器主机指纹信息不完整。"
            }
            if (profile.hasTrustedHost) {
                require(profile.trustedHostKey.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))) { "服务器主机指纹数据无效。" }
                require(runCatching { Base64.getDecoder().decode(profile.trustedHostKey) }.isSuccess) {
                    "服务器主机指纹数据无效。"
                }
                require(profile.trustedHostKeyType.matches(Regex("^[A-Za-z0-9@._+-]{1,80}$"))) {
                    "服务器主机密钥类型无效。"
                }
            }
            require(ids.add(profile.id)) { "CPA 档案标识重复。" }
            require(names.add(profile.name.trim().lowercase(Locale.ROOT))) { "已有同名 CPA 服务器档案。" }
            profile.localHostOctet?.let { require(localHosts.add(it)) { "CPA 档案回环地址重复。" } }
        }
    }
}

class CpaProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("amadeus_cpa_profiles", Context.MODE_PRIVATE)
    private val mutableProfiles = MutableStateFlow(readProfiles())
    private val mutableSelectedId = MutableStateFlow(readSelectedId(mutableProfiles.value))
    private val mutableActiveServerId = MutableStateFlow(readActiveServerId(mutableProfiles.value))

    val profiles: StateFlow<List<CpaServerProfile>> = mutableProfiles.asStateFlow()
    val selectedId: StateFlow<String?> = mutableSelectedId.asStateFlow()
    val activeServerId: StateFlow<String?> = mutableActiveServerId.asStateFlow()

    fun current(): List<CpaServerProfile> = readProfiles()

    fun find(id: String): CpaServerProfile? = current().firstOrNull { it.id == id }

    fun currentActiveServerId(): String? = readActiveServerId(current())

    fun save(profile: CpaServerProfile) {
        val current = current()
        val previous = current.firstOrNull { it.id == profile.id }
        val normalized = CpaProfileCodec.normalize(profile, previous)
        val endpointChanged = previous != null &&
            (previous.host != profile.host.trim() || previous.sshPort != profile.sshPort)
        val retiredHosts = readRetiredHostOctets() +
            listOfNotNull(previous?.localHostOctet.takeIf { endpointChanged })
        val next = CpaProfileCodec.assignLocalHosts(
            current.filterNot { it.id == normalized.id } + normalized,
            retiredHosts
        )
        CpaProfileCodec.validate(next)
        val selected = mutableSelectedId.value ?: normalized.id
        if (retiredHosts != readRetiredHostOctets()) {
            preferences.edit { putString(KEY_RETIRED_HOSTS, retiredHosts.sorted().joinToString(",")) }
        }
        persist(next, selected)
    }

    fun select(id: String?) {
        require(id == null || mutableProfiles.value.any { it.id == id }) { "所选 CPA 服务器档案不存在。" }
        persist(mutableProfiles.value, id)
    }

    fun selectActiveServer(id: String?) {
        require(id == null || mutableProfiles.value.any { it.id == id }) { "所选服务器档案不存在。" }
        persist(mutableProfiles.value, mutableSelectedId.value, id)
    }

    fun trustHost(id: String, keyType: String, encodedKey: String) {
        val profile = find(id) ?: error("CPA 服务器档案已删除，请重新选择。")
        save(profile.copy(trustedHostKeyType = keyType, trustedHostKey = encodedKey))
    }

    fun delete(id: String) {
        val removed = mutableProfiles.value.firstOrNull { it.id == id } ?: return
        val next = mutableProfiles.value.filterNot { it.id == id }
        if (next.size == mutableProfiles.value.size) return
        val retired = readRetiredHostOctets() + listOfNotNull(removed.localHostOctet)
        preferences.edit { putString(KEY_RETIRED_HOSTS, retired.sorted().joinToString(",")) }
        val selected = mutableSelectedId.value.takeUnless { it == id }
        val active = mutableActiveServerId.value.takeUnless { it == id }
        persist(next, selected, active)
    }

    private fun readProfiles(): List<CpaServerProfile> {
        val decoded = CpaProfileCodec.decode(preferences.getString(KEY_PROFILES, null))
        val assigned = CpaProfileCodec.assignLocalHosts(decoded, readRetiredHostOctets())
        if (assigned != decoded) preferences.edit { putString(KEY_PROFILES, CpaProfileCodec.encode(assigned)) }
        return assigned
    }

    private fun readSelectedId(profiles: List<CpaServerProfile>): String? =
        preferences.getString(KEY_SELECTED, null)?.takeIf { id -> profiles.any { it.id == id } }

    private fun readActiveServerId(profiles: List<CpaServerProfile>): String? =
        preferences.getString(KEY_ACTIVE_SERVER, null)?.takeIf { id -> profiles.any { it.id == id } }

    private fun readRetiredHostOctets(): Set<Int> = preferences.getString(KEY_RETIRED_HOSTS, "").orEmpty()
        .split(',').mapNotNull(String::toIntOrNull).filter { it in 2..17 }.toSet()

    private fun persist(
        profiles: List<CpaServerProfile>,
        selectedId: String?,
        activeServerId: String? = mutableActiveServerId.value
    ) {
        CpaProfileCodec.validate(profiles)
        require(activeServerId == null || profiles.any { it.id == activeServerId }) { "所选服务器档案不存在。" }
        preferences.edit {
            putString(KEY_PROFILES, CpaProfileCodec.encode(profiles))
            if (selectedId == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, selectedId)
            if (activeServerId == null) remove(KEY_ACTIVE_SERVER) else putString(KEY_ACTIVE_SERVER, activeServerId)
        }
        mutableProfiles.value = profiles
        mutableSelectedId.value = selectedId
        mutableActiveServerId.value = activeServerId
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_SELECTED = "selected_id"
        const val KEY_ACTIVE_SERVER = "active_server_id"
        const val KEY_RETIRED_HOSTS = "retired_loopback_addresses"
    }
}
