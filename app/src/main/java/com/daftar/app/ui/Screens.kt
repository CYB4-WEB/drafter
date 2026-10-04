package com.daftar.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.daftar.app.MainActivity
import com.daftar.app.R
import com.daftar.app.data.Entry
import com.daftar.app.data.Kind
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkEditorScaffold
import com.daftar.app.ink.InkDoc
import com.daftar.app.planner.EventType
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.Planner
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.folderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun eventColor(type: Int) = folderColor(
    when (type) { EventType.EXAM -> 0; EventType.ASSIGNMENT -> 2; EventType.MEETING -> 7; EventType.CLASS -> 6; else -> 11 }
)

@Composable
private fun gutter() = if (LocalWidthClass.current == WidthClass.Compact) D.gutter else D.gutterWide

/** Screen header: big title, optional subtitle, trailing actions. */
@Composable
private fun Header(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter()).padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
        }
        actions()
    }
}

// =====================================================================================
// Home
// =====================================================================================

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val c = D.c
    val actions = rememberActions()
    val compact = LocalWidthClass.current == WidthClass.Compact
    val expanded = LocalWidthClass.current == WidthClass.Expanded
    val v = Storage.version
    val subjects = remember(v) { Storage.list(Storage.root).filter { it.kind == Kind.FOLDER } }
    val recents = remember(v, Storage.recents.size) { Storage.recents.map { File(it) }.filter { it.exists() }.take(8).map { Storage.entry(it) } }
    val pins = remember(v, Storage.pins.size) { Storage.pins.map { File(it) }.filter { it.exists() }.map { Storage.entry(it) } }
    val upcoming = remember(Planner.version) { Planner.upcoming(5) }

    val pending by MainActivity.pendingAction
    LaunchedEffect(pending) {
        when (pending) {
            "new_note" -> actions.quick("note", null)
            "import" -> actions.quick("import", null)
        }
        MainActivity.pendingAction.value = null
    }

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greet = stringResource(if (hour < 12) R.string.greeting_morning else if (hour < 18) R.string.greeting_afternoon else R.string.greeting_evening)
    val date = DateFormat.getDateInstance(DateFormat.FULL).format(Date())

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Header(greet, date) {
                if (compact) IconButton(onClick = { Nav.tab(Screen.Search("")) }) { Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = c.ink) }
            }
            Column(Modifier.padding(horizontal = gutter())) {
                SearchField { Nav.tab(Screen.Search("")) }
                // quick actions
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction(Icons.Rounded.Draw, stringResource(R.string.new_note), Color(0xFF3B82F6)) { actions.quick("note", null) }
                    QuickAction(Icons.Rounded.Dashboard, stringResource(R.string.new_whiteboard), Color(0xFF6366F1)) { actions.quick("whiteboard", null) }
                    QuickAction(Icons.Rounded.CreateNewFolder, stringResource(R.string.new_folder), Color(0xFF10B981)) { actions.newFolder(Storage.root) }
                    QuickAction(Icons.Rounded.FileUpload, stringResource(R.string.import_file), Color(0xFFF59E0B)) { actions.quick("import", null) }
                    QuickAction(Icons.Rounded.Transform, stringResource(R.string.convert), Color(0xFFEF4444)) { Nav.tab(Screen.Convert()) }
                    QuickAction(Icons.Rounded.EventAvailable, stringResource(R.string.add_event), Color(0xFF8B5CF6)) { Nav.push(Screen.EditEvent(null)) }
                }

                if (expanded) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(2f)) {
                            SubjectsSection(subjects, actions, columns = 3)
                            RecentSection(recents, actions)
                        }
                        Column(Modifier.weight(1f)) {
                            UpcomingSection(upcoming)
                            if (pins.isNotEmpty()) PinnedSection(pins, actions)
                        }
                    }
                } else {
                    UpcomingSection(upcoming)
                    SubjectsSection(subjects, actions, columns = if (compact) 0 else 3)
                    if (pins.isNotEmpty()) PinnedSection(pins, actions)
                    RecentSection(recents, actions)
                }
                Spacer(Modifier.height(96.dp))
            }
        }
        Fab({ actions.create(Storage.root) }, Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding())
    }
}

@Composable
private fun SearchField(onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.muted)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.search_hint), color = c.muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val c = D.c
    Column(
        Modifier.width(132.dp).card(c).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(14.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SubjectsSection(subjects: List<Entry>, actions: Actions, columns: Int) {
    val ctx = LocalContext.current
    SectionTitle(stringResource(R.string.subjects)) {
        if (subjects.isNotEmpty()) TextButton(onClick = { Nav.tab(Screen.Library(Storage.root.absolutePath)) }) { Text(stringResource(R.string.see_all)) }
    }
    if (subjects.isEmpty()) {
        Box(Modifier.fillMaxWidth().card(D.c)) {
            EmptyState(Icons.Rounded.CreateNewFolder, stringResource(R.string.no_subjects)) {
                Button(onClick = { actions.newFolder(Storage.root) }) { Text(stringResource(R.string.new_folder)) }
            }
        }
        return
    }
    if (columns == 0) {
        Column(Modifier.fillMaxWidth().card(D.c).padding(4.dp)) {
            subjects.forEach { e -> EntryRow(e, { pane.open(ctx, e.file) }, { actions.menu(e) }) }
        }
    } else {
        subjects.take(9).chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { e -> Box(Modifier.weight(1f)) { FolderTile(e, { pane.open(ctx, e.file) }, { actions.menu(e) }) } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecentSection(recents: List<Entry>, actions: Actions) {
    val ctx = LocalContext.current
    SectionTitle(stringResource(R.string.recent))
    if (recents.isEmpty()) {
        Box(Modifier.fillMaxWidth().card(D.c)) { EmptyState(Icons.Rounded.History, stringResource(R.string.no_recent)) }
    } else Column(Modifier.fillMaxWidth().card(D.c).padding(4.dp)) {
        recents.forEach { e -> EntryRow(e, { pane.open(ctx, e.file) }, { actions.menu(e) }) }
    }
}

@Composable
private fun PinnedSection(pins: List<Entry>, actions: Actions) {
    val ctx = LocalContext.current
    SectionTitle(stringResource(R.string.pinned))
    Column(Modifier.fillMaxWidth().card(D.c).padding(4.dp)) {
        pins.forEach { e -> EntryRow(e, { pane.open(ctx, e.file) }, { actions.menu(e) }) }
    }
}

@Composable
private fun UpcomingSection(items: List<Occurrence>) {
    val c = D.c
    SectionTitle(stringResource(R.string.upcoming)) {
        TextButton(onClick = { Nav.tab(Screen.Planner) }) { Text(stringResource(R.string.see_all)) }
    }
    Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) {
        if (items.isEmpty()) EmptyState(Icons.Rounded.EventAvailable, stringResource(R.string.nothing_upcoming)) {
            OutlinedButton(onClick = { Nav.push(Screen.EditEvent(null)) }) { Text(stringResource(R.string.add_event)) }
        }
        items.forEach { o ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Nav.push(Screen.EditEvent(o.event.id)) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(38.dp).clip(RoundedCornerShape(2.dp)).background(eventColor(o.event.type)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(o.event.title, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(whenLabel(o), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun whenLabel(o: Occurrence): String {
    val day = DateUtilsCompat.dayLabel(o.start, stringResource(R.string.today), stringResource(R.string.tomorrow))
    return if (o.event.allDay) day else day + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(o.start))
}

private object DateUtilsCompat {
    fun dayLabel(t: Long, today: String, tomorrow: String): String {
        val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = t }
        fun same(x: Calendar, y: Calendar) = x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
        if (same(a, b)) return today
        a.add(Calendar.DAY_OF_YEAR, 1)
        if (same(a, b)) return tomorrow
        return SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))
    }
}

// =====================================================================================
// Files (library browser)
// =====================================================================================

@Composable
fun LibraryScreen(dir: String) {
    val ctx = LocalContext.current
    val c = D.c
    val folder = File(dir)
    val actions = rememberActions()
    val v = Storage.version
    val sort = Prefs.sortMode
    val entries = remember(dir, v, sort) { if (folder.exists()) Storage.list(folder) else emptyList() }
    val compact = LocalWidthClass.current == WidthClass.Compact
    val isRoot = Storage.isRoot(folder)
    val meta = remember(dir, v) { if (isRoot) null else Storage.meta(folder) }
    var sortMenu by remember { mutableStateOf(false) }
    val grid = Prefs.gridView && !compact

    LaunchedEffect(dir) { if (!folder.exists()) pane.back() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter()).padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!isRoot) {
                    IconButton(onClick = { pane.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink) }
                    Spacer(Modifier.width(4.dp))
                    FolderGlyph(meta?.color ?: 6, meta?.icon ?: "folder", 40.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (isRoot) stringResource(R.string.files) else folder.name, style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!meta?.desc.isNullOrBlank()) Text(meta!!.desc, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1)
                }
                if (!isRoot) IconButton(onClick = { actions.menu(Storage.entry(folder)) }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.ink) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.sort), tint = c.ink) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf(R.string.sort_name, R.string.sort_date, R.string.sort_type).forEachIndexed { i, l ->
                            DropdownMenuItem({ Text(stringResource(l)) }, { Prefs.putSort(i); sortMenu = false },
                                trailingIcon = { if (sort == i) Icon(Icons.Rounded.Check, null, tint = c.accent) })
                        }
                    }
                }
                if (!compact) IconButton(onClick = { Prefs.putGrid(!Prefs.gridView) }) {
                    Icon(if (Prefs.gridView) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView,
                        stringResource(if (Prefs.gridView) R.string.view_list else R.string.view_grid), tint = c.ink)
                }
            }
            // breadcrumbs
            if (!isRoot) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter()).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val crumbs = Storage.crumbs(folder)
                crumbs.forEachIndexed { i, f ->
                    val last = i == crumbs.lastIndex
                    Text(if (Storage.isRoot(f)) stringResource(R.string.files) else f.name, style = MaterialTheme.typography.labelLarge,
                        color = if (last) c.ink else c.accent,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) {
                            if (Storage.isRoot(f)) Nav.tab(Screen.Library(Storage.root.absolutePath))
                            else { while (Nav.stack.size > 1 && (Nav.current as? Screen.Library)?.dir != f.absolutePath) pane.back() }
                        }.padding(horizontal = 6.dp, vertical = 4.dp))
                    if (!last) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.size(16.dp))
                }
            }

            if (entries.isEmpty()) {
                EmptyState(Icons.Rounded.FolderOpen, stringResource(R.string.empty_folder), Modifier.padding(top = 48.dp)) {
                    Button(onClick = { actions.create(folder) }) { Text(stringResource(R.string.new_item)) }
                }
            } else if (grid) {
                LazyVerticalGrid(GridCells.Adaptive(176.dp), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = gutter(), end = gutter(), top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(entries, key = { it.file.absolutePath }) { e ->
                        if (e.kind == Kind.FOLDER) FolderTile(e, { pane.open(ctx, e.file) }, { actions.menu(e) })
                        else FileTile(e, { pane.open(ctx, e.file) }, { actions.menu(e) })
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter(), end = gutter(), top = 4.dp, bottom = 120.dp)) {
                    items(entries, key = { it.file.absolutePath }) { e -> EntryRow(e, { pane.open(ctx, e.file) }, { actions.menu(e) }, showParent = false) }
                }
            }
        }
        Fab({ actions.create(folder) }, Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding())
    }
}

// =====================================================================================
// Notes (all notebooks)
// =====================================================================================

private data class NoteInfo(val e: Entry, val subject: File?, val pages: Int, val preview: String, val whiteboard: Boolean)

/** Parsed note summaries keyed by path; reused while the file's mtime is unchanged (notes can be large). */
private val noteInfoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, NoteInfo>>()

private fun noteInfo(f: File): NoteInfo {
    val mt = f.lastModified()
    noteInfoCache[f.absolutePath]?.let { (t, info) -> if (t == mt) return info }
    val subj = Storage.crumbs(f.parentFile!!).getOrNull(1)
    val d = InkDoc.load(f)
    val preview = d?.pages?.flatMap { it.texts }?.firstOrNull()?.text?.lineSequence()?.firstOrNull() ?: ""
    val info = NoteInfo(Storage.entry(f), subj, d?.pages?.size ?: 1, preview, d?.infinite == true)
    noteInfoCache[f.absolutePath] = mt to info
    return info
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesScreen() {
    val ctx = LocalContext.current
    val c = D.c
    val actions = rememberActions()
    val v = Storage.version
    var filter by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<NoteInfo>>(emptyList()) }
    LaunchedEffect(v) {
        notes = withContext(Dispatchers.IO) {
            Storage.root.walkTopDown().onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == Storage.NOTE_EXT }
                .sortedByDescending { it.lastModified() }
                .map { f -> noteInfo(f) }.toList()
        }
    }
    val subjects = notes.mapNotNull { it.subject }.distinctBy { it.absolutePath }
    val shown = notes.filter { filter == null || it.subject?.absolutePath == filter }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(stringResource(R.string.notes))
            FlowRow(Modifier.padding(horizontal = gutter()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.all), filter == null, { filter = null })
                subjects.forEach { s ->
                    val m = Storage.meta(s)
                    Chip(s.name, filter == s.absolutePath, { filter = s.absolutePath }, tint = folderColor(m.color))
                }
            }
            if (shown.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.StickyNote2, stringResource(R.string.no_notes), Modifier.padding(top = 40.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.quick("note", null) }) { Text(stringResource(R.string.new_note)) }
                    OutlinedButton(onClick = { actions.quick("whiteboard", null) }) { Text(stringResource(R.string.new_whiteboard)) }
                }
            }
            else LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = gutter(), end = gutter(), bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.e.file.absolutePath }) { n ->
                    val m = n.subject?.let { Storage.meta(it) }
                    Row(Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp))
                        .combinedClickableCompat({ pane.open(ctx, n.e.file) }, { actions.menu(n.e) }).padding(16.dp)) {
                        Box(Modifier.width(4.dp).height(52.dp).clip(RoundedCornerShape(2.dp)).background(if (m != null) folderColor(m.color) else c.line))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(n.e.file.lastModified())), style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            Text(n.preview.ifBlank { if (n.whiteboard) stringResource(R.string.whiteboard) else stringResource(R.string.pages_n, n.pages) },
                                style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(parentLabel(n.e.file), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                        }
                    }
                }
            }
        }
        var fabMenu by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding()) {
            Fab({ fabMenu = true })
            DropdownMenu(fabMenu, { fabMenu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.new_note)) }, { fabMenu = false; actions.quick("note", null) },
                    leadingIcon = { Icon(Icons.Rounded.Draw, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(R.string.new_whiteboard)) }, { fabMenu = false; actions.quick("whiteboard", null) },
                    leadingIcon = { Icon(Icons.Rounded.Dashboard, null, tint = c.muted) })
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit) =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong))

// =====================================================================================
// Search
// =====================================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(query: String) {
    val ctx = LocalContext.current
    val c = D.c
    val actions = rememberActions()
    var q by remember { mutableStateOf(query) }
    var type by remember { mutableIntStateOf(0) }   // 0 all, 1 folders, 2 files, 3 notes
    var results by remember { mutableStateOf<List<Entry>>(emptyList()) }
    val v = Storage.version
    LaunchedEffect(q, v) { results = withContext(Dispatchers.IO) { Storage.search(q.trim()) } }
    val shown = results.filter {
        when (type) { 1 -> it.kind == Kind.FOLDER; 2 -> it.kind != Kind.FOLDER && it.kind != Kind.NOTE; 3 -> it.kind == Kind.NOTE; else -> true }
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Header(stringResource(R.string.search))
        Row(Modifier.padding(horizontal = gutter()).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = c.muted)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text(stringResource(R.string.search_hint), color = c.muted, style = MaterialTheme.typography.bodyLarge)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().focusRequester(fr))
            }
            if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
        }
        FlowRow(Modifier.padding(horizontal = gutter(), vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.all, R.string.folders, R.string.files, R.string.notes).forEachIndexed { i, l -> Chip(stringResource(l), type == i, { type = i }) }
        }
        if (q.isNotBlank() && shown.isEmpty()) EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.no_results))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter(), end = gutter(), bottom = 48.dp)) {
            items(shown, key = { it.file.absolutePath }) { e -> EntryRow(e, { pane.open(ctx, e.file) }, { actions.menu(e) }) }
        }
    }
}

// =====================================================================================
// Settings
// =====================================================================================

@Composable
fun SettingsScreen() {
    val c = D.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats = remember(Storage.version) { Storage.stats() }
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) } }
    val langNow = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState())) {
        Header(stringResource(R.string.settings))
        Column(Modifier.padding(horizontal = gutter()).widthIn(max = 760.dp)) {
            SettingsGroup(stringResource(R.string.set_general)) {
                ChoiceRow(Icons.Rounded.Language, stringResource(R.string.set_language),
                    listOf("" to stringResource(R.string.set_system), "en" to "English", "ar" to "العربية"),
                    if (langNow.startsWith("ar")) "ar" else if (langNow.startsWith("en")) "en" else "") { tag ->
                    AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                }
                ChoiceRow(Icons.Rounded.DarkMode, stringResource(R.string.set_theme),
                    listOf(0 to stringResource(R.string.set_system), 1 to stringResource(R.string.set_light), 2 to stringResource(R.string.set_dark)), Prefs.themeMode) { Prefs.putTheme(it) }
            }
            SettingsGroup(stringResource(R.string.set_display)) {
                ChoiceRow(Icons.Rounded.FormatSize, stringResource(R.string.set_text_size),
                    listOf(0.9f to stringResource(R.string.text_small), 1f to stringResource(R.string.text_default), 1.15f to stringResource(R.string.text_large),
                        1.3f to stringResource(R.string.text_xlarge), 1.5f to stringResource(R.string.text_huge)), Prefs.textScale) { Prefs.putTextScale(it) }
                Text(stringResource(R.string.set_text_preview), style = MaterialTheme.typography.bodyLarge, color = c.muted,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp))
                SwitchRow(Icons.Rounded.TouchApp, stringResource(R.string.set_large_controls), stringResource(R.string.set_large_controls_desc), Prefs.largeControls) { Prefs.putLargeControls(it) }
                SwitchRow(Icons.Rounded.LightMode, stringResource(R.string.set_keep_screen_on), stringResource(R.string.set_keep_screen_on_desc), Prefs.keepScreenOn) { Prefs.putKeepScreenOn(it) }
            }
            SettingsGroup(stringResource(R.string.set_pen)) {
                SwitchRow(Icons.Rounded.Draw, stringResource(R.string.set_pen_only), stringResource(R.string.set_pen_only_desc), Prefs.penOnly) { Prefs.putPenOnly(it) }
                ChoiceRow(Icons.Rounded.Mouse, stringResource(R.string.set_button),
                    listOf(0 to stringResource(R.string.ink_tool_eraser), 1 to stringResource(R.string.ink_tool_lasso)), Prefs.stylusButton) { Prefs.putStylusButton(it) }
                ChoiceRow(Icons.Rounded.GridOn, stringResource(R.string.set_paper),
                    listOf("blank" to stringResource(R.string.ink_paper_blank), "lined" to stringResource(R.string.ink_paper_lined), "grid" to stringResource(R.string.ink_paper_grid),
                        "dots" to stringResource(R.string.ink_paper_dots), "cornell" to stringResource(R.string.ink_paper_cornell)), Prefs.defaultPaper) { Prefs.putPaper(it) }
                ChoiceRow(Icons.Rounded.Gesture, stringResource(R.string.set_ink_lang),
                    listOf("en-US" to "English", "ar" to "العربية"), Prefs.inkLang) { Prefs.putInkLang(it) }
                ChoiceRow(Icons.Rounded.KeyboardVoice, stringResource(R.string.set_speech_lang),
                    listOf("ar-SA" to "العربية", "en-US" to "English"), Prefs.speechLang) { Prefs.putSpeechLang(it) }
                SwitchRow(Icons.Rounded.Link, stringResource(R.string.set_links_in_app), stringResource(R.string.set_links_in_app_desc), Prefs.linksInApp) { Prefs.putLinksInApp(it) }
            }
            SettingsGroup(stringResource(R.string.planner)) {
                SwitchRow(Icons.Rounded.WbSunny, stringResource(R.string.set_daily), stringResource(R.string.set_daily_desc), Prefs.dailySummary) {
                    Prefs.putDaily(it)
                }
            }
            SettingsGroup(stringResource(R.string.set_storage)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storage, null, tint = c.muted)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.set_storage_stats, stats.first, stats.second, android.text.format.Formatter.formatShortFileSize(ctx, stats.third)), color = c.ink)
                        Text(Storage.root.absolutePath, color = c.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        withContext(Dispatchers.IO) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        cacheBytes = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) }
                        toast(ctx, ctx.getString(R.string.cache_cleared))
                    }
                }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CleaningServices, null, tint = c.muted)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.set_clear_cache), color = c.ink)
                        Text(if (cacheBytes < 0) "…" else stringResource(R.string.set_clear_cache_desc, android.text.format.Formatter.formatShortFileSize(ctx, cacheBytes)),
                            color = c.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                DaftarLogo(28.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.app_name) + " 1.1", color = c.ink, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.app_tagline), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Column(Modifier.fillMaxWidth().card(D.c), content = content)
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, desc: String?, value: Boolean, onChange: (Boolean) -> Unit) {
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
private fun <T> ChoiceRow(icon: ImageVector, title: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit) {
    val c = D.c
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.muted); Spacer(Modifier.width(16.dp)); Text(title, color = c.ink)
        }
        FlowRow(Modifier.padding(start = 40.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, label) -> Chip(label, k == value, { onPick(k) }) }
        }
    }
}

// =====================================================================================
// Notebook editor
// =====================================================================================

@Composable
fun NoteScreen(path: String) {
    val f = File(path)
    InkEditorScaffold(
        title = f.nameWithoutExtension, inkFile = f, source = null, isNote = true,
        onBack = { pane.back() },
        onRename = { n -> Storage.rename(f, n)?.let { pane.replace(Screen.Note(it.absolutePath)) } },
    )
}
