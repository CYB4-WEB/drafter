package com.daftar.app.planner

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.daftar.app.DaftarApp
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.ui.ACTION_PLANNER
import com.daftar.app.ui.EXTRA_ACTION
import com.daftar.app.ui.EXTRA_EVENT_ID

/** Builds and posts planner notifications. */
internal object Notify {
    private const val DAILY_ID = 7301

    fun permitted(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** True when the user will actually see notifications (permission + not blocked in system settings). */
    fun enabled(c: Context): Boolean = permitted(c) && NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun notificationId(eventId: Long): Int = (eventId xor (eventId ushr 32)).toInt()

    fun reminder(ctx: Context, e: PlanEvent, occStart: Long) {
        if (!permitted(ctx)) return
        val c = localized(ctx)
        val occ = Planner.occurrenceAt(e, occStart) ?: Occurrence(e, occStart, occStart + (e.end - e.start))
        val nid = notificationId(e.id)
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_EVENT_ID, e.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val text = occurrenceSummary(c, occ)
        val big = buildString {
            append(text)
            if (!e.allDay && e.type != EventType.ASSIGNMENT && occ.end > occ.start) append(" (").append(fmtTimeRange(c, occ)).append(")")
            if (e.description.isNotBlank()) append("\n\n").append(e.description.trim())
        }
        val b = NotificationCompat.Builder(ctx, DaftarApp.CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(e.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setColor(typeColorArgb(e.type))
            .setCategory(if (e.type == EventType.CLASS || e.type == EventType.MEETING) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(occ.start).setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(contentPi)

        if (e.link.isNotBlank()) {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(e.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val linkPi = PendingIntent.getActivity(ctx, nid, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, c.getString(R.string.planner_open_link), linkPi)
        }
        if (e.type == EventType.ASSIGNMENT && !e.done) {
            val done = Intent(ctx, ReminderReceiver::class.java).setAction(Alarms.ACTION_DONE)
                .setData(Uri.parse("daftar://done/${e.id}")).putExtra(Alarms.EXTRA_ID, e.id)
            val donePi = PendingIntent.getBroadcast(ctx, nid, done, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, c.getString(R.string.planner_mark_done), donePi)
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(nid, b.build()) }
    }

    fun cancel(ctx: Context, eventId: Long) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(notificationId(eventId)) }
    }

    /** "Today: 2 classes, 1 exam" + the first items. Posts nothing on an empty day. */
    fun daily(ctx: Context) {
        if (!permitted(ctx)) return
        val c = localized(ctx)
        val items = Planner.onDay(Planner.today(), Planner.snapshot())
            .filter { !(it.event.type == EventType.ASSIGNMENT && it.event.done) }
        if (items.isEmpty()) return
        val counts = AllTypes.mapNotNull { t ->
            val n = items.count { it.event.type == t }
            if (n == 0) null else c.resources.getQuantityString(pluralRes(t), n, n)
        }
        val title = c.getString(R.string.planner_daily_title, counts.joinToString(c.getString(R.string.planner_list_sep)))
        val lines = items.map { o ->
            val time = if (o.event.allDay) c.getString(R.string.planner_all_day) else fmtTime(c, o.start)
            "$time  ${o.event.title}"
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText(c.getString(R.string.planner_more_count, lines.size - 6))
        val open = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ACTION, ACTION_PLANNER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, DAILY_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, DaftarApp.CH_DAILY)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(lines.take(2).joinToString(" · "))
            .setStyle(style)
            .setColor(typeColorArgb(items.first().event.type))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(DAILY_ID, n) }
    }

    private fun pluralRes(type: Int) = when (type) {
        EventType.EXAM -> R.plurals.planner_n_exams
        EventType.ASSIGNMENT -> R.plurals.planner_n_assignments
        EventType.MEETING -> R.plurals.planner_n_meetings
        EventType.CLASS -> R.plurals.planner_n_classes
        else -> R.plurals.planner_n_other
    }
}
