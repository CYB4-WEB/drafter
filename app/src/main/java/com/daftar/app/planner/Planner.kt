package com.daftar.app.planner

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.daftar.app.R
import com.daftar.app.data.json
import com.daftar.app.ui.toast
import com.daftar.app.widget.Widgets
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors

object EventType { const val EXAM = 0; const val ASSIGNMENT = 1; const val MEETING = 2; const val CLASS = 3; const val OTHER = 4 }

@Serializable
data class PlanEvent(
    val id: Long,
    val type: Int,
    val title: String,
    val start: Long,                 // epoch millis
    val end: Long,                   // epoch millis (== start when no duration)
    val allDay: Boolean = false,
    val weekly: Boolean = false,     // repeats every week (timetable classes)
    val until: Long = 0,             // weekly repeat end (0 = forever)
    val location: String = "",
    val link: String = "",
    val description: String = "",
    val reminders: List<Int> = listOf(60),   // minutes before start
    val folder: String = "",         // optional linked library folder (absolute path)
    val done: Boolean = false,       // assignments can be ticked off
    /** The user wants a copy in the phone's calendar (Samsung / Google Calendar). */
    val calendarSync: Boolean = false,
    /** CalendarContract event id of the phone copy (0 = none). Maintained by the store, not by editors. */
    val deviceEventId: Long = 0,
    /** Calendar holding the phone copy (0 = none). */
    val deviceCalendarId: Long = 0,
    /** Set on events copied from the phone calendar so the same item is not imported twice. */
    val importKey: String = "",
)

/** True when two versions differ in anything the phone-calendar copy shows. */
internal fun calendarFieldsDiffer(a: PlanEvent, b: PlanEvent): Boolean =
    a.title != b.title || a.start != b.start || a.end != b.end || a.allDay != b.allDay || a.weekly != b.weekly ||
        a.until != b.until || a.location != b.location || a.link != b.link || a.description != b.description ||
        a.reminders != b.reminders

/** One concrete occurrence (weekly events expand to many). */
data class Occurrence(val event: PlanEvent, val start: Long, val end: Long)

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

/**
 * Planner store: `filesDir/planner.json`, held in memory as a Compose state list.
 * Mutations happen on the main thread; disk writes and alarm scheduling run on one background thread (ordered).
 */
object Planner {
    /** Bumped on every change (Compose observable). */
    var version by mutableIntStateOf(0)
        private set

    private val events = mutableStateListOf<PlanEvent>()
    private lateinit var appCtx: Context
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "planner-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** Start time handed from the Week/Month views to a new event in the editor (consumed once). */
    internal var draftStart: Long? = null

    val isReady get() = ::appCtx.isInitialized

    fun init(ctx: Context) {
        if (isReady) return
        appCtx = ctx.applicationContext
        file = File(appCtx.filesDir, "planner.json")
        CalendarPrefs.init(appCtx)
        // Small file, needed synchronously so Home / widgets can render immediately.
        runCatching {
            if (file.exists()) events.addAll(json.decodeFromString<List<PlanEvent>>(file.readText()))
        }
        synchronized(lock) { saved = events.toList() }
        io.execute {
            Alarms.rescheduleAll(appCtx, snapshot())
            Widgets.refreshNow(appCtx)
        }
    }

    // ---------- read API ----------
    fun all(): List<PlanEvent> = events.toList()
    fun get(id: Long): PlanEvent? = events.firstOrNull { it.id == id }
    /** Thread-safe copy of the last committed list (usable from any thread). */
    internal fun snapshot(): List<PlanEvent> = synchronized(lock) { saved }

    private val lock = Any()
    private var saved: List<PlanEvent> = emptyList()

    // ---------- write API ----------
    fun newId(): Long {
        var id = System.currentTimeMillis()
        while (events.any { it.id == id }) id++
        return id
    }

    /**
     * Insert or replace [e]. The phone-calendar link (deviceEventId / deviceCalendarId) is owned by the store:
     * an editor holding an old copy can never overwrite it. The phone copy follows automatically.
     */
    fun upsert(e: PlanEvent) {
        val i = events.indexOfFirst { it.id == e.id }
        val old = if (i >= 0) events[i] else null
        val merged = if (old != null) e.copy(deviceEventId = old.deviceEventId, deviceCalendarId = old.deviceCalendarId) else e
        if (i >= 0) events[i] = merged else events.add(merged)
        val mirror = (merged.calendarSync || merged.deviceEventId != 0L) &&
            (old == null || old.calendarSync != merged.calendarSync || calendarFieldsDiffer(old, merged) || merged.deviceEventId == 0L)
        changed(listOf(merged.id), mirrorIds = if (mirror) listOf(merged.id) else emptyList())
    }

    /** Adds many new events at once (calendar import). */
    fun addAll(list: List<PlanEvent>) {
        if (list.isEmpty()) return
        events.addAll(list)
        changed(list.map { it.id }, mirrorIds = list.filter { it.calendarSync }.map { it.id })
    }

    fun delete(id: Long) {
        val old = events.firstOrNull { it.id == id }
        events.removeAll { it.id == id }
        changed(listOf(id), removedDevice = mapOf(id to (old?.deviceEventId ?: 0L)))
    }

    fun setDone(id: Long, done: Boolean) {
        val i = events.indexOfFirst { it.id == id }
        if (i < 0) return
        events[i] = events[i].copy(done = done)
        changed(listOf(id))
    }

    /** Turns the phone-calendar copy on/off for these events (on = insert/update, off = remove the copy). */
    fun setCalendarSync(ids: Collection<Long>, on: Boolean) {
        val touched = ArrayList<Long>()
        for (id in ids) {
            val i = events.indexOfFirst { it.id == id }
            if (i < 0) continue
            if (events[i].calendarSync != on) events[i] = events[i].copy(calendarSync = on)
            touched.add(id)
        }
        if (touched.isNotEmpty()) changed(touched, mirrorIds = touched, reschedule = false)
    }

    fun setCalendarSync(id: Long, on: Boolean) = setCalendarSync(listOf(id), on)

    /** Pushes every event that wants a phone copy but has none yet (e.g. after calendar access was granted). */
    fun syncPending() {
        val ids = events.filter { (it.calendarSync && it.deviceEventId == 0L) || (!it.calendarSync && it.deviceEventId != 0L) }.map { it.id }
        if (ids.isNotEmpty()) io.execute { mirrorAll(ids) }
    }

    /**
     * Copies deleted in the calendar app: clear the link and switch sync off for that event
     * (respect the user's deletion; turning the switch on again re-adds it).
     */
    fun reconcileDevice(ctx: Context) {
        val linked = events.filter { it.deviceEventId != 0L }.map { it.id to it.deviceEventId }
        if (linked.isEmpty() || !DeviceCalendar.canRead(ctx)) return
        val c = ctx.applicationContext
        io.execute {
            val alive = DeviceCalendar.existing(c, linked.map { it.second }) ?: return@execute
            val gone = linked.filter { (id, dev) -> dev !in alive && (linkCache[id] ?: dev) == dev }
            if (gone.isEmpty()) return@execute
            gone.forEach { linkCache.remove(it.first) }
            main.post {
                var any = false
                for ((id, dev) in gone) {
                    val i = events.indexOfFirst { it.id == id }
                    if (i >= 0 && events[i].deviceEventId == dev) {
                        events[i] = events[i].copy(calendarSync = false, deviceEventId = 0, deviceCalendarId = 0); any = true
                    }
                }
                if (any) changed(emptyList(), reschedule = false)
            }
        }
    }

    /** Stores the phone-copy link written by the IO thread (no further sync). */
    private fun setLink(id: Long, deviceId: Long, calendarId: Long) {
        val i = events.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = events[i]
        val next = cur.copy(deviceEventId = deviceId, deviceCalendarId = calendarId)
        if (next != cur) { events[i] = next; changed(emptyList(), reschedule = false) }
    }

    // ---------- phone calendar mirror (IO thread only) ----------
    /** Latest device id per event as known by the IO thread (newer than the list while a link write is in flight). */
    private val linkCache = HashMap<Long, Long>()

    private fun mirrorAll(ids: List<Long>) {
        val list = snapshot()
        for (id in ids) list.firstOrNull { it.id == id }?.let { mirror(appCtx, it) }
    }

    private fun mirror(c: Context, e: PlanEvent) {
        val known = linkCache[e.id] ?: e.deviceEventId
        if (!DeviceCalendar.permitted(c)) return // stays pending; syncPending() runs once access is granted
        if (!e.calendarSync) {
            if (known != 0L) {
                DeviceCalendar.delete(c, known)
                linkCache[e.id] = 0L
                main.post { setLink(e.id, 0, 0) }
            }
            return
        }
        val r = DeviceCalendar.push(c, e, known)
        if (r == null) {
            main.post { toast(c, localized(c).getString(R.string.planner_cal_failed)) }
            return
        }
        linkCache[e.id] = r.first
        if (r.first != e.deviceEventId || r.second != e.deviceCalendarId) main.post { setLink(e.id, r.first, r.second) }
    }

    /** Runs [block] on the planner IO thread after all queued writes (used by receivers with goAsync). */
    internal fun afterIo(block: () -> Unit) = io.execute(block)

    /** Reschedule all alarms + widgets (boot, exact-alarm permission granted, time change). */
    fun rescheduleAll(ctx: Context) {
        val list = events.toList()
        val c = ctx.applicationContext
        io.execute { Alarms.rescheduleAll(c, list); Widgets.refreshNow(c) }
    }

    private fun changed(
        ids: List<Long>,
        mirrorIds: List<Long> = emptyList(),
        removedDevice: Map<Long, Long> = emptyMap(),
        reschedule: Boolean = true,
    ) {
        version++
        val list = events.toList()
        synchronized(lock) { saved = list }
        val c = appCtx
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, "planner.json.tmp")
                tmp.writeText(json.encodeToString(list))
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
            if (reschedule) {
                for (id in ids) {
                    val e = list.firstOrNull { it.id == id }
                    if (e == null) Alarms.cancelEvent(c, id) else Alarms.scheduleEvent(c, e)
                }
                Widgets.refreshNow(c, list)
            }
            for ((id, dev) in removedDevice) {
                val d = linkCache.remove(id) ?: dev
                if (d != 0L && DeviceCalendar.permitted(c)) DeviceCalendar.delete(c, d)
            }
            for (id in mirrorIds) list.firstOrNull { it.id == id }?.let { runCatching { mirror(c, it) } }
        }
    }

    // ---------- occurrences ----------
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** All occurrences overlapping [from, to], sorted by start. Includes done assignments. */
    fun occurrences(from: Long, to: Long, list: List<PlanEvent> = all()): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        for (e in list) expand(e, from, to, Int.MAX_VALUE, out)
        out.sortWith(compareBy({ it.start }, { it.end }))
        return out
    }

    /** Next [limit] occurrences whose end ≥ [from], sorted by start; done assignments excluded. */
    fun upcoming(limit: Int, from: Long = System.currentTimeMillis()): List<Occurrence> = upcomingIn(all(), limit, from)

    internal fun upcomingIn(list: List<PlanEvent>, limit: Int, from: Long): List<Occurrence> {
        if (limit <= 0) return emptyList()
        val out = ArrayList<Occurrence>()
        for (e in list) {
            if (e.type == EventType.ASSIGNMENT && e.done) continue
            expand(e, from, Long.MAX_VALUE, limit, out)
        }
        out.sortWith(compareBy({ it.start }, { it.end }))
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** Undone assignments whose due time has passed. */
    fun overdue(now: Long = System.currentTimeMillis()): List<Occurrence> =
        all().filter { it.type == EventType.ASSIGNMENT && !it.done && !it.weekly && it.end < now }
            .sortedBy { it.start }.map { Occurrence(it, it.start, it.end) }

    /**
     * Adds occurrences of [e] that overlap [from, to] (end ≥ from and start ≤ to), at most [max].
     * Weekly events step by calendar weeks in the local zone, so wall-clock time stays fixed across DST.
     */
    internal fun expand(e: PlanEvent, from: Long, to: Long, max: Int, out: MutableList<Occurrence>) {
        val dur = (e.end - e.start).coerceAtLeast(0)
        if (!e.weekly) {
            if (e.end >= from && e.start <= to) out.add(Occurrence(e, e.start, e.end))
            return
        }
        val base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(e.start), zone)
        // Jump close to `from`, then walk.
        var k = if (from > e.end) ((from - e.end) / (7 * DAY) - 1).coerceAtLeast(0) else 0L
        var added = 0
        var guard = 0
        while (added < max && guard < 2000) {
            guard++
            val s = base.plusWeeks(k).toInstant().toEpochMilli()
            k++
            if (s > to) break
            if (e.until > 0 && s > e.until) break
            val en = s + dur
            if (en < from) continue
            out.add(Occurrence(e, s, en)); added++
        }
    }

    /** Occurrence of [e] starting exactly at [start] if it exists (used by notifications). */
    internal fun occurrenceAt(e: PlanEvent, start: Long): Occurrence? {
        val tmp = ArrayList<Occurrence>(1)
        expand(e, start, start, 1, tmp)
        return tmp.firstOrNull { it.start == start } ?: if (!e.weekly) Occurrence(e, e.start, e.end) else null
    }

    // ---------- day helpers ----------
    fun startOfDay(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()
    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    fun today(): LocalDate = LocalDate.now(zone)

    /** Occurrences touching local day [d]. */
    fun onDay(d: LocalDate, list: List<PlanEvent> = all()): List<Occurrence> =
        occurrences(startOfDay(d), startOfDay(d.plusDays(1)) - 1, list)

    internal fun postMain(block: () -> Unit) { main.post(block) }
}
