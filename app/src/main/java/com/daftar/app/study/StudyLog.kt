package com.daftar.app.study

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.Storage
import com.daftar.app.data.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/** App-private study folder: `filesDir/study/` (focus log, quizzes). */
internal val studyDir: File get() = File(Storage.appCtx.filesDir, "study").apply { mkdirs() }

const val DAY_MS = 24 * 3600_000L

fun epochDay(t: Long = System.currentTimeMillis()): Long =
    java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

/** Temp file + fsync + rename, so a crash never leaves a half-written JSON. */
internal fun atomicWrite(f: File, text: String) {
    runCatching {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** One finished (or stopped) focus block. [subject] = subject folder path ("" = General). */
@Serializable
data class FocusSession(val subject: String, val start: Long, val minutes: Int)

@Serializable
private data class LogData(val sessions: List<FocusSession> = emptyList())

/** Study history: focus sessions (`filesDir/study/log.json`). Observable via [version]. */
object StudyLog {
    var version by mutableIntStateOf(0)
        private set
    private var data = LogData()
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "study-log").apply { isDaemon = true } }
    private val file get() = File(studyDir, "log.json")

    private fun ensure() {
        if (loaded) return
        loaded = true
        data = runCatching { json.decodeFromString<LogData>(file.readText()) }.getOrDefault(LogData())
    }

    fun sessions(): List<FocusSession> { ensure(); return data.sessions }

    fun addSession(s: FocusSession) {
        ensure()
        if (s.minutes <= 0) return
        data = data.copy(sessions = data.sessions + s)
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

    /** Consecutive days (ending today, or yesterday when today is still empty) with any focus time. */
    fun streak(): Int {
        val active = HashSet<Long>()
        sessions().forEach { active.add(epochDay(it.start)) }
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

/**
 * v3.5: flashcards were removed. Deletes the old card store once (`filesDir/study/cards.json`, `img/`), the
 * daily "cards due" alarm, its notification + channel and its prefs. Safe to call often; does the work only once.
 * Call off the main thread.
 */
object StudyCleanup {
    private const val FLAG = "cardsRemoved"
    @Volatile private var done = false

    fun runOnce(ctx: Context = Storage.appCtx) {
        if (done) return
        val sp = ctx.getSharedPreferences("study", Context.MODE_PRIVATE)
        if (sp.getBoolean(FLAG, false)) { done = true; return }
        runCatching {
            val dir = File(ctx.filesDir, "study")
            File(dir, "cards.json").delete()
            File(dir, "cards.json.tmp").delete()
            File(dir, "img").deleteRecursively()
        }
        runCatching {
            // the old daily reminder alarm (request code 7411, data daftar://study/daily)
            val i = Intent(ctx, StudyReceiver::class.java).setAction("com.daftar.app.study.DAILY").setData(Uri.parse("daftar://study/daily"))
            PendingIntent.getBroadcast(ctx, 7411, i, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                ctx.getSystemService(android.app.AlarmManager::class.java).cancel(it); it.cancel()
            }
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancel(7403)
            if (Build.VERSION.SDK_INT >= 26) nm.deleteNotificationChannel("study_daily")
        }
        sp.edit().remove("daily").remove("dailyAt").putBoolean(FLAG, true).apply()
        done = true
    }
}
