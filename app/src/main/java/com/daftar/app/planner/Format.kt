package com.daftar.app.planner

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.text.format.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.School
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import com.daftar.app.R
import com.daftar.app.ui.theme.FolderPalette
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Type colours from DESIGN.md mapped onto the flat folder palette. */
fun typeColor(type: Int): Color = when (type) {
    EventType.EXAM -> FolderPalette[0]        // coral / red
    EventType.ASSIGNMENT -> FolderPalette[2]  // sand / amber
    EventType.MEETING -> FolderPalette[7]     // indigo
    EventType.CLASS -> FolderPalette[6]       // blue
    else -> FolderPalette[11]                 // slate
}

fun typeColorArgb(type: Int): Int = typeColor(type).toArgb()

fun typeLabelRes(type: Int): Int = when (type) {
    EventType.EXAM -> R.string.planner_type_exam
    EventType.ASSIGNMENT -> R.string.planner_type_assignment
    EventType.MEETING -> R.string.planner_type_meeting
    EventType.CLASS -> R.string.planner_type_class
    else -> R.string.planner_type_other
}

fun typeIcon(type: Int): ImageVector = when (type) {
    EventType.EXAM -> Icons.Rounded.Quiz
    EventType.ASSIGNMENT -> Icons.AutoMirrored.Rounded.Assignment
    EventType.MEETING -> Icons.Rounded.Groups
    EventType.CLASS -> Icons.Rounded.School
    else -> Icons.Rounded.Event
}

val AllTypes = listOf(EventType.EXAM, EventType.ASSIGNMENT, EventType.MEETING, EventType.CLASS, EventType.OTHER)

/** Reminder presets (minutes before start) with labels. */
val ReminderPresets = listOf(
    0 to R.string.planner_rem_at, 10 to R.string.planner_rem_10m, 30 to R.string.planner_rem_30m,
    60 to R.string.planner_rem_1h, 1440 to R.string.planner_rem_1d, 10080 to R.string.planner_rem_1w,
)

fun defaultReminders(type: Int): List<Int> = when (type) {
    EventType.EXAM -> listOf(1440, 60)
    EventType.ASSIGNMENT -> listOf(1440)
    EventType.MEETING, EventType.CLASS -> listOf(10)
    else -> listOf(60)
}

/**
 * Context whose resources follow the in-app language (Settings → Language) even outside an Activity
 * (widgets, notifications). On API 33+ the framework already applies per-app locales to every context.
 */
fun localized(ctx: Context): Context {
    if (Build.VERSION.SDK_INT >= 33) return ctx
    val tags = appLocaleTags(ctx) ?: return ctx
    val conf = Configuration(ctx.resources.configuration)
    conf.setLocales(LocaleList.forLanguageTags(tags))
    return ctx.createConfigurationContext(conf)
}

private fun appLocaleTags(ctx: Context): String? {
    val l = AppCompatDelegate.getApplicationLocales()
    if (!l.isEmpty) return l.toLanguageTags()
    // AppCompat (autoStoreLocales=true) persists the choice here; it is only loaded once an Activity starts,
    // so receivers/widgets running without an Activity read it directly.
    return runCatching {
        val f = File(ctx.filesDir, "androidx.appcompat.app.AppCompatDelegate.application_locales_record_file")
        Regex("application_locales=\"([^\"]*)\"").find(f.readText())?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

fun localeOf(ctx: Context): Locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()

private val zone: ZoneId get() = ZoneId.systemDefault()

fun timeFormatter(ctx: Context): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a", localeOf(ctx))

fun fmtTime(ctx: Context, millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(timeFormatter(ctx))

fun fmtTimeRange(ctx: Context, o: Occurrence): String = when {
    o.event.allDay -> ctx.getString(R.string.planner_all_day)
    o.event.type == EventType.ASSIGNMENT || o.end <= o.start -> fmtTime(ctx, o.start)
    else -> ctx.getString(R.string.planner_time_range, fmtTime(ctx, o.start), fmtTime(ctx, o.end))
}

/** "Today", "Tomorrow", "Yesterday", else "Mon 12 Oct" (+ year when not this year). */
fun dayLabel(ctx: Context, d: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (d) {
    today -> ctx.getString(R.string.planner_today)
    today.plusDays(1) -> ctx.getString(R.string.planner_tomorrow)
    today.minusDays(1) -> ctx.getString(R.string.planner_yesterday)
    else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", localeOf(ctx)))
}

/** Short day for widget rows: "Today" / "Tomorrow" / "Tue". */
fun shortDayLabel(ctx: Context, d: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when {
    d == today -> ctx.getString(R.string.planner_today)
    d == today.plusDays(1) -> ctx.getString(R.string.planner_tomorrow)
    d.isAfter(today) && d.isBefore(today.plusDays(7)) -> d.format(DateTimeFormatter.ofPattern("EEE", localeOf(ctx)))
    else -> d.format(DateTimeFormatter.ofPattern("d MMM", localeOf(ctx)))
}

fun fmtDate(ctx: Context, d: LocalDate): String =
    d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", localeOf(ctx)))

/** "Exam · Tomorrow 09:00 · Hall B" */
fun occurrenceSummary(ctx: Context, o: Occurrence, withLocation: Boolean = true): String {
    val d = Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
    val day = shortDayLabel(ctx, d)
    val time = if (o.event.allDay) day else "$day ${fmtTime(ctx, o.start)}"
    val parts = mutableListOf(ctx.getString(typeLabelRes(o.event.type)), time)
    if (withLocation && o.event.location.isNotBlank()) parts.add(o.event.location)
    return parts.joinToString(" · ")
}

/** Normalise a user-typed link; null if invalid. Blank stays blank. */
fun normalizeLink(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    val uri = runCatching { android.net.Uri.parse(withScheme) }.getOrNull() ?: return null
    val host = uri.host ?: return null
    if (t.contains(' ')) return null
    if (uri.scheme in listOf("http", "https") && !host.contains('.') && host != "localhost") return null
    return withScheme
}
