package com.daftar.app.planner

import com.daftar.app.ui.pane
import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.folderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private data class FolderOption(val path: String, val label: String, val color: Int, val icon: String)

/** Full-screen add/edit event. id null = new event; presetType -1 = none. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EditEventScreen(id: Long?, presetType: Int) {
    val ctx = LocalContext.current
    val existing = remember(id) { id?.let { Planner.get(it) } }
    val compact = LocalWidthClass.current == WidthClass.Compact
    val gutter = if (compact) D.gutter else D.gutterWide

    if (id != null && existing == null) {
        Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(stringResource(R.string.planner_edit_event), onBack = { pane.back() })
            EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.planner_not_found), Modifier.padding(top = 48.dp)) {
                TextButton(onClick = { pane.back() }) { Text(stringResource(R.string.back), color = D.c.accent) }
            }
        }
        return
    }

    val zone = ZoneId.systemDefault()
    val initStart = remember {
        existing?.start ?: Planner.draftStart.also { Planner.draftStart = null }
            ?: ZonedDateTime.now(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
    }
    val initZ = Instant.ofEpochMilli(initStart).atZone(zone)
    val initType = existing?.type ?: if (presetType in 0..4) presetType else EventType.OTHER

    var type by rememberSaveable { mutableIntStateOf(initType) }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var allDay by rememberSaveable { mutableStateOf(existing?.allDay ?: false) }
    var date by rememberSaveable { mutableLongStateOf(initZ.toLocalDate().toEpochDay()) }
    var startMin by rememberSaveable { mutableIntStateOf(initZ.hour * 60 + initZ.minute) }
    var endMin by rememberSaveable {
        mutableIntStateOf(
            if (existing != null && !existing.allDay && existing.end > existing.start) {
                val ez = Instant.ofEpochMilli(existing.end).atZone(zone)
                if (ez.toLocalDate() != initZ.toLocalDate()) 23 * 60 + 59 else ez.hour * 60 + ez.minute
            } else minOf(initZ.hour * 60 + initZ.minute + 60, 23 * 60 + 59)
        )
    }
    var weekly by rememberSaveable { mutableStateOf(existing?.weekly ?: (initType == EventType.CLASS)) }
    var weeklyTouched by rememberSaveable { mutableStateOf(existing != null) }
    var until by rememberSaveable { mutableLongStateOf(existing?.until?.takeIf { it > 0 }?.let { Planner.dateOf(it).toEpochDay() } ?: -1L) }
    var location by rememberSaveable { mutableStateOf(existing?.location ?: "") }
    var link by rememberSaveable { mutableStateOf(existing?.link ?: "") }
    var description by rememberSaveable { mutableStateOf(existing?.description ?: "") }
    var remindersCsv by rememberSaveable { mutableStateOf((existing?.reminders ?: defaultReminders(initType)).joinToString(",")) }
    var remindersTouched by rememberSaveable { mutableStateOf(existing != null) }
    var folder by rememberSaveable { mutableStateOf(existing?.folder ?: "") }
    // Phone calendar copy: per event; new events default from the remembered choice (Always = on).
    var calSync by rememberSaveable { mutableStateOf(existing?.calendarSync ?: (CalendarPrefs.mode == CalendarPrefs.ALWAYS)) }
    var calTouched by rememberSaveable { mutableStateOf(false) }

    var titleError by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf(false) }
    var timeError by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showUntil by remember { mutableStateOf(false) }
    var timeDialog by remember { mutableIntStateOf(0) } // 1 start, 2 end
    var confirmDelete by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val folders by produceState(emptyList<FolderOption>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val root = Storage.root.absolutePath
                Storage.allFolders().filter { it.absolutePath != root }.map { f ->
                    val m = Storage.meta(f)
                    FolderOption(f.absolutePath, f.absolutePath.removePrefix(root).trim('/', '\\').replace("/", " / "), m.color, m.icon)
                }.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    val reminders = remindersCsv.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    val isAssignment = type == EventType.ASSIGNMENT

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pane.back() }
    val access = rememberCalendarAccess()
    /** Event id waiting for the "Also add to your phone's calendar?" answer (after saving a new event). */
    var askId by remember { mutableStateOf<Long?>(null) }
    var askReminders by remember { mutableStateOf(false) }
    val addedMsg = stringResource(R.string.planner_cal_added)

    // Where the phone copy lives (null = no live copy). Also catches copies deleted in the calendar app.
    val linkedId = existing?.deviceEventId ?: 0L
    val calName by produceState<String?>(null, linkedId) {
        if (linkedId == 0L) { value = null; return@produceState }
        value = withContext(Dispatchers.IO) { DeviceCalendar.calendarNameOf(ctx, linkedId) }
        if (value == null && DeviceCalendar.canRead(ctx)) {
            Planner.reconcileDevice(ctx)
            if (!calTouched) calSync = false
        }
    }

    /** Leaves the editor, asking for the notification permission once when the event has reminders. */
    fun leave(hasReminders: Boolean) {
        if (hasReminders && Build.VERSION.SDK_INT >= 33 && !Notify.permitted(ctx) && !PlannerLocalPrefs.askedNotif(ctx)) {
            PlannerLocalPrefs.setAskedNotif(ctx)
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) // pops in the result callback
        } else pane.back()
    }

    /** Explain → calendar permission → insert the copy; denied → the calendar app's own "new event" screen. */
    fun addToPhone(id: Long, hasReminders: Boolean) {
        access.request(allowFallback = true) { r ->
            when (r) {
                CalAccess.GRANTED -> {
                    Planner.setCalendarSync(id, true)
                    toast(ctx, addedMsg)
                    leave(hasReminders)
                }
                CalAccess.DENIED -> {
                    // Not kept in sync (no access), so the switch goes back off; the user confirms in the calendar app.
                    if (Planner.get(id)?.calendarSync == true) Planner.setCalendarSync(id, false)
                    Planner.get(id)?.let { DeviceCalendar.insertViaApp(ctx, it) }
                    pane.back()
                }
                CalAccess.CANCELLED -> {
                    if (Planner.get(id)?.calendarSync == true) Planner.setCalendarSync(id, false)
                    leave(hasReminders)
                }
            }
        }
    }

    fun save() {
        val t = title.trim()
        titleError = t.isEmpty()
        val l = normalizeLink(link)
        linkError = l == null
        val d = LocalDate.ofEpochDay(date)
        timeError = !allDay && !isAssignment && endMin < startMin
        if (titleError || linkError || timeError) return
        val s: Long; val e: Long
        if (allDay) {
            s = Planner.startOfDay(d); e = Planner.startOfDay(d.plusDays(1)) - 1
        } else {
            s = ZonedDateTime.of(d, LocalTime.of(startMin / 60, startMin % 60), zone).toInstant().toEpochMilli()
            e = if (isAssignment) s else ZonedDateTime.of(d, LocalTime.of(endMin / 60, endMin % 60), zone).toInstant().toEpochMilli()
        }
        val u = if (weekly && until >= 0) Planner.startOfDay(LocalDate.ofEpochDay(maxOf(until, date)).plusDays(1)) - 1 else 0L
        val ev = PlanEvent(
            id = existing?.id ?: Planner.newId(), type = type, title = t, start = s, end = e, allDay = allDay,
            weekly = weekly, until = u, location = location.trim(), link = l!!, description = description.trim(),
            reminders = reminders.sorted(), folder = folder, done = existing?.done ?: false,
            calendarSync = calSync, importKey = existing?.importKey ?: "",
        )
        Planner.upsert(ev) // the store keeps the phone copy in step (insert / update / remove)
        val hasReminders = ev.reminders.isNotEmpty()
        when {
            existing == null && !calTouched && CalendarPrefs.mode == CalendarPrefs.ASK -> { askReminders = hasReminders; askId = ev.id }
            ev.calendarSync && !DeviceCalendar.permitted(ctx) -> addToPhone(ev.id, hasReminders)
            else -> {
                if (ev.calendarSync && existing?.calendarSync != true) toast(ctx, addedMsg)
                leave(hasReminders)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(D.c.bg)) {
        ViewerTopBar(
            stringResource(if (existing == null) R.string.planner_new_event else R.string.planner_edit_event),
            onBack = { pane.back() },
        ) {
            if (existing != null) com.daftar.app.grades.GradeItButton(existing) // grades-agent: past exams → "Grade it"
            if (existing != null) IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), tint = D.c.muted)
            }
            Button(
                onClick = ::save, modifier = Modifier.padding(end = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent),
            ) { Text(stringResource(R.string.save)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = gutter, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Type
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AllTypes.forEach { t ->
                        Chip(stringResource(typeLabelRes(t)), type == t, {
                            type = t
                            if (!weeklyTouched && existing == null) weekly = t == EventType.CLASS
                            if (!remindersTouched && existing == null) remindersCsv = defaultReminders(t).joinToString(",")
                        }, leading = typeIcon(t), tint = typeColor(t))
                    }
                }
                // Title
                OutlinedTextField(
                    title, { title = it; if (it.isNotBlank()) titleError = false },
                    label = { Text(stringResource(R.string.planner_field_title)) },
                    isError = titleError,
                    supportingText = if (titleError) { { Text(stringResource(R.string.planner_title_required)) } } else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                // When
                Column(Modifier.fillMaxWidth().card(D.c)) {
                    SwitchRow(Icons.Rounded.WbSunny, stringResource(R.string.planner_all_day), allDay) { allDay = it }
                    RowDivider()
                    ValueRow(Icons.Rounded.CalendarToday, stringResource(R.string.planner_date), fmtDate(ctx, LocalDate.ofEpochDay(date))) { showDate = true }
                    if (!allDay) {
                        RowDivider()
                        ValueRow(Icons.Rounded.Schedule, stringResource(if (isAssignment) R.string.planner_due_time else R.string.planner_start),
                            LocalTime.of(startMin / 60, startMin % 60).format(timeFormatter(ctx))) { timeDialog = 1 }
                        if (!isAssignment) {
                            RowDivider()
                            ValueRow(Icons.Rounded.TimerOff, stringResource(R.string.planner_end),
                                LocalTime.of(endMin / 60, endMin % 60).format(timeFormatter(ctx)), error = timeError) { timeDialog = 2 }
                            if (timeError) Text(stringResource(R.string.planner_end_before_start), color = D.c.danger,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 52.dp, bottom = 8.dp))
                        }
                    }
                    RowDivider()
                    SwitchRow(Icons.Rounded.Repeat, stringResource(R.string.planner_repeat_weekly), weekly) { weekly = it; weeklyTouched = true }
                    if (weekly) {
                        RowDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ValueRow(Icons.Rounded.EventRepeat, stringResource(R.string.planner_until),
                                    if (until >= 0) fmtDate(ctx, LocalDate.ofEpochDay(until)) else stringResource(R.string.planner_until_none)) { showUntil = true }
                            }
                            if (until >= 0) IconButton(onClick = { until = -1 }) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.planner_clear), tint = D.c.muted)
                            }
                        }
                    }
                }
                // Where / link / description
                OutlinedTextField(
                    location, { location = it }, label = { Text(stringResource(R.string.planner_location)) },
                    leadingIcon = { Icon(Icons.Rounded.Place, null, tint = D.c.muted) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    link, { link = it; linkError = false }, label = { Text(stringResource(R.string.planner_link)) },
                    placeholder = { Text("zoom.us/j/…") },
                    leadingIcon = { Icon(Icons.Rounded.Link, null, tint = D.c.muted) },
                    trailingIcon = if (link.isNotBlank() && normalizeLink(link) != null) {
                        { IconButton(onClick = { normalizeLink(link)?.let { openLink(ctx, it) } }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.planner_open_link), tint = D.c.accent) } }
                    } else null,
                    isError = linkError,
                    supportingText = if (linkError) { { Text(stringResource(R.string.planner_link_invalid)) } } else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    description, { description = it }, label = { Text(stringResource(R.string.planner_description)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Notes, null, tint = D.c.muted) },
                    minLines = 3, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                // Reminders
                Column {
                    Text(stringResource(R.string.planner_reminders), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
                        modifier = Modifier.padding(bottom = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReminderPresets.forEach { (m, label) ->
                            val on = m in reminders
                            Chip(stringResource(label), on, {
                                val next = if (on) reminders - m else reminders + m
                                remindersCsv = next.sorted().joinToString(","); remindersTouched = true
                            }, leading = if (on) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone)
                        }
                    }
                }
                // Phone calendar
                Column(Modifier.fillMaxWidth().card(D.c)) {
                    SwitchRow(
                        Icons.Rounded.EditCalendar, stringResource(R.string.planner_cal_switch), calSync,
                        desc = calName?.let { stringResource(R.string.planner_cal_synced_in, it.ifBlank { stringResource(R.string.planner_set_calendar) }) }
                            ?: stringResource(R.string.planner_cal_switch_hint),
                    ) { calSync = it; calTouched = true }
                    val linked = existing?.let { Planner.get(it.id) }
                    if (linked != null && linked.deviceEventId != 0L && calName != null) {
                        RowDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { DeviceCalendar.openInApp(ctx, linked) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = D.c.accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(stringResource(R.string.planner_cal_open), style = MaterialTheme.typography.bodyLarge, color = D.c.accent)
                        }
                    }
                }
                // Linked folder
                Column {
                    Text(stringResource(R.string.planner_folder), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            val sel = folders.firstOrNull { it.path == folder }
                            Row(
                                Modifier.fillMaxWidth().card(D.c, 12.dp).clickable { folderMenu = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val meta = remember(folder) { if (folder.isNotBlank()) runCatching { Storage.meta(File(folder)) }.getOrNull() else null }
                                Icon(if (folder.isBlank()) Icons.Rounded.FolderOff else studyIcon(sel?.icon ?: meta?.icon ?: "folder"), null,
                                    tint = if (folder.isBlank()) D.c.muted else folderColor(sel?.color ?: meta?.color ?: 5), modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (folder.isBlank()) stringResource(R.string.planner_folder_none) else sel?.label ?: File(folder).name,
                                    style = MaterialTheme.typography.bodyLarge, color = if (folder.isBlank()) D.c.muted else D.c.ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Rounded.ArrowDropDown, null, tint = D.c.muted)
                            }
                            DropdownMenu(folderMenu, { folderMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.planner_folder_none)) },
                                    leadingIcon = { Icon(Icons.Rounded.FolderOff, null, tint = D.c.muted) },
                                    onClick = { folder = ""; folderMenu = false },
                                )
                                folders.forEach { f ->
                                    DropdownMenuItem(
                                        text = { Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(studyIcon(f.icon), null, tint = folderColor(f.color)) },
                                        onClick = { folder = f.path; folderMenu = false },
                                    )
                                }
                            }
                        }
                        if (folder.isNotBlank() && File(folder).isDirectory) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { pane.open(ctx, File(folder)) }) {
                                Text(stringResource(R.string.planner_open_folder), color = D.c.accent)
                            }
                        }
                    }
                }
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = D.c.danger)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.planner_delete_event), color = D.c.danger)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDate || showUntil) {
        val forUntil = showUntil
        val initial = if (forUntil && until >= 0) until else date
        val minDay = if (forUntil) date else Long.MIN_VALUE
        val st = rememberDatePickerState(
            initialSelectedDateMillis = initial * DAY,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY >= minDay
            },
        )
        val close = { showDate = false; showUntil = false }
        DatePickerDialog(
            onDismissRequest = close,
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { ms ->
                        val day = Math.floorDiv(ms, DAY)
                        if (forUntil) until = day else { date = day; if (until in 0 until day) until = day }
                    }
                    close()
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(st) }
    }

    if (timeDialog != 0) {
        val initial = if (timeDialog == 1) startMin else endMin
        val st = rememberTimePickerState(initial / 60, initial % 60, DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { timeDialog = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val m = st.hour * 60 + st.minute
                    if (timeDialog == 1) {
                        val dur = (endMin - startMin).coerceAtLeast(0)
                        startMin = m
                        endMin = minOf(m + (if (dur == 0) 60 else dur), 23 * 60 + 59)
                    } else endMin = m
                    timeError = false
                    timeDialog = 0
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { timeDialog = 0 }) { Text(stringResource(R.string.cancel)) } },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(st) } },
        )
    }

    if (confirmDelete && existing != null) {
        ConfirmDialog(
            title = stringResource(R.string.planner_delete_title),
            text = stringResource(if (existing.deviceEventId != 0L) R.string.planner_delete_text_synced else R.string.planner_delete_text, existing.title),
            confirm = stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                Notify.cancel(ctx, existing.id)
                Planner.delete(existing.id) // also removes the phone-calendar copy
                pane.back()
            },
        )
    }

    askId?.let { id ->
        AddToCalendarDialog(
            onAdd = { always ->
                if (always) CalendarPrefs.putMode(CalendarPrefs.ALWAYS)
                askId = null
                addToPhone(id, askReminders)
            },
            onNotNow = { never ->
                if (never) CalendarPrefs.putMode(CalendarPrefs.NEVER)
                askId = null
                leave(askReminders)
            },
        )
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = D.c.line, thickness = 1.dp, modifier = Modifier.padding(start = 52.dp))

@Composable
private fun SwitchRow(icon: ImageVector, label: String, checked: Boolean, desc: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = if (desc != null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = D.c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = D.c.muted)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = D.c.accent, checkedThumbColor = D.c.onAccent,
                uncheckedTrackColor = D.c.surfaceAlt, uncheckedThumbColor = D.c.muted, uncheckedBorderColor = D.c.line),
        )
    }
}

@Composable
private fun ValueRow(icon: ImageVector, label: String, value: String, error: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = D.c.muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = D.c.ink, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = if (error) D.c.danger else D.c.accent,
            modifier = Modifier.background(D.c.surfaceAlt, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
    }
}
