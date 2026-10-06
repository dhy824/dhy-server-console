package cn.zzmllk.amadeus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = !busy && !otherBusy

    plan?.let { selected ->
        AlertDialog(
            onDismissRequest = model::dismissPlan,
            title = { Text("确认升级 ${projects.first { it.id == selected.project }.name}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${selected.installed} → ${selected.target}", style = MaterialTheme.typography.titleMedium)
                    Text(UpdateProtocol.impact(selected.project))
                    Text("计划有效期 5 分钟，状态变化后需重新准备。手机断线不会取消已启动的服务器任务；请用原编号查询。")
                    SelectionContainer { Text("任务/备份编号：${selected.requestId}") }
                }
            },
            confirmButton = { Button(onClick = model::confirm, enabled = enabled && !pending) { Text("确认本次升级") } },
            dismissButton = { TextButton(onClick = model::dismissPlan) { Text("取消") } }
        )
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomInset + 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("项目更新", style = MaterialTheme.typography.headlineSmall)
                Text("检查版本和更新说明；每次升级单独确认。服务器需已安装工具箱更新组件。")
                Button(onClick = model::checkVersions, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在处理……" else "检查所有项目")
                }
                OutlinedButton(onClick = model::query, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("查询上次升级结果") }
                SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
                if (pending) Text("有未核对的任务，已暂停新升级。恢复原服务器连接后查询；不要清除应用数据。", color = MaterialTheme.colorScheme.error)
            }
        }
        items(projects, key = { it.id }) { project ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleLarge)
                    Text("已安装：${project.installed}\n上游：${project.latest}")
                    Text(project.status, color = MaterialTheme.colorScheme.primary)
                    Text(project.repository, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { expanded = if (expanded == project.id) null else project.id }) {
                        Text(if (expanded == project.id) "收起更新说明" else "查看更新说明")
                    }
                    if (expanded == project.id) SelectionContainer { Text(project.notes.ifBlank { "检查后显示官方更新说明。" }) }
                    TextButton(onClick = { runCatching { uri.openUri(project.url) } }) { Text("打开官方发布页") }
                    if (project.id == "astrbot") Text("仅检查版本；核心升级使用既有维护流程。")
                    else OutlinedButton(onClick = { model.prepare(project.id) }, enabled = enabled && !pending) { Text("准备升级计划") }
                }
            }
        }
    }
}
