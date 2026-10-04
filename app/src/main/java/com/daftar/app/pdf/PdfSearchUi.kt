package com.daftar.app.pdf

import android.graphics.RectF
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.ink.EditorController
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** State of the find-in-document mode (one per opened file version). */
internal class PdfSearchState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    /** The query actually searched (debounced / submitted). */
    var submitted by mutableStateOf("")
    val hits = mutableStateListOf<SearchHit>()
    var active by mutableIntStateOf(-1)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var showList by mutableStateOf(true)

    fun close() {
        open = false; query = ""; submitted = ""; hits.clear(); active = -1; running = false
    }
}

/**
 * Re-renders the editor's page backgrounds so new search highlights show. Uses `EditorController.refreshPages()`
 * when the editor provides it (requested from the lead — see pdf-agent log); returns false otherwise, and then
 * no highlights are drawn at all (never stale ones).
 */
internal fun EditorController.refreshPagesCompat(): Boolean {
    val m = refreshMethod ?: return false
    return runCatching { m.invoke(this) }.isSuccess
}

internal fun EditorController.canRefreshPages(): Boolean = refreshMethod != null

/** Looked up on the public interface (not the editor's private class) so invoking it needs no special access. */
private val refreshMethod: java.lang.reflect.Method? by lazy {
    runCatching { EditorController::class.java.getMethod("refreshPages") }.getOrNull()
}

private fun Modifier.blockTouches() = this.pointerInput(Unit) { detectTapGestures { } }

/**
 * Native-reader search: a search bar that covers the editor header while active, prev/next, match counter,
 * a results card (page + snippet with the match in bold), and highlights on the page.
 */
@Composable
internal fun PdfSearchOverlay(
    state: PdfSearchState,
    session: PdfSession,
    source: PdfSource,
    ctl: EditorController,
    narrow: Boolean,
    maxHeight: Dp,
) {
    val c = D.c
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val highlights = remember(ctl) { ctl.canRefreshPages() }

    BackHandler(enabled = state.open) { state.close() }

    // Debounced live search (2+ characters); Enter searches right away.
    LaunchedEffect(state.query) {
        val q = state.query.trim()
        if (q.isEmpty()) { state.submitted = ""; return@LaunchedEffect }
        delay(450)
        if (q.length >= 2) state.submitted = q
    }
    LaunchedEffect(state.submitted) {
        state.hits.clear(); state.active = -1; state.done = 0; state.total = 0
        val q = state.submitted
        if (q.isEmpty()) { state.running = false; return@LaunchedEffect }
        state.running = true
        // Like native readers: the first match shown is the first one at or after the page being read.
        val startPage = ctl.currentPage
        fun select(k: Int) {
            state.active = k
            val p = state.hits[k].page
            if (p != ctl.currentPage) ctl.goToPage(p)
        }
        try {
            session.textIndex.search(q).collect { ev ->
                when (ev) {
                    is SearchEvent.Progress -> { state.done = ev.done; state.total = ev.total }
                    is SearchEvent.Hits -> {
                        val from = state.hits.size
                        state.hits.addAll(ev.hits)
                        if (state.active < 0) {
                            val k = (from until state.hits.size).firstOrNull { state.hits[it].page >= startPage }
                            if (k != null) select(k)
                        }
                    }
                }
            }
            if (state.active < 0 && state.hits.isNotEmpty()) select(0)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("PdfSearch", "search failed", t)
        } finally {
            state.running = false
        }
    }
    // Highlights: at most every 400 ms while results stream in.
    LaunchedEffect(highlights) {
        if (!highlights) return@LaunchedEffect
        try {
            snapshotFlow { Triple(state.hits.size, state.active, state.submitted) }.conflate().collect {
                source.marks = if (state.hits.isEmpty()) null else buildMarks(state.hits, state.active)
                ctl.refreshPagesCompat()
                delay(400)
            }
        } finally {
            source.marks = null
            ctl.refreshPagesCompat()
        }
    }
    LaunchedEffect(state.active) {
        if (state.active >= 0) runCatching { list.animateScrollToItem(state.active) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun go(k: Int) {
        if (state.hits.isEmpty()) return
        val i = ((k % state.hits.size) + state.hits.size) % state.hits.size
        state.active = i
        val p = state.hits[i].page
        if (p != ctl.currentPage) ctl.goToPage(p)
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().background(c.surface).blockTouches().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().height(if (Prefs.largeControls) 64.dp else 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { state.close() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pdf_search_close), tint = c.ink)
                }
                Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) Text(
                        stringResource(R.string.pdf_search_hint), style = MaterialTheme.typography.bodyLarge, color = c.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    BasicTextField(
                        value = state.query,
                        onValueChange = { state.query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val q = state.query.trim()
                            if (q.isNotEmpty()) {
                                if (q == state.submitted && state.hits.isNotEmpty()) go(state.active + 1) else state.submitted = q
                            }
                            keyboard?.hide()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (state.query.isNotEmpty()) IconButton(onClick = { state.query = ""; runCatching { focus.requestFocus() } }) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.pdf_search_clear), tint = c.muted)
                }
                if (state.submitted.isNotEmpty()) Text(
                    when {
                        state.hits.isEmpty() -> if (state.running) "…" else "0"
                        else -> "${if (state.active >= 0) state.active + 1 else "–"}/${state.hits.size}${if (state.hits.size >= PdfTextIndex.MAX_HITS) "+" else ""}"
                    },
                    style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(onClick = { go(if (state.active < 0) state.hits.size - 1 else state.active - 1) }, enabled = state.hits.isNotEmpty()) {
                    Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.pdf_search_prev), tint = if (state.hits.isNotEmpty()) c.ink else c.line)
                }
                IconButton(onClick = { go(state.active + 1) }, enabled = state.hits.isNotEmpty()) {
                    Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.pdf_search_next), tint = if (state.hits.isNotEmpty()) c.ink else c.line)
                }
                IconButton(onClick = { state.showList = !state.showList }) {
                    Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.pdf_search_results), tint = if (state.showList) c.accent else c.ink)
                }
            }
            if (state.running && state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total }, color = c.accent, trackColor = c.line,
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        }

        if (state.showList && state.submitted.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                val shape = RoundedCornerShape(16.dp)
                Column(
                    Modifier.align(Alignment.TopEnd)
                        .then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(380.dp))
                        .heightIn(max = maxHeight * 0.55f)
                        .background(c.surface, shape).border(1.dp, c.line, shape).clip(shape).blockTouches(),
                ) {
                    val status = when {
                        state.running && state.hits.isEmpty() -> stringResource(R.string.pdf_searching, state.done, state.total)
                        state.hits.isEmpty() -> stringResource(R.string.pdf_search_none)
                        state.hits.size >= PdfTextIndex.MAX_HITS -> stringResource(R.string.pdf_search_capped, state.hits.size)
                        state.running -> stringResource(R.string.pdf_search_count_running, state.hits.size, state.done, state.total)
                        else -> pluralStringResource(R.plurals.pdf_search_count, state.hits.size, state.hits.size)
                    }
                    Text(status, style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    if (state.hits.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                        LazyColumn(state = list) {
                            itemsIndexed(state.hits) { k, h ->
                                ResultRow(h, k == state.active) {
                                    go(k)
                                    if (narrow) state.showList = false
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(h: SearchHit, active: Boolean, onClick: () -> Unit) {
    val c = D.c
    val text = remember(h) {
        buildAnnotatedString {
            append(h.snippet)
            val s = h.boldStart.coerceIn(0, h.snippet.length)
            val e = h.boldEnd.coerceIn(s, h.snippet.length)
            if (e > s) addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = c.ink, background = c.accent.copy(alpha = 0.14f)), s, e)
        }
    }
    Column(
        Modifier.fillMaxWidth().background(if (active) c.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.pdf_page_label, h.page + 1), style = MaterialTheme.typography.labelMedium, color = if (active) c.accent else c.muted)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun buildMarks(hits: List<SearchHit>, active: Int): SearchMarks {
    val by = HashMap<Int, MutableList<RectF>>()
    for (h in hits) by.getOrPut(h.page) { ArrayList() }.addAll(h.rects)
    val a = hits.getOrNull(active)
    return SearchMarks(by, a?.page ?: -1, a?.rects ?: emptyList())
}
