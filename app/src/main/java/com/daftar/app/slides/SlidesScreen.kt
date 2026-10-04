package com.daftar.app.slides

import com.daftar.app.ui.pane
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.data.json
import com.daftar.app.ink.EditorController
import com.daftar.app.ink.InkEditorScaffold
import com.daftar.app.ui.Chip
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Nav
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.card
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ state holders

private sealed class LoadState {
    data object Loading : LoadState()
    class Error(val msgRes: Int) : LoadState()
    class Ready(val src: PptxSource, val notes: MyNotes, val thumbs: Thumbs) : LoadState()
}

/** Owns the opened source so it is closed even when the screen leaves while parsing is still running. */
private class Holder {
    private var src: PptxSource? = null
    private var disposed = false

    @Synchronized fun adopt(s: PptxSource): Boolean {
        if (disposed) { s.close(); return false }
        src = s; return true
    }

    @Synchronized fun dispose() { disposed = true; src?.close(); src = null }
}

/** Student's typed notes per slide, persisted as JSON (slide index -> text) in a sidecar. */
private class MyNotes(private val file: File) {
    val map = mutableStateMapOf<Int, String>()
    var version by mutableIntStateOf(0)
        private set

    fun load() {
        if (!file.exists()) return
        runCatching { json.decodeFromString<Map<Int, String>>(file.readText()) }.getOrNull()?.let { map.putAll(it) }
    }

    fun set(i: Int, text: String) { map[i] = text; version++ }

    fun snapshot(): Map<Int, String> = map.filterValues { it.isNotBlank() }.toSortedMap()

    /** Writes atomically; called off the main thread. */
    @Synchronized fun save(data: Map<Int, String>) {
        runCatching {
            if (data.isEmpty()) { file.delete(); return }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(data))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }
}

/** Thumbnails rendered one at a time off the UI thread, cached in memory. */
private class Thumbs(private val src: PptxSource) {
    private val cache = LruCache<Int, ImageBitmap>(160)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    fun cached(i: Int): ImageBitmap? = cache.get(i)

    suspend fun load(i: Int): ImageBitmap? = cache.get(i) ?: withContext(dispatcher) {
        cache.get(i) ?: src.thumbnail(i, THUMB_PX)?.let { b: Bitmap -> b.asImageBitmap().also { cache.put(i, it) } }
    }

    companion object { const val THUMB_PX = 320 }
}

// ------------------------------------------------------------------ screen

@Composable
fun SlidesScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val labels = Labels(
        stringResource(R.string.slides_chart), stringResource(R.string.slides_diagram),
        stringResource(R.string.slides_object), stringResource(R.string.slides_image),
    )
    val holder = remember(path) { Holder() }
    var state by remember(path) { mutableStateOf<LoadState>(LoadState.Loading) }

    LaunchedEffect(path) {
        state = withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext LoadState.Error(R.string.slides_err_missing)
            try {
                val src = PptxSource.open(file, labels)
                if (!holder.adopt(src)) return@withContext LoadState.Loading
                val notes = MyNotes(Storage.sidecar(file, "mynotes.json")).also { it.load() }
                LoadState.Ready(src, notes, Thumbs(src))
            } catch (e: PptxException) {
                LoadState.Error(
                    when (e.kind) {
                        PptxException.Kind.LEGACY -> R.string.slides_err_legacy
                        PptxException.Kind.EMPTY -> R.string.slides_err_empty
                        PptxException.Kind.CORRUPT -> R.string.slides_err_corrupt
                    },
                )
            } catch (_: Throwable) {
                LoadState.Error(R.string.slides_err_corrupt)
            }
        }
    }
    DisposableEffect(path) { onDispose { holder.dispose() } }

    when (val s = state) {
        LoadState.Loading -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(file.nameWithoutExtension, onBack = { pane.back() })
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = D.c.accent)
                Spacer(Modifier.height(16.dp))
                Text(file.name, style = MaterialTheme.typography.titleMedium, color = D.c.ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.slides_opening), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
            }
        }
        is LoadState.Error -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(file.nameWithoutExtension, onBack = { pane.back() })
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = D.c.muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.slides_err_title), style = MaterialTheme.typography.titleMedium, color = D.c.ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(s.msgRes), style = MaterialTheme.typography.bodyLarge, color = D.c.muted,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp),
                )
                if (file.isFile) {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { openExternally(ctx, file) }, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.open_externally))
                    }
                }
            }
        }
        is LoadState.Ready -> SlidesEditor(file, s)
    }
}

@Composable
private fun SlidesEditor(file: File, s: LoadState.Ready) {
    val notes = s.notes
    // Debounced autosave of "My notes" (~600 ms after the last keystroke) + a final save on exit.
    LaunchedEffect(notes) {
        snapshotFlow { notes.version }.drop(1).collectLatest {
            delay(600)
            val data = notes.snapshot()
            withContext(Dispatchers.IO) { notes.save(data) }
        }
    }
    DisposableEffect(notes) {
        onDispose {
            if (notes.version > 0) {
                val data = notes.snapshot()
                Thread { notes.save(data) }.start()
            }
        }
    }

    InkEditorScaffold(
        title = file.nameWithoutExtension,
        inkFile = Storage.sidecar(file, "ink.json"),
        source = s.src,
        isNote = false,
        onBack = { pane.back() },
        extraActions = { controller -> SlidesMenu(file, s, controller) },
        sidePanel = { controller -> SlidesPanel(s, controller) },
        sidePanelLabel = stringResource(R.string.slides_panel),
    )
}

// ------------------------------------------------------------------ overflow menu

@Composable
private fun SlidesMenu(file: File, s: LoadState.Ready, controller: EditorController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.slides_more), tint = D.c.ink) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.open_externally)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = D.c.muted) },
                onClick = { open = false; controller.saveNow(); openExternally(ctx, file) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, null, tint = D.c.muted) },
                onClick = { open = false; shareFiles(ctx, listOf(file)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.slides_export_notes)) },
                leadingIcon = { Icon(Icons.Rounded.FileDownload, null, tint = D.c.muted) },
                onClick = {
                    open = false
                    val mine = s.notes.snapshot()
                    scope.launch {
                        val out = withContext(Dispatchers.IO) { runCatching { exportNotes(ctx, file, s.src.deck, mine) }.getOrNull() }
                        if (out != null) { Storage.touch(); toast(ctx, ctx.getString(R.string.saved_to, out.name)) }
                        else toast(ctx, ctx.getString(R.string.error_generic))
                    }
                },
            )
        }
    }
}

/** Writes "<deck> - notes.txt" next to the deck: per slide, speaker notes and my notes. */
private fun exportNotes(ctx: Context, file: File, deck: Pptx, mine: Map<Int, String>): File {
    val out = Storage.uniqueFile(file.parentFile!!, ctx.getString(R.string.slides_export_file, file.nameWithoutExtension), "txt")
    val sb = StringBuilder()
    sb.append(file.nameWithoutExtension).append('\n').append("=".repeat(file.nameWithoutExtension.length.coerceIn(3, 60))).append("\n\n")
    for (slide in deck.slides) {
        val n = slide.index + 1
        val head = if (slide.title.isNotBlank()) ctx.getString(R.string.slides_slide_n_title, n, slide.title) else ctx.getString(R.string.slides_slide_n, n)
        sb.append(head).append('\n').append("-".repeat(head.length.coerceIn(3, 60))).append('\n')
        if (slide.notes.isNotBlank()) {
            sb.append(ctx.getString(R.string.slides_speaker_notes)).append(":\n").append(slide.notes.trim()).append("\n\n")
        }
        mine[slide.index]?.takeIf { it.isNotBlank() }?.let {
            sb.append(ctx.getString(R.string.slides_tab_mynotes)).append(":\n").append(it.trim()).append("\n\n")
        }
        if (slide.comments.isNotEmpty()) {
            sb.append(ctx.getString(R.string.slides_comments_header)).append(":\n")
            for (c in slide.comments) {
                sb.append("- ").append(c.author.ifBlank { ctx.getString(R.string.slides_unknown_author) }).append(": ").append(c.text).append('\n')
                for (r in c.replies) sb.append("    - ").append(r.author.ifBlank { ctx.getString(R.string.slides_unknown_author) }).append(": ").append(r.text).append('\n')
            }
            sb.append('\n')
        }
        sb.append('\n')
    }
    out.writeText(sb.toString())
    return out
}

// ------------------------------------------------------------------ side panel

@Composable
private fun SlidesPanel(s: LoadState.Ready, controller: EditorController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val c = D.c
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.slides_tab_slides), tab == 0, { tab = 0 })
            Chip(stringResource(R.string.slides_tab_notes), tab == 1, { tab = 1 })
            val count = s.src.deck.slides.getOrNull(controller.currentPage)?.comments?.let { l -> l.size + l.sumOf { it.replies.size } } ?: 0
            Chip(stringResource(R.string.slides_tab_comments) + if (count > 0) " ($count)" else "", tab == 2, { tab = 2 })
            Chip(stringResource(R.string.slides_tab_mynotes), tab == 3, { tab = 3 })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> ThumbList(s, controller)
                1 -> SpeakerNotes(s, controller.currentPage)
                2 -> CommentList(s, controller.currentPage)
                else -> MyNotesEditor(s.notes, controller.currentPage)
            }
        }
    }
}

@Composable
private fun ThumbList(s: LoadState.Ready, controller: EditorController) {
    val deck = s.src.deck
    val list = rememberLazyListState(initialFirstVisibleItemIndex = controller.currentPage.coerceIn(0, deck.slides.lastIndex))
    val cur = controller.currentPage
    LaunchedEffect(cur) {
        val visible = list.layoutInfo.visibleItemsInfo
        val fully = visible.filter { it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }.map { it.index }
        if (cur !in fully) list.animateScrollToItem(cur.coerceIn(0, deck.slides.lastIndex))
    }
    val ratio = (deck.widthPt / deck.heightPt).coerceIn(0.3f, 4f)
    LazyColumn(
        state = list, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(deck.slides, key = { it.index }) { slide ->
            val i = slide.index
            val selected = i == cur
            val bmp by produceState(s.thumbs.cached(i), i) { if (value == null) value = s.thumbs.load(i) }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${i + 1}", style = MaterialTheme.typography.labelMedium,
                    color = if (selected) D.c.accent else D.c.muted, modifier = Modifier.width(28.dp).padding(top = 2.dp),
                )
                val shape = RoundedCornerShape(8.dp)
                Box(
                    Modifier.weight(1f).aspectRatio(ratio).clip(shape)
                        .border(if (selected) 2.dp else 1.dp, if (selected) D.c.accent else D.c.line, shape)
                        .background(D.c.surfaceAlt)
                        .clickable { controller.goToPage(i) }
                        .alpha(if (slide.hidden) 0.5f else 1f),
                ) {
                    bmp?.let { Image(it, contentDescription = stringResource(R.string.slides_slide_n, i + 1), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
            }
        }
    }
}

@Composable
private fun SpeakerNotes(s: LoadState.Ready, page: Int) {
    val slide = s.src.deck.slides.getOrNull(page) ?: return
    if (slide.notes.isBlank()) {
        EmptyState(Icons.Rounded.Description, stringResource(R.string.slides_no_notes))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.slides_slide_n, page + 1), style = MaterialTheme.typography.labelMedium, color = D.c.muted)
        Spacer(Modifier.height(8.dp))
        SelectionContainer {
            Text(slide.notes, style = MaterialTheme.typography.bodyLarge, color = D.c.ink, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CommentList(s: LoadState.Ready, page: Int) {
    val slide = s.src.deck.slides.getOrNull(page) ?: return
    if (slide.comments.isEmpty()) {
        EmptyState(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.slides_no_comments))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(slide.comments) { cm ->
            Column(Modifier.fillMaxWidth().card(D.c, 12.dp).padding(12.dp)) {
                CommentBody(cm)
                if (cm.replies.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    for (r in cm.replies) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 6.dp)) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(D.c.line))
                            Column(Modifier.padding(start = 10.dp)) { CommentBody(r) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentBody(cm: Comment) {
    val date = remember(cm.time) { cm.time?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            cm.author.ifBlank { stringResource(R.string.slides_unknown_author) }, style = MaterialTheme.typography.labelLarge,
            color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        if (cm.resolved) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.slides_resolved), style = MaterialTheme.typography.bodySmall, color = D.c.muted,
                modifier = Modifier.background(D.c.surfaceAlt, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
    if (date != null) Text(date, style = MaterialTheme.typography.bodySmall, color = D.c.muted)
    Spacer(Modifier.height(4.dp))
    SelectionContainer { Text(cm.text, style = MaterialTheme.typography.bodyMedium, color = D.c.ink) }
}

@Composable
private fun MyNotesEditor(notes: MyNotes, page: Int) {
    val c = D.c
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = notes.map[page] ?: "",
            onValueChange = { notes.set(page, it) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            placeholder = { Text(stringResource(R.string.slides_mynotes_hint, page + 1), color = c.muted) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.slides_mynotes_saved), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}
