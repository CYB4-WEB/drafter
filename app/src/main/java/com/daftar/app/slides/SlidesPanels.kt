package com.daftar.app.slides

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.json
import com.daftar.app.ink.EditorController
import com.daftar.app.ui.Chip
import com.daftar.app.ui.card
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

// ------------------------------------------------------------------ state holders

/**
 * Slide thumbnails rendered one at a time off the UI thread, cached by bytes (≤ maxMemory / 16; the renderer's
 * picture cache takes the other 1/16 of this screen's 1/8 budget). Evicted thumbnails are left to the GC because a
 * list item may still be drawing them; everything is recycled in [dispose] when the screen is gone.
 */
internal class Thumbs(private val src: PptxSource, val widthPx: Int) {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 16).coerceAtMost(48L * 1024 * 1024).toInt().coerceAtLeast(2 * 1024 * 1024)
    private val cache = object : LruCache<Int, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lane = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var disposed = false

    fun cached(i: Int): ImageBitmap? = cache.get(i)

    suspend fun load(i: Int): ImageBitmap? = cache.get(i) ?: withContext(lane) {
        if (disposed) return@withContext null
        cache.get(i) ?: src.thumbnail(i, widthPx)?.let { b ->
            if (disposed) { b.recycle(); null } else b.asImageBitmap().also { cache.put(i, it) }
        }
    }

    fun dispose() {
        disposed = true
        val all = cache.snapshot().values.toList()
        cache.evictAll()
        all.forEach { val b = it.asAndroidBitmap(); if (!b.isRecycled) b.recycle() }
    }
}

/** Student's typed notes per slide, persisted as JSON (slide index -> text) in a sidecar. */
internal class MyNotes(private val file: File) {
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

internal enum class PaneTab { SLIDES, SPEAKER, COMMENTS, MINE }

// ------------------------------------------------------------------ thumbnails

@Composable
private fun SlideThumb(
    slide: SlidePart, thumbs: Thumbs, ratio: Float, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, numberBadge: Boolean = false,
) {
    val c = D.c
    val i = slide.index
    val bmp by produceState(thumbs.cached(i), i, thumbs) { if (value == null) value = thumbs.load(i) }
    val shape = RoundedCornerShape(8.dp)
    val label = stringResource(R.string.slides_slide_n, i + 1)
    Box(
        modifier.aspectRatio(ratio).clip(shape)
            .background(c.surfaceAlt)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        bmp?.let {
            Image(
                it, contentDescription = label, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(if (selected) 2.dp else 1.dp).alpha(if (slide.hidden) 0.45f else 1f),
            )
        }
        if (numberBadge) {
            Text(
                "${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) c.onAccent else c.ink, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp)
                    .background(if (selected) c.accent else c.surface, RoundedCornerShape(6.dp))
                    .border(1.dp, if (selected) c.accent else c.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        if (slide.hidden) {
            Icon(
                Icons.Rounded.VisibilityOff, stringResource(R.string.slides_hidden), tint = c.muted,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp)
                    .background(c.surface, RoundedCornerShape(4.dp)).padding(1.dp),
            )
        }
    }
}

/** Keeps the current slide visible in a thumbnail list. */
@Composable
private fun FollowCurrent(list: LazyListState, cur: Int, last: Int) {
    LaunchedEffect(cur) {
        val info = list.layoutInfo
        val fully = info.visibleItemsInfo
            .filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
            .map { it.index }
        if (cur !in fully) list.animateScrollToItem(cur.coerceIn(0, last))
    }
}

/** PowerPoint-style slide rail (start side, wide screens): numbered thumbnails, current one outlined. */
@Composable
internal fun SlideRail(deck: Pptx, thumbs: Thumbs, controller: EditorController) {
    val c = D.c
    val last = deck.slides.lastIndex
    val cur = controller.currentPage
    val list = rememberLazyListState(initialFirstVisibleItemIndex = cur.coerceIn(0, last))
    FollowCurrent(list, cur, last)
    val ratio = (deck.widthPt / deck.heightPt).coerceIn(0.3f, 4f)
    LazyColumn(
        state = list, modifier = Modifier.fillMaxSize().background(c.surface),
        contentPadding = PaddingValues(start = 6.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(deck.slides, key = { it.index }) { slide ->
            val selected = slide.index == cur
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "${slide.index + 1}", style = MaterialTheme.typography.labelMedium,
                    color = if (selected) c.accent else c.muted, textAlign = TextAlign.End,
                    modifier = Modifier.width(30.dp).padding(end = 8.dp, top = 2.dp),
                )
                SlideThumb(slide, thumbs, ratio, selected, { controller.goToPage(slide.index) }, Modifier.weight(1f))
            }
        }
    }
}

/** Horizontal filmstrip for narrow screens and split panes (first tab of the notes pane). */
@Composable
private fun SlideStrip(deck: Pptx, thumbs: Thumbs, controller: EditorController) {
    val last = deck.slides.lastIndex
    val cur = controller.currentPage
    val list = rememberLazyListState(initialFirstVisibleItemIndex = cur.coerceIn(0, last))
    FollowCurrent(list, cur, last)
    val ratio = (deck.widthPt / deck.heightPt).coerceIn(0.3f, 4f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Number badge on the thumbnail itself, so even a short pane (landscape phone) shows whole slides.
        val thumbH = (maxHeight - 20.dp).coerceIn(32.dp, 220.dp)
        LazyRow(
            state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(deck.slides, key = { it.index }) { slide ->
                SlideThumb(
                    slide, thumbs, ratio, slide.index == cur, { controller.goToPage(slide.index) },
                    Modifier.height(thumbH).width(thumbH * ratio), numberBadge = true,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ notes pane

/**
 * The collapsible pane under the slide: Speaker notes / Comments / My notes (plus a Slides filmstrip first when the
 * rail is not shown). While "My notes" is being typed into, the pane lifts above the on-screen keyboard.
 */
@Composable
internal fun NotesPane(
    deck: Pptx, thumbs: Thumbs, notes: MyNotes, controller: EditorController,
    tab: PaneTab, onTab: (PaneTab) -> Unit, withSlides: Boolean,
) {
    val c = D.c
    val page = controller.currentPage
    val shown = if (!withSlides && tab == PaneTab.SLIDES) PaneTab.SPEAKER else tab
    var editing by remember { mutableStateOf(false) }

    // Keyboard overlap: the editor does not resize for the IME, so lift the pane by the part the keyboard covers.
    val density = LocalDensity.current
    val root = LocalView.current.rootView
    var bottomInWindow by remember { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    val lift = if (editing && shown == PaneTab.MINE && imeBottom > 0) max(0, bottomInWindow - (root.height - imeBottom)) else 0

    Box(Modifier.fillMaxSize().onGloballyPositioned { bottomInWindow = (it.positionInWindow().y + it.size.height).toInt() }) {
        Column(Modifier.fillMaxSize().offset { IntOffset(0, -lift) }.background(c.surface)) {
            if (lift > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (withSlides) Chip(stringResource(R.string.slides_tab_slides), shown == PaneTab.SLIDES, { onTab(PaneTab.SLIDES) }, Icons.Rounded.ViewCarousel)
                    Chip(stringResource(R.string.slides_speaker_notes), shown == PaneTab.SPEAKER, { onTab(PaneTab.SPEAKER) }, Icons.AutoMirrored.Rounded.SpeakerNotes)
                    val count = deck.slides.getOrNull(page)?.comments?.let { l -> l.size + l.sumOf { it.replies.size } } ?: 0
                    Chip(
                        stringResource(R.string.slides_tab_comments) + if (count > 0) " ($count)" else "",
                        shown == PaneTab.COMMENTS, { onTab(PaneTab.COMMENTS) }, Icons.AutoMirrored.Rounded.Comment,
                    )
                    Chip(stringResource(R.string.slides_tab_mynotes), shown == PaneTab.MINE, { onTab(PaneTab.MINE) }, Icons.Rounded.EditNote)
                }
                if (shown != PaneTab.SLIDES) {
                    Text(
                        if (shown == PaneTab.MINE) stringResource(R.string.slides_mynotes_saved) else stringResource(R.string.slides_slide_n, page + 1),
                        style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (shown) {
                    PaneTab.SLIDES -> SlideStrip(deck, thumbs, controller)
                    PaneTab.SPEAKER -> SpeakerNotes(deck, page)
                    PaneTab.COMMENTS -> CommentList(deck, page)
                    PaneTab.MINE -> MyNotesEditor(notes, page) { editing = it }
                }
            }
        }
    }
}

@Composable
private fun PaneEmpty(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = D.c.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = D.c.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SpeakerNotes(deck: Pptx, page: Int) {
    val slide = deck.slides.getOrNull(page) ?: return
    if (slide.notes.isBlank()) { PaneEmpty(Icons.Rounded.Description, stringResource(R.string.slides_no_notes)); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        SelectionContainer {
            Text(slide.notes, style = MaterialTheme.typography.bodyLarge, color = D.c.ink, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CommentList(deck: Pptx, page: Int) {
    val slide = deck.slides.getOrNull(page) ?: return
    if (slide.comments.isEmpty()) { PaneEmpty(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.slides_no_comments)); return }
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
private fun MyNotesEditor(notes: MyNotes, page: Int, onEditing: (Boolean) -> Unit) {
    val c = D.c
    OutlinedTextField(
        value = notes.map[page] ?: "",
        onValueChange = { notes.set(page, it) },
        modifier = Modifier.fillMaxSize().padding(12.dp).onFocusChanged { onEditing(it.isFocused || it.hasFocus) },
        placeholder = { Text(stringResource(R.string.slides_mynotes_hint, page + 1), color = c.muted) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent,
            focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        ),
    )
}
