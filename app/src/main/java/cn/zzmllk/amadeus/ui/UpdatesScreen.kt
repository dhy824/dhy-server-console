package cn.zzmllk.amadeus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.zzmllk.amadeus.updates.ProjectVersion
import cn.zzmllk.amadeus.updates.UpdatePlan
import cn.zzmllk.amadeus.updates.UpdateProtocol
import cn.zzmllk.amadeus.updates.UpdatesViewModel

@Composable
fun UpdatesScreen(bottomInset: Dp, otherBusy: Boolean, model: UpdatesViewModel = viewModel()) {
    val projects by model.items.collectAsState()
    val busy by model.busy.collectAsState()
    val pending by model.pending.collectAsState()
    val message by model.message.collectAsState()
    val plan by model.plan.collectAsState()
    val uri = LocalUriHandler.current
    UpdatesContent(bottomInset, projects, busy, pending, message, plan, !otherBusy,
        model::checkVersions, model::query, model::prepare, model::confirm, model::dismissPlan,
        { runCatching { uri.openUri(it) } })
}

// Shares the home screen's glass card, spacing, type and accent colours.
@Composable
internal fun UpdatesContent(
    bottomInset: Dp, projects: List<ProjectVersion>, busy: Boolean, pending: Boolean,
    message: String, plan: UpdatePlan?, connectionIdle: Boolean,
    onCheck: () -> Unit, onQuery: () -> Unit, onPrepare: (String) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onOpen: (String) -> Unit
) {
    val enabled = !busy && connectionIdle
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDetails by rememberSaveable { mutableStateOf(false) }

    plan?.let { selected ->
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = GoogleBlue) },
            title = { Text("确认升级 ${projects.firstOrNull { it.id == selected.project }?.name ?: selected.project}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${selected.installed} → ${selected.target}", style = MaterialTheme.typography.titleMedium)
                    Text(UpdateProtocol.impact(selected.project))
                    Text("计划有效期 5 分钟，状态变化后需重新准备。手机断线不会取消已启动的服务器任务；请用原编号查询。")
                    SelectionContainer { Text("任务/备份编号：${selected.requestId}") }
                }
            },
            confirmButton = { Button(onClick = onConfirm, enabled = enabled && !pending) { Text("确认本次升级") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("项目更新", style = MaterialTheme.typography.displaySmall)
                Text("查看官方版本与更新说明，按需升级服务器项目。", color = MutedInk)
            }
        }
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UpdateIcon(Icons.Rounded.SystemUpdate)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("更新中心", style = MaterialTheme.typography.titleMedium)
                        Text("4 个项目 · 每次升级单独确认", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = onCheck, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "正在处理…" else "检查所有项目")
                }
                TextButton(onClick = onQuery, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.History, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (pending) "查询当前任务进度" else "查询上次升级结果")
                }
                HorizontalDivider(color = Hairline)
                Spacer(Modifier.height(12.dp))
                val visibleMessage = message.substringBefore("\n任务/备份编号：")
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(visibleMessage.substringBefore('\n'), style = MaterialTheme.typography.bodyMedium, color = Ink)
                        if ('\n' in visibleMessage) Text(visibleMessage.substringAfter('\n'), style = MaterialTheme.typography.bodyMedium, color = MutedInk)
                    }
                }
                if (message.contains("\n任务/备份编号：")) {
                    TextButton(onClick = { taskDetails = !taskDetails }) {
                        Text(if (taskDetails) "收起任务编号" else "查看任务编号")
                    }
                    if (taskDetails) SelectionContainer {
                        Text(message.substringAfter("\n任务/备份编号："), style = MaterialTheme.typography.bodySmall, color = MutedInk)
                    }
                }
                if (pending) {
                    Spacer(Modifier.height(8.dp))
                    Surface(color = GoogleBlueSoft.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
                        Text("任务尚未核对，已暂停新升级。恢复原连接后查询，请保留应用数据。",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = GoogleBlueDark)
                    }
                }
            }
        }
        items(projects, key = { it.id }) { project ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UpdateIcon(when (project.id) {
                        "mihomo" -> Icons.Rounded.Speed
                        "nginx" -> Icons.Rounded.Language
                        "cliproxyapi" -> Icons.Rounded.Api
                        else -> Icons.Rounded.Memory
                    })
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.titleLarge)
                        Text(when (project.id) {
                            "mihomo" -> "代理与网络"
                            "nginx" -> "网站与 API 入口"
                            "cliproxyapi" -> "反代 API 服务"
                            else -> "机器人平台"
                        }, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(color = GoogleBlueSoft.copy(alpha = 0.5f), shape = RoundedCornerShape(10.dp)) {
                    Text(project.status, Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = GoogleBlueDark, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 280.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            VersionTile("已安装", project.installed)
                            VersionTile("官方上游", project.latest)
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        VersionTile("已安装", project.installed, Modifier.weight(1f))
                        VersionTile("官方上游", project.latest, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (project.id == "astrbot") {
                    Text("核心升级使用既有维护流程。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                } else {
                    FilledTonalButton(onClick = { onPrepare(project.id) }, enabled = enabled && !pending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.SystemUpdate, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("准备升级计划")
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Hairline)
                TextButton(onClick = { expanded = if (expanded == project.id) null else project.id },
                    contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (expanded == project.id) "收起更新说明" else "更新说明与来源", modifier = Modifier.weight(1f))
                    Icon(if (expanded == project.id) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                }
                if (expanded == project.id) {
                    SelectionContainer {
                        Text(project.notes.ifBlank { "检查后显示官方更新说明。" }, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(project.repository, color = MutedInk, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { onOpen(project.url) }) {
                        Text("打开官方发布页")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateIcon(icon: ImageVector) {
    Surface(shape = RoundedCornerShape(15.dp), color = GoogleBlueSoft) {
        Icon(icon, null, tint = GoogleBlueDark, modifier = Modifier.padding(13.dp).size(27.dp))
    }
}

@Composable
private fun VersionTile(label: String, version: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = Color(0xFFF0F4FA), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium)
            Text(version, color = Ink, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
