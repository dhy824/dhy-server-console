package cn.zzmllk.amadeus.ssh

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class TunnelHealthTest {
    private fun probe(status: Int): Boolean = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
        val worker = thread {
            server.accept().use { socket ->
                if (status == 0) Thread.sleep(400)
                else {
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 $status Test\r\nContent-Length: 0\r\nLocation: http://example.invalid/\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        try { tunnelHttpReady(server.localPort, if (status == 0) 150 else 2_000) } finally { worker.join(2_000) }
    }

    @Test fun actualPageAndLoginChallengeAreReachable() {
        assertTrue(probe(200))
        assertTrue(probe(401))
        assertTrue(probe(302)) // Redirects are not followed beyond loopback.
    }

    @Test fun listeningWithoutAResponseIsNotAWorkingTunnel() {
        assertFalse(probe(0))
        assertFalse(probe(503))
        assertFalse(tunnelHttpReady(0))
    }

    @Test fun failuresKeepStageAndCauseWithoutLeakingConnectionValues() {
        val secret = "private-user@secret.example /private/key.pem"
        val auth = tunnelFailure(Exception("Auth fail $secret"), "SSH 身份验证与主机校验")
        assertTrue(auth.contains("身份验证失败"))
        assertFalse(auth.contains(secret))
        assertTrue(tunnelFailure(Exception(null, SocketTimeoutException(secret)), "检查已有通道").contains("SocketTimeoutException"))
        assertTrue(tunnelFailure(Exception(null as String?), "建立本地转发").contains("建立本地转发"))
        assertTrue(tunnelFailure(Exception("reject HostKey: $secret"), "SSH").contains("主机密钥校验失败"))
        assertTrue(tunnelFailure(java.net.BindException(secret), "建立本地转发").contains("本机端口被占用"))
    }
}
