package com.daftar.app.planner.countdown

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.planner.DAY
import com.daftar.app.planner.EventType
import com.daftar.app.planner.HOUR
import com.daftar.app.planner.MINUTE
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.PlanEvent
import com.daftar.app.planner.Planner
import com.daftar.app.planner.fmtTime
import com.daftar.app.planner.localeOf
import com.daftar.app.planner.typeColor
import com.daftar.app.planner.typeLabelRes
import com.daftar.app.ui.theme.FolderPalette
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

// =====================================================================================
// Lists (built on Planner events — no separate store)
// =====================================================================================

private val zone: ZoneId get() = ZoneId.systemDefault()

fun isDone(e: PlanEvent) = e.type == EventType.ASSIGNMENT && e.done

/**
 * One card per event that has not ended: weekly events show their next occurrence (in progress counts),
 * one-off events themselves. Ticked assignments stay (their block reads "Done"). Sorted by start.
 */
fun futureCountdowns(list: List<PlanEvent>, now: Long): List<Occurrence> {
    val out = ArrayList<Occurrence>()
    val tmp = ArrayList<Occurrence>(1)
    for (e in list) {
        if (e.weekly) {
            tmp.clear()
            Planner.expand(e, now, Long.MAX_VALUE, 1, tmp)
            tmp.firstOrNull()?.let(out::add)
        } else if (e.end >= now) out.add(Occurrence(e, e.start, e.end))
    }
    out.sortWith(compareBy({ it.start }, { it.end }))
    return out
}

/** Ended events, most recent first. A weekly series whose repeat has ended shows its last occurrence. */
fun pastCountdowns(list: List<PlanEvent>, now: Long): List<Occurrence> {
    val out = ArrayList<Occurrence>()
    val tmp = ArrayList<Occurrence>()
    for (e in list) {
        if (!e.weekly) {
            if (e.end < now) out.add(Occurrence(e, e.start, e.end))
            continue
        }
        if (e.until <= 0 || e.until >= now) continue
        tmp.clear()
        Planner.expand(e, e.until - 8 * DAY, e.until, 4, tmp)
        tmp.lastOrNull()?.takeIf { it.end < now }?.let(out::add)
    }
    out.sortWith(compareByDescending<Occurrence> { it.end }.thenByDescending { it.start })
    return out
}

/** The occurrence a countdown screen / widget refers to (null when the event is gone). */
fun occurrenceFor(eventId: Long, occStart: Long, now: Long): Occurrence? {
    val e = Planner.get(eventId) ?: return null
    if (!e.weekly) return Occurrence(e, e.start, e.end)
    if (occStart > 0) Planner.occurrenceAt(e, occStart)?.let { return it }
    return futureCountdowns(listOf(e), now).firstOrNull() ?: pastCountdowns(listOf(e), now).firstOrNull()
}

// =====================================================================================
// Labels
// =====================================================================================

enum class CdState { FUTURE, TODAY, NOW, PAST, DONE }

/** End-block label: [number] big (null = text only), [unit] under it. */
data class EndLabel(val state: CdState, val number: String?, val unit: String)

fun fmtNumber(c: Context, n: Long): String = String.format(localeOf(c), "%d", n)

fun stateOf(o: Occurrence, now: Long): CdState = when {
    isDone(o.event) -> CdState.DONE
    now >= o.start && now <= maxOf(o.end, o.start) -> CdState.NOW
    now > o.end -> CdState.PAST
    else -> CdState.FUTURE
}

/**
 * "1 / day to go" (calendar days, like the reference app), "5 / hours to go" or "12 / mins to go" later today,
 * "Today" for all-day events, "Now", "Done", "2 / days ago". [short] drops "to go" / "ago" (narrow widget blocks).
 */
fun endLabel(c: Context, o: Occurrence, now: Long, unit: Int = CountdownStyles.UNIT_AUTO, short: Boolean = false): EndLabel {
    val r = c.resources
    return when (stateOf(o, now)) {
        CdState.DONE -> EndLabel(CdState.DONE, null, c.getString(R.string.cd_done))
        CdState.NOW -> EndLabel(CdState.NOW, null, c.getString(R.string.cd_now))
        CdState.PAST -> {
            val days = ChronoUnit.DAYS.between(Planner.dateOf(o.end), Planner.dateOf(now))
            val ago = now - o.end
            when {
                days >= 1 -> EndLabel(CdState.PAST, fmtNumber(c, days),
                    r.getQuantityString(if (short) R.plurals.cd_days else R.plurals.cd_days_ago, days.toInt()))
                ago >= HOUR -> (ago / HOUR).let { EndLabel(CdState.PAST, fmtNumber(c, it),
                    r.getQuantityString(if (short) R.plurals.cd_hours else R.plurals.cd_hours_ago, it.toInt())) }
                else -> ((ago / MINUTE).coerceAtLeast(1)).let { EndLabel(CdState.PAST, fmtNumber(c, it),
                    r.getQuantityString(if (short) R.plurals.cd_mins else R.plurals.cd_mins_ago, it.toInt())) }
            }
        }
        else -> {
            val days = ChronoUnit.DAYS.between(Planner.dateOf(now), Planner.dateOf(o.start))
            val left = o.start - now
            when {
                unit == CountdownStyles.UNIT_WEEKS && days >= 14 -> (days / 7).let {
                    EndLabel(CdState.FUTURE, fmtNumber(c, it), r.getQuantityString(if (short) R.plurals.cd_weeks else R.plurals.cd_weeks_to_go, it.toInt()))
                }
                days >= 1 -> EndLabel(CdState.FUTURE, fmtNumber(c, days),
                    r.getQuantityString(if (short) R.plurals.cd_days else R.plurals.cd_days_to_go, days.toInt()))
                o.event.allDay || unit == CountdownStyles.UNIT_DAYS -> EndLabel(CdState.TODAY, null, c.getString(R.string.cd_today))
                left >= HOUR -> (left / HOUR).let {
                    EndLabel(CdState.TODAY, fmtNumber(c, it), r.getQuantityString(if (short) R.plurals.cd_hours else R.plurals.cd_hours_to_go, it.toInt()))
                }
                else -> ((left + MINUTE - 1) / MINUTE).coerceAtLeast(1).let {
                    EndLabel(CdState.TODAY, fmtNumber(c, it), r.getQuantityString(if (short) R.plurals.cd_mins else R.plurals.cd_mins_to_go, it.toInt()))
                }
            }
        }
    }
}

/** When [endLabel] for [o] next changes (strictly after [now]); used to schedule widget refreshes. */
fun nextLabelChange(o: Occurrence, now: Long): Long {
    val midnight = Planner.startOfDay(Planner.dateOf(now).plusDays(1))
    return when (stateOf(o, now)) {
        CdState.DONE -> midnight
        CdState.NOW -> maxOf(o.end, o.start) + 1000
        CdState.PAST -> if (now - o.end < DAY) now + HOUR else midnight
        else -> {
            val left = o.start - now
            if (Planner.dateOf(o.start) != Planner.dateOf(now) || o.event.allDay) minOf(midnight, o.start)
            else if (left >= HOUR) o.start - (left / HOUR) * HOUR + 1000 // the hour count drops
            else o.start
        }
    }
}

/** Remaining [days, hours, mins, secs] until [target] (zeros once reached). */
fun liveParts(target: Long, now: Long): LongArray {
    val r = (target - now).coerceAtLeast(0) / 1000
    return longArrayOf(r / 86_400, (r % 86_400) / 3600, (r % 3600) / 60, r % 60)
}

/** "Tuesday, October 6, 2026 · 10:00" in the app language (all-day: date only). */
fun fullDate(c: Context, o: Occurrence): String {
    val d = Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
    val date = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(localeOf(c)))
    return if (o.event.allDay) date else c.getString(R.string.cd_date_time, date, fmtTime(c, o.start))
}

/** "Tuesday, October 6, 2026" */
fun fullDay(c: Context, millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(localeOf(c)))

/** "Tue 6 Oct" */
fun shortDate(c: Context, millis: Long): String {
    val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now(zone).year) "EEE d MMM" else "d MMM yyyy", localeOf(c)))
}

/** Card subtitle: location · subject, else the first description line, else the type. */
fun subtitleOf(c: Context, e: PlanEvent, subject: SubjectInfo?): String {
    val parts = listOfNotNull(e.location.trim().takeIf { it.isNotEmpty() }, subject?.name)
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    val first = e.description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return first ?: c.getString(typeLabelRes(e.type))
}

// =====================================================================================
// Subject folders & colours
// =====================================================================================

/** First-level library folder ("subject") an event is linked to. */
data class SubjectInfo(val path: String, val name: String, val color: Int, val icon: String)

object Subjects {
    /** Resolves the subject of a linked folder (small file read; call off the main thread for many events). */
    fun of(folder: String): SubjectInfo? {
        if (folder.isBlank()) return null
        return runCatching {
            val root = Storage.root.absolutePath
            var d: File? = File(folder)
            var subject: File? = null
            while (d != null && d.absolutePath.startsWith(root) && d.absolutePath != root) { subject = d; d = d.parentFile }
            if (subject == null || !subject.isDirectory) null
            else Storage.meta(subject).let { SubjectInfo(subject.absolutePath, subject.name, it.color, it.icon) }
        }.getOrNull()
    }

    /** eventId → subject for every event with a linked folder. */
    fun map(list: List<PlanEvent>): Map<Long, SubjectInfo> {
        val byFolder = HashMap<String, SubjectInfo?>()
        val out = HashMap<Long, SubjectInfo>()
        for (e in list) {
            if (e.folder.isBlank()) continue
            val s = byFolder.getOrPut(e.folder) { of(e.folder) } ?: continue
            out[e.id] = s
        }
        return out
    }
}

/** Card colour: the event's countdown style, else its subject folder colour, else the type colour. */
fun cardColor(e: PlanEvent, subject: SubjectInfo?, style: CountdownStyles.Style): Color = when {
    style.color in FolderPalette.indices -> FolderPalette[style.color]
    subject != null -> FolderPalette[subject.color.mod(FolderPalette.size)]
    else -> typeColor(e.type)
}

/** Readable text/icon colour on a flat colour card. */
fun contentOn(bg: Color): Color = if (bg.luminance() > 0.4f) Color(0xFF1F2937) else Color.White

/** The darker end block / band (flat shade of the card colour, no gradient). */
fun blockOn(bg: Color): Color = Color.Black.copy(alpha = 0.16f).compositeOver(bg)

// =====================================================================================
// Small stores: per-event style, first-run hint, widget choice, editor hand-off
// =====================================================================================

private fun prefs(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences("countdown", Context.MODE_PRIVATE)

/** Optional per-event "countdown style" (set in the event editor). Compose-observable through [version]. */
object CountdownStyles {
    const val UNIT_AUTO = 0
    const val UNIT_DAYS = 1
    const val UNIT_WEEKS = 2

    data class Style(val color: Int = -1, val unit: Int = UNIT_AUTO)

    var version by mutableIntStateOf(0)
        private set

    private var cache: Map<Long, Style>? = null

    private fun load(c: Context): Map<Long, Style> {
        cache?.let { return it }
        val m = HashMap<Long, Style>()
        for ((k, v) in prefs(c).all) {
            if (!k.startsWith("style_") || v !is String) continue
            val id = k.removePrefix("style_").toLongOrNull() ?: continue
            val p = v.split(',')
            m[id] = Style(p.getOrNull(0)?.toIntOrNull() ?: -1, p.getOrNull(1)?.toIntOrNull() ?: UNIT_AUTO)
        }
        cache = m
        return m
    }

    fun get(c: Context, id: Long): Style = synchronized(this) { load(c)[id] ?: Style() }

    fun put(c: Context, id: Long, s: Style) {
        synchronized(this) {
            val m = HashMap(load(c))
            if (s == Style()) m.remove(id) else m[id] = s
            cache = m
            prefs(c).edit().apply { if (s == Style()) remove("style_$id") else putString("style_$id", "${s.color},${s.unit}") }.apply()
        }
        version++
    }
}

object CountdownPrefs {
    fun hintShown(c: Context) = prefs(c).getBoolean("hintShown", false)
    fun setHintShown(c: Context) = prefs(c).edit().putBoolean("hintShown", true).apply()

    const val WIDGET_NEXT_EXAM = -1L
    const val WIDGET_NEXT_ANY = -2L

    /** Event shown by countdown widget [widgetId]: an event id, or [WIDGET_NEXT_EXAM] (default) / [WIDGET_NEXT_ANY]. */
    fun widgetEvent(c: Context, widgetId: Int): Long = prefs(c).getLong("widget_$widgetId", WIDGET_NEXT_EXAM)
    fun setWidgetEvent(c: Context, widgetId: Int, eventId: Long) = prefs(c).edit().putLong("widget_$widgetId", eventId).apply()
    fun removeWidget(c: Context, widgetId: Int) = prefs(c).edit().remove("widget_$widgetId").apply()
}

/** Hand-off from the Countdowns "+" (subject filter) to a new event in the editor; consumed once. */
object CountdownDraft {
    var folder: String? = null
}
