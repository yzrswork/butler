package dev.syumai.butler

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Fixed 960×480 physical-pixel design, scaled only if the actual immersive content bounds differ. */
internal class DashboardView(context: Context, private val settings: Settings) : FrameLayout(context) {
    private object C {
        const val WHITE = Color.WHITE
        const val TEXT = 0xFFE8EEF5.toInt()
        const val SECONDARY = 0xFFBBC8D7.toInt()
        const val MUTED = 0xFF9CABBD.toInt()
        const val BLUE = 0xFF65A4FF.toInt()
        const val GREEN = 0xFF69C9B8.toInt()
        const val AMBER = 0xFFD3AD76.toInt()
        const val ERROR = 0xFFDB9E9E.toInt()
    }
    private val design = FrameLayout(context)
    private val right = FrameLayout(context)
    private val nav = mutableListOf<FrameLayout>()
    private val values = mutableListOf<TextView>()
    private lateinit var notesExtra: TextView
    private lateinit var commitsExtra: TextView
    private lateinit var prsExtra: TextView
    private lateinit var syncValue: TextView
    private lateinit var syncExtra: TextView
    private lateinit var syncDot: TextView
    private lateinit var headerExtra: TextView
    private var snapshot: DashboardSnapshot? = null
    private var fetchFailed = false
    private var fetching = false
    private var weather: WeatherBrief? = null
    private var mode = 0
    var onRefresh: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    private lateinit var voiceStatus: TextView
    private val jst = ZoneId.of("Asia/Tokyo")

    init {
        setBackgroundColor(0xFF08131F.toInt())
        addView(design, LayoutParams(960, 480))
        val bg = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val custom = settings.background
            if (custom.exists()) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(custom.path, opts)
                opts.inSampleSize = if (opts.outWidth > 1920 || opts.outHeight > 960) 2 else 1
                val bitmap = BitmapFactory.decodeFile(custom.path, opts)
                if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.dashboard_landscape)
            } else {
                setImageResource(R.drawable.dashboard_landscape)
            }
        }
        design.addView(bg, LayoutParams(960, 480))
        design.addView(View(context).apply { setBackgroundColor(0x73040D19) }, LayoutParams(960, 480))
        design.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x12040B14, 0x08040B14, 0xA8030A13.toInt()))
        }, LayoutParams(960, 480))
        val panel = FrameLayout(context).apply { background = panelBackground() }
        design.place(panel, 28, 28, 304, 414)
        design.place(label("TODAY", 16, C.SECONDARY, true).apply { letterSpacing = 0.10f }, 50, 47, 117, 25)
        headerExtra = label("", 12, C.MUTED)
        design.place(headerExtra, 177, 50, 130, 20)

        val labels = listOf("Notes", "Commits", "Open PRs", "Projects", "Last Sync")
        val icons = listOf("▤", "⌁", "#", "□", "↻")
        for (i in 0..4) {
            val y = 80 + 69 * i
            val icon = label(icons[i], 22, C.SECONDARY, false, Gravity.CENTER).apply {
                background = shape(11f, 0x741D2A3A, 0)
            }
            design.place(icon, 51, y + 7, 36, 36)
            design.place(label(labels[i], 16, C.SECONDARY), 102, y + 3, 205, 23)
            val value = label("—", 29, C.WHITE, true)
            value.fontFeatureSettings = "tnum"
            values.add(value)
            design.place(value, 102, y + 27, 125, 36)
            if (i < 4) design.place(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, 102, 147 + 69 * i, 208, 1)
        }
        notesExtra = label("", 14, C.SECONDARY)
        commitsExtra = label("", 13, C.MUTED)
        prsExtra = label("", 13, C.SECONDARY)
        design.place(notesExtra, 184, 112, 126, 25)
        design.place(commitsExtra, 184, 181, 126, 24)
        design.place(prsExtra, 184, 250, 126, 23)
        syncValue = values[4]
        syncValue.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f)
        syncDot = label("●", 16, C.MUTED)
        syncExtra = label("No snapshot", 13, C.MUTED)
        design.place(syncDot, 184, 386, 20, 23)
        design.place(syncExtra, 204, 387, 106, 24)
        val syncHit = View(context).apply {
            isClickable = true; isFocusable = true
            contentDescription = "Refresh dashboard display"
            setOnClickListener { if (!fetching) onRefresh?.invoke() }
            background = context.borderlessRippleBackground()
        }
        design.place(syncHit, 48, 354, 264, 69)

        design.place(right, 356, 28, 576, 334)
        val gear = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            background = context.borderlessRippleBackground()
            contentDescription = context.getString(R.string.settings_button_description)
            isClickable = true; isFocusable = true
            setOnClickListener { onSettings?.invoke() }
        }
        design.place(gear, 868, 28, 64, 64)

        voiceStatus = label("IDLE", 13, C.TEXT, true, Gravity.CENTER).apply {
            contentDescription = "Voice input status: IDLE"
            background = shape(12f, 0xCB101B29.toInt(), C.BLUE)
        }
        design.place(voiceStatus, 738, 34, 112, 52)
        listOf("CLOCK", "TODAY", "NEXT", "AI").forEachIndexed { i, title ->
            val holder = FrameLayout(context).apply {
                isClickable = true; isFocusable = true
                contentDescription = "$title mode"
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    shape(17f, C.WHITE, 0))
                setOnClickListener { setMode(i) }
            }
            val face = label(title, 16, C.TEXT, true, Gravity.CENTER)
            face.letterSpacing = 0.04f
            holder.place(face, 0, 20, 128, 48)
            nav.add(holder)
            design.place(holder, 372 + 140 * i, 370, 128, 82)
        }
        renderPanel()
        setMode(0, false)
    }

    private fun panelBackground() = shape(17f, 0xDB0A1523.toInt(), 0x1AFFFFFF)
    private fun shape(radius: Float, fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        if (stroke != 0) setStroke(1, stroke)
    }
    private fun label(value: String, size: Int, color: Int, medium: Boolean = false, gravity: Int = Gravity.CENTER_VERTICAL) =
        TextView(context).apply {
            text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            this.gravity = gravity; includeFontPadding = false
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
    private fun FrameLayout.place(view: View, x: Int, y: Int, w: Int, h: Int) {
        addView(view, LayoutParams(w, h).apply { leftMargin = x; topMargin = y })
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val scale = minOf(w / 960f, h / 480f)
        design.pivotX = 0f; design.pivotY = 0f
        design.scaleX = scale; design.scaleY = scale
        design.translationX = (w - 960 * scale) / 2f
        design.translationY = (h - 480 * scale) / 2f
    }
    fun updateClock(now: Date = Date()) {
        if (mode == 0) {
            clock?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date?.text = SimpleDateFormat(if (Locale.getDefault().language == "ja") "M月d日（E）" else "EEE, MMM d", Locale.getDefault()).format(now)
        }
        renderSync()
    }
    private var clock: TextView? = null
    private var date: TextView? = null
    fun setWeather(brief: WeatherBrief?) { weather = brief; if (mode == 0) renderMode(false) }
    fun setSnapshot(value: DashboardSnapshot?, failed: Boolean = false, loading: Boolean = false) {
        snapshot = value; fetchFailed = failed; fetching = loading
        renderPanel(); renderMode(false)
    }
    private fun compact(n: Int?): String = when {
        n == null -> "—"
        n > 9_999_999 -> "9.9M+"
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        else -> String.format(Locale.US, "%,d", n)
    }
    private fun compactTokens(n: Long?): String = when {
        n == null -> "—"
        n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> String.format(Locale.US, "%,d", n)
    }
    private fun resetTime(at: OffsetDateTime?): String {
        if (at == null) return "—"
        val local = at.atZoneSameInstant(jst)
        val pattern = if (local.toLocalDate() == java.time.LocalDate.now(jst)) "HH:mm" else "M/d HH:mm"
        return local.format(DateTimeFormatter.ofPattern(pattern))
    }
    private fun prList(numbers: List<Int>): String {
        val paint = prsExtra.paint
        for (shown in minOf(3, numbers.size) downTo 0) {
            val rest = numbers.size - shown
            val text = numbers.take(shown).joinToString(" ") { "#$it" } + (if (rest > 0) " +$rest" else "")
            if (paint.measureText(text.trim()) <= 126) return text.trim()
        }
        return "+${numbers.size}"
    }
    private fun renderPanel() {
        val s = snapshot
        headerExtra.text = s?.dataDate?.takeIf { it != OffsetDateTime.now(jst).toLocalDate().toString() }
            ?.let { runCatching { java.time.LocalDate.parse(it).format(DateTimeFormatter.ofPattern("M/d")) + " SNAPSHOT" }.getOrDefault("") } ?: ""
        values[0].text = compact(s?.notes)
        values[1].text = compact(s?.commits)
        values[2].text = compact(s?.prCount)
        values[3].text = compact(s?.projects)
        for (i in 0..3) values[i].setTextSize(TypedValue.COMPLEX_UNIT_PX, if (values[i].text.length > 5) 24f else 29f)
        notesExtra.text = s?.newNotes?.let { "+$it today" } ?: ""
        commitsExtra.text = s?.commitFrom?.let { "since " + runCatching { OffsetDateTime.parse(it).atZoneSameInstant(jst).format(DateTimeFormatter.ofPattern("M/d HH:mm")) }.getOrDefault("previous") } ?: ""
        prsExtra.text = s?.prNumbers?.let { prList(it) } ?: ""
        renderSync()
    }
    private fun renderSync() {
        val s = snapshot
        if (s == null) {
            syncValue.text = "—"; syncDot.setTextColor(C.MUTED)
            syncExtra.text = if (fetching) "Fetching" else if (fetchFailed) "Offline" else "No snapshot"
            return
        }
        syncValue.text = s.generatedAt.atZoneSameInstant(jst).format(DateTimeFormatter.ofPattern("HH:mm"))
        val hours = Duration.between(s.generatedAt.toInstant(), java.time.Instant.now()).toHours().coerceAtLeast(0)
        val color = if (fetchFailed) C.ERROR else if (hours < 24) C.GREEN else C.AMBER
        syncDot.setTextColor(color)
        syncExtra.text = when {
            fetching -> "Fetching"
            fetchFailed -> "Offline"
            hours >= 48 -> "Stale"
            hours >= 24 -> "${hours}h old"
            else -> "Synced"
        }
    }
    private fun setMode(index: Int, animate: Boolean = true) {
        if (index == mode && right.childCount > 0) return
        mode = index
        nav.forEachIndexed { i, holder ->
            holder.isSelected = i == mode
            val face = holder.getChildAt(0) as TextView
            face.setTextColor(if (i == mode) C.WHITE else C.SECONDARY)
            face.background = if (i == mode) shape(17f, 0xCB183555.toInt(), C.BLUE) else shape(17f, 0xCB101B29.toInt(), 0x24FFFFFF)
        }
        renderMode(animate)
    }
    fun returnToClock() { setMode(0) }
    fun setVoiceState(state: String) {
        voiceStatus.text = state
        voiceStatus.contentDescription = "Voice input status: $state"
        voiceStatus.background = shape(12f, 0xCB101B29.toInt(), when (state) {
            "LISTENING" -> C.GREEN
            "TRANSCRIBING" -> C.AMBER
            else -> C.BLUE
        })
    }
    private fun renderMode(animate: Boolean) {
        right.removeAllViews(); clock = null; date = null
        when (mode) { 0 -> renderClock(); 1 -> renderToday(); 2 -> renderNext(); 3 -> renderAi(); else -> renderClock() }
        if (animate) { right.alpha = 0f; right.animate().alpha(1f).setDuration(150).start() }
    }
    private fun renderClock() {
        clock = label("", 142, C.WHITE, false, Gravity.CENTER).apply { fontFeatureSettings = "tnum" }
        date = label("", 28, C.SECONDARY, false, Gravity.CENTER)
        right.place(clock!!, 47, 49, 480, 151)
        right.place(date!!, 81, 202, 412, 42)
        val w = weather
        if (w == null) {
            right.place(label("Weather unavailable", 15, C.MUTED, false, Gravity.CENTER), 87, 259, 400, 54)
        } else {
            right.place(label(w.icon, 31, C.WHITE, false, Gravity.CENTER), 87, 259, 52, 54)
            right.place(label("${w.temperature}°", 31, C.WHITE), 150, 259, 85, 54)
            right.place(View(context).apply { setBackgroundColor(0x44FFFFFF) }, 246, 267, 1, 38)
            right.place(label(w.condition, 17, C.TEXT), 266, 260, 220, 26)
            right.place(label("H ${w.high}° / L ${w.low}°", 14, C.SECONDARY), 266, 286, 220, 23)
        }
        updateClock()
    }
    private fun modeTitle(title: String) {
        right.place(label(title, 22, C.WHITE, true), 28, 36, 300, 34)
        val s = snapshot
        val stamp = s?.generatedAt?.atZoneSameInstant(jst)?.format(DateTimeFormatter.ofPattern("M/d HH:mm")) ?: ""
        right.place(label(stamp, 13, C.MUTED, false, Gravity.CENTER_VERTICAL or Gravity.RIGHT), 280, 40, 100, 28)
    }
    private fun renderToday() {
        modeTitle("TODAY")
        val items = snapshot?.activity.orEmpty()
        if (items.isEmpty()) {
            right.place(label("今日の作業記録はまだありません", 18, C.SECONDARY), 28, 138, 520, 43)
            return
        }
        if (items.all { it.kind == "curated" }) {
            items.take(4).forEachIndexed { i, item ->
                val y = 80 + 55 * i
                right.place(label(item.workId ?: "WORK", 14, C.BLUE, true), 28, y, 85, 48)
                right.place(label(item.title, 17, C.TEXT), 118, y, 430, 48)
                if (i < minOf(items.size, 4) - 1) right.place(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, 28, y + 51, 520, 1)
            }
            return
        }
        val recent = items.sortedByDescending { it.at.toInstant() }
        recent.take(4).forEachIndexed { i, item ->
            val y = 80 + 55 * i
            val time = item.at.atZoneSameInstant(jst).format(DateTimeFormatter.ofPattern("HH:mm"))
            right.place(label(time, 17, C.SECONDARY), 28, y, 82, 48)
            right.place(label(item.workId ?: if (item.kind == "pr_merged") "PR" else "UPDATE", 14, C.BLUE, true), 111, y, 85, 48)
            right.place(label(item.title, 17, C.TEXT), 199, y, 349, 48)
            if (i < minOf(recent.size, 4) - 1) right.place(View(context).apply { setBackgroundColor(0x1AFFFFFF) }, 28, y + 51, 520, 1)
        }
        if (recent.size > 4) right.place(label("ほか${recent.size - 4}件", 13, C.MUTED), 28, 310, 520, 21)
    }
    private fun renderNext() {
        modeTitle("NEXT ACTION")
        val featured = snapshot?.featured
        if (featured == null) right.place(label("次の作業は未設定", 19, C.SECONDARY), 28, 83, 520, 88)
        else {
            right.place(label(listOfNotNull(featured.workId, featured.stage).joinToString("  ·  ").uppercase(), 14, C.BLUE, true), 28, 79, 520, 23)
            right.place(label(featured.title, 22, C.WHITE, true), 28, 104, 520, 34)
            right.place(label(featured.action, 17, C.TEXT), 28, 141, 520, 28)
        }
        right.place(View(context).apply { setBackgroundColor(0x30FFFFFF) }, 28, 190, 520, 1)
        right.place(label("ACTIVE", 16, C.SECONDARY, true), 28, 197, 280, 29)
        val active = snapshot?.active.orEmpty()
        val rest = (snapshot?.activeTotal ?: active.size) - minOf(3, active.size)
        if (rest > 0) right.place(label("+$rest", 13, C.MUTED, false, Gravity.RIGHT or Gravity.CENTER_VERTICAL), 450, 197, 98, 29)
        if (active.isEmpty()) right.place(label("—", 17, C.MUTED), 28, 236, 520, 37)
        active.take(3).forEachIndexed { i, project ->
            val y = 228 + i * 34
            right.place(label(project.workId, 16, C.WHITE, true), 28, y, 86, 31)
            right.place(label(project.title, 16, C.TEXT), 115, y, 285, 31)
            right.place(label(project.stage.uppercase(), 13, C.SECONDARY, false, Gravity.RIGHT or Gravity.CENTER_VERTICAL), 403, y, 145, 31)
        }
    }
    private fun renderAi() {
        val codex = snapshot?.ai?.codex
        right.place(label("AI", 24, C.WHITE, true), 28, 34, 240, 34)
        val status = when {
            codex == null -> "NO DATA"
            codex.status == "ok" && !codex.stale -> "LIVE"
            else -> "STALE"
        }
        val statusColor = when (status) { "LIVE" -> C.GREEN; "STALE" -> C.AMBER; else -> C.MUTED }
        right.place(label(status, 13, statusColor, true, Gravity.CENTER).apply {
            background = shape(15f, 0xCB101B29.toInt(), statusColor)
            letterSpacing = 0.06f
        }, 422, 74, 126, 32)
        right.place(label("CODEX / ${codex?.plan ?: "—"}", 19, C.TEXT, true), 28, 79, 374, 29)

        fun card(title: String, window: DashboardSnapshot.UsageWindow?, x: Int) {
            val panel = FrameLayout(context).apply { background = panelBackground() }
            right.place(panel, x, 118, 252, 120)
            panel.place(label(title, 13, C.SECONDARY, true).apply { letterSpacing = 0.08f }, 16, 13, 218, 20)
            val remaining = window?.let {
                String.format(Locale.US, "%.1f%% LEFT", it.remainingPercent).replace(".0%", "%")
            } ?: "—"
            panel.place(label(remaining, 26, C.WHITE, true), 16, 39, 220, 34)
            panel.place(label("RESET ${resetTime(window?.resetsAt)}", 13, C.MUTED), 16, 83, 220, 20)
        }
        card("SESSION", codex?.session, 28)
        card("WEEKLY", codex?.weekly, 296)

        right.place(label("TODAY", 13, C.SECONDARY, true).apply { letterSpacing = 0.08f }, 28, 257, 150, 19)
        right.place(label("${compactTokens(codex?.todayTokens)} TOKENS", 22, C.WHITE, true), 28, 278, 252, 30)
        val updated = snapshot?.ai?.updatedAt?.atZoneSameInstant(jst)
            ?.format(DateTimeFormatter.ofPattern("M/d HH:mm")) ?: "—"
        right.place(label("UPDATED $updated", 12, C.MUTED, false, Gravity.RIGHT or Gravity.CENTER_VERTICAL), 278, 282, 270, 22)
    }
}

internal data class WeatherBrief(val temperature: Int, val icon: String, val condition: String, val high: Int, val low: Int)
