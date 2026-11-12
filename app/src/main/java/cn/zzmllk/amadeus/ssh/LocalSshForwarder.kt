package cn.zzmllk.amadeus.ssh

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    @Volatile var lastErrorType: String? = null

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
        var upstream: Future<*>? = null
        var downstream: Future<*>? = null
        try {
            client.tcpNoDelay = true
            channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(remoteHost)
            channel.setPort(remotePort)
            channel.setOrgIPAddress(client.inetAddress.hostAddress ?: "127.0.0.1")
            channel.setOrgPort(client.port)
            channel.connect(10_000)
            val clientInput = client.getInputStream()
            val clientOutput = client.getOutputStream()
            val channelInput = channel.inputStream
            val channelOutput = channel.outputStream
            upstream = workers.submit { copy(clientInput, channelOutput) }
            downstream = workers.submit { copy(channelInput, clientOutput) }
            while (!closed && !client.isClosed && !channel.isClosed && !upstream.isDone && !downstream.isDone) {
                Thread.sleep(100)
            }
        } catch (error: Exception) {
            lastErrorType = error.javaClass.simpleName.take(48)
        } finally {
            upstream?.cancel(true)
            downstream?.cancel(true)
            try { channel?.disconnect() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (!closed) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { listener.close() } catch (_: Exception) { }
        workers.shutdownNow()
    }
}
