package com.daftar.app.pdf

import com.daftar.app.ui.pane
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.EditorController
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkEditorScaffold
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Nav
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PdfScreen"
private const val THUMB_PX = 200

private sealed class LoadState {
    data object Loading : LoadState()
    data class Ready(val source: PdfSource) : LoadState()
    data class Failed(val password: Boolean) : LoadState()
}

/** Holds resources that must be closed when the screen leaves (the main renderer and the thumbnail renderer). */
private class PdfResources(val file: File) {
    private var main: PdfSource? = null
    private var thumbs: PdfSource? = null
    private var disposed = false
    val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    /** Keeps [s] for closing on dispose; closes it right away if the screen already left. */
    @Synchronized fun adopt(s: PdfSource): Boolean {
        if (disposed) { s.close(); return false }
        main = s; return true
    }

    /** Separate renderer so thumbnails never wait behind the editor's tile rendering. */
    @Synchronized fun thumbSource(): PdfSource? {
        if (disposed) return null
        return thumbs ?: runCatching { PdfSource(file) }.getOrNull().also { thumbs = it }
    }

    @Synchronized fun dispose() {
        disposed = true
        main?.close(); main = null
        thumbs?.close(); thumbs = null
        cache.evictAll()
    }
}

@Composable
fun PdfScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val res = remember(path) { PdfResources(file) }
    var state by remember(path) { mutableStateOf<LoadState>(LoadState.Loading) }

    DisposableEffect(res) { onDispose { res.dispose() } }
    LaunchedEffect(res) {
        val r = withContext(Dispatchers.IO) {
            runCatching { PdfSource(file).takeIf { res.adopt(it) } }
        }
        r.onSuccess { if (it != null) state = LoadState.Ready(it) }
            .onFailure {
                Log.e(TAG, "open $path", it)
                state = LoadState.Failed(password = it is SecurityException)
            }
        withContext(Dispatchers.IO) { cleanWorkFiles() }
    }

    val title = file.nameWithoutExtension
    when (val s = state) {
        LoadState.Loading -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(title, onBack = { pane.back() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pdf_opening), color = D.c.muted)
                }
            }
        }
        is LoadState.Failed -> Column(Modifier.fillMaxSize().background(D.c.bg)) {
            ViewerTopBar(title, onBack = { pane.back() })
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = if (s.password) Icons.Rounded.Lock else Icons.Rounded.ErrorOutline,
                    text = stringResource(if (s.password) R.string.pdf_error_password else R.string.pdf_error_open),
                    modifier = Modifier.widthIn(max = 480.dp),
                ) {
                    OutlinedButton(onClick = { openExternally(ctx, file) }) { Text(stringResource(R.string.open_externally)) }
                }
            }
        }
        is LoadState.Ready -> InkEditorScaffold(
            title = title,
            inkFile = Storage.sidecar(file, "ink.json"),
            source = s.source,
            isNote = false,
            onBack = { pane.back() },
            extraActions = { ctl -> PdfActions(ctl, file, s.source.pageCount) },
            sidePanel = { ctl -> ThumbnailsPanel(ctl, res, s.source) },
            sidePanelLabel = stringResource(R.string.pdf_thumbnails),
        )
    }
}

/** Removes temp files from earlier share/print jobs (older than 6 h, so a running print job is never affected). */
private fun cleanWorkFiles() {
    runCatching {
        val limit = System.currentTimeMillis() - 6 * 3600_000L
        Storage.cacheDir().listFiles()?.filter { it.name.startsWith("pdfwork-") && it.lastModified() < limit }?.forEach { it.delete() }
    }
}

private fun workFile(name: String): File {
    val d = File(Storage.cacheDir(), "pdfwork-${System.currentTimeMillis()}")
    d.mkdirs()
    return File(d, Storage.sanitize(name).ifBlank { "document" } + ".pdf")
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

// ------------------------------------------------------------------ thumbnails

@Composable
private fun ThumbnailsPanel(ctl: EditorController, res: PdfResources, source: PdfSource) {
    val list = rememberLazyListState()
    val cur = ctl.currentPage
    LaunchedEffect(cur) {
        val visible = list.layoutInfo.visibleItemsInfo
        val fully = visible.filter { it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (fully.none { it.index == cur }) runCatching { list.animateScrollToItem(cur.coerceAtLeast(0)) }
    }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(D.c.surface),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(source.pageCount, key = { it }) { i ->
            Thumb(i, selected = i == cur, res = res, source = source) { ctl.goToPage(i) }
        }
    }
}

@Composable
private fun Thumb(i: Int, selected: Boolean, res: PdfResources, source: PdfSource, onClick: () -> Unit) {
    val (pw, ph) = source.pageSize(i)
    val bmp by produceState<ImageBitmap?>(res.cache.get(i)?.takeIf { !it.isRecycled }?.asImageBitmap(), i) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                res.cache.get(i) ?: res.thumbSource()?.renderFit(i, THUMB_PX, THUMB_PX * 2)?.also { res.cache.put(i, it) }
            }.onFailure { Log.e(TAG, "thumb $i", it) }.getOrNull()
        }?.asImageBitmap()
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.widthIn(max = 200.dp).fillMaxWidth().clip8().clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio((pw / ph).coerceIn(0.2f, 5f))
                .background(androidx.compose.ui.graphics.Color.White, shape)
                .border(if (selected) 2.dp else 1.dp, if (selected) D.c.accent else D.c.line, shape)
                .padding(if (selected) 2.dp else 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            if (b != null) {
                Image(b, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            (i + 1).toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) D.c.accent else D.c.muted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Modifier.clip8() = this.clip(RoundedCornerShape(8.dp))

// ------------------------------------------------------------------ top-bar actions + tools

private enum class Dlg { NONE, GOTO, PRINT, TEXT, EXTRACT, IMAGES }

@Composable
private fun PdfActions(ctl: EditorController, file: File, pageCount: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf(Dlg.NONE) }
    var busy by remember { mutableStateOf<BusyState?>(null) }
    val cancelled = remember { AtomicBoolean(false) }
    val name = file.nameWithoutExtension
    val cur = ctl.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

    /** Saves the editor and returns an immutable snapshot of the ink layer (main thread). */
    fun snapshot(): InkDoc {
        runCatching { ctl.saveNow() }
        val d = runCatching { ctl.doc() }.getOrNull() ?: return InkDoc()
        return InkDoc(pages = d.pages.toList())
    }

    fun run(message: String, cancellable: Boolean = false, work: suspend CoroutineScope.(BusyState) -> Unit) {
        if (busy != null) return
        val st = BusyState(message, cancellable)
        cancelled.set(false)
        busy = st
        scope.launch {
            try {
                work(st)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "pdf tool failed", t)
                toast(ctx, ctx.getString(R.string.error_generic))
            } finally {
                busy = null
            }
        }
    }

    /** Source file for derived outputs: the annotated copy when there is ink to include, else the original. */
    fun baseFile(ink: InkDoc, withInk: Boolean): File =
        if (withInk && PdfTools.hasInk(ink)) PdfTools.exportAnnotated(file, ink, workFile(ctx.getString(R.string.pdf_file_annotated, name)))
        else file

    // --- top bar: page indicator (tap = go to page) + tools menu
    Text(
        stringResource(R.string.page_of, cur + 1, pageCount),
        style = MaterialTheme.typography.labelLarge, color = D.c.muted,
        modifier = Modifier.clip8().clickable { dlg = Dlg.GOTO }.padding(horizontal = 10.dp, vertical = 12.dp),
    )
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.pdf_tools), tint = D.c.ink) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            @Composable
            fun item(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, action: () -> Unit) =
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    leadingIcon = { Icon(icon, null, tint = D.c.muted) },
                    onClick = { menu = false; action() },
                )
            item(R.string.pdf_go_to_page, Icons.Rounded.FindInPage) { dlg = Dlg.GOTO }
            item(R.string.pdf_export_annotated, Icons.Rounded.Save) {
                val ink = snapshot()
                run(ctx.getString(R.string.pdf_exporting)) {
                    val out = withContext(Dispatchers.IO) {
                        val target = Storage.uniqueFile(file.parentFile!!, ctx.getString(R.string.pdf_file_annotated, name), "pdf")
                        PdfTools.exportAnnotated(file, ink, target)
                    }
                    Storage.touch()
                    toast(ctx, ctx.getString(R.string.saved_to, out.name))
                }
            }
            item(R.string.share, Icons.Rounded.Share) {
                val ink = snapshot()
                if (!PdfTools.hasInk(ink)) shareFiles(ctx, listOf(file))
                else run(ctx.getString(R.string.pdf_exporting)) {
                    val out = withContext(Dispatchers.IO) { baseFile(ink, true) }
                    shareFiles(ctx, listOf(out))
                }
            }
            item(R.string.pdf_print, Icons.Rounded.Print) { dlg = Dlg.PRINT }
            item(R.string.pdf_copy_text, Icons.Rounded.ContentCopy) { dlg = Dlg.TEXT }
            item(R.string.pdf_extract_pages, Icons.Rounded.ContentCut) { dlg = Dlg.EXTRACT }
            item(R.string.pdf_pages_to_images, Icons.Rounded.Image) { dlg = Dlg.IMAGES }
            item(R.string.open_externally, Icons.AutoMirrored.Rounded.OpenInNew) { openExternally(ctx, file) }
        }
    }

    // --- dialogs
    when (dlg) {
        Dlg.NONE -> {}
        Dlg.GOTO -> GoToPageDialog(pageCount, cur, onDismiss = { dlg = Dlg.NONE }) { ctl.goToPage(it); dlg = Dlg.NONE }
        Dlg.TEXT -> CopyTextDialog(file, pageCount, cur, onDismiss = { dlg = Dlg.NONE })
        Dlg.PRINT -> PrintDialog(pageCount, cur, onDismiss = { dlg = Dlg.NONE }) { sc, pages ->
            dlg = Dlg.NONE
            val ink = snapshot()
            val printCtx = ctx.findActivity() ?: ctx
            run(ctx.getString(R.string.pdf_preparing_print)) {
                val doc = withContext(Dispatchers.IO) {
                    val base = baseFile(ink, true)
                    if (sc == PageScope.ALL) base
                    else PdfTools.extractPages(base, pages, workFile(name))
                }
                PdfTools.print(printCtx, doc, name)
            }
        }
        Dlg.EXTRACT -> ExtractDialog(pageCount, cur, PdfTools.hasInk(runCatching { ctl.doc() }.getOrNull()), onDismiss = { dlg = Dlg.NONE }) { pages, label, withInk ->
            dlg = Dlg.NONE
            val ink = snapshot()
            run(ctx.getString(R.string.pdf_extracting)) {
                val out = withContext(Dispatchers.IO) {
                    val base = baseFile(ink, withInk)
                    val target = Storage.uniqueFile(file.parentFile!!, ctx.getString(R.string.pdf_file_pages, name, label), "pdf")
                    PdfTools.extractPages(base, pages, target)
                }
                Storage.touch()
                toast(ctx, ctx.getString(R.string.saved_to, out.name))
            }
        }
        Dlg.IMAGES -> ImagesDialog(pageCount, cur, PdfTools.hasInk(runCatching { ctl.doc() }.getOrNull()), onDismiss = { dlg = Dlg.NONE }) { pages, jpeg, withInk ->
            dlg = Dlg.NONE
            val ink = if (withInk) snapshot() else null
            run(ctx.getString(R.string.pdf_rendering), cancellable = true) { st ->
                st.total = pages.size
                val dir = File(file.parentFile, Storage.sanitize(ctx.getString(R.string.pdf_folder_images, name)))
                val written = withContext(Dispatchers.IO) {
                    PdfTools.pagesToImages(
                        file, pages, dir, jpeg, ink,
                        nameFor = { n -> ctx.getString(R.string.pdf_image_name, n) },
                        isCancelled = { cancelled.get() },
                        onProgress = { d, t -> scope.launch { st.done = d; st.total = t } },
                    )
                }
                Storage.touch()
                toast(ctx, ctx.getString(R.string.pdf_images_saved, written.size, dir.name))
            }
        }
    }

    busy?.let { st -> BusyDialog(st, onCancel = { cancelled.set(true) }) }
}
