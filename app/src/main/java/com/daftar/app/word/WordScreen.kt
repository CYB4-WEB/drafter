package com.daftar.app.word

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ChromeReaderMode
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.daftar.app.R
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.ZoomControls
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarColors
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Read-layout zoom (text scale), kept in memory for the app session. */
private object WordPrefs {
    var scale by mutableFloatStateOf(1f)
}

/** dp per point at 100% in the print layout (96-dpi "100%" like desktop Word: A4 ≈ 794dp wide). */
private const val PRINT_DP_PER_PT = 4f / 3f
private val PRINT_STEPS = floatArrayOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
private val READ_STEPS = floatArrayOf(0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.25f, 1.4f, 1.6f, 1.8f, 2f, 2.5f)

private sealed interface DocLoad {
    data object Loading : DocLoad
    data object Error : DocLoad
    data object Legacy : DocLoad
    class Ready(val doc: DocxDoc) : DocLoad
}

private enum class Mode { PRINT, READ, SHEET }

private fun stepUp(steps: FloatArray, v: Float) = steps.firstOrNull { it > v * 1.01f } ?: steps.last()
private fun stepDown(steps: FloatArray, v: Float) = steps.lastOrNull { it < v * 0.99f } ?: steps.first()

@Composable
fun WordScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val load by produceState<DocLoad>(DocLoad.Loading, path) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) DocLoad.Error else DocLoad.Ready(DocLoader.load(file))
            } catch (_: LegacyDocException) {
                DocLoad.Legacy
            } catch (_: Throwable) {
                DocLoad.Error
            }
        }
    }
    val actions = rememberViewerActions(file)
    val images = remember(path) { DocxImages(path) }
    DisposableEffect(images) { onDispose { images.close() } }
    val doc = (load as? DocLoad.Ready)?.doc

    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    var outlineOn by rememberSaveable { mutableStateOf(true) }
    var outlineSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    var printChoice by rememberSaveable(path) { mutableStateOf<Boolean?>(null) }
    var layoutFailed by remember(doc) { mutableStateOf(false) }

    val readList = rememberLazyListState()
    val printList = rememberLazyListState()
    val sheetList = rememberLazyListState()
    val printH = rememberScrollState()
    val sheetH = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val mode = when {
        doc == null -> Mode.READ
        doc.sheet != null -> Mode.SHEET
        layoutFailed -> Mode.READ
        else -> if (printChoice ?: defaultPrint(doc, file)) Mode.PRINT else Mode.READ
    }

    // ---------------------------------------------------------------- zoom state
    var printZoom by rememberSaveable(path) { mutableFloatStateOf(-1f) }
    var sheetZoom by rememberSaveable(path) { mutableFloatStateOf(1f) }
    val readScale = WordPrefs.scale
    var live by remember { mutableFloatStateOf(1f) }
    var pivot by remember { mutableStateOf(Offset.Zero) }
    var viewportW by remember { mutableFloatStateOf(0f) }

    fun printFit(): Float {
        val page = doc?.page ?: return 1f
        return ((viewportW - 32f) / (page.w * PRINT_DP_PER_PT)).coerceIn(0.2f, 4f)
    }
    val zoomValue = when (mode) { Mode.PRINT -> max(printZoom, 0.2f); Mode.READ -> readScale; Mode.SHEET -> sheetZoom }

    /** Applies a zoom factor around [at] (px in the viewer), keeping that point steady. */
    fun applyZoom(factor: Float, at: Offset) {
        val list = when (mode) { Mode.PRINT -> printList; Mode.READ -> readList; Mode.SHEET -> sheetList }
        val h = when (mode) { Mode.PRINT -> printH; Mode.SHEET -> sheetH; Mode.READ -> null }
        val old = zoomValue
        val new = when (mode) {
            Mode.PRINT -> (old * factor).coerceIn(0.2f, 4f)
            Mode.READ -> (old * factor).coerceIn(0.5f, 2.5f)
            Mode.SHEET -> (old * factor).coerceIn(0.5f, 3f)
        }
        if (abs(new - old) < 0.001f) return
        val ratio = new / old
        when (mode) { Mode.PRINT -> printZoom = new; Mode.READ -> WordPrefs.scale = new; Mode.SHEET -> sheetZoom = new }
        val idx = list.firstVisibleItemIndex
        val off = list.firstVisibleItemScrollOffset
        scope.launch {
            list.scrollToItem(idx, ((off + at.y) * ratio - at.y).roundToInt().coerceAtLeast(0))
            if (h != null) {
                withFrameNanos { }
                withFrameNanos { }
                h.scrollTo(((h.value + at.x) * ratio - at.x).roundToInt().coerceIn(0, h.maxValue))
            }
        }
    }
    /** [at] is in pixels of the viewer; the pill zooms around the top centre so the current text stays in view. */
    fun zoomTo(target: Float, at: Offset = Offset(viewportW * density.density / 2f, 0f)) { if (zoomValue > 0f) applyZoom(target / zoomValue, at) }

    // ---------------------------------------------------------------- pagination (background, published progressively)
    val pagesFlow = remember(doc) { MutableStateFlow<List<LaidPage>>(emptyList()) }
    val pages by pagesFlow.collectAsState()
    var layoutDone by remember(doc) { mutableStateOf(false) }
    LaunchedEffect(doc, mode) {
        if (doc == null || mode != Mode.PRINT || layoutDone) return@LaunchedEffect
        val ok = withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            val acc = ArrayList<LaidPage>()
            var last = 0L
            try {
                val pg = Paginator(doc.page, TextEngine(), onPage = { p ->
                    acc.add(p)
                    val now = SystemClock.uptimeMillis()
                    if (acc.size <= 3 || now - last > 150) { last = now; pagesFlow.value = ArrayList(acc) }
                }, cancelled = { job?.isActive == false })
                pg.addAll(doc.blocks)
                pg.finish()
                pagesFlow.value = ArrayList(acc)
                true
            } catch (_: Paginator.Cancelled) {
                null
            } catch (_: Throwable) {
                false
            }
        }
        when (ok) { true -> layoutDone = true; false -> layoutFailed = true; null -> {} }
    }

    // ---------------------------------------------------------------- find
    BackHandler(enabled = finding) { finding = false; query = "" }
    LaunchedEffect(query, doc) {
        if (doc == null || query.isBlank()) { matches = emptyList(); current = 0; return@LaunchedEffect }
        delay(180)
        val q = query
        matches = withContext(Dispatchers.Default) { findAll(doc, q) }
        current = 0
    }
    val hits = remember(matches, current) {
        if (doc?.sheet != null) Hits.None
        else Hits(matches.groupBy { it.pid }.mapValues { e -> e.value.map { it.start until it.end } }, matches.getOrNull(current))
    }
    val sheetHits = remember(matches, doc) {
        if (doc?.sheet == null) emptyMap()
        else matches.groupBy { it.pid }.mapValues { e -> e.value.groupBy { it.col }.mapValues { c -> c.value.map { it.start until it.end } } }
    }
    /** y offset (px) of the slice holding [pid]/[offset] on its page, for scrolling the print layout. */
    fun sliceOffsetPx(pageIdx: Int, pid: Int, offset: Int): Int {
        val d = doc ?: return 0
        val page = pages.getOrNull(pageIdx) ?: return 0
        var y = d.page.top
        for (s in page.slices) {
            val hit = when (s) {
                is TextSlice -> s.para.pid == pid && offset >= s.start && offset <= s.end
                is TableSlice -> true.takeIf { s.rows.any { r -> s.table.rows[r].cells.any { c -> c.blocks.any { b -> b is DocBlock.Para && b.pid == pid } } } } ?: false
                else -> false
            }
            if (hit) break
            y += s.gap + s.height + s.after
        }
        val k = max(printZoom, 0.2f) * PRINT_DP_PER_PT * density.density
        return (y * k - 48 * density.density).roundToInt().coerceAtLeast(0)
    }

    fun reveal(m: Match) {
        val d = doc ?: return
        scope.launch {
            when (mode) {
                Mode.SHEET -> {
                    sheetList.animateScrollToItem((m.pid).coerceAtLeast(0))
                    val s = d.sheet ?: return@launch
                    val charDp = 7.6f * sheetZoom
                    var x = (s.rows.size.toString().length * 8f + 20f) * sheetZoom
                    for (c in 0 until m.col.coerceAtMost(s.cols)) x += s.charWidths[c].coerceIn(3, 40) * charDp + 20f * sheetZoom
                    sheetH.animateScrollTo((x * density.density - viewportW * density.density / 3).roundToInt().coerceIn(0, sheetH.maxValue))
                }
                Mode.READ -> readList.animateScrollToItem(m.top + 1)
                Mode.PRINT -> {
                    val pg = pageOf(pages, m.pid, m.start)
                    if (pg >= 0) printList.animateScrollToItem(pg, sliceOffsetPx(pg, m.pid, m.start))
                }
            }
        }
    }
    LaunchedEffect(matches) { matches.firstOrNull()?.let { reveal(it) } }
    fun goTo(i: Int) {
        if (matches.isEmpty()) return
        current = (i + matches.size) % matches.size
        reveal(matches[current])
    }
    fun jump(h: Heading) {
        val d = doc ?: return
        scope.launch {
            if (mode == Mode.PRINT) {
                val pid = (d.blocks.getOrNull(h.blockIndex) as? DocBlock.Para)?.pid ?: return@launch
                val pg = pageOf(pages, pid, 0)
                if (pg >= 0) printList.animateScrollToItem(pg, sliceOffsetPx(pg, pid, 0))
            } else readList.animateScrollToItem(h.blockIndex + 1)
        }
    }

    // ---------------------------------------------------------------- UI
    BoxWithConstraints(Modifier.fillMaxSize().background(D.c.bg)) {
        val narrow = maxWidth < 400.dp
        val wideScreen = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize()) {
            ViewerTopBar(title = file.nameWithoutExtension, onBack = { pane.back() }) {
                if (doc != null) {
                    IconButton(onClick = { finding = !finding; if (!finding) query = "" }) {
                        Icon(Icons.Rounded.Search, stringResource(R.string.word_find), tint = if (finding) D.c.accent else D.c.ink)
                    }
                    if (doc.sheet == null) Box {
                        IconButton(onClick = { viewMenu = true }) {
                            Icon(Icons.AutoMirrored.Rounded.ChromeReaderMode, stringResource(R.string.word_view), tint = if (viewMenu) D.c.accent else D.c.ink)
                        }
                        DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                            CheckRow(stringResource(R.string.word_layout_print), Icons.Rounded.Print, mode == Mode.PRINT) {
                                viewMenu = false; printChoice = true; layoutFailed = false
                            }
                            CheckRow(stringResource(R.string.word_layout_read), Icons.AutoMirrored.Rounded.MenuBook, mode == Mode.READ) {
                                viewMenu = false; printChoice = false
                            }
                            if (doc.headings.isNotEmpty()) {
                                HorizontalDivider(color = D.c.line)
                                CheckRow(stringResource(R.string.word_outline), Icons.AutoMirrored.Rounded.Toc, if (wideScreen) outlineOn else false) {
                                    viewMenu = false
                                    if (wideScreen) outlineOn = !outlineOn else outlineSheet = true
                                }
                            }
                        }
                    }
                }
                if (!narrow) ConvertButton(actions)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        ViewerMenuItems(actions, close = { menu = false })
                        if (doc != null) {
                            val copiedMsg = stringResource(R.string.word_copied)
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.word_copy_all)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = D.c.muted) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val text = withContext(Dispatchers.Default) { doc.plainText() }
                                        copyText(ctx, file.nameWithoutExtension, text)
                                        toast(ctx, copiedMsg)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (finding && doc != null) {
                FindBar(query, { query = it }, matches.size, current, onPrev = { goTo(current - 1) }, onNext = { goTo(current + 1) },
                    onClose = { finding = false; query = "" })
            }
            if (doc != null && doc.kind == DocKind.DOC) {
                Banner(stringResource(R.string.word_doc_notice), stringResource(R.string.open_externally)) { openExternally(ctx, file) }
            }
            if (doc != null && doc.truncatedAt > 0) {
                Banner(stringResource(R.string.word_truncated, Formatter.formatShortFileSize(ctx, doc.truncatedAt)), stringResource(R.string.open_externally)) {
                    openExternally(ctx, file)
                }
            }

            when (val l = load) {
                DocLoad.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = D.c.accent)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.word_loading), color = D.c.muted, style = MaterialTheme.typography.bodyLarge)
                }
                DocLoad.Error, DocLoad.Legacy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Rounded.ErrorOutline, stringResource(if (l == DocLoad.Legacy) R.string.word_legacy else R.string.word_error)) {
                        Button(onClick = { openExternally(ctx, file) }) { Text(stringResource(R.string.open_externally)) }
                    }
                }
                is DocLoad.Ready -> {
                    val d = l.doc
                    val empty = d.blocks.isEmpty() && (d.sheet == null || d.sheet.rows.isEmpty())
                    if (empty) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.Rounded.Description, stringResource(R.string.word_empty))
                        }
                    } else Row(Modifier.fillMaxSize()) {
                        BoxWithConstraints(
                            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                                .pinchToZoom(onPinch = { z, c -> pivot = c; live = (live * z).coerceIn(0.25f, 5f) },
                                    onEnd = { val f = live; live = 1f; applyZoom(f, pivot) })
                                .ctrlWheelZoom { f, at -> applyZoom(f, at) }
                                .stylusDoubleTap { at ->
                                    // Pen double-tap: fit width ↔ 2× (print), 100% ↔ 160% (read / sheet)
                                    val target = when (mode) {
                                        Mode.PRINT -> if (zoomValue < printFit() * 1.4f) printFit() * 2f else printFit()
                                        else -> if (zoomValue < 1.3f) 1.6f else 1f
                                    }
                                    zoomTo(target, at)
                                },
                        ) {
                            val w = maxWidth.value
                            LaunchedEffect(w) {
                                viewportW = w
                                if (printZoom < 0f) printZoom = min(printFit(), 1f)
                            }
                            Box(Modifier.fillMaxSize().graphicsLayer {
                                scaleX = live; scaleY = live
                                transformOrigin = if (size.width > 0f && size.height > 0f)
                                    TransformOrigin((pivot.x / size.width).coerceIn(0f, 1f), (pivot.y / size.height).coerceIn(0f, 1f)) else TransformOrigin.Center
                            }) {
                                when (mode) {
                                    Mode.SHEET -> SheetView(d.sheet!!, sheetZoom, sheetList, sheetH, sheetHits, matches.getOrNull(current))
                                    Mode.READ -> ReadView(d, images, readList, readScale, hits)
                                    Mode.PRINT -> if (printZoom > 0f) PrintView(d, pages, layoutDone, printZoom, printList, printH, images, hits)
                                }
                            }
                            if (mode == Mode.PRINT) PageCounter(printList, pages.size, layoutDone, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                            val pct = (zoomValue * live * 100).roundToInt()
                            ZoomControls(
                                percent = pct,
                                onOut = { zoomTo(stepDown(if (mode == Mode.PRINT) PRINT_STEPS else READ_STEPS, zoomValue)) },
                                onIn = { zoomTo(stepUp(if (mode == Mode.PRINT) PRINT_STEPS else READ_STEPS, zoomValue)) },
                                onFit = { zoomTo(if (mode == Mode.PRINT) printFit() else 1f) },
                                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
                            )
                        }
                        if (wideScreen && outlineOn && d.headings.isNotEmpty() && mode != Mode.SHEET) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(D.c.line))
                            Column(Modifier.width(280.dp).fillMaxHeight().background(D.c.surface)) {
                                Text(stringResource(R.string.word_outline), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
                                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                                OutlineList(d.headings, Modifier.weight(1f)) { jump(it) }
                            }
                        }
                    }
                    if (outlineSheet) OutlineSheet(d.headings, onDismiss = { outlineSheet = false }) { outlineSheet = false; jump(it) }
                }
            }
        }
    }
}

/** Print layout by default for documents; long plain text and logs open in the read layout (smooth, no pagination wait). */
private fun defaultPrint(doc: DocxDoc, f: File): Boolean = when (doc.kind) {
    DocKind.DOCX, DocKind.DOC, DocKind.RTF, DocKind.MD -> true
    DocKind.TXT -> f.length() < 512 * 1024
    DocKind.LOG, DocKind.CSV -> false
}

private fun findAll(doc: DocxDoc, q: String): List<Match> {
    val out = ArrayList<Match>()
    val sheet = doc.sheet
    if (sheet != null) {
        for ((r, row) in sheet.rows.withIndex()) {
            for ((c, s) in row.withIndex()) {
                var i = s.indexOf(q, ignoreCase = true)
                while (i >= 0 && out.size < 5000) { out.add(Match(r, i, i + q.length, r, c)); i = s.indexOf(q, i + q.length, ignoreCase = true) }
            }
            if (out.size >= 5000) break
        }
        return out
    }
    for ((p, top) in doc.paragraphs) {
        var i = p.text.indexOf(q, ignoreCase = true)
        while (i >= 0 && out.size < 5000) {
            out.add(Match(p.pid, i, i + q.length, top))
            i = p.text.indexOf(q, i + q.length, ignoreCase = true)
        }
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

@Composable
private fun CheckRow(text: String, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, color = if (checked) D.c.ink else D.c.ink) },
        leadingIcon = { Icon(icon, null, tint = if (checked) D.c.accent else D.c.muted) },
        trailingIcon = { if (checked) Icon(Icons.Rounded.Check, null, tint = D.c.accent) else Spacer(Modifier.size(24.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Info, null, tint = c.muted, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        TextButton(onClick = onAction) { Text(action, color = c.accent, maxLines = 1) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}

/** "Page 3 of 12" pill while scrolling the print layout. */
@Composable
private fun PageCounter(state: LazyListState, total: Int, done: Boolean, modifier: Modifier) {
    val c = D.c
    val page by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            (info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size >= center }?.index ?: state.firstVisibleItemIndex) + 1
        }
    }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) show = true else { delay(1200); show = false }
    }
    AnimatedVisibility(visible = show && total > 0, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            stringResource(if (done) R.string.word_page_of else R.string.word_page_of_more, page.coerceIn(1, max(total, 1)), total),
            style = MaterialTheme.typography.labelMedium, color = c.ink,
            modifier = Modifier.background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

// ------------------------------------------------------------------ print layout view

@Composable
private fun PrintView(doc: DocxDoc, pages: List<LaidPage>, done: Boolean, zoom: Float, list: LazyListState, h: ScrollState, images: DocxImages, hits: Hits) {
    val c = D.c
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val density = LocalDensity.current
        val k = zoom * PRINT_DP_PER_PT * density.density
        // The page is geometry, not UI text: font scale 1 keeps sizes linear (Android 14 scales large fonts non-linearly).
        val paperDensity = remember(density.density) { Density(density.density, 1f) }
        val scale = remember(k, density.density) { PaperScale(k, density.density, 1f) }
        val pageW = doc.page.w * zoom * PRINT_DP_PER_PT
        val gutter = 16f
        val contentW = max(maxWidth.value, pageW + 2 * gutter)
        val scrollsX = pageW + 2 * gutter > maxWidth.value + 0.5f
        CompositionLocalProvider(LocalDensity provides paperDensity) { SelectionContainer {
            Box(Modifier.fillMaxSize().then(if (scrollsX) Modifier.horizontalScroll(h) else Modifier)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.width(contentW.dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
                ) {
                    items(count = pages.size, key = { it }) { i -> PrintPage(pages[i], doc.page, scale, images, hits) }
                    if (!done) item(key = "more") {
                        CompositionLocalProvider(LocalDensity provides density) {
                            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.word_paginating), color = c.muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        } }
    }
}

// ------------------------------------------------------------------ read (web) layout view

@Composable
private fun ReadView(doc: DocxDoc, images: DocxImages, listState: LazyListState, scale: Float, hits: Hits) {
    val c = D.c
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val card = maxWidth >= (MAX_TEXT_WIDTH + 48).dp
        val gutter = if (card) 24.dp else 0.dp
        val inner = if (card) 40.dp else if (maxWidth >= 600.dp) 24.dp else 16.dp
        val pageWidth = min(maxWidth.value - gutter.value * 2, MAX_TEXT_WIDTH.toFloat() + if (card) inner.value * 2 else 0f).dp
        val textWidth = pageWidth - inner * 2
        val pageMod = Modifier.width(pageWidth)
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(if (card) c.bg else c.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = if (card) 24.dp else 0.dp, bottom = 96.dp),
            ) {
                item(key = "top") { Spacer(pageMod.height(if (card) 40.dp else 20.dp).pageSegment(c, card, top = true, bottom = false)) }
                itemsIndexed(doc.blocks) { _, b ->
                    Box(pageMod.pageSegment(c, card, top = false, bottom = false).padding(horizontal = inner)) {
                        BlockView(b, images, scale, hits, textWidth, allowScroll = true)
                    }
                }
                item(key = "bottom") { Spacer(pageMod.height(if (card) 48.dp else 32.dp).pageSegment(c, card, top = false, bottom = true)) }
            }
        }
    }
}

/** Draws one horizontal slice of the reading card (surface + 1dp line border; rounded caps at the ends). */
private fun Modifier.pageSegment(c: DaftarColors, card: Boolean, top: Boolean, bottom: Boolean): Modifier =
    if (!card) this.background(c.surface) else this.drawBehind {
        val r = 16.dp.toPx(); val sw = 1.dp.toPx()
        val y0 = if (top) 0f else -r * 2
        val y1 = if (bottom) size.height else size.height + r * 2
        clipRect {
            drawRoundRect(c.surface, Offset(0f, y0), Size(size.width, y1 - y0), CornerRadius(r))
            drawRoundRect(c.line, Offset(sw / 2, y0 + sw / 2), Size(size.width - sw, y1 - y0 - sw), CornerRadius(r), style = Stroke(sw))
        }
    }

// ------------------------------------------------------------------ find bar

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int, current: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(D.c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.word_find_hint)) },
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
                count == 0 -> stringResource(R.string.word_find_none)
                else -> stringResource(R.string.word_find_count, current + 1, count)
            },
            style = MaterialTheme.typography.labelMedium, color = D.c.muted, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.word_find_prev), tint = D.c.muted) }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.word_find_next), tint = D.c.muted) }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.word_find_close), tint = D.c.muted) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))
}

// ------------------------------------------------------------------ outline

@Composable
private fun OutlineList(headings: List<Heading>, modifier: Modifier = Modifier, onPick: (Heading) -> Unit) {
    val minLevel = headings.minOfOrNull { it.level } ?: 1
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(headings) { _, h ->
            val depth = (h.level - minLevel).coerceIn(0, 4)
            Text(
                h.text,
                style = if (depth == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = if (depth == 0) D.c.ink else D.c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onPick(h) }
                    .padding(start = (20 + depth * 14).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(headings: List<Heading>, onDismiss: () -> Unit, onPick: (Heading) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = D.c.surface) {
        Text(stringResource(R.string.word_outline), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp))
        if (headings.isEmpty()) Text(stringResource(R.string.word_outline_empty), color = D.c.muted, modifier = Modifier.padding(20.dp))
        OutlineList(headings, Modifier.navigationBarsPadding(), onPick)
    }
}
