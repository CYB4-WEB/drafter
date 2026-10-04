package com.daftar.app.convert

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MergeType
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.FileBadge
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.FolderPickerDialog
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.kindIcon
import com.daftar.app.ui.pane
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================================================================
// Hub
// =====================================================================================================================

/** Inputs, options, output folder and the current run of one conversion in the hub. */
@Stable
private class SetupState(val conv: Conv, initial: List<Src>) {
    val sources = mutableStateListOf<Src>().apply { addAll(initial) }
    var options by mutableStateOf(conv.defaults())
    var outDir by mutableStateOf<File?>(null)
    var job by mutableStateOf<ConvertJob?>(null)
    var pageCount by mutableIntStateOf(0)

    fun add(list: List<Src>) {
        val ok = list.filter { it.kind == conv.from }
        if (ok.isEmpty()) return
        if (!conv.multi) {
            sources.clear(); sources.add(ok.first())
        } else ok.forEach { s -> if (sources.none { it.key == s.key }) sources.add(s) }
        if (job?.running != true) job = null
    }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (i in sources.indices && j in sources.indices) sources.add(j, sources.removeAt(i))
    }

    /** Chosen folder, or the first library source's folder, or Inbox (device files). */
    fun targetDir(): File = outDir ?: sources.firstNotNullOfOrNull { it.libFile }?.parentFile
        ?.takeIf { it.absolutePath.startsWith(Storage.root.absolutePath) } ?: File(Storage.root, "Inbox")

    fun canRun(): Boolean = job?.running != true && sources.size >= conv.minSources && optionsValid(conv, options, pageCount)
}

private fun initialSources(conv: Conv, carried: List<Src>): List<Src> {
    val ok = carried.filter { it.kind == conv.from && (it is Src.Lib || conv.from == Kind.IMAGE) }
    return if (conv.multi) ok else ok.take(1)
}

/** Converter hub (top-level tab). [path] preselects a source file. */
@Composable
fun ConvertScreen(path: String?) {
    val c = D.c
    val preFile = remember(path) { path?.let(::File)?.takeIf { it.isFile } }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedName?.let { n -> Conv.entries.firstOrNull { it.name == n } }
    val carried = remember(preFile) { mutableStateListOf<Src>().apply { if (preFile != null) add(Src.Lib(preFile)) } }
    val setup = remember(selected) { selected?.let { SetupState(it, initialSources(it, carried)) } }
    LaunchedEffect(setup) {
        val st = setup ?: return@LaunchedEffect
        snapshotFlow { st.sources.toList() }.collect { list -> if (list.isNotEmpty()) { carried.clear(); carried.addAll(list) } }
    }
    BackHandler(enabled = selected != null) { selectedName = null }

    val inPane = LocalPaneNav.current.inPane
    // The phone bottom bar is only shown for a top-level tab; otherwise keep content above the gesture bar.
    val navPad = !inPane && !(Nav.stack.size == 1 && LocalWidthClass.current == WidthClass.Compact)

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= 840.dp
        val gutter = if (maxWidth < 600.dp) 16.dp else 24.dp
        val panelWidth = (maxWidth * 0.4f).coerceIn(380.dp, 520.dp)
        val onPick: (Conv) -> Unit = { selectedName = it.name }
        when {
            wide -> Row(Modifier.fillMaxSize()) {
                Hub(Modifier.weight(1f).fillMaxHeight(), gutter, selected, preFile, path != null, navPad, onPick)
                if (setup != null) {
                    Box(Modifier.fillMaxHeight().width(1.dp).background(c.line))
                    SetupPanel(setup, Modifier.width(panelWidth).fillMaxHeight().background(c.surface),
                        wide = true, navPad = navPad) { selectedName = null }
                }
            }
            setup != null -> SetupPanel(setup, Modifier.fillMaxSize().background(c.bg), wide = false, navPad = navPad) { selectedName = null }
            else -> Hub(Modifier.fillMaxSize(), gutter, null, preFile, path != null, navPad, onPick)
        }
    }
}

@Composable
private fun Hub(modifier: Modifier, gutter: Dp, selected: Conv?, preFile: File?, showBack: Boolean, navPad: Boolean, onPick: (Conv) -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(156.dp),
        modifier = modifier.then(if (navPad) Modifier.navigationBarsPadding() else Modifier),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header", span = full) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) IconButton(onClick = { pane.back() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.convert), style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.convert_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }
        if (preFile != null) {
            val kind = Storage.kindOf(preFile)
            val forFile = Conv.entries.filter { it.from == kind }
            item(key = "file", span = full) {
                Column {
                    SectionLabel(stringResource(R.string.convert_for_file))
                    Row(Modifier.fillMaxWidth().card(c).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(kind, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(preFile.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (forFile.isEmpty()) item(key = "file_none", span = full) {
                Text(stringResource(R.string.convert_none), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            items(forFile, key = { "f_${it.name}" }) { conv -> ConvCard(conv, conv == selected, { onPick(conv) }) }
        }
        for (g in Group.entries) {
            item(key = "g_${g.name}", span = full) { SectionLabel(stringResource(g.title)) }
            items(Conv.inGroup(g), key = { it.name }) { conv -> ConvCard(conv, conv == selected, { onPick(conv) }) }
        }
        val recent = ConvertJobs.recent
        if (recent.isNotEmpty()) {
            item(key = "recent", span = full) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.convert_recent), Modifier.weight(1f))
                    if (recent.any { !it.running }) TextButton(onClick = { ConvertJobs.clearFinished() }, modifier = Modifier.padding(top = 12.dp)) {
                        Text(stringResource(R.string.convert_clear), color = c.accent)
                    }
                }
            }
            items(recent.toList(), key = { "j${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { j ->
                RecentRow(j, onOpen = { pane.open(ctx, it) }, onShare = { shareFiles(ctx, it) },
                    onFolder = { pane.push(Screen.Library(it.absolutePath)) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPanel(st: SetupState, modifier: Modifier, wide: Boolean, navPad: Boolean, onClose: () -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val conv = st.conv
    var pickLibrary by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    fun importDocs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            importing = true
            val files = withContext(Dispatchers.IO) {
                val inbox = Storage.inbox()
                uris.mapNotNull { Storage.import(ctx, it, inbox) }
            }
            importing = false
            val ok = files.filter { conv.accepts(it) }
            val bad = files.filter { it !in ok }
            if (bad.isNotEmpty()) {
                withContext(Dispatchers.IO) { bad.forEach { it.delete() } }
                toast(ctx, ctx.getString(R.string.convert_not_supported))
            }
            Storage.touch()
            st.add(ok.map { Src.Lib(it) })
        }
    }
    val openMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importDocs(it) }
    val openOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importDocs(listOf(uri)) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val list = withContext(Dispatchers.IO) { uris.map { Src.Device(it, Storage.displayName(ctx, it)) } }
            st.add(list)
        }
    }

    // Page count of the (first) PDF for range hints and validation.
    val firstPdf = st.sources.firstOrNull()?.libFile?.takeIf { conv.from == Kind.PDF }
    LaunchedEffect(firstPdf) { st.pageCount = if (firstPdf == null) 0 else withContext(Dispatchers.IO) { Engines.pdfPageCount(firstPdf) } }
    val sourceKeys = st.sources.map { it.key }
    val hasInk by produceState(false, sourceKeys) {
        value = withContext(Dispatchers.IO) { st.sources.toList().any { s -> s.libFile?.let(Engines::hasInk) == true } }
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                if (wide) Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.ink)
                else Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink)
            }
            Text(stringResource(conv.title), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConvBadges(conv, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(conv.desc), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
            }

            // ---- sources
            SectionLabel(stringResource(if (conv.multi) R.string.convert_sources else R.string.convert_source))
            if (st.sources.isEmpty()) {
                Text(stringResource(if (conv.minSources > 1) R.string.convert_need_two else R.string.convert_no_source),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            st.sources.forEachIndexed { i, s ->
                SourceRow(s, i, st.sources.size, conv.multi, onMove = { d -> st.move(i, d) }, onRemove = { st.sources.removeAt(i); if (st.job?.running != true) st.job = null })
                Spacer(Modifier.height(8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.convert_from_library), Icons.Rounded.Folder) { pickLibrary = true }
                if (conv.from == Kind.IMAGE) {
                    SecondaryButton(stringResource(R.string.convert_from_photos), Icons.Rounded.PhotoLibrary) {
                        photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                } else {
                    SecondaryButton(stringResource(R.string.convert_from_device), Icons.Rounded.PhoneAndroid) {
                        if (conv.multi) openMany.launch(mimesFor(conv.from)) else openOne.launch(mimesFor(conv.from))
                    }
                }
                if (importing) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically), color = c.accent, strokeWidth = 2.dp)
            }

            // ---- options
            if (conv.options.any { it != Opt.ANNOTATIONS } || hasInk && Opt.ANNOTATIONS in conv.options) {
                SectionLabel(stringResource(R.string.convert_options))
                OptionsEditor(conv, st.options, st.pageCount, hasInk) { st.options = it }
            }

            // ---- output folder
            SectionLabel(stringResource(R.string.convert_save_to))
            val dir = st.targetDir()
            Row(
                Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp)).clickable { pickFolder = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val meta = remember(dir.absolutePath, Storage.version) { if (Storage.isRoot(dir) || !dir.exists()) null else Storage.meta(dir) }
                if (meta != null) FolderGlyph(meta.color, meta.icon, 32.dp) else FolderGlyph(10, if (Storage.isRoot(dir)) "folder" else "inbox", 32.dp)
                Spacer(Modifier.width(12.dp))
                Text(displayPath(ctx, dir), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.convert_change), style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
            Spacer(Modifier.height(20.dp))
            st.job?.let { j ->
                JobPanel(j, onOpen = { pane.open(ctx, it) }, onShare = { shareFiles(ctx, it) }, onFolder = { pane.push(Screen.Library(it.absolutePath)) })
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- run bar
        Column(Modifier.fillMaxWidth().background(if (wide) c.surface else c.bg).then(if (navPad) Modifier.navigationBarsPadding() else Modifier)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            PrimaryButton(
                stringResource(if (st.job?.state is ConvertJob.State.Done) R.string.convert_again else R.string.convert_run),
                enabled = st.canRun(), modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                val outDir = st.outDir ?: st.targetDir().let { if (it.name == "Inbox" && it.parentFile == Storage.root) Storage.inbox() else it }
                st.job = ConvertJobs.start(ctx, conv, st.sources.toList(), st.options, outDir)
            }
        }
    }

    if (pickLibrary) {
        LibraryFilePickerDialog(
            title = stringResource(if (conv.multi) R.string.convert_pick_files else R.string.convert_pick_file),
            accept = { conv.accepts(it) }, multiple = conv.multi, onDismiss = { pickLibrary = false },
            start = st.sources.firstNotNullOfOrNull { it.libFile }?.parentFile ?: Storage.root,
        ) { files ->
            pickLibrary = false
            st.add(files.map { Src.Lib(it) })
        }
    }
    if (pickFolder) {
        FolderPickerDialog(stringResource(R.string.convert_choose_folder), null, onDismiss = { pickFolder = false }) { d ->
            pickFolder = false
            st.outDir = d
        }
    }
}

@Composable
private fun SourceRow(s: Src, index: Int, count: Int, reorder: Boolean, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val c = D.c
    val info = remember(s.key) { s.libFile?.let { formatSize(it.length()) } }
    Row(Modifier.fillMaxWidth().card(c).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (reorder && count > 1) {
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.width(22.dp))
        }
        FileBadge(s.kind, 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (info != null) Text(info, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        if (reorder && count > 1) {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.convert_move_up), tint = if (index > 0) c.muted else c.line)
            }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.convert_move_down), tint = if (index < count - 1) c.muted else c.line)
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.convert_remove), tint = c.muted) }
    }
}

private fun mimesFor(kind: Kind): Array<String> = when (kind) {
    Kind.PDF -> arrayOf("application/pdf")
    Kind.PPTX -> arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-powerpoint")
    Kind.DOCX -> arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword")
    Kind.TEXT -> arrayOf("text/*", "application/rtf", "application/x-rtf")
    Kind.IMAGE -> arrayOf("image/*")
    Kind.ONENOTE -> arrayOf("application/onenote", "application/msonenote", "application/x-onenote", "application/vnd.ms-cab-compressed", "application/octet-stream")
    else -> arrayOf("*/*")
}

// =====================================================================================================================
// Per-file sheet
// =====================================================================================================================

/** Bottom sheet listing every conversion available for [file]; viewers show it from their "Convert" action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertSheet(file: File, onDismiss: () -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val kind = remember(file) { Storage.kindOf(file) }
    val convs = remember(kind) { Conv.forKind(kind) }
    var step by remember { mutableStateOf<Conv?>(null) }
    var options by remember { mutableStateOf(ConvOptions()) }
    var job by remember { mutableStateOf<ConvertJob?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(file) { if (kind == Kind.PDF) pageCount = withContext(Dispatchers.IO) { Engines.pdfPageCount(file) } }
    val hasInk by produceState(false, file) { value = withContext(Dispatchers.IO) { Engines.hasInk(file) } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun run(conv: Conv, opt: ConvOptions) {
        val dir = file.parentFile?.takeIf { it.absolutePath.startsWith(Storage.root.absolutePath) } ?: Storage.inbox()
        job = ConvertJobs.start(ctx, conv, listOf(Src.Lib(file)), opt, dir)
    }
    fun openHub() { onDismiss(); pane.push(Screen.Convert(file.absolutePath)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FileBadge(kind, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.convert), style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            val j = job
            val st = step
            when {
                j != null -> Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        ConvBadges(j.conv, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(j.conv.title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    }
                    JobPanel(
                        j,
                        onOpen = { f -> onDismiss(); pane.open(ctx, f) },
                        onShare = { shareFiles(ctx, it) },
                        onFolder = { d -> onDismiss(); pane.push(Screen.Library(d.absolutePath)) },
                    )
                    if (!j.running) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { job = null; step = null }) { Text(stringResource(R.string.convert_other), color = c.accent) }
                    }
                }
                st != null -> Column(Modifier.padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { step = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink) }
                        ConvBadges(st, 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(st.title), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    OptionsEditor(st, options, pageCount, hasInk) { options = it }
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(stringResource(R.string.convert_run), enabled = optionsValid(st, options, pageCount), modifier = Modifier.fillMaxWidth()) {
                        run(st, options)
                    }
                }
                convs.isEmpty() -> EmptyState(kindIcon(kind), stringResource(R.string.convert_none))
                else -> {
                    convs.forEach { conv ->
                        val hasOptions = conv.options.any { it != Opt.ANNOTATIONS } || (hasInk && Opt.ANNOTATIONS in conv.options)
                        ConvRow(
                            conv,
                            onClick = {
                                if (conv.needsSetup) { options = conv.defaults(); step = conv } else run(conv, conv.defaults())
                            },
                            onOptions = if (hasOptions && !conv.needsSetup) ({ options = conv.defaults(); step = conv }) else null,
                        )
                    }
                    if (kind == Kind.PDF) LinkRow(Icons.AutoMirrored.Rounded.MergeType, stringResource(R.string.convert_merge_more)) { openHub() }
                    LinkRow(Icons.Rounded.Transform, stringResource(R.string.convert_open_hub)) { openHub() }
                }
            }
        }
    }
}
