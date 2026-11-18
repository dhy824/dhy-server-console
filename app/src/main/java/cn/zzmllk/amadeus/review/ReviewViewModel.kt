package cn.zzmllk.amadeus.review

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.zzmllk.amadeus.data.ActiveServerStore
import cn.zzmllk.amadeus.data.ServerService
import cn.zzmllk.amadeus.ssh.SshEngine
import cn.zzmllk.amadeus.ssh.connectionIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

data class ReviewBatch(val id: String, val title: String, val total: Int, val reviewed: Int, val lastSaved: Double?)

class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val servers = ActiveServerStore(application)
    private val mutableBatches = MutableStateFlow<List<ReviewBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableMessage = MutableStateFlow("刷新查看服务器批次。手机与电脑共用已保存的判断。")
    val message = mutableMessage.asStateFlow()

    fun refresh() {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                val connection = servers.current()
                check(connection.supports(ServerService.REVIEW)) { "当前服务器未配置审核功能。" }
                check(connection.hasKey && connection.config.hasTrustedHost) { "请先为当前服务器配置 SSH 私钥并核对主机指纹。" }
                val identity = connectionIdentity(connection.config, connection.keyRevision)
                mutableMessage.value = "正在读取审核批次……"
                val privateKey = servers.readKey(connection)
                val response = runInterruptible(Dispatchers.IO) {
                    try { JSONObject(SshEngine.reviewCenterRequest(connection.config, privateKey)) }
                    finally { privateKey.fill(0) }
                }
                val current = servers.current()
                check(connectionIdentity(current.config, current.keyRevision) == identity) { "当前服务器或私钥已变化，请重新刷新。" }
                check(response.optString("status") == "ok") { "服务器审核组件尚不可用，请核对版本。" }
                val rows = response.getJSONObject("data").getJSONArray("batches")
                mutableBatches.value = List(rows.length()) { index ->
                    val row = rows.getJSONObject(index)
                    val total = row.getInt("total")
                    val reviewed = row.getInt("reviewed")
                    check(total >= 0 && reviewed in 0..total)
                    ReviewBatch(row.getString("batch_id"), row.getString("title"), total, reviewed,
                        if (row.isNull("last_saved")) null else row.getDouble("last_saved"))
                }
                mutableMessage.value = if (rows.length() == 0) "暂无批次，新审核任务准备好后会显示在这里。" else "已从服务器更新。打开审核中心继续判断；首次使用需登录 AstrBot。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableMessage.value = if (error is IllegalStateException) error.message ?: "审核读取失败。" else "读取失败，请检查连接和服务器审核组件。上次列表保留。"
            } finally {
                mutableBusy.value = false
            }
        }
    }
}
