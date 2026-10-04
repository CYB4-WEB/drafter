package com.daftar.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.ui.theme.D
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Extra paper templates (vector, drawn only over the visible area, light + dark variants). `InkRender.drawPaper`
 * delegates every key this object [handles]; the original keys (blank, lined, grid, dots, cornell) stay in InkRender.
 *
 * Keys are plain strings stored in `InkPage.paper`. Planner templates may carry the date they were created for, so a
 * page keeps its dates forever: `week:2026-10-05` (first day of that week), `day:2026-10-04`, `month:2026-10`.
 * An undated planner key (`week`, e.g. a default paper chosen in Settings) draws the same layout without dates.
 *
 * Everything here is thread-safe (paints are thread-local, label caches concurrent): page thumbnails render on
 * background threads.
 */
object PaperTemplates {

    /** Every paper the user can choose, in chooser order (base keys, without dates). */
    val keys = listOf(
        "blank", "lined", "lined_wide", "lined_narrow", "grid", "graph", "dots", "isometric",
        "cornell", "todo", "music", "storyboard", "week", "day", "month",
    )

    /** Patterns that tile without page margins, so they also work on an infinite whiteboard. */
    val tiling = setOf("blank", "lined", "lined_wide", "lined_narrow", "grid", "graph", "dots", "isometric", "music")

    private val own = setOf("lined_wide", "lined_narrow", "graph", "isometric", "todo", "music", "storyboard", "week", "day", "month")

    fun base(key: String): String = key.substringBefore(':')

    /** True for the templates drawn here (InkRender draws the rest). */
    fun handles(key: String): Boolean = base(key) in own

    fun isPlanner(key: String) = base(key).let { it == "week" || it == "day" || it == "month" }

    fun labelRes(key: String): Int = when (base(key)) {
        "blank" -> R.string.ink_paper_blank
        "lined" -> R.string.ink_paper_lined
        "grid" -> R.string.ink_paper_grid
        "dots" -> R.string.ink_paper_dots
        "cornell" -> R.string.ink_paper_cornell
        "lined_wide" -> R.string.pages_tpl_lined_wide
        "lined_narrow" -> R.string.pages_tpl_lined_narrow
        "graph" -> R.string.pages_tpl_graph
        "isometric" -> R.string.pages_tpl_isometric
        "todo" -> R.string.pages_tpl_todo
        "music" -> R.string.pages_tpl_music
        "storyboard" -> R.string.pages_tpl_storyboard
        "week" -> R.string.pages_tpl_week
        "day" -> R.string.pages_tpl_day
        "month" -> R.string.pages_tpl_month
        else -> R.string.ink_paper_blank
    }

    // =====================================================================================
    // Dates
    // =====================================================================================

    /** The key to store for a page created now: planners get today's week / day / month. */
    fun stamp(key: String, today: LocalDate = LocalDate.now()): String = when (base(key)) {
        "week" -> "week:" + today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek()))
        "day" -> "day:$today"
        "month" -> "month:" + YearMonth.from(today)
        else -> base(key)
    }

    /** The key for the page that follows a page with [key]: dated planners advance one week / day / month. */
    fun nextKey(key: String, steps: Int = 1): String {
        if (steps == 0) return key
        val v = key.substringAfter(':', "")
        if (v.isEmpty()) return key
        return runCatching {
            when (base(key)) {
                "week" -> "week:" + LocalDate.parse(v).plusWeeks(steps.toLong())
                "day" -> "day:" + LocalDate.parse(v).plusDays(steps.toLong())
                "month" -> "month:" + YearMonth.parse(v).plusMonths(steps.toLong())
                else -> key
            }
        }.getOrDefault(key)
    }

    /** UI locale: the app language (Settings) or the device's, with the device region for week rules. */
    fun locale(): Locale {
        val dev = Locale.getDefault()
        val app = runCatching { AppCompatDelegate.getApplicationLocales().get(0) }.getOrNull() ?: return dev
        return if (app.country.isEmpty() && dev.country.isNotEmpty()) Locale(app.language, dev.country) else app
    }

    /** First day of the week for the UI locale (Saturday in much of the Arab world, Monday / Sunday elsewhere). */
    fun firstDayOfWeek(loc: Locale = locale()): DayOfWeek = when (Calendar.getInstance(loc).firstDayOfWeek) {
        Calendar.SUNDAY -> DayOfWeek.SUNDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        Calendar.FRIDAY -> DayOfWeek.FRIDAY
        Calendar.TUESDAY -> DayOfWeek.TUESDAY
        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Calendar.THURSDAY -> DayOfWeek.THURSDAY
        else -> DayOfWeek.MONDAY
    }

    // =====================================================================================
    // Localized labels (cached per key + locale: no formatting work per frame)
    // =====================================================================================

    @Volatile private var appContext: Context? = null

    /** Gives the templates a context for translated captions ("Notes"); called by the editor UI. */
    fun bind(ctx: Context) { if (appContext == null) appContext = ctx.applicationContext }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun context(): Context? = appContext ?: runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()?.also { appContext = it }

    private class Labels(
        val rtl: Boolean,
        val title: String,
        val notes: String,
        /** week: 7 day headers (from the first day); month: 7 short weekday names; day: 16 hour labels. */
        val heads: List<String>,
        /** month: day-number strings 1…31 placed in cells (index = cell, "" = outside the month). */
        val cells: List<String> = emptyList(),
        val numbers: List<String> = emptyList(),
    )

    private val labelCache = ConcurrentHashMap<String, Labels>()

    private fun labels(key: String): Labels {
        val loc = locale()
        val ck = key + "|" + loc.toLanguageTag()
        labelCache[ck]?.let { return it }
        if (labelCache.size > 64) labelCache.clear()
        val l = buildLabels(key, loc)
        labelCache[ck] = l
        return l
    }

    private fun localized(loc: Locale, res: Int): String {
        val ctx = context() ?: return ""
        return runCatching {
            val conf = Configuration(ctx.resources.configuration).apply { setLocale(loc) }
            ctx.createConfigurationContext(conf).getString(res)
        }.getOrDefault("")
    }

    private fun buildLabels(key: String, loc: Locale): Labels {
        val rtl = TextUtils.getLayoutDirectionFromLocale(loc) == View.LAYOUT_DIRECTION_RTL
        val ds = DecimalStyle.of(loc)
        val nf = NumberFormat.getIntegerInstance(loc)
        val v = key.substringAfter(':', "")
        val notes = localized(loc, R.string.pages_tpl_notes)
        return when (base(key)) {
            "week" -> {
                val start = runCatching { LocalDate.parse(v) }.getOrNull()
                val first = start?.dayOfWeek ?: firstDayOfWeek(loc)
                val heads = (0 until 7).map { k ->
                    val dow = first.plus(k.toLong())
                    val name = dow.getDisplayName(TextStyle.FULL, loc)
                    if (start != null) name + "  " + nf.format(start.plusDays(k.toLong()).dayOfMonth) else name
                }
                val title = if (start != null) {
                    val end = start.plusDays(6)
                    val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(loc).withDecimalStyle(ds)
                    f.format(start) + " – " + f.format(end)
                } else ""
                Labels(rtl, title, notes, heads)
            }
            "day" -> {
                val date = runCatching { LocalDate.parse(v) }.getOrNull()
                val title = date?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(loc).withDecimalStyle(ds).format(it) } ?: ""
                val tf = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(loc).withDecimalStyle(ds)
                Labels(rtl, title, notes, (DAY_FIRST..DAY_LAST).map { tf.format(LocalTime.of(it, 0)) })
            }
            "month" -> {
                val ym = runCatching { YearMonth.parse(v) }.getOrNull()
                val first = firstDayOfWeek(loc)
                val heads = (0 until 7).map { first.plus(it.toLong()).getDisplayName(TextStyle.SHORT, loc) }
                val title = ym?.let { DateTimeFormatter.ofPattern("LLLL yyyy", loc).withDecimalStyle(ds).format(it) } ?: ""
                val cells = if (ym != null) {
                    val lead = (ym.atDay(1).dayOfWeek.value - first.value + 7) % 7
                    val rows = ceil((lead + ym.lengthOfMonth()) / 7.0).toInt()
                    List(rows * 7) { i -> val d = i - lead + 1; if (d in 1..ym.lengthOfMonth()) nf.format(d) else "" }
                } else List(35) { "" }
                Labels(rtl, title, notes, heads, cells)
            }
            "storyboard" -> Labels(rtl, "", notes, emptyList(), numbers = (1..12).map { nf.format(it) })
            else -> Labels(rtl, "", notes, emptyList())
        }
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    private const val MM = 72f / 25.4f
    private const val DAY_FIRST = 7
    private const val DAY_LAST = 22

    private val lineTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    private val dashTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(3f, 3f), 0f) } }
    private val fillTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val textTL = ThreadLocal.withInitial { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT } }
    private val areaTL = ThreadLocal.withInitial { RectF() }
    private val dotsTL = ThreadLocal.withInitial { FloatArray(4096) }

    /** Colours (light paper / dark paper). */
    private fun cLine(dark: Boolean) = if (dark) 0x33FFFFFF else 0xFFC9D8EA.toInt()
    private fun cMinor(dark: Boolean) = if (dark) 0x1CFFFFFF else 0xFFE4EBF3.toInt()
    private fun cMajor(dark: Boolean) = if (dark) 0x40FFFFFF else 0xFFB7C9DD.toInt()
    private fun cFrame(dark: Boolean) = if (dark) 0x59FFFFFF else 0xFF9DB3CC.toInt()
    private fun cBand(dark: Boolean) = if (dark) 0x12FFFFFF else 0xFFF1F5FA.toInt()
    private fun cText(dark: Boolean) = if (dark) 0xB3FFFFFF.toInt() else 0xFF52657D.toInt()
    private fun cMargin() = 0x55E57373

    /**
     * Same contract as `InkRender.drawPaper`: page coordinates, no background fill, only [clip] drawn, [pxPerUnit] > 0
     * thins dense patterns (always a subset, so nothing shifts) and keeps hairlines visible; [bounded] false = whiteboard
     * (tiling patterns cover the clip without page margins).
     */
    fun draw(c: Canvas, page: InkPage, dark: Boolean = false, clip: RectF? = null, pxPerUnit: Float = 0f, bounded: Boolean = true) {
        val key = page.paper
        val b = base(key)
        val area = areaTL.get()!!
        if (clip != null) area.set(clip) else area.set(0f, 0f, page.w, page.h)
        // page layouts (planners, boxes) always live inside the page, even on a whiteboard
        val layout = b !in tiling
        if ((bounded || layout) && !area.intersect(0f, 0f, page.w, page.h)) return
        if (area.isEmpty) return
        val g = G(c, page, dark, area, pxPerUnit, bounded)
        when (b) {
            "lined_wide" -> g.lined(34f)
            "lined_narrow" -> g.lined(18f)
            "graph" -> g.graph()
            "isometric" -> g.isometric()
            "music" -> g.music()
            else -> {
                c.save(); c.clipRect(area)
                when (b) {
                    "todo" -> g.todo()
                    "storyboard" -> g.storyboard(labels(key))
                    "week" -> g.week(labels(key))
                    "day" -> g.day(labels(key))
                    "month" -> g.month(labels(key))
                }
                c.restore()
            }
        }
    }

    /** One draw call's state (cheap; avoids passing seven arguments to every template). */
    private class G(val c: Canvas, val page: InkPage, val dark: Boolean, val area: RectF, val px: Float, val bounded: Boolean) {
        val w = page.w
        val h = page.h
        val lp: Paint = lineTL.get()!!
        val tp: TextPaint = textTL.get()!!
        val fp: Paint = fillTL.get()!!

        fun hair(base: Float) = if (px > 0f) max(base, 0.75f / px) else base
        fun lod(spacing: Float, minPx: Float): Float {
            var s = spacing
            if (px > 0f) while (s * px < minPx && s < 1e6f) s *= 2f
            return s
        }
        fun line(color: Int, width: Float) { lp.color = color; lp.strokeWidth = hair(width); lp.pathEffect = null }
        fun hLine(x0: Float, x1: Float, y: Float) {
            if (y < area.top - 1 || y > area.bottom + 1) return
            val a = max(x0, area.left); val b = min(x1, area.right)
            if (a < b) c.drawLine(a, y, b, y, lp)
        }
        fun vLine(x: Float, y0: Float, y1: Float) {
            if (x < area.left - 1 || x > area.right + 1) return
            val a = max(y0, area.top); val b = min(y1, area.bottom)
            if (a < b) c.drawLine(x, a, x, b, lp)
        }
        fun rect(l: Float, t: Float, r: Float, b: Float) { if (r >= area.left && l <= area.right && b >= area.top && t <= area.bottom) c.drawRect(l, t, r, b, lp) }
        fun band(l: Float, t: Float, r: Float, b: Float) { fp.color = cBand(dark); if (r >= area.left && l <= area.right && b >= area.top && t <= area.bottom) c.drawRect(l, t, r, b, fp) }

        /** Text at the start (or end) edge of [l, r] honouring RTL, baseline [y], ellipsized to the width. */
        fun text(s: String, l: Float, r: Float, y: Float, size: Float, rtl: Boolean, bold: Boolean = false, atEnd: Boolean = false, center: Boolean = false) {
            if (s.isEmpty() || y - size > area.bottom || y + size * 0.4f < area.top) return
            tp.textSize = size; tp.color = cText(dark); tp.isFakeBoldText = bold
            val avail = r - l
            if (avail <= 2f) return
            val t = TextUtils.ellipsize(s, tp, avail, TextUtils.TruncateAt.END)
            val tw = tp.measureText(t, 0, t.length)
            val right = rtl != atEnd
            val x = when { center -> l + (avail - tw) / 2f; right -> r - tw; else -> l }
            tp.textAlign = Paint.Align.LEFT
            c.drawText(t, 0, t.length, x, y, tp)
            tp.isFakeBoldText = false
        }

        // ---- tiling patterns ----

        fun lined(spacing: Float) {
            line(cLine(dark), 0.6f)
            val s = lod(spacing, 7f)
            if (bounded) {
                val x0 = 24f; val x1 = w - 24f
                var y = 72f + max(0f, ceil((area.top - 72f) / s)) * s
                val yEnd = min(h - 20f, area.bottom)
                while (y < yEnd) { hLine(x0, x1, y); y += s }
                lp.color = cMargin(); vLine(64f, area.top, area.bottom)
            } else {
                var y = ceil(area.top / s) * s
                while (y <= area.bottom) { c.drawLine(area.left, y, area.right, y, lp); y += s }
            }
        }

        fun graph() {
            val minor = 5f * MM
            val skip = bounded
            if (px <= 0f || minor * px >= 5f) {
                line(cMinor(dark), 0.4f)
                var k = ceil(area.left / minor).toLong()
                while (k * minor <= area.right) {
                    val x = k * minor
                    if (k % 2L != 0L && !(skip && (x <= 0f || x >= w))) c.drawLine(x, area.top, x, area.bottom, lp)
                    k++
                }
                k = ceil(area.top / minor).toLong()
                while (k * minor <= area.bottom) {
                    val y = k * minor
                    if (k % 2L != 0L && !(skip && (y <= 0f || y >= h))) c.drawLine(area.left, y, area.right, y, lp)
                    k++
                }
            }
            line(cMajor(dark), 0.6f)
            val major = lod(10f * MM, 7f)
            var x = ceil(area.left / major) * major
            while (x <= area.right) { if (!(skip && (x <= 0.01f || x >= w - 0.01f))) c.drawLine(x, area.top, x, area.bottom, lp); x += major }
            var y = ceil(area.top / major) * major
            while (y <= area.bottom) { if (!(skip && (y <= 0.01f || y >= h - 0.01f))) c.drawLine(area.left, y, area.right, y, lp); y += major }
        }

        /** Isometric (triangular) dot lattice, 5 mm; zooming out keeps every 2nd/4th… dot of the same lattice. */
        fun isometric() {
            var s = 5f * MM
            if (px > 0f) while (s * px < 11f && s < 1e6f) s *= 2f
            val rh = s * sqrt(3f) / 2f
            lp.color = if (dark) 0x40FFFFFF else 0xFFAFBAC7.toInt()
            lp.strokeWidth = 2f * (if (px > 0f) max(0.85f, 0.85f / px) else 0.85f)
            lp.strokeCap = Paint.Cap.ROUND; lp.pathEffect = null
            var buf = dotsTL.get()!!
            var n = 0
            var j = floor(area.top / rh).toLong()
            while (j * rh <= area.bottom) {
                val y = j * rh
                if (!(bounded && (y <= 0f || y >= h))) {
                    val off = if (j % 2L != 0L) s / 2f else 0f
                    var x = ceil((area.left - off) / s) * s + off
                    while (x <= area.right) {
                        if (!(bounded && (x <= 0f || x >= w))) {
                            if (n + 2 > buf.size) {
                                if (buf.size < 65536) { buf = buf.copyOf(buf.size * 2); dotsTL.set(buf) } else { c.drawPoints(buf, 0, n, lp); n = 0 }
                            }
                            buf[n++] = x; buf[n++] = y
                        }
                        x += s
                    }
                }
                j++
            }
            if (n > 0) c.drawPoints(buf, 0, n, lp)
            lp.strokeCap = Paint.Cap.BUTT
        }

        /** Five-line staves (7 pt gaps) every 72 pt. */
        fun music() {
            val gap = 7f; val period = 72f; val top0 = if (bounded) 64f else 0f
            if (px > 0f && period * px < 10f) return
            line(if (dark) 0x4DFFFFFF else 0xFFAFC0D4.toInt(), 0.6f)
            val x0 = if (bounded) 36f else area.left
            val x1 = if (bounded) w - 36f else area.right
            val yEnd = if (bounded) h - 36f else area.bottom + period
            var k = max(0f, floor((area.top - top0 - 4 * gap) / period))
            while (true) {
                val y = top0 + k * period
                if (y > area.bottom || (bounded && y + 4 * gap > yEnd)) break
                for (i in 0 until 5) hLine(x0, x1, y + i * gap)
                if (bounded) { vLine(x0, y, y + 4 * gap); vLine(x1, y, y + 4 * gap) }
                k++
            }
        }

        // ---- page layouts (clipped by the caller) ----

        fun todo() {
            val rtl = labels("todo").rtl
            val m = 36f; val step = 32f; val box = 12f
            line(cFrame(dark), 1f)
            hLine(m, w - m, 72f)                 // title line
            var y = 72f + step
            while (y <= h - m) {
                val bx = if (rtl) w - m - box else m
                line(cFrame(dark), 0.8f)
                if (y - 9f - box <= area.bottom && y >= area.top) c.drawRoundRect(bx, y - 8f - box, bx + box, y - 8f, 2f, 2f, lp)
                line(cLine(dark), 0.6f)
                if (rtl) hLine(m, w - m - box - 12f, y) else hLine(m + box + 12f, w - m, y)
                y += step
            }
        }

        fun storyboard(l: Labels) {
            val land = w > h
            val cols = if (land) 3 else 2; val rows = if (land) 2 else 3
            val m = 36f; val gut = 18f
            val cw = (w - 2 * m - (cols - 1) * gut) / cols
            val ch = (h - 2 * m - (rows - 1) * gut) / rows
            val caption = 3 * 16f + 6f
            var fw = cw; var fh = cw * 9f / 16f
            if (fh + caption > ch) { fh = max(20f, ch - caption); fw = fh * 16f / 9f }
            for (r in 0 until rows) for (k in 0 until cols) {
                val col = if (l.rtl) cols - 1 - k else k
                val cx = m + col * (cw + gut); val cy = m + r * (ch + gut)
                val fx = cx + (cw - fw) / 2f
                line(cFrame(dark), 1f)
                rect(fx, cy, fx + fw, cy + fh)
                val num = l.numbers.getOrElse(r * cols + k) { "" }
                text(num, fx + 4f, fx + fw - 4f, cy + 12f, 9f, l.rtl)
                line(cLine(dark), 0.6f)
                for (i in 1..3) hLine(fx, fx + fw, cy + fh + 6f + i * 16f)
            }
        }

        /** 7 day boxes + a notes box in a 2×4 (portrait) / 4×2 (landscape) table, week range on top. */
        fun week(l: Labels) {
            val m = 36f
            val land = w > h
            val cols = if (land) 4 else 2; val rows = if (land) 2 else 4
            text(l.title, m, w - m, 62f, 15f, l.rtl, bold = true)
            val top = 80f; val bottom = h - m
            val cw = (w - 2 * m) / cols; val ch = (bottom - top) / rows
            val head = 20f
            for (i in 0 until 8) {
                val r = i / cols; val k = i % cols
                val col = if (l.rtl) cols - 1 - k else k
                val x0 = m + col * cw; val y0 = top + r * ch
                band(x0, y0, x0 + cw, y0 + head)
                text(if (i < 7) l.heads[i] else l.notes, x0 + 6f, x0 + cw - 6f, y0 + 14f, 10.5f, l.rtl, bold = true)
                line(cLine(dark), 0.5f)
                var y = y0 + head + 22f
                while (y < y0 + ch - 4f) { hLine(x0 + 6f, x0 + cw - 6f, y); y += 22f }
            }
            line(cFrame(dark), 0.9f)
            rect(m, top, w - m, bottom)
            for (k in 1 until cols) vLine(m + k * cw, top, bottom)
            for (r in 1 until rows) hLine(m, w - m, top + r * ch)
            for (r in 0 until rows) hLine(m, w - m, top + r * ch + head)
        }

        /** Hours 07–22 (half hours dashed) with a time column at the start and a notes column at the end. */
        fun day(l: Labels) {
            val m = 36f
            text(l.title, m, w - m, 62f, 15f, l.rtl, bold = true)
            val top = 80f; val bottom = h - m
            val timeW = 46f
            val notesW = (w - 2 * m) * (if (w > h) 0.34f else 0.3f)
            val hours = DAY_LAST - DAY_FIRST + 1
            val head = 20f
            val rowH = (bottom - top - head) / hours
            // logical columns (LTR positions), mirrored for RTL
            fun xs(x: Float) = if (l.rtl) w - x else x
            val tL = min(xs(m), xs(m + timeW)); val tR = max(xs(m), xs(m + timeW))
            val nL = min(xs(w - m - notesW), xs(w - m)); val nR = max(xs(w - m - notesW), xs(w - m))
            val gL = if (l.rtl) nR else tR; val gR = if (l.rtl) tL else nL       // the hours grid between them
            band(m, top, w - m, top + head)
            text(l.notes, nL + 6f, nR - 6f, top + 14f, 10.5f, l.rtl, bold = true)
            val dp = dashTL.get()!!
            for (i in 0 until hours) {
                val y = top + head + i * rowH
                text(l.heads.getOrElse(i) { "" }, tL + 4f, tR - 4f, y + 12f, 9f, l.rtl)
                line(cLine(dark), 0.6f)
                if (i > 0) hLine(min(tL, gL), max(tR, gR), y)
                val yh = y + rowH / 2f
                if (yh >= area.top && yh <= area.bottom) {
                    dp.color = cMajor(dark); dp.strokeWidth = hair(0.5f)
                    c.drawLine(max(gL, area.left), yh, min(gR, area.right), yh, dp)
                }
            }
            line(cLine(dark), 0.5f)
            var y = top + head + 22f
            while (y < bottom - 4f) { hLine(nL + 6f, nR - 6f, y); y += 22f }
            line(cFrame(dark), 0.9f)
            rect(m, top, w - m, bottom)
            hLine(m, w - m, top + head)
            vLine(if (l.rtl) tL else tR, top, bottom)
            vLine(if (l.rtl) nR else nL, top, bottom)
        }

        /** Month grid: title, weekday header row, 5–6 week rows with the day number in each cell's top-start corner. */
        fun month(l: Labels) {
            val m = 36f
            text(l.title, m, w - m, 62f, 17f, l.rtl, bold = true)
            val top = 80f; val bottom = h - m
            val head = 20f
            val rows = max(1, l.cells.size / 7)
            val cw = (w - 2 * m) / 7f; val ch = (bottom - top - head) / rows
            band(m, top, w - m, top + head)
            for (k in 0 until 7) {
                val col = if (l.rtl) 6 - k else k
                val x0 = m + col * cw
                text(l.heads.getOrElse(k) { "" }, x0 + 2f, x0 + cw - 2f, top + 14f, 10f, l.rtl, bold = true, center = true)
            }
            for (i in l.cells.indices) {
                val s = l.cells[i]
                val r = i / 7; val k = i % 7
                val col = if (l.rtl) 6 - k else k
                val x0 = m + col * cw; val y0 = top + head + r * ch
                if (s.isEmpty() && l.cells.any { it.isNotEmpty() }) band(x0, y0, x0 + cw, y0 + ch)
                else text(s, x0 + 5f, x0 + cw - 5f, y0 + 14f, 11f, l.rtl)
            }
            line(cFrame(dark), 0.9f)
            rect(m, top, w - m, bottom)
            hLine(m, w - m, top + head)
            for (k in 1 until 7) vLine(m + k * cw, top, bottom)
            line(cLine(dark), 0.7f)
            for (r in 1 until rows) hLine(m, w - m, top + head + r * ch)
        }
    }

    // =====================================================================================
    // UI: previews, chooser, paper dialog
    // =====================================================================================

    /** Real mini preview of [key] (the same vector drawing as the page, scaled down). */
    @Composable
    fun PaperPreview(key: String, modifier: Modifier = Modifier, landscape: Boolean = false, paperColor: Int = 0xFFFFFFFF.toInt()) {
        val ctx = LocalContext.current
        remember { bind(ctx); true }
        val page = remember(key, landscape) { if (landscape) InkPage(842f, 595f, key) else InkPage(595f, 842f, key) }
        val dark = isDarkColor(paperColor)
        Canvas(modifier.aspectRatio(page.w / page.h)) {
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                val s = size.width / page.w
                nc.save()
                nc.drawColor(paperColor or 0xFF000000.toInt())
                nc.scale(s, s)
                InkRender.drawPaper(nc, page, dark, null, s)
                nc.restore()
            }
        }
    }

    fun isDarkColor(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }

    /** Grid of template cards with live previews; [onPick] gets the base key. */
    @Composable
    fun TemplateGrid(
        selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier,
        whiteboard: Boolean = false, landscape: Boolean = false, paperColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val c = D.c
        val list = if (whiteboard) keys.filter { it in tiling } else keys
        val sel = selected?.let { base(it) }
        LazyVerticalGrid(GridCells.Adaptive(if (landscape) 120.dp else 92.dp), modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it }) { k ->
                val on = k == sel
                val label = stringResource(labelRes(k))
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (on) c.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = label) { onPick(k) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PaperPreview(k, Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, RoundedCornerShape(6.dp)), landscape, paperColor)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = if (on) c.accent else c.ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }

    /**
     * Template chooser: previews (+ portrait / landscape for new pages). [onPick] gets the base key and landscape flag.
     */
    @Composable
    fun TemplateChooserDialog(
        title: String, initial: String, initialLandscape: Boolean, paperColor: Int, onDismiss: () -> Unit,
        showOrientation: Boolean = true, confirm: Int = R.string.pages_insert, onPick: (String, Boolean) -> Unit,
    ) {
        val c = D.c
        var land by remember { mutableStateOf(initialLandscape) }
        var key by remember { mutableStateOf(base(initial)) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = {
                Column {
                    if (showOrientation) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!land, { land = false }, { Text(stringResource(R.string.pages_portrait)) })
                        FilterChip(land, { land = true }, { Text(stringResource(R.string.pages_landscape)) })
                    }
                    Spacer(Modifier.height(8.dp))
                    TemplateGrid(key, { key = it }, Modifier.heightIn(max = 420.dp), landscape = land, paperColor = paperColor)
                }
            },
            confirmButton = { TextButton(onClick = { onPick(key, land) }) { Text(stringResource(confirm), color = c.accent) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    /**
     * The editor's "Paper style" dialog: every template with a preview, optional "apply to all pages". Dated planners
     * applied to all pages get consecutive weeks / days / months. The choice becomes the default paper for new notes
     * (undated base key).
     */
    @Composable
    fun PaperPickerDialog(view: InkView, whiteboard: Boolean, onDismiss: () -> Unit) {
        var all by remember { mutableStateOf(false) }
        val current = view.doc.pages.getOrNull(view.currentPage)?.paper
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ink_paper)) },
            text = {
                Column {
                    TemplateGrid(current, { k ->
                        applyPaper(view, k, all, whiteboard)
                        if (!whiteboard) Prefs.putPaper(k)
                        onDismiss()
                    }, Modifier.heightIn(max = 440.dp), whiteboard = whiteboard, paperColor = view.doc.paperColor)
                    if (!whiteboard) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(all, { all = it }); Text(stringResource(R.string.ink_apply_all_pages), color = D.c.ink)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun applyPaper(view: InkView, base: String, all: Boolean, whiteboard: Boolean) {
        val first = stamp(base)
        if (whiteboard || !all || !isPlanner(first)) { view.setPaper(first, all); return }
        var k = first
        val pages = view.doc.pages.mapIndexed { i, p -> if (i == 0) p.copy(paper = k) else { k = nextKey(k); p.copy(paper = k) } }
        view.applyPages(pages)
    }

    /** (key, label) pairs for a settings "default paper" picker. */
    @Composable
    fun choices(): List<Pair<String, String>> = keys.map { it to stringResource(labelRes(it)) }
}
