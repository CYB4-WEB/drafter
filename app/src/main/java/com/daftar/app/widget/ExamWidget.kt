package com.daftar.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.planner.DAY
import com.daftar.app.planner.EventType
import com.daftar.app.planner.HOUR
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.PlanEvent
import com.daftar.app.planner.Planner
import com.daftar.app.planner.fmtTime
import com.daftar.app.planner.localeOf
import com.daftar.app.planner.localized
import com.daftar.app.planner.shortDayLabel
import com.daftar.app.ui.ACTION_PLANNER
import com.daftar.app.ui.EXTRA_ACTION
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Exam countdown" widget (grades-agent): the next 1–3 planner exams with "3 d 4 h", subject colour bar, title and date.
 * Refreshed with every planner change (Widgets.refreshNow) and by a non-wakeup RTC alarm at the moment a countdown label
 * changes — never a frequent or wakeup alarm.
 */
class ExamWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Planner.init(context)
        ExamWidgets.update(context, manager, ids, Planner.snapshot())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Planner.init(context)
        ExamWidgets.update(context, manager, intArrayOf(id), Planner.snapshot())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ExamWidgets.ACTION_TICK) {
            Planner.init(context)
            ExamWidgets.refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDisabled(context: Context) {
        ExamWidgets.cancelTick(context)
    }
}

object ExamWidgets {
    internal const val ACTION_TICK = "com.daftar.app.widget.EXAM_TICK"
    private const val MAX_ROWS = 3
    private val rowIds = intArrayOf(R.id.ex_row0, R.id.ex_row1, R.id.ex_row2)
    private val barIds = intArrayOf(R.id.ex_bar0, R.id.ex_bar1, R.id.ex_bar2)
    private val titleIds = intArrayOf(R.id.ex_title0, R.id.ex_title1, R.id.ex_title2)
    private val subIds = intArrayOf(R.id.ex_sub0, R.id.ex_sub1, R.id.ex_sub2)
    private val iconIds = intArrayOf(R.id.ex_icon0, R.id.ex_icon1, R.id.ex_icon2)
    private val numIds = intArrayOf(R.id.ex_num0, R.id.ex_num1, R.id.ex_num2)
    private val unitIds = intArrayOf(R.id.ex_unit0, R.id.ex_unit1, R.id.ex_unit2)

    /** Re-render every placed exam widget (any thread). */
    fun refresh(ctx: Context, list: List<PlanEvent> = Planner.snapshot()) {
        val c = ctx.applicationContext
        runCatching {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = m.getAppWidgetIds(ComponentName(c, ExamWidget::class.java))
            if (ids.isNotEmpty()) update(c, m, ids, list) else cancelTick(c)
        }
    }

    /** Next [limit] exam occurrences that have not ended yet. */
    fun nextExams(list: List<PlanEvent>, limit: Int, now: Long): List<Occurrence> =
        Planner.upcomingIn(list.filter { it.type == EventType.EXAM }, limit, now)

    private fun launch(ctx: Context, req: Int, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.fill()
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun rowsFor(m: AppWidgetManager, id: Int): Int {
        val h = runCatching { m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) }.getOrDefault(0)
        if (h <= 0) return MAX_ROWS
        return ((h - 46) / 58).coerceIn(1, MAX_ROWS) // card rows: 52dp + 6dp gap
    }

    /** "3 d 4 h", "5 h", "< 1 h", "Now" (whole hours only: the label changes at most hourly). */
    fun countdown(c: Context, start: Long, end: Long, now: Long): String {
        if (now >= start) return c.getString(R.string.grades_cd_now)
        val r = start - now
        val days = (r / DAY).toInt()
        val hours = ((r % DAY) / HOUR).toInt()
        return when {
            days >= 10 -> c.getString(R.string.grades_cd_days, days)
            days > 0 -> if (hours > 0) c.getString(R.string.grades_cd_days_hours, days, hours) else c.getString(R.string.grades_cd_days, days)
            hours > 0 -> c.getString(R.string.grades_cd_hours, hours)
            else -> c.getString(R.string.grades_cd_lt_hour)
        }
    }

    /** When the label for an exam at [start] next changes (strictly after [now]). */
    private fun nextChange(start: Long, end: Long, now: Long): Long {
        if (now >= start) return end + 1000                // "Now" until it ends, then it leaves the list
        val r = start - now
        val step = if (r >= 10 * DAY) DAY else HOUR       // ≥ 10 days the label shows whole days only
        val k = r / step                                   // label changes when the remaining time drops below k*step
        val at = if (k <= 0) start else start - k * step
        return if (at > now) at + 1000 else now + step
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    internal fun update(ctx: Context, m: AppWidgetManager, ids: IntArray, list: List<PlanEvent>) {
        val c = localized(ctx)
        val now = System.currentTimeMillis()
        val items = nextExams(list, MAX_ROWS, now)
        for (wid in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_exams)
            v.setTextViewText(R.id.ex_header, c.getString(R.string.grades_widget_header))
            v.setTextViewText(R.id.ex_empty, c.getString(R.string.grades_widget_empty))
            v.setOnClickPendingIntent(R.id.ex_root, launch(ctx, 7601) { putExtra(EXTRA_ACTION, ACTION_PLANNER) })
            val shown = items.take(rowsFor(m, wid))
            v.setViewVisibility(R.id.ex_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
            for (k in 0 until MAX_ROWS) {
                val o = shown.getOrNull(k)
                if (o == null) { v.setViewVisibility(rowIds[k], View.GONE); continue }
                v.setViewVisibility(rowIds[k], View.VISIBLE)
                // countdown-agent: card row (subject/type colour) with the end number block; tap → live countdown.
                val (num, unit) = WidgetCards.durationBlock(c, o, now)
                WidgetCards.fillRow(ctx, v, intArrayOf(barIds[k], iconIds[k], titleIds[k], subIds[k], numIds[k], unitIds[k]), o, dateLine(c, o), num, unit)
                v.setContentDescription(rowIds[k], o.event.title + ", " + countdown(c, o.start, o.end, now))
                v.setOnClickPendingIntent(rowIds[k], WidgetCards.openCountdown(ctx, 7610 + k, o))
            }
            m.updateAppWidget(wid, v)
        }
        val next = items.minOfOrNull { nextChange(it.start, it.end, now) }
        if (next != null) scheduleTick(ctx, next) else cancelTick(ctx)
    }

    /** "Tue 12 Oct · 09:00 · Hall B" */
    private fun dateLine(c: Context, o: Occurrence): String {
        val d = Instant.ofEpochMilli(o.start).atZone(zone).toLocalDate()
        val today = java.time.LocalDate.now(zone)
        val day = if (d.isAfter(today.plusDays(6)) || d.isBefore(today)) d.format(DateTimeFormatter.ofPattern("EEE d MMM", localeOf(c)))
        else shortDayLabel(c, d, today)
        val parts = mutableListOf(day)
        if (!o.event.allDay) parts.add(fmtTime(c, o.start))
        if (o.event.location.isNotBlank()) parts.add(o.event.location)
        return parts.joinToString(" · ")
    }

    private fun tickIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, ExamWidget::class.java).setAction(ACTION_TICK).setData(Uri.parse("daftar://exam-widget-tick"))
        return PendingIntent.getBroadcast(ctx, 7602, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Non-wakeup alarm: delivered when the device is next awake, so a sleeping phone is never woken for a label. */
    private fun scheduleTick(ctx: Context, at: Long) {
        runCatching { ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, tickIntent(ctx)) }
    }

    internal fun cancelTick(ctx: Context) {
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(tickIntent(ctx)) }
    }
}
