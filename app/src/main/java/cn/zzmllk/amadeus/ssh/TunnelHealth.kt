package cn.zzmllk.amadeus.ssh

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.Locale
import cn.zzmllk.amadeus.data.IdentityKeyException

/** Probe fixed loopback HTTP services; no credentials, redirects, body reads or system proxy. */
fun tunnelHttpReady(port: Int, timeoutMillis: Int = 1_500): Boolean {
    if (port !in 1024..65535) return false
    var connection: HttpURLConnection? = null
    return try {
        connection = URL("http://127.0.0.1:$port/").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.instanceFollowRedirects = false
        connection.responseCode in 200..499
    } catch (_: Exception) {
        false
    } finally {
        connection?.disconnect()
    }
}

/** Error text never includes peer addresses, user names, key paths or raw library messages. */
fun tunnelFailure(error: Exception, phase: String): String {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    chain.filterIsInstance<IdentityKeyException>().firstOrNull()?.let { return "$phase：${it.message}" }
    val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
    val detail = when {
        chain.any { it is java.net.BindException } || "address already in use" in text || "portforwardingl" in text -> "本机端口被占用，请检查设置中的本地端口。"
        "auth fail" in text || "authentication" in text -> "SSH 身份验证失败，请核对用户名及已导入的私钥。"
        "hostkey" in text || "host key" in text || "reject hostkey" in text -> "服务器主机密钥校验失败，请在设置中重新测试并核对指纹。"
        chain.any { it is java.net.SocketTimeoutException } || "timeout" in text || "timed out" in text -> "连接超时，请检查网络和服务器 SSH 连通性。"
        chain.any { it is java.net.UnknownHostException } -> "服务器地址解析失败，请检查地址和网络。"
        chain.any { it is java.net.ConnectException } -> "连接被拒绝或无法到达，请检查网络和服务器 SSH 状态。"
        else -> "请检查连接设置，或在设置中测试 SSH 连接。"
    }
    val type = chain.last().javaClass.simpleName.replace(Regex("[^A-Za-z0-9_]"), "").take(48).ifBlank { "Exception" }
    return "$phase：$detail（$type）"
}
