package cn.zzmllk.amadeus.web

import java.net.URI

fun isAllowedConsoleUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "http" && uri.userInfo == null &&
        (uri.host == "127.0.0.1" || uri.host in CPA_LOOPBACK_HOSTS || uri.host == "localhost") &&
        uri.port in 1..65535
}.getOrDefault(false)

fun isSameConsoleOrigin(initialUrl: String, destinationUrl: String): Boolean = runCatching {
    if (!isAllowedConsoleUrl(initialUrl) || !isAllowedConsoleUrl(destinationUrl)) return false
    val initial = URI(initialUrl)
    val destination = URI(destinationUrl)
    initial.scheme.equals(destination.scheme, ignoreCase = true) &&
        initial.host.equals(destination.host, ignoreCase = true) && initial.port == destination.port
}.getOrDefault(false)

private val CPA_LOOPBACK_HOSTS = (2..17).map { "127.0.0.$it" }.toSet()
