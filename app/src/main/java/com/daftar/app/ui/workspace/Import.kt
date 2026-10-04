package com.daftar.app.ui.workspace

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.data.FolderMeta
import com.daftar.app.data.Storage
import com.daftar.app.ui.PaneNav
import com.daftar.app.ui.RootPaneNav
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.FolderPalette
import com.daftar.app.ui.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the user picked to import. */
sealed class ImportSource {
    /** Individual documents (OpenMultipleDocuments, shares). */
    data class Docs(val uris: List<Uri>) : ImportSource()
    /** A whole folder (OpenDocumentTree), copied recursively with its structure. */
    data class Tree(val uri: Uri) : ImportSource()
}

/** A pending import waiting for the Copy / Move choice. */
data class ImportRequest(val source: ImportSource, val dest: File, val nav: PaneNav?)

/** Final report of an import. [top] is the imported folder (tree imports) or null. */
data class ImportResult(
    val files: List<File>, val top: File?, val failed: List<String>, val notRemoved: List<String>,
    val cancelled: Boolean, val move: Boolean,
)

/**
 * Runs imports on IO in a process-wide scope (survives screen changes), exposing observable progress for [ImportHost].
 * One import at a time; starting another while one runs is refused with a toast.
 */
object Importer {
    enum class Phase { SCANNING, COPYING, REMOVING }

    class Progress(phase: Phase, done: Int, total: Int, current: String) {
        var phase by mutableStateOf(phase)
        var done by mutableIntStateOf(done)
        var total by mutableIntStateOf(total)
        var current by mutableStateOf(current)
    }

    /** Non-null while an import runs. */
    var progress by mutableStateOf<Progress?>(null)
        private set
    /** Shown after an import that needs a report (failures, originals kept, cancel). */
    var report by mutableStateOf<ImportResult?>(null)
    /** Import waiting for the Copy / Move answer. */
    var request by mutableStateOf<ImportRequest?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    val running get() = progress != null

    /** Window (RootPaneNav for the main window, or a [WindowNav]) whose [ImportHost] shows the dialogs. */
    internal var owner: Any by mutableStateOf<Any>(RootPaneNav)
        private set

    /** The window a pane / window navigation belongs to. */
    fun windowOf(nav: PaneNav?): Any = when (nav) {
        is WindowNav -> nav
        is SplitPane -> (nav.controller.host as? WindowNav) ?: RootPaneNav
        else -> RootPaneNav
    }

    /** Asks Copy or Move (dialog in [ImportHost]), then imports. */
    fun ask(source: ImportSource, dest: File, nav: PaneNav?) {
        if (!running) owner = windowOf(nav)
        request = ImportRequest(source, dest, nav)
    }

    @Volatile private var cancelRequested = false
    private val live: Boolean get() = !cancelRequested

    fun cancel() { cancelRequested = true }

    /**
     * Imports [source] into [dest]. [move] removes the originals after a successful copy.
     * [onDone] runs on the main thread with the result (e.g. to open the single imported file).
     */
    fun start(ctx: Context, source: ImportSource, dest: File, move: Boolean, onDone: (ImportResult) -> Unit = {}) {
        if (running) { toast(ctx, ctx.getString(R.string.ws_import_busy)); return }
        if (ctx is com.daftar.app.MainActivity) owner = RootPaneNav
        val app = ctx.applicationContext
        val p = Progress(Phase.SCANNING, 0, 0, "")
        progress = p
        cancelRequested = false
        job = scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    when (source) {
                        is ImportSource.Docs -> importDocs(app, source.uris, dest, move, p)
                        is ImportSource.Tree -> importTree(app, source.uri, dest, move, p)
                    }
                }.getOrElse { ImportResult(emptyList(), null, emptyList(), emptyList(), cancelled = true, move = move) }
            }
            Storage.touch()
            progress = null
            job = null
            if (res.cancelled || res.failed.isNotEmpty() || res.notRemoved.isNotEmpty()) report = res
            else if (res.files.isNotEmpty() || res.top != null) {
                if (res.files.size > 1 || res.top != null) toast(ctx, app.resources.getQuantityString(R.plurals.ws_imported_n, res.files.size, res.files.size))
            }
            onDone(res)
        }
    }

    /** Progress fields are snapshot state; writing them from the IO thread is safe and never suspends (cancel keeps partial results). */
    private inline fun progressUpdate(block: () -> Unit) = block()

    private fun importDocs(ctx: Context, uris: List<Uri>, dest: File, move: Boolean, p: Progress): ImportResult {
        val files = ArrayList<File>(); val failed = ArrayList<String>(); val copied = ArrayList<Pair<Uri, String>>()
        progressUpdate { p.phase = Phase.COPYING; p.total = uris.size }
        var cancelled = false
        for ((i, uri) in uris.withIndex()) {
            if (!live) { cancelled = true; break }
            val name = Storage.nameWithExt(ctx, uri)
            progressUpdate { p.current = name; p.done = i }
            val f = Storage.copyIn(ctx, uri, dest, name) { live }
            if (f != null) { files.add(f); copied.add(uri to name) } else if (live) failed.add(name) else { cancelled = true; break }
        }
        val notRemoved = ArrayList<String>()
        if (move && !cancelled && copied.isNotEmpty()) {
            progressUpdate { p.phase = Phase.REMOVING; p.done = 0; p.total = copied.size }
            for ((i, c) in copied.withIndex()) {
                progressUpdate { p.current = c.second; p.done = i }
                if (!Storage.deleteDocument(ctx, c.first)) notRemoved.add(c.second)
            }
        }
        return ImportResult(files, null, failed, notRemoved, cancelled, move)
    }

    private fun importTree(ctx: Context, tree: Uri, dest: File, move: Boolean, p: Progress): ImportResult {
        val rootName = Storage.treeName(ctx, tree)
        progressUpdate { p.phase = Phase.SCANNING; p.current = rootName }
        val docs = Storage.listTree(ctx, tree) { live }
        if (!live) return ImportResult(emptyList(), null, emptyList(), emptyList(), cancelled = true, move = move)
        val fileDocs = docs.filter { !it.isDir }
        val color = (Storage.countItems(dest) * 5) % FolderPalette.size
        val meta = FolderMeta(color = color, icon = "folder")
        val top = Storage.makeImportedFolder(dest, rootName, meta, unique = true)
        // directories first so empty folders are kept too (structure preserved)
        val dirs = HashMap<String, File>().apply { put("", top) }
        fun dirFor(rel: String): File = dirs.getOrPut(rel) {
            val parent = dirFor(rel.substringBeforeLast('/', ""))
            Storage.makeImportedFolder(parent, rel.substringAfterLast('/'), meta, unique = false)
        }
        docs.filter { it.isDir }.forEach { dirFor(if (it.rel.isEmpty()) it.name else it.rel + "/" + it.name) }
        progressUpdate { p.phase = Phase.COPYING; p.total = fileDocs.size; p.done = 0 }
        val files = ArrayList<File>(); val failed = ArrayList<String>(); val copied = ArrayList<Storage.TreeDoc>()
        var cancelled = false
        for ((i, d) in fileDocs.withIndex()) {
            if (!live) { cancelled = true; break }
            progressUpdate { p.current = d.name; p.done = i }
            val f = Storage.copyIn(ctx, d.uri, dirFor(d.rel), d.name) { live }
            val shown = if (d.rel.isEmpty()) d.name else d.rel + "/" + d.name
            if (f != null) { files.add(f); copied.add(d) } else if (live) failed.add(shown) else { cancelled = true; break }
        }
        val notRemoved = ArrayList<String>()
        if (move && !cancelled) {
            progressUpdate { p.phase = Phase.REMOVING; p.done = 0; p.total = copied.size; p.current = rootName }
            // everything copied: remove the picked folder in one go; otherwise only the files that made it
            val wholeOk = failed.isEmpty() && Storage.deleteDocument(ctx, Storage.treeRootDoc(tree))
            if (!wholeOk) {
                for ((i, d) in copied.withIndex()) {
                    progressUpdate { p.current = d.name; p.done = i }
                    if (!Storage.deleteDocument(ctx, d.uri)) notRemoved.add(if (d.rel.isEmpty()) d.name else d.rel + "/" + d.name)
                }
            }
        }
        return ImportResult(files, top, failed, notRemoved, cancelled, move)
    }
}

/**
 * Dialogs of the import flow: Copy / Move choice, progress (counts, current name, cancel) and the final report.
 * Hosted once per window (MainActivity, WindowActivity).
 */
@Composable
fun ImportHost(window: Any = RootPaneNav) {
    val c = D.c
    if (Importer.owner !== window) return
    Importer.request?.let { req -> CopyMoveDialog(req) }

    Importer.progress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(stringResource(when (p.phase) {
                    Importer.Phase.SCANNING -> R.string.ws_scanning
                    Importer.Phase.COPYING -> R.string.ws_importing
                    Importer.Phase.REMOVING -> R.string.ws_removing
                }))
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (p.phase == Importer.Phase.SCANNING || p.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                    else LinearProgressIndicator({ (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                    Spacer(Modifier.height(12.dp))
                    if (p.total > 0) Text(stringResource(R.string.ws_progress, minOf(p.done + 1, p.total), p.total), color = c.ink, style = MaterialTheme.typography.labelLarge)
                    Text(p.current, color = c.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {},
            dismissButton = {
                if (p.phase != Importer.Phase.REMOVING) TextButton(onClick = { Importer.cancel() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Importer.report?.let { r ->
        AlertDialog(
            onDismissRequest = { Importer.report = null },
            title = {
                Text(stringResource(when {
                    r.cancelled -> R.string.ws_import_cancelled_title
                    r.notRemoved.isNotEmpty() -> R.string.ws_not_removed_title
                    else -> R.string.ws_import_done_title
                }))
            },
            text = {
                Column {
                    Text(pluralStringResource(R.plurals.ws_imported_n, r.files.size, r.files.size), color = c.ink)
                    if (r.failed.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.ws_failed_n, r.failed.size, r.failed.size), color = c.danger)
                        NameList(r.failed)
                    }
                    if (r.notRemoved.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(pluralStringResource(R.plurals.ws_not_removed_n, r.notRemoved.size, r.notRemoved.size), color = c.ink)
                        NameList(r.notRemoved)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Importer.report = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun NameList(names: List<String>) {
    LazyColumn(Modifier.heightIn(max = 180.dp).padding(top = 4.dp)) {
        items(names) { Text("• $it", color = D.c.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun CopyMoveDialog(req: ImportRequest) {
    val ctx = LocalContext.current
    val c = D.c
    // Move needs the provider to allow deleting the originals; check off the main thread.
    var canMove by remember(req) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(req) {
        canMove = withContext(Dispatchers.IO) {
            when (val s = req.source) {
                is ImportSource.Docs -> s.uris.any { Storage.canDelete(ctx, it) }
                is ImportSource.Tree -> Storage.canDelete(ctx, Storage.treeRootDoc(s.uri))
            }
        }
    }
    val title = when (val s = req.source) {
        is ImportSource.Docs -> pluralStringResource(R.plurals.ws_import_items, s.uris.size, s.uris.size)
        is ImportSource.Tree -> stringResource(R.string.ws_import_folder_named, remember(s) { Storage.treeName(ctx, s.uri) })
    }
    fun go(move: Boolean) {
        Importer.request = null
        Importer.start(ctx, req.source, req.dest, move) { res ->
            val nav = req.nav ?: return@start
            when {
                res.cancelled -> {}
                res.top != null -> nav.open(ctx, res.top)
                res.files.size == 1 -> nav.open(ctx, res.files[0])
            }
        }
    }
    AlertDialog(
        onDismissRequest = { Importer.request = null },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(stringResource(R.string.ws_import_into, if (Storage.isRoot(req.dest)) stringResource(R.string.files) else req.dest.name),
                    color = c.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                ChoiceRow(Icons.Rounded.ContentCopy, stringResource(R.string.ws_copy), stringResource(R.string.ws_copy_desc), true) { go(false) }
                Spacer(Modifier.height(8.dp))
                ChoiceRow(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.ws_move),
                    stringResource(if (canMove == false) R.string.ws_move_unavailable else R.string.ws_move_desc), canMove != false) { go(true) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { Importer.request = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(icon: ImageVector, title: String, desc: String, enabled: Boolean, onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt.copy(alpha = if (enabled) 0.6f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) c.accent else c.muted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.ink else c.muted, style = MaterialTheme.typography.titleMedium)
            Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
