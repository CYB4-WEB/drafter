package com.daftar.app.planner

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.json
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
)

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

    fun upsert(e: PlanEvent) {
        val i = events.indexOfFirst { it.id == e.id }
        if (i >= 0) events[i] = e else events.add(e)
        changed(listOf(e.id))
    }

    fun delete(id: Long) {
        events.removeAll { it.id == id }
        changed(listOf(id))
    }

    fun setDone(id: Long, done: Boolean) {
        val i = events.indexOfFirst { it.id == id }
        if (i < 0) return
        events[i] = events[i].copy(done = done)
        changed(listOf(id))
    }

    /** Runs [block] on the planner IO thread after all queued writes (used by receivers with goAsync). */
    internal fun afterIo(block: () -> Unit) = io.execute(block)

    /** Reschedule all alarms + widgets (boot, exact-alarm permission granted, time change). */
    fun rescheduleAll(ctx: Context) {
        val list = events.toList()
        val c = ctx.applicationContext
        io.execute { Alarms.rescheduleAll(c, list); Widgets.refreshNow(c) }
    }

    private fun changed(ids: List<Long>) {
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
            for (id in ids) {
                val e = list.firstOrNull { it.id == id }
                if (e == null) Alarms.cancelEvent(c, id) else Alarms.scheduleEvent(c, e)
            }
            Widgets.refreshNow(c, list)
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
