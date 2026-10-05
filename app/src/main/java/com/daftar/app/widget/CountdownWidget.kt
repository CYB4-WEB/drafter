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
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.planner.DAY
import com.daftar.app.planner.EventType
import com.daftar.app.planner.HOUR
import com.daftar.app.planner.MINUTE
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.PlanEvent
import com.daftar.app.planner.Planner
import com.daftar.app.planner.countdown.CdState
import com.daftar.app.planner.countdown.CountdownPrefs
import com.daftar.app.planner.countdown.CountdownStyles
import com.daftar.app.planner.countdown.Subjects
import com.daftar.app.planner.countdown.cardColor
import com.daftar.app.planner.countdown.contentOn
import com.daftar.app.planner.countdown.endLabel
import com.daftar.app.planner.countdown.fmtNumber
import com.daftar.app.planner.countdown.futureCountdowns
import com.daftar.app.planner.countdown.isDone
import com.daftar.app.planner.countdown.nextLabelChange
import com.daftar.app.planner.countdown.occurrenceFor
import com.daftar.app.planner.countdown.shortDate
import com.daftar.app.planner.countdown.stateOf
import com.daftar.app.planner.fmtTime
import com.daftar.app.planner.localized
import com.daftar.app.planner.typeColorArgb
import com.daftar.app.ui.ACTION_ADD_EVENT
import com.daftar.app.ui.EXTRA_ACTION
import com.daftar.app.ui.EXTRA_COUNTDOWN_EVENT
import com.daftar.app.ui.EXTRA_COUNTDOWN_START

/** Card-style rows shared by the Upcoming, Exam and Countdown widgets (countdown-agent). */
internal object WidgetCards {
    fun iconRes(type: Int): Int = when (type) {
        EventType.EXAM -> R.drawable.widget_ic_exam
        EventType.ASSIGNMENT -> R.drawable.widget_ic_assignment
        EventType.MEETING -> R.drawable.widget_ic_meeting
        EventType.CLASS -> R.drawable.widget_ic_class
        else -> R.drawable.widget_ic_other
    }

    /** Card colour (style → subject folder → type) as ARGB. */
    fun colorOf(ctx: Context, e: PlanEvent): Int =
        runCatching { cardColor(e, Subjects.of(e.folder), CountdownStyles.get(ctx, e.id)).toArgb() }.getOrDefault(typeColorArgb(e.type))

    fun fg(argb: Int): Int = contentOn(Color(argb)).toArgb()
    fun soft(argb: Int): Int = (fg(argb) and 0x00FFFFFF) or (0xDD shl 24)

    /** Opens the live countdown of [o]. */
    fun openCountdown(ctx: Context, req: Int, o: Occurrence): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_COUNTDOWN_EVENT, o.event.id).putExtra(EXTRA_COUNTDOWN_START, o.start)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Fills one card row: tinted background, icon, title, subtitle, end block (number may be null). */
    fun fillRow(
        ctx: Context, v: RemoteViews, ids: IntArray, o: Occurrence, sub: String, num: String?, unit: String,
    ) {
        val (bg, icon, title, subId, numId, unitId) = ids.let { Six(it[0], it[1], it[2], it[3], it[4], it[5]) }
        val color = colorOf(ctx, o.event)
        val fg = fg(color)
        v.setInt(bg, "setColorFilter", color)
        v.setImageViewResource(icon, iconRes(o.event.type))
        v.setInt(icon, "setColorFilter", fg)
        v.setTextViewText(title, o.event.title)
        v.setTextColor(title, fg)
        v.setTextViewText(subId, sub)
        v.setTextColor(subId, soft(color))
        v.setViewVisibility(numId, if (num == null) View.GONE else View.VISIBLE)
        v.setTextViewText(numId, num ?: "")
        v.setTextColor(numId, fg)
        v.setTextViewText(unitId, unit)
        v.setTextColor(unitId, fg)
    }

    private data class Six(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int)

    /** Upcoming widget block: calendar days ("3 / days"), "10:00 / Today" later today, "Now" — changes only at midnight / start / end. */
    fun calendarBlock(c: Context, o: Occurrence, now: Long): Pair<String?, String> {
        val l = endLabel(c, o, now, short = true)
        return when (l.state) {
            CdState.TODAY -> if (o.event.allDay) null to l.unit else fmtTime(c, o.start) to c.getString(R.string.cd_today)
            else -> l.number to l.unit
        }
    }

    /** Exam widget block: whole days, else whole hours, else "<1 hour"; "Now" once started (changes at most hourly). */
    fun durationBlock(c: Context, o: Occurrence, now: Long): Pair<String?, String> {
        if (now >= o.start) return null to c.getString(R.string.cd_now)
        val r = o.start - now
        val res = c.resources
        return when {
            r >= DAY -> (r / DAY).let { fmtNumber(c, it) to res.getQuantityString(R.plurals.cd_days, it.toInt()) }
            r >= HOUR -> (r / HOUR).let { fmtNumber(c, it) to res.getQuantityString(R.plurals.cd_hours, it.toInt()) }
            else -> "<" + fmtNumber(c, 1) to res.getQuantityString(R.plurals.cd_hours, 1)
        }
    }
}

/** Single-event countdown widget (2×2 → 4×2). Default: the next exam; the configure activity picks any event. */
class CountdownWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Planner.init(context)
        CountdownWidgets.update(context, manager, ids, Planner.snapshot())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Planner.init(context)
        CountdownWidgets.update(context, manager, intArrayOf(id), Planner.snapshot())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CountdownWidgets.ACTION_TICK) {
            Planner.init(context)
            CountdownWidgets.refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { CountdownPrefs.removeWidget(context, it) }
    }

    override fun onDisabled(context: Context) = CountdownWidgets.cancelTick(context)
}

object CountdownWidgets {
    internal const val ACTION_TICK = "com.daftar.app.widget.COUNTDOWN_TICK"

    fun refresh(ctx: Context, list: List<PlanEvent> = Planner.snapshot()) {
        val c = ctx.applicationContext
        runCatching {
            val m = AppWidgetManager.getInstance(c) ?: return
            val ids = m.getAppWidgetIds(ComponentName(c, CountdownWidget::class.java))
            if (ids.isNotEmpty()) update(c, m, ids, list) else cancelTick(c)
        }
    }

    /** The occurrence widget [wid] shows (null = nothing to count down to). */
    fun occurrenceOf(ctx: Context, wid: Int, list: List<PlanEvent>, now: Long): Occurrence? {
        val choice = CountdownPrefs.widgetEvent(ctx, wid)
        if (choice >= 0 && list.any { it.id == choice }) occurrenceFor(choice, 0L, now)?.let { return it }
        val pool = if (choice == CountdownPrefs.WIDGET_NEXT_ANY) list else list.filter { it.type == EventType.EXAM }
        return futureCountdowns(pool, now).firstOrNull { !isDone(it.event) }
    }

    private fun isWide(m: AppWidgetManager, id: Int): Boolean {
        val w = runCatching { m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) }.getOrDefault(0)
        return w >= 220
    }

    internal fun update(ctx: Context, m: AppWidgetManager, ids: IntArray, list: List<PlanEvent>) {
        val c = localized(ctx)
        val now = System.currentTimeMillis()
        var next = Long.MAX_VALUE
        for (wid in ids) {
            val v = RemoteViews(ctx.packageName, if (isWide(m, wid)) R.layout.widget_countdown else R.layout.widget_countdown_small)
            val choice = CountdownPrefs.widgetEvent(ctx, wid)
            val o = occurrenceOf(ctx, wid, list, now)
            if (o == null) {
                renderEmpty(ctx, c, v, wid, choice)
            } else {
                next = minOf(next, render(ctx, c, v, wid, o, now))
            }
            m.updateAppWidget(wid, v)
        }
        if (next != Long.MAX_VALUE) scheduleTick(ctx, next) else cancelTick(ctx)
    }

    private fun renderEmpty(ctx: Context, c: Context, v: RemoteViews, wid: Int, choice: Long) {
        val color = typeColorArgb(if (choice == CountdownPrefs.WIDGET_NEXT_ANY) EventType.OTHER else EventType.EXAM)
        val fg = WidgetCards.fg(color)
        v.setInt(R.id.cd_bg, "setColorFilter", color)
        v.setImageViewResource(R.id.cd_icon, WidgetCards.iconRes(EventType.EXAM))
        v.setInt(R.id.cd_icon, "setColorFilter", fg)
        v.setInt(R.id.cd_ring, "setColorFilter", fg)
        v.setTextViewText(R.id.cd_title, c.getString(if (choice == CountdownPrefs.WIDGET_NEXT_ANY) R.string.cd_widget_empty_any else R.string.cd_widget_empty))
        v.setTextColor(R.id.cd_title, fg)
        v.setTextViewText(R.id.cd_date, c.getString(R.string.cd_widget_tap_add))
        v.setTextColor(R.id.cd_date, WidgetCards.soft(color))
        v.setViewVisibility(R.id.cd_block, View.GONE)
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_ACTION, ACTION_ADD_EVENT)
        v.setOnClickPendingIntent(R.id.cd_root, PendingIntent.getActivity(ctx, 7800 + (wid and 0xFFF), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    /** Renders [o]; returns when the widget next needs a refresh. */
    private fun render(ctx: Context, c: Context, v: RemoteViews, wid: Int, o: Occurrence, now: Long): Long {
        val e = o.event
        val color = WidgetCards.colorOf(ctx, e)
        val fg = WidgetCards.fg(color)
        val soft = WidgetCards.soft(color)
        v.setInt(R.id.cd_bg, "setColorFilter", color)
        v.setImageViewResource(R.id.cd_icon, WidgetCards.iconRes(e.type))
        v.setInt(R.id.cd_icon, "setColorFilter", fg)
        v.setInt(R.id.cd_ring, "setColorFilter", fg)
        v.setTextViewText(R.id.cd_title, e.title)
        v.setTextColor(R.id.cd_title, fg)
        val date = if (e.allDay) shortDate(c, o.start) else c.getString(R.string.cd_date_time, shortDate(c, o.start), fmtTime(c, o.start))
        v.setTextViewText(R.id.cd_date, date)
        v.setTextColor(R.id.cd_date, soft)
        v.setViewVisibility(R.id.cd_block, View.VISIBLE)
        for (id in intArrayOf(R.id.cd_num, R.id.cd_unit, R.id.cd_chrono)) v.setTextColor(id, fg)
        v.setTextColor(R.id.cd_small, soft)
        v.setOnClickPendingIntent(R.id.cd_root, WidgetCards.openCountdown(ctx, 7700 + (wid and 0xFFF), o))

        val res = c.resources
        val left = o.start - now
        val state = stateOf(o, now)
        fun show(num: String?, unit: String, small: String?) {
            v.setViewVisibility(R.id.cd_chrono, View.GONE)
            v.setChronometer(R.id.cd_chrono, SystemClock.elapsedRealtime(), null, false)
            v.setViewVisibility(R.id.cd_num, if (num == null) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.cd_num, num ?: "")
            v.setTextViewText(R.id.cd_unit, unit)
            v.setViewVisibility(R.id.cd_small, if (small == null) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.cd_small, small ?: "")
        }
        return when {
            state == CdState.FUTURE && left >= DAY -> {
                // Big whole days + small "4 hours" line: changes hourly at most (non-wakeup alarm).
                val days = left / DAY
                val hours = (left % DAY) / HOUR
                show(fmtNumber(c, days), res.getQuantityString(R.plurals.cd_days, days.toInt()),
                    fmtNumber(c, hours) + " " + res.getQuantityString(R.plurals.cd_hours, hours.toInt()))
                val k = left / HOUR
                o.start - k * HOUR + 1000
            }
            state == CdState.FUTURE -> {
                // Under 24 h: the launcher animates a count-down Chronometer — no app wakeups at all.
                v.setViewVisibility(R.id.cd_num, View.GONE)
                v.setViewVisibility(R.id.cd_chrono, View.VISIBLE)
                v.setChronometer(R.id.cd_chrono, SystemClock.elapsedRealtime() + left, null, true)
                v.setChronometerCountDown(R.id.cd_chrono, true)
                v.setTextViewText(R.id.cd_unit, c.getString(R.string.cd_widget_left))
                v.setViewVisibility(R.id.cd_small, View.VISIBLE)
                v.setTextViewText(R.id.cd_small, if (e.allDay) c.getString(R.string.cd_today) else fmtTime(c, o.start))
                o.start + 1000
            }
            else -> {
                val l = endLabel(c, o, now, CountdownStyles.get(ctx, e.id).unit, short = true)
                val unit = if (state == CdState.NOW && o.end > o.start) c.getString(R.string.cd_started) else l.unit
                show(l.number, unit, if (state == CdState.PAST) c.getString(R.string.cd_finished) else null)
                maxOf(nextLabelChange(o, now), now + MINUTE)
            }
        }
    }

    private fun tickIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, CountdownWidget::class.java).setAction(ACTION_TICK).setData(Uri.parse("daftar://countdown-widget-tick"))
        return PendingIntent.getBroadcast(ctx, 7702, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Non-wakeup alarm: delivered when the device is next awake — a sleeping phone is never woken for a label. */
    private fun scheduleTick(ctx: Context, at: Long) {
        runCatching { ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, tickIntent(ctx)) }
    }

    internal fun cancelTick(ctx: Context) {
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(tickIntent(ctx)) }
    }
}
