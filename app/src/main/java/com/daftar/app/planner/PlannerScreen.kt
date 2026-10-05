package com.daftar.app.planner

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.daftar.app.R
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Tiny planner-local preferences (shared Prefs is owned by the lead). */
internal object PlannerLocalPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("planner", Context.MODE_PRIVATE)
    fun askedNotif(c: Context) = sp(c).getBoolean("askedNotif", false)
    fun setAskedNotif(c: Context) = sp(c).edit().putBoolean("askedNotif", true).apply()
    fun askedCalendar(c: Context) = sp(c).getBoolean("askedCalendar", false)
    fun setAskedCalendar(c: Context) = sp(c).edit().putBoolean("askedCalendar", true).apply()
}

internal fun openLink(ctx: Context, link: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: ActivityNotFoundException) { toast(ctx, ctx.getString(R.string.no_app_found)) }
    catch (_: SecurityException) { toast(ctx, ctx.getString(R.string.no_app_found)) }
}

/** Current time, ticking on each minute boundary while composed. */
@Composable
internal fun rememberNow(): Long = com.daftar.app.ui.rememberTickingNow(MINUTE)

@Composable
fun PlannerScreen() {
    val ctx = LocalContext.current
    val compact = LocalWidthClass.current == WidthClass.Compact
    val gutter = if (compact) D.gutter else D.gutterWide
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var monthSelected by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var notifOk by remember { mutableStateOf(Notify.enabled(ctx)) }
    var exactOk by remember { mutableStateOf(Alarms.canExact(ctx)) }
    LifecycleResumeEffect(Unit) {
        notifOk = Notify.enabled(ctx)
        val was = exactOk
        exactOk = Alarms.canExact(ctx)
        if (!was && exactOk) Planner.rescheduleAll(ctx) // upgrade inexact alarms to exact
        if (DeviceCalendar.permitted(ctx)) {
            Planner.syncPending()          // access granted later in system settings
            Planner.reconcileDevice(ctx)   // copies deleted in the calendar app
        }
        onPauseOrDispose { }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = Notify.enabled(ctx) }
    val v = Planner.version
    val hasReminders = remember(v) { Planner.all().any { it.reminders.isNotEmpty() && !(it.type == EventType.ASSIGNMENT && it.done) } }
    LaunchedEffect(hasReminders) {
        if (hasReminders && Build.VERSION.SDK_INT >= 33 && !Notify.permitted(ctx) && !PlannerLocalPrefs.askedNotif(ctx)) {
            PlannerLocalPrefs.setAskedNotif(ctx)
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val doneMsg = stringResource(R.string.planner_marked_done)
    val undo = stringResource(R.string.planner_undo)
    val onToggleDone: (PlanEvent, Boolean) -> Unit = { e, d ->
        Planner.setDone(e.id, d)
        if (d) scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(doneMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) Planner.setDone(e.id, false)
        }
    }

    var dialog by rememberSaveable { mutableIntStateOf(0) } // 1 sync, 2 import, 3 export

    Box(Modifier.fillMaxSize().background(D.c.bg)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // Header: title + segmented tabs (same row on wide screens) + overflow menu.
            val tabs = listOf(R.string.planner_tab_agenda, R.string.planner_tab_week, R.string.planner_tab_month)
            if (compact) {
                Row(Modifier.fillMaxWidth().padding(start = gutter, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.planner_title), style = MaterialTheme.typography.headlineSmall, color = D.c.ink, modifier = Modifier.weight(1f))
                    CountdownsButton()
                    PlannerMenu { dialog = it }
                }
                Segmented(tabs.map { stringResource(it) }, tab, { tab = it }, Modifier.padding(horizontal = gutter).fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.planner_title), style = MaterialTheme.typography.displaySmall, color = D.c.ink, modifier = Modifier.weight(1f))
                    Segmented(tabs.map { stringResource(it) }, tab, { tab = it }, Modifier.widthIn(max = 420.dp))
                    Spacer(Modifier.width(4.dp))
                    CountdownsButton()
                    PlannerMenu { dialog = it }
                }
            }
            if (hasReminders && !notifOk) {
                Banner(Icons.Rounded.NotificationsOff, stringResource(R.string.planner_notif_banner), stringResource(R.string.planner_turn_on), gutter) {
                    if (Build.VERSION.SDK_INT >= 33 && !Notify.permitted(ctx) && !PlannerLocalPrefs.askedNotif(ctx)) {
                        PlannerLocalPrefs.setAskedNotif(ctx); permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        runCatching { ctx.startActivity(i) }.onFailure {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
                        }
                    }
                }
            } else if (hasReminders && !exactOk && Build.VERSION.SDK_INT >= 31) {
                Banner(Icons.Rounded.AlarmOff, stringResource(R.string.planner_exact_banner), stringResource(R.string.planner_allow), gutter) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> AgendaTab(gutter, onToggleDone)
                    1 -> WeekTab(gutter)
                    else -> MonthTab(gutter, LocalDate.ofEpochDay(monthSelected), { monthSelected = it.toEpochDay() }, onToggleDone)
                }
            }
        }

        val fabPad = if (compact) Modifier else Modifier.windowInsetsPadding(WindowInsets.navigationBars)
        FloatingActionButton(
            onClick = {
                val sel = LocalDate.ofEpochDay(monthSelected)
                Planner.draftStart = if (tab == 2 && sel != Planner.today())
                    sel.atTime(LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() else null
                pane.push(Screen.EditEvent(null))
            },
            shape = CircleShape, containerColor = D.c.accent, contentColor = D.c.onAccent,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = Modifier.align(Alignment.BottomEnd).then(fabPad).padding(gutter),
        ) { Icon(Icons.Rounded.Add, stringResource(R.string.add_event)) }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).then(fabPad).padding(bottom = 88.dp, start = gutter, end = gutter))
    }

    when (dialog) {
        1 -> CalendarSyncDialog { dialog = 0 }
        2 -> ImportCalendarDialog { dialog = 0 }
        3 -> ExportIcsDialog { dialog = 0 }
    }
}

/** countdown-agent: header entry to the Countdowns list. */
@Composable
private fun CountdownsButton() {
    IconButton(onClick = { pane.push(Screen.Countdowns) }) {
        Icon(Icons.Rounded.HourglassTop, stringResource(R.string.cd_countdowns), tint = D.c.ink)
    }
}

/** Header overflow: Sync with device calendar · Import from calendar · Export .ics. */
@Composable
private fun PlannerMenu(onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.planner_more), tint = D.c.ink) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.planner_menu_sync)) },
                leadingIcon = { Icon(Icons.Rounded.Sync, null, tint = D.c.muted) },
                trailingIcon = if (CalendarPrefs.mode == CalendarPrefs.ALWAYS) { { Icon(Icons.Rounded.Check, null, tint = D.c.accent) } } else null,
                onClick = { open = false; onPick(1) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.planner_menu_import)) },
                leadingIcon = { Icon(Icons.Rounded.Download, null, tint = D.c.muted) },
                onClick = { open = false; onPick(2) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.planner_menu_export)) },
                leadingIcon = { Icon(Icons.Rounded.IosShare, null, tint = D.c.muted) },
                onClick = { open = false; onPick(3) },
            )
        }
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: String, gutter: Dp, onAction: () -> Unit) {
    Row(
        Modifier.padding(start = gutter, end = gutter, top = 12.dp).fillMaxWidth().card(D.c, 12.dp).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = D.c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = D.c.ink, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = onAction) { Text(action, color = D.c.accent) }
    }
}

/** Flat segmented control: surfaceAlt track, selected segment = surface with line border. */
@Composable
internal fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.background(D.c.surfaceAlt, RoundedCornerShape(12.dp)).padding(4.dp)) {
        labels.forEachIndexed { i, l ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp)
                    .then(if (sel) Modifier.background(D.c.surface, RoundedCornerShape(10.dp)).border(1.dp, D.c.line, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, style = MaterialTheme.typography.labelLarge, color = if (sel) D.c.ink else D.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------- Agenda ----------------

@Composable
private fun AgendaTab(gutter: Dp, onToggleDone: (PlanEvent, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val v = Planner.version
    val now = rememberNow()
    val today = Planner.dateOf(now)
    var days by rememberSaveable { mutableIntStateOf(60) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    val todayStart = Planner.startOfDay(today)
    val horizonEnd = Planner.startOfDay(today.plusDays(days.toLong())) - 1
    val all = remember(v, today, days) { Planner.occurrences(todayStart, horizonEnd) }
    val hasDone = all.any { it.event.type == EventType.ASSIGNMENT && it.event.done }
    val items = all.filter { showDone || !(it.event.type == EventType.ASSIGNMENT && it.event.done) }
    val overdue = remember(v, today) { Planner.overdue(todayStart) }
    val more = remember(v, days, today) { Planner.upcomingIn(Planner.all(), 1, horizonEnd + 1).isNotEmpty() }
    val grouped = remember(items) {
        items.groupBy { Planner.dateOf(maxOf(it.start, todayStart)) }.toSortedMap()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 112.dp)) {
        if (overdue.isNotEmpty()) {
            item(key = "overdue_h") { DayHeader(stringResource(R.string.planner_overdue), D.c.danger) }
            item(key = "overdue") { DayCard(overdue, now, onToggleDone) }
        }
        if (hasDone) item(key = "filters") {
            Row(Modifier.padding(top = 8.dp)) {
                Chip(stringResource(R.string.planner_show_done), showDone, { showDone = !showDone }, leading = Icons.Rounded.TaskAlt)
            }
        }
        if (grouped.isEmpty() && overdue.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.planner_empty), Modifier.padding(top = 48.dp)) {
                    Button(onClick = { pane.push(Screen.EditEvent(null)) }, colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent)) {
                        Text(stringResource(R.string.add_event))
                    }
                }
            }
        }
        grouped.forEach { (day, list) ->
            item(key = "h$day") { DayHeader(dayLabel(ctx, day, today), if (day == today) D.c.accent else D.c.ink) }
            item(key = "d$day") { DayCard(list, now, onToggleDone) }
        }
        if (more) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { days += 60 }) { Text(stringResource(R.string.planner_show_more), color = D.c.accent) }
            }
        }
    }
}

@Composable
internal fun DayHeader(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp))
}

@Composable
internal fun DayCard(list: List<Occurrence>, now: Long, onToggleDone: (PlanEvent, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().card(D.c)) {
        list.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(color = D.c.line, thickness = 1.dp, modifier = Modifier.padding(start = 28.dp))
            EventRow(o, now, onToggleDone)
        }
    }
}

/** Agenda row: colour bar, title, time · type · location, link button, done checkbox. */
@Composable
internal fun EventRow(o: Occurrence, now: Long, onToggleDone: (PlanEvent, Boolean) -> Unit) {
    val ctx = LocalContext.current
    val e = o.event
    val done = e.type == EventType.ASSIGNMENT && e.done
    val past = o.end < now && !(e.type == EventType.ASSIGNMENT && !e.done)
    var menu by remember { mutableStateOf(false) } // countdown-agent: long-press → "Show countdown"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .combinedClickable(onClick = { pane.push(Screen.EditEvent(e.id)) }, onLongClick = { menu = true })
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).alpha(if (past || done) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EventLongPressMenu(o, menu) { menu = false }
        Box(Modifier.width(4.dp).height(40.dp).background(typeColor(e.type), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = D.c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (done) TextDecoration.LineThrough else null,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val parts = buildList {
                    add(if (e.type == EventType.ASSIGNMENT && !e.allDay) stringResource(R.string.planner_due_at, fmtTime(ctx, o.start)) else fmtTimeRange(ctx, o))
                    add(stringResource(typeLabelRes(e.type)))
                    if (e.location.isNotBlank()) add(e.location)
                }
                if (e.weekly) {
                    Icon(Icons.Rounded.Repeat, stringResource(R.string.planner_weekly), tint = D.c.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (e.link.isNotBlank()) {
            IconButton(onClick = { openLink(ctx, e.link) }) {
                Icon(Icons.Rounded.Link, stringResource(R.string.planner_open_link), tint = D.c.accent)
            }
        }
        if (e.type == EventType.ASSIGNMENT) {
            Checkbox(
                checked = e.done, onCheckedChange = { onToggleDone(e, it) },
                colors = CheckboxDefaults.colors(checkedColor = D.c.accent, uncheckedColor = D.c.muted, checkmarkColor = D.c.onAccent),
            )
        } else Spacer(Modifier.width(8.dp))
    }
}

/** countdown-agent: long-press menu on planner events — Show countdown · Edit event. */
@Composable
internal fun EventLongPressMenu(o: Occurrence, open: Boolean, onClose: () -> Unit) {
    DropdownMenu(open, onClose) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_show_countdown)) },
            leadingIcon = { Icon(Icons.Rounded.HourglassTop, null, tint = D.c.muted) },
            onClick = { onClose(); pane.push(Screen.Countdown(o.event.id, o.start)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_edit_event)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = D.c.muted) },
            onClick = { onClose(); pane.push(Screen.EditEvent(o.event.id)) },
        )
    }
}
