package com.daftar.app.planner.countdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.planner.EventType
import com.daftar.app.planner.MINUTE
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.Planner
import com.daftar.app.planner.Segmented
import com.daftar.app.planner.typeColor
import com.daftar.app.planner.typeIcon
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.FolderPalette
import java.io.File

private const val FILTER_ALL = "all"

/** Filter types in the dropdown order (plural labels). */
private val FilterTypes = listOf(
    EventType.EXAM to R.string.cd_filter_exams,
    EventType.ASSIGNMENT to R.string.cd_filter_assignments,
    EventType.CLASS to R.string.cd_filter_classes,
    EventType.MEETING to R.string.cd_filter_meetings,
    EventType.OTHER to R.string.cd_filter_other,
)

/** "Exam Countdown"-style list built on the planner: filter, Past/Future, search, colour cards with the end block. */
@Composable
fun CountdownsScreen() {
    val ctx = LocalContext.current
    val compact = LocalWidthClass.current == WidthClass.Compact
    val gutter = if (compact) D.gutter else D.gutterWide
    val v = Planner.version
    val now = rememberTickingNow(MINUTE) // minute labels; only while visible
    val subjects = rememberSubjectMap()

    var showPast by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf(!CountdownPrefs.hintShown(ctx)) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val events = remember(v) { Planner.all() }
    val minute = now / MINUTE
    val future = remember(events, minute) { futureCountdowns(events, now) }
    val past = remember(events, minute) { pastCountdowns(events, now) }
    val subjectOptions = remember(subjects) { subjects.values.distinctBy { it.path }.sortedBy { it.name.lowercase() } }
    if (filter.startsWith("s:") && subjects.isNotEmpty() && subjectOptions.none { "s:" + it.path == filter }) filter = FILTER_ALL

    val q = query.trim()
    fun matches(o: Occurrence): Boolean {
        val e = o.event
        val okFilter = when {
            filter == FILTER_ALL -> true
            filter.startsWith("t:") -> e.type == filter.drop(2).toIntOrNull()
            filter.startsWith("s:") -> subjects[e.id]?.path == filter.drop(2)
            else -> true
        }
        if (!okFilter) return false
        if (!searching || q.isEmpty()) return true
        return e.title.contains(q, true) || e.location.contains(q, true) || e.description.contains(q, true) ||
            (subjects[e.id]?.name?.contains(q, true) ?: false)
    }
    val fFuture = future.filter(::matches)
    val fPast = past.filter(::matches)
    val shown = if (showPast) fPast else fFuture

    fun openLive(o: Occurrence) {
        if (hint) { hint = false; CountdownPrefs.setHintShown(ctx) }
        pane.push(Screen.Countdown(o.event.id, o.start))
    }

    fun add() {
        val preset = if (filter.startsWith("t:")) filter.drop(2).toIntOrNull() ?: EventType.EXAM else EventType.EXAM
        CountdownDraft.folder = if (filter.startsWith("s:")) filter.drop(2) else null
        pane.push(Screen.EditEvent(null, preset))
    }

    Box(Modifier.fillMaxSize().background(D.c.bg)) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: back · filter dropdown ("All events ▾") · search
            Row(
                Modifier.fillMaxWidth().background(D.c.surface).windowInsetsPadding(WindowInsets.statusBars)
                    .height(if (Prefs.largeControls) 64.dp else 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { pane.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = D.c.ink) }
                Box(Modifier.weight(1f)) {
                    FilterDropdown(filter, subjectOptions) { filter = it }
                }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                    Icon(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, stringResource(R.string.cd_search), tint = if (searching) D.c.accent else D.c.ink)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))

            Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                if (searching) {
                    val fr = remember { FocusRequester() }
                    OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.cd_search_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = D.c.muted) },
                        trailingIcon = if (query.isNotEmpty()) { { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_clear), tint = D.c.muted) } } } else null,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(fr),
                    )
                    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                }
                Segmented(
                    listOf(stringResource(R.string.cd_past_n, fPast.size), stringResource(R.string.cd_future_n, fFuture.size)),
                    if (showPast) 0 else 1, { showPast = it == 0 },
                    Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                )
                if (hint && !showPast && fFuture.isNotEmpty()) HintBubble(stringResource(R.string.cd_hint)) {
                    hint = false; CountdownPrefs.setHintShown(ctx)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 12.dp, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    val filtered = filter != FILTER_ALL || (searching && q.isNotEmpty())
                    EmptyState(
                        if (showPast) Icons.Rounded.History else Icons.Rounded.HourglassEmpty,
                        stringResource(
                            when {
                                filtered -> R.string.cd_no_match
                                showPast -> R.string.cd_empty_past
                                else -> R.string.cd_empty_future
                            }
                        ),
                        Modifier.padding(top = 32.dp),
                    ) {
                        if (!showPast) Button(onClick = ::add, colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent)) {
                            Text(stringResource(R.string.add_event))
                        }
                    }
                }
                items(shown, key = { "${it.event.id}_${it.start}" }) { o ->
                    val key = "${o.event.id}_${o.start}"
                    Box {
                        CountdownCard(o, subjects[o.event.id], now, onLongClick = { menuFor = key }) { openLive(o) }
                        CardMenu(o, menuFor == key, { menuFor = null }) { openLive(o) }
                    }
                }
            }
        }

        val fabPad = if (compact) Modifier.navigationBarsPadding() else Modifier.windowInsetsPadding(WindowInsets.navigationBars)
        FloatingActionButton(
            onClick = ::add, shape = CircleShape, containerColor = D.c.accent, contentColor = D.c.onAccent,
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
            modifier = Modifier.align(Alignment.BottomEnd).then(fabPad).padding(gutter),
        ) { Icon(Icons.Rounded.Add, stringResource(R.string.add_event)) }
    }
}

@Composable
private fun FilterDropdown(filter: String, subjects: List<SubjectInfo>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when {
        filter.startsWith("t:") -> FilterTypes.firstOrNull { "t:${it.first}" == filter }?.let { stringResource(it.second) }
        filter.startsWith("s:") -> subjects.firstOrNull { "s:" + it.path == filter }?.name
        else -> null
    } ?: stringResource(R.string.cd_filter_all)
    Row(
        Modifier.clip12().clickable { open = true }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Rounded.ArrowDropDown, stringResource(R.string.cd_filter), tint = D.c.ink)
    }
    DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 480.dp)) {
        @Composable
        fun item(text: String, key: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color) {
            DropdownMenuItem(
                text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(icon, null, tint = tint) },
                trailingIcon = if (filter == key) { { Icon(Icons.Rounded.Check, null, tint = D.c.accent) } } else null,
                onClick = { open = false; onPick(key) },
            )
        }
        item(stringResource(R.string.cd_filter_all), FILTER_ALL, Icons.Rounded.HourglassTop, D.c.muted)
        FilterTypes.forEach { (t, res) -> item(stringResource(res), "t:$t", typeIcon(t), typeColor(t)) }
        if (subjects.isNotEmpty()) {
            HorizontalDivider(color = D.c.line)
            Text(stringResource(R.string.cd_filter_subjects), style = MaterialTheme.typography.labelMedium, color = D.c.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            subjects.forEach { s -> item(s.name, "s:" + s.path, studyIcon(s.icon), FolderPalette[s.color.mod(FolderPalette.size)]) }
        }
    }
}

private fun Modifier.clip12() = this.then(Modifier.clip(RoundedCornerShape(12.dp)))

/** Long-press actions on a countdown card. */
@Composable
internal fun CardMenu(o: Occurrence, open: Boolean, onClose: () -> Unit, onShow: () -> Unit) {
    val ctx = LocalContext.current
    val e = o.event
    DropdownMenu(open, onClose) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_show_countdown)) },
            leadingIcon = { Icon(Icons.Rounded.HourglassTop, null, tint = D.c.muted) },
            onClick = { onClose(); onShow() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_edit_event)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = D.c.muted) },
            onClick = { onClose(); pane.push(Screen.EditEvent(e.id)) },
        )
        if (e.type == EventType.ASSIGNMENT) DropdownMenuItem(
            text = { Text(stringResource(if (e.done) R.string.cd_mark_not_done else R.string.cd_mark_done)) },
            leadingIcon = { Icon(if (e.done) Icons.Rounded.RemoveDone else Icons.Rounded.TaskAlt, null, tint = D.c.muted) },
            onClick = { onClose(); Planner.setDone(e.id, !e.done) },
        )
        if (e.folder.isNotBlank() && File(e.folder).isDirectory) DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_open_folder)) },
            leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = D.c.muted) },
            onClick = { onClose(); pane.open(ctx, File(e.folder)) },
        )
    }
}

/** One-time tip ("Tap an event to view a live countdown"): dark bubble with a pointer, flat. */
@Composable
private fun HintBubble(text: String, onClose: () -> Unit) {
    val c = D.c
    Column(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.widthIn(max = 520.dp).background(c.ink, RoundedCornerShape(12.dp)).padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.TouchApp, null, tint = c.bg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.bg, modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.cd_dismiss), tint = c.bg.copy(alpha = 0.8f)) }
        }
        // Pointer towards the first card.
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
            }
            drawPath(p, c.ink)
        }
    }
}
