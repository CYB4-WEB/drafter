package com.daftar.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.planner.Alarms
import com.daftar.app.planner.PlanEvent
import com.daftar.app.planner.Planner
import com.daftar.app.planner.localized
import com.daftar.app.planner.occurrenceSummary
import com.daftar.app.ui.ACTION_ADD_EVENT
import com.daftar.app.ui.ACTION_IMPORT
import com.daftar.app.ui.ACTION_NEW_NOTE
import com.daftar.app.ui.ACTION_PLANNER
import com.daftar.app.ui.EXTRA_ACTION
import java.time.LocalDate
import java.time.ZoneId

/** "Upcoming" widget: next planner items (4×2, resizable). */
class UpcomingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Planner.init(context)
        Widgets.updateUpcoming(context, manager, ids, Planner.snapshot())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        Planner.init(context)
        Widgets.updateUpcoming(context, manager, intArrayOf(id), Planner.snapshot())
    }
}

/** "Daftar shortcuts" widget: New note · Planner · Import (4×1). */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widgets.updateQuick(context, manager, ids)
    }
}

object Widgets {
    private val rowIds = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3)
    private val barIds = intArrayOf(R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3)
    private val iconIds = intArrayOf(R.id.icon0, R.id.icon1, R.id.icon2, R.id.icon3)
    private val titleIds = intArrayOf(R.id.title0, R.id.title1, R.id.title2, R.id.title3)
    private val subIds = intArrayOf(R.id.sub0, R.id.sub1, R.id.sub2, R.id.sub3)
    private val numIds = intArrayOf(R.id.num0, R.id.num1, R.id.num2, R.id.num3)
    private val unitIds = intArrayOf(R.id.unit0, R.id.unit1, R.id.unit2, R.id.unit3)

    /** Refresh every placed widget (call after any planner change; safe from any thread). */
    fun refresh(ctx: Context) = refreshNow(ctx.applicationContext)

    fun refreshNow(ctx: Context, list: List<PlanEvent> = Planner.snapshot()) {
        runCatching {
            val m = AppWidgetManager.getInstance(ctx) ?: return
            val up = m.getAppWidgetIds(ComponentName(ctx, UpcomingWidget::class.java))
            if (up.isNotEmpty()) updateUpcoming(ctx, m, up, list)
            val q = m.getAppWidgetIds(ComponentName(ctx, QuickWidget::class.java))
            if (q.isNotEmpty()) updateQuick(ctx, m, q)
            ExamWidgets.refresh(ctx, list) // grades-agent: exam countdown widget follows every planner change
            CountdownWidgets.refresh(ctx, list) // countdown-agent: single-event countdown widget
        }
    }

    private fun launch(ctx: Context, req: Int, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.fill()
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun rowsFor(m: AppWidgetManager, id: Int): Int {
        val h = runCatching { m.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) }.getOrDefault(0)
        if (h <= 0) return 4
        return ((h - 52) / 58).coerceIn(1, 4) // card rows: 52dp + 6dp gap
    }

    internal fun updateUpcoming(ctx: Context, m: AppWidgetManager, ids: IntArray, list: List<PlanEvent>) {
        val c = localized(ctx)
        val now = System.currentTimeMillis()
        val items = Planner.upcomingIn(list, 4, now)
        for (wid in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_upcoming)
            v.setTextViewText(R.id.header, c.getString(R.string.widget_upcoming))
            v.setTextViewText(R.id.add, c.getString(R.string.add_short))
            v.setTextViewText(R.id.empty, c.getString(R.string.nothing_upcoming))
            v.setOnClickPendingIntent(R.id.root, launch(ctx, 7401) { putExtra(EXTRA_ACTION, ACTION_PLANNER) })
            v.setOnClickPendingIntent(R.id.add, launch(ctx, 7402) { putExtra(EXTRA_ACTION, ACTION_ADD_EVENT) })
            val shown = items.take(rowsFor(m, wid))
            v.setViewVisibility(R.id.empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
            for (k in 0 until 4) {
                val o = shown.getOrNull(k)
                if (o == null) { v.setViewVisibility(rowIds[k], View.GONE); continue }
                v.setViewVisibility(rowIds[k], View.VISIBLE)
                // countdown-agent: colour card row with the end number block; tap → live countdown.
                val (num, unit) = WidgetCards.calendarBlock(c, o, now)
                WidgetCards.fillRow(ctx, v, intArrayOf(barIds[k], iconIds[k], titleIds[k], subIds[k], numIds[k], unitIds[k]),
                    o, occurrenceSummary(c, o, withLocation = false), num, unit)
                v.setOnClickPendingIntent(rowIds[k], WidgetCards.openCountdown(ctx, 7410 + k, o))
            }
            m.updateAppWidget(wid, v)
        }
        if (ids.isNotEmpty()) {
            // Next refresh: when a shown item ends (it leaves the list) or at midnight ("Today"/"Tomorrow" labels).
            val zone = ZoneId.systemDefault()
            val midnight = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Block labels ("Today" → "Now") also change when a shown item starts.
            val nextEnd = items.flatMap { listOf(it.start + 1000, it.end + 1000) }.filter { it > now }.minOrNull() ?: Long.MAX_VALUE
            Alarms.scheduleWidgetRefresh(ctx, minOf(midnight + 1000, nextEnd))
        }
    }

    internal fun updateQuick(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        val c = localized(ctx)
        for (wid in ids) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_quick)
            v.setTextViewText(R.id.q_note, c.getString(R.string.new_note))
            v.setTextViewText(R.id.q_planner, c.getString(R.string.planner))
            v.setTextViewText(R.id.q_import, c.getString(R.string.import_file))
            v.setOnClickPendingIntent(R.id.q_note, launch(ctx, 7501) { putExtra(EXTRA_ACTION, ACTION_NEW_NOTE) })
            v.setOnClickPendingIntent(R.id.q_planner, launch(ctx, 7502) { putExtra(EXTRA_ACTION, ACTION_PLANNER) })
            v.setOnClickPendingIntent(R.id.q_import, launch(ctx, 7503) { putExtra(EXTRA_ACTION, ACTION_IMPORT) })
            m.updateAppWidget(wid, v)
        }
    }
}
