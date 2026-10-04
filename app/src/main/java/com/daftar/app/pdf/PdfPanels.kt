package com.daftar.app.pdf

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ink.EditorController
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Page operations offered from a thumbnail's long-press menu (and the Tools menu). */
internal enum class PageAction { INSERT_BLANK, ROTATE_RIGHT, ROTATE_LEFT, MOVE_UP, MOVE_DOWN, DELETE }

internal enum class PanelTab { PAGES, OUTLINE }

/**
 * Bumps a counter whenever the editor's ink changes (the page list is replaced on every edit), so thumbnails
 * redraw the user's ink. Polls only while the caller is on screen.
 */
@Composable
internal fun rememberInkTick(ctl: EditorController): Int {
    var tick by remember(ctl) { mutableIntStateOf(0) }
    LaunchedEffect(ctl) {
        var last: Any? = null
        while (true) {
            val now = runCatching { ctl.doc().pages }.getOrNull()
            if (now !== last) { last = now; tick++ }
            delay(500)
        }
    }
    return tick
}

/** Side panel: Pages (thumbnails with ink) | Outline (bookmarks tree). */
@Composable
internal fun PdfSidePanel(
    ctl: EditorController,
    session: PdfSession,
    tab: PanelTab,
    onTab: (PanelTab) -> Unit,
    onPageAction: (PageAction, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(D.c.surface)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelTabButton(stringResource(R.string.pdf_thumbnails), tab == PanelTab.PAGES, Modifier.weight(1f)) { onTab(PanelTab.PAGES) }
            PanelTabButton(stringResource(R.string.pdf_outline), tab == PanelTab.OUTLINE, Modifier.weight(1f)) { onTab(PanelTab.OUTLINE) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))
        when (tab) {
            PanelTab.PAGES -> ThumbnailsGrid(ctl, session, onPageAction)
            PanelTab.OUTLINE -> OutlineTree(ctl, session)
        }
    }
}

@Composable
private fun PanelTabButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = D.c
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) c.accent else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ thumbnails

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbnailsGrid(ctl: EditorController, session: PdfSession, onPageAction: (PageAction, Int) -> Unit) {
    val grid = rememberLazyGridState()
    val cur = ctl.currentPage
    val n = session.pageCount
    val tick = rememberInkTick(ctl)
    LaunchedEffect(cur) {
        val info = grid.layoutInfo
        val fully = info.visibleItemsInfo.filter { it.offset.y >= 0 && it.offset.y + it.size.height <= info.viewportEndOffset }
        if (fully.none { it.index == cur }) runCatching { grid.animateScrollToItem(cur.coerceIn(0, (n - 1).coerceAtLeast(0))) }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(n, key = { it }) { i ->
            var menu by remember { mutableStateOf(false) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = { ctl.goToPage(i) }, onLongClick = { menu = true })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    PageThumb(session, i, 0, runCatching { ctl.doc().pages.getOrNull(i) }.getOrNull(), tick, selected = i == cur)
                    PageMenu(menu, i, n, onDismiss = { menu = false }) { a -> menu = false; onPageAction(a, i) }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    (i + 1).toString(), style = MaterialTheme.typography.labelMedium,
                    color = if (i == cur) D.c.accent else D.c.muted, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PageMenu(expanded: Boolean, page: Int, count: Int, onDismiss: () -> Unit, onAction: (PageAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(text: Int, icon: ImageVector, a: PageAction, enabled: Boolean = true) = DropdownMenuItem(
            text = { Text(stringResource(text)) },
            leadingIcon = { Icon(icon, null, tint = if (enabled) D.c.muted else D.c.line) },
            enabled = enabled,
            onClick = { onAction(a) },
        )
        item(R.string.pdf_insert_blank, Icons.AutoMirrored.Rounded.NoteAdd, PageAction.INSERT_BLANK)
        item(R.string.pdf_rotate_right, Icons.Rounded.RotateRight, PageAction.ROTATE_RIGHT)
        item(R.string.pdf_rotate_left, Icons.Rounded.RotateLeft, PageAction.ROTATE_LEFT)
        item(R.string.pdf_move_up, Icons.Rounded.ArrowUpward, PageAction.MOVE_UP, page > 0)
        item(R.string.pdf_move_down, Icons.Rounded.ArrowDownward, PageAction.MOVE_DOWN, page < count - 1)
        item(R.string.pdf_delete_page, Icons.Rounded.DeleteOutline, PageAction.DELETE, count > 1)
    }
}

/**
 * Page thumbnail with the user's ink drawn on top (vector, at display size). [rotate] previews a pending rotation
 * (organize dialog). Re-draws when [inkTick] changes.
 */
@Composable
internal fun PageThumb(
    session: PdfSession,
    src: Int,
    rotate: Int,
    ink: InkPage?,
    inkTick: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val (pw, ph) = session.pageSize(src)
    val r = PdfPages.norm(rotate)
    val swap = r == 90 || r == 270
    val aspect = (if (swap) ph / pw else pw / ph).coerceIn(0.2f, 5f)
    val bmp by produceState<Bitmap?>(session.cachedThumb(src), session, src) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { session.thumb(src) }.onFailure { Log.e("PdfPanels", "thumb $src", it) }.getOrNull()
        }
    }
    val img = remember(bmp) { bmp?.takeIf { !it.isRecycled }?.asImageBitmap() }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.fillMaxWidth().aspectRatio(aspect)
            .background(Color.White, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) D.c.accent else D.c.line, shape)
            .padding(if (selected) 2.dp else 1.dp)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (inkTick < 0) return@Canvas   // reading the tick re-draws when the ink changes
            val w = if (swap) size.height else size.width
            val h = if (swap) size.width else size.height
            withTransform({
                translate(size.width / 2f, size.height / 2f)
                rotate(r.toFloat(), pivot = Offset.Zero)
                translate(-w / 2f, -h / 2f)
            }) {
                img?.let { drawImage(it, dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium) }
                if (ink != null && !ink.isEmpty() && ink.w > 0f && ink.h > 0f) drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(w / ink.w, h / ink.h)
                    InkRender.drawPageContent(nc, ink)
                    nc.restore()
                }
            }
        }
        if (img == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = D.c.accent)
    }
}

// ------------------------------------------------------------------ outline

@Composable
private fun OutlineTree(ctl: EditorController, session: PdfSession) {
    LaunchedEffect(session) {
        if (session.outline == null) session.outline = withContext(Dispatchers.IO) {
            runCatching { PdfPages.loadOutline(session.file) }.onFailure { Log.e("PdfPanels", "outline", it) }.getOrDefault(emptyList())
        }
    }
    val entries = session.outline
    if (entries == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = D.c.accent) }
        return
    }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            EmptyState(Icons.AutoMirrored.Rounded.Toc, stringResource(R.string.pdf_no_outline))
        }
        return
    }
    val collapsed = remember(entries) {
        mutableStateMapOf<Int, Boolean>().apply { entries.forEach { if (it.hasChildren && !it.open) put(it.id, true) } }
    }
    val visible = run {
        val out = ArrayList<OutlineEntry>()
        var hideBelow = Int.MAX_VALUE
        for (e in entries) {
            if (e.depth > hideBelow) continue
            hideBelow = Int.MAX_VALUE
            out.add(e)
            if (e.hasChildren && collapsed[e.id] == true) hideBelow = e.depth
        }
        out
    }
    val cur = ctl.currentPage
    // The section being read: the last bookmark (document order) that starts at or before the current page.
    val currentId = remember(entries, cur) { entries.lastOrNull { it.page in 0..cur }?.id ?: -1 }
    val list = rememberLazyListState()
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(visible, key = { it.id }) { e ->
            val active = e.id == currentId
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (active) D.c.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable(enabled = e.page >= 0) { ctl.goToPage(e.page) }
                    .heightIn(min = 44.dp)
                    .padding(start = (e.depth.coerceAtMost(6) * 14).dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.hasChildren) {
                    val isCollapsed = collapsed[e.id] == true
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .clickable { if (isCollapsed) collapsed.remove(e.id) else collapsed[e.id] = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isCollapsed) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.ExpandMore,
                            stringResource(if (isCollapsed) R.string.pdf_expand else R.string.pdf_collapse), tint = D.c.muted,
                        )
                    }
                } else Spacer(Modifier.width(40.dp))
                Text(
                    e.title.ifBlank { stringResource(R.string.pdf_untitled_bookmark) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when { active -> D.c.accent; e.page < 0 -> D.c.muted; else -> D.c.ink },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                if (e.page >= 0) Text(
                    (e.page + 1).toString(), style = MaterialTheme.typography.labelMedium, color = D.c.muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
