package cn.zzmllk.amadeus.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.zzmllk.amadeus.review.ReviewViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ReviewScreen(bottomInset: Dp, otherBusy: Boolean, onOpen: () -> Unit, model: ReviewViewModel = viewModel()) {
    val batches by model.batches.collectAsState()
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = bottomInset + 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("审核中心", style = MaterialTheme.typography.displaySmall)
            Text("人格对照、文本与差异审核，共用一个入口。", color = MutedInk)
        }
        item {
            GlassCard {
                Icon(Icons.Rounded.CheckCircle, null, tint = GoogleBlue, modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(12.dp))
                Text("手机和电脑同步", style = MaterialTheme.typography.titleMedium)
                Text("批次与判断保存在服务器。审核只记录意见，应用变更仍走原确认流程。", color = MutedInk)
                Spacer(Modifier.height(16.dp))
                Button(onClick = model::refresh, enabled = !busy && !otherBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(8.dp)); Text(if (busy) "正在读取…" else "刷新审核批次")
                }
                OutlinedButton(onClick = onOpen, enabled = !busy && !otherBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("打开审核中心")
                }
                Text(message, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(batches, key = { it.id }) { batch ->
            GlassCard {
                Text(batch.title, style = MaterialTheme.typography.titleMedium)
                Text("已审 ${batch.reviewed} / ${batch.total} · 待审 ${batch.total - batch.reviewed}", color = GoogleBlue)
                LinearProgressIndicator(progress = { if (batch.total == 0) 0f else batch.reviewed.toFloat() / batch.total }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                Text("最近保存：" + (batch.lastSaved?.let { DateFormat.getDateTimeInstance().format(Date((it * 1000).toLong())) } ?: "尚未保存"), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
