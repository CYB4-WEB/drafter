package com.daftar.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.TextInputDialog
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// =====================================================================================
// Page operations (pure: InkPage lists in, InkPage lists out)
// =====================================================================================

internal object PageOps {

    /** Same page with portrait ↔ landscape swapped; content kept in place, shrunk only if it would fall off the page. */
    fun rotated(p: InkPage): InkPage {
        val r = p.copy(w = p.h, h = p.w)
        val b = p.contentBounds() ?: return r
        if (b.right <= r.w + 0.5f && b.bottom <= r.h + 0.5f) return r
        if (b.right <= 0f || b.bottom <= 0f) return r
        val k = min(r.w / b.right, r.h / b.bottom).coerceIn(0.05f, 1f)
        return scaled(r, k)
    }

    /** All content scaled by [k] around the page origin. Render caches of images are carried over. */
    fun scaled(p: InkPage, k: Float): InkPage = p.copy(
        strokes = p.strokes.map { it.mapped({ x, y -> x * k to y * k }, k) },
        texts = p.texts.map { it.copy(x = it.x * k, y = it.y * k, w = it.w * k, size = it.size * k) },
        images = p.images.map { i -> i.copy(x = i.x * k, y = i.y * k, w = i.w * k, h = i.h * k).also { it.bmp = i.bmp } },
        links = p.links.map { it.copy(x = it.x * k, y = it.y * k, w = it.w * k, h = it.h * k) },
    )

    /**
     * A copy safe to put into another note: strokes lose their audio-recording link (recording ids belong to this
     * note and would point at the wrong audio there). Everything else (images are inline) travels as is.
     */
    fun portable(p: InkPage): InkPage =
        if (p.strokes.none { it.rec != 0 }) p.copy()
        else p.copy(strokes = p.strokes.map { s -> if (s.rec == 0) s else Stroke(s.tool, s.color, s.width, s.pts, 0, -1, s.style) })

    /** Moves the selected pages one step earlier (dir < 0) or later; blocks keep their order. */
    fun shift(pages: List<InkPage>, sel: Set<Int>, dir: Int): List<InkPage> {
        val l = pages.toMutableList()
        val s = BooleanArray(l.size) { it in sel }
        fun swap(a: Int, b: Int) { val t = l[a]; l[a] = l[b]; l[b] = t; val u = s[a]; s[a] = s[b]; s[b] = u }
        if (dir < 0) { for (i in 1 until l.size) if (s[i] && !s[i - 1]) swap(i, i - 1) }
        else { for (i in l.size - 2 downTo 0) if (s[i] && !s[i + 1]) swap(i, i + 1) }
        return l
    }

    fun move(pages: List<InkPage>, from: Int, to: Int): List<InkPage> {
        if (from == to || from !in pages.indices) return pages
        val l = pages.toMutableList()
        val p = l.removeAt(from)
        l.add(to.coerceIn(0, l.size), p)
        return l
    }

    /**
     * Appends [add] to the note [target] (load → append → atomic save). Pending saves of open editors are flushed
     * first so a note open in this window is read in its latest state.
     */
    fun appendToNote(target: File, add: List<InkPage>): AppendResult {
        InkSaver.flush()
        val d = InkDoc.load(target) ?: return AppendResult.FAILED
        if (d.infinite) return AppendResult.WHITEBOARD
        d.pages = d.pages + add.map { portable(it) }
        return runCatching { d.save(target); AppendResult.OK }.getOrDefault(AppendResult.FAILED)
    }

    enum class AppendResult { OK, FAILED, WHITEBOARD }
}

// =====================================================================================
// Thumbnails: rendered off the main thread, LRU bounded by bytes
// =====================================================================================

/** Cache key: the page instance itself (pages are immutable; any edit makes a new instance) + paper colour + width. */
private class ThumbKey(val page: InkPage, val color: Int, val w: Int) {
    override fun equals(other: Any?) = other is ThumbKey && other.page === page && other.color == color && other.w == w
    override fun hashCode() = (System.identityHashCode(page) * 31 + color) * 31 + w
}

private class ThumbCache {
    /** ≤ 1/16 of the heap: the editor behind keeps its own ink layers (≤ 1/8) alive meanwhile. */
    private val lru = object : LruCache<ThumbKey, Bitmap>((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: ThumbKey, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "page-thumbs").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
    val dispatcher = pool.asCoroutineDispatcher()
    @Volatile private var closed = false

    fun peek(k: ThumbKey): Bitmap? = lru.get(k)

    /** Renders on the thumbnail threads (or returns the cached one). Fits the page into [k].w × [hPx]. */
    suspend fun load(k: ThumbKey, hPx: Int): Bitmap? = withContext(dispatcher) {
        lru.get(k)?.let { return@withContext it }
        if (closed) return@withContext null
        val p = k.page
        val s = min(k.w / p.w, hPx / p.h)
        val bw = (p.w * s).roundToInt().coerceAtLeast(1); val bh = (p.h * s).roundToInt().coerceAtLeast(1)
        val bmp = runCatching { Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return@withContext null
        val c = Canvas(bmp)
        c.drawColor(k.color or 0xFF000000.toInt())
        c.scale(s, s)
        runCatching {
            InkRender.drawPaper(c, p, PaperTemplates.isDarkColor(k.color), null, s)
            InkRender.drawPageContent(c, p)
        }
        if (!closed) lru.put(k, bmp)
        bmp
    }

    fun close() {
        closed = true
        pool.shutdownNow()
        // only called when the panel has left the composition, so nothing still draws these
        val all = lru.snapshot().values
        lru.evictAll()
        all.forEach { it.recycle() }
    }
}

// =====================================================================================
// UI
// =====================================================================================

/**
 * Full-screen page manager for a paged note: thumbnail grid, tap to open, long-press-drag to reorder, multi-select
 * to delete / duplicate / change template / rotate / move earlier-later / insert / copy or move to another note /
 * extract to a new note. Every change to this note goes through [InkView.applyPages] (one undo step, saved).
 */
@Composable
internal fun PageManagerPanel(view: InkView, noteFile: File, title: String, onDismiss: () -> Unit, onOpenFile: (File) -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    remember { PaperTemplates.bind(ctx); true }

    var pages by remember { mutableStateOf(view.doc.pages) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }
    var selecting by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(view.currentPage) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, File?>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var templateFor by remember { mutableStateOf(false) }
    var insertAt by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }   // (reference index, after)
    var pickTarget by remember { mutableStateOf<Boolean?>(null) }           // true = move, false = copy
    var extractName by remember { mutableStateOf(false) }
    val cache = remember { ThumbCache() }
    DisposableEffect(Unit) { onDispose { cache.close() } }
    val paperColor = view.doc.paperColor

    fun refresh() { pages = view.doc.pages; current = view.currentPage }

    /** Applies [newPages] (one undo step) and re-selects the given page instances. */
    fun commit(newPages: List<InkPage>, select: List<InkPage> = selected.sorted().mapNotNull { pages.getOrNull(it) }, goTo: Int = -1) {
        view.applyPages(newPages, goTo)
        refresh()
        selected = pages.indices.filter { i -> select.any { it === pages[i] } }.toSet()
        if (selected.isEmpty() && select.isNotEmpty()) selecting = false
    }

    fun sel() = selected.sorted()

    fun duplicate() {
        val s = selected
        val copies = ArrayList<InkPage>()
        val out = ArrayList<InkPage>()
        pages.forEachIndexed { i, p -> out.add(p); if (i in s) p.copy().also { copies.add(it); out.add(it) } }
        commit(out, copies)
    }

    fun rotate() {
        val s = selected
        val out = pages.mapIndexed { i, p -> if (i in s) PageOps.rotated(p) else p }
        commit(out, out.filterIndexed { i, _ -> i in s })
    }

    fun delete() {
        val s = selected
        if (s.size >= pages.size) { toast(ctx, ctx.getString(R.string.pages_delete_all_blocked)); return }
        commit(pages.filterIndexed { i, _ -> i !in s }, emptyList())
        selecting = false
    }

    fun setTemplate(base: String) {
        val s = sel()
        var key = PaperTemplates.stamp(base)
        val out = pages.toMutableList()
        s.forEachIndexed { k, i -> if (k > 0) key = PaperTemplates.nextKey(key); out[i] = out[i].copy(paper = key) }
        commit(out, s.map { out[it] })
    }

    fun insert(ref: Int, after: Boolean, base: String, landscape: Boolean) {
        val r = pages.getOrNull(ref) ?: pages.last()
        val short = min(r.w, r.h); val long = max(r.w, r.h)
        val key = if (PaperTemplates.base(r.paper) == base && r.paper.contains(':')) PaperTemplates.nextKey(r.paper, if (after) 1 else -1)
            else PaperTemplates.stamp(base)
        val np = if (landscape) InkPage(long, short, key) else InkPage(short, long, key)
        val at = if (after) ref + 1 else ref
        val out = pages.toMutableList().also { it.add(at.coerceIn(0, it.size), np) }
        commit(out, if (selecting) listOf(np) else emptyList())
        if (selecting) selected = setOf(at)
    }

    fun sendTo(target: File, move: Boolean) {
        val s = sel()
        val chosen = s.map { pages[it] }
        if (move && s.size >= pages.size) { toast(ctx, ctx.getString(R.string.pages_delete_all_blocked)); return }
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { PageOps.appendToNote(target, chosen) }
            busy = false
            when (res) {
                PageOps.AppendResult.OK -> {
                    Storage.touch()
                    if (move) { commit(pages.filterIndexed { i, _ -> i !in s }, emptyList()); selecting = false }
                    val name = target.nameWithoutExtension
                    message = ctx.getString(if (move) R.string.pages_moved else R.string.pages_copied, chosen.size, name) to target
                }
                PageOps.AppendResult.WHITEBOARD -> toast(ctx, ctx.getString(R.string.pages_whiteboard_target))
                PageOps.AppendResult.FAILED -> toast(ctx, ctx.getString(R.string.pages_failed))
            }
        }
    }

    fun extract(name: String) {
        val chosen = sel().map { PageOps.portable(pages[it]) }
        val dir = noteFile.parentFile ?: return
        busy = true
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                runCatching {
                    val out = Storage.uniqueFile(dir, name, Storage.NOTE_EXT)
                    InkDoc(pages = chosen, paperColor = paperColor).save(out)
                    out
                }.getOrNull()
            }
            busy = false
            if (f == null) toast(ctx, ctx.getString(R.string.pages_failed))
            else { Storage.touch(); message = ctx.getString(R.string.pages_extracted, f.nameWithoutExtension) to f }
        }
    }

    // ---- drag to reorder ----
    val grid = rememberLazyGridState()
    val rects = remember { mutableStateMapOf<Int, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var gridHeight by remember { mutableIntStateOf(0) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dropAt by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }
    fun indexAt(local: Offset): Int? {
        val root = local + gridOrigin
        return rects.entries.firstOrNull { it.value.contains(root) }?.key
    }
    val edge = with(density) { 56.dp.toPx() }
    LaunchedEffect(dragFrom) {
        if (dragFrom == null) return@LaunchedEffect
        while (isActive && dragFrom != null) {
            val dy = when {
                pointer.y < edge -> -(edge - pointer.y) / 3f
                pointer.y > gridHeight - edge -> (pointer.y - (gridHeight - edge)) / 3f
                else -> 0f
            }
            if (dy != 0f) {
                val used = grid.scrollBy(dy)
                dragOffset = dragOffset.copy(y = dragOffset.y + used)
                indexAt(pointer)?.let { dropAt = it }
            }
            delay(16)
        }
    }

    fun toggle(i: Int) {
        selecting = true
        selected = if (i in selected) selected - i else selected + i
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (selecting) { selecting = false; selected = emptySet() } else onDismiss() }) {
                    Icon(Icons.Rounded.Close, stringResource(if (selecting) R.string.pages_clear_selection else R.string.close), tint = c.ink)
                }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(if (selecting) stringResource(R.string.pages_selected, selected.size) else stringResource(R.string.pages_title),
                        style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (selecting) stringResource(R.string.pages_reorder_hint) else "$title · " + stringResource(R.string.pages_count, pages.size),
                        style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { view.undo(); refresh(); selected = emptySet() }) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.ink_undo), tint = c.ink) }
                IconButton(onClick = { view.redo(); refresh(); selected = emptySet() }) { Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.ink_redo), tint = c.ink) }
                if (selecting) {
                    IconButton(onClick = { selected = if (selected.size == pages.size) emptySet() else pages.indices.toSet() }) {
                        Icon(Icons.Rounded.SelectAll, stringResource(R.string.pages_select_all), tint = c.ink)
                    }
                } else {
                    IconButton(onClick = { selecting = true }) { Icon(Icons.Rounded.Checklist, stringResource(R.string.pages_select), tint = c.ink) }
                }
                IconButton(onClick = { insertAt = (if (selected.size == 1) selected.first() else pages.lastIndex) to true }) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.pages_add), tint = c.accent)
                }
            }
            HorizontalDivider(color = c.line)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)

            // ---- grid ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(
                    GridCells.Adaptive(140.dp), state = grid,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                        .onGloballyPositioned { gridOrigin = it.positionInRoot(); gridHeight = it.size.height }
                        .pointerInput(pages) {
                            detectTapGestures(
                                onTap = { off ->
                                    val i = indexAt(off) ?: return@detectTapGestures
                                    if (selecting) toggle(i) else { view.goToPage(i); onDismiss() }
                                },
                                onLongPress = { },   // a long press belongs to drag / select below, never a tap
                            )
                        }
                        .pointerInput(pages) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off ->
                                    dragFrom = indexAt(off); dropAt = dragFrom; dragOffset = Offset.Zero; pointer = off; moved = false
                                },
                                onDrag = { ch, amt ->
                                    ch.consume()
                                    dragOffset += amt; pointer += amt
                                    if (abs(dragOffset.x) + abs(dragOffset.y) > 12f) moved = true
                                    indexAt(pointer)?.let { dropAt = it }
                                },
                                onDragEnd = {
                                    val f = dragFrom; val t = dropAt
                                    dragFrom = null; dropAt = null; dragOffset = Offset.Zero
                                    if (f != null) {
                                        if (!moved || t == null || t == f) toggle(f)
                                        else commit(PageOps.move(pages, f, t), selected.sorted().mapNotNull { pages.getOrNull(it) })
                                    }
                                },
                                onDragCancel = { dragFrom = null; dropAt = null; dragOffset = Offset.Zero },
                            )
                        },
                ) {
                    itemsIndexed(pages, key = { i, _ -> i }) { i, page ->
                        val dragging = dragFrom == i
                        PageTile(
                            page, i, cache, paperColor,
                            selected = i in selected, current = i == current, selecting = selecting,
                            dropTarget = dropAt == i && dragFrom != null && dragFrom != i,
                            modifier = Modifier
                                .onGloballyPositioned { rects[i] = it.boundsInRoot() }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) { translationX = dragOffset.x; translationY = dragOffset.y; scaleX = 1.05f; scaleY = 1.05f; alpha = 0.92f }
                                },
                            onEarlier = { if (i > 0) commit(PageOps.move(pages, i, i - 1)) },
                            onLater = { if (i < pages.lastIndex) commit(PageOps.move(pages, i, i + 1)) },
                        )
                        DisposableEffect(i) { onDispose { rects.remove(i) } }
                    }
                }
                message?.let { (msg, file) ->
                    LaunchedEffect(msg) { delay(8000); if (message?.first == msg) message = null }
                    Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).background(c.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, color = c.ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false).padding(vertical = 12.dp))
                        if (file != null) TextButton(onClick = { message = null; onDismiss(); onOpenFile(file) }) { Text(stringResource(R.string.open), color = c.accent) }
                        IconButton(onClick = { message = null }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
                    }
                }
            }

            // ---- selection actions ----
            if (selecting && selected.isNotEmpty()) {
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().background(c.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val one = selected.size == 1
                    Action(Icons.Rounded.KeyboardArrowUp, R.string.pages_move_earlier, enabled = selected.any { it > 0 && (it - 1) !in selected }) {
                        commit(PageOps.shift(pages, selected, -1))
                    }
                    Action(Icons.Rounded.KeyboardArrowDown, R.string.pages_move_later, enabled = selected.any { it < pages.lastIndex && (it + 1) !in selected }) {
                        commit(PageOps.shift(pages, selected, 1))
                    }
                    Action(Icons.Rounded.ContentCopy, R.string.pages_duplicate) { duplicate() }
                    Action(Icons.Rounded.GridOn, R.string.pages_template) { templateFor = true }
                    Action(Icons.Rounded.ScreenRotation, R.string.pages_rotate) { rotate() }
                    if (one) {
                        Action(Icons.Rounded.VerticalAlignTop, R.string.pages_insert_before) { insertAt = selected.first() to false }
                        Action(Icons.Rounded.VerticalAlignBottom, R.string.pages_insert_after) { insertAt = selected.first() to true }
                    }
                    Action(Icons.Rounded.FileCopy, R.string.pages_copy_to, enabled = !busy) { pickTarget = false }
                    Action(Icons.AutoMirrored.Rounded.DriveFileMove, R.string.pages_move_to, enabled = !busy && selected.size < pages.size) { pickTarget = true }
                    Action(Icons.Rounded.NoteAdd, R.string.pages_extract, enabled = !busy) { extractName = true }
                    Action(Icons.Rounded.Delete, R.string.pages_delete, tint = c.danger, enabled = selected.size < pages.size) { confirmDelete = true }
                }
            }
        }

        // ---- dialogs ----
        if (confirmDelete) ConfirmDialog(
            title = stringResource(R.string.pages_delete_title),
            text = stringResource(R.string.pages_delete_msg, selected.size),
            confirm = stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false },
        ) { confirmDelete = false; delete() }

        if (templateFor) {
            val first = pages.getOrNull(selected.minOrNull() ?: 0)
            PaperTemplates.TemplateChooserDialog(
                stringResource(R.string.pages_template), first?.paper ?: "lined", (first?.let { it.w > it.h }) ?: false, paperColor,
                onDismiss = { templateFor = false }, showOrientation = false, confirm = R.string.pages_apply,
            ) { key, _ -> templateFor = false; setTemplate(key) }
        }

        insertAt?.let { (ref, after) ->
            val r = pages.getOrNull(ref)
            PaperTemplates.TemplateChooserDialog(
                stringResource(if (after) R.string.pages_insert_after else R.string.pages_insert_before),
                r?.paper ?: "lined", (r?.let { it.w > it.h }) ?: false, paperColor,
                onDismiss = { insertAt = null },
            ) { key, land -> insertAt = null; insert(ref, after, key, land) }
        }

        pickTarget?.let { move ->
            LibraryFilePickerDialog(
                title = stringResource(R.string.pages_pick_note),
                accept = { f -> f.extension.equals(Storage.NOTE_EXT, true) && f.absolutePath != noteFile.absolutePath },
                multiple = false,
                onDismiss = { pickTarget = null },
                start = noteFile.parentFile ?: Storage.root,
            ) { files -> pickTarget = null; files.firstOrNull()?.let { sendTo(it, move) } }
        }

        if (extractName) TextInputDialog(
            title = stringResource(R.string.pages_extract_name),
            initial = stringResource(R.string.pages_extract_default, noteFile.nameWithoutExtension),
            confirm = stringResource(R.string.pages_create),
            onDismiss = { extractName = false },
        ) { name -> extractName = false; extract(name) }
    }
}

@Composable
private fun Action(icon: ImageVector, label: Int, tint: Color = D.c.ink, enabled: Boolean = true, onClick: () -> Unit) {
    val c = D.c
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (enabled) tint else c.line)
            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = if (enabled) tint else c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun PageTile(
    page: InkPage, index: Int, cache: ThumbCache, paperColor: Int,
    selected: Boolean, current: Boolean, selecting: Boolean, dropTarget: Boolean,
    modifier: Modifier, onEarlier: () -> Unit, onLater: () -> Unit,
) {
    val c = D.c
    val label = stringResource(R.string.pages_page_n, index + 1)
    val earlier = stringResource(R.string.pages_move_earlier)
    val later = stringResource(R.string.pages_move_later)
    val currentLabel = stringResource(R.string.pages_current)
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = if (current) "$label, $currentLabel" else label
            this.selected = selected
            customActions = listOf(
                CustomAccessibilityAction(earlier) { onEarlier(); true },
                CustomAccessibilityAction(later) { onLater(); true },
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(10.dp)
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(0.78f).clip(shape)
                .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surfaceAlt.copy(alpha = 0.5f))
                .border(if (selected || dropTarget || current) 2.dp else 1.dp, when {
                    dropTarget -> c.accent; selected -> c.accent; current -> c.accent.copy(alpha = 0.5f); else -> c.line
                }, shape)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            // bucket the width so small layout changes don't re-render every thumbnail
            val wPx = with(density) { maxWidth.toPx() }.let { ((it / 32f).roundToInt() * 32).coerceIn(64, 720) }
            val hPx = with(density) { maxHeight.toPx() }.roundToInt().coerceAtLeast(64)
            val key = remember(page, paperColor, wPx) { ThumbKey(page, paperColor, wPx) }
            val bmp by produceState(cache.peek(key), key) { if (value == null) value = runCatching { cache.load(key, hPx) }.getOrNull() }
            val b = bmp
            if (b != null && !b.isRecycled) {
                val img = remember(b) { b.asImageBitmap() }
                Image(img, null, Modifier.aspectRatio(b.width.toFloat() / b.height, matchHeightConstraintsFirst = b.height * 0.78f >= b.width).border(1.dp, c.line), contentScale = ContentScale.FillBounds)
            } else {
                Box(Modifier.fillMaxHeight().aspectRatio(page.w / page.h, matchHeightConstraintsFirst = page.h >= page.w)
                    .background(Color(paperColor or 0xFF000000.toInt())).border(1.dp, c.line))
            }
            if (selecting) {
                Box(Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape)
                    .background(if (selected) c.accent else c.surface).border(1.5.dp, if (selected) c.accent else c.muted, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (current || selected) c.accent else c.muted)
    }
}
