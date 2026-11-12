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
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.ssh.SshEngine
import cn.zzmllk.amadeus.ssh.connectionIdentity
import cn.zzmllk.amadeus.ssh.tunnelHttpReady
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
    private lateinit var keyStore: SecureKeyStore
    private var session: Session? = null
    private var connectionSignature = ""
    private var apiForwardPort: Int? = null
    private var astrBotForwardPort: Int? = null
    private var zakoForwardPort: Int? = null

    override fun onCreate() {
        super.onCreate()
        configStore = ConfigStore(this)
        keyStore = SecureKeyStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("准备安全隧道……"))
        executor.scheduleWithFixedDelay({ refreshConnectionState() }, 5, 5, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceReconnect = intent?.getBooleanExtra(EXTRA_FORCE_RECONNECT, false) == true
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (destroyed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_API -> executor.execute { startTunnel(Target.API, forceReconnect, requestId) }
            ACTION_START_ASTRBOT -> executor.execute { startTunnel(Target.ASTRBOT, forceReconnect, requestId) }
            ACTION_START_ZAKO -> executor.execute { startTunnel(Target.ZAKO, forceReconnect, requestId) }
            ACTION_STOP_ALL -> executor.execute { stopAll("隧道已停止") }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(sessionLock) {
            destroyed = true
            disconnectSession()
            TunnelRuntime.update(TunnelState())
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startTunnel(target: Target, forceReconnect: Boolean = false, requestId: String = "") {
        if (destroyed) return
        if (target == Target.ZAKO) {
            startZakoTunnel(forceReconnect, requestId)
            return
        }
        val previous = TunnelRuntime.state.value
        val restoreApi = previous.apiActive || apiForwardPort != null || target == Target.API
        val restoreAstrBot = previous.astrBotActive || astrBotForwardPort != null || target == Target.ASTRBOT
        val restoreZako = previous.zakoActive || zakoForwardPort != null || target == Target.ZAKO
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 SSH 安全通道……"))
        var phase = "检查连接配置"
        try {
            val config = configStore.current()
            check(keyStore.hasKey) { "尚未导入 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = connectionIdentity(config, keyStore.revision)
            phase = "检查已有通道"
            val reuse = !forceReconnect && session?.isConnected == true && signature == connectionSignature &&
                activePagesReady()
            if (!reuse) {
                disconnectSession()
                phase = "读取手机中的 SSH 私钥"
                val privateKey = keyStore.read()
                phase = "SSH 身份验证与主机校验"
                val created = try { SshEngine.connect(config, privateKey) } finally { privateKey.fill(0) }
                synchronized(sessionLock) {
                    if (destroyed) { created.disconnect(); return }
                    session = created
                    connectionSignature = signature
                }
            }
            val connected = session ?: error("SSH 会话未建立。")
            phase = "建立本地转发"
            if (restoreApi && apiForwardPort == null) {
                connected.setPortForwardingL("127.0.0.1", config.apiLocalPort, "127.0.0.1", 8317)
                apiForwardPort = config.apiLocalPort
            }
            if (restoreAstrBot && astrBotForwardPort == null) {
                connected.setPortForwardingL("127.0.0.1", config.astrBotLocalPort, "127.0.0.1", 6185)
                astrBotForwardPort = config.astrBotLocalPort
            }
            if (restoreZako && zakoForwardPort == null) {
                check(config.apiLocalPort != ZAKO_LOCAL_PORT && config.astrBotLocalPort != ZAKO_LOCAL_PORT) { "Zako 本地端口与其他通道设置冲突。" }
                connected.setPortForwardingL("127.0.0.1", ZAKO_LOCAL_PORT, "127.0.0.1", 1990)
                zakoForwardPort = ZAKO_LOCAL_PORT
            }
            phase = "检查目标页面"
            if (target != Target.ZAKO) check(waitForActivePages()) { "目标页面未响应。" }
            val state = TunnelState(
                requestId = requestId,
                connecting = false,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                zakoActive = zakoForwardPort != null,
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
                "尚未导入 SSH 私钥。", "尚未信任服务器主机密钥，请先在设置中测试连接。" -> error.message!!
                "目标页面未响应。" -> "SSH 已连接，但目标页面未响应；请检查服务器服务或网络后重新打开。"
                else -> tunnelFailure(error, phase)
            }
            publish(TunnelState(error = message, message = "连接失败：$message", requestId = requestId))
        }
    }

    private fun startZakoTunnel(forceReconnect: Boolean, requestId: String) {
        if (destroyed) return
        val previous = TunnelRuntime.state.value
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 Zako SSH 转发……"))
        var phase = "检查连接配置"
        try {
            val config = configStore.current()
            check(keyStore.hasKey) { "尚未导入 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = connectionIdentity(config, keyStore.revision)
            val canReuse = !forceReconnect && session?.isConnected == true && signature == connectionSignature &&
                zakoForwardPort?.let { tunnelHttpReady(it, "/", ZAKO_PROBE_TIMEOUT_MS) } == true
            if (!canReuse) {
                disconnectSession()
                phase = "读取手机中的 SSH 私钥"
                val privateKey = keyStore.read()
                phase = "SSH 身份验证与主机校验"
                val created = try { SshEngine.connect(config, privateKey) } finally { privateKey.fill(0) }
                synchronized(sessionLock) {
                    if (destroyed) { created.disconnect(); return }
                    session = created
                    connectionSignature = signature
                }
            }
            phase = "建立 Zako 本地转发"
            val connected = session ?: error("SSH 会话未建立。")
            if (zakoForwardPort == null) {
                // Do not reserve a well-known port on the phone. Other local apps and stale
                // forwarding threads can occupy it even though SSH authentication succeeded.
                zakoForwardPort = connected.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", 1990)
            }
            val localPort = zakoForwardPort ?: error("Zako 本地转发未返回端口。")
            phase = "检查 Zako 本地转发"
            check(waitForZakoPage(localPort)) { "Zako 本地转发已建立，但网页未响应。" }
            publish(TunnelState(
                requestId = requestId,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                zakoActive = true,
                zakoLocalPort = localPort,
                message = "Zako SSH 转发已建立"
            ))
        } catch (error: Exception) {
            disconnectSession()
            if (destroyed) return
            val message = when (error.message) {
                "尚未导入 SSH 私钥。", "尚未信任服务器主机密钥，请先在设置中测试连接。",
                "Zako 本地转发已建立，但网页未响应。" -> error.message!!
                else -> tunnelFailure(error, phase)
            }
            publish(TunnelState(error = message, message = "连接失败：$message", requestId = requestId))
        }
    }

    private fun stopAll(message: String) {
        disconnectSession()
        publish(TunnelState(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disconnectSession() = synchronized(sessionLock) {
        try { session?.disconnect() } catch (_: Exception) { }
        session = null
        connectionSignature = ""
        apiForwardPort = null
        astrBotForwardPort = null
        zakoForwardPort = null
    }

    private fun refreshConnectionState() {
        if (destroyed || session == null) return
        try {
            if (session?.isConnected == true && connectionSignature == connectionIdentity(configStore.current(), keyStore.revision) && activePagesReady()) return
        } catch (_: Exception) { }
        disconnectSession()
        val message = "通道已断开、页面未响应或连接设置已变化，请重新打开通道。"
        publish(TunnelState(message = message, error = message))
    }

    private fun activePagesReady(): Boolean =
        (apiForwardPort?.let { tunnelHttpReady(it) } ?: true) &&
            (astrBotForwardPort?.let { tunnelHttpReady(it) } ?: true)

    private fun waitForActivePages(): Boolean {
        repeat(3) { attempt ->
            if (activePagesReady()) return true
            if (attempt < 2) Thread.sleep(750)
        }
        return false
    }

    private fun waitForZakoPage(port: Int): Boolean {
        repeat(ZAKO_PROBE_ATTEMPTS) { attempt ->
            if (tunnelHttpReady(port, "/", ZAKO_PROBE_TIMEOUT_MS)) return true
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
        const val ACTION_STOP_ALL = "cn.zzmllk.amadeus.STOP_ALL"
        const val EXTRA_FORCE_RECONNECT = "force_reconnect"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val ZAKO_PROBE_TIMEOUT_MS = 1_500
        private const val ZAKO_PROBE_INTERVAL_MS = 250L
        private const val ZAKO_PROBE_ATTEMPTS = 8
        private const val CHANNEL_ID = "amadeus_tunnels"
        private const val NOTIFICATION_ID = 8317
    }
}
