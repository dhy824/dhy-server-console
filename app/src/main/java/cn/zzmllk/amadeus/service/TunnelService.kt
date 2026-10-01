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
        val previous = TunnelRuntime.state.value
        val restoreApi = previous.apiActive || apiForwardPort != null || target == Target.API
        val restoreAstrBot = previous.astrBotActive || astrBotForwardPort != null || target == Target.ASTRBOT
        publish(previous.copy(connecting = true, error = null, requestId = requestId, message = "正在建立 SSH 安全通道……"))
        try {
            val config = configStore.current()
            check(keyStore.hasKey) { "尚未导入 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = connectionIdentity(config, keyStore.revision)
            if (forceReconnect || session?.isConnected != true || signature != connectionSignature) {
                disconnectSession()
                val created = SshEngine.connect(config, keyStore.read())
                synchronized(sessionLock) {
                    if (destroyed) { created.disconnect(); return }
                    session = created
                    connectionSignature = signature
                }
            }
            val connected = session ?: error("SSH 会话未建立。")
            if (restoreApi && apiForwardPort == null) {
                connected.setPortForwardingL("127.0.0.1", config.apiLocalPort, "127.0.0.1", 8317)
                apiForwardPort = config.apiLocalPort
            }
            if (restoreAstrBot && astrBotForwardPort == null) {
                connected.setPortForwardingL("127.0.0.1", config.astrBotLocalPort, "127.0.0.1", 6185)
                astrBotForwardPort = config.astrBotLocalPort
            }
            val state = TunnelState(
                requestId = requestId,
                connecting = false,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                message = when {
                    apiForwardPort != null && astrBotForwardPort != null -> "API 与 AstrBot 通道运行中"
                    apiForwardPort != null -> "反代 API 通道运行中"
                    else -> "AstrBot 通道运行中"
                }
            )
            publish(state)
        } catch (error: Exception) {
            disconnectSession()
            if (destroyed) return
            val message = error.message ?: "建立隧道失败。"
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
    }

    private fun refreshConnectionState() {
        if (destroyed || session == null) return
        try {
            if (session?.isConnected == true && connectionSignature == connectionIdentity(configStore.current(), keyStore.revision)) return
        } catch (_: Exception) { }
        disconnectSession()
        val message = "SSH 已断开或连接设置已变化，请重新打开通道。"
        publish(TunnelState(message = message, error = message))
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

    private enum class Target { API, ASTRBOT }

    companion object {
        const val ACTION_START_API = "cn.zzmllk.amadeus.START_API"
        const val ACTION_START_ASTRBOT = "cn.zzmllk.amadeus.START_ASTRBOT"
        const val ACTION_STOP_ALL = "cn.zzmllk.amadeus.STOP_ALL"
        const val EXTRA_FORCE_RECONNECT = "force_reconnect"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "amadeus_tunnels"
        private const val NOTIFICATION_ID = 8317
    }
}
