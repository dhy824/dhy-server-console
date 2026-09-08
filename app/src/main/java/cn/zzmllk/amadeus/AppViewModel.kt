package cn.zzmllk.amadeus

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.zzmllk.amadeus.data.AppConfig
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.service.TunnelRuntime
import cn.zzmllk.amadeus.service.TunnelService
import cn.zzmllk.amadeus.ssh.HostProbe
import cn.zzmllk.amadeus.ssh.SshEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

enum class ConsoleTarget { API, ASTRBOT }

data class ConsoleRequest(val title: String, val url: String)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    private val keyStore = SecureKeyStore(application)

    val config: StateFlow<AppConfig> = configStore.state
    val tunnel = TunnelRuntime.state

    private val mutableKeyImported = MutableStateFlow(keyStore.hasKey)
    val keyImported: StateFlow<Boolean> = mutableKeyImported.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableTerminalOutput = MutableStateFlow("连接服务器后，可在这里执行单条 SSH 命令。")
    val terminalOutput: StateFlow<String> = mutableTerminalOutput.asStateFlow()
    private val mutableSubscriptions = MutableStateFlow("")
    val subscriptions = mutableSubscriptions.asStateFlow()
    private val mutableSubscriptionLink = MutableStateFlow<String?>(null)
    val subscriptionLink = mutableSubscriptionLink.asStateFlow()

    fun closeSubscriptionLink() { mutableSubscriptionLink.value = null }

    fun subscriptionAction(request: JSONObject) {
        viewModelScope.launch {
            runBusy(null) {
                check(keyStore.hasKey) { "请先导入 SSH 私钥。" }
                val reply = withContext(Dispatchers.IO) {
                    JSONObject(SshEngine.subscriptionRequest(config.value, keyStore.read(), request.toString()))
                }
                check(reply.optBoolean("ok")) { reply.optString("error", "订阅操作失败，请刷新状态。") }
                val data = reply.getJSONObject("data")
                if (request.optString("action") == "reveal") {
                    mutableSubscriptionLink.value = data.getString("url")
                } else {
                    mutableSubscriptions.value = data.toString()
                }
            }
        }
    }

    private val mutableFingerprint = MutableStateFlow<String?>(null)
    val fingerprint: StateFlow<String?> = mutableFingerprint.asStateFlow()
    private var pendingProbe: HostProbe? = null

    private val mutableOpenConsole = MutableSharedFlow<ConsoleRequest>(extraBufferCapacity = 1)
    val openConsole: SharedFlow<ConsoleRequest> = mutableOpenConsole.asSharedFlow()

    fun updateConfig(transform: (AppConfig) -> AppConfig) {
        configStore.update(transform(config.value))
    }

    fun importPrivateKey(uri: Uri) {
        viewModelScope.launch {
            runBusy("SSH 私钥已安全导入。") {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取选择的文件。")
                }
                withContext(Dispatchers.IO) { keyStore.save(bytes) }
                mutableKeyImported.value = true
            }
        }
    }

    fun clearPrivateKey() {
        stopAllTunnels()
        keyStore.clear()
        mutableKeyImported.value = false
        mutableMessage.value = "已移除手机中的 SSH 私钥。"
    }

    fun testConnection() {
        viewModelScope.launch {
            runBusy(null) {
                check(keyStore.hasKey) { "请先导入 SSH 私钥。" }
                val probe = withContext(Dispatchers.IO) { SshEngine.probe(config.value, keyStore.read()) }
                pendingProbe = probe
                mutableFingerprint.value = probe.fingerprint
            }
        }
    }

    fun confirmHostTrust() {
        pendingProbe?.let { configStore.trustHost(it.keyType, it.encodedKey) }
        pendingProbe = null
        mutableFingerprint.value = null
        mutableMessage.value = "服务器主机密钥已信任，后续连接会严格校验。"
    }

    fun dismissHostTrust() {
        pendingProbe = null
        mutableFingerprint.value = null
    }

    fun open(target: ConsoleTarget) {
        viewModelScope.launch {
            runBusy(null) {
                val current = tunnel.value
                val alreadyActive = when (target) {
                    ConsoleTarget.API -> current.apiActive
                    ConsoleTarget.ASTRBOT -> current.astrBotActive
                }
                if (!alreadyActive) {
                    check(keyStore.hasKey) { "请先在设置中导入 SSH 私钥。" }
                    check(config.value.hasTrustedHost) { "请先在设置中测试连接并确认主机指纹。" }
                    val action = when (target) {
                        ConsoleTarget.API -> TunnelService.ACTION_START_API
                        ConsoleTarget.ASTRBOT -> TunnelService.ACTION_START_ASTRBOT
                    }
                    ContextCompat.startForegroundService(
                        getApplication(),
                        Intent(getApplication(), TunnelService::class.java).setAction(action)
                    )
                    withTimeout(25_000) {
                        TunnelRuntime.state.first { state ->
                            state.error != null || when (target) {
                                ConsoleTarget.API -> state.apiActive
                                ConsoleTarget.ASTRBOT -> state.astrBotActive
                            }
                        }.error?.let { error(it) }
                    }
                }
                val request = when (target) {
                    ConsoleTarget.API -> ConsoleRequest(
                        "反代 API 管理",
                        "http://127.0.0.1:${config.value.apiLocalPort}/management.html"
                    )
                    ConsoleTarget.ASTRBOT -> ConsoleRequest(
                        "AstrBot 控制台",
                        "http://127.0.0.1:${config.value.astrBotLocalPort}"
                    )
                }
                mutableOpenConsole.emit(request)
            }
        }
    }

    fun runSpeedTest() {
        viewModelScope.launch {
            runBusy("节点测速流程已完成，请在终端查看各组结果。") {
                check(keyStore.hasKey) { "请先导入 SSH 私钥。" }
                val output = withContext(Dispatchers.IO) {
                    SshEngine.runCommand(config.value, keyStore.read(), "/usr/local/sbin/mihomo-auto force")
                }
                appendTerminal("节点测速", output)
            }
        }
    }

    fun runTerminalCommand(command: String) {
        viewModelScope.launch {
            runBusy(null) {
                check(keyStore.hasKey) { "请先导入 SSH 私钥。" }
                val output = withContext(Dispatchers.IO) {
                    SshEngine.runCommand(config.value, keyStore.read(), command)
                }
                appendTerminal(command, output)
            }
        }
    }

    fun stopAllTunnels() {
        val intent = Intent(getApplication(), TunnelService::class.java).setAction(TunnelService.ACTION_STOP_ALL)
        getApplication<Application>().startService(intent)
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    private suspend fun runBusy(success: String?, block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        try {
            block()
            if (success != null) mutableMessage.value = success
        } catch (error: Exception) {
            mutableMessage.value = error.message ?: "操作失败。"
        } finally {
            mutableBusy.value = false
        }
    }

    private fun appendTerminal(command: String, output: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val block = "[$time] $ $command\n$output"
        mutableTerminalOutput.value = when {
            mutableTerminalOutput.value.startsWith("连接服务器后") -> block
            else -> mutableTerminalOutput.value + "\n\n" + block
        }.takeLast(40_000)
    }

    private fun readLimited(stream: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            require(output.size() <= 1_048_576) { "私钥文件不能超过 1 MB。" }
        }
        return output.toByteArray()
    }
}
