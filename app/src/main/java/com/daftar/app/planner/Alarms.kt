package com.daftar.app.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** AlarmManager scheduling for reminders, the daily summary and widget refreshes. */
internal object Alarms {
    const val ACTION_REMIND = "com.daftar.app.planner.REMIND"
    const val ACTION_DAILY = "com.daftar.app.planner.DAILY"
    const val ACTION_REFRESH = "com.daftar.app.planner.REFRESH"
    const val ACTION_DONE = "com.daftar.app.planner.DONE"
    const val EXTRA_ID = "id"
    const val EXTRA_MINUTES = "minutes"
    const val EXTRA_OCC = "occ"

    /** Reminder slots per event that we ever schedule / cancel. */
    private const val MAX_SLOTS = 8
    /** All-day events are reminded relative to 09:00 local of that day. */
    private const val ALL_DAY_BASE = 9 * HOUR

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun canExact(c: Context): Boolean = Build.VERSION.SDK_INT < 31 || am(c).canScheduleExactAlarms()

    fun requestCode(id: Long, slot: Int): Int = ((id xor (id ushr 32)).toInt() * 31) + slot

    private fun reminderIntent(c: Context, id: Long, slot: Int) =
        Intent(c, ReminderReceiver::class.java).setAction(ACTION_REMIND).setData(Uri.parse("daftar://reminder/$id/$slot"))

    private fun set(c: Context, at: Long, pi: PendingIntent, exact: Boolean = true) {
        val m = am(c)
        try {
            if (exact && canExact(c)) m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            m.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Time the reminder for occurrence starting at [occStart] fires. */
    fun triggerAt(e: PlanEvent, occStart: Long, minutes: Int): Long =
        occStart + (if (e.allDay) ALL_DAY_BASE else 0) - minutes * MINUTE

    /** Next (occurrence start) whose reminder [minutes] is still in the future, or null. */
    fun nextOccurrenceFor(e: PlanEvent, minutes: Int, now: Long): Long? {
        val lead = minutes * MINUTE - (if (e.allDay) ALL_DAY_BASE else 0)
        val dur = (e.end - e.start).coerceAtLeast(0)
        val tmp = ArrayList<Occurrence>(4)
        Planner.expand(e, now + lead - dur, Long.MAX_VALUE, 4, tmp)
        return tmp.firstOrNull { triggerAt(e, it.start, minutes) > now }?.start
    }

    fun scheduleEvent(c: Context, e: PlanEvent, now: Long = System.currentTimeMillis()) {
        // Events copied to the phone calendar are reminded by the calendar app only (no double notifications).
        val skip = (e.type == EventType.ASSIGNMENT && e.done) || e.deviceEventId != 0L
        val mins = e.reminders.distinct().take(MAX_SLOTS)
        for (slot in 0 until MAX_SLOTS) {
            val m = mins.getOrNull(slot)
            val occ = if (skip || m == null) null else nextOccurrenceFor(e, m, now)
            if (occ == null || m == null) { cancelSlot(c, e.id, slot); continue }
            val i = reminderIntent(c, e.id, slot).putExtra(EXTRA_ID, e.id).putExtra(EXTRA_MINUTES, m).putExtra(EXTRA_OCC, occ)
            val pi = PendingIntent.getBroadcast(c, requestCode(e.id, slot), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            set(c, triggerAt(e, occ, m), pi)
        }
    }

    private fun cancelSlot(c: Context, id: Long, slot: Int) {
        val pi = PendingIntent.getBroadcast(c, requestCode(id, slot), reminderIntent(c, id, slot),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        am(c).cancel(pi); pi.cancel()
    }

    fun cancelEvent(c: Context, id: Long) { for (s in 0 until MAX_SLOTS) cancelSlot(c, id, s) }

    fun rescheduleAll(c: Context, list: List<PlanEvent>) {
        val now = System.currentTimeMillis()
        for (e in list) runCatching { scheduleEvent(c, e, now) }
        scheduleDaily(c)
    }

    // ---- daily summary (always scheduled; the receiver checks Prefs.dailySummary so toggling needs no extra wiring) ----
    val DAILY_TIME: LocalTime = LocalTime.of(7, 30)

    fun scheduleDaily(c: Context) {
        val zone = ZoneId.systemDefault()
        var next = ZonedDateTime.of(LocalDate.now(zone), DAILY_TIME, zone)
        if (!next.toInstant().isAfter(java.time.Instant.now().plusSeconds(5))) next = next.plusDays(1)
        val i = Intent(c, ReminderReceiver::class.java).setAction(ACTION_DAILY).setData(Uri.parse("daftar://daily"))
        val pi = PendingIntent.getBroadcast(c, 7301, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        set(c, next.toInstant().toEpochMilli(), pi)
    }

    // ---- widget refresh (non-wakeup: delivered when the device is next awake) ----
    fun scheduleWidgetRefresh(c: Context, at: Long) {
        val i = Intent(c, ReminderReceiver::class.java).setAction(ACTION_REFRESH).setData(Uri.parse("daftar://refresh"))
        val pi = PendingIntent.getBroadcast(c, 7302, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { am(c).set(AlarmManager.RTC, at, pi) }
    }
}
