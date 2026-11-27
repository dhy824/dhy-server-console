package cn.zzmllk.amadeus

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.zzmllk.amadeus.data.AppConfig
import cn.zzmllk.amadeus.data.ActiveServerConnection
import cn.zzmllk.amadeus.data.ActiveServerStore
import cn.zzmllk.amadeus.data.CpaProfileStore
import cn.zzmllk.amadeus.data.CpaSecureKeyStore
import cn.zzmllk.amadeus.data.CpaServerProfile
import cn.zzmllk.amadeus.data.ProfileConsolePage
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.data.ServerConnectionFile
import cn.zzmllk.amadeus.data.ServerService
import cn.zzmllk.amadeus.service.TunnelRuntime
import cn.zzmllk.amadeus.service.TunnelService
import cn.zzmllk.amadeus.ssh.HostProbe
import cn.zzmllk.amadeus.ssh.SshEngine
import cn.zzmllk.amadeus.ssh.tunnelFailure
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
    val cpaProfileId: String? = null,
    val serverProfileId: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ConfigStore(application)
    private val keyStore = SecureKeyStore(application)
    private val cpaProfileStore = CpaProfileStore(application)
    private val cpaKeyStore = CpaSecureKeyStore(application)
    private val activeServerStore = ActiveServerStore(application)

    val config: StateFlow<AppConfig> = configStore.state
    val cpaProfiles: StateFlow<List<CpaServerProfile>> = cpaProfileStore.profiles
    val activeServerId: StateFlow<String?> = cpaProfileStore.activeServerId
    private val mutableCpaKeys = MutableStateFlow(
        cpaProfileStore.current().filter { cpaKeyStore.hasKey(it.id) }.map { it.id }.toSet()
    )
    val cpaKeyProfiles: StateFlow<Set<String>> = mutableCpaKeys.asStateFlow()
    private val mutableSharedCpaKey = MutableStateFlow(cpaKeyStore.hasSharedKey())
    val sharedCpaKeyImported: StateFlow<Boolean> = mutableSharedCpaKey.asStateFlow()
    private val mutableSharedCpaPublicKey = MutableStateFlow(cpaProfileStore.currentSharedPublicKey())
    val sharedCpaPublicKey: StateFlow<String> = mutableSharedCpaPublicKey.asStateFlow()
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

    fun activeServer(): ActiveServerConnection = activeServerStore.current()

    fun selectActiveServer(profileId: String?) {
        try {
            val previous = activeServerStore.current()
            val next = activeServerStore.forProfile(profileId)
            if (previous != next) stopAllTunnels()
            cpaProfileStore.selectActiveServer(profileId)
            cpaProfileStore.select(profileId)
            mutableSubscriptions.value = ""
            mutableSubscriptionLink.value = null
            mutableMessage.value = "当前服务器已切换为${activeServerStore.current().name}；连接、命令和后续操作将使用该服务器档案。"
        } catch (error: Exception) {
            mutableMessage.value = error.message ?: "无法切换当前服务器。"
        }
    }

    private fun readActiveKey(connection: ActiveServerConnection): ByteArray = activeServerStore.readKey(connection)

    fun subscriptionAction(request: JSONObject) {
        viewModelScope.launch {
            runBusy(null) {
                val selected = activeServerStore.current()
                check(selected.supports(ServerService.SUBSCRIPTIONS)) { "当前服务器未配置订阅管理。" }
                check(selected.hasKey && selected.config.hasTrustedHost) { "请先为当前服务器配置 SSH 私钥并确认主机指纹。" }
                val identity = cn.zzmllk.amadeus.ssh.connectionIdentity(selected.config, selected.keyRevision)
                val privateKey = readActiveKey(selected)
                val reply = runInterruptible(Dispatchers.IO) {
                    try { JSONObject(SshEngine.subscriptionRequest(selected.config, privateKey, request.toString())) }
                    finally { privateKey.fill(0) }
                }
                val current = activeServerStore.current()
                check(cn.zzmllk.amadeus.ssh.connectionIdentity(current.config, current.keyRevision) == identity) {
                    "当前服务器或私钥已切换，未应用本次订阅操作。"
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
        val endpointChanged = previous != null &&
            (previous.host != profile.host.trim() || previous.sshPort != profile.sshPort)
        cpaProfileStore.save(profile)
        if (previous != null) {
            stopCpaTunnel()
            if (activeServerId.value == profile.id) stopAllTunnels()
        }
        if (endpointChanged) {
            cpaKeyStore.delete(profile.id)
            mutableCpaKeys.value = mutableCpaKeys.value - profile.id
            mutableMessage.value = "服务器地址已更改，旧密钥已清除；请为新目标生成密钥并登记公钥。"
        } else {
            mutableMessage.value = "服务器档案已保存。"
        }
        true
    } catch (error: Exception) {
        mutableMessage.value = error.message ?: "无法保存 服务器档案。"
        false
    }

    fun deleteCpaProfile(profileId: String) {
        try {
            val wasActive = activeServerId.value == profileId
            cpaProfileStore.delete(profileId)
            stopCpaTunnel()
            if (wasActive) stopAllTunnels()
            cpaKeyStore.delete(profileId)
            mutableCpaKeys.value = mutableCpaKeys.value - profileId
            mutableMessage.value = "服务器档案已删除。"
        } catch (error: Exception) {
            mutableMessage.value = error.message ?: "无法删除 服务器档案。"
        }
    }

    fun importServerConnection(uri: Uri, onImported: () -> Unit) {
        viewModelScope.launch {
            runBusy(null) {
                val connection = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取服务器连接文件。")
                    try { ServerConnectionFile.decode(bytes) } finally { bytes.fill(0) }
                }
                val profile = try {
                    runInterruptible(Dispatchers.IO) {
                        val probeKey = connection.privateKey.copyOf()
                        try {
                            SshEngine.connect(connection.profile.toAppConfig(), probeKey).disconnect()
                        } catch (error: Exception) {
                            throw IllegalStateException(tunnelFailure(error, "验证连接文件"))
                        } finally {
                            probeKey.fill(0)
                        }
                    }
                    stopAllTunnels()
                    cpaProfileStore.importConnection(connection.profile) {
                        cpaKeyStore.saveShared(connection.privateKey)
                    }
                } finally {
                    connection.privateKey.fill(0)
                }
                mutableCpaKeys.value = mutableCpaKeys.value + profile.id
                cpaProfileStore.clearSharedPublicKey()
                mutableSharedCpaPublicKey.value = ""
                mutableSharedCpaKey.value = true
                mutableSubscriptions.value = ""
                mutableSubscriptionLink.value = null
                mutableMessage.value = "已导入 ${profile.name}，可直接连接。"
                onImported()
            }
        }
    }

    fun importCpaPrivateKey(uri: Uri, profileId: String) {
        viewModelScope.launch {
            runBusy("此 服务器档案的 SSH 私钥已单独加密保存。") {
                check(cpaProfileStore.find(profileId) != null) { "服务器档案已删除，请刷新后重试。" }
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取选择的文件。")
                }
                withContext(Dispatchers.IO) { cpaKeyStore.save(profileId, bytes) }
                cpaProfileStore.find(profileId)?.let { cpaProfileStore.save(it.copy(publicKey = "")) }
                mutableCpaKeys.value = mutableCpaKeys.value + profileId
                stopCpaTunnel()
                if (activeServerId.value == profileId) stopAllTunnels()
            }
        }
    }

    fun importSharedCpaPrivateKey(uri: Uri) {
        viewModelScope.launch {
            runBusy("全服务器共用 SSH 私钥已安全导入。阿里云和两台甲骨文都会使用它。") {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readLimited)
                        ?: error("无法读取选择的文件。")
                }
                withContext(Dispatchers.IO) { cpaKeyStore.saveShared(bytes) }
                cpaProfileStore.clearSharedPublicKey()
                mutableSharedCpaPublicKey.value = ""
                mutableSharedCpaKey.value = true
                stopAllTunnels()
            }
        }
    }

    fun promoteDefaultPrivateKeyToShared() {
        viewModelScope.launch {
            runBusy("正在把阿里云现有私钥复制为全服务器共用私钥。") {
                check(keyStore.hasKey) { "阿里云尚未导入 SSH 私钥，请先导入私钥。" }
                val bytes = withContext(Dispatchers.IO) { keyStore.read() }
                withContext(Dispatchers.IO) { cpaKeyStore.saveShared(bytes) }
                cpaProfileStore.clearSharedPublicKey()
                mutableSharedCpaPublicKey.value = ""
                mutableSharedCpaKey.value = true
                stopAllTunnels()
                mutableMessage.value = "已将阿里云现有私钥复制为全服务器共用私钥。"
            }
        }
    }

    fun clearCpaPrivateKey(profileId: String) {
        viewModelScope.launch {
            runBusy("已移除此 服务器档案的 SSH 私钥。") {
                if (activeServerId.value == profileId) stopAllTunnels() else stopCpaTunnel()
                withContext(Dispatchers.IO) { cpaKeyStore.delete(profileId) }
                cpaProfileStore.find(profileId)?.let { cpaProfileStore.save(it.copy(publicKey = "")) }
                mutableCpaKeys.value = mutableCpaKeys.value - profileId
            }
        }
    }

    fun clearSharedCpaPrivateKey() {
        viewModelScope.launch {
            runBusy("已移除全服务器共用 SSH 私钥。") {
                stopAllTunnels()
                withContext(Dispatchers.IO) { cpaKeyStore.clearShared() }
                cpaProfileStore.clearSharedPublicKey()
                mutableSharedCpaPublicKey.value = ""
                mutableSharedCpaKey.value = false
            }
        }
    }

    fun generateCpaKeyPair(profileId: String, replaceExisting: Boolean = false) {
        viewModelScope.launch {
            runBusy(null) {
                val checkedProfile = cpaProfileStore.find(profileId) ?: error("服务器档案已删除，请刷新后重试。")
                check(replaceExisting || !cpaKeyStore.hasKey(profileId)) { "此档案已有密钥；请确认后再生成替换密钥。" }
                val publicKey = runInterruptible(Dispatchers.IO) { cpaKeyStore.generate(profileId) }
                if (cpaProfileStore.find(profileId) != checkedProfile) {
                    withContext(Dispatchers.IO) { cpaKeyStore.delete(profileId) }
                    error("服务器档案在生成密钥期间发生变化，密钥已清除，请重试。")
                }
                cpaProfileStore.save(checkedProfile.copy(publicKey = publicKey))
                mutableCpaKeys.value = mutableCpaKeys.value + profileId
                if (activeServerId.value == profileId) stopAllTunnels() else stopCpaTunnel()
                mutableMessage.value = "手机密钥已生成并加密保存。公钥已显示；服务器登记前尚不能用它登录。"
            }
        }
    }

    fun generateSharedCpaKey(replaceExisting: Boolean = false) {
        viewModelScope.launch {
            runBusy(null) {
                check(replaceExisting || !cpaKeyStore.hasSharedKey()) {
                    "全服务器已有共用私钥；请确认后再生成替换密钥。"
                }
                val publicKey = runInterruptible(Dispatchers.IO) { cpaKeyStore.generateShared() }
                cpaProfileStore.saveSharedPublicKey(publicKey)
                mutableSharedCpaPublicKey.value = publicKey
                mutableSharedCpaKey.value = true
                stopAllTunnels()
                mutableMessage.value = "全服务器共用手机密钥已生成。请把公钥登记到阿里云和两台甲骨文后再测试连接。"
            }
        }
    }

    fun copyCpaPublicKey(profileId: String) {
        val publicKey = cpaProfileStore.find(profileId)?.publicKey
        if (publicKey.isNullOrBlank()) {
            mutableMessage.value = "此 服务器档案没有可复制的手机公钥。"
            return
        }
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            mutableMessage.value = "无法访问剪贴板。"
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("服务器 SSH 公钥", publicKey))
        mutableMessage.value = "已复制公钥。私钥仍保留在手机加密存储中。"
    }

    fun copySharedCpaPublicKey() {
        if (sharedCpaPublicKey.value.isBlank()) {
            mutableMessage.value = "共用私钥是外部导入的，应用没有对应公钥文本可复制。"
            return
        }
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            mutableMessage.value = "无法访问剪贴板。"
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("全服务器共用 SSH 公钥", sharedCpaPublicKey.value))
        mutableMessage.value = "已复制全服务器共用公钥。私钥仍保留在手机加密存储中。"
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
        activeServerId.value?.let { profileId ->
            testCpaProfile(profileId)
            return
        }
        viewModelScope.launch {
            runBusy(null) {
                val selected = activeServerStore.current()
                check(selected.profileId == null && selected.hasKey) { "请先为默认服务器导入 SSH 私钥。" }
                val privateKey = readActiveKey(selected)
                val probe = runInterruptible(Dispatchers.IO) {
                    try { SshEngine.probe(selected.config, privateKey) }
                    finally { privateKey.fill(0) }
                }
                check(activeServerStore.current() == selected) { "当前服务器或连接设置已变化，请重新测试主机指纹。" }
                pendingCpaProfile = null
                mutableFingerprintTarget.value = "默认服务器 SSH 连接"
                pendingProbeConfig = selected.config
                pendingProbe = probe
                mutableFingerprint.value = probe.fingerprint
            }
        }
    }

    fun testCpaProfile(profileId: String) {
        viewModelScope.launch {
            runBusy(null) {
                check(activeServerStore.current().profileId == profileId) { "请先在设置中切换到要测试的服务器。" }
                val checkedProfile = cpaProfileStore.find(profileId) ?: error("服务器档案已删除，请刷新后重试。")
                val selected = activeServerStore.current()
                check(selected.hasKey) { "请先为当前服务器导入 SSH 私钥。" }
                val privateKey = activeServerStore.readKey(selected)
                val probe = runInterruptible(Dispatchers.IO) {
                    try { SshEngine.probe(selected.config, privateKey) }
                    finally { privateKey.fill(0) }
                }
                check(activeServerStore.current() == selected && cpaProfileStore.find(profileId) == checkedProfile) {
                    "当前服务器或连接设置已变化，请重新测试主机指纹。"
                }
                pendingProbeConfig = null
                pendingCpaProfile = checkedProfile
                pendingProbe = probe
                mutableFingerprintTarget.value = "服务器：${checkedProfile.name}"
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
                mutableMessage.value = "服务器档案已变化，请重新测试。"
                return
            }
            pendingProbe?.let { cpaProfileStore.trustHost(cpaProfile.id, it.keyType, it.encodedKey) }
            pendingProbe = null
            pendingCpaProfile = null
            mutableFingerprint.value = null
            mutableMessage.value = "服务器主机指纹已固定，后续连接会严格校验。"
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

    fun openProfileConsole(profileId: String, page: ProfileConsolePage) {
        val console = page.console
        viewModelScope.launch {
            runBusy(null) {
                val selected = activeServerStore.current()
                check(selected.profileId == profileId) { "当前服务器已切换，请重新打开控制台。" }
                check(selected.supports(console.service)) { "当前服务器未配置${console.title}。" }
                val profile = cpaProfileStore.find(profileId) ?: error("所选服务器档案不存在。")
                check(selected.hasKey) { "请先为当前服务器配置 SSH 私钥。" }
                check(profile.hasTrustedHost) { "请先测试此档案并核对服务器指纹。" }
                val requestId = UUID.randomUUID().toString()
                val action = TunnelService.actionForProfileConsole(console)
                ContextCompat.startForegroundService(
                    getApplication(),
                    Intent(getApplication(), TunnelService::class.java)
                        .setAction(action)
                        .putExtra(TunnelService.EXTRA_REQUEST_ID, requestId)
                        .putExtra(TunnelService.EXTRA_CPA_PROFILE_ID, profile.id)
                        .putExtra(TunnelService.EXTRA_SERVER_PROFILE_SELECTED, true)
                        .putExtra(TunnelService.EXTRA_SERVER_PROFILE_ID, selected.profileId)
                )
                val state = withTimeout(35_000) {
                    TunnelRuntime.state.first {
                        it.requestId == requestId && it.cpaProfileId == profile.id && !it.connecting &&
                            (it.error != null || it.cpaActive && it.profileConsole == console)
                    }
                }
                state.error?.let { error(it) }
                check(activeServerStore.current() == selected) { "服务器设置已变化，请重新打开控制台。" }
                val localPort = state.cpaLocalPort ?: error("本机回环转发未建立。")
                val localHost = state.cpaLocalHost ?: error("隔离回环地址缺失。")
                mutableOpenConsole.emit(
                    ConsoleRequest(
                        "${page.title} · ${profile.name}",
                        page.localUrl(localHost, localPort),
                        action,
                        cpaProfileId = profile.id,
                        serverProfileId = selected.profileId
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
                val selected = activeServerStore.current()
                val requiredService = when (target) {
                    ConsoleTarget.API -> ServerService.API
                    ConsoleTarget.ASTRBOT -> when {
                        review -> ServerService.REVIEW
                        analysis -> ServerService.DISTILLATION
                        else -> ServerService.ASTRBOT
                    }
                    ConsoleTarget.ZAKO -> ServerService.ZAKO
                }
                check(selected.supports(requiredService)) { "当前服务器未配置${requiredService.displayName}。" }
                check(selected.hasKey) { "请先为当前服务器配置 SSH 私钥。" }
                check(selected.config.hasTrustedHost) { "请先测试当前服务器并确认主机指纹。" }
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
                        .putExtra(TunnelService.EXTRA_SERVER_PROFILE_SELECTED, true)
                        .putExtra(TunnelService.EXTRA_SERVER_PROFILE_ID, selected.profileId)
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
                check(activeServerStore.current() == selected) { "当前服务器已切换，请重新打开目标页面。" }
                val request = when (target) {
                    ConsoleTarget.API -> ConsoleRequest(
                        "反代 API 管理 · ${selected.name}",
                        "http://${selected.localHost}:${selected.config.apiLocalPort}/management.html", action,
                        serverProfileId = selected.profileId
                    )
                    ConsoleTarget.ASTRBOT -> ConsoleRequest(
                        (if (review) "AstrBot 审核中心" else if (analysis) "AstrBot 蒸馏中心" else "AstrBot 控制台") + " · ${selected.name}",
                        "http://${selected.localHost}:${selected.config.astrBotLocalPort}" + (if (review) REVIEW_ROUTE else if (analysis) DISTILLATION_ROUTE else ""), action,
                        serverProfileId = selected.profileId
                    )
                    ConsoleTarget.ZAKO -> ConsoleRequest(
                        "Zako WebUI · ${selected.name}",
                        "http://${selected.localHost}:${tunnelState.zakoLocalPort ?: error("Zako 本地端口未建立。")}/",
                        action,
                        serverProfileId = selected.profileId
                    )
                }
                mutableOpenConsole.emit(request)
            }
        }
    }

    fun runSpeedTest() {
        viewModelScope.launch {
            runBusy("节点测速流程已完成，请在终端查看各组结果。") {
                val selected = activeServerStore.current()
                check(selected.supports(ServerService.SPEED_TEST)) { "当前服务器未配置节点测速。" }
                check(selected.hasKey && selected.config.hasTrustedHost) { "请先为当前服务器配置 SSH 私钥并确认主机指纹。" }
                val privateKey = readActiveKey(selected)
                val output = runInterruptible(Dispatchers.IO) {
                    try { SshEngine.runCommand(selected.config, privateKey, "/usr/local/sbin/mihomo-auto force") }
                    finally { privateKey.fill(0) }
                }
                check(activeServerStore.current() == selected) { "当前服务器已切换，测速结果未应用。" }
                appendTerminal("节点测速", output)
            }
        }
    }

    fun runTerminalCommand(command: String) {
        viewModelScope.launch {
            runBusy(null) {
                val selected = activeServerStore.current()
                check(selected.supports(ServerService.TERMINAL)) { "当前服务器未配置终端入口。" }
                check(selected.hasKey && selected.config.hasTrustedHost) { "请先为当前服务器配置 SSH 私钥并确认主机指纹。" }
                val privateKey = readActiveKey(selected)
                val output = runInterruptible(Dispatchers.IO) {
                    try { SshEngine.runCommand(selected.config, privateKey, command) }
                    finally { privateKey.fill(0) }
                }
                check(activeServerStore.current() == selected) { "当前服务器已切换，命令结果未应用。" }
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
