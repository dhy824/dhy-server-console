package cn.zzmllk.amadeus.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android-local TCP listener backed by one SSH direct-tcpip channel per client.
 * This deliberately does not use JSch PortWatcher, whose listener lifecycle is
 * unreliable on some Android network stacks.
 */
class LocalSshForwarder(
    private val session: Session,
    private val remoteHost: String,
    private val remotePort: Int
) : AutoCloseable {
    private val threadId = AtomicInteger(0)
    private val workers = Executors.newCachedThreadPool(ThreadFactory { runnable ->
        Thread(runnable, "amadeus-zako-forward-${threadId.incrementAndGet()}").apply { isDaemon = true }
    })
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed = false

    val localPort: Int get() = listener.localPort

    init {
        workers.execute(::acceptLoop)
    }

    val isOpen: Boolean
        get() = !closed && !listener.isClosed && session.isConnected

    private fun acceptLoop() {
        try {
            while (!closed) {
                val client = listener.accept()
                workers.execute { bridge(client) }
            }
        } catch (_: Exception) {
            if (!closed) close()
        }
    }

    private fun bridge(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.tcpNoDelay = true
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setInputStream(client.getInputStream())
            channel.setOutputStream(client.getOutputStream())
            channel.setHost(remoteHost)
            channel.setPort(remotePort)
            channel.setOrgIPAddress(client.inetAddress.hostAddress ?: "127.0.0.1")
            channel.setOrgPort(client.port)
            channel.connect(10_000)
            while (!closed && !client.isClosed && !channel.isClosed) {
                Thread.sleep(100)
            }
        } catch (_: Exception) {
            // The client receives a reset when the SSH direct-tcpip channel is refused.
        } finally {
            try { channel?.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { listener.close() } catch (_: Exception) { }
        workers.shutdownNow()
    }
}
