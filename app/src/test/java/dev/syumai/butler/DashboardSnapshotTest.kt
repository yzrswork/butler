package dev.syumai.butler

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.OffsetDateTime

class DashboardSnapshotTest {
    private val sha0 = "0".repeat(40)
    private val sha1 = "1".repeat(40)
    private fun payload(): JSONObject = JSONObject("""
        {
          "schema_version": 1, "data_date": "2026-09-27", "generated_at": "2026-09-27T23:55:00+09:00",
          "source": {"repository": "yzrswork/obsidian-vault", "main_sha": "$sha1",
            "commit_window": {"from_sha": "$sha0", "from_generated_at": "2026-09-26T23:55:00+09:00", "to_sha": "$sha1"}},
          "today": {"notes_total": 1284, "new_notes": 8, "commits_since_snapshot": 6,
            "open_prs": {"count": 3, "numbers": [476, 455, 470]}, "projects": 10},
          "activity": [{"id": "pr-476", "occurred_at": "2026-09-27T12:06:00+09:00", "kind": "pr_merged", "title": "PR #476 merged"}],
          "next": {"featured": {"work_id": "HW-12", "title": "Dashboard", "stage": "design", "action": "Check layout"},
            "active": [{"work_id": "HW-12", "title": "Dashboard", "stage": "design"}], "active_total": 10}
        }
    """.trimIndent())
    private val now = OffsetDateTime.parse("2026-09-27T23:56:00+09:00")

    @Test fun readsSnapshotWindowAndSortsPrNumbers() {
        val s = DashboardSnapshot.parse(payload().toString(), now)
        assertEquals(6, s.commits)
        assertEquals("2026-09-26T23:55:00+09:00", s.commitFrom)
        assertEquals(listOf(455, 470, 476), s.prNumbers)
        assertEquals(10, s.projects)
    }

    @Test fun parsesOptionalAiUsageAndLongTokenCounts() {
        val p = payload().put("ai", JSONObject("""
            {
              "source":"token-monitor", "updatedAt":"2026-09-27T23:55:30+09:00",
              "codex": {
                "plan":"Plus", "status":"ok", "stale":false, "todayTokens":1234567,
                "session":{"remainingPercent":42.5,"resetsAt":"2026-09-28T03:00:00+09:00"},
                "weekly":{"remainingPercent":81,"resetsAt":"2026-10-02T12:00:00+09:00"}
              }
            }
        """.trimIndent()))
        val ai = DashboardSnapshot.parse(p.toString(), now).ai!!
        assertEquals("Plus", ai.codex.plan)
        assertEquals(1_234_567L, ai.codex.todayTokens)
        assertEquals(42.5, ai.codex.session.remainingPercent, 0.0)
        assertEquals(OffsetDateTime.parse("2026-09-28T03:00:00+09:00"), ai.codex.session.resetsAt)
    }

    @Test fun malformedOptionalAiNeverBreaksBaseSnapshot() {
        val p = payload().put("ai", JSONObject("""
            {"source":"token-monitor","updatedAt":"not-a-date","codex":{}}
        """.trimIndent()))
        val parsed = DashboardSnapshot.parse(p.toString(), now)
        assertEquals(1284, parsed.notes)
        assertEquals(6, parsed.commits)
        assertNull(parsed.ai)
    }

    @Test fun initialScheduledSnapshotHasNoInventedCommits() {
        val p = payload()
        p.getJSONObject("source").getJSONObject("commit_window")
            .put("from_sha", JSONObject.NULL).put("from_generated_at", JSONObject.NULL)
        p.getJSONObject("today").put("commits_since_snapshot", JSONObject.NULL)
        assertNull(DashboardSnapshot.parse(p.toString(), now).commits)
        p.getJSONObject("today").put("commits_since_snapshot", 0)
        assertThrows(IllegalArgumentException::class.java) { DashboardSnapshot.parse(p.toString(), now) }
    }

    @Test fun optionalJsonNullsDoNotBecomeText() {
        val p = payload()
        p.getJSONArray("activity").getJSONObject(0).put("work_id", JSONObject.NULL)
        p.getJSONObject("next").getJSONObject("featured")
            .put("work_id", JSONObject.NULL).put("stage", JSONObject.NULL)
        val s = DashboardSnapshot.parse(p.toString(), now)
        assertNull(s.activity[0].workId)
        assertNull(s.featured?.workId)
        assertNull(s.featured?.stage)
    }

    @Test fun readsPrivateDailyDigestAsCuratedActivity() {
        val p = payload()
        p.put("activity", org.json.JSONArray("""[
            {"id":"daily-2026-09-27-1","occurred_at":"2026-09-27T23:55:00+09:00",
             "kind":"curated","work_id":"HW-16","title":"音声入力の実機確認を完了"},
            {"id":"daily-2026-09-27-2","occurred_at":"2026-09-27T23:55:00+09:00",
             "kind":"curated","work_id":"SY-01","title":"表示を修正"}
        ]"""))
        val s = DashboardSnapshot.parse(p.toString(), now)
        assertEquals(2, s.activity.size)
        assertEquals("curated", s.activity[0].kind)
        assertEquals("音声入力の実機確認を完了", s.activity[0].title)
        assertEquals(6, s.commits)
        assertEquals("Check layout", s.featured?.action)
    }

    @Test fun refreshRequestUsesSameOriginAndDedicatedBearerOnly() {
        val request = dashboardRefreshRequest("https://example.com/dashboard", "refresh-only")
        assertEquals("https://example.com/dashboard/refresh", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer refresh-only", request.header("Authorization"))
        val body = okio.Buffer()
        request.body!!.writeTo(body)
        assertEquals("{}", body.readUtf8())
        validateDashboardRefreshResponse("""{"ok":true,"status":"dispatch_requested"}""")
        assertThrows(IllegalArgumentException::class.java) { dashboardRefreshRequest("http://example.com/dashboard", "token") }
        assertThrows(IllegalArgumentException::class.java) { dashboardRefreshRequest("https://example.com/dashboard?next=other", "token") }
        assertThrows(IllegalArgumentException::class.java) { dashboardRefreshRequest("https://example.com/dashboard", "") }
        assertThrows(IllegalArgumentException::class.java) { validateDashboardRefreshResponse("""{"ok":false,"status":"failed"}""") }
    }

    @Test fun rejectsInconsistentOrFuturePayload() {
        val p = payload()
        p.getJSONObject("today").getJSONObject("open_prs").put("count", 4)
        assertThrows(IllegalArgumentException::class.java) { DashboardSnapshot.parse(p.toString(), now) }
        val valid = payload().toString()
        assertThrows(IllegalArgumentException::class.java) {
            DashboardSnapshot.parse(valid, OffsetDateTime.parse("2026-09-27T23:00:00+09:00"))
        }
    }
}
