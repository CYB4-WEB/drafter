package com.daftar.app.onenote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Screen
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.ZoomControls
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import com.daftar.app.word.ctrlWheelZoom
import com.daftar.app.word.pinchToZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private sealed interface OneLoad {
    data object Loading : OneLoad
    class Failed(val kind: OneError) : OneLoad
    class Ready(val book: OneBook, val pages: List<PageRef>) : OneLoad
}

/** A search hit: page index in the flat page list, paragraph (null = title), range, and the y (pt) of its outline. */
private class Match(val page: Int, val para: OnePara?, val range: IntRange, val y: Float)

/** Export / import running in the background. */
private class Busy(val label: Int) {
    var progress by mutableIntStateOf(-1)
    var done by mutableStateOf<File?>(null)
    var failed by mutableStateOf(false)
    var job: Job? = null
}

private val ZOOM_STEPS = floatArrayOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
/** Section tabs take the folder palette in order (OneNote keeps real section colours in the .onetoc2, which we don't read). */
private val SECTION_COLORS = listOf(0xFFF26D5B, 0xFF3B82F6, 0xFF4CC38A, 0xFF8B6CE0, 0xFFF59E42, 0xFF2EB5B0, 0xFFE56BA6, 0xFF5B6EE8, 0xFF9BC53D, 0xFFB08968)
    .map { Color(it) }

/** Viewer for OneNote sections (.one) and notebook packages (.onepkg). */
@Composable
fun OneNoteScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val actions = rememberViewerActions(file)
    val scope = rememberCoroutineScope()
    val load by produceState<OneLoad>(OneLoad.Loading, path) {
        value = withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            try {
                val book = OneLoader.load(file, File(ctx.cacheDir, "onenote")) { job?.isActive == false }
                OneLoad.Ready(book, book.sections.flatMap { s -> s.pages.map { PageRef(s, it) } })
            } catch (e: OneException) {
                OneLoad.Failed(e.kind)
            } catch (e: InterruptedException) {
                OneLoad.Loading
            } catch (e: Throwable) {
                OneLoad.Failed(OneError.CORRUPT)
            }
        }
    }
    val bitmaps = remember(path) { OneBitmaps() }
    DisposableEffect(bitmaps) { onDispose { bitmaps.release() } }
    val ready = load as? OneLoad.Ready

    var selected by rememberSaveable(path) { mutableIntStateOf(0) }
    var railOpen by rememberSaveable { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    var importDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }

    var zoom by rememberSaveable(path) { mutableFloatStateOf(-1f) }
    var live by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var viewportW by remember { mutableFloatStateOf(0f) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val density = LocalDensity.current

    val pages = ready?.pages ?: emptyList()
    val page = pages.getOrNull(selected.coerceIn(0, max(pages.size - 1, 0)))?.page

    fun fitZoom(): Float {
        val p = page ?: return 1f
        return ((viewportW - 32f) / (pageWidthEstimate(p) * DP_PER_PT)).coerceIn(0.25f, 1.5f)
    }
    LaunchedEffect(viewportW, page) { if (zoom < 0f && viewportW > 0f && page != null) zoom = minOf(1f, fitZoom()) }
    val z = if (zoom > 0f) zoom else 1f

    fun applyZoom(factor: Float, at: Offset) {
        val old = z
        val new = (old * factor).coerceIn(0.25f, 4f)
        if (abs(new - old) < 0.001f) return
        zoom = new
        val ratio = new / old
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            vScroll.scrollTo(((vScroll.value + at.y) * ratio - at.y).roundToInt().coerceIn(0, vScroll.maxValue))
            hScroll.scrollTo(((hScroll.value + at.x) * ratio - at.x).roundToInt().coerceIn(0, hScroll.maxValue))
        }
    }
    fun zoomTo(target: Float) = applyZoom(target / z, Offset(0f, 0f))

    fun select(i: Int) {
        if (i == selected || i !in pages.indices) return
        selected = i
        scope.launch { vScroll.scrollTo(0); hScroll.scrollTo(0) }
    }

    // ------------------------------------------------------------------ search
    BackHandler(enabled = finding) { finding = false; query = "" }
    LaunchedEffect(query, ready) {
        if (ready == null || query.isBlank()) { matches = emptyList(); current = 0; return@LaunchedEffect }
        delay(200)
        val q = query.trim()
        matches = withContext(Dispatchers.Default) { findAll(ready.pages, q) }
        current = matches.indexOfFirst { it.page >= selected }.coerceAtLeast(0)
    }
    fun reveal(m: Match) {
        select(m.page)
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val y = (m.y * z * DP_PER_PT * density.density - 64 * density.density).roundToInt()
            vScroll.animateScrollTo(y.coerceIn(0, vScroll.maxValue))
        }
    }
    LaunchedEffect(matches) { matches.getOrNull(current)?.let { reveal(it) } }
    fun goTo(i: Int) {
        if (matches.isEmpty()) return
        current = (i + matches.size) % matches.size
        reveal(matches[current])
    }
    val hits = remember(matches, current, selected) {
        val mine = matches.filter { it.page == selected && it.para != null }
        if (mine.isEmpty()) PageHits.None
        else PageHits(mine.groupBy { it.para!! }.mapValues { e -> e.value.map { it.range } },
            matches.getOrNull(current)?.takeIf { it.page == selected && it.para != null }?.let { it.para!! to it.range })
    }
    val matchCounts = remember(matches) { matches.groupingBy { it.page }.eachCount() }

    // ------------------------------------------------------------------ background jobs
    val outDir = remember(path) { file.parentFile?.takeIf { it.absolutePath.startsWith(Storage.root.absolutePath) && it.canWrite() } ?: Storage.inbox() }
    val fileLabel = stringResource(R.string.one_attachment)
    fun launchJob(label: Int, work: suspend (Busy) -> File) {
        val b = Busy(label)
        busy = b
        b.job = scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { work(b) }
                Storage.touch()
                b.done = f
            } catch (e: kotlinx.coroutines.CancellationException) {
                busy = null
            } catch (e: InterruptedException) {
                busy = null
            } catch (e: Throwable) {
                b.failed = true
            }
        }
    }
    fun cancelledOf(b: Busy): () -> Boolean = { b.job?.isActive == false }
    fun bookName() = ready?.book?.name ?: file.nameWithoutExtension
    fun exportPdf() {
        val all = ready?.pages ?: return
        launchJob(R.string.one_exporting) { b ->
            val out = Storage.uniqueFile(outDir, bookName(), "pdf")
            OneExport.pdf(all, out, fileLabel, { b.progress = it }, cancelledOf(b))
            out
        }
    }
    fun exportText() {
        val all = ready?.pages ?: return
        launchJob(R.string.one_exporting) { _ ->
            val out = Storage.uniqueFile(outDir, bookName(), "txt")
            out.writeText(OneExport.text(all))
            out
        }
    }
    fun importNote(allPages: Boolean) {
        val r = ready ?: return
        val list = if (allPages) r.pages else listOfNotNull(r.pages.getOrNull(selected))
        if (list.isEmpty()) return
        val base = if (allPages) bookName() else (bookName() + " - " + list[0].page.title.ifBlank { ctx.getString(R.string.one_untitled) }).take(80)
        launchJob(R.string.one_importing) { b ->
            val out = Storage.uniqueFile(outDir, base, Storage.NOTE_EXT)
            OneExport.note(list, out, cancelledOf(b))
            out
        }
    }
    fun openAttachment(a: OneAttachment) {
        if (a.data == null) { toast(ctx, ctx.getString(R.string.one_attachment_missing)); return }
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                val f = Storage.uniqueNamed(outDir, Storage.sanitize(a.name).ifBlank { "attachment" })
                if (OneExport.saveAttachment(a, f)) f else null
            }
            if (out == null) { toast(ctx, ctx.getString(R.string.one_failed)); return@launch }
            Storage.touch()
            toast(ctx, ctx.getString(R.string.one_attachment_saved, out.name))
            pane.open(ctx, out)
        }
    }
    fun openUrl(url: String) {
        if (Prefs.linksInApp && (url.startsWith("http://", true) || url.startsWith("https://", true))) { pane.push(Screen.Web(url)); return }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(ctx, ctx.getString(R.string.no_app_found)) }
    }

    // ------------------------------------------------------------------ UI
    BoxWithConstraints(Modifier.fillMaxSize().background(D.c.bg)) {
        val narrow = maxWidth < 420.dp
        val railMode = LocalWidthClass.current != WidthClass.Compact && maxWidth >= 640.dp
        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(title = ready?.book?.name ?: file.nameWithoutExtension, onBack = { pane.back() }) {
                if (ready != null && pages.isNotEmpty()) {
                    IconButton(onClick = { finding = !finding; if (!finding) query = "" }) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.one_search), tint = if (finding) D.c.accent else D.c.ink)
                    }
                    if (railMode) IconButton(onClick = { railOpen = !railOpen }) {
                        Icon(Icons.AutoMirrored.Rounded.ViewSidebar, stringResource(R.string.one_toggle_pages), tint = if (railOpen) D.c.accent else D.c.ink)
                    } else IconButton(onClick = { sheet = true }) {
                        Icon(Icons.AutoMirrored.Rounded.ViewList, stringResource(R.string.one_pages), tint = D.c.ink)
                    }
                }
                if (!narrow) ConvertButton(actions)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ViewerMenuItems(actions, close = { menu = false })
                        if (ready != null && pages.isNotEmpty()) {
                            HorizontalDivider(color = D.c.line)
                            DropdownMenuItem({ Text(stringResource(R.string.one_import_note)) }, { menu = false; importDialog = true },
                                leadingIcon = { Icon(Icons.Rounded.Draw, null, tint = D.c.muted) })
                            DropdownMenuItem({ Text(stringResource(R.string.one_export_pdf)) }, { menu = false; exportPdf() },
                                leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null, tint = D.c.muted) })
                            DropdownMenuItem({ Text(stringResource(R.string.one_export_text)) }, { menu = false; exportText() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.TextSnippet, null, tint = D.c.muted) })
                            val copied = stringResource(R.string.one_copied)
                            DropdownMenuItem({ Text(stringResource(R.string.one_copy_page)) }, {
                                menu = false
                                page?.let { copyText(ctx, it.title, it.plainText()); toast(ctx, copied) }
                            }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = D.c.muted) })
                        }
                    }
                }
            }
            if (finding && ready != null) {
                FindBar(query, { query = it }, matches.size, current, onPrev = { goTo(current - 1) }, onNext = { goTo(current + 1) },
                    onClose = { finding = false; query = "" })
            }
            when (val l = load) {
                OneLoad.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = D.c.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.one_loading), color = D.c.muted, style = MaterialTheme.typography.bodyLarge)
                }
                is OneLoad.Failed -> ErrorState(l.kind) { openExternally(ctx, file) }
                is OneLoad.Ready -> {
                    if (l.pages.isEmpty()) {
                        val err = l.book.sections.firstNotNullOfOrNull { it.error }
                        if (err != null) ErrorState(err) { openExternally(ctx, file) }
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.AutoMirrored.Rounded.ViewList, stringResource(R.string.one_no_pages))
                        }
                    } else Row(Modifier.fillMaxSize()) {
                        if (railMode && railOpen) {
                            Column(Modifier.width(280.dp).fillMaxHeight().background(D.c.surface)) {
                                PageList(l.book, l.pages, selected, matchCounts, Modifier.weight(1f)) { select(it) }
                            }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(D.c.line))
                        }
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                                .pinchToZoom(onPinch = { f, c -> pivot = c; live = (live * f).coerceIn(0.25f / z, 4f / z) },
                                    onEnd = { val f = live; live = 1f; applyZoom(f, pivot) })
                                .ctrlWheelZoom { f, at -> applyZoom(f, at) },
                        ) {
                            val vw = maxWidth.value
                            val vh = maxHeight.value
                            LaunchedEffect(vw) { viewportW = vw }
                            Box(Modifier.fillMaxSize().graphicsLayer {
                                scaleX = live; scaleY = live
                                transformOrigin = if (size.width > 0f && size.height > 0f)
                                    TransformOrigin((pivot.x / size.width).coerceIn(0f, 1f), (pivot.y / size.height).coerceIn(0f, 1f)) else TransformOrigin.Center
                            }) {
                                val p = page
                                if (p != null) Box(Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(16.dp)) {
                                    SelectionContainer {
                                        OnePageCanvas(p, z, bitmaps, hits, minW = vw - 32f, minH = vh - 32f, onLink = { openUrl(it) }, onFile = { openAttachment(it) })
                                    }
                                }
                            }
                            ZoomControls(
                                percent = (z * live * 100).roundToInt(),
                                onOut = { zoomTo(ZOOM_STEPS.lastOrNull { it < z * 0.99f } ?: ZOOM_STEPS.first()) },
                                onIn = { zoomTo(ZOOM_STEPS.firstOrNull { it > z * 1.01f } ?: ZOOM_STEPS.last()) },
                                onFit = { zoomTo(fitZoom()) },
                                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
                            )
                            if (!(railMode && railOpen) && l.pages.size > 1) {
                                PagePager(selected, l.pages.size, { select(selected - 1) }, { select(selected + 1) },
                                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp))
                            }
                        }
                    }
                    if (sheet) PageSheet(l.book, l.pages, selected, matchCounts, onDismiss = { sheet = false }) { sheet = false; select(it) }
                }
            }
        }
    }

    if (importDialog && ready != null) {
        ImportDialog(ready.pages.size, onDismiss = { importDialog = false }) { all -> importDialog = false; importNote(all) }
    }
    busy?.let { b -> BusyDialog(b, onCancel = { b.job?.cancel(); busy = null }, onClose = { busy = null }, onOpen = { f -> busy = null; pane.open(ctx, f) }) }
}

/** Rough width of a page's content in pt (for "fit width"). */
private fun pageWidthEstimate(p: OnePage): Float {
    var r = p.titleX + 300f
    for (it in p.items) {
        val w = when (it) {
            is OneOutline -> if (it.width > 20f) it.width else AUTO_OUTLINE_PT
            is OneImageItem -> if (it.image.widthPt > 0f) it.image.widthPt else 200f
            is OneInkItem -> it.ink.bounds[2]
            is OneFileItem -> 200f
        }
        r = max(r, it.x + w)
    }
    return (r + 48f).coerceIn(300f, 4000f)
}

private fun findAll(pages: List<PageRef>, q: String): List<Match> {
    val out = ArrayList<Match>()
    fun scan(text: String, page: Int, para: OnePara?, y: Float) {
        var i = text.indexOf(q, ignoreCase = true)
        while (i >= 0 && out.size < 5000) {
            out.add(Match(page, para, i until i + q.length, y))
            i = text.indexOf(q, i + q.length, ignoreCase = true)
        }
    }
    fun walk(blocks: List<OneBlock>, page: Int, y: Float) {
        for (b in blocks) when (b) {
            is OnePara -> scan(b.text, page, b, y)
            is OneTable -> b.rows.forEach { r -> r.forEach { c -> walk(c, page, y) } }
            else -> {}
        }
    }
    for ((pi, ref) in pages.withIndex()) {
        scan(ref.page.title, pi, null, 0f)
        for (it in ref.page.items.sortedWith(compareBy({ it.y }, { it.x }))) if (it is OneOutline) walk(it.blocks, pi, it.y)
        if (out.size >= 5000) break
    }
    return out
}

private fun copyText(ctx: Context, label: String, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun errorText(kind: OneError): Int = when (kind) {
    OneError.ENCRYPTED -> R.string.one_err_encrypted
    OneError.CLOUD -> R.string.one_err_cloud
    OneError.TOC -> R.string.one_err_toc
    OneError.OLD_FORMAT -> R.string.one_err_old
    OneError.NOT_ONENOTE -> R.string.one_err_not_onenote
    OneError.CORRUPT -> R.string.one_err_corrupt
    OneError.PACKAGE_COMPRESSION -> R.string.one_err_compression
    OneError.EMPTY_PACKAGE -> R.string.one_err_empty_pkg
    OneError.IO -> R.string.one_err_io
}

@Composable
private fun ErrorState(kind: OneError, onOpenExternally: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        EmptyState(if (kind == OneError.ENCRYPTED) Icons.Rounded.Lock else Icons.Rounded.ErrorOutline, stringResource(errorText(kind))) {
            Button(onClick = onOpenExternally) { Text(stringResource(R.string.open_externally)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------- page list

@Composable
private fun PageList(book: OneBook, pages: List<PageRef>, selected: Int, counts: Map<Int, Int>, modifier: Modifier = Modifier,
                     state: LazyListState = rememberLazyListState(), onPick: (Int) -> Unit) {
    val c = D.c
    val dateFmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    LaunchedEffect(Unit) {
        // bring the open page into view (header rows + page rows before it)
        var row = 0; var idx = 0
        for (s in book.sections) {
            row++
            for (p in s.pages) { if (idx == selected) { state.scrollToItem((row - 2).coerceAtLeast(0)); return@LaunchedEffect }; row++; idx++ }
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        var index = 0
        for ((si, s) in book.sections.withIndex()) {
            val color = SECTION_COLORS[si % SECTION_COLORS.size]
            item(key = "s$si") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (si == 0) 4.dp else 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 18.dp).background(color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = when (s.error) {
                            null -> null
                            OneError.ENCRYPTED -> stringResource(R.string.one_section_locked)
                            else -> stringResource(R.string.one_section_unreadable)
                        }
                        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.muted)
                    }
                    if (s.error == OneError.ENCRYPTED) Icon(Icons.Rounded.Lock, null, tint = c.muted, modifier = Modifier.size(16.dp))
                }
            }
            for (p in s.pages) {
                val i = index++
                item(key = "p$i") {
                    val sel = i == selected
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)
                            .background(if (sel) c.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(12.dp))
                            .selectable(selected = sel, role = Role.Tab) { onPick(i) }
                            .padding(start = (12 + (p.level - 1) * 16).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title.ifBlank { stringResource(R.string.one_untitled) }, style = MaterialTheme.typography.bodyMedium,
                                color = if (sel) c.accent else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (p.created > 0) Text(dateFmt.format(Date(p.created)), style = MaterialTheme.typography.labelSmall, color = c.muted)
                        }
                        counts[i]?.let { n ->
                            Text(n.toString(), style = MaterialTheme.typography.labelSmall, color = c.ink,
                                modifier = Modifier.background(Color(0x66FACC15), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageSheet(book: OneBook, pages: List<PageRef>, selected: Int, counts: Map<Int, Int>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = D.c.surface) {
        Text(stringResource(R.string.one_pages), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 4.dp))
        PageList(book, pages, selected, counts, Modifier.navigationBarsPadding(), onPick = onPick)
    }
}

@Composable
private fun PagePager(index: Int, count: Int, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier) {
    val c = D.c
    Row(
        modifier.background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = index > 0, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.one_prev_page), tint = if (index > 0) c.ink else c.line)
        }
        Text(stringResource(R.string.one_page_of, index + 1, count), style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1)
        IconButton(onClick = onNext, enabled = index < count - 1, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.one_next_page), tint = if (index < count - 1) c.ink else c.line)
        }
    }
}

// ---------------------------------------------------------------------------------------------- find bar

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int, current: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(Modifier.fillMaxWidth().background(D.c.surface).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.one_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = D.c.muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = D.c.accent, unfocusedBorderColor = D.c.line,
                focusedContainerColor = D.c.surfaceAlt, unfocusedContainerColor = D.c.surfaceAlt,
            ),
            modifier = Modifier.weight(1f).focusRequester(fr),
        )
        Text(
            when {
                query.isBlank() -> ""
                count == 0 -> stringResource(R.string.one_find_none)
                else -> stringResource(R.string.one_find_count, current + 1, count)
            },
            style = MaterialTheme.typography.labelMedium, color = D.c.muted, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.one_find_prev), tint = D.c.muted) }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.one_find_next), tint = D.c.muted) }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.one_find_close), tint = D.c.muted) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))
}

// ---------------------------------------------------------------------------------------------- dialogs

@Composable
private fun ImportDialog(total: Int, onDismiss: () -> Unit, onImport: (Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    val c = D.c
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.one_import_note)) },
        text = {
            Column {
                Text(stringResource(R.string.one_import_desc), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                Spacer(Modifier.height(12.dp))
                for (opt in listOf(false, true)) {
                    Row(Modifier.fillMaxWidth().selectable(selected = all == opt, role = Role.RadioButton) { all = opt }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = all == opt, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
                        Spacer(Modifier.width(12.dp))
                        Text(if (opt) stringResource(R.string.one_scope_all, total) else stringResource(R.string.one_scope_page), color = c.ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(all) }) { Text(stringResource(R.string.one_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun BusyDialog(b: Busy, onCancel: () -> Unit, onClose: () -> Unit, onOpen: (File) -> Unit) {
    val done = b.done
    AlertDialog(
        onDismissRequest = { if (done != null || b.failed) onClose() },
        title = { Text(stringResource(if (done != null) R.string.one_done else b.label)) },
        text = {
            Column {
                when {
                    b.failed -> Text(stringResource(R.string.one_failed), color = D.c.ink)
                    done != null -> Text(stringResource(R.string.saved_to, done.name), color = D.c.ink)
                    b.progress >= 0 -> LinearProgressIndicator(progress = { b.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = D.c.accent)
                    else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = D.c.accent)
                }
            }
        },
        confirmButton = {
            when {
                done != null -> TextButton(onClick = { onOpen(done) }) { Text(stringResource(R.string.one_open)) }
                b.failed -> TextButton(onClick = onClose) { Text(stringResource(R.string.one_close)) }
                else -> {}
            }
        },
        dismissButton = {
            when {
                done != null -> TextButton(onClick = onClose) { Text(stringResource(R.string.one_close)) }
                b.failed -> {}
                else -> TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
