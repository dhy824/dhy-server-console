package cn.zzmllk.amadeus.data

import android.content.Context
import androidx.core.content.edit
import cn.zzmllk.amadeus.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppConfig(
    val host: String,
    val sshPort: Int,
    val user: String,
    val apiLocalPort: Int,
    val astrBotLocalPort: Int,
    val trustedHostKey: String = "",
    val trustedHostKeyType: String = ""
) {
    val hasTrustedHost: Boolean get() = trustedHostKey.isNotBlank()
}

class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("amadeus_config", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<AppConfig> = mutableState.asStateFlow()

    fun update(next: AppConfig) {
        val current = mutableState.value
        val normalized = next.copy(
            host = next.host.trim(),
            user = next.user.trim(),
            sshPort = next.sshPort.coerceIn(1, 65535),
            apiLocalPort = next.apiLocalPort.coerceIn(1024, 65535),
            astrBotLocalPort = next.astrBotLocalPort.coerceIn(1024, 65535),
            trustedHostKey = if (current.host != next.host.trim() || current.sshPort != next.sshPort) "" else next.trustedHostKey,
            trustedHostKeyType = if (current.host != next.host.trim() || current.sshPort != next.sshPort) "" else next.trustedHostKeyType
        )
        write(normalized)
    }

    fun trustHost(keyType: String, encodedKey: String) {
        write(mutableState.value.copy(trustedHostKeyType = keyType, trustedHostKey = encodedKey))
    }

    fun clearTrustedHost() {
        write(mutableState.value.copy(trustedHostKeyType = "", trustedHostKey = ""))
    }

    private fun read(): AppConfig {
        val defaults = AppConfig(
            host = appContext.getString(R.string.default_host),
            sshPort = appContext.getString(R.string.default_ssh_port).toIntOrNull() ?: 22,
            user = appContext.getString(R.string.default_user).ifBlank { "root" },
            apiLocalPort = appContext.getString(R.string.default_api_port).toIntOrNull() ?: 18317,
            astrBotLocalPort = appContext.getString(R.string.default_astrbot_port).toIntOrNull() ?: 16185
        )
        return AppConfig(
            host = preferences.getString("host", defaults.host).orEmpty(),
            sshPort = preferences.getInt("ssh_port", defaults.sshPort),
            user = preferences.getString("user", defaults.user).orEmpty(),
            apiLocalPort = preferences.getInt("api_port", defaults.apiLocalPort),
            astrBotLocalPort = preferences.getInt("astrbot_port", defaults.astrBotLocalPort),
            trustedHostKey = preferences.getString("trusted_host_key", "").orEmpty(),
            trustedHostKeyType = preferences.getString("trusted_host_key_type", "").orEmpty()
        )
    }

    private fun write(config: AppConfig) {
        preferences.edit {
            putString("host", config.host)
            putInt("ssh_port", config.sshPort)
            putString("user", config.user)
            putInt("api_port", config.apiLocalPort)
            putInt("astrbot_port", config.astrBotLocalPort)
            putString("trusted_host_key", config.trustedHostKey)
            putString("trusted_host_key_type", config.trustedHostKeyType)
        }
        mutableState.value = config
    }
}
