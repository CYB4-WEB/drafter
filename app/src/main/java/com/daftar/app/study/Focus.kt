package com.daftar.app.study

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.planner.localized
import com.daftar.app.ui.EXTRA_ACTION
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Deep-link actions handled by the study screens (sent as [EXTRA_ACTION]). */
const val ACTION_STUDY = "study"
const val ACTION_STUDY_REVIEW = "study_review"

object Phase { const val IDLE = 0; const val FOCUS = 1; const val SHORT = 2; const val LONG = 3 }

/**
 * Timer state, persisted on every change so it survives process death. Time is kept as wall-clock timestamps:
 * nothing ticks in the background; the UI computes the remaining time while visible and an alarm ends the phase.
 */
data class FocusState(
    val phase: Int = Phase.IDLE,
    val running: Boolean = false,
    /** Wall time the phase ends (valid while running). */
    val endsAt: Long = 0,
    /** Remaining ms while paused / waiting to start. */
    val remaining: Long = 0,
    /** Full length of the current phase. */
    val phaseMs: Long = 0,
    /** Focus blocks completed in the current set (long break every N). */
    val cycle: Int = 0,
    val subject: String = "",
    /** Wall time the current focus phase was first started. */
    val started: Long = 0,
) {
    val active get() = phase != Phase.IDLE
    fun remainingAt(now: Long) = if (running) (endsAt - now).coerceAtLeast(0) else remaining
}

/** Study settings (SharedPreferences "study"), Compose-observable. */
object StudyPrefs {
    private val sp: SharedPreferences by lazy { Storage.appCtx.getSharedPreferences("study", Context.MODE_PRIVATE) }

    var focusMin by mutableStateOf(sp.getInt("focus", 25)); private set
    var shortMin by mutableStateOf(sp.getInt("short", 5)); private set
    var longMin by mutableStateOf(sp.getInt("long", 15)); private set
    var longEvery by mutableStateOf(sp.getInt("longEvery", 4)); private set
    var autoStart by mutableStateOf(sp.getBoolean("autoStart", true)); private set
    var dnd by mutableStateOf(sp.getBoolean("dnd", false)); private set
    var lastSubject by mutableStateOf(sp.getString("lastSubject", "") ?: ""); private set
    var dailyReminder by mutableStateOf(sp.getBoolean("daily", false)); private set
    /** Minutes after midnight. */
    var dailyAt by mutableStateOf(sp.getInt("dailyAt", 18 * 60)); private set

    fun putDurations(focus: Int, short: Int, long: Int, every: Int) {
        focusMin = focus.coerceIn(1, 180); shortMin = short.coerceIn(1, 60); longMin = long.coerceIn(1, 90); longEvery = every.coerceIn(2, 10)
        sp.edit().putInt("focus", focusMin).putInt("short", shortMin).putInt("long", longMin).putInt("longEvery", longEvery).apply()
    }
    fun putAutoStart(v: Boolean) { autoStart = v; sp.edit().putBoolean("autoStart", v).apply() }
    fun putDnd(v: Boolean) { dnd = v; sp.edit().putBoolean("dnd", v).apply() }
    fun putLastSubject(v: String) { lastSubject = v; sp.edit().putString("lastSubject", v).apply() }
    fun putDaily(v: Boolean) { dailyReminder = v; sp.edit().putBoolean("daily", v).apply() }
    fun putDailyAt(v: Int) { dailyAt = v; sp.edit().putInt("dailyAt", v).apply() }

    internal fun loadState(): FocusState = FocusState(
        phase = sp.getInt("t.phase", 0), running = sp.getBoolean("t.running", false), endsAt = sp.getLong("t.ends", 0),
        remaining = sp.getLong("t.rem", 0), phaseMs = sp.getLong("t.len", 0), cycle = sp.getInt("t.cycle", 0),
        subject = sp.getString("t.subject", "") ?: "", started = sp.getLong("t.started", 0),
    )

    internal fun saveState(s: FocusState) {
        sp.edit().putInt("t.phase", s.phase).putBoolean("t.running", s.running).putLong("t.ends", s.endsAt).putLong("t.rem", s.remaining)
            .putLong("t.len", s.phaseMs).putInt("t.cycle", s.cycle).putString("t.subject", s.subject).putLong("t.started", s.started).apply()
    }

    internal var dndPrev: Int
        get() = sp.getInt("dndPrev", -1)
        set(v) { sp.edit().putInt("dndPrev", v).apply() }
}

/** The focus / Pomodoro timer. All calls on the main thread. */
object FocusTimer {
    private var loaded = false
    private var _state by mutableStateOf(FocusState())

    val state: FocusState get() { ensure(); return _state }

    private fun ensure() {
        if (loaded) return
        loaded = true
        _state = StudyPrefs.loadState()
    }

    private val ctx get() = Storage.appCtx

    private fun set(s: FocusState) {
        _state = s
        StudyPrefs.saveState(s)
        StudyAlarms.scheduleTimer(ctx, s)
        StudyNotify.timer(ctx, s)
        Dnd.follow(ctx, s)
    }

    fun lengthOf(phase: Int): Long = 60_000L * when (phase) {
        Phase.FOCUS -> StudyPrefs.focusMin
        Phase.SHORT -> StudyPrefs.shortMin
        Phase.LONG -> StudyPrefs.longMin
        else -> 0
    }

    fun start(subject: String = state.subject.ifEmpty { StudyPrefs.lastSubject }) {
        val now = System.currentTimeMillis()
        StudyPrefs.putLastSubject(subject)
        val len = lengthOf(Phase.FOCUS)
        set(state.copy(phase = Phase.FOCUS, running = true, endsAt = now + len, remaining = len, phaseMs = len, subject = subject, started = now))
    }

    fun pause() {
        val s = state
        if (!s.running) return
        set(s.copy(running = false, remaining = s.remainingAt(System.currentTimeMillis())))
    }

    fun resume() {
        val s = state
        if (s.running || !s.active) return
        val now = System.currentTimeMillis()
        set(s.copy(running = true, endsAt = now + s.remaining, started = if (s.phase == Phase.FOCUS && s.started == 0L) now else s.started))
    }

    fun setSubject(subject: String) {
        StudyPrefs.putLastSubject(subject)
        if (state.active) set(state.copy(subject = subject)) else _state = state.copy(subject = subject)
    }

    /** Ends the current phase now: a focus block counts the minutes done so far. */
    fun skip() {
        val s = state
        if (!s.active) return
        val now = System.currentTimeMillis()
        if (s.phase == Phase.FOCUS) record(s, now)
        next(s, now, completed = false, autoStart = true)
    }

    fun stop() {
        val s = state
        if (!s.active) return
        if (s.phase == Phase.FOCUS) record(s, System.currentTimeMillis())
        set(FocusState(subject = s.subject))
    }

    /** Called by the alarm (with the end time it was set for) or by the visible UI when the countdown reaches zero. */
    fun phaseEnded(expectedEnd: Long) {
        val s = state
        if (!s.active || !s.running || s.endsAt != expectedEnd) return
        val now = System.currentTimeMillis()
        if (now < s.endsAt - 1500) return
        if (s.phase == Phase.FOCUS) record(s, now)
        val n = next(s, now, completed = true, autoStart = StudyPrefs.autoStart)
        StudyNotify.phaseDone(ctx, s.phase, n)
    }

    private fun next(s: FocusState, now: Long, completed: Boolean, autoStart: Boolean): FocusState {
        val cycle = if (s.phase == Phase.FOCUS && completed) s.cycle + 1 else s.cycle
        val phase = when (s.phase) {
            Phase.FOCUS -> if (completed && cycle % StudyPrefs.longEvery == 0) Phase.LONG else Phase.SHORT
            else -> Phase.FOCUS
        }
        val len = lengthOf(phase)
        val n = s.copy(
            phase = phase, running = autoStart, endsAt = now + len, remaining = len, phaseMs = len,
            cycle = if (s.phase == Phase.LONG) 0 else cycle, started = if (phase == Phase.FOCUS && autoStart) now else 0,
        )
        set(n)
        return n
    }

    private fun record(s: FocusState, now: Long) {
        val done = s.phaseMs - s.remainingAt(now)
        val min = (done / 60_000L).toInt()
        if (min >= 1) StudyLog.addSession(FocusSession(s.subject, if (s.started > 0) s.started else now - done, min))
    }

    /** Re-arm alarms / notification after a reboot or app update; ends phases that ran out meanwhile. */
    fun restore() {
        val s = state
        if (s.active && s.running && System.currentTimeMillis() >= s.endsAt) phaseEnded(s.endsAt)
        else if (s.active) { StudyAlarms.scheduleTimer(ctx, s); StudyNotify.timer(ctx, s) }
    }
}

/** Display name of a subject path ("" = General). */
fun subjectName(ctx: Context, subject: String): String =
    if (subject.isEmpty()) ctx.getString(R.string.study_general) else File(subject).name

/** "12:34" / "1:02:03". */
fun fmtClock(ms: Long): String {
    val t = (ms + 999) / 1000
    val h = t / 3600; val m = (t / 60) % 60; val s = t % 60
    return if (h > 0) String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(java.util.Locale.getDefault(), "%02d:%02d", m, s)
}

// =====================================================================================
// Alarms
// =====================================================================================

internal object StudyAlarms {
    const val ACTION_PHASE_END = "com.daftar.app.study.PHASE_END"
    const val ACTION_PAUSE = "com.daftar.app.study.PAUSE"
    const val ACTION_RESUME = "com.daftar.app.study.RESUME"
    const val ACTION_SKIP = "com.daftar.app.study.SKIP"
    const val ACTION_STOP = "com.daftar.app.study.STOP"
    const val ACTION_DAILY = "com.daftar.app.study.DAILY"
    const val EXTRA_END = "end"
    private const val RC_TIMER = 7410
    private const val RC_DAILY = 7411

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun timerPi(c: Context, end: Long, flags: Int): PendingIntent? {
        val i = Intent(c, StudyReceiver::class.java).setAction(ACTION_PHASE_END).setData(Uri.parse("daftar://study/timer")).putExtra(EXTRA_END, end)
        return PendingIntent.getBroadcast(c, RC_TIMER, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun scheduleTimer(c: Context, s: FocusState) {
        val m = am(c)
        if (!s.active || !s.running) {
            timerPi(c, 0, PendingIntent.FLAG_NO_CREATE)?.let { m.cancel(it); it.cancel() }
            return
        }
        val pi = timerPi(c, s.endsAt, PendingIntent.FLAG_UPDATE_CURRENT)!!
        try {
            if (Build.VERSION.SDK_INT < 31 || m.canScheduleExactAlarms()) m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endsAt, pi)
            else m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endsAt, pi)
        } catch (_: SecurityException) {
            m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endsAt, pi)
        }
    }

    fun scheduleDaily(c: Context) {
        val i = Intent(c, StudyReceiver::class.java).setAction(ACTION_DAILY).setData(Uri.parse("daftar://study/daily"))
        if (!StudyPrefs.dailyReminder) {
            PendingIntent.getBroadcast(c, RC_DAILY, i, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am(c).cancel(it); it.cancel() }
            return
        }
        val zone = ZoneId.systemDefault()
        val at = LocalTime.of(StudyPrefs.dailyAt / 60, StudyPrefs.dailyAt % 60)
        var next = ZonedDateTime.of(LocalDate.now(zone), at, zone)
        if (!next.toInstant().isAfter(java.time.Instant.now().plusSeconds(5))) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(c, RC_DAILY, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Inexact is fine for a daily nudge (the system batches it with other wakeups).
        am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pi)
    }

    fun actionPi(c: Context, action: String): PendingIntent {
        val i = Intent(c, StudyReceiver::class.java).setAction(action).setData(Uri.parse("daftar://study/$action"))
        return PendingIntent.getBroadcast(c, action.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Timer phase ends, notification actions, the daily "cards due" reminder and re-arming after reboot. */
class StudyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            StudyAlarms.ACTION_PHASE_END -> FocusTimer.phaseEnded(i.getLongExtra(StudyAlarms.EXTRA_END, 0))
            StudyAlarms.ACTION_PAUSE -> FocusTimer.pause()
            StudyAlarms.ACTION_RESUME -> FocusTimer.resume()
            StudyAlarms.ACTION_SKIP -> FocusTimer.skip()
            StudyAlarms.ACTION_STOP -> FocusTimer.stop()
            StudyAlarms.ACTION_DAILY -> {
                if (StudyPrefs.dailyReminder) StudyNotify.due(c.applicationContext, Flashcards.dueCount())
                StudyAlarms.scheduleDaily(c.applicationContext)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                FocusTimer.restore()
                StudyAlarms.scheduleDaily(c.applicationContext)
            }
        }
    }
}

// =====================================================================================
// Notifications
// =====================================================================================

internal object StudyNotify {
    const val CH_TIMER = "study_timer"
    const val CH_ALERT = "study_alert"
    const val CH_DAILY = "study_daily"
    private const val ID_TIMER = 7401
    private const val ID_ALERT = 7402
    private const val ID_DAILY = 7403
    private var channelsMade = false

    fun permitted(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    private fun channels(c: Context) {
        if (channelsMade || Build.VERSION.SDK_INT < 26) return
        channelsMade = true
        val l = localized(c)
        nm(c).createNotificationChannel(NotificationChannel(CH_TIMER, l.getString(R.string.study_ch_timer), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm(c).createNotificationChannel(NotificationChannel(CH_ALERT, l.getString(R.string.study_ch_alert), NotificationManager.IMPORTANCE_HIGH))
        nm(c).createNotificationChannel(NotificationChannel(CH_DAILY, l.getString(R.string.study_ch_daily), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openPi(c: Context, action: String, rc: Int): PendingIntent {
        val i = Intent(c, MainActivity::class.java).putExtra(EXTRA_ACTION, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun phaseLabel(c: Context, phase: Int) = c.getString(when (phase) {
        Phase.FOCUS -> R.string.study_phase_focus
        Phase.SHORT -> R.string.study_phase_short
        Phase.LONG -> R.string.study_phase_long
        else -> R.string.study_focus
    })

    /** Ongoing notification while a phase is active: a system-drawn countdown (no app wakeups) + Pause/Resume, Skip, Stop. */
    fun timer(ctx: Context, s: FocusState) {
        if (!s.active) { runCatching { nm(ctx).cancel(ID_TIMER) }; return }
        if (!permitted(ctx)) return
        channels(ctx)
        val c = localized(ctx)
        val title = phaseLabel(c, s.phase) + " · " + subjectName(c, s.subject)
        val b = NotificationCompat.Builder(ctx, CH_TIMER)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setColor(if (s.phase == Phase.FOCUS) 0xFF3B82F6.toInt() else 0xFF4CC38A.toInt())
            .setContentIntent(openPi(ctx, ACTION_STUDY, ID_TIMER))
        if (s.running) {
            b.setWhen(s.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
                .setContentText(c.getString(R.string.study_notif_until, android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(s.endsAt))))
                .addAction(0, c.getString(R.string.study_pause), StudyAlarms.actionPi(ctx, StudyAlarms.ACTION_PAUSE))
        } else {
            b.setShowWhen(false).setContentText(c.getString(R.string.study_notif_paused, fmtClock(s.remaining)))
                .addAction(0, c.getString(if (s.remaining == s.phaseMs) R.string.study_start else R.string.study_resume), StudyAlarms.actionPi(ctx, StudyAlarms.ACTION_RESUME))
        }
        b.addAction(0, c.getString(R.string.study_skip), StudyAlarms.actionPi(ctx, StudyAlarms.ACTION_SKIP))
        b.addAction(0, c.getString(R.string.study_stop), StudyAlarms.actionPi(ctx, StudyAlarms.ACTION_STOP))
        runCatching { nm(ctx).notify(ID_TIMER, b.build()) }
    }

    /** Heads-up with sound when a phase ends. */
    fun phaseDone(ctx: Context, ended: Int, next: FocusState) {
        if (!permitted(ctx)) return
        channels(ctx)
        val c = localized(ctx)
        val title = c.getString(if (ended == Phase.FOCUS) R.string.study_notif_focus_done else R.string.study_notif_break_done)
        val text = if (next.running) c.getString(R.string.study_notif_next_running, phaseLabel(c, next.phase), fmtClock(next.phaseMs))
        else c.getString(R.string.study_notif_next_ready, phaseLabel(c, next.phase))
        val n = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setTimeoutAfter(5 * 60_000L)
            .setContentIntent(openPi(ctx, ACTION_STUDY, ID_ALERT))
            .build()
        runCatching { nm(ctx).notify(ID_ALERT, n) }
    }

    fun due(ctx: Context, count: Int) {
        if (count <= 0 || !permitted(ctx)) return
        channels(ctx)
        val c = localized(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DAILY)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(c.resources.getQuantityString(R.plurals.study_n_cards_due, count, count))
            .setContentText(c.getString(R.string.study_notif_due_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPi(ctx, ACTION_STUDY_REVIEW, ID_DAILY))
            .addAction(0, c.getString(R.string.study_review), openPi(ctx, ACTION_STUDY_REVIEW, ID_DAILY + 1))
            .build()
        runCatching { nm(ctx).notify(ID_DAILY, n) }
    }
}

// =====================================================================================
// Do Not Disturb (optional; only with notification-policy access)
// =====================================================================================

internal object Dnd {
    fun granted(c: Context): Boolean = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    /** Priority-only during running focus phases; restores the user's previous filter otherwise (only if we changed it). */
    fun follow(c: Context, s: FocusState) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val want = StudyPrefs.dnd && s.phase == Phase.FOCUS && s.running
        runCatching {
            if (want) {
                if (StudyPrefs.dndPrev == -1) {
                    val cur = nm.currentInterruptionFilter
                    if (cur == NotificationManager.INTERRUPTION_FILTER_ALL || cur == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                        StudyPrefs.dndPrev = cur
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    }
                }
            } else if (StudyPrefs.dndPrev != -1) {
                if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                StudyPrefs.dndPrev = -1
            }
        }
    }
}
