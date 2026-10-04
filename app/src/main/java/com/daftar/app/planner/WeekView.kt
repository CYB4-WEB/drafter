package com.daftar.app.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

private const val PAGES = 10_000
private const val CENTER = PAGES / 2
private val GUTTER_W = 52.dp

@Composable
internal fun WeekTab(gutter: Dp) {
    val compact = LocalWidthClass.current == WidthClass.Compact
    val n = if (compact) 3 else 7
    val locale = LocalConfiguration.current.locales[0]
    val firstDow = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val today = Planner.dateOf(rememberNow())
    val anchor = if (compact) today else today.with(TemporalAdjusters.previousOrSame(firstDow))
    key(n, anchor) {
        val pager = rememberPagerState(initialPage = CENTER) { PAGES }
        val scope = rememberCoroutineScope()
        fun pageStart(p: Int): LocalDate = anchor.plusDays((p - CENTER).toLong() * n)
        val cur = pageStart(pager.currentPage)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter - 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.planner_prev), tint = D.c.ink)
                }
                IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.planner_next), tint = D.c.ink)
                }
                Text(rangeLabel(cur, cur.plusDays(n - 1L), locale), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pager.currentPage != CENTER) {
                    OutlinedButton(
                        onClick = { scope.launch { pager.animateScrollToPage(CENTER) } },
                        border = androidx.compose.foundation.BorderStroke(1.dp, D.c.line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = D.c.accent),
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(if (compact) R.string.planner_today_btn else R.string.planner_this_week)) }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { it }) { p ->
                WeekPage(pageStart(p), n, today, gutter)
            }
        }
    }
}

private fun rangeLabel(a: LocalDate, b: LocalDate, locale: java.util.Locale): String {
    val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    return when {
        a.year != b.year -> "${a.format(full)} – ${b.format(full)}"
        a.month != b.month -> "${a.format(DateTimeFormatter.ofPattern("d MMM", locale))} – ${b.format(full)}"
        else -> "${a.dayOfMonth} – ${b.format(full)}"
    }
}

/** A segment of an occurrence clipped to one day, with its overlap lane. */
private data class Block(val o: Occurrence, val startMin: Int, val endMin: Int, var lane: Int = 0, var lanes: Int = 1)

private fun layoutLanes(blocks: List<Block>): List<Block> {
    val sorted = blocks.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
    val cluster = ArrayList<Block>()
    var clusterEnd = -1
    val laneEnds = ArrayList<Int>()
    fun flush() {
        val k = laneEnds.size.coerceAtLeast(1)
        cluster.forEach { it.lanes = k }
        cluster.clear(); laneEnds.clear()
    }
    for (b in sorted) {
        if (b.startMin >= clusterEnd && cluster.isNotEmpty()) flush()
        var lane = laneEnds.indexOfFirst { it <= b.startMin }
        if (lane < 0) { lane = laneEnds.size; laneEnds.add(b.endMin) } else laneEnds[lane] = b.endMin
        b.lane = lane
        cluster.add(b)
        clusterEnd = maxOf(clusterEnd, b.endMin)
    }
    if (cluster.isNotEmpty()) flush()
    return sorted
}

@Composable
private fun WeekPage(start: LocalDate, n: Int, today: LocalDate, gutter: Dp) {
    val ctx = LocalContext.current
    val v = Planner.version
    val now = rememberNow()
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val days = (0 until n).map { start.plusDays(it.toLong()) }
    val occ = remember(v, start, n) { Planner.occurrences(Planner.startOfDay(start), Planner.startOfDay(start.plusDays(n.toLong())) - 1) }

    // Per-day timed blocks + all-day items.
    val perDay = remember(occ) {
        days.map { d ->
            val ds = Planner.startOfDay(d); val de = Planner.startOfDay(d.plusDays(1))
            val list = occ.filter { !it.event.allDay && it.start < de && maxOf(it.end, it.start + 1) > ds }
            layoutLanes(list.map { o ->
                val s = ((maxOf(o.start, ds) - ds) / MINUTE).toInt()
                val visualEnd = maxOf(o.end, o.start + 30 * MINUTE)
                val e = ((minOf(visualEnd, de) - ds) / MINUTE).toInt().coerceAtLeast(s + 15)
                Block(o, s, e)
            })
        }
    }
    val allDay = remember(occ) {
        days.map { d ->
            val ds = Planner.startOfDay(d); val de = Planner.startOfDay(d.plusDays(1))
            occ.filter { it.event.allDay && it.start < de && it.end >= ds }
        }
    }
    val blocks = perDay.flatten()
    val minH = minOf(7, blocks.minOfOrNull { it.startMin / 60 } ?: 7)
    val maxH = maxOf(22, blocks.maxOfOrNull { (it.endMin + 59) / 60 } ?: 22).coerceAtMost(24)
    val hours = maxH - minH

    Column(Modifier.fillMaxSize().padding(horizontal = gutter - 8.dp)) {
        // Day headers
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
            Spacer(Modifier.width(GUTTER_W))
            days.forEach { d ->
                val isToday = d == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.format(DateTimeFormatter.ofPattern("EEE", locale)), style = MaterialTheme.typography.labelMedium,
                        color = if (isToday) D.c.accent else D.c.muted, maxLines = 1)
                    Box(
                        Modifier.padding(top = 2.dp).size(32.dp).then(if (isToday) Modifier.background(D.c.accent, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium,
                            color = if (isToday) D.c.onAccent else D.c.ink)
                    }
                }
            }
        }
        // All-day strip
        if (allDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Box(Modifier.width(GUTTER_W).padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(stringResource(R.string.planner_all_day), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 2, textAlign = TextAlign.End)
                }
                allDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(2).forEach { o ->
                            val c = typeColor(o.event.type)
                            Text(
                                o.event.title, style = MaterialTheme.typography.labelMedium, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().background(c.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                                    .clickable { Nav.push(Screen.EditEvent(o.event.id)) }.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                        if (list.size > 2) Text(stringResource(R.string.planner_more_count, list.size - 2), style = MaterialTheme.typography.bodySmall, color = D.c.muted,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val hourH = maxOf(if (LocalWidthClass.current == WidthClass.Compact) 52.dp else 48.dp, maxHeight / hours)
            val scroll = rememberScrollState()
            val density = LocalDensity.current
            LaunchedEffect(start) {
                val nowH = LocalTime.now().hour
                val firstEvent = blocks.minOfOrNull { it.startMin / 60 }
                val target = (if (days.contains(today)) nowH - 1 else firstEvent ?: 8).coerceIn(minH, maxH)
                scroll.scrollTo(with(density) { (hourH * (target - minH)).roundToPx() })
            }
            Row(Modifier.fillMaxWidth().verticalScroll(scroll).height(hourH * hours)) {
                // Hour labels
                Column(Modifier.width(GUTTER_W).fillMaxHeight()) {
                    for (h in minH until maxH) {
                        Box(Modifier.height(hourH).fillMaxWidth().padding(end = 6.dp, top = 2.dp), contentAlignment = Alignment.TopEnd) {
                            Text(LocalTime.of(h, 0).format(timeFormatter(ctx)), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1)
                        }
                    }
                }
                days.forEachIndexed { i, d ->
                    DayColumn(d, perDay[i], minH, hourH, if (d == today) now else null, zone, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun DayColumn(d: LocalDate, blocks: List<Block>, minH: Int, hourH: Dp, now: Long?, zone: ZoneId, modifier: Modifier) {
    val ctx = LocalContext.current
    val line = D.c.line
    val density = LocalDensity.current
    val hourPx = with(density) { hourH.toPx() }
    val tint = if (now != null) D.c.accent.copy(alpha = 0.04f) else androidx.compose.ui.graphics.Color.Transparent
    BoxWithConstraints(
        modifier.background(tint)
            .drawBehind {
                // hour lines + start-side column divider (mirrors in RTL)
                var y = 0f
                while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += hourPx }
                val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - 0.5f else 0.5f
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            .pointerInput(d, minH, hourPx) {
                detectTapGestures { pos ->
                    val minutes = (minH * 60 + (pos.y / hourPx * 60).toInt()) / 30 * 30
                    Planner.draftStart = d.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
                    Nav.push(Screen.EditEvent(null))
                }
            },
    ) {
        val colW = maxWidth
        blocks.forEach { b ->
            val top = hourH * ((b.startMin - minH * 60) / 60f)
            val h = maxOf(hourH * ((b.endMin - b.startMin) / 60f), 22.dp)
            val w = colW / b.lanes
            val e = b.o.event
            val c = typeColor(e.type)
            val done = e.type == EventType.ASSIGNMENT && e.done
            Row(
                Modifier.offset(x = w * b.lane, y = top).width(w).height(h).padding(1.dp)
                    .background(c.copy(alpha = if (D.c.dark) 0.30f else 0.20f), RoundedCornerShape(6.dp))
                    .clickable { Nav.push(Screen.EditEvent(e.id)) }
                    .alpha(if (done) 0.5f else 1f),
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c, RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)))
                Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(e.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = D.c.ink,
                        maxLines = if (h > 60.dp) 3 else 1, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) TextDecoration.LineThrough else null)
                    if (h >= 44.dp) Text(fmtTime(ctx, b.o.start), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1)
                    if (h >= 64.dp && e.location.isNotBlank()) Text(e.location, style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (now != null) {
            val mins = ((now - d.atStartOfDay(zone).toInstant().toEpochMilli()) / MINUTE).toInt()
            val y = hourH * ((mins - minH * 60) / 60f)
            if (mins >= minH * 60 && y <= maxHeight) {
                Box(Modifier.offset(y = y - 4.dp).fillMaxWidth().height(8.dp)) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(D.c.danger))
                    Box(Modifier.align(Alignment.CenterStart).size(8.dp).background(D.c.danger, CircleShape))
                }
            }
        }
    }
}
