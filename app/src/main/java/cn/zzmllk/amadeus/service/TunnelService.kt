package cn.zzmllk.amadeus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.zzmllk.amadeus.MainActivity
import cn.zzmllk.amadeus.R
import cn.zzmllk.amadeus.data.ActiveServerConnection
import cn.zzmllk.amadeus.data.ActiveServerStore
import cn.zzmllk.amadeus.data.ProfileConsole
import cn.zzmllk.amadeus.data.CpaProfileStore
import cn.zzmllk.amadeus.data.CpaSecureKeyStore
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.data.ServerService
import cn.zzmllk.amadeus.ssh.SshEngine
import cn.zzmllk.amadeus.ssh.connectionIdentity
import cn.zzmllk.amadeus.ssh.LocalSshForwarder
import cn.zzmllk.amadeus.ssh.tunnelHttpReady
import cn.zzmllk.amadeus.ssh.tunnelHttpStatus
import cn.zzmllk.amadeus.ssh.cpaConsoleResponseOk
import cn.zzmllk.amadeus.ssh.tunnelFailure
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TunnelState(
    val connecting: Boolean = false,
    val apiActive: Boolean = false,
    val astrBotActive: Boolean = false,
    val zakoActive: Boolean = false,
    val zakoLocalPort: Int? = null,
    val cpaActive: Boolean = false,
    val cpaLocalPort: Int? = null,
    val cpaProfileId: String? = null,
    val cpaLocalHost: String? = null,
    val profileConsole: ProfileConsole? = null,
    val activeServerId: String? = null,
    val activeServerName: String? = null,
    val activeServerLocalHost: String = "127.0.0.1",
    val message: String = "隧道未启动",
    val error: String? = null,
    val requestId: String = ""
)

object TunnelRuntime {
    private val mutableState = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = mutableState.asStateFlow()
    internal fun update(value: TunnelState) { mutableState.value = value }
}

class TunnelService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val sessionLock = Any()
    @Volatile private var destroyed = false
    private lateinit var configStore: ConfigStore
    private lateinit var cpaProfileStore: CpaProfileStore
    private lateinit var cpaKeyStore: CpaSecureKeyStore
    private lateinit var keyStore: SecureKeyStore
    private lateinit var activeServerStore: ActiveServerStore
    private var session: Session? = null
    private var connectionSignature = ""
    private var sessionServerProfileId: String? = null
    private var sessionLocalHost = "127.0.0.1"
    private var apiForwardPort: Int? = null
    private var astrBotForwardPort: Int? = null
    private var zakoForwardPort: Int? = null
    private var zakoForwarder: LocalSshForwarder? = null
    private var cpaSession: Session? = null
    private var cpaConnectionSignature = ""
    private var cpaProfileId: String? = null
    private var cpaForwarder: LocalSshForwarder? = null
    private var activeProfileConsole: ProfileConsole? = null

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        cpaProfileStore = CpaProfileStore(this)
        cpaKeyStore = CpaSecureKeyStore(this)
        keyStore = SecureKeyStore(this)
        activeServerStore = ActiveServerStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("准备安全隧道……"))
        executor.scheduleWithFixedDelay({ refreshConnectionState() }, 5, 5, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceReconnect = intent?.getBooleanExtra(EXTRA_FORCE_RECONNECT, false) == true
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val serverProfileId = if (intent?.getBooleanExtra(EXTRA_SERVER_PROFILE_SELECTED, false) == true) {
            intent.getStringExtra(EXTRA_SERVER_PROFILE_ID)
        } else activeServerStore.current().profileId
        if (destroyed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_API -> executor.execute { startTunnel(Target.API, forceReconnect, requestId, serverProfileId) }
            ACTION_START_ASTRBOT -> executor.execute { startTunnel(Target.ASTRBOT, forceReconnect, requestId, serverProfileId) }
            ACTION_START_ZAKO -> executor.execute { startTunnel(Target.ZAKO, forceReconnect, requestId, serverProfileId) }
            ACTION_START_CPA, ACTION_START_SEARXNG -> executor.execute {
                val console = profileConsoleForAction(intent.action) ?: return@execute
                startProfileTunnel(console, intent.getStringExtra(EXTRA_CPA_PROFILE_ID).orEmpty(), requestId, serverProfileId)
            }
            ACTION_STOP_CPA -> executor.execute { stopCpaTunnel() }
            ACTION_STOP_DEFAULT -> executor.execute { stopDefaultTunnels() }
            ACTION_STOP_ALL -> executor.execute { stopAll("隧道已停止") }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(sessionLock) {
            destroyed = true
            disconnectSession()
            disconnectCpaSession()
            TunnelRuntime.update(TunnelState())
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel(target: Target, forceReconnect: Boolean = false, requestId: String = "", profileId: String? = activeServerStore.current().profileId) {
        if (destroyed) return
        if (target == Target.ZAKO) {
            startZakoTunnel(forceReconnect, requestId, profileId)
            return
        }
        val previous = TunnelRuntime.state.value
        val restoreApi = previous.apiActive || apiForwardPort != null || target == Target.API
        val restoreAstrBot = previous.astrBotActive || astrBotForwardPort != null || target == Target.ASTRBOT
        val restoreZako = previous.zakoActive || zakoForwardPort != null || target == Target.ZAKO
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 SSH 安全通道……"))
        var phase = "检查连接配置"
        try {
            val selected = activeServerStore.current().also {
                check(it.profileId == profileId) { "当前服务器已切换，请重新打开页面。" }
            }
            val service = when (target) {
                Target.API -> ServerService.API
                Target.ASTRBOT -> ServerService.ASTRBOT
                Target.ZAKO -> ServerService.ZAKO
            }
            check(selected.supports(service)) { "当前服务器未配置${service.displayName}。" }
            val config = selected.config
            check(selected.hasKey) { "尚未为当前服务器配置 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = connectionIdentity(config, selected.keyRevision)
            phase = "检查已有通道"
            val reuse = !forceReconnect && session?.isConnected == true && signature == connectionSignature &&
                sessionServerProfileId == profileId && activePagesReady(sessionLocalHost)
            if (!reuse) {
                disconnectSession()
                phase = "读取手机中的 SSH 私钥"
                val privateKey = activeServerStore.readKey(selected)
                phase = "SSH 身份验证与主机校验"
                val created = try { SshEngine.connect(config, privateKey) } finally { privateKey.fill(0) }
                synchronized(sessionLock) {
                    if (destroyed) { created.disconnect(); return }
                    session = created
                    connectionSignature = signature
                    sessionServerProfileId = profileId
                    sessionLocalHost = selected.localHost
                }
            }
            val connected = session ?: error("SSH 会话未建立。")
            phase = "建立本地转发"
            if (restoreApi && apiForwardPort == null) {
                connected.setPortForwardingL(selected.localHost, config.apiLocalPort, "127.0.0.1", 8317)
                apiForwardPort = config.apiLocalPort
            }
            if (restoreAstrBot && astrBotForwardPort == null) {
                connected.setPortForwardingL(selected.localHost, config.astrBotLocalPort, "127.0.0.1", 6185)
                astrBotForwardPort = config.astrBotLocalPort
            }
            if (restoreZako && zakoForwardPort == null) {
                check(config.apiLocalPort != ZAKO_LOCAL_PORT && config.astrBotLocalPort != ZAKO_LOCAL_PORT) { "Zako 本地端口与其他通道设置冲突。" }
                connected.setPortForwardingL(selected.localHost, ZAKO_LOCAL_PORT, "127.0.0.1", 1990)
                zakoForwardPort = ZAKO_LOCAL_PORT
            }
            phase = "检查目标页面"
            if (target != Target.ZAKO) check(waitForActivePages(selected.localHost)) { "目标页面未响应。" }
            val state = TunnelState(
                requestId = requestId,
                connecting = false,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                zakoActive = zakoForwardPort != null,
                cpaActive = cpaForwarder?.isOpen == true,
                cpaLocalPort = cpaForwarder?.localPort,
                cpaProfileId = cpaProfileId,
                cpaLocalHost = cpaProfileId?.let(cpaProfileStore::find)?.localHost,
                activeServerId = profileId,
                activeServerName = selected.name,
                activeServerLocalHost = selected.localHost,
                message = listOfNotNull(
                    "API".takeIf { apiForwardPort != null },
                    "AstrBot".takeIf { astrBotForwardPort != null },
                    "Zako".takeIf { zakoForwardPort != null }
                ).joinToString("、") + " 通道运行中"
            )
            publish(state)
        } catch (error: Exception) {
            disconnectSession()
            if (destroyed) return
            val message = when (error.message) {
                "尚未为当前服务器配置 SSH 私钥。", "尚未信任服务器主机密钥，请先在设置中测试连接。" -> error.message!!
                "目标页面未响应。" -> "SSH 已连接，但目标页面未响应；请检查服务器服务或网络后重新打开。"
                else -> tunnelFailure(error, phase)
            }
            publish(previous.copy(
                connecting = false,
                apiActive = false,
                astrBotActive = false,
                zakoActive = false,
                zakoLocalPort = null,
                error = message,
                message = "连接失败：$message",
                requestId = requestId
            ))
        }
    }

    private fun startZakoTunnel(forceReconnect: Boolean, requestId: String, profileId: String? = activeServerStore.current().profileId) {
        if (destroyed) return
        val previous = TunnelRuntime.state.value
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 Zako SSH 转发……"))
        var phase = "检查连接配置"
        try {
            val selected = activeServerStore.current().also {
                check(it.profileId == profileId) { "当前服务器已切换，请重新打开页面。" }
            }
            val config = selected.config
            check(selected.hasKey) { "尚未为当前服务器配置 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = connectionIdentity(config, selected.keyRevision)
            check(selected.supports(ServerService.ZAKO)) { "当前服务器未配置 Zako。" }
            val canReuse = !forceReconnect && session?.isConnected == true && signature == connectionSignature &&
                sessionServerProfileId == profileId &&
                zakoForwarder?.isOpen == true &&
                zakoForwardPort?.let { tunnelHttpReady(it, "/", ZAKO_PROBE_TIMEOUT_MS, selected.localHost) } == true
            if (!canReuse) {
                disconnectSession()
                phase = "读取手机中的 SSH 私钥"
                val privateKey = activeServerStore.readKey(selected)
                phase = "SSH 身份验证与主机校验"
                val created = try { SshEngine.connect(config, privateKey) } finally { privateKey.fill(0) }
                synchronized(sessionLock) {
                    if (destroyed) { created.disconnect(); return }
                    session = created
                    connectionSignature = signature
                    sessionServerProfileId = profileId
                    sessionLocalHost = selected.localHost
                }
            }
            phase = "建立 Zako 本地转发"
            val connected = session ?: error("SSH 会话未建立。")
            if (zakoForwarder == null) {
                zakoForwarder = LocalSshForwarder(connected, selected.localHost, 1990)
                zakoForwardPort = zakoForwarder?.localPort
            }
            val localPort = zakoForwardPort ?: error("Zako 本地转发未返回端口。")
            phase = "检查 Zako 本地转发"
            check(waitForZakoPage(localPort, selected.localHost)) {
                zakoForwarder?.lastErrorType?.let { "Zako SSH 直连通道失败（$it）。" }
                    ?: "Zako 本地转发已建立，但网页未响应。"
            }
            publish(TunnelState(
                requestId = requestId,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                zakoActive = true,
                zakoLocalPort = localPort,
                cpaActive = cpaForwarder?.isOpen == true,
                cpaLocalPort = cpaForwarder?.localPort,
                cpaProfileId = cpaProfileId,
                cpaLocalHost = cpaProfileId?.let(cpaProfileStore::find)?.localHost,
                activeServerId = profileId,
                activeServerName = selected.name,
                activeServerLocalHost = selected.localHost,
                message = "Zako SSH 转发已建立"
            ))
        } catch (error: Exception) {
            disconnectSession()
            if (destroyed) return
            val message = when (error.message) {
                "尚未导入 SSH 私钥。", "尚未信任服务器主机密钥，请先在设置中测试连接。",
                "Zako 本地转发已建立，但网页未响应。" -> error.message!!
                else -> error.message?.takeIf { it.startsWith("Zako SSH 直连通道失败（") } ?: tunnelFailure(error, phase)
            }
            publish(previous.copy(
                connecting = false,
                apiActive = false,
                astrBotActive = false,
                zakoActive = false,
                zakoLocalPort = null,
                error = message,
                message = "连接失败：$message",
                requestId = requestId
            ))
        }
    }

    private fun startProfileTunnel(console: ProfileConsole, profileId: String, requestId: String, serverProfileId: String?) {
        if (destroyed) return
        val previous = TunnelRuntime.state.value
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 ${console.title}的 SSH 转发……"))
        var phase = "检查服务器档案"
        var selected = activeServerStore.current()
        try {
            val profile = cpaProfileStore.find(profileId) ?: error("所选服务器档案不存在。")
            selected = activeServerStore.current()
            check(selected.profileId == serverProfileId) { "当前服务器已切换，请重新打开页面。" }
            check(selected.profileId == profileId && selected.supports(console.service)) { "当前服务器未配置此入口，请在设置中切换服务器。" }
            check(selected.hasKey) { "尚未为此服务器配置 SSH 私钥。" }
            check(profile.hasTrustedHost) { "请先测试此档案并确认服务器主机指纹。" }
            check(profile.localHostOctet != null) { "服务器档案回环地址尚未分配，请编辑并保存档案后重试。" }
            val signature = connectionIdentity(selected.config, selected.keyRevision)
            val reusable = activeProfileConsole == console && cpaProfileId == profile.id && cpaSession?.isConnected == true &&
                cpaConnectionSignature == signature && cpaForwarder?.isOpen == true &&
                cpaConsoleResponseOk(cpaForwarder?.localPort?.let {
                    tunnelHttpStatus(it, console.probePath, CPA_PROBE_TIMEOUT_MS, selected.localHost)
                })
            if (!reusable) {
                disconnectCpaSession()
                phase = "读取手机中的 SSH 私钥"
                val privateKey = activeServerStore.readKey(selected)
                phase = "SSH 身份验证与主机校验"
                val created = try { SshEngine.connect(selected.config, privateKey) }
                finally { privateKey.fill(0) }
                if (destroyed) { created.disconnect(); return }
                cpaSession = created
                cpaConnectionSignature = signature
                cpaProfileId = profile.id
                activeProfileConsole = console
                phase = "建立本机回环转发"
                cpaForwarder = LocalSshForwarder(created, "127.0.0.1", console.remotePort, selected.localHost)
            }
            val localPort = cpaForwarder?.localPort ?: error("本机回环转发未建立。")
            phase = "检查${console.title}"
            val status = waitForProfileConsole(console, localPort, selected.localHost)
            check(cpaConsoleResponseOk(status)) {
                if (status == null) "SSH 转发已建立，但网页未响应。请检查服务器上的${console.service.displayName}。"
                else "网页返回 HTTP $status，通道未就绪。"
            }
            check(activeServerStore.current() == selected) { "当前服务器或私钥已变化，请重新打开网页。" }
            publish(previous.copy(
                requestId = requestId,
                connecting = false,
                cpaActive = true,
                cpaLocalPort = localPort,
                cpaProfileId = profile.id,
                cpaLocalHost = selected.localHost,
                profileConsole = console,
                activeServerId = selected.profileId,
                activeServerName = selected.name,
                activeServerLocalHost = selected.localHost,
                message = "${console.title}已就绪：${profile.name}"
            ))
        } catch (error: Exception) {
            disconnectCpaSession()
            if (destroyed) return
            val message = when (error.message) {
                "尚未为此服务器配置 SSH 私钥。", "请先测试此档案并确认服务器主机指纹。",
                "所选服务器档案不存在。" -> error.message!!
                else -> error.message?.takeIf { it.startsWith("网页返回 HTTP ") || it.startsWith("SSH 转发已建立，但网页未响应。") }
                    ?: tunnelFailure(error, phase)
            }
            publish(previous.copy(
                connecting = false,
                error = message,
                cpaActive = false,
                cpaLocalPort = null,
                cpaProfileId = profileId,
                cpaLocalHost = selected.localHost,
                profileConsole = console,
                activeServerId = selected.profileId,
                activeServerName = selected.name,
                activeServerLocalHost = selected.localHost,
                message = "${console.title}连接失败：$message",
                requestId = requestId
            ))
        }
    }

    private fun stopCpaTunnel() {
        disconnectCpaSession()
        val previous = TunnelRuntime.state.value
        publish(previous.copy(
            connecting = false,
            cpaActive = false,
            cpaLocalPort = null,
            cpaProfileId = null,
            cpaLocalHost = null,
            profileConsole = null,
            error = null,
            message = "服务器网页通道已停止"
        ))
        if (!previous.apiActive && !previous.astrBotActive && !previous.zakoActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopDefaultTunnels() {
        disconnectSession()
        val previous = TunnelRuntime.state.value
        publish(previous.copy(
            connecting = false,
            apiActive = false,
            astrBotActive = false,
            zakoActive = false,
            zakoLocalPort = null,
            error = null,
            message = "默认 SSH 通道已停止"
        ))
        if (!previous.cpaActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun waitForProfileConsole(console: ProfileConsole, port: Int, localHost: String): Int? {
        repeat(CPA_PROBE_ATTEMPTS) { attempt ->
            val status = tunnelHttpStatus(port, console.probePath, CPA_PROBE_TIMEOUT_MS, localHost)
            if (status != null) return status
            if (attempt + 1 < CPA_PROBE_ATTEMPTS) Thread.sleep(CPA_PROBE_INTERVAL_MS)
        }
        return null
    }

    private fun stopAll(message: String) {
        disconnectSession()
        disconnectCpaSession()
        publish(TunnelState(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disconnectSession() = synchronized(sessionLock) {
        try { zakoForwarder?.close() } catch (_: Exception) { }
        zakoForwarder = null
        try { session?.disconnect() } catch (_: Exception) { }
        session = null
        connectionSignature = ""
        sessionServerProfileId = null
        sessionLocalHost = "127.0.0.1"
        apiForwardPort = null
        astrBotForwardPort = null
        zakoForwardPort = null
    }

    private fun disconnectCpaSession() {
        try { cpaForwarder?.close() } catch (_: Exception) { }
        cpaForwarder = null
        try { cpaSession?.disconnect() } catch (_: Exception) { }
        cpaSession = null
        cpaConnectionSignature = ""
        cpaProfileId = null
        activeProfileConsole = null
    }

    private fun refreshConnectionState() {
        if (destroyed || session == null && cpaSession == null) return
        try {
            val active = activeServerStore.current()
            val sessionTarget = activeServerStore.forProfile(sessionServerProfileId)
            val normalHealthy = session == null || sessionServerProfileId == active.profileId && session?.isConnected == true &&
                connectionSignature == connectionIdentity(sessionTarget.config, sessionTarget.keyRevision) &&
                activePagesReady(sessionLocalHost)
            val cpaProfile = cpaProfileId?.let(cpaProfileStore::find)
            val cpaTarget = cpaProfile?.let { activeServerStore.forProfile(it.id) }
            val console = activeProfileConsole
            val cpaHealthy = cpaSession == null || cpaTarget != null && console != null &&
                active.profileId == cpaTarget.profileId && cpaTarget.supports(console.service) && cpaSession?.isConnected == true &&
                cpaConnectionSignature == connectionIdentity(cpaTarget.config, cpaTarget.keyRevision) &&
                cpaForwarder?.isOpen == true && cpaForwarder?.localPort?.let {
                    cpaConsoleResponseOk(
                        tunnelHttpStatus(it, console.probePath, CPA_PROBE_TIMEOUT_MS, cpaTarget.localHost)
                    )
                } == true
            if (normalHealthy && cpaHealthy) return
            if (!normalHealthy) disconnectSession()
            if (!cpaHealthy) disconnectCpaSession()
        } catch (_: Exception) {
            disconnectSession()
            disconnectCpaSession()
        }
        val previous = TunnelRuntime.state.value
        val message = "通道已断开、页面未响应或连接设置已变化，请重新打开通道。"
        publish(previous.copy(
            apiActive = session?.isConnected == true && apiForwardPort != null,
            astrBotActive = session?.isConnected == true && astrBotForwardPort != null,
            zakoActive = session?.isConnected == true && zakoForwardPort != null,
            zakoLocalPort = zakoForwardPort,
            cpaActive = cpaSession?.isConnected == true && cpaForwarder?.isOpen == true,
            cpaLocalPort = cpaForwarder?.localPort,
            cpaLocalHost = cpaProfileId?.let { runCatching { activeServerStore.forProfile(it).localHost }.getOrNull() },
            cpaProfileId = cpaProfileId,
            profileConsole = activeProfileConsole,
            activeServerId = sessionServerProfileId,
            activeServerName = sessionServerProfileId?.let(cpaProfileStore::find)?.name ?: "阿里云",
            activeServerLocalHost = sessionLocalHost,
            error = message,
            message = message
        ))
    }

    private fun activePagesReady(localHost: String = "127.0.0.1"): Boolean =
        (apiForwardPort?.let { tunnelHttpReady(it, "/", loopbackHost = localHost) } ?: true) &&
            (astrBotForwardPort?.let { tunnelHttpReady(it, "/", loopbackHost = localHost) } ?: true)

    private fun waitForActivePages(localHost: String): Boolean {
        repeat(3) { attempt ->
            if (activePagesReady(localHost)) return true
            if (attempt < 2) Thread.sleep(750)
        }
        return false
    }

    private fun waitForZakoPage(port: Int, localHost: String): Boolean {
        repeat(ZAKO_PROBE_ATTEMPTS) { attempt ->
            if (tunnelHttpReady(port, "/", ZAKO_PROBE_TIMEOUT_MS, localHost)) return true
            if (attempt + 1 < ZAKO_PROBE_ATTEMPTS) Thread.sleep(ZAKO_PROBE_INTERVAL_MS)
        }
        return false
    }

    private fun publish(state: TunnelState) = synchronized(sessionLock) {
        if (!destroyed) {
            TunnelRuntime.update(state)
            updateNotification(state.message)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tunnel_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.tunnel_channel_description) }
        )
    }

    private fun notification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.amadeus_logo)
            .setContentTitle("服务器控制台安全通道")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private enum class Target { API, ASTRBOT, ZAKO }

    companion object {
        const val ACTION_START_API = "cn.zzmllk.amadeus.START_API"
        const val ACTION_START_ASTRBOT = "cn.zzmllk.amadeus.START_ASTRBOT"
        const val ZAKO_LOCAL_PORT = 11990
        const val ACTION_START_ZAKO = "cn.zzmllk.amadeus.START_ZAKO"
        const val ACTION_START_CPA = "cn.zzmllk.amadeus.START_CPA"
        const val ACTION_START_SEARXNG = "cn.zzmllk.amadeus.START_SEARXNG"

        fun actionForProfileConsole(console: ProfileConsole): String = when (console) {
            ProfileConsole.CPA -> ACTION_START_CPA
            ProfileConsole.SEARXNG -> ACTION_START_SEARXNG
        }

        fun profileConsoleForAction(action: String?): ProfileConsole? = when (action) {
            ACTION_START_CPA -> ProfileConsole.CPA
            ACTION_START_SEARXNG -> ProfileConsole.SEARXNG
            else -> null
        }
        const val ACTION_STOP_CPA = "cn.zzmllk.amadeus.STOP_CPA"
        const val ACTION_STOP_DEFAULT = "cn.zzmllk.amadeus.STOP_DEFAULT"
        const val ACTION_STOP_ALL = "cn.zzmllk.amadeus.STOP_ALL"
        const val EXTRA_FORCE_RECONNECT = "force_reconnect"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CPA_PROFILE_ID = "cpa_profile_id"
        const val EXTRA_SERVER_PROFILE_SELECTED = "server_profile_selected"
        const val EXTRA_SERVER_PROFILE_ID = "server_profile_id"
        private const val ZAKO_PROBE_TIMEOUT_MS = 1_500
        private const val ZAKO_PROBE_INTERVAL_MS = 250L
        private const val ZAKO_PROBE_ATTEMPTS = 8
        private const val CPA_PROBE_TIMEOUT_MS = 1_500
        private const val CPA_PROBE_INTERVAL_MS = 250L
        private const val CPA_PROBE_ATTEMPTS = 8
        private const val CHANNEL_ID = "amadeus_tunnels"
        private const val NOTIFICATION_ID = 8317
    }
}
