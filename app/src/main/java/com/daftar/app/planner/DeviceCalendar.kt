package com.daftar.app.planner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.daftar.app.R
import com.daftar.app.ui.toast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What to do with the phone calendar when a new event is saved. Observable from Compose. */
object CalendarPrefs {
    const val ASK = 0
    const val ALWAYS = 1
    const val NEVER = 2

    private var ready = false
    private lateinit var appCtx: Context
    private fun sp() = appCtx.getSharedPreferences("planner", Context.MODE_PRIVATE)

    /** [ASK], [ALWAYS] or [NEVER]. */
    var mode by mutableIntStateOf(ASK)
        private set
    /** Calendar new copies go to; -1 = the primary calendar. */
    var calendarId by mutableLongStateOf(-1L)
        private set

    fun init(ctx: Context) {
        if (ready) return
        appCtx = ctx.applicationContext
        mode = sp().getInt("calMode", ASK).coerceIn(ASK, NEVER)
        calendarId = sp().getLong("calId", -1L)
        ready = true
    }

    fun putMode(v: Int) { mode = v; if (ready) sp().edit().putInt("calMode", v).apply() }
    fun putCalendar(id: Long) { calendarId = id; if (ready) sp().edit().putLong("calId", id).apply() }
}

/** A calendar on the device that Daftar can write to. */
data class DeviceCal(
    val id: Long, val name: String, val account: String, val accountType: String, val color: Int, val primary: Boolean,
)

/** One importable item read from the device calendar. [event] has id 0 until it is imported. */
data class ImportCandidate(
    val key: String, val event: PlanEvent, val calendar: String, val color: Int, val alreadyImported: Boolean,
)

/**
 * The phone's calendar (Samsung Calendar, Google Calendar…) through CalendarContract.
 * Every call is safe without permission (returns null/false) and must run off the main thread unless noted.
 */
object DeviceCalendar {
    private val PERMS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    val permissions: Array<String> get() = PERMS.copyOf()

    private const val MAX_REMINDERS = 5
    /** Daftar reminds all-day events relative to 09:00; calendars count back from midnight. */
    private const val ALL_DAY_BASE_MIN = 9 * 60

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
    fun permitted(c: Context): Boolean = PERMS.all { granted(c, it) }
    fun canRead(c: Context): Boolean = granted(c, Manifest.permission.READ_CALENDAR)

    // ------------------------------------------------------------------ calendars

    /** Visible calendars the user can add events to, primary first. */
    fun calendars(c: Context): List<DeviceCal> {
        if (!canRead(c)) return emptyList()
        val sel = "${Calendars.VISIBLE}=1 AND ${Calendars.CALENDAR_ACCESS_LEVEL}>=${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val base = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.OWNER_ACCOUNT)
        fun read(withPrimary: Boolean): List<DeviceCal> {
            val proj = if (withPrimary) base + Calendars.IS_PRIMARY else base
            val out = ArrayList<DeviceCal>()
            c.contentResolver.query(Calendars.CONTENT_URI, proj, sel, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val account = cur.getString(2).orEmpty()
                    val type = cur.getString(3).orEmpty()
                    val owner = cur.getString(5).orEmpty()
                    val isPrimary = if (withPrimary) cur.getInt(6) == 1 else false
                    out.add(DeviceCal(
                        id = cur.getLong(0), name = cur.getString(1)?.takeIf { it.isNotBlank() } ?: account,
                        account = account, accountType = type, color = cur.getInt(4),
                        primary = isPrimary || (!withPrimary && owner.equals(account, true) && type != CalendarContract.ACCOUNT_TYPE_LOCAL),
                    ))
                }
            }
            return out
        }
        val list = runCatching { read(true) }.recoverCatching { read(false) }.getOrDefault(emptyList())
        val best = pickPrimary(list)
        return list.map { it.copy(primary = it.id == best?.id) }.sortedWith(compareBy({ !it.primary }, { it.account.lowercase() }, { it.name.lowercase() }))
    }

    private fun pickPrimary(list: List<DeviceCal>): DeviceCal? =
        list.firstOrNull { it.primary && it.accountType == "com.google" }
            ?: list.firstOrNull { it.primary }
            ?: list.firstOrNull { it.accountType == "com.google" && it.name.equals(it.account, true) }
            ?: list.firstOrNull { it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            ?: list.firstOrNull()

    /** The calendar new copies go to: the chosen one if it still exists, else the primary. */
    fun target(c: Context): DeviceCal? {
        val list = calendars(c)
        return list.firstOrNull { it.id == CalendarPrefs.calendarId } ?: list.firstOrNull { it.primary } ?: list.firstOrNull()
    }

    // ------------------------------------------------------------------ write

    /**
     * Inserts or updates the phone copy of [e]. [knownId] is the existing copy (0 = none); a copy deleted in the
     * calendar app is inserted again. Returns (device event id, calendar id), or null on failure.
     */
    fun push(c: Context, e: PlanEvent, knownId: Long): Pair<Long, Long>? {
        if (!permitted(c)) return null
        val cr = c.contentResolver
        return runCatching {
            val existingCal = if (knownId != 0L) calendarOf(cr, knownId) else null
            if (existingCal != null) {
                cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, knownId), values(e), null, null)
                writeReminders(cr, knownId, e)
                knownId to existingCal
            } else {
                val cal = target(c) ?: return@runCatching null
                val v = values(e).apply { put(Events.CALENDAR_ID, cal.id) }
                val uri = cr.insert(Events.CONTENT_URI, v) ?: return@runCatching null
                val id = ContentUris.parseId(uri)
                writeReminders(cr, id, e)
                id to cal.id
            }
        }.getOrNull()
    }

    fun delete(c: Context, deviceId: Long): Boolean {
        if (deviceId == 0L || !permitted(c)) return false
        return runCatching { c.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, deviceId), null, null) > 0 }
            .getOrDefault(false)
    }

    /** Calendar id of a live (not deleted) device event, or null when it is gone. */
    private fun calendarOf(cr: ContentResolver, deviceId: Long): Long? =
        cr.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.CALENDAR_ID, Events.DELETED),
            "${Events._ID}=?", arrayOf(deviceId.toString()), null)?.use { cur ->
            if (cur.moveToFirst() && cur.getInt(2) == 0) cur.getLong(1) else null
        }

    /** The subset of [ids] that still exist (not deleted) in the device calendar; null without permission / on error. */
    fun existing(c: Context, ids: List<Long>): Set<Long>? {
        if (!canRead(c)) return null
        if (ids.isEmpty()) return emptySet()
        return runCatching {
            val out = HashSet<Long>()
            ids.distinct().chunked(200).forEach { chunk ->
                val sel = "${Events.DELETED}=0 AND ${Events._ID} IN (${chunk.joinToString(",")})"
                c.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), sel, null, null)?.use { cur ->
                    while (cur.moveToNext()) out.add(cur.getLong(0))
                }
            }
            out
        }.getOrNull()
    }

    /** Display name of the calendar holding device event [deviceId], or null when it is gone. */
    fun calendarNameOf(c: Context, deviceId: Long): String? {
        if (deviceId == 0L || !canRead(c)) return null
        return runCatching {
            c.contentResolver.query(Events.CONTENT_URI, arrayOf(Events.CALENDAR_DISPLAY_NAME, Events.DELETED),
                "${Events._ID}=?", arrayOf(deviceId.toString()), null)?.use { cur ->
                if (cur.moveToFirst() && cur.getInt(1) == 0) cur.getString(0).orEmpty() else null
            }
        }.getOrNull()
    }

    private fun values(e: PlanEvent): ContentValues = ContentValues().apply {
        put(Events.TITLE, e.title)
        put(Events.DESCRIPTION, descriptionWithLink(e))
        put(Events.EVENT_LOCATION, e.location)
        put(Events.HAS_ALARM, if (reminderMinutes(e).isNotEmpty()) 1 else 0)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        if (e.allDay) {
            val (d0, d1) = allDayDates(e)
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, d0.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            if (e.weekly) {
                putNull(Events.DTEND)
                put(Events.DURATION, "P${java.time.temporal.ChronoUnit.DAYS.between(d0, d1).coerceAtLeast(1)}D")
                put(Events.RRULE, rrule(e, withByDay = true))
            } else {
                put(Events.DTEND, d1.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                putNull(Events.DURATION); putNull(Events.RRULE)
            }
        } else {
            val dur = (e.end - e.start).coerceAtLeast(0)
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(Events.DTSTART, e.start)
            if (e.weekly) {
                putNull(Events.DTEND)
                put(Events.DURATION, "P${dur / 1000}S")
                put(Events.RRULE, rrule(e, withByDay = true))
            } else {
                put(Events.DTEND, e.start + dur)
                putNull(Events.DURATION); putNull(Events.RRULE)
            }
        }
    }

    private fun writeReminders(cr: ContentResolver, deviceId: Long, e: PlanEvent) {
        cr.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID}=?", arrayOf(deviceId.toString()))
        for (m in reminderMinutes(e)) {
            cr.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, deviceId)
                put(Reminders.MINUTES, m)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    /**
     * Calendar reminder minutes that fire at the same moment as Daftar's. All-day events: Daftar counts from 09:00,
     * calendars from midnight, so "1 day before" = 900 min; reminders on the day itself (before 09:00 offset) cannot be
     * expressed in a calendar and are left to Daftar's own notification.
     */
    internal fun reminderMinutes(e: PlanEvent): List<Int> =
        e.reminders.distinct().sorted().mapNotNull { m ->
            if (!e.allDay) m else (m - ALL_DAY_BASE_MIN).takeIf { it >= 0 }
        }.distinct().take(MAX_REMINDERS)

    /** First day and the day after the last day of an all-day event (local dates). */
    internal fun allDayDates(e: PlanEvent): Pair<LocalDate, LocalDate> {
        val d0 = Planner.dateOf(e.start)
        val last = Planner.dateOf(maxOf(e.end, e.start))
        return d0 to (if (last.isAfter(d0)) last else d0).plusDays(1)
    }

    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** "FREQ=WEEKLY;UNTIL=…[;BYDAY=MO]" — UNTIL as a UTC date-time (timed) or a date (all-day). */
    internal fun rrule(e: PlanEvent, withByDay: Boolean): String = buildString {
        append("FREQ=WEEKLY")
        if (e.until > 0) {
            append(";UNTIL=")
            if (e.allDay) append(Planner.dateOf(e.until).format(DATE_STAMP))
            else append(UTC_STAMP.format(Instant.ofEpochMilli(e.until)))
        }
        if (withByDay) append(";BYDAY=").append(dayCode(Planner.dateOf(e.start).dayOfWeek))
    }

    private fun dayCode(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "MO"; DayOfWeek.TUESDAY -> "TU"; DayOfWeek.WEDNESDAY -> "WE"; DayOfWeek.THURSDAY -> "TH"
        DayOfWeek.FRIDAY -> "FR"; DayOfWeek.SATURDAY -> "SA"; DayOfWeek.SUNDAY -> "SU"
    }

    /** Description shown in the calendar app: the event's text plus its link (calendar apps make it tappable). */
    internal fun descriptionWithLink(e: PlanEvent): String {
        val d = e.description.trim()
        return when {
            e.link.isBlank() || d.contains(e.link) -> d
            d.isEmpty() -> e.link
            else -> "$d\n\n${e.link}"
        }
    }

    // ------------------------------------------------------------------ intents (main thread)

    /**
     * Permission-free fallback: opens the calendar app's "new event" screen pre-filled; the user confirms there.
     * Returns false when no calendar app is installed.
     */
    fun insertViaApp(ctx: Context, e: PlanEvent): Boolean {
        val i = Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
            .putExtra(Events.TITLE, e.title)
            .putExtra(Events.DESCRIPTION, descriptionWithLink(e))
            .putExtra(Events.EVENT_LOCATION, e.location)
            .putExtra(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
        if (e.allDay) {
            val (d0, d1) = allDayDates(e)
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, Planner.startOfDay(d0))
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, Planner.startOfDay(d1))
        } else {
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, maxOf(e.end, e.start))
        }
        if (e.weekly) i.putExtra(Events.RRULE, rrule(e, withByDay = true))
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: ActivityNotFoundException) {
            toast(ctx, ctx.getString(R.string.planner_cal_no_app)); false
        } catch (_: SecurityException) {
            toast(ctx, ctx.getString(R.string.planner_cal_no_app)); false
        }
    }

    /** Opens the phone copy in the calendar app, at occurrence [o] when given. */
    fun openInApp(ctx: Context, e: PlanEvent, o: Occurrence? = null) {
        if (e.deviceEventId == 0L) return
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, e.deviceEventId)
        val (b, en) = if (e.allDay) {
            val d0 = Planner.dateOf(o?.start ?: e.start)
            val days = java.time.temporal.ChronoUnit.DAYS.between(allDayDates(e).first, allDayDates(e).second)
            d0.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to d0.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else (o?.start ?: e.start) to (o?.end ?: maxOf(e.end, e.start))
        val i = Intent(Intent.ACTION_VIEW, uri)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, b)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, en)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) { toast(ctx, ctx.getString(R.string.planner_cal_no_app)) }
        catch (_: SecurityException) { toast(ctx, ctx.getString(R.string.planner_cal_no_app)) }
    }

    // ------------------------------------------------------------------ import

    /**
     * Upcoming device events in the next [days] days from visible calendars, Daftar's own copies excluded.
     * Simple weekly series (FREQ=WEEKLY, interval 1) become one weekly item per weekday; anything else is listed per occurrence.
     */
    fun upcomingForImport(c: Context, days: Int, own: List<PlanEvent>, maxItems: Int = 400): List<ImportCandidate>? {
        if (!canRead(c)) return null
        val now = System.currentTimeMillis()
        val to = now + days * DAY
        val ownIds = own.mapNotNull { it.deviceEventId.takeIf { id -> id != 0L } }.toHashSet()
        val importedKeys = own.mapNotNull { it.importKey.takeIf { k -> k.isNotEmpty() } }.toHashSet()
        val b = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - DAY) // all-day instances are stored at UTC midnight
        ContentUris.appendId(b, to)
        val proj = arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE, Instances.ALL_DAY,
            Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.CALENDAR_DISPLAY_NAME, Instances.DISPLAY_COLOR,
            Instances.RRULE, Instances.DTSTART)
        val out = LinkedHashMap<String, ImportCandidate>()
        val zone = ZoneId.systemDefault()
        return runCatching {
            c.contentResolver.query(b.build(), proj, "${Instances.VISIBLE}=1", null, "${Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext() && out.size < maxItems) {
                    val evId = cur.getLong(0)
                    if (evId in ownIds) continue
                    val allDay = cur.getInt(4) == 1
                    val begin = cur.getLong(1)
                    val endRaw = cur.getLong(2)
                    // Convert to Daftar's representation (all-day: local midnight .. end of last day).
                    val start: Long; val end: Long; val dow: DayOfWeek
                    if (allDay) {
                        val d0 = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
                        val d1 = Instant.ofEpochMilli(endRaw).atZone(ZoneOffset.UTC).toLocalDate().let { if (it.isAfter(d0)) it else d0.plusDays(1) }
                        start = Planner.startOfDay(d0); end = Planner.startOfDay(d1) - 1; dow = d0.dayOfWeek
                    } else {
                        start = begin; end = maxOf(endRaw, begin); dow = Instant.ofEpochMilli(begin).atZone(zone).dayOfWeek
                    }
                    if (end < now) continue
                    val rule = cur.getString(9).orEmpty()
                    val weekly = isSimpleWeekly(rule)
                    val key = if (weekly) "w$evId-${dow.value}" else "i$evId-$begin"
                    if (out.containsKey(key)) continue
                    val title = cur.getString(3)?.trim().orEmpty().ifEmpty { "—" }
                    val location = cur.getString(5)?.trim().orEmpty()
                    val description = plainText(cur.getString(6).orEmpty())
                    val link = findLink(description) ?: findLink(location) ?: ""
                    val until = if (weekly) untilOf(rule, cur.getLong(10), allDay) else 0L
                    val type = guessType(title, weekly)
                    val ev = PlanEvent(
                        id = 0, type = type, title = title, start = start, end = end, allDay = allDay,
                        weekly = weekly, until = until, location = location, link = link, description = description,
                        reminders = emptyList(), importKey = key,
                    )
                    out[key] = ImportCandidate(key, ev, cur.getString(7).orEmpty(), cur.getInt(8), key in importedKeys)
                }
            }
            out.values.toList()
        }.getOrNull()
    }

    private fun ruleParts(rule: String): Map<String, String> =
        rule.removePrefix("RRULE:").split(';').mapNotNull { p ->
            val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).trim().uppercase() to p.substring(i + 1).trim()
        }.toMap()

    internal fun isSimpleWeekly(rule: String): Boolean {
        if (rule.isBlank()) return false
        val p = ruleParts(rule)
        if (p["FREQ"]?.uppercase() != "WEEKLY") return false
        if ((p["INTERVAL"] ?: "1") != "1") return false
        if (listOf("BYMONTH", "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYSETPOS", "BYHOUR", "BYMINUTE").any { it in p }) return false
        val byDay = p["BYDAY"] ?: return true
        return byDay.split(',').all { it.trim().matches(Regex("(MO|TU|WE|TH|FR|SA|SU)", RegexOption.IGNORE_CASE)) }
    }

    /** Daftar `until` (end of the last local day) from RRULE UNTIL / COUNT; 0 = forever. */
    private fun untilOf(rule: String, dtStart: Long, allDay: Boolean): Long {
        val p = ruleParts(rule)
        p["UNTIL"]?.let { u ->
            val date: LocalDate? = runCatching {
                when {
                    u.length >= 15 && u.endsWith("Z") -> LocalDateTime.parse(u.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
                    u.length >= 15 -> LocalDateTime.parse(u.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).toLocalDate()
                    else -> LocalDate.parse(u.take(8), DATE_STAMP)
                }
            }.getOrNull()
            if (date != null) return Planner.startOfDay(date.plusDays(1)) - 1
        }
        p["COUNT"]?.toIntOrNull()?.let { n ->
            val perWeek = (p["BYDAY"]?.split(',')?.size ?: 1).coerceAtLeast(1)
            val weeks = ((n + perWeek - 1) / perWeek - 1).coerceAtLeast(0).toLong()
            val first = if (allDay) Instant.ofEpochMilli(dtStart).atZone(ZoneOffset.UTC).toLocalDate() else Planner.dateOf(dtStart)
            return Planner.startOfDay(first.plusWeeks(weeks).plusDays(1)) - 1
        }
        return 0L
    }

    private fun plainText(s: String): String {
        val t = if (s.contains('<') && s.contains('>')) HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() else s
        return t.replace("\r\n", "\n").trim()
    }

    private val urlRe = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    internal fun findLink(s: String): String? = urlRe.find(s)?.value?.trimEnd('.', ',', ')', ']', ';', '!', '?')

    private val examRe = Regex("""\b(exams?|quiz(zes)?|midterms?|finals?|tests?)\b""", RegexOption.IGNORE_CASE)
    private val assignRe = Regex("""\b(assignments?|homework|hw|deadline|due|submission|submit|projects?|reports?|essays?)\b""", RegexOption.IGNORE_CASE)
    private val meetRe = Regex("""\b(meetings?|meet|zoom|teams|office hours?|call|interview)\b""", RegexOption.IGNORE_CASE)
    private val classRe = Regex("""\b(lectures?|class(es)?|labs?|seminars?|tutorials?|sections?|courses?|lessons?)\b""", RegexOption.IGNORE_CASE)

    /** Type from the title (English + Arabic keywords); repeating weekly items default to Class, others to Other. */
    fun guessType(title: String, weekly: Boolean): Int {
        val t = title.lowercase()
        fun ar(vararg w: String) = w.any { t.contains(it) }
        return when {
            examRe.containsMatchIn(t) || ar("اختبار", "امتحان", "كويز", "اختبارات", "امتحانات") -> EventType.EXAM
            assignRe.containsMatchIn(t) || ar("واجب", "تكليف", "تسليم", "مشروع", "تقرير", "بحث") -> EventType.ASSIGNMENT
            meetRe.containsMatchIn(t) || ar("اجتماع", "لقاء", "مقابلة", "ساعات مكتبية") -> EventType.MEETING
            classRe.containsMatchIn(t) || ar("محاضرة", "محاضرات", "درس", "دروس", "مختبر", "معمل", "سكشن", "شعبة", "حصة") -> EventType.CLASS
            weekly -> EventType.CLASS
            else -> EventType.OTHER
        }
    }
}
