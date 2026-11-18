package cn.zzmllk.amadeus

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.zzmllk.amadeus.data.AppConfig
import cn.zzmllk.amadeus.data.CPA_CONSOLE_PATH
import cn.zzmllk.amadeus.data.CpaProfileStore
import cn.zzmllk.amadeus.data.CpaSecureKeyStore
import cn.zzmllk.amadeus.data.CpaServerProfile
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.service.TunnelRuntime
import cn.zzmllk.amadeus.service.TunnelService
import cn.zzmllk.amadeus.ssh.HostProbe
import cn.zzmllk.amadeus.ssh.SshEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import java.util.UUID
import cn.zzmllk.amadeus.service.TunnelState
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

const val REVIEW_ROUTE = "/#/plugin-page/astrbot_plugin_identity_guard/review"

const val DISTILLATION_ROUTE = "/#/plugin-page/astrbot_plugin_identity_guard/analysis"

enum class ConsoleTarget { API, ASTRBOT, ZAKO }

data class ConsoleRequest(
    val title: String,
    val url: String,
    val tunnelAction: String,
    val cpaProfileId: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    private val keyStore = SecureKeyStore(application)
    private val cpaProfileStore = CpaProfileStore(application)
    private val cpaKeyStore = CpaSecureKeyStore(application)

    val config: StateFlow<AppConfig> = configStore.state
    val cpaProfiles: StateFlow<List<CpaServerProfile>> = cpaProfileStore.profiles
    val selectedCpaProfileId: StateFlow<String?> = cpaProfileStore.selectedId
    private val mutableCpaKeys = MutableStateFlow(
        cpaProfileStore.current().filter { cpaKeyStore.hasKey(it.id) }.map { it.id }.toSet()
    )
    val cpaKeyProfiles: StateFlow<Set<String>> = mutableCpaKeys.asStateFlow()
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
                val reply = runInterruptible(Dispatchers.IO) {
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
    private val mutableFingerprintTarget = MutableStateFlow("当前 SSH 连接")
    val fingerprintTarget: StateFlow<String> = mutableFingerprintTarget.asStateFlow()
    private var pendingProbe: HostProbe? = null
    private var pendingProbeConfig: AppConfig? = null
    private var pendingCpaProfile: CpaServerProfile? = null

    private val mutableOpenConsole = MutableSharedFlow<ConsoleRequest>(extraBufferCapacity = 1)
    val openConsole: SharedFlow<ConsoleRequest> = mutableOpenConsole.asSharedFlow()

    fun updateConfig(transform: (AppConfig) -> AppConfig) {
        val previous = config.value
        configStore.update(transform(previous))
        if (config.value != previous) stopDefaultTunnels()
    }

    fun saveCpaProfile(profile: CpaServerProfile): Boolean = try {
        val previous = cpaProfileStore.find(profile.id)
        cpaProfileStore.save(profile)
        if (previous != null) stopCpaTunnel()
        mutableMessage.value = "CPA 服务器档案已保存。"
        true
    } catch (error: Exception) {
        mutableMessage.value = error.message ?: "无法保存 CPA 服务器档案。"
        false
    }

    fun deleteCpaProfile(profileId: String) {
        try {
            cpaProfileStore.delete(profileId)
            stopCpaTunnel()
            cpaKeyStore.delete(profileId)
            mutableCpaKeys.value = mutableCpaKeys.value - profileId
            mutableMessage.value = "CPA 服务器档案已删除。"
        } catch (error: Exception) {
            mutableMessage.value = error.message ?: "无法删除 CPA 服务器档案。"
        }
    }

    fun importCpaPrivateKey(uri: Uri, profileId: String) {
        viewModelScope.launch {
            runBusy("此 CPA 档案的 SSH 私钥已单独加密保存。") {
                check(cpaProfileStore.find(profileId) != null) { "CPA 服务器档案已删除，请刷新后重试。" }
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取选择的文件。")
                }
                withContext(Dispatchers.IO) { cpaKeyStore.save(profileId, bytes) }
                mutableCpaKeys.value = mutableCpaKeys.value + profileId
                stopCpaTunnel()
            }
        }
    }

    fun clearCpaPrivateKey(profileId: String) {
        viewModelScope.launch {
            runBusy("已移除此 CPA 档案的 SSH 私钥。") {
                stopCpaTunnel()
                withContext(Dispatchers.IO) { cpaKeyStore.delete(profileId) }
                mutableCpaKeys.value = mutableCpaKeys.value - profileId
            }
        }
    }

    fun selectCpaProfile(profileId: String?) {
        try {
            if (selectedCpaProfileId.value != profileId) stopCpaTunnel()
            cpaProfileStore.select(profileId)
        } catch (error: Exception) {
            mutableMessage.value = error.message ?: "无法选择 CPA 服务器档案。"
        }
    }

    fun importPrivateKey(uri: Uri) {
        viewModelScope.launch {
            runBusy("SSH 私钥已安全导入。") {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取选择的文件。")
                }
                withContext(Dispatchers.IO) { keyStore.save(bytes) }
                stopDefaultTunnels()
                mutableKeyImported.value = true
            }
        }
    }

    fun clearPrivateKey() {
        viewModelScope.launch {
            runBusy("已移除手机中的 SSH 私钥。") {
                stopDefaultTunnels()
                try { withContext(Dispatchers.IO) { keyStore.clear() } }
                finally { mutableKeyImported.value = keyStore.hasKey }
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            runBusy(null) {
                check(keyStore.hasKey) { "请先导入 SSH 私钥。" }
                val checkedConfig = config.value
                val probe = runInterruptible(Dispatchers.IO) { SshEngine.probe(checkedConfig, keyStore.read()) }
                check(checkedConfig.host == config.value.host && checkedConfig.sshPort == config.value.sshPort) { "连接设置已变化，请重新测试主机指纹。" }
                pendingCpaProfile = null
                mutableFingerprintTarget.value = "当前 SSH 连接"
                pendingProbeConfig = checkedConfig
                pendingProbe = probe
                mutableFingerprint.value = probe.fingerprint
            }
        }
    }

    fun testCpaProfile(profileId: String) {
        viewModelScope.launch {
            runBusy(null) {
                val checkedProfile = cpaProfileStore.find(profileId) ?: error("CPA 服务器档案已删除，请刷新后重试。")
                check(cpaKeyStore.hasKey(profileId)) { "请先为此 CPA 档案单独导入 SSH 私钥。" }
                val probe = runInterruptible(Dispatchers.IO) {
                    SshEngine.probe(checkedProfile.toAppConfig(), cpaKeyStore.read(profileId))
                }
                check(cpaProfileStore.find(profileId) == checkedProfile) { "CPA 服务器档案已变化，请重新测试主机指纹。" }
                pendingProbeConfig = null
                pendingCpaProfile = checkedProfile
                pendingProbe = probe
                mutableFingerprintTarget.value = "CPA 档案：${checkedProfile.name}"
                mutableFingerprint.value = probe.fingerprint
            }
        }
    }

    fun confirmHostTrust() {
        val cpaProfile = pendingCpaProfile
        if (cpaProfile != null) {
            val current = cpaProfileStore.find(cpaProfile.id)
            if (current == null || current.host != cpaProfile.host || current.sshPort != cpaProfile.sshPort || current.user != cpaProfile.user) {
                dismissHostTrust()
                mutableMessage.value = "CPA 服务器档案已变化，请重新测试。"
                return
            }
            pendingProbe?.let { cpaProfileStore.trustHost(cpaProfile.id, it.keyType, it.encodedKey) }
            pendingProbe = null
            pendingCpaProfile = null
            mutableFingerprint.value = null
            mutableMessage.value = "CPA 服务器主机指纹已固定，后续连接会严格校验。"
            return
        }
        val checkedConfig = pendingProbeConfig
        if (checkedConfig?.host != config.value.host || checkedConfig?.sshPort != config.value.sshPort) {
            dismissHostTrust(); mutableMessage.value = "连接设置已变化，请重新测试。"; return
        }
        stopAllTunnels()
        pendingProbe?.let { configStore.trustHost(it.keyType, it.encodedKey) }
        pendingProbe = null
        pendingCpaProfile = null
        mutableFingerprint.value = null
        mutableMessage.value = "服务器主机密钥已信任，后续连接会严格校验。"
    }

    fun dismissHostTrust() {
        pendingProbe = null
        pendingProbeConfig = null
        pendingCpaProfile = null
        mutableFingerprint.value = null
    }

    fun openCpaConsole(profileId: String?) {
        viewModelScope.launch {
            runBusy(null) {
                check(profileId != null) { "请先在设置中添加并选择 CPA 服务器档案。" }
                val profile = cpaProfileStore.find(profileId) ?: error("所选 CPA 服务器档案不存在。")
                check(cpaKeyStore.hasKey(profileId)) { "请先为此 CPA 档案单独导入 SSH 私钥。" }
                check(profile.hasTrustedHost) { "请先测试此 CPA 档案并核对、确认服务器指纹。" }
                val requestId = UUID.randomUUID().toString()
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), TunnelService::class.java)
                        .setAction(TunnelService.ACTION_START_CPA)
                        .putExtra(TunnelService.EXTRA_REQUEST_ID, requestId)
                        .putExtra(TunnelService.EXTRA_CPA_PROFILE_ID, profile.id)
                )
                val state = withTimeout(35_000) {
                    TunnelRuntime.state.first { current ->
                        current.requestId == requestId && current.cpaProfileId == profile.id && !current.connecting &&
                            (current.error != null || current.cpaActive)
                    }
                }
                state.error?.let { error(it) }
                val localPort = state.cpaLocalPort ?: error("CPA 本机回环转发未建立。")
                mutableOpenConsole.emit(
                    ConsoleRequest(
                        "CPA 插件控制台 · ${profile.name}",
                        "http://${state.cpaLocalHost ?: error("CPA 隔离回环地址缺失。")}:$localPort$CPA_CONSOLE_PATH",
                        TunnelService.ACTION_START_CPA,
                        profile.id
                    )
                )
            }
        }
    }

    fun stopCpaTunnel() {
        getApplication<Application>().startService(
            Intent(getApplication(), TunnelService::class.java).setAction(TunnelService.ACTION_STOP_CPA)
        )
    }

    fun open(target: ConsoleTarget, analysis: Boolean = false, review: Boolean = false) {
        viewModelScope.launch {
            runBusy(null) {
                check(keyStore.hasKey) { "请先在设置中导入 SSH 私钥。" }
                check(config.value.hasTrustedHost) { "请先在设置中测试连接并确认主机指纹。" }
                val requestId = UUID.randomUUID().toString()
                val action = when (target) {
                    ConsoleTarget.API -> TunnelService.ACTION_START_API
                    ConsoleTarget.ASTRBOT -> TunnelService.ACTION_START_ASTRBOT
                    ConsoleTarget.ZAKO -> TunnelService.ACTION_START_ZAKO
                }
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), TunnelService::class.java).setAction(action)
                        .putExtra(TunnelService.EXTRA_REQUEST_ID, requestId)
                )
                val tunnelState = withTimeout(35_000) {
                    TunnelRuntime.state.first { state ->
                        state.requestId == requestId && !state.connecting && (state.error != null || when (target) {
                            ConsoleTarget.API -> state.apiActive
                            ConsoleTarget.ASTRBOT -> state.astrBotActive
                            ConsoleTarget.ZAKO -> state.zakoActive
                        })
                    }
                }
                tunnelState.error?.let { error(it) }
                val request = when (target) {
                    ConsoleTarget.API -> ConsoleRequest(
                        "反代 API 管理",
                        "http://127.0.0.1:${config.value.apiLocalPort}/management.html", action
                    )
                    ConsoleTarget.ASTRBOT -> ConsoleRequest(
                        if (review) "AstrBot 审核中心" else if (analysis) "AstrBot 蒸馏中心" else "AstrBot 控制台",
                        "http://127.0.0.1:${config.value.astrBotLocalPort}" + (if (review) REVIEW_ROUTE else if (analysis) DISTILLATION_ROUTE else ""), action
                    )
                    ConsoleTarget.ZAKO -> ConsoleRequest(
                        "Zako WebUI",
                        "http://127.0.0.1:${tunnelState.zakoLocalPort ?: error("Zako 本地端口未建立。")}/",
                        action
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
                val output = runInterruptible(Dispatchers.IO) {
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
                val output = runInterruptible(Dispatchers.IO) {
                    SshEngine.runCommand(config.value, keyStore.read(), command)
                }
                appendTerminal(command, output)
            }
        }
    }

    fun stopAllTunnels() {
        val intent = Intent(getApplication(), TunnelService::class.java).setAction(TunnelService.ACTION_STOP_ALL)
        getApplication<Application>().stopService(intent)
        TunnelRuntime.update(TunnelState(message = "隧道已停止"))
    }

    private fun stopDefaultTunnels() {
        getApplication<Application>().startService(
            Intent(getApplication(), TunnelService::class.java).setAction(TunnelService.ACTION_STOP_DEFAULT)
        )
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
        } catch (error: CancellationException) {
            throw error
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
