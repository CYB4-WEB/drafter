package com.daftar.app.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.theme.D
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

@Composable
internal fun MonthTab(gutter: Dp, selected: LocalDate, onSelect: (LocalDate) -> Unit, onToggleDone: (PlanEvent, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val v = Planner.version
    val now = rememberNow()
    val today = Planner.dateOf(now)
    val locale = LocalConfiguration.current.locales[0]
    var ymText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val ym = YearMonth.parse(ymText)
    val firstDow = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val gridStart = ym.atDay(1).with(TemporalAdjusters.previousOrSame(firstDow))
    val cells = (0 until 42).map { gridStart.plusDays(it.toLong()) }
    val gridEnd = cells.last()

    // types per day for dots
    val dots = remember(v, gridStart) {
        val map = HashMap<LocalDate, MutableSet<Int>>()
        val occ = Planner.occurrences(Planner.startOfDay(gridStart), Planner.startOfDay(gridEnd.plusDays(1)) - 1)
        for (o in occ) {
            if (o.event.type == EventType.ASSIGNMENT && o.event.done) continue
            var d = maxOf(Planner.dateOf(o.start), gridStart)
            val last = minOf(Planner.dateOf(maxOf(o.end, o.start)), gridEnd)
            var guard = 0
            while (!d.isAfter(last) && guard < 42) { map.getOrPut(d) { sortedSetOf() }.add(o.event.type); d = d.plusDays(1); guard++ }
        }
        map
    }
    val dayItems = remember(v, selected) { Planner.onDay(selected) }
    val expanded = LocalWidthClass.current == WidthClass.Expanded
    val cellH = if (LocalWidthClass.current == WidthClass.Compact) 52.dp else 68.dp

    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ymText = ym.minusMonths(1).toString() }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.planner_prev), tint = D.c.ink)
            }
            IconButton(onClick = { ymText = ym.plusMonths(1).toString() }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.planner_next), tint = D.c.ink)
            }
            Text(ym.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)), style = MaterialTheme.typography.titleMedium,
                color = D.c.ink, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            if (ym != YearMonth.from(today) || selected != today) {
                OutlinedButton(
                    onClick = { ymText = YearMonth.from(today).toString(); onSelect(today) },
                    border = androidx.compose.foundation.BorderStroke(1.dp, D.c.line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = D.c.accent),
                ) { Text(stringResource(R.string.planner_today_btn)) }
            }
        }
    }
    val grid: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().card(D.c).padding(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                (0 until 7).forEach { i ->
                    Text(firstDow.plus(i.toLong()).getDisplayName(TextStyle.SHORT, locale), style = MaterialTheme.typography.labelMedium,
                        color = D.c.muted, maxLines = 1, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { d ->
                        val inMonth = d.month == ym.month
                        val sel = d == selected
                        val isToday = d == today
                        Column(
                            Modifier.weight(1f).height(cellH).padding(2.dp)
                                .then(if (sel) Modifier.background(D.c.accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)) else Modifier)
                                .clickable {
                                    onSelect(d)
                                    if (!inMonth) ymText = YearMonth.from(d).toString()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Box(
                                Modifier.size(30.dp).then(if (isToday) Modifier.border(1.5.dp, D.c.accent, CircleShape) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isToday || sel) FontWeight.SemiBold else FontWeight.Normal,
                                    color = when { sel || isToday -> D.c.accent; inMonth -> D.c.ink; else -> D.c.muted.copy(alpha = 0.6f) })
                            }
                            Row(Modifier.height(10.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                dots[d]?.take(4)?.forEach { t -> Box(Modifier.size(6.dp).background(typeColor(t), CircleShape)) }
                            }
                        }
                    }
                }
            }
        }
    }
    val dayList: LazyListScope.() -> Unit = {
        item(key = "dayh") { DayHeader(dayLabel(ctx, selected, today), if (selected == today) D.c.accent else D.c.ink) }
        if (dayItems.isEmpty()) item(key = "dayempty") { EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.planner_no_events_day)) }
        else item(key = "daycard") { DayCard(dayItems, now, onToggleDone) }
    }

    if (expanded) {
        Row(Modifier.fillMaxSize().padding(horizontal = gutter)) {
            Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                header(); Spacer(Modifier.height(8.dp)); grid(); Spacer(Modifier.height(112.dp))
            }
            Spacer(Modifier.width(24.dp))
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 112.dp)) { dayList() }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 112.dp)) {
            item(key = "header") { header() }
            item(key = "grid") { Spacer(Modifier.height(8.dp)); grid() }
            dayList()
        }
    }
}
