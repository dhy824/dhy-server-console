package cn.zzmllk.amadeus.web

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import cn.zzmllk.amadeus.service.TunnelService
import cn.zzmllk.amadeus.ui.AmadeusTheme
import cn.zzmllk.amadeus.ui.enableAmadeusEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class WebConsoleActivity : ComponentActivity() {
    private var externalBrowserOpening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkTheme = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        enableAmadeusEdgeToEdge(darkTheme)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "本机控制台" }
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!isSafeLocalUrl(url)) {
            finish()
            return
        }
        setContent {
            AmadeusTheme(darkTheme = darkTheme) {
                var webView by remember { mutableStateOf<WebView?>(null) }
                BackHandler {
                    val current = webView
                    if (current?.canGoBack() == true) current.goBack() else finish()
                }
                DisposableEffect(Unit) {
                    onDispose {
                        webView?.stopLoading()
                        webView?.clearCache(true)
                        webView?.clearHistory()
                        webView?.removeAllViews()
                        webView?.destroy()
                        webView = null
                    }
                }
                Scaffold(
                    topBar = {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .statusBarsPadding()
                                        .height(COMPACT_TOOLBAR_HEIGHT)
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                                    }
                                    Text(
                                        text = title,
                                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(onClick = { openInExternalBrowser(url) }) {
                                        Icon(Icons.Rounded.OpenInBrowser, contentDescription = "用系统浏览器打开")
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AndroidView(
                            factory = {
                                createWebView(darkTheme).also { view ->
                                    webView = view
                                    view.doOnLayout {
                                        if (view.url == null) view.loadUrl(url)
                                    }
                                }
                            },
                            modifier = Modifier.matchParentSize(),
                            update = {
                                webView = it
                            }
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(darkTheme: Boolean): WebView = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // These are loopback management pages. Avoid retaining their HTTP assets in the app's
        // WebView profile; cookies and DOM storage remain intact so login state is preserved.
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.safeBrowsingEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        clearCache(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            settings.forceDark = if (darkTheme) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        setBackgroundColor(if (darkTheme) Color.rgb(17, 19, 24) else Color.WHITE)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val destination = request?.url?.toString().orEmpty()
                return !isSafeLocalUrl(destination)
            }
        }
    }

    private fun openInExternalBrowser(url: String) {
        if (externalBrowserOpening) return
        val tunnelAction = if (intent.getStringExtra(EXTRA_TITLE).orEmpty().contains("AstrBot", ignoreCase = true)) {
            TunnelService.ACTION_START_ASTRBOT
        } else {
            TunnelService.ACTION_START_API
        }
        lifecycleScope.launch {
            externalBrowserOpening = true
            Toast.makeText(this@WebConsoleActivity, "正在确认浏览器通道……", Toast.LENGTH_SHORT).show()
            try {
                requestTunnel(tunnelAction, forceReconnect = false)
                var reachable = waitUntilReachable(url, NORMAL_PROBE_TIMEOUT_MS)
                if (!reachable) {
                    requestTunnel(tunnelAction, forceReconnect = true)
                    reachable = waitUntilReachable(url, RECONNECT_PROBE_TIMEOUT_MS)
                }
                check(reachable) { "local tunnel unavailable" }
                startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            } catch (_: Exception) {
                Toast.makeText(
                    this@WebConsoleActivity,
                    "本地隧道暂时不可用，请检查网络后重试。",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                externalBrowserOpening = false
            }
        }
    }

    private fun requestTunnel(action: String, forceReconnect: Boolean) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TunnelService::class.java)
                .setAction(action)
                .putExtra(TunnelService.EXTRA_FORCE_RECONNECT, forceReconnect)
        )
    }

    private suspend fun waitUntilReachable(url: String, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (probeLocalHttp(url)) return@withContext true
            delay(PROBE_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        false
    }

    private fun probeLocalHttp(url: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = HTTP_PROBE_TIMEOUT_MS
            connection.readTimeout = HTTP_PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.responseCode in 100..599
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun isSafeLocalUrl(value: String): Boolean = runCatching {
        val uri = value.toUri()
        uri.scheme == "http" &&
            uri.userInfo == null &&
            (uri.host == "127.0.0.1" || uri.host == "localhost") &&
            uri.port in 1..65535
    }.getOrDefault(false)

    companion object {
        private val COMPACT_TOOLBAR_HEIGHT = 52.dp
        private const val NORMAL_PROBE_TIMEOUT_MS = 2_500L
        private const val RECONNECT_PROBE_TIMEOUT_MS = 15_000L
        private const val PROBE_INTERVAL_MS = 300L
        private const val HTTP_PROBE_TIMEOUT_MS = 1_500
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
    }
}
