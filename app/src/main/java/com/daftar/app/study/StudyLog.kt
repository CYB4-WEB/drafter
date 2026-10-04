package com.daftar.app.study

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors

/** One finished (or stopped) focus block. [subject] = subject folder path ("" = General). */
@Serializable
data class FocusSession(val subject: String, val start: Long, val minutes: Int)

@Serializable
private data class LogData(
    val sessions: List<FocusSession> = emptyList(),
    /** epochDay → cards reviewed that day. */
    val reviews: Map<Long, Int> = emptyMap(),
)

/** Study history: focus sessions and daily review counts (`filesDir/study/log.json`). Observable via [version]. */
object StudyLog {
    var version by mutableIntStateOf(0)
        private set
    private var data = LogData()
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "study-log").apply { isDaemon = true } }
    private val file get() = File(Flashcards.dir, "log.json")

    private fun ensure() {
        if (loaded) return
        loaded = true
        data = runCatching { json.decodeFromString<LogData>(file.readText()) }.getOrDefault(LogData())
    }

    fun sessions(): List<FocusSession> { ensure(); return data.sessions }
    fun reviews(): Map<Long, Int> { ensure(); return data.reviews }

    fun addSession(s: FocusSession) {
        ensure()
        if (s.minutes <= 0) return
        data = data.copy(sessions = data.sessions + s)
        save()
    }

    fun addReview(n: Int = 1) {
        ensure()
        val d = epochDay()
        data = data.copy(reviews = data.reviews + (d to (data.reviews[d] ?: 0) + n))
        save()
    }

    private fun save() {
        version++
        val snap = data
        io.execute { atomicWrite(file, json.encodeToString(snap)) }
    }

    // ---------------- stats ----------------

    /** Minutes per subject for sessions whose start day is in [fromDay]..[toDay] (epoch days). */
    fun minutesBySubject(fromDay: Long, toDay: Long): Map<String, Int> =
        sessions().filter { epochDay(it.start) in fromDay..toDay }.groupBy { it.subject }.mapValues { (_, l) -> l.sumOf { it.minutes } }

    fun minutesOn(day: Long): Int = sessions().filter { epochDay(it.start) == day }.sumOf { it.minutes }

    fun minutesToday() = minutesOn(epochDay())

    fun reviewedOn(day: Long) = reviews()[day] ?: 0

    /** Consecutive days (ending today, or yesterday when today is still empty) with any focus time or reviews. */
    fun streak(): Int {
        val active = HashSet<Long>()
        sessions().forEach { active.add(epochDay(it.start)) }
        reviews().forEach { (d, n) -> if (n > 0) active.add(d) }
        var d = epochDay()
        if (d !in active) d--
        var n = 0
        while (d in active) { n++; d-- }
        return n
    }

    /** Monday-based (Saturday in some locales) first day of the current week as an epoch day. */
    fun weekStart(): Long {
        val first = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
        var d = today()
        while (d.dayOfWeek != first) d = d.minusDays(1)
        return d.toEpochDay()
    }
}
