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
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

data class TunnelState(
    val connecting: Boolean = false,
    val apiActive: Boolean = false,
    val astrBotActive: Boolean = false,
    val message: String = "隧道未启动",
    val error: String? = null
)

object TunnelRuntime {
    private val mutableState = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = mutableState.asStateFlow()
    internal fun update(value: TunnelState) { mutableState.value = value }
}

class TunnelService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forceReconnect = intent?.getBooleanExtra(EXTRA_FORCE_RECONNECT, false) == true
        when (intent?.action) {
            ACTION_START_API -> executor.execute { startTunnel(Target.API, forceReconnect) }
            ACTION_START_ASTRBOT -> executor.execute { startTunnel(Target.ASTRBOT, forceReconnect) }
            ACTION_STOP_ALL -> executor.execute { stopAll("隧道已停止") }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        disconnectSession()
        TunnelRuntime.update(TunnelState())
        super.onDestroy()
    }

    private fun startTunnel(target: Target, forceReconnect: Boolean = false) {
        val previous = TunnelRuntime.state.value
        val restoreApi = previous.apiActive || apiForwardPort != null || target == Target.API
        val restoreAstrBot = previous.astrBotActive || astrBotForwardPort != null || target == Target.ASTRBOT
        TunnelRuntime.update(previous.copy(connecting = true, error = null, message = "正在建立 SSH 安全通道……"))
        updateNotification("正在连接服务器……")
        try {
            val config = configStore.state.value
            check(keyStore.hasKey) { "尚未导入 SSH 私钥。" }
            check(config.hasTrustedHost) { "尚未信任服务器主机密钥，请先在设置中测试连接。" }
            val signature = "${config.user}@${config.host}:${config.sshPort}:${config.apiLocalPort}:${config.astrBotLocalPort}"
            if (forceReconnect || session?.isConnected != true || signature != connectionSignature) {
                disconnectSession()
                session = SshEngine.connect(config, keyStore.read())
                connectionSignature = signature
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
                connecting = false,
                apiActive = apiForwardPort != null,
                astrBotActive = astrBotForwardPort != null,
                message = when {
                    apiForwardPort != null && astrBotForwardPort != null -> "API 与 AstrBot 通道运行中"
                    apiForwardPort != null -> "反代 API 通道运行中"
                    else -> "AstrBot 通道运行中"
                }
            )
            TunnelRuntime.update(state)
            updateNotification(state.message)
        } catch (error: Exception) {
            disconnectSession()
            val message = error.message ?: "建立隧道失败。"
            TunnelRuntime.update(TunnelState(error = message, message = "连接失败"))
            updateNotification("连接失败：$message")
        }
    }

    private fun stopAll(message: String) {
        disconnectSession()
        TunnelRuntime.update(TunnelState(message = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun disconnectSession() {
        try { session?.disconnect() } catch (_: Exception) { }
        session = null
        connectionSignature = ""
        apiForwardPort = null
        astrBotForwardPort = null
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
        private const val CHANNEL_ID = "amadeus_tunnels"
        private const val NOTIFICATION_ID = 8317
    }
}
