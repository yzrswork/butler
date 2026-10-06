package dev.syumai.butler

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Read-only contract for the published snapshot. The device never advances a commit baseline. */
internal data class DashboardSnapshot(
    val dataDate: String,
    val generatedAt: OffsetDateTime,
    val notes: Int?, val newNotes: Int?, val commits: Int?,
    val commitFrom: String?, val prNumbers: List<Int>, val prCount: Int?, val projects: Int?,
    val activity: List<ActivityItem>, val featured: Featured?, val active: List<ProjectItem>, val activeTotal: Int?,
    val ai: AiUsage? = null,
) {
    data class ActivityItem(val at: OffsetDateTime, val kind: String, val workId: String?, val title: String)
    data class Featured(val workId: String?, val title: String, val stage: String?, val action: String)
    data class ProjectItem(val workId: String, val title: String, val stage: String)
    data class UsageWindow(val remainingPercent: Double, val resetsAt: OffsetDateTime)
    data class CodexUsage(
        val plan: String, val status: String, val stale: Boolean, val todayTokens: Long,
        val session: UsageWindow, val weekly: UsageWindow,
    )
    data class AiUsage(val source: String, val updatedAt: OffsetDateTime, val codex: CodexUsage)

    companion object {
        private fun JSONObject.requiredObject(key: String): JSONObject = getJSONObject(key)
        private fun JSONObject.count(key: String): Int? {
            require(has(key)) { "Missing $key" }
            return if (isNull(key)) null else getInt(key).also { require(it >= 0) }
        }
        private fun JSONObject.optionalString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
        private fun String.limited(max: Int): String = also { require(codePointCount(0, length) <= max) }

        private fun parseAi(root: JSONObject): AiUsage? = runCatching {
            val ai = root.optJSONObject("ai") ?: return null
            val source = ai.getString("source").also { require(it == "token-monitor") }
            val updatedAt = OffsetDateTime.parse(ai.getString("updatedAt"))
            val codex = ai.getJSONObject("codex")
            val plan = codex.getString("plan").trim().also { require(it.isNotEmpty()); it.limited(64) }
            val status = codex.getString("status").trim().also { require(it.isNotEmpty()); it.limited(24) }
            val stale = codex.get("stale") as? Boolean ?: error("Invalid AI stale value")
            val rawTokens = codex.get("todayTokens") as? Number ?: error("Invalid AI todayTokens")
            val tokensValue = rawTokens.toDouble()
            require(tokensValue.isFinite() && tokensValue >= 0 && tokensValue <= 9_007_199_254_740_991.0)
            require(tokensValue == kotlin.math.floor(tokensValue))
            val todayTokens = rawTokens.toLong()
            fun window(key: String): UsageWindow {
                val value = codex.getJSONObject(key)
                val rawPercent = value.get("remainingPercent") as? Number ?: error("Invalid AI $key percent")
                val percent = rawPercent.toDouble()
                require(percent.isFinite() && percent in 0.0..100.0)
                return UsageWindow(percent, OffsetDateTime.parse(value.getString("resetsAt")))
            }
            AiUsage(source, updatedAt, CodexUsage(plan, status, stale, todayTokens, window("session"), window("weekly")))
        }.getOrNull()

        fun parse(raw: String, now: OffsetDateTime = OffsetDateTime.now()): DashboardSnapshot {
            require(raw.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
            val root = JSONObject(raw)
            require(root.getInt("schema_version") == 1)
            val date = root.getString("data_date").also { require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) }
            val at = OffsetDateTime.parse(root.getString("generated_at"))
            require(!at.toInstant().isAfter(now.toInstant().plusSeconds(300)))
            require(at.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDate().toString() == date)
            val source = root.requiredObject("source")
            val sha = source.getString("main_sha")
            require(sha.matches(Regex("[0-9a-f]{40}")))
            val window = source.requiredObject("commit_window")
            require(window.getString("to_sha") == sha)
            val from = window.optionalString("from_sha")
            require(from == null || from.matches(Regex("[0-9a-f]{40}")))
            val fromAt = window.optionalString("from_generated_at")
            fromAt?.let { OffsetDateTime.parse(it) }
            val today = root.requiredObject("today")
            require(today.has("new_notes") && today.has("commits_since_snapshot") && today.has("projects") && today.has("notes_total"))
            require(from != null || today.isNull("commits_since_snapshot"))
            val prs = today.requiredObject("open_prs")
            val numbersJson = prs.getJSONArray("numbers")
            require(numbersJson.length() <= 500)
            val numbers = (0 until numbersJson.length()).map { numbersJson.getInt(it).also { n -> require(n > 0) } }
            require(numbers.distinct().size == numbers.size)
            val prCount = prs.count("count")
            require(prCount == null || prCount == numbers.size)
            val activityJson = root.getJSONArray("activity")
            require(activityJson.length() <= 20)
            val activity = (0 until activityJson.length()).map { i ->
                val item = activityJson.getJSONObject(i)
                val kind = item.getString("kind")
                require(kind in setOf("pr_merged", "work_note_updated", "curated"))
                ActivityItem(OffsetDateTime.parse(item.getString("occurred_at")), kind,
                    item.optionalString("work_id")?.limited(20), item.getString("title").limited(48))
            }
            val next = root.requiredObject("next")
            val featured = next.optJSONObject("featured")?.let {
                Featured(it.optionalString("work_id")?.limited(20),
                    it.getString("title").limited(48), it.optionalString("stage")?.limited(24),
                    it.getString("action").limited(80))
            }
            val activeJson = next.getJSONArray("active")
            require(activeJson.length() <= 4)
            val active = (0 until activeJson.length()).map { i ->
                val item = activeJson.getJSONObject(i)
                ProjectItem(item.getString("work_id").limited(20), item.getString("title").limited(48),
                    item.getString("stage").limited(24))
            }
            val activeTotal = next.count("active_total")
            require(activeTotal == null || activeTotal >= active.size)
            return DashboardSnapshot(date, at, today.count("notes_total"), today.count("new_notes"),
                today.count("commits_since_snapshot"), fromAt, numbers.sorted(), prCount,
                today.count("projects"), activity, featured, active, activeTotal, parseAi(root))
        }
    }
}

/** One last-known-good file; a bad response never replaces it. Fetches run off the UI thread. */
internal class DashboardStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "dashboard-snapshot.json"))
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false)
        .retryOnConnectionFailure(false).build()

    fun cancel() { http.dispatcher.cancelAll() }

    fun requestRefresh(settings: Settings) {
        val request = dashboardRefreshRequest(settings.get("dashboardUrl"), settings.secret("dashboardRefreshToken"))
        http.newCall(request).execute().use { response ->
            Log.i("ButlerDashboard", "Refresh dispatch HTTP ${response.code}")
            check(response.isSuccessful) { "Dashboard refresh HTTP ${response.code}" }
            val source = (response.body ?: error("Empty refresh response")).source()
            require(!source.request(8 * 1024L + 1)) { "Refresh response too large" }
            validateDashboardRefreshResponse(source.readUtf8())
        }
    }

    fun cached(): DashboardSnapshot? = runCatching {
        DashboardSnapshot.parse(file.openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    fun fetch(settings: Settings): DashboardSnapshot {
        val url = settings.get("dashboardUrl").trim()
        require(url.startsWith("https://")) { "Dashboard HTTPS URL is not configured" }
        val token = settings.secret("dashboardReadToken")
        require(token.isNotBlank()) { "Dashboard read token is not configured" }
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        val raw = http.newCall(request).execute().use { response ->
            if (BuildConfig.DEBUG && response.code == 401) {
                // Record only the input form; never log the token, its length, or the request header.
                val tokenForm = when {
                    token.startsWith("Bearer ", ignoreCase = true) -> "bearer-prefix"
                    token.startsWith("Authorization:", ignoreCase = true) -> "header-prefix"
                    token.startsWith("<") && token.endsWith(">") -> "angle-brackets"
                    token.startsWith('"') && token.endsWith('"') -> "quotes"
                    token.any(Char::isWhitespace) -> "whitespace"
                    token.any { it.code > 127 } -> "non-ascii"
                    else -> "plain"
                }
                Log.w("ButlerDashboard", "HTTP 401 token form: $tokenForm")
            }
            check(response.isSuccessful) { "Dashboard HTTP ${response.code}" }
            val body = response.body ?: error("Empty dashboard response")
            val source = body.source()
            require(!source.request(128 * 1024L + 1)) { "Dashboard response too large" }
            source.readUtf8()
        }
        val parsed = try { DashboardSnapshot.parse(raw) }
            catch (e: Exception) { throw IllegalArgumentException("Dashboard schema invalid", e) }
        cached()?.let { previous ->
            if (parsed.generatedAt.toInstant().isBefore(previous.generatedAt.toInstant())) return previous
        }
        val stream = file.startWrite()
        try { stream.write(raw.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        return parsed
    }
}

/** Derive the refresh endpoint from the configured read endpoint on the same HTTPS origin. */
internal fun dashboardRefreshRequest(readUrl: String, token: String): Request {
    val url = readUrl.trim().toHttpUrl()
    require(url.isHttps && url.encodedPath.trimEnd('/') == "/dashboard")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    require(token.isNotBlank()) { "Dashboard refresh token is not configured" }
    val refreshUrl = url.newBuilder().encodedPath("/dashboard/refresh").build()
    return Request.Builder().url(refreshUrl).header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .post("{}".toRequestBody("application/json".toMediaType())).build()
}

internal fun validateDashboardRefreshResponse(raw: String) {
    val response = JSONObject(raw)
    require(response.getBoolean("ok") && response.getString("status") == "dispatch_requested")
}
