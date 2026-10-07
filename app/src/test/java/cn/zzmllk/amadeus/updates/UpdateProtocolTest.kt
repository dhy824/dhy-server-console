package cn.zzmllk.amadeus.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private fun reply(project: String = "mihomo") = JSONObject().put("status", "planned").put("plan", JSONObject()
        .put("project", project).put("target", "1.20.0").put("request_id", "a".repeat(32))
        .put("plan_hash", "b".repeat(64)).put("expires", 1300L)
        .put("baseline", JSONObject().put("version", "1.19.0")))
    private fun refused(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun versionsUseNumericOrderingAndDoNotRecommendUnknownBuilds() {
        assertEquals("有上游新版本", UpdateProtocol.compare("1.9.0", "v1.10.0"))
        assertEquals("已安装版本领先上游", UpdateProtocol.compare("2.0.0", "1.99.9"))
        assertEquals("与上游版本一致", UpdateProtocol.compare("v1.2.3", "1.2.3"))
        for (version in listOf("dev", "1.2.3-beta.1", "99999999999.1.1", ""))
            assertEquals("版本需人工核对", UpdateProtocol.compare(version, "1.2.3"))
    }

    @Test fun astrbotBetaTagIsPreviewEvenIfGithubFlagIsFalse() {
        val release = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v4.29.0-beta.1").put("body", "notes")
        assertEquals("预览版本，仅供查看", UpdateProtocol.release(UpdateProtocol.catalog().last(), release).status)
        refused { UpdateProtocol.release(UpdateProtocol.catalog().first(), release) }
        release.put("draft", true)
        refused { UpdateProtocol.release(UpdateProtocol.catalog().last(), release) }
    }

    @Test fun nginxTagsAreOnlyReferenceAndSortedNumerically() {
        val tags = JSONArray("[{name:'release-1.9.1'},{name:'release-1.28.3'},{name:'feature-x'}]")
        val item = UpdateProtocol.release(UpdateProtocol.catalog()[1], tags)
        assertEquals("1.28.3", item.latest)
        assertEquals("上游标签仅供参考", item.status)
    }

    @Test fun plansPreserveServerFieldsAndBindConfirmation() {
        val reply = reply()
        reply.getJSONObject("plan").put("asset", JSONObject().put("sha256", "c".repeat(64)))
        val plan = UpdateProtocol.plan(reply, "mihomo", "identity", 1000)
        assertEquals("c".repeat(64), JSONObject(plan.raw).getJSONObject("asset").getString("sha256"))
        UpdateProtocol.confirm(plan, "identity", 1299)
        refused { UpdateProtocol.confirm(plan, "other-server", 1001) }
        refused { UpdateProtocol.confirm(plan, "identity", 1300) }
    }

    @Test fun invalidExpiredAndMismatchedPlansCannotBeConfirmed() {
        refused { UpdateProtocol.plan(reply(), "cliproxyapi", "identity", 1000) }
        refused { UpdateProtocol.plan(reply("astrbot"), "astrbot", "identity", 1000) }
        refused { UpdateProtocol.plan(reply(), "mihomo", "identity", 1300) }
        refused { UpdateProtocol.plan(reply(), "mihomo", "identity", 998) }
        val invalid = reply()
        invalid.getJSONObject("plan").put("request_id", "../other")
        refused { UpdateProtocol.plan(invalid, "mihomo", "identity", 1000) }
    }

    @Test fun unknownAndMismatchedReceiptsNeverUnlockAnotherUpgrade() {
        for (status in listOf("queued", "running", "not_found", "needs_attention", "unexpected"))
            assertFalse(UpdateProtocol.resolved(JSONObject().put("request_id", "id").put("status", status), "id"))
        for (status in listOf("succeeded", "rolled_back", "refused")) {
            assertTrue(UpdateProtocol.resolved(JSONObject().put("request_id", "id").put("status", status), "id"))
            assertFalse(UpdateProtocol.resolved(JSONObject().put("request_id", "other").put("status", status), "id"))
            assertFalse(UpdateProtocol.resolved(JSONObject().put("status", status), "id"))
        }
    }

    @Test fun cliMajorReleaseIsShownAsRequiringCompatibilityReview() {
        val release = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v8.1.0")
        val cli = UpdateProtocol.catalog()[2].copy(installed = "7.2.0")
        assertEquals("跨主版本，需兼容性评审", UpdateProtocol.release(cli, release).status)
        assertEquals("有上游新版本", UpdateProtocol.release(cli.copy(installed = "8.0.0"), release).status)
        assertEquals("版本需人工核对", UpdateProtocol.release(cli.copy(installed = "unknown"), release).status)
    }

    @Test fun downloadProgressAndRefusalDescribeOnlyVerifiedMutationState() {
        val reply = JSONObject().put("request_id", "id").put("status", "running")
            .put("phase", "download").put("downloaded_bytes", 5242880)
        assertTrue(UpdateProtocol.describe(reply).contains("已下载 5.0 MiB"))
        assertFalse(UpdateProtocol.resolved(reply, "id"))
        reply.put("status", "refused").put("reason", "download_deadline_exceeded")
        assertFalse(UpdateProtocol.describe(reply).contains("程序未改动"))
        assertFalse(UpdateProtocol.describe(reply).contains("已回退"))
        reply.put("production_change_started", false)
        assertTrue(UpdateProtocol.describe(reply).contains("程序未改动"))
        assertTrue(UpdateProtocol.resolved(reply, "id"))
        // A contradictory receipt must not unlock another production action.
        reply.put("production_change_started", true)
        assertFalse(UpdateProtocol.resolved(reply, "id"))
        assertTrue(UpdateProtocol.describe(reply).contains("回执状态不一致"))
        reply.put("status", "needs_attention").put("reason", "rollback_requires_attention")
        assertTrue(UpdateProtocol.describe(reply).contains("回退尚未通过验证"))
        assertFalse(UpdateProtocol.resolved(reply, "id"))
    }

    @Test fun malformedAndUnknownProgressDoesNotInventDownloadedSize() {
        val reply = JSONObject().put("status", "running").put("phase", "unrecognized").put("downloaded_bytes", 123)
        assertFalse(UpdateProtocol.describe(reply).contains("MiB"))
        reply.put("phase", "download").put("downloaded_bytes", -1)
        assertFalse(UpdateProtocol.describe(reply).contains("MiB"))
        reply.put("downloaded_bytes", "invalid")
        assertFalse(UpdateProtocol.describe(reply).contains("MiB"))
    }
}
