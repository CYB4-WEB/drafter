package com.daftar.app.planner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.ui.Chip
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.card
import com.daftar.app.ui.toast
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

// =====================================================================================
// Calendar permission: explain first, then the system prompt
// =====================================================================================

/** Outcome of [CalendarAccess.request]: granted, denied by Android, or the user backed out of the explanation. */
enum class CalAccess { GRANTED, DENIED, CANCELLED }

@Stable
class CalendarAccess internal constructor(private val ctx: Context) {
    internal var explaining by mutableStateOf(false)
    internal var allowFallback by mutableStateOf(false)
    private var callback: ((CalAccess) -> Unit)? = null

    val granted: Boolean get() = DeviceCalendar.permitted(ctx)

    /**
     * Shows a short explanation, then Android's permission prompt. [onResult] gets the outcome
     * (immediately GRANTED when access is already there). [allowFallback] offers "Use calendar app" when access is blocked.
     */
    fun request(allowFallback: Boolean = false, onResult: (CalAccess) -> Unit) {
        if (granted) { onResult(CalAccess.GRANTED); return }
        callback = onResult
        this.allowFallback = allowFallback
        explaining = true
    }

    internal fun finish(r: CalAccess) {
        explaining = false
        val cb = callback
        callback = null
        if (r == CalAccess.GRANTED) Planner.syncPending()
        cb?.invoke(r)
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Calendar-permission helper bound to the current screen; draws its own explanation dialog. */
@Composable
fun rememberCalendarAccess(): CalendarAccess {
    val ctx = LocalContext.current
    val state = remember { CalendarAccess(ctx.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.finish(if (DeviceCalendar.permitted(ctx)) CalAccess.GRANTED else CalAccess.DENIED)
    }
    if (state.explaining) {
        val activity = remember { ctx.findActivity() }
        // Asked before and Android will no longer show its prompt ("Don't allow" twice / "Don't ask again").
        val blocked = PlannerLocalPrefs.askedCalendar(ctx) && activity != null &&
            DeviceCalendar.permissions.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                .any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        AlertDialog(
            onDismissRequest = { state.finish(CalAccess.CANCELLED) },
            icon = { Icon(Icons.Rounded.EventAvailable, null, tint = D.c.accent) },
            title = { Text(stringResource(R.string.planner_cal_access_title), textAlign = TextAlign.Center) },
            text = {
                Text(stringResource(if (blocked) R.string.planner_cal_access_blocked else R.string.planner_cal_access_text),
                    style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
            },
            confirmButton = {
                if (blocked) TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    state.finish(CalAccess.CANCELLED)
                }) { Text(stringResource(R.string.planner_open_settings), color = D.c.accent) }
                else TextButton(onClick = {
                    state.explaining = false
                    PlannerLocalPrefs.setAskedCalendar(ctx)
                    runCatching { launcher.launch(DeviceCalendar.permissions) }.onFailure { state.finish(CalAccess.DENIED) }
                }) { Text(stringResource(R.string.planner_continue), color = D.c.accent) }
            },
            dismissButton = {
                if (blocked && state.allowFallback) TextButton(onClick = { state.finish(CalAccess.DENIED) }) {
                    Text(stringResource(R.string.planner_use_calendar_app), color = D.c.ink)
                } else TextButton(onClick = { state.finish(CalAccess.CANCELLED) }) {
                    Text(stringResource(R.string.planner_not_now), color = D.c.ink)
                }
            },
        )
    }
    return state
}

// =====================================================================================
// "Also add this to your phone's calendar?" (after saving a new event)
// =====================================================================================

/** [onAdd] / [onNotNow] get true when "Remember my choice" is ticked (→ always / never). */
@Composable
internal fun AddToCalendarDialog(onAdd: (remember: Boolean) -> Unit, onNotNow: (remember: Boolean) -> Unit) {
    var remember by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onNotNow(false) },
        icon = { Icon(Icons.Rounded.EditCalendar, null, tint = D.c.accent) },
        title = { Text(stringResource(R.string.planner_ask_title), textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(stringResource(R.string.planner_ask_text), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { remember = !remember }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(remember, { remember = it },
                        colors = CheckboxDefaults.colors(checkedColor = D.c.accent, uncheckedColor = D.c.muted, checkmarkColor = D.c.onAccent))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.planner_ask_remember), style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                        Text(stringResource(R.string.planner_ask_settings_hint), style = MaterialTheme.typography.bodySmall, color = D.c.muted)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(remember) }, colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent)) {
                Text(stringResource(if (remember) R.string.planner_ask_always else R.string.planner_ask_add))
            }
        },
        dismissButton = {
            TextButton(onClick = { onNotNow(remember) }) {
                Text(stringResource(if (remember) R.string.planner_ask_never else R.string.planner_not_now), color = D.c.ink)
            }
        },
    )
}

// =====================================================================================
// Calendar choice
// =====================================================================================

internal fun calColor(argb: Int) = Color(argb or 0xFF000000.toInt())

/** Radio list of writable calendars: colour, name, account; the primary is marked "Default". */
@Composable
internal fun CalendarList(cals: List<DeviceCal>, selectedId: Long, onPick: (DeviceCal) -> Unit) {
    val sel = cals.firstOrNull { it.id == selectedId } ?: cals.firstOrNull { it.primary } ?: cals.firstOrNull()
    Column {
        cals.forEach { cal ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(cal) }.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(cal.id == sel?.id, { onPick(cal) }, colors = RadioButtonDefaults.colors(selectedColor = D.c.accent, unselectedColor = D.c.muted))
                Box(Modifier.size(12.dp).background(calColor(cal.color), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cal.name, style = MaterialTheme.typography.bodyLarge, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (cal.primary) Text(" · " + stringResource(R.string.planner_default_cal), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1)
                    }
                    if (cal.account.isNotBlank() && cal.account != cal.name)
                        Text(cal.account, style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CalendarsBody(cals: List<DeviceCal>?, onPick: (DeviceCal) -> Unit) {
    when {
        cals == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = D.c.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        cals.isEmpty() -> Text(stringResource(R.string.planner_sync_no_calendars), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
        else -> CalendarList(cals, CalendarPrefs.calendarId, onPick)
    }
}

/** Picks the calendar new copies go to (access must already be granted). */
@Composable
internal fun CalendarPickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val cals by produceState<List<DeviceCal>?>(null) { value = withContext(Dispatchers.IO) { DeviceCalendar.calendars(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_sync_target)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.planner_sync_target_hint), style = MaterialTheme.typography.bodySmall, color = D.c.muted,
                    modifier = Modifier.padding(bottom = 8.dp))
                CalendarsBody(cals) { CalendarPrefs.putCalendar(it.id); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = D.c.ink) } },
    )
}

// =====================================================================================
// Planner menu: Sync with device calendar
// =====================================================================================

@Composable
internal fun CalendarSyncDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val access = rememberCalendarAccess()
    var permitted by remember { mutableStateOf(DeviceCalendar.permitted(ctx)) }
    val cals by produceState<List<DeviceCal>?>(null, permitted) {
        value = if (permitted) withContext(Dispatchers.IO) { DeviceCalendar.calendars(ctx) } else emptyList()
    }
    val v = Planner.version
    val toAdd = remember(v) {
        val now = System.currentTimeMillis()
        Planner.all().filter { !it.calendarSync && Planner.upcomingIn(listOf(it), 1, now).isNotEmpty() }.map { it.id }
    }
    val synced = remember(v) { Planner.all().filter { it.calendarSync || it.deviceEventId != 0L }.map { it.id } }
    var confirmRemove by remember { mutableStateOf(false) }
    val needed = stringResource(R.string.planner_cal_needed)
    val adding = stringResource(R.string.planner_sync_adding)

    fun setAuto(on: Boolean) {
        if (!on) { CalendarPrefs.putMode(CalendarPrefs.NEVER); return }
        access.request { r ->
            if (r == CalAccess.GRANTED) { permitted = true; CalendarPrefs.putMode(CalendarPrefs.ALWAYS) }
            else if (r == CalAccess.DENIED) toast(ctx, needed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Sync, null, tint = D.c.accent) },
        title = { Text(stringResource(R.string.planner_menu_sync), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                val auto = CalendarPrefs.mode == CalendarPrefs.ALWAYS && permitted
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { setAuto(!auto) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.planner_sync_auto), style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                        Text(stringResource(R.string.planner_sync_auto_hint), style = MaterialTheme.typography.bodySmall, color = D.c.muted)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(auto, { setAuto(it) }, colors = SwitchDefaults.colors(checkedTrackColor = D.c.accent, checkedThumbColor = D.c.onAccent))
                }
                HorizontalDivider(color = D.c.line, modifier = Modifier.padding(vertical = 8.dp))
                if (!permitted) {
                    Text(stringResource(R.string.planner_sync_need_access), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
                    TextButton(onClick = { access.request { if (it == CalAccess.GRANTED) permitted = true } }) {
                        Text(stringResource(R.string.planner_sync_allow), color = D.c.accent)
                    }
                } else {
                    Text(stringResource(R.string.planner_sync_target), style = MaterialTheme.typography.labelLarge, color = D.c.ink,
                        modifier = Modifier.padding(bottom = 4.dp))
                    CalendarsBody(cals) { CalendarPrefs.putCalendar(it.id) }
                    if (toAdd.isNotEmpty() || synced.isNotEmpty()) HorizontalDivider(color = D.c.line, modifier = Modifier.padding(vertical = 8.dp))
                    if (toAdd.isNotEmpty()) TextButton(onClick = { Planner.setCalendarSync(toAdd, true); toast(ctx, adding) }) {
                        Icon(Icons.Rounded.EventAvailable, null, tint = D.c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(pluralStringResource(R.plurals.planner_sync_add_existing, toAdd.size, toAdd.size), color = D.c.accent)
                    }
                    if (synced.isNotEmpty()) TextButton(onClick = { confirmRemove = true }) {
                        Icon(Icons.Rounded.EventBusy, null, tint = D.c.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(pluralStringResource(R.plurals.planner_sync_remove, synced.size, synced.size), color = D.c.danger)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planner_done), color = D.c.accent) } },
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.planner_sync_remove_title)) },
            text = { Text(stringResource(R.string.planner_sync_remove_text)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; Planner.setCalendarSync(synced, false) }) {
                    Text(stringResource(R.string.planner_remove), color = D.c.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel), color = D.c.ink) } },
        )
    }
}

// =====================================================================================
// Planner menu: Import from calendar
// =====================================================================================

private val ImportRanges = listOf(14 to R.string.planner_import_range_2w, 31 to R.string.planner_import_range_1m,
    92 to R.string.planner_import_range_3m, 183 to R.string.planner_import_range_6m)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportCalendarDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val access = rememberCalendarAccess()
    var readable by remember { mutableStateOf(DeviceCalendar.canRead(ctx)) }
    val needed = stringResource(R.string.planner_cal_needed)
    LaunchedEffect(Unit) {
        if (!readable) access.request { r ->
            if (r == CalAccess.GRANTED) readable = true
            else { if (r == CalAccess.DENIED) toast(ctx, needed); onDismiss() }
        }
    }
    if (!readable) return

    var days by rememberSaveable { mutableIntStateOf(31) }
    val candidates by produceState<List<ImportCandidate>?>(null, days) {
        value = null
        value = withContext(Dispatchers.IO) { DeviceCalendar.upcomingForImport(ctx, days, Planner.snapshot()) ?: emptyList() }
    }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val types = remember { mutableStateMapOf<String, Int>() }
    var addReminders by rememberSaveable { mutableStateOf(false) }
    val selectable = candidates.orEmpty().filter { !it.alreadyImported }
    val chosen = selectable.filter { selected[it.key] == true }

    fun import() {
        val base = Planner.newId()
        val list = chosen.mapIndexed { i, cand ->
            val t = types[cand.key] ?: cand.event.type
            cand.event.copy(id = base + i, type = t, reminders = if (addReminders) defaultReminders(t) else emptyList())
        }
        Planner.addAll(list)
        toast(ctx, ctx.resources.getQuantityString(R.plurals.planner_imported_n, list.size, list.size))
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f)
                .background(D.c.surface, RoundedCornerShape(24.dp)).border(1.dp, D.c.line, RoundedCornerShape(24.dp)),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.planner_import_title), style = MaterialTheme.typography.titleLarge, color = D.c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.planner_close), tint = D.c.muted) }
            }
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportRanges.forEach { (d, label) -> Chip(stringResource(label), days == d, { days = d }) }
            }
            if (selectable.isNotEmpty()) {
                val all = chosen.size == selectable.size
                Row(
                    Modifier.fillMaxWidth().clickable { val to = !all; selectable.forEach { selected[it.key] = to } }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(all, { to -> selectable.forEach { selected[it.key] = to } },
                        colors = CheckboxDefaults.colors(checkedColor = D.c.accent, uncheckedColor = D.c.muted, checkmarkColor = D.c.onAccent))
                    Text(stringResource(R.string.planner_import_select_all), style = MaterialTheme.typography.labelLarge, color = D.c.ink, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.planner_import_type_hint), style = MaterialTheme.typography.bodySmall, color = D.c.muted,
                        modifier = Modifier.padding(end = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = D.c.line)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = candidates
                when {
                    list == null -> CircularProgressIndicator(color = D.c.accent, strokeWidth = 2.dp, modifier = Modifier.size(32.dp).align(Alignment.Center))
                    list.isEmpty() -> Text(stringResource(R.string.planner_import_empty), style = MaterialTheme.typography.bodyLarge, color = D.c.muted,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(list, key = { it.key }) { cand ->
                            ImportRow(cand, selected[cand.key] == true, types[cand.key] ?: cand.event.type,
                                onToggle = { selected[cand.key] = it },
                                onType = { types[cand.key] = AllTypes[(AllTypes.indexOf(types[cand.key] ?: cand.event.type) + 1) % AllTypes.size] })
                        }
                    }
                }
            }
            HorizontalDivider(color = D.c.line)
            Row(
                Modifier.fillMaxWidth().clickable { addReminders = !addReminders }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.planner_import_reminders), style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                    Text(stringResource(R.string.planner_import_reminders_hint), style = MaterialTheme.typography.bodySmall, color = D.c.muted)
                }
                Switch(addReminders, { addReminders = it }, colors = SwitchDefaults.colors(checkedTrackColor = D.c.accent, checkedThumbColor = D.c.onAccent))
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = D.c.ink) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::import, enabled = chosen.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent)) {
                    Text(stringResource(R.string.planner_import_button, chosen.size))
                }
            }
        }
    }
}

@Composable
private fun ImportRow(cand: ImportCandidate, checked: Boolean, type: Int, onToggle: (Boolean) -> Unit, onType: () -> Unit) {
    val ctx = LocalContext.current
    val e = cand.event
    val o = Occurrence(e, e.start, e.end)
    val enabled = !cand.alreadyImported
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (enabled) Modifier.clickable { onToggle(!checked) } else Modifier)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked && enabled, { onToggle(it) }, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = D.c.accent, uncheckedColor = D.c.muted, checkmarkColor = D.c.onAccent))
        Box(Modifier.width(4.dp).height(36.dp).background(calColor(cand.color), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = D.c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.weekly) {
                    Icon(Icons.Rounded.Repeat, stringResource(R.string.planner_weekly), tint = D.c.muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                val parts = buildList {
                    add(dayLabel(ctx, Planner.dateOf(e.start)) + " · " + fmtTimeRange(ctx, o))
                    if (cand.alreadyImported) add(stringResource(R.string.planner_import_already))
                    else if (cand.calendar.isNotBlank()) add(cand.calendar)
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = D.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (enabled) Chip(stringResource(typeLabelRes(type)), true, onType, leading = typeIcon(type), tint = typeColor(type))
    }
}

// =====================================================================================
// Planner menu: Export .ics
// =====================================================================================

@Composable
internal fun ExportIcsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = remember { Planner.all() }
    val upcoming = remember(all) {
        val now = System.currentTimeMillis()
        all.filter { Planner.upcomingIn(listOf(it), 1, now).isNotEmpty() }
    }
    var which by rememberSaveable { mutableIntStateOf(if (upcoming.isNotEmpty()) 0 else 1) }
    var busy by remember { mutableStateOf(false) }
    val chosen = if (which == 0) upcoming else all
    val failed = stringResource(R.string.planner_export_failed)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Rounded.IosShare, null, tint = D.c.accent) },
        title = { Text(stringResource(R.string.planner_export_title), textAlign = TextAlign.Center) },
        text = {
            Column {
                Text(stringResource(R.string.planner_export_desc), style = MaterialTheme.typography.bodyMedium, color = D.c.muted,
                    modifier = Modifier.padding(bottom = 8.dp))
                listOf(stringResource(R.string.planner_export_upcoming, upcoming.size), stringResource(R.string.planner_export_all, all.size))
                    .forEachIndexed { i, label ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { which = i },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(which == i, { which = i }, colors = RadioButtonDefaults.colors(selectedColor = D.c.accent, unselectedColor = D.c.muted))
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                        }
                    }
                if (busy) LinearProgressIndicator(color = D.c.accent, trackColor = D.c.surfaceAlt, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && chosen.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val f = withContext(Dispatchers.IO) { runCatching { Ics.write(ctx, chosen) }.getOrNull() }
                    busy = false
                    if (f == null) toast(ctx, failed) else Ics.share(ctx, f)
                    onDismiss()
                }
            }) { Text(stringResource(R.string.share), color = if (!busy && chosen.isNotEmpty()) D.c.accent else D.c.muted) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel), color = D.c.ink) } },
    )
}

// =====================================================================================
// Settings → Planner (embedded by the lead in SettingsScreen)
// =====================================================================================

/**
 * The Planner group of Settings: phone-calendar choice (Ask / Always / Never), target calendar, morning summary.
 * Draws its own section title + card, matching the other Settings groups.
 */
@Composable
fun PlannerSettingsSection() {
    val ctx = LocalContext.current
    val access = rememberCalendarAccess()
    var permitted by remember { mutableStateOf(DeviceCalendar.permitted(ctx)) }
    LifecycleResumeEffect(Unit) {
        permitted = DeviceCalendar.permitted(ctx)
        onPauseOrDispose { }
    }
    var picker by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var targetLoaded by remember { mutableStateOf(false) }
    val target by produceState<DeviceCal?>(null, permitted, CalendarPrefs.calendarId, reload) {
        targetLoaded = false
        value = if (permitted) withContext(Dispatchers.IO) { DeviceCalendar.target(ctx) } else null
        targetLoaded = true
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val needed = stringResource(R.string.planner_cal_needed)

    SectionTitle(stringResource(R.string.planner))
    Column(Modifier.fillMaxWidth().card(D.c)) {
        SetChoiceRow(
            Icons.Rounded.EditCalendar, stringResource(R.string.planner_set_cal_mode), stringResource(R.string.planner_cal_switch_hint),
            listOf(CalendarPrefs.ASK to stringResource(R.string.planner_mode_ask), CalendarPrefs.ALWAYS to stringResource(R.string.planner_mode_always),
                CalendarPrefs.NEVER to stringResource(R.string.planner_mode_never)),
            CalendarPrefs.mode,
        ) { m ->
            if (m == CalendarPrefs.ALWAYS && !DeviceCalendar.permitted(ctx)) access.request { r ->
                if (r == CalAccess.GRANTED) { permitted = true; CalendarPrefs.putMode(CalendarPrefs.ALWAYS) }
                else if (r == CalAccess.DENIED) toast(ctx, needed)
            } else CalendarPrefs.putMode(m)
        }
        val cal = target
        SetValueRow(
            Icons.Rounded.CalendarMonth, stringResource(R.string.planner_set_calendar),
            when {
                !permitted -> stringResource(R.string.planner_sync_allow)
                cal != null -> if (cal.account.isNotBlank() && cal.account != cal.name) "${cal.name} · ${cal.account}" else cal.name
                targetLoaded -> stringResource(R.string.planner_set_calendar_none)
                else -> "…"
            },
            dot = cal?.let { calColor(it.color) },
        ) {
            if (permitted) picker = true
            else access.request { if (it == CalAccess.GRANTED) { permitted = true; picker = true } }
        }
        val at = LocalTime.of(7, 30).format(timeFormatter(ctx))
        SetSwitchRow(Icons.Rounded.WbSunny, stringResource(R.string.planner_set_daily), stringResource(R.string.planner_set_daily_desc, at), Prefs.dailySummary) { on ->
            Prefs.putDaily(on)
            if (on) {
                Planner.rescheduleAll(ctx)
                if (Build.VERSION.SDK_INT >= 33 && !Notify.permitted(ctx)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    if (picker) CalendarPickerDialog(onDismiss = { picker = false; reload++ })
}

// Same look as SettingsScreen's private rows (ui/Screens.kt).

@Composable
private fun SetSwitchRow(icon: ImageVector, title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink)
            if (desc != null) Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SetChoiceRow(icon: ImageVector, title: String, desc: String?, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    val c = D.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.muted)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = c.ink)
                if (desc != null) Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        FlowRow(Modifier.padding(start = 40.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, label) -> Chip(label, k == value, { onPick(k) }) }
        }
    }
}

@Composable
private fun SetValueRow(icon: ImageVector, title: String, value: String, dot: Color?, onClick: () -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(value, color = c.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}
