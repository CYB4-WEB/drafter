package com.daftar.app.planner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.daftar.app.data.Prefs
import com.daftar.app.widget.Widgets

/**
 * Handles every planner alarm: reminders, the daily summary, widget refresh ticks,
 * and the "Mark done" notification action. (Application.onCreate has already run Planner.init.)
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ctx = c.applicationContext
        Planner.init(ctx)
        when (i.action) {
            Alarms.ACTION_REMIND -> {
                val id = i.getLongExtra(Alarms.EXTRA_ID, 0)
                val occ = i.getLongExtra(Alarms.EXTRA_OCC, 0)
                val e = Planner.get(id) ?: return
                if (!(e.type == EventType.ASSIGNMENT && e.done)) Notify.reminder(ctx, e, occ)
                // Weekly events: arm the next week's reminder (and keep other slots consistent).
                val pr = goAsync()
                Planner.afterIo {
                    try { Alarms.scheduleEvent(ctx, e); Widgets.refreshNow(ctx) } finally { pr.finish() }
                }
            }
            Alarms.ACTION_DAILY -> {
                if (Prefs.dailySummary) Notify.daily(ctx)
                val pr = goAsync()
                Planner.afterIo { try { Alarms.scheduleDaily(ctx); Widgets.refreshNow(ctx) } finally { pr.finish() } }
            }
            Alarms.ACTION_REFRESH -> {
                val pr = goAsync()
                Planner.afterIo { try { Widgets.refreshNow(ctx) } finally { pr.finish() } }
            }
            Alarms.ACTION_DONE -> {
                val id = i.getLongExtra(Alarms.EXTRA_ID, 0)
                Notify.cancel(ctx, id)
                Planner.setDone(id, true)
                val pr = goAsync()
                Planner.afterIo { pr.finish() } // runs after the queued save
            }
        }
    }
}

/** Alarms are cleared on reboot (and on time/zone changes they may be off): reschedule everything. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ctx = c.applicationContext
        Planner.init(ctx)
        Planner.rescheduleAll(ctx)
        val pr = goAsync()
        Planner.afterIo { pr.finish() }
    }
}
