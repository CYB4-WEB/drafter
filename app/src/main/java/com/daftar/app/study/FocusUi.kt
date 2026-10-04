package com.daftar.app.study

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberTickingNow
import com.daftar.app.ui.theme.D
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val FocusColor = Color(0xFF3B82F6)
private val BreakColor = Color(0xFF4CC38A)
private fun phaseColor(p: Int) = if (p == Phase.FOCUS || p == Phase.IDLE) FocusColor else BreakColor

@Composable
private fun phaseText(p: Int) = stringResource(when (p) {
    Phase.FOCUS -> R.string.study_phase_focus
    Phase.SHORT -> R.string.study_phase_short
    Phase.LONG -> R.string.study_phase_long
    else -> R.string.study_ready
})

/** Remaining ms of the timer; ticks every second only while running and visible. Ends the phase when it hits zero on screen. */
@Composable
private fun rememberRemaining(s: FocusState): Long {
    if (!s.running) return s.remaining
    val now = rememberTickingNow(1000)
    val left = s.remainingAt(now)
    LaunchedEffect(s.endsAt, left <= 0) { if (left <= 0) FocusTimer.phaseEnded(s.endsAt) }
    return left
}

/** Starts the timer, asking for the notification permission first on Android 13+ (the countdown lives in a notification). */
@Composable
private fun rememberStarter(): (String) -> Unit {
    val ctx = LocalContext.current
    var subject by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { FocusTimer.start(subject) }
    return { sub ->
        subject = sub
        if (Build.VERSION.SDK_INT >= 33 && !StudyNotify.permitted(ctx)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else FocusTimer.start(sub)
    }
}

// =====================================================================================
// Focus section (Study screen)
// =====================================================================================

@Composable
internal fun FocusSection() {
    val c = D.c
    val s = FocusTimer.state
    val start = rememberStarter()
    var settings by remember { mutableStateOf(false) }
    var subjectMenu by remember { mutableStateOf(false) }
    val subject = if (s.active) s.subject else s.subject.ifEmpty { StudyPrefs.lastSubject }
    val left = rememberRemaining(s)
    val total = if (s.active) s.phaseMs else FocusTimer.lengthOf(Phase.FOCUS)
    val shownLeft = if (s.active) left else total
    val compact = LocalWidthClass.current == WidthClass.Compact

    SectionTitle(stringResource(R.string.study_focus_timer)) {
        IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Tune, stringResource(R.string.study_timer_settings), tint = c.muted) }
    }
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // subject picker
        Box {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).clickable { subjectMenu = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(subjectColor(subject), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(subjectLabel(subject), style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp))
                Icon(Icons.Rounded.ArrowDropDown, null, tint = c.muted)
            }
            DropdownMenu(subjectMenu, { subjectMenu = false }) {
                (listOf("") + rememberSubjects().map { it.path }).forEach { p ->
                    DropdownMenuItem({ Text(subjectLabel(p)) }, { subjectMenu = false; FocusTimer.setSubject(p) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(subjectColor(p), CircleShape)) },
                        trailingIcon = { if (p == subject) Icon(Icons.Rounded.Check, null, tint = c.accent) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TimerRing(shownLeft, total, phaseColor(s.phase), if (compact) 180.dp else 200.dp) {
            Text(fmtClock(shownLeft), style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold), color = c.ink)
            Text(if (s.active && !s.running && s.remaining < s.phaseMs) stringResource(R.string.study_paused) else phaseText(s.phase),
                style = MaterialTheme.typography.labelLarge, color = if (s.active) phaseColor(s.phase) else c.muted)
        }
        Spacer(Modifier.height(10.dp))
        // cycle dots
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(StudyPrefs.longEvery) { i ->
                Box(Modifier.size(8.dp).background(if (i < s.cycle % StudyPrefs.longEvery || (s.phase == Phase.LONG)) FocusColor else c.line, CircleShape))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!s.active) {
                Button(onClick = { start(subject) }, Modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.study_start_focus))
                }
            } else {
                CircleAction(Icons.Rounded.Stop, stringResource(R.string.study_stop)) { FocusTimer.stop() }
                Button(onClick = { if (s.running) FocusTimer.pause() else FocusTimer.resume() }, Modifier.height(48.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                    Icon(if (s.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (s.running) R.string.study_pause else if (s.remaining < s.phaseMs) R.string.study_resume else R.string.study_start))
                }
                CircleAction(Icons.Rounded.SkipNext, stringResource(R.string.study_skip)) { FocusTimer.skip() }
            }
        }
        Text(stringResource(R.string.study_timer_summary, StudyPrefs.focusMin, StudyPrefs.shortMin, StudyPrefs.longMin, StudyPrefs.longEvery),
            style = MaterialTheme.typography.bodySmall, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
    if (settings) FocusSettingsDialog { settings = false }
}

@Composable
private fun CircleAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = D.c
    Box(Modifier.size(48.dp).clip(CircleShape).background(c.surfaceAlt).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = c.ink)
    }
}

@Composable
private fun TimerRing(left: Long, total: Long, color: Color, size: Dp, content: @Composable ColumnScope.() -> Unit) {
    val c = D.c
    val track = c.line
    val frac = if (total > 0) (left.toFloat() / total).coerceIn(0f, 1f) else 1f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = 10.dp.toPx()
            val inset = w / 2
            val sz = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
            drawArc(color, -90f, 360f * frac, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

// =====================================================================================
// Timer settings
// =====================================================================================

@Composable
internal fun FocusSettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    var focus by remember { mutableIntStateOf(StudyPrefs.focusMin) }
    var short by remember { mutableIntStateOf(StudyPrefs.shortMin) }
    var long by remember { mutableIntStateOf(StudyPrefs.longMin) }
    var every by remember { mutableIntStateOf(StudyPrefs.longEvery) }
    var auto by remember { mutableStateOf(StudyPrefs.autoStart) }
    var dnd by remember { mutableStateOf(StudyPrefs.dnd && Dnd.granted(ctx)) }
    var askDnd by remember { mutableStateOf(false) }

    StudyDialog(stringResource(R.string.study_timer_settings), onDismiss, buttons = {
        TextButton(onClick = { focus = 25; short = 5; long = 15; every = 4 }) { Text(stringResource(R.string.study_defaults)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        Button(onClick = {
            StudyPrefs.putDurations(focus, short, long, every); StudyPrefs.putAutoStart(auto); StudyPrefs.putDnd(dnd)
            Dnd.follow(ctx, FocusTimer.state)
            onDismiss()
        }, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_save)) }
    }) {
        Stepper(stringResource(R.string.study_focus_len), focus, 5, 5..120) { focus = it }
        Stepper(stringResource(R.string.study_short_len), short, 1, 1..30) { short = it }
        Stepper(stringResource(R.string.study_long_len), long, 5, 5..60) { long = it }
        Stepper(stringResource(R.string.study_long_every), every, 1, 2..8, unit = false) { every = it }
        SwitchLine(stringResource(R.string.study_auto_start), stringResource(R.string.study_auto_start_desc), auto) { auto = it }
        SwitchLine(stringResource(R.string.study_dnd), stringResource(R.string.study_dnd_desc), dnd) { on ->
            if (on && !Dnd.granted(ctx)) askDnd = true else dnd = on
        }
    }
    if (askDnd) ConfirmDialog(stringResource(R.string.study_dnd), stringResource(R.string.study_dnd_ask), stringResource(R.string.study_open_settings),
        onDismiss = { askDnd = false }) {
        askDnd = false
        StudyPrefs.putDnd(true) // takes effect once access is granted
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }
}

@Composable
private fun Stepper(label: String, value: Int, step: Int, range: IntRange, unit: Boolean = true, onChange: (Int) -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Rounded.Remove, null, tint = c.ink) }
        Text(if (unit) stringResource(R.string.study_minutes_short, value) else "$value", color = c.ink, style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 64.dp))
        IconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Rounded.Add, null, tint = c.ink) }
    }
}

@Composable
private fun SwitchLine(title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink)
            if (desc != null) Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

// =====================================================================================
// Stats
// =====================================================================================

@Composable
internal fun StatsSection() {
    val c = D.c
    var week by remember { mutableStateOf(false) }
    val lv = StudyLog.version
    val today = epochDay()
    val from = if (week) StudyLog.weekStart() else today
    val bySubject = remember(lv, week, today) { StudyLog.minutesBySubject(from, today).entries.sortedByDescending { it.value } }
    val days = remember(lv, today) { (6 downTo 0).map { today - it } }
    val perDay = remember(lv, today) { days.map { d -> StudyLog.sessions().filter { epochDay(it.start) == d }.groupBy { it.subject }.mapValues { e -> e.value.sumOf { it.minutes } } } }
    val reviewedToday = StudyLog.reviewedOn(today)
    val reviewedWeek = remember(lv, today) { (StudyLog.weekStart()..today).sumOf { StudyLog.reviewedOn(it) } }
    val streak = remember(lv, today) { StudyLog.streak() }
    val totalMin = bySubject.sumOf { it.value }

    SectionTitle(stringResource(R.string.study_stats)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(stringResource(R.string.study_today), !week, { week = false })
            Chip(stringResource(R.string.study_this_week), week, { week = true })
        }
    }
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(stringResource(R.string.study_minutes_short, totalMin), stringResource(R.string.study_focused), Modifier.weight(1f))
            StatTile("${if (week) reviewedWeek else reviewedToday}", stringResource(R.string.study_reviewed), Modifier.weight(1f))
            StatTile(pluralStringResource(R.plurals.study_n_days, streak, streak), stringResource(R.string.study_streak_label), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.study_last_7_days), style = MaterialTheme.typography.labelMedium, color = c.muted)
        Spacer(Modifier.height(8.dp))
        WeekChart(days, perDay)
        Spacer(Modifier.height(16.dp))
        if (bySubject.isEmpty()) Text(stringResource(R.string.study_no_focus_yet), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        else {
            val max = bySubject.first().value.coerceAtLeast(1)
            bySubject.forEach { (subj, min) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(subjectLabel(subj), style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(110.dp))
                    val col = subjectColor(subj)
                    val track = c.surfaceAlt
                    Canvas(Modifier.weight(1f).height(12.dp)) {
                        val r = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(track, cornerRadius = r)
                        val w = size.width * min / max
                        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                        drawRoundRect(col, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = Size(w, size.height), cornerRadius = r)
                    }
                    Text(stringResource(R.string.study_minutes_short, min), style = MaterialTheme.typography.labelMedium, color = c.muted,
                        textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    val c = D.c
    Column(modifier.background(c.surfaceAlt, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Seven columns (oldest first; mirrored in RTL), stacked by subject colour. */
@Composable
private fun WeekChart(days: List<Long>, perDay: List<Map<String, Int>>) {
    val c = D.c
    val max = perDay.maxOf { it.values.sum() }.coerceAtLeast(30)
    val colors = perDay.map { m -> m.entries.sortedBy { it.key }.map { subjectColor(it.key) to it.value } }
    val track = c.surfaceAlt
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val n = days.size
        val slot = size.width / n
        val bw = (slot * 0.5f).coerceAtMost(28.dp.toPx())
        val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
        for (i in 0 until n) {
            val col = if (rtl) n - 1 - i else i
            val x = col * slot + (slot - bw) / 2
            drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(6.dp.toPx()))
            var y = size.height
            colors[i].forEach { (color, m) ->
                val h = size.height * m / max
                drawRect(color, Offset(x, y - h), Size(bw, h))
                y -= h
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        days.forEach { d ->
            val ld = LocalDate.ofEpochDay(d)
            Text(ld.dayOfWeek.getDisplayName(TextStyle.NARROW_STANDALONE, Locale.getDefault()), style = MaterialTheme.typography.bodySmall,
                color = if (d == epochDay()) c.ink else c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
}

// =====================================================================================
// Home card
// =====================================================================================

/** Home screen card: today's due cards + Review, focus minutes today, start / control the focus timer. */
@Composable
fun StudyHomeCard() {
    val c = D.c
    // Deep links from study notifications arrive as MainActivity.pendingAction (routed to Home).
    val pending = MainActivity.pendingAction.value
    var handled by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if ((pending == ACTION_STUDY || pending == ACTION_STUDY_REVIEW) && handled != pending) {
            handled = pending
            MainActivity.pendingAction.value = null
            Nav.tab(Screen.Study)
            if (pending == ACTION_STUDY_REVIEW) Nav.push(Screen.Review(null))
        }
    }
    val v = Flashcards.version
    val now = rememberTickingNow(60_000)
    val due = remember(v, now) { Flashcards.dueCount(null, now) }
    val minutes = remember(StudyLog.version, now / 60_000) { StudyLog.minutesToday() }
    val s = FocusTimer.state
    val start = rememberStarter()

    SectionTitle(stringResource(R.string.study_title)) {
        TextButton(onClick = { Nav.tab(Screen.Study) }) { Text(stringResource(R.string.study_open)) }
    }
    Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Nav.tab(Screen.Study) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeIcon(Icons.Rounded.Style, c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pluralStringResource(R.plurals.study_n_cards_due, due, due), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1)
                Text(stringResource(R.string.study_focus_today, minutes), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
            }
            if (due > 0) Button(onClick = { pane.push(Screen.Review(null)) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                contentPadding = PaddingValues(horizontal = 14.dp)) { Text(stringResource(R.string.study_review)) }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeIcon(Icons.Rounded.Timer, phaseColor(s.phase))
            Spacer(Modifier.width(12.dp))
            if (s.active) {
                val left = rememberRemaining(s)
                Column(Modifier.weight(1f)) {
                    Text(fmtClock(left), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(phaseText(s.phase) + " · " + subjectLabel(s.subject), style = MaterialTheme.typography.bodySmall, color = c.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { if (s.running) FocusTimer.pause() else FocusTimer.resume() }) {
                    Icon(if (s.running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, stringResource(if (s.running) R.string.study_pause else R.string.study_resume), tint = c.ink)
                }
                IconButton(onClick = { FocusTimer.stop() }) { Icon(Icons.Rounded.Stop, stringResource(R.string.study_stop), tint = c.ink) }
            } else {
                val subj = StudyPrefs.lastSubject
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.study_focus_timer), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1)
                    Text(stringResource(R.string.study_minutes_short, StudyPrefs.focusMin) + " · " + subjectLabel(subj), style = MaterialTheme.typography.bodySmall,
                        color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { start(subj) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.study_start))
                }
            }
        }
    }
}

@Composable
private fun HomeIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Small pill for an app-wide overlay while the timer runs (e.g. top-centre of AppShell). Tap opens Study.
 * Renders nothing when the timer is idle or the Study screen is already showing.
 */
@Composable
fun FocusChip(modifier: Modifier = Modifier) {
    val s = FocusTimer.state
    if (!s.active || Nav.current == Screen.Study) return
    val c = D.c
    val left = rememberRemaining(s)
    Row(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
        .clickable { Nav.tab(Screen.Study) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(phaseColor(s.phase), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(fmtClock(left), style = MaterialTheme.typography.labelLarge, color = c.ink)
        if (!s.running) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Pause, null, tint = c.muted, modifier = Modifier.size(16.dp)) }
    }
}

// =====================================================================================
// Settings section (embedded by the lead in Settings)
// =====================================================================================

@Composable
fun StudySettingsSection() {
    val ctx = LocalContext.current
    val c = D.c
    var timer by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val at = java.time.LocalTime.of(StudyPrefs.dailyAt / 60, StudyPrefs.dailyAt % 60)
    val atText = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(
        java.time.LocalDate.now().atTime(at).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))

    SectionTitle(stringResource(R.string.study_title))
    Column(Modifier.fillMaxWidth().card(c)) {
        SetRow(Icons.Rounded.NotificationsActive, stringResource(R.string.study_daily_reminder), stringResource(R.string.study_daily_reminder_desc, atText),
            switch = StudyPrefs.dailyReminder) {
            val on = !StudyPrefs.dailyReminder
            StudyPrefs.putDaily(on)
            StudyAlarms.scheduleDaily(ctx)
            if (on && Build.VERSION.SDK_INT >= 33 && !StudyNotify.permitted(ctx)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (StudyPrefs.dailyReminder) SetRow(Icons.Rounded.Schedule, stringResource(R.string.study_reminder_time), atText) {
            android.app.TimePickerDialog(ctx, { _, h, m -> StudyPrefs.putDailyAt(h * 60 + m); StudyAlarms.scheduleDaily(ctx) },
                StudyPrefs.dailyAt / 60, StudyPrefs.dailyAt % 60, android.text.format.DateFormat.is24HourFormat(ctx)).show()
        }
        SetRow(Icons.Rounded.Timer, stringResource(R.string.study_focus_timer),
            stringResource(R.string.study_timer_summary, StudyPrefs.focusMin, StudyPrefs.shortMin, StudyPrefs.longMin, StudyPrefs.longEvery)) { timer = true }
    }
    if (timer) FocusSettingsDialog { timer = false }
}

@Composable
private fun SetRow(icon: ImageVector, title: String, desc: String, switch: Boolean? = null, onClick: () -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink)
            Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (switch != null) Switch(switch, { onClick() }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}
