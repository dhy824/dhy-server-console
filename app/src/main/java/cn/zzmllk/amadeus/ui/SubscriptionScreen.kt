package cn.zzmllk.amadeus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import cn.zzmllk.amadeus.AppViewModel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SubscriptionScreen(model: AppViewModel, bottomInset: Dp = 0.dp) {
    val raw by model.subscriptions.collectAsState()
    val busy by model.busy.collectAsState()
    val revealed by model.subscriptionLink.collectAsState()
    val status = remember(raw) { if (raw.isBlank()) JSONObject() else JSONObject(raw) }
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    val action: (String, String) -> Unit = { operation, id ->
        model.subscriptionAction(JSONObject().put("action", operation).put("id", id))
    }
    LaunchedEffect(Unit) { action("list", "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("订阅与网络", style = MaterialTheme.typography.headlineSmall)
        Text("切到备用后，节点自动测速继续生效；所有启用订阅不可用时自动直连。", style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("服务器正在处理，检测或切换可能需要几分钟…")
        }
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前模式：" + when (status.optString("mode")) { "direct" -> "直连"; "rule", "global" -> "订阅代理"; else -> "尚未获取或控制器不可达" })
                Text("当前订阅：" + if (status.optString("mode") == "direct") "未使用代理" else status.optString("active_url", "尚未获取"))
                Text("DNS：" + status.optString("dns", "待获取"))
                Text(if (status.optBoolean("automatic", true)) "自动恢复已开启" else "已手动直连，点击恢复自动模式重新选订阅")
                if (status.optString("notice").isNotBlank()) Text(status.optString("notice"))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { action("list", "") }) { Text("刷新状态") }
            Button(enabled = !busy, onClick = { editId = ""; name = ""; url = ""; enabled = true; editing = true }) { Text("新增订阅") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { action("direct", "") }) { Text("直接直连") }
            OutlinedButton(enabled = !busy, onClick = { action("auto", "") }) { Text("恢复自动模式") }
        }
        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("主、备用共用自动测速", style = MaterialTheme.typography.titleSmall)
                Text("服务器每 15 分钟检查通用节点；有新 API 请求时，同轮检查 OpenAI 节点。无需保持应用打开。", style = MaterialTheme.typography.bodySmall)
                Text("OpenAI 组直测 ChatGPT 官网，仅正常响应参与选点。测速不调用反代或模型。", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("可用性为上次通用网络检测结果，不代表模型可用。修改地址后需点击“切换使用”应用。", style = MaterialTheme.typography.bodySmall)
        val entries = status.optJSONArray("items")
        for (index in 0 until (entries?.length() ?: 0)) {
            val entry = entries!!.getJSONObject(index)
            val id = entry.getString("id")
            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.getString("name") + if (entry.optBoolean("active")) " · 使用中" else "", style = MaterialTheme.typography.titleMedium)
                    Text(entry.optString("url"), style = MaterialTheme.typography.bodySmall)
                    Text((if (entry.optBoolean("enabled")) "参与故障切换" else "故障切换已停用") + " · 通用网络 " + entry.optString("health", "未检测") + " · " + entry.optInt("healthy") + "/" + entry.optInt("total"))
                    if (entry.optBoolean("pending_change")) Text("地址已修改，当前连接仍使用修改前地址。")
                    val stamp = entry.optDouble("checked_at", 0.0)
                    if (stamp > 0) Text("检测时间：" + SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date((stamp * 1000).toLong())), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { action("switch", id) }) { Text("切换使用") }
                        OutlinedButton(enabled = !busy, onClick = { action("test", id) }) { Text("检测可用性") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = { editId = id; name = entry.getString("name"); url = ""; enabled = entry.optBoolean("enabled"); editing = true }) { Text("修改") }
                        OutlinedButton(enabled = !busy, onClick = { action("reveal", id) }) { Text("完整链接") }
                    }
                }
            }
        }
    }
    if (editing) {
        AlertDialog(onDismissRequest = { editing = false; url = "" }, title = { Text(if (editId.isBlank()) "新增订阅" else "修改订阅") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(64) }, label = { Text("订阅名称") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it.take(4096) }, label = { Text("完整 HTTPS 地址") }, placeholder = { Text(if (editId.isBlank()) "粘贴订阅链接" else "留空保留原地址") }, maxLines = 5)
                Row { Checkbox(checked = enabled, onCheckedChange = { enabled = it }); Text("参与自动故障切换", Modifier.padding(top = 12.dp)) }
            }
        }, confirmButton = {
            Button(enabled = name.isNotBlank() && (editId.isNotBlank() || url.isNotBlank()), onClick = {
                model.subscriptionAction(JSONObject().put("action", "save").put("id", editId).put("name", name).put("url", url.trim()).put("enabled", enabled))
                editing = false; url = ""
            }) { Text("保存并检测") }
        }, dismissButton = { OutlinedButton(onClick = { editing = false; url = "" }) { Text("取消") } })
    }
    if (revealed != null) {
        AlertDialog(onDismissRequest = model::closeSubscriptionLink, title = { Text("完整链接（含凭据）") }, text = {
            SelectionContainer { Text(revealed.orEmpty(), Modifier.verticalScroll(rememberScrollState())) }
        }, confirmButton = { Button(onClick = model::closeSubscriptionLink) { Text("关闭") } })
    }
}
