package cn.zzmllk.amadeus.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import cn.zzmllk.amadeus.AppViewModel
import cn.zzmllk.amadeus.ConsoleRequest
import cn.zzmllk.amadeus.ConsoleTarget
import cn.zzmllk.amadeus.R
import cn.zzmllk.amadeus.data.AppConfig
import cn.zzmllk.amadeus.service.TunnelState

private enum class AppTab(val title: String, val icon: ImageVector) {
    HOME("总览", Icons.Rounded.Dashboard),
    TERMINAL("终端", Icons.Rounded.Terminal),
    SUBSCRIPTIONS("订阅", Icons.Rounded.Cloud),
    UPDATES("更新", Icons.Rounded.SystemUpdate),
    SETTINGS("设置", Icons.Rounded.Settings)
}

@Composable
fun AmadeusApp(
    viewModel: AppViewModel,
    onOpenConsole: (ConsoleRequest) -> Unit
) {
    val config by viewModel.config.collectAsState()
    val tunnel by viewModel.tunnel.collectAsState()
    val keyImported by viewModel.keyImported.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val output by viewModel.terminalOutput.collectAsState()
    val fingerprint by viewModel.fingerprint.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val navigationHaze = remember { HazeState() }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var confirmSpeed by remember { mutableStateOf(false) }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPrivateKey(uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.openConsole.collect(onOpenConsole)
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    if (fingerprint != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissHostTrust,
            icon = { Icon(Icons.Rounded.Security, contentDescription = null, tint = GoogleBlue) },
            title = { Text("确认服务器指纹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("请与可信来源中的服务器指纹核对。确认后，后续连接将拒绝任何不匹配的主机密钥。")
                    SelectionContainer {
                        Text(
                            fingerprint.orEmpty(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Ink
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmHostTrust) { Text("确认信任") } },
            dismissButton = { OutlinedButton(onClick = viewModel::dismissHostTrust) { Text("取消") } }
        )
    }

    if (confirmSpeed) {
        AlertDialog(
            onDismissRequest = { confirmSpeed = false },
            icon = { Icon(Icons.Rounded.Speed, contentDescription = null, tint = GoogleBlue) },
            title = { Text("测速并切换节点？") },
            text = { Text("对当前订阅的通用组和 OpenAI 组测速，并为各组选取最快的可用节点。\n\nOpenAI 组直测 ChatGPT 官网（chatgpt.com），仅正常响应的节点参与选点。\n\n主、备用订阅共用此流程；测速不调用反代或模型。") },
            confirmButton = {
                Button(onClick = { confirmSpeed = false; viewModel.runSpeedTest() }) { Text("测速并切换") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmSpeed = false }) { Text("取消") } }
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                AppNavigationRail(selected = selected, onSelected = { selected = it })
            }
            Scaffold(
                modifier = Modifier.weight(1f).imePadding(),
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    if (!useNavigationRail) {
                        AppBottomNavigation(selected = selected, onSelected = { selected = it }, hazeState = navigationHaze)
                    }
                }
            ) { padding ->
                AuroraBackground(Modifier.fillMaxSize().hazeSource(navigationHaze)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding())
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxHeight()
                                .widthIn(max = 920.dp)
                                .fillMaxWidth()
                        ) {
                            AppHeader(tunnel = tunnel, busy = busy)
                            when (AppTab.entries[selected]) {
                                AppTab.HOME -> DashboardScreen(
                                    bottomInset = padding.calculateBottomPadding(),
                                    config = config,
                                    tunnel = tunnel,
                                    busy = busy,
                                    onOpenApi = { viewModel.open(ConsoleTarget.API) },
                                    onOpenAstrBot = { viewModel.open(ConsoleTarget.ASTRBOT) },
                                    onSpeed = { confirmSpeed = true },
                                    onStop = viewModel::stopAllTunnels,
                                    onOpenSettings = { selected = AppTab.SETTINGS.ordinal }
                                )
                                AppTab.TERMINAL -> TerminalScreen(
                                    bottomInset = padding.calculateBottomPadding(),
                                    output = output,
                                    busy = busy,
                                    onRun = viewModel::runTerminalCommand
                                )
                                AppTab.SUBSCRIPTIONS -> SubscriptionScreen(viewModel, padding.calculateBottomPadding())
                                AppTab.UPDATES -> UpdatesScreen(padding.calculateBottomPadding(), busy)
                                AppTab.SETTINGS -> SettingsScreen(
                                    bottomInset = padding.calculateBottomPadding(),
                                    config = config,
                                    keyImported = keyImported,
                                    busy = busy,
                                    onUpdate = viewModel::updateConfig,
                                    // A single wildcard MIME type matters here: some Android document
                                    // providers hide extensionless files when EXTRA_MIME_TYPES is used.
                                    onImportKey = { importKey.launch(arrayOf("*/*")) },
                                    onClearKey = viewModel::clearPrivateKey,
                                    onTest = viewModel::testConnection,
                                    onClearTrust = { viewModel.updateConfig { it.copy(trustedHostKey = "", trustedHostKeyType = "") } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBottomNavigation(selected: Int, onSelected: (Int) -> Unit, hazeState: HazeState) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
      NavigationBar(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .shadow(16.dp, shape, clip = false, ambientColor = GoogleBlue.copy(alpha = 0.16f), spotColor = Ink.copy(alpha = 0.16f))
            .clip(shape)
            .hazeEffect(hazeState, style = HazeStyle(
                backgroundColor = AppCanvas,
                tint = HazeTint(Color.White.copy(alpha = 0.72f)),
                blurRadius = 24.dp,
                noiseFactor = 0.035f,
                fallbackTint = HazeTint(Color.White.copy(alpha = 0.94f))
            ))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), GoogleBlue.copy(alpha = 0.12f))), shape)
            .padding(horizontal = 2.dp),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
      ) {
        AppTab.entries.forEachIndexed { index, tab ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelected(index) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.title, maxLines = 1, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Medium, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = GoogleBlueDark,
                    selectedTextColor = GoogleBlueDark,
                    indicatorColor = GoogleBlue.copy(alpha = 0.14f),
                    unselectedIconColor = MutedInk,
                    unselectedTextColor = MutedInk
                )
            )
        }
    }
    }
}

@Composable
private fun AppNavigationRail(selected: Int, onSelected: (Int) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight().statusBarsPadding(),
        containerColor = Color.White.copy(alpha = 0.96f)
    ) {
        Spacer(Modifier.height(12.dp))
        AppTab.entries.forEachIndexed { index, tab ->
            NavigationRailItem(
                selected = selected == index,
                onClick = { onSelected(index) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.title) },
                alwaysShowLabel = true
            )
        }
    }
}

@Composable
private fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFFF8FAFE), Color(0xFFF3F6FB), Color(0xFFF7F9FD))
            )
        )
    ) {
        Box(
            Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(listOf(GoogleBlue.copy(alpha = 0.14f), Color.Transparent)),
                    CircleShape
                )
        )
        content()
    }
}

@Composable
private fun AppHeader(
    tunnel: TunnelState,
    busy: Boolean
) {
    val density = LocalDensity.current.density
    val compact = density >= 3f
    val horizontal = if (compact) 16.dp else 20.dp
    val vertical = if (compact) 4.dp else 7.dp
    val logoSize = if (compact) 26.dp else 30.dp
    val logoPadding = if (compact) 6.dp else 7.dp
    val gap = if (compact) 8.dp else 10.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = Color.White.copy(alpha = 0.88f),
            border = androidx.compose.foundation.BorderStroke(1.dp, GoogleBlue.copy(alpha = 0.18f))
        ) {
            Image(
                painter = painterResource(R.drawable.amadeus_logo),
                contentDescription = null,
                modifier = Modifier.padding(logoPadding).size(logoSize)
            )
        }
        Spacer(Modifier.width(gap))
        Column(Modifier.weight(1f)) {
            Text("服务器控制台", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text("Amadeus", style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        StatusBadge(
            active = tunnel.apiActive || tunnel.astrBotActive,
            text = when {
                busy || tunnel.connecting -> "处理中"
                tunnel.apiActive || tunnel.astrBotActive -> "通道运行中"
                else -> "待机"
            }
        )
    }
}

@Composable
private fun DashboardScreen(
    bottomInset: Dp,
    config: AppConfig,
    tunnel: TunnelState,
    busy: Boolean,
    onOpenApi: () -> Unit,
    onOpenAstrBot: () -> Unit,
    onSpeed: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomInset)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("快捷管理", style = MaterialTheme.typography.displaySmall)
        Text("通过本机 SSH 安全通道访问服务，管理页面不会直接暴露到公网。", color = MutedInk)
        Spacer(Modifier.height(2.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showConsoleCardsSideBySide = maxWidth >= 760.dp
            if (showConsoleCardsSideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsoleActionCard(
                        target = ConsoleTarget.API,
                        tunnel = tunnel,
                        busy = busy,
                        onClick = onOpenApi,
                        modifier = Modifier.weight(1f)
                    )
                    ConsoleActionCard(
                        target = ConsoleTarget.ASTRBOT,
                        tunnel = tunnel,
                        busy = busy,
                        onClick = onOpenAstrBot,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ConsoleActionCard(ConsoleTarget.API, tunnel, busy, onOpenApi)
                    ConsoleActionCard(ConsoleTarget.ASTRBOT, tunnel, busy, onOpenAstrBot)
                }
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Speed, contentDescription = null, tint = GoogleBlue, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("节点测速与切换", style = MaterialTheme.typography.titleMedium)
                    Text("OpenAI 组直测 ChatGPT 官网", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                FilledTonalButton(onClick = onSpeed, enabled = !busy) { Text("测速切换") }
            }
        }
        if (tunnel.error != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Text(tunnel.error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = GoogleBlue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("连接目标", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (config.host.isBlank()) "尚未配置服务器" else "已配置 SSH 连接参数",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = onOpenSettings) { Text("设置") }
            }
            if (tunnel.apiActive || tunnel.astrBotActive) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Hairline)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止全部通道")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ConsoleActionCard(
    target: ConsoleTarget,
    tunnel: TunnelState,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isApi = target == ConsoleTarget.API
    val active = if (isApi) tunnel.apiActive else tunnel.astrBotActive
    ActionCard(
        icon = if (isApi) Icons.Rounded.Api else Icons.Rounded.Memory,
        eyebrow = if (isApi) "REVERSE API" else "ASTRBOT",
        title = if (isApi) "反代 API 管理" else "AstrBot 控制台",
        description = if (isApi) {
            "建立本地通道，在应用内系统 WebView 打开管理页"
        } else {
            "通过 SSH 转发，在应用内系统 WebView 访问 Dashboard"
        },
        active = active,
        buttonText = when {
            isApi && active -> "打开管理页"
            !isApi && active -> "打开控制台"
            else -> "建立通道并打开"
        },
        enabled = !busy,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    active: Boolean,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(15.dp), color = GoogleBlueSoft) {
                Icon(icon, contentDescription = null, tint = GoogleBlueDark, modifier = Modifier.padding(13.dp).size(27.dp))
            }
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(eyebrow, color = GoogleBlueDark, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(5.dp))
                Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
            StatusBadge(active, if (active) "已连接" else "未连接")
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp)) {
            if (!enabled) CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(buttonText)
        }
    }
}

@Composable
private fun TerminalScreen(output: String, busy: Boolean, bottomInset: Dp, onRun: (String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = bottomInset).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("SSH 命令终端", style = MaterialTheme.typography.headlineSmall)
        Text("执行单条远程命令。高风险命令仍需由你明确输入。", color = MutedInk)
        OutlinedTextField(
            value = command,
            onValueChange = { if (it.length <= 4096) command = it },
            label = { Text("远程命令") },
            placeholder = { Text("例如：docker ps") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                Icon(Icons.Rounded.Terminal, contentDescription = null, tint = GoogleBlue)
            }
        )
        Button(
            onClick = { val value = command.trim(); if (value.isNotEmpty()) { onRun(value); command = "" } },
            enabled = !busy && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text(if (busy) "正在执行……" else "执行命令") }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = TerminalSurface,
            shape = RoundedCornerShape(18.dp)
        ) {
            SelectionContainer {
                Text(
                    output,
                    modifier = Modifier.padding(17.dp).verticalScroll(rememberScrollState()),
                    color = Color(0xFFE8EAED),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun SettingsScreen(
    bottomInset: Dp,
    config: AppConfig,
    keyImported: Boolean,
    busy: Boolean,
    onUpdate: ((AppConfig) -> AppConfig) -> Unit,
    onImportKey: () -> Unit,
    onClearKey: () -> Unit,
    onTest: () -> Unit,
    onClearTrust: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset).padding(horizontal = 20.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("连接设置", style = MaterialTheme.typography.headlineSmall)
        Text("参数修改会自动保存到当前手机。私钥使用 Android Keystore 加密。", color = MutedInk)
        GlassCard {
            Text("服务器", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = config.host,
                onValueChange = { value -> onUpdate { it.copy(host = value) } },
                label = { Text("服务器地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = config.user,
                    onValueChange = { value -> onUpdate { it.copy(user = value) } },
                    label = { Text("SSH 用户名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                PortField("SSH 端口", config.sshPort, Modifier.weight(0.72f)) { port -> onUpdate { it.copy(sshPort = port) } }
            }
        }
        GlassCard {
            Text("本机端口", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PortField("API", config.apiLocalPort, Modifier.weight(1f)) { port -> onUpdate { it.copy(apiLocalPort = port) } }
                PortField("AstrBot", config.astrBotLocalPort, Modifier.weight(1f)) { port -> onUpdate { it.copy(astrBotLocalPort = port) } }
            }
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = GoogleBlue, modifier = Modifier.size(27.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SSH 私钥", style = MaterialTheme.typography.titleMedium)
                    Text(if (keyImported) "已加密保存在本机" else "尚未导入", color = MutedInk)
                }
                if (keyImported) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF188038))
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImportKey, modifier = Modifier.weight(1f)) { Text(if (keyImported) "更换私钥" else "导入私钥") }
                if (keyImported) {
                    OutlinedButton(onClick = onClearKey) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("移除")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "请选择没有 .pub 后缀的私钥文件；文件选择器会显示所有文件。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Security, contentDescription = null, tint = GoogleBlue)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("主机密钥校验", style = MaterialTheme.typography.titleMedium)
                    Text(if (config.hasTrustedHost) "已固定服务器指纹" else "首次连接前需要确认", color = MutedInk)
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onTest, enabled = !busy && keyImported, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "正在测试……" else "测试连接并核对指纹")
            }
            if (config.hasTrustedHost) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onClearTrust, modifier = Modifier.fillMaxWidth()) { Text("清除已信任指纹") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PortField(label: String, port: Int, modifier: Modifier = Modifier, onPort: (Int) -> Unit) {
    var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            if (value.length <= 5 && value.all(Char::isDigit)) {
                text = value
                value.toIntOrNull()?.takeIf { it in 1..65535 }?.let(onPort)
            }
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.94f), Color(0xFFF9FBFF).copy(alpha = 0.88f))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(Color.White, GoogleBlue.copy(alpha = 0.22f), Hairline)),
                shape = shape
            )
            .padding(18.dp),
        content = content
    )
}

@Composable
private fun StatusBadge(active: Boolean, text: String) {
    val background by animateColorAsState(
        if (active) Color(0xFFDDF5E5) else Color(0xFFE9EDF3),
        label = "status-background"
    )
    val foreground = if (active) Color(0xFF137333) else Color(0xFF5F6368)
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(foreground, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(text, color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
