package cn.zzmllk.amadeus.updates

import org.json.JSONArray
import org.json.JSONObject

data class ProjectVersion(
    val id: String, val name: String, val repository: String,
    val installed: String = "未检查", val latest: String = "未检查",
    val status: String = "未检查", val notes: String = ""
) {
    val url get() = "https://github.com/$repository/" + if (id == "nginx") "tags" else "releases"
}

data class UpdatePlan(val project: String, val target: String, val installed: String,
    val requestId: String, val hash: String, val expires: Long, val raw: String, val identity: String)

object UpdateProtocol {
    fun catalog() = listOf(
        ProjectVersion("mihomo", "Mihomo", "MetaCubeX/mihomo"),
        ProjectVersion("nginx", "Nginx", "nginx/nginx"),
        ProjectVersion("cliproxyapi", "CLIProxyAPI", "router-for-me/CLIProxyAPI"),
        ProjectVersion("astrbot", "AstrBot", "AstrBotDevs/AstrBot")
    )

    private fun version(text: String): List<Int>? {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(text) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }

    fun compare(installed: String, latest: String): String {
        val left = version(installed) ?: return "版本需人工核对"
        val right = version(latest) ?: return "版本需人工核对"
        for (index in left.indices) {
            if (left[index] < right[index]) return "有上游新版本"
            if (left[index] > right[index]) return "已安装版本领先上游"
        }
        return "与上游版本一致"
    }

    fun release(item: ProjectVersion, data: Any): ProjectVersion {
        if (item.id == "nginx") {
            val tags = data as JSONArray
            val highest = (0 until tags.length()).mapNotNull {
                val name = tags.getJSONObject(it).optString("name")
                if (name.startsWith("release-")) version(name.removePrefix("release-")) else null
            }.maxWithOrNull(compareBy<List<Int>> { it[0] }.thenBy { it[1] }.thenBy { it[2] })
                ?: error("上游标签无法核验。")
            return item.copy(latest = highest.joinToString("."), status = "上游标签仅供参考",
                notes = item.notes + "\n官方最近 100 个标签的最高版本，可能是 mainline；实际升级沿用 APT 软件源。")
        }
        val release = data as JSONObject
        check(!release.getBoolean("draft")) { "上游发布尚为草稿。" }
        val tag = release.getString("tag_name")
        val preview = release.getBoolean("prerelease") || tag.contains('-')
        check(version(tag) != null || (item.id == "astrbot" && Regex("^v?\\d+\\.\\d+\\.\\d+-[A-Za-z0-9.-]+$").matches(tag))) {
            "上游版本格式无法核验。"
        }
        check(!preview || item.id == "astrbot") { "上游没有可核验的稳定版本。" }
        return item.copy(latest = tag, status = if (preview) "预览版本，仅供查看" else compare(item.installed, tag),
            notes = item.notes + "\n" + release.optString("body").take(16000))
    }

    fun plan(reply: JSONObject, project: String, identity: String, now: Long): UpdatePlan {
        check(project in setOf("mihomo", "nginx", "cliproxyapi")) { "此项目仅支持查看。" }
        check(reply.optString("status") == "planned") { describe(reply) }
        val plan = reply.getJSONObject("plan")
        check(plan.getString("project") == project) { "计划项目不匹配。" }
        val id = plan.getString("request_id")
        val hash = plan.getString("plan_hash")
        check(Regex("[0-9a-f]{32}").matches(id) && Regex("[0-9a-f]{64}").matches(hash)) { "计划标识无效。" }
        val expires = plan.getLong("expires")
        check(expires > now && expires <= now + 301) { "计划已过期或手机时间不准确。" }
        val target = plan.getString("target")
        check(Regex("[A-Za-z0-9.+:~_-]{1,100}").matches(target)) { "计划目标无效。" }
        val baseline = plan.getJSONObject("baseline")
        val installed = if (project == "nginx") baseline.getJSONObject("packages").getString("nginx") else baseline.getString("version")
        return UpdatePlan(project, target, installed, id, hash, expires, plan.toString(), identity)
    }

    fun confirm(plan: UpdatePlan, identity: String, now: Long) {
        check(plan.identity == identity) { "连接、主机信任或私钥已变化，请重新准备计划。" }
        check(now < plan.expires) { "计划已过期，请重新准备。" }
    }

    // Only a matching terminal receipt permits another submission. Unknown outcomes stay blocked.
    fun resolved(reply: JSONObject, requestId: String): Boolean =
        reply.optString("request_id") == requestId && reply.optString("status") in setOf("succeeded", "rolled_back", "refused")

    fun impact(project: String): String = when (project) {
        "mihomo" -> "会短暂重启代理服务。校验官方发行包 SHA-256，备份程序与配置，测试候选配置及代理连通性；失败尝试恢复旧程序。"
        "nginx" -> "会短暂重启网站/API 入口。沿用当前 APT 索引和软件源，先保存原版本包与配置，再安装、执行 nginx -t 和 HTTP 检查；失败尝试恢复原包。索引未刷新不代表没有安全更新。"
        else -> "会短暂重启 API 服务。校验官方包 SHA-256，备份程序、配置及服务定义，仅替换程序；校验 API 与 Nginx 入口，失败尝试恢复旧程序。OAuth 数据原位保留；跨主版本需要单独兼容性评审。"
    }

    fun describe(reply: JSONObject): String {
        val label = when (reply.optString("status")) {
            "queued" -> "已提交，稍后查询结果"
            "running" -> "正在执行，稍后查询结果"
            "succeeded" -> "升级成功"
            "rolled_back" -> "升级失败，已回退"
            "needs_attention" -> "需要维护检查，请勿重复提交"
            "refused" -> "未执行：条件不满足"
            "not_found" -> "服务器未找到任务。请继续用原编号查询；不要重复升级。"
            else -> "未知结果，请核对原任务"
        }
        val reason = when (reply.optString("reason")) {
            "major_version_requires_compatibility_review" -> "CLIProxyAPI 跨主版本，需先评审兼容性及迁移方案。"
            "no_upgrade_in_cached_apt_index" -> "当前 APT 索引没有更高版本；索引尚未刷新。"
            "no_newer_stable_version" -> "当前没有更新的稳定版本。"
            "maintenance_busy" -> "已有维护任务正在执行。"
            "plan_expired" -> "计划已过期，请重新准备。"
            "plan_or_baseline_changed", "baseline_changed" -> "目标版本或服务器状态已变化，请重新准备。"
            "official_asset_digest_required" -> "发行包缺少官方 SHA-256，需要人工核验。"
            "dependency_change_requires_manual_review" -> "涉及额外依赖变化，需要单独审阅。"
            "" -> ""
            else -> "服务器检查未通过，请由维护端核对。"
        }
        return listOf(label, reason).filter { it.isNotEmpty() }.joinToString("\n")
    }
}
