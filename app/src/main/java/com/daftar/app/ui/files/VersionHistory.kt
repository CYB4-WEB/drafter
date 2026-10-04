package com.daftar.app.ui.files

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.data.Versions
import com.daftar.app.ink.InkRender
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.relTime
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One parse at a time: notes with pictures can be big, so versions are never decoded in parallel. */
@OptIn(ExperimentalCoroutinesApi::class)
private val versionLane: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/** Page counts by snapshot (name + mtime) — parsing a snapshot is the expensive part. */
private val pageCounts = ConcurrentHashMap<String, Int>()
private fun Versions.Version.key() = file.absolutePath + ":" + time

private const val PREVIEW_PX = 720

/** Page 1 of [v] rendered to a bitmap ≤ [PREVIEW_PX] wide (paper, ink, text, pictures). Blocking. */
private fun renderPreview(v: Versions.Version): Pair<Bitmap, Int>? = runCatching {
    val doc = Versions.load(v) ?: return null
    pageCounts[v.key()] = doc.pages.size
    val page = doc.pages.firstOrNull() ?: return null
    val r = doc.exportRect(0)
    val scale = PREVIEW_PX / r.width()
    val w = PREVIEW_PX
    val h = (r.height() * scale).toInt().coerceIn(1, PREVIEW_PX * 2)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(doc.paperColor)
    c.scale(scale, scale)
    c.translate(-r.left, -r.top)
    InkRender.drawPaper(c, page, dark = false, clip = r, bounded = !doc.infinite)
    InkRender.drawPageContent(c, page)
    bmp to doc.pages.size
}.getOrNull()

/** True when [note] is open in the main window (directly or in a split) — restoring would be overwritten by the editor. */
private fun isOpen(note: File): Boolean {
    val s = Screen.Note(note.absolutePath)
    return Nav.stack.any { it == s || (it is Screen.Split && (it.first == s || it.second == s)) }
}

/**
 * Version history of a note: list (time, size, pages), page-1 preview, Restore (the current content becomes a version
 * first) and Save as copy. Side by side on wide windows, stacked on narrow ones.
 */
@Composable
fun VersionHistoryDialog(note: File, onDismiss: () -> Unit, onOpen: (File) -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var versions by remember { mutableStateOf<List<Versions.Version>?>(null) }
    LaunchedEffect(note, refresh) { versions = withContext(Dispatchers.IO) { Versions.list(note) } }
    var selected by remember { mutableStateOf<Versions.Version?>(null) }
    LaunchedEffect(versions) { if (selected == null || versions?.none { it.file == selected?.file } == true) selected = versions?.firstOrNull() }
    var confirm by remember { mutableStateOf<Versions.Version?>(null) }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth(0.94f).widthIn(max = 980.dp).fillMaxHeight(0.88f)
            .background(c.surface, RoundedCornerShape(24.dp)).border(1.dp, c.line, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))) {
            val wide = maxWidth >= 640.dp
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = c.muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.files_version_history), style = MaterialTheme.typography.titleLarge, color = c.ink)
                        Text(note.nameWithoutExtension, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.ink) }
                }
                HorizontalDivider(color = c.line)
                val list = versions
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.History, stringResource(R.string.files_versions_none))
                    }
                    else -> {
                        val listPane: @Composable (Modifier) -> Unit = { m ->
                            LazyColumn(m, contentPadding = PaddingValues(8.dp)) {
                                items(list, key = { it.file.absolutePath }) { v ->
                                    VersionRow(v, v.file == selected?.file, v == list.first(), fmt) { selected = v }
                                }
                            }
                        }
                        val previewPane: @Composable (Modifier) -> Unit = { m ->
                            Preview(selected, m,
                                onRestore = { v -> if (isOpen(note)) toast(ctx, ctx.getString(R.string.files_version_note_open)) else confirm = v },
                                onCopy = { v ->
                                    scope.launch {
                                        busy = true
                                        val name = ctx.getString(R.string.files_version_copy_name, note.nameWithoutExtension,
                                            SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(Date(v.time)))
                                        val f = withContext(Dispatchers.IO) { Versions.saveAsCopy(note, v, name) }
                                        busy = false
                                        if (f == null) toast(ctx, ctx.getString(R.string.files_version_failed)) else { onDismiss(); onOpen(f) }
                                    }
                                })
                        }
                        if (wide) Row(Modifier.fillMaxSize()) {
                            listPane(Modifier.weight(1f).fillMaxHeight())
                            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                            previewPane(Modifier.weight(1.3f).fillMaxHeight())
                        } else Column(Modifier.fillMaxSize()) {
                            previewPane(Modifier.fillMaxWidth().weight(1.1f))
                            HorizontalDivider(color = c.line)
                            listPane(Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            }
            if (busy) Box(Modifier.matchParentSize().background(c.surface.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
        }
    }

    confirm?.let { v ->
        ConfirmDialog(stringResource(R.string.files_version_restore_title), stringResource(R.string.files_version_restore_text, fmt.format(Date(v.time))),
            stringResource(R.string.files_restore), onDismiss = { confirm = null }) {
            confirm = null
            scope.launch {
                busy = true
                val ok = withContext(Dispatchers.IO) { Versions.restore(note, v) }
                busy = false
                toast(ctx, ctx.getString(if (ok) R.string.files_version_restored else R.string.files_version_failed))
                refresh++
            }
        }
    }
}

@Composable
private fun VersionRow(v: Versions.Version, selected: Boolean, latest: Boolean, fmt: DateFormat, onClick: () -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val pages by produceState(pageCounts[v.key()], v.key()) {
        if (value == null) value = withContext(versionLane) { pageCounts[v.key()] ?: Versions.load(v)?.pages?.size?.also { pageCounts[v.key()] = it } }
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(v.time)), style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (latest) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.files_version_latest), style = MaterialTheme.typography.labelSmall, color = c.accent,
                        modifier = Modifier.background(c.accent.copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            val parts = listOfNotNull(relTime(v.time), Formatter.formatShortFileSize(ctx, v.bytes), pages?.let { pluralStringResource(R.plurals.files_pages, it, it) })
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        }
    }
}

@Composable
private fun Preview(v: Versions.Version?, modifier: Modifier, onRestore: (Versions.Version) -> Unit, onCopy: (Versions.Version) -> Unit) {
    val c = D.c
    Column(modifier.padding(16.dp)) {
        if (v == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.files_version_pick), color = c.muted)
            }
            return@Column
        }
        var img by remember(v.key()) { mutableStateOf<ImageBitmap?>(null) }
        var failed by remember(v.key()) { mutableStateOf(false) }
        DisposableEffect(v.key()) {
            var bmp: Bitmap? = null
            val job = kotlinx.coroutines.MainScope().launch {
                val r = withContext(versionLane) { renderPreview(v) }
                if (r == null) failed = true else { bmp = r.first; img = r.first.asImageBitmap() }
            }
            onDispose { job.cancel(); img = null; bmp?.recycle() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt).padding(12.dp), contentAlignment = Alignment.Center) {
            val i = img
            when {
                i != null -> Image(i, null, Modifier.fillMaxSize().border(1.dp, c.line), contentScale = ContentScale.Fit)
                failed -> Text(stringResource(R.string.files_version_failed), color = c.muted)
                else -> CircularProgressIndicator(color = c.accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { onCopy(v) }) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.files_version_copy))
            }
            Button(onClick = { onRestore(v) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.files_restore))
            }
        }
    }
}
