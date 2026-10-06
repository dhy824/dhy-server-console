package cn.zzmllk.amadeus.updates

import android.app.Application
import android.util.AtomicFile
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.zzmllk.amadeus.data.AppConfig
import cn.zzmllk.amadeus.data.ConfigStore
import cn.zzmllk.amadeus.data.SecureKeyStore
import cn.zzmllk.amadeus.ssh.SshEngine
import cn.zzmllk.amadeus.ssh.connectionIdentity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

class UpdatesViewModel(application: Application) : AndroidViewModel(application) {
    private val config = ConfigStore(application)
    private val keys = SecureKeyStore(application)
    private val receipts = File(application.noBackupFilesDir, "project-update-receipts")
    private val last = AtomicFile(File(application.noBackupFilesDir, "last-project-update.json"))
    private val mutableItems = MutableStateFlow(UpdateProtocol.catalog())
    val items = mutableItems.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutablePlan = MutableStateFlow<UpdatePlan?>(null)
    val plan = mutablePlan.asStateFlow()
    private val mutableMessage = MutableStateFlow("检查会通过已信任的 SSH 查询服务器版本与官方 GitHub；不会安装更新。")
    val message = mutableMessage.asStateFlow()
    private val mutablePending = MutableStateFlow(true)
    val pending = mutablePending.asStateFlow()
    private var receipt: JSONObject? = null
    private var receiptBroken = false

    init {
        try {
            receipt = if (last.baseFile.exists() || File(last.baseFile.path + ".bak").exists()) JSONObject(last.readFully().toString(Charsets.UTF_8)) else null
            receipt?.let {
                check(Regex("[0-9a-f]{32}").matches(it.getString("request_id")))
                check(Regex("[0-9a-f]{64}").matches(it.getString("identity")))
                check(it.has("resolved"))
                mutableMessage.value = "已恢复任务 ${it.getString("request_id")}，可查询服务器结果。"
            }
            mutablePending.value = receipt?.getBoolean("resolved") == false
        } catch (_: Exception) {
            receiptBroken = true
            mutableMessage.value = "本机任务回执无法读取，已阻止新升级。请由维护端核对服务器任务后恢复回执，勿清除应用数据。"
        }
    }

    private fun now() = System.currentTimeMillis() / 1000
    private fun identity(connection: AppConfig) = connectionIdentity(connection, keys.revision)
    private fun snapshot(): AppConfig = config.current().also {
        check(keys.hasKey && it.hasTrustedHost) { "请先在设置中导入私钥并核对服务器指纹。" }
    }
    private fun unchanged(expected: String) {
        check(identity(config.current()) == expected) { "连接或私钥已变化；请恢复原连接后查询，或重新检查。" }
    }
    private suspend fun request(connection: AppConfig, expected: String, payload: JSONObject): JSONObject = runInterruptible(Dispatchers.IO) {
        unchanged(expected)
        JSONObject(SshEngine.projectUpdateRequest(connection, keys.read(), payload.toString()))
    }
    private fun action(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableMessage.value = (if (error is IllegalStateException) error.message else null)
                    ?: "连接或响应校验失败，请检查 SSH、网络及服务器组件；已提交任务请查询原编号。"
            } finally { mutableBusy.value = false }
        }
    }

    fun checkVersions() = action {
        mutablePlan.value = null
        val connection = snapshot()
        val expected = identity(connection)
        mutableItems.value = UpdateProtocol.catalog()
        mutableMessage.value = "正在读取服务器版本与官方发布信息……"
        var inventoryError = false
        val inventory = try {
            runInterruptible(Dispatchers.IO) {
                unchanged(expected)
                val script = getApplication<Application>().assets.open("update_probe.py").use { it.readBytes() }
                val encoded = Base64.encodeToString(script, Base64.NO_WRAP)
                JSONObject(SshEngine.runCommand(connection, keys.read(), "python3 -c \"import base64;exec(base64.b64decode('$encoded'))\""))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { inventoryError = true; JSONObject() }
        for ((index, project) in UpdateProtocol.catalog().withIndex()) {
            unchanged(expected)
            var item = project.copy(installed = inventory.optString(project.id).ifBlank { "读取失败/未安装" },
                notes = if (inventoryError) "服务器版本读取失败。\n" else "")
            if (item.id == "nginx") item = item.copy(notes = item.notes + "APT 已安装：${inventory.optString("nginx_package")}；本地索引候选：${inventory.optString("nginx_candidate")}。未刷新索引，不能据此判定没有安全更新。")
            item = try {
                val reply = request(connection, expected, JSONObject().put("action", "release").put("project", item.id))
                check(reply.optString("status") == "ok")
                UpdateProtocol.release(item, reply.get("data"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { item.copy(status = "上游查询失败或响应无法核验", notes = item.notes + "\n请检查网络、GitHub 限流与服务器更新组件，也可打开官方发布页。") }
            unchanged(expected)
            mutableItems.value = mutableItems.value.toMutableList().also { it[index] = item }
        }
        mutableMessage.value = "检查结束：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}。版本信息仅代表本次检查。"
    }

    fun prepare(project: String) = action {
        check(!mutablePending.value && !receiptBroken) { "请先查询并核对上一项升级结果。" }
        mutablePlan.value = null
        val connection = snapshot()
        val expected = identity(connection)
        mutableMessage.value = "正在检查服务、配置及目标版本，生成升级计划……"
        val inspected = request(connection, expected, JSONObject().put("action", "inspect").put("project", project))
        check(inspected.optString("status") == "ok") { UpdateProtocol.describe(inspected) }
        val reply = request(connection, expected, JSONObject().put("action", "plan").put("project", project))
        unchanged(expected)
        mutablePlan.value = UpdateProtocol.plan(reply, project, expected, now())
        mutableMessage.value = "计划已生成，确认后仅升级选中的项目。"
    }

    fun dismissPlan() { mutablePlan.value = null }

    private fun save(value: JSONObject) {
        receipts.mkdirs()
        fun write(file: AtomicFile) {
            val stream = file.startWrite()
            try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
        // The pointer is authoritative and must be durable before dispatch.
        write(last)
        write(AtomicFile(File(receipts, value.getString("request_id") + ".json")))
        receipt = value
    }

    fun confirm() {
        val selected = mutablePlan.value ?: return
        if (mutableBusy.value) return
        mutablePlan.value = null // consume this confirmation; repeated taps cannot dispatch it twice
        action {
            check(!mutablePending.value && !receiptBroken) { "上一项升级尚未核对。" }
            val connection = snapshot()
            UpdateProtocol.confirm(selected, identity(connection), now())
            val saved = JSONObject().put("request_id", selected.requestId).put("plan_hash", selected.hash)
                .put("project", selected.project).put("identity", selected.identity).put("resolved", false)
            mutablePending.value = true
            receipt = saved
            runInterruptible(Dispatchers.IO) { save(saved) }
            mutableMessage.value = "正在提交任务 ${selected.requestId}；断线后请查询原任务。"
            val reply = request(connection, selected.identity, JSONObject().put("action", "execute").put("plan", JSONObject(selected.raw)))
            // Explicit pre-submission refusals from the fixed helper have no receipt ID.
            if (reply.optString("status") == "refused" && !reply.has("request_id") && reply.optString("reason") in
                setOf("maintenance_busy", "plan_expired", "plan_or_baseline_changed", "major_version_requires_compatibility_review", "no_newer_stable_version", "no_upgrade_in_cached_apt_index")) {
                reply.put("request_id", selected.requestId)
            }
            acceptReply(reply, saved)
        }
    }

    private suspend fun acceptReply(reply: JSONObject, saved: JSONObject) {
        val id = saved.getString("request_id")
        check(!reply.has("request_id") || reply.optString("request_id") == id) { "响应任务编号不匹配，请查询原任务。" }
        check(!reply.has("plan_hash") || reply.optString("plan_hash") == saved.getString("plan_hash")) { "响应计划摘要不匹配，请由维护端核对原任务。" }
        val next = JSONObject(saved.toString()).put("resolved", UpdateProtocol.resolved(reply, id))
        runInterruptible(Dispatchers.IO) { save(next) }
        mutablePending.value = !next.getBoolean("resolved")
        mutableMessage.value = UpdateProtocol.describe(reply) + "\n任务/备份编号：$id"
    }

    fun query() = action {
        check(!receiptBroken) { "回执损坏，请由维护端恢复。" }
        val saved = receipt ?: error("尚无已提交的升级任务。")
        val connection = snapshot()
        val expected = saved.getString("identity")
        unchanged(expected)
        val reply = request(connection, expected, JSONObject().put("action", "result").put("request_id", saved.getString("request_id")))
        acceptReply(reply, saved)
    }
}
