package com.daftar.app.pdf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HomeRepairService
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ink.EditorController
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkEditorScaffold
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.ViewerActions
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PdfScreen"

private sealed class LoadState {
    data object Loading : LoadState()
    data class Ready(val source: PdfSource) : LoadState()
    data class Failed(val password: Boolean) : LoadState()
}

/** State that outlives a reload of the same file (after a page edit). */
private class PdfHost {
    /** Page to show once the reloaded editor is ready (-1 = none). */
    var pendingPage = -1
    /** Completed when the editor has left composition (it saves its ink on dispose). */
    var editorGone: CompletableDeferred<Unit>? = null
}

@Composable
fun PdfScreen(path: String) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val file = remember(path) { File(path) }
    val inkFile = remember(path) { Storage.sidecar(file, "ink.json") }
    var version by remember(path) { mutableIntStateOf(0) }
    var working by remember(path) { mutableStateOf<String?>(null) }
    val host = remember(path) { PdfHost() }
    val session = remember(path, version) { PdfSession(file, density) }
    var state by remember(session) { mutableStateOf<LoadState>(LoadState.Loading) }
    val scope = rememberCoroutineScope()
    val actions = rememberViewerActions(file)

    DisposableEffect(session) { onDispose { session.dispose() } }
    LaunchedEffect(session) {
        val r = withContext(Dispatchers.IO) { runCatching { PdfSource(file).takeIf { session.adopt(it) } } }
        r.onSuccess { if (it != null) state = LoadState.Ready(it) }
            .onFailure {
                Log.e(TAG, "open $path", it)
                state = LoadState.Failed(password = it is SecurityException)
            }
        withContext(Dispatchers.IO) { cleanWorkFiles() }
    }

    /**
     * Applies a page plan. The editor leaves composition first (its final save commits any floating selection),
     * then the PDF and the ink sidecar are rewritten from what is on disk, and the file is reopened at [focus].
     */
    fun editPages(plan: List<PageSpec>, focus: Int) {
        if (working != null) return
        val gone = CompletableDeferred<Unit>()
        host.editorGone = gone
        working = ctx.getString(R.string.pdf_updating_pages)
        scope.launch {
            gone.await()
            val ok = withContext(NonCancellable + Dispatchers.IO) {
                delay(120) // let a save that was already being written finish
                runCatching {
                    PdfPages.rebuild(file, plan, InkDoc.load(inkFile) ?: InkDoc(), inkFile)
                    Storage.touch()
                }.onFailure { Log.e(TAG, "page edit failed", it) }.isSuccess
            }
            if (!ok) toast(ctx, ctx.getString(R.string.pdf_page_edit_failed))
            host.pendingPage = focus
            working = null
            version++
        }
    }

    val title = file.nameWithoutExtension
    BoxWithConstraints(Modifier.fillMaxSize().background(D.c.bg)) {
        val narrow = maxWidth < 600.dp
        val height = maxHeight
        val w = working
        when (val s = state) {
            is LoadState.Ready -> if (w == null) key(session) {
                PdfReader(file, session, s.source, actions, narrow, height, host, ::editPages)
            } else StatusScreen(title, w)
            LoadState.Loading -> StatusScreen(title, w ?: stringResource(R.string.pdf_opening))
            is LoadState.Failed -> Column(Modifier.fillMaxSize()) {
                ViewerTopBar(title, onBack = { pane.back() }) { OverflowOnly(actions) }
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
        }
    }
}

@Composable
private fun StatusScreen(title: String, message: String) {
    Column(Modifier.fillMaxSize().background(D.c.bg)) {
        ViewerTopBar(title, onBack = { pane.back() })
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = D.c.accent)
                Spacer(Modifier.height(12.dp))
                Text(message, color = D.c.muted)
            }
        }
    }
}

@Composable
private fun OverflowOnly(actions: ViewerActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.ink) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { ViewerMenuItems(actions, close = { menu = false }) }
    }
}

/** The reader: ink editor + header actions + side panel (Pages | Outline) + search overlay. */
@Composable
private fun PdfReader(
    file: File,
    session: PdfSession,
    source: PdfSource,
    actions: ViewerActions,
    narrow: Boolean,
    maxHeight: Dp,
    host: PdfHost,
    editPages: (List<PageSpec>, Int) -> Unit,
) {
    val search = remember(session) { PdfSearchState() }
    var tab by remember(session) { mutableStateOf(PanelTab.PAGES) }
    var ctlRef by remember(session) { mutableStateOf<EditorController?>(null) }
    var confirmDelete by remember(session) { mutableIntStateOf(-1) }

    // Declared before the editor so it is disposed after it (the editor's own dispose saves the ink).
    DisposableEffect(Unit) { onDispose { host.editorGone?.complete(Unit) } }

    fun pageAction(a: PageAction, i: Int) {
        val n = session.pageCount
        if (i !in 0 until n) return
        when (a) {
            PageAction.INSERT_BLANK -> { val (w, h) = session.pageSize(i); editPages(PdfPages.insertBlank(n, i, w, h), i + 1) }
            PageAction.ROTATE_RIGHT -> editPages(PdfPages.rotate(n, i, 90), i)
            PageAction.ROTATE_LEFT -> editPages(PdfPages.rotate(n, i, 270), i)
            PageAction.MOVE_UP -> if (i > 0) editPages(PdfPages.move(n, i, -1), i - 1)
            PageAction.MOVE_DOWN -> if (i < n - 1) editPages(PdfPages.move(n, i, 1), i + 1)
            PageAction.DELETE -> if (n > 1) confirmDelete = i
        }
    }

    Box(Modifier.fillMaxSize()) {
        InkEditorScaffold(
            title = file.nameWithoutExtension,
            inkFile = Storage.sidecar(file, "ink.json"),
            source = source,
            isNote = false,
            onBack = { pane.back() },
            extraActions = { ctl ->
                LaunchedEffect(ctl) {
                    ctlRef = ctl
                    // After a page edit: show the page the user was working on once the new editor is ready.
                    val p = host.pendingPage
                    if (p >= 0) {
                        host.pendingPage = -1
                        snapshotFlow { ctl.pageCount }.first { it > 0 }
                        delay(80)
                        ctl.goToPage(p.coerceIn(0, ctl.pageCount - 1))
                    }
                }
                PdfHeaderActions(ctl, file, session, actions, narrow, search, ::pageAction, editPages)
            },
            sidePanel = { ctl -> PdfSidePanel(ctl, session, tab, { tab = it }, ::pageAction) },
            sidePanelLabel = stringResource(R.string.pdf_panel),
            sidePanelAtStart = true,
        )
        val ctl = ctlRef
        if (search.open && ctl != null) PdfSearchOverlay(search, session, source, ctl, narrow, maxHeight)
    }

    if (confirmDelete >= 0) {
        val i = confirmDelete
        ConfirmDialog(
            title = stringResource(R.string.pdf_delete_page_title, i + 1),
            text = stringResource(R.string.pdf_delete_page_text),
            confirm = stringResource(R.string.delete),
            danger = true,
            onDismiss = { confirmDelete = -1 },
        ) {
            confirmDelete = -1
            val n = session.pageCount
            editPages(PdfPages.delete(n, setOf(i)), i.coerceAtMost(n - 2).coerceAtLeast(0))
        }
    }
}

/** Removes temp files from earlier share/print jobs (older than 6 h, so a running print job is never affected). */
private fun cleanWorkFiles() {
    runCatching {
        val limit = System.currentTimeMillis() - 6 * 3600_000L
        Storage.cacheDir().listFiles()?.filter { it.name.startsWith("pdfwork-") && it.lastModified() < limit }?.forEach { it.deleteRecursively() }
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

// ------------------------------------------------------------------ header actions + tools

private enum class Dlg { NONE, GOTO, PRINT, TEXT, EXTRACT, IMAGES, ORGANIZE, MERGE_PICK, MERGE, SIGN }

private enum class MenuLevel { MAIN, PAGES }

@Composable
private fun PdfHeaderActions(
    ctl: EditorController,
    file: File,
    session: PdfSession,
    actions: ViewerActions,
    narrow: Boolean,
    search: PdfSearchState,
    onPageAction: (PageAction, Int) -> Unit,
    editPages: (List<PageSpec>, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(MenuLevel.MAIN) }
    var dlg by remember { mutableStateOf(Dlg.NONE) }
    var busy by remember { mutableStateOf<BusyState?>(null) }
    var mergeFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var saved by remember { mutableStateOf<File?>(null) }
    val cancelled = remember { AtomicBoolean(false) }
    val name = file.nameWithoutExtension
    val pageCount = session.pageCount
    val cur = ctl.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val c = D.c

    /** Saves the editor and returns an immutable snapshot of the ink layer (main thread). */
    fun snapshot(): InkDoc {
        runCatching { ctl.saveNow() }
        val d = runCatching { ctl.doc() }.getOrNull() ?: return InkDoc()
        return InkDoc(pages = d.pages.toList(), paperColor = d.paperColor, recordings = d.recordings)
    }

    fun run(message: String, cancellable: Boolean = false, work: suspend CoroutineScope.(BusyState) -> Unit) {
        if (busy != null) return
        val st = BusyState(message, cancellable)
        cancelled.set(false)
        busy = st
        scope.launch {
            try {
                work(st)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "pdf tool failed", t)
                toast(ctx, ctx.getString(R.string.error_generic))
            } finally {
                busy = null
            }
        }
    }

    /** Source for derived outputs: the annotated copy when there is ink to include, else the original. */
    fun baseFile(ink: InkDoc, withInk: Boolean): File =
        if (withInk && PdfTools.hasInk(ink)) PdfTools.exportAnnotated(file, ink, workFile(ctx.getString(R.string.pdf_file_annotated, name)))
        else file

    fun shareAnnotated() {
        val ink = snapshot()
        if (!PdfTools.hasInk(ink)) shareFiles(ctx, listOf(file))
        else run(ctx.getString(R.string.pdf_exporting)) {
            val out = withContext(Dispatchers.IO) { baseFile(ink, true) }
            shareFiles(ctx, listOf(out))
        }
    }

    fun closeMenu() { menu = false; level = MenuLevel.MAIN }

    IconButton(onClick = { search.open = true }) {
        Icon(Icons.Rounded.Search, stringResource(R.string.pdf_search), tint = if (search.open) c.accent else c.ink)
    }
    if (!narrow) ConvertButton(actions)
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.HomeRepairService, stringResource(R.string.pdf_tools), tint = c.ink) }
        DropdownMenu(expanded = menu, onDismissRequest = { closeMenu() }) {
            @Composable
            fun item(text: String, icon: ImageVector, enabled: Boolean = true, danger: Boolean = false, trailing: Boolean = false, action: () -> Unit) =
                DropdownMenuItem(
                    text = { Text(text, color = when { !enabled -> c.muted; danger -> c.danger; else -> c.ink }) },
                    leadingIcon = { Icon(icon, null, tint = when { !enabled -> c.line; danger -> c.danger; else -> c.muted }) },
                    trailingIcon = if (trailing) { { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted) } } else null,
                    enabled = enabled,
                    onClick = action,
                )
            when (level) {
                MenuLevel.MAIN -> {
                    item(stringResource(R.string.pdf_go_to_page), Icons.Rounded.FindInPage) { closeMenu(); dlg = Dlg.GOTO }
                    item(stringResource(R.string.pdf_pages_menu), Icons.Rounded.AutoStories, trailing = true) { level = MenuLevel.PAGES }
                    item(stringResource(R.string.pdf_sign), Icons.Rounded.Draw) { closeMenu(); dlg = Dlg.SIGN }
                    item(stringResource(R.string.pdf_export_annotated), Icons.Rounded.Save) {
                        closeMenu()
                        val ink = snapshot()
                        run(ctx.getString(R.string.pdf_exporting)) {
                            val out = withContext(Dispatchers.IO) {
                                val target = Storage.uniqueFile(file.parentFile!!, ctx.getString(R.string.pdf_file_annotated, name), "pdf")
                                PdfTools.exportAnnotated(file, ink, target)
                            }
                            Storage.touch()
                            saved = out
                        }
                    }
                    item(stringResource(R.string.pdf_print), Icons.Rounded.Print) { closeMenu(); dlg = Dlg.PRINT }
                    item(stringResource(R.string.pdf_copy_text), Icons.Rounded.ContentCopy) { closeMenu(); dlg = Dlg.TEXT }
                    item(stringResource(R.string.pdf_pages_to_images), Icons.Rounded.Image) { closeMenu(); dlg = Dlg.IMAGES }
                    HorizontalDivider(color = c.line)
                    ViewerMenuItems(actions, close = { closeMenu() }, onShare = { shareAnnotated() }, showConvert = narrow)
                }
                MenuLevel.PAGES -> {
                    item(stringResource(R.string.pdf_pages_menu), Icons.AutoMirrored.Rounded.ArrowBack) { level = MenuLevel.MAIN }
                    HorizontalDivider(color = c.line)
                    item(stringResource(R.string.pdf_insert_blank), Icons.AutoMirrored.Rounded.NoteAdd) { closeMenu(); onPageAction(PageAction.INSERT_BLANK, cur) }
                    item(stringResource(R.string.pdf_rotate_right), Icons.Rounded.RotateRight) { closeMenu(); onPageAction(PageAction.ROTATE_RIGHT, cur) }
                    item(stringResource(R.string.pdf_rotate_left), Icons.Rounded.RotateLeft) { closeMenu(); onPageAction(PageAction.ROTATE_LEFT, cur) }
                    item(stringResource(R.string.pdf_move_up), Icons.Rounded.ArrowUpward, enabled = cur > 0) { closeMenu(); onPageAction(PageAction.MOVE_UP, cur) }
                    item(stringResource(R.string.pdf_move_down), Icons.Rounded.ArrowDownward, enabled = cur < pageCount - 1) { closeMenu(); onPageAction(PageAction.MOVE_DOWN, cur) }
                    item(stringResource(R.string.pdf_delete_page), Icons.Rounded.DeleteOutline, enabled = pageCount > 1, danger = true) { closeMenu(); onPageAction(PageAction.DELETE, cur) }
                    HorizontalDivider(color = c.line)
                    item(stringResource(R.string.pdf_organize_pages), Icons.Rounded.GridView) { closeMenu(); dlg = Dlg.ORGANIZE }
                    item(stringResource(R.string.pdf_extract_pages), Icons.Rounded.ContentCut) { closeMenu(); dlg = Dlg.EXTRACT }
                    item(stringResource(R.string.pdf_merge), Icons.AutoMirrored.Rounded.CallMerge) { closeMenu(); dlg = Dlg.MERGE_PICK }
                }
            }
        }
    }

    // --- dialogs
    when (dlg) {
        Dlg.NONE -> {}
        Dlg.GOTO -> GoToPageDialog(pageCount, cur, onDismiss = { dlg = Dlg.NONE }) { ctl.goToPage(it); dlg = Dlg.NONE }
        Dlg.TEXT -> CopyTextDialog(file, pageCount, cur, onDismiss = { dlg = Dlg.NONE })
        Dlg.SIGN -> SignatureDialog(onDismiss = { dlg = Dlg.NONE }) { bmp -> dlg = Dlg.NONE; ctl.addImage(bmp) }
        Dlg.ORGANIZE -> OrganizePagesDialog(
            session, runCatching { ctl.doc().pages.toList() }.getOrDefault(emptyList()), cur,
            onDismiss = { dlg = Dlg.NONE },
        ) { plan ->
            dlg = Dlg.NONE
            val focus = plan.indexOfFirst { it.src == cur }.takeIf { it >= 0 } ?: cur.coerceAtMost(plan.size - 1)
            editPages(plan, focus)
        }
        Dlg.PRINT -> PrintDialog(pageCount, cur, onDismiss = { dlg = Dlg.NONE }) { sc, pages ->
            dlg = Dlg.NONE
            val ink = snapshot()
            val printCtx = ctx.findActivity() ?: ctx
            run(ctx.getString(R.string.pdf_preparing_print)) {
                val doc = withContext(Dispatchers.IO) {
                    val base = baseFile(ink, true)
                    if (sc == PageScope.ALL) base else PdfTools.extractPages(base, pages, workFile(name))
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
                saved = out
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
        Dlg.MERGE_PICK -> LibraryFilePickerDialog(
            title = stringResource(R.string.pdf_merge_pick),
            accept = { f -> Storage.kindOf(f) == Kind.PDF && f.absolutePath != file.absolutePath },
            multiple = true,
            onDismiss = { dlg = Dlg.NONE },
            start = file.parentFile ?: Storage.root,
        ) { picked ->
            mergeFiles = listOf(file) + picked
            dlg = Dlg.MERGE
        }
        Dlg.MERGE -> MergeDialog(mergeFiles, ctx.getString(R.string.pdf_file_merged, name), onDismiss = { dlg = Dlg.NONE }) { order, outName ->
            dlg = Dlg.NONE
            val ink = snapshot()
            run(ctx.getString(R.string.pdf_merging), cancellable = true) { st ->
                val out = withContext(Dispatchers.IO) {
                    val target = Storage.uniqueFile(file.parentFile!!, outName, "pdf")
                    val inks = order.map { f -> if (f.absolutePath == file.absolutePath) ink else InkDoc.load(Storage.sidecar(f, "ink.json")) }
                    PdfPages.merge(order, inks, target, isCancelled = { cancelled.get() }) { d, t -> scope.launch { st.done = d; st.total = t } }
                }
                Storage.touch()
                saved = out
            }
        }
    }

    saved?.let { f ->
        SavedDialog(f, onDismiss = { saved = null }) { saved = null; pane.open(ctx, f) }
    }
    busy?.let { st -> BusyDialog(st, onCancel = { cancelled.set(true) }) }
}
