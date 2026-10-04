package com.daftar.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Entry
import com.daftar.app.data.FolderMeta
import com.daftar.app.data.Kind
import com.daftar.app.data.Prefs
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.pdf.PdfTools
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.FolderPalette
import com.daftar.app.ui.theme.folderColor
import com.daftar.app.ui.workspace.ImportSource
import com.daftar.app.ui.workspace.Importer
import com.daftar.app.ui.workspace.Workspace
import com.daftar.app.ui.workspace.fileItemGestures
import com.daftar.app.ui.workspace.rememberSplitPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================================
// Visual atoms
// =====================================================================================

/** Two-tone folder shape in the folder colour with the subject icon on the front (DESIGN.md). */
@Composable
fun FolderGlyph(colorIdx: Int, icon: String, size: Dp = 48.dp) {
    val base = folderColor(colorIdx)
    val back = lerp(base, Color.Black, 0.18f)
    val front = lerp(base, Color.White, 0.12f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val w = this.size.width; val h = this.size.height
            val r = w * 0.10f
            // back with tab
            val tab = Path().apply {
                moveTo(0f, h * 0.22f + r); quadraticTo(0f, h * 0.12f, r, h * 0.12f)
                lineTo(w * 0.38f, h * 0.12f); lineTo(w * 0.48f, h * 0.22f)
                lineTo(w - r, h * 0.22f); quadraticTo(w, h * 0.22f, w, h * 0.22f + r)
                lineTo(w, h * 0.88f); lineTo(0f, h * 0.88f); close()
            }
            drawPath(tab, back)
            drawRoundRect(front, topLeft = Offset(0f, h * 0.32f), size = Size(w, h * 0.58f), cornerRadius = CornerRadius(r, r))
        }
        Icon(studyIcon(icon), null, tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.padding(top = size * 0.2f).size(size * 0.36f))
    }
}

/** Short type label shown on file badges (PDF, DOCX, PPTX, TXT, MD, NOTE…). */
fun typeLabel(kind: Kind, ext: String?): String {
    val e = ext?.lowercase().orEmpty()
    return when {
        kind == Kind.NOTE -> "NOTE"
        kind == Kind.PDF -> "PDF"
        kind == Kind.PPTX -> "PPTX"
        e == "doc" -> "DOC"
        kind == Kind.DOCX -> "DOCX"
        e == "markdown" -> "MD"
        e.isNotEmpty() && e.length <= 4 -> e.uppercase()
        kind == Kind.TEXT -> "TXT"
        kind == Kind.IMAGE -> "IMG"
        kind == Kind.AUDIO -> "AUDIO"
        else -> "FILE"
    }
}

/**
 * File type badge from the icon sheet: tinted rounded square, outlined document glyph with a folded corner and a small
 * coloured type label (PDF / DOCX / PPTX / TXT / MD / NOTE) over its lower part. Colours from [kindColor].
 */
@Composable
fun FileBadge(kind: Kind, size: Dp = 36.dp, ext: String? = null) {
    val col = kindColor(kind)
    val label = typeLabel(kind, ext)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // label size follows the badge (icon), not the UI text-size setting
    val labelStyle = remember(size, density) {
        androidx.compose.ui.text.TextStyle(
            fontSize = with(density) { (size * (if (label.length > 3) 0.17f else 0.2f)).toSp() },
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        )
    }
    val paper = D.c.surface
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(col.copy(alpha = 0.12f))) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            val s = this.size.minDimension
            val stroke = (s * 0.055f).coerceAtLeast(1.2f)
            // document outline with a folded top corner
            val l = s * 0.27f; val r = s * 0.73f; val t = s * 0.16f; val b = s * 0.80f
            val fold = s * 0.15f; val rad = s * 0.05f
            val doc = Path().apply {
                moveTo(l + rad, t); lineTo(r - fold, t); lineTo(r, t + fold); lineTo(r, b - rad)
                quadraticTo(r, b, r - rad, b); lineTo(l + rad, b); quadraticTo(l, b, l, b - rad)
                lineTo(l, t + rad); quadraticTo(l, t, l + rad, t); close()
            }
            drawPath(doc, paper)
            drawPath(doc, col, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            val foldPath = Path().apply { moveTo(r - fold, t); lineTo(r - fold, t + fold - rad); quadraticTo(r - fold, t + fold, r - fold + rad, t + fold); lineTo(r, t + fold) }
            drawPath(foldPath, col, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            // two text lines on the page
            val lx0 = l + s * 0.08f
            drawLine(col.copy(alpha = 0.55f), Offset(lx0, t + s * 0.2f), Offset(r - s * 0.1f, t + s * 0.2f), stroke * 0.8f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(col.copy(alpha = 0.55f), Offset(lx0, t + s * 0.29f), Offset(r - s * 0.16f, t + s * 0.29f), stroke * 0.8f, androidx.compose.ui.graphics.StrokeCap.Round)
            // type label
            val tl = measurer.measure(label, labelStyle, maxLines = 1, softWrap = false)
            val lw = (tl.size.width + s * 0.12f).coerceAtMost(s * 0.92f)
            val lh = tl.size.height + s * 0.03f
            val lx = (s - lw) / 2f; val ly = b - lh * 0.72f
            drawRoundRect(col, Offset(lx, ly), Size(lw, lh), CornerRadius(lh * 0.3f, lh * 0.3f))
            drawText(tl, topLeft = Offset(lx + (lw - tl.size.width) / 2f, ly + (lh - tl.size.height) / 2f))
        }
    }
}

fun relTime(t: Long): String =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

@Composable
fun kindLabel(k: Kind): String = when (k) {
    Kind.FOLDER -> stringResource(R.string.kind_folder)
    Kind.NOTE -> stringResource(R.string.kind_note)
    Kind.PDF -> "PDF"
    Kind.PPTX -> stringResource(R.string.kind_slides)
    Kind.DOCX -> stringResource(R.string.kind_word)
    Kind.TEXT -> stringResource(R.string.kind_text)
    Kind.IMAGE -> stringResource(R.string.kind_image)
    Kind.AUDIO -> stringResource(R.string.kind_audio)
    Kind.OTHER -> stringResource(R.string.kind_file)
}

@Composable
fun parentLabel(f: File): String {
    val p = f.parentFile ?: return ""
    return if (Storage.isRoot(p)) stringResource(R.string.files) else p.name
}

@Composable
fun FolderTile(e: Entry, onClick: () -> Unit, onLong: () -> Unit) {
    val c = D.c
    val m = e.meta ?: FolderMeta()
    val count = remember(e.file, Storage.version) { Storage.countItems(e.file) }
    Column(
        Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp))
            .fileItemGestures(e.file, onClick, onLong).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FolderGlyph(m.color, m.icon, 48.dp)
            Spacer(Modifier.weight(1f))
            if (e.file.absolutePath in Storage.pins) Icon(Icons.Rounded.PushPin, null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.items_count, count), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
fun EntryRow(e: Entry, onClick: () -> Unit, onLong: () -> Unit, showParent: Boolean = true) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).fileItemGestures(e.file, onClick, onLong)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.kind == Kind.FOLDER) FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 36.dp) else FileBadge(e.kind, 36.dp, e.ext)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = if (e.kind == Kind.FOLDER) stringResource(R.string.items_count, remember(e.file, Storage.version) { Storage.countItems(e.file) })
            else listOfNotNull(kindLabel(e.kind), if (showParent) parentLabel(e.file) else null, relTime(e.file.lastModified())).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.file.absolutePath in Storage.pins) Icon(Icons.Rounded.PushPin, null, tint = c.muted, modifier = Modifier.size(16.dp).padding(end = 4.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}

/** Grid tile for a file: card with the type badge, pin mark, name and "type · size · time". Long-press-and-move drags it. */
@Composable
fun FileTile(e: Entry, onClick: () -> Unit, onLong: () -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    val size = remember(e.file, Storage.version) { android.text.format.Formatter.formatShortFileSize(ctx, e.file.length()) }
    Column(
        Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp)).fileItemGestures(e.file, onClick, onLong).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            FileBadge(e.kind, 56.dp, e.ext)
            Spacer(Modifier.weight(1f))
            if (e.file.absolutePath in Storage.pins) Icon(Icons.Rounded.PushPin, null, tint = c.muted, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 44.dp))
        Text(kindLabel(e.kind) + " · " + size + " · " + relTime(e.file.lastModified()), style = MaterialTheme.typography.bodySmall, color = c.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Fab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(onClick = onClick, modifier = modifier, shape = CircleShape, containerColor = D.c.accent, contentColor = D.c.onAccent,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)) {
        Icon(Icons.Rounded.Add, stringResource(R.string.new_item))
    }
}

// =====================================================================================
// Actions host: create / import / rename / move / delete … shared by all list screens
// =====================================================================================

class Actions internal constructor() {
    internal var menuFor by mutableStateOf<Entry?>(null)
    internal var renameFor by mutableStateOf<Entry?>(null)
    internal var deleteFor by mutableStateOf<Entry?>(null)
    internal var moveFor by mutableStateOf<Entry?>(null)
    internal var editFolder by mutableStateOf<Pair<File, Entry?>?>(null)  // parent, existing
    internal var createIn by mutableStateOf<File?>(null)                  // shows "New…" sheet
    internal var importIn by mutableStateOf<File?>(null)                  // shows the Files / Folder import choice
    internal var pickTarget by mutableStateOf<String?>(null)              // action awaiting a destination folder
    internal var target: File? = null
    internal var busy by mutableStateOf(false)

    fun menu(e: Entry) { menuFor = e }
    fun create(dir: File) { createIn = dir }
    fun newFolder(parent: File) { editFolder = parent to null }
    /**
     * Run a quick action in [dir], or ask for a folder first when null. Keys: "note", "whiteboard", "folder",
     * "import" (Files / Folder choice), "import_files", "import_folder", "pdf" (images → PDF).
     */
    fun quick(action: String, dir: File?) { if (dir == null) pickTarget = action else { target = dir; pending = action } }
    internal var pending by mutableStateOf<String?>(null)
}

@Composable
fun rememberActions(): Actions {
    val a = remember { Actions() }
    ActionsHost(a)
    return a
}

fun newNote(ctx: android.content.Context, dir: File): File {
    val label = ctx.getString(R.string.untitled_note) + " " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())
    val f = Storage.uniqueFile(dir, label, Storage.NOTE_EXT)
    InkDoc.newNote(Prefs.defaultPaper).save(f)
    Storage.touch()
    return f
}

/** Creates an infinite-canvas whiteboard note in [dir] (saved like [newNote]) and returns its file. */
fun newWhiteboard(ctx: android.content.Context, dir: File): File {
    val label = ctx.getString(R.string.whiteboard) + " " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())
    val f = Storage.uniqueFile(dir, label, Storage.NOTE_EXT)
    InkDoc.newWhiteboard().save(f)
    Storage.touch()
    return f
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsHost(a: Actions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = D.c
    // results open in the pane / window this screen lives in, even if focus moved meanwhile
    val nav = LocalPaneNav.current
    val pickSplit = rememberSplitPicker()

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val dir = a.target ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        Importer.ask(ImportSource.Docs(uris), dir, nav)
    }
    val folderImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        val dir = a.target ?: return@rememberLauncherForActivityResult
        if (tree == null) return@rememberLauncherForActivityResult
        Importer.ask(ImportSource.Tree(tree), dir, nav)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(60)) { uris: List<Uri> ->
        val dir = a.target ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            a.busy = true
            val name = ctx.getString(R.string.images_pdf_name) + " " + SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(Date())
            val out = Storage.uniqueFile(dir, name, "pdf")
            val res = withContext(Dispatchers.IO) { runCatching { PdfTools.imagesToPdf(ctx, uris, out) }.getOrNull() }
            a.busy = false
            Storage.touch()
            if (res != null) nav.open(ctx, res) else toast(ctx, ctx.getString(R.string.error_generic))
        }
    }

    // run a pending quick action once its target is known
    LaunchedEffect(a.pending) {
        val act = a.pending ?: return@LaunchedEffect
        val dir = a.target ?: return@LaunchedEffect
        a.pending = null
        when (act) {
            "note" -> nav.open(ctx, newNote(ctx, dir))
            "whiteboard" -> nav.open(ctx, newWhiteboard(ctx, dir))
            "import" -> a.importIn = dir
            "import_files" -> runCatching { importer.launch(arrayOf("*/*")) }.onFailure { toast(ctx, ctx.getString(R.string.no_app_found)) }
            "import_folder" -> runCatching { folderImporter.launch(null) }.onFailure { toast(ctx, ctx.getString(R.string.no_app_found)) }
            "pdf" -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "folder" -> a.editFolder = dir to null
        }
    }

    a.createIn?.let { dir ->
        ModalBottomSheet(onDismissRequest = { a.createIn = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.new_item), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(8.dp))
                SheetItem(Icons.Rounded.Draw, stringResource(R.string.new_note)) { a.createIn = null; a.quick("note", dir) }
                SheetItem(Icons.Rounded.Dashboard, stringResource(R.string.new_whiteboard)) { a.createIn = null; a.quick("whiteboard", dir) }
                SheetItem(Icons.Rounded.CreateNewFolder, stringResource(R.string.new_folder)) { a.createIn = null; a.editFolder = dir to null }
                SheetItem(Icons.Rounded.FileUpload, stringResource(R.string.import_files)) { a.createIn = null; a.quick("import_files", dir) }
                SheetItem(Icons.Rounded.DriveFolderUpload, stringResource(R.string.ws_import_folder_action)) { a.createIn = null; a.quick("import_folder", dir) }
                SheetItem(Icons.Rounded.PictureAsPdf, stringResource(R.string.images_to_pdf)) { a.createIn = null; a.quick("pdf", dir) }
            }
        }
    }

    a.importIn?.let { dir ->
        ModalBottomSheet(onDismissRequest = { a.importIn = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.ws_import_title), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(8.dp))
                SheetItem(Icons.Rounded.FileUpload, stringResource(R.string.ws_import_files), stringResource(R.string.ws_import_files_desc)) {
                    a.importIn = null; a.quick("import_files", dir)
                }
                SheetItem(Icons.Rounded.DriveFolderUpload, stringResource(R.string.ws_import_folder), stringResource(R.string.ws_import_folder_desc)) {
                    a.importIn = null; a.quick("import_folder", dir)
                }
            }
        }
    }

    a.pickTarget?.let { act ->
        FolderPickerDialog(stringResource(R.string.save_in), null, onDismiss = { a.pickTarget = null }) { dir ->
            a.pickTarget = null
            a.quick(act, dir)
        }
    }

    a.menuFor?.let { e ->
        val pinned = e.file.absolutePath in Storage.pins
        val viewable = screenFor(e.file) != null
        ModalBottomSheet(onDismissRequest = { a.menuFor = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.kind == Kind.FOLDER) FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 40.dp) else FileBadge(e.kind, 40.dp, e.ext)
                    Spacer(Modifier.width(12.dp))
                    Text(e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SheetItem(Icons.Rounded.FolderOpen, stringResource(R.string.open)) { a.menuFor = null; nav.open(ctx, e.file) }
                if (viewable) {
                    SheetItem(Icons.Rounded.VerticalSplit, stringResource(R.string.open_side_by_side)) { a.menuFor = null; pickSplit(e.file) }
                    SheetItem(Icons.Rounded.OpenInBrowser, stringResource(R.string.open_new_window)) { a.menuFor = null; Workspace.openInNewWindow(ctx, e.file) }
                }
                SheetItem(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.rename)) { a.menuFor = null; a.renameFor = e }
                if (e.kind == Kind.FOLDER) SheetItem(Icons.Rounded.Palette, stringResource(R.string.appearance)) { a.menuFor = null; a.editFolder = e.file.parentFile!! to e }
                SheetItem(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.move)) { a.menuFor = null; a.moveFor = e }
                SheetItem(Icons.Rounded.ContentCopy, stringResource(R.string.duplicate)) { a.menuFor = null; Storage.duplicate(e.file) }
                if (e.kind != Kind.FOLDER) {
                    SheetItem(Icons.Rounded.Share, stringResource(R.string.share)) { a.menuFor = null; shareFiles(ctx, listOf(e.file)) }
                    if (e.kind != Kind.NOTE) SheetItem(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open_externally)) { a.menuFor = null; openExternally(ctx, e.file) }
                }
                SheetItem(Icons.Rounded.PushPin, stringResource(if (pinned) R.string.unpin else R.string.pin)) { a.menuFor = null; Storage.togglePin(e.file) }
                SheetItem(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), danger = true) { a.menuFor = null; a.deleteFor = e }
            }
        }
    }

    a.renameFor?.let { e ->
        TextInputDialog(stringResource(R.string.rename), e.name, stringResource(R.string.save), { a.renameFor = null }) { n ->
            a.renameFor = null
            if (Storage.rename(e.file, n) == null) toast(ctx, ctx.getString(R.string.error_generic))
        }
    }

    a.deleteFor?.let { e ->
        ConfirmDialog(stringResource(R.string.delete), stringResource(R.string.delete_confirm, e.name), stringResource(R.string.delete), danger = true,
            onDismiss = { a.deleteFor = null }) { a.deleteFor = null; Storage.delete(e.file) }
    }

    a.moveFor?.let { e ->
        FolderPickerDialog(stringResource(R.string.move_to), exclude = e.file, onDismiss = { a.moveFor = null }) { dir ->
            a.moveFor = null
            if (Storage.move(e.file, dir) == null) toast(ctx, ctx.getString(R.string.error_generic))
        }
    }

    a.editFolder?.let { (parent, existing) ->
        FolderDialog(parent, existing, onDismiss = { a.editFolder = null }) { a.editFolder = null }
    }

    if (a.busy) Dialog { CircularProgressIndicator(color = c.accent) }
}

@Composable
private fun Dialog(content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Box(Modifier.size(96.dp).background(D.c.surface, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) =
    SheetItem(icon, label, null, danger, onClick)

/** Bottom-sheet row with an optional second line. */
@Composable
fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, desc: String?, danger: Boolean = false, onClick: () -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) c.danger else c.muted)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, color = if (danger) c.danger else c.ink, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Folder tree picker (library root + all folders, indented). */
@Composable
fun FolderPickerDialog(title: String, exclude: File?, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val c = D.c
    val folders = remember(Storage.version) {
        Storage.allFolders().filter { exclude == null || !(it.absolutePath == exclude.absolutePath || it.absolutePath.startsWith(exclude.absolutePath + File.separator)) }
            .sortedBy { it.absolutePath.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(folders, key = { it.absolutePath }) { f ->
                    val depth = Storage.crumbs(f).size - 1
                    val m = if (Storage.isRoot(f)) null else Storage.meta(f)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(f) }
                        .padding(start = (8 + depth * 20).dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (m == null) Icon(Icons.Rounded.Home, null, tint = c.accent, modifier = Modifier.size(28.dp)) else FolderGlyph(m.color, m.icon, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(if (m == null) stringResource(R.string.files) else f.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Create or edit a folder: title, description, colour, icon, course sub-folders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderDialog(parent: File, existing: Entry?, onDismiss: () -> Unit, onDone: (File) -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    val m0 = existing?.meta ?: FolderMeta(color = (Storage.countItems(parent) * 5) % FolderPalette.size, icon = if (Storage.isRoot(parent)) "book" else "folder")
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var desc by remember { mutableStateOf(m0.desc) }
    var color by remember { mutableIntStateOf(m0.color) }
    var icon by remember { mutableStateOf(m0.icon) }
    var course by remember { mutableStateOf(existing == null && Storage.isRoot(parent)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.new_folder else R.string.edit_folder)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FolderGlyph(color, icon, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.folder_title)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text(stringResource(R.string.folder_desc)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelMedium, color = c.muted)
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderPalette.forEachIndexed { i, col ->
                        Box(Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (i == color) c.ink else Color.Transparent, CircleShape)
                            .clickable { color = i }.padding(4.dp).clip(CircleShape).background(col))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.icon), style = MaterialTheme.typography.labelMedium, color = c.muted)
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudyIcons.forEach { (key, vec) ->
                        val sel = key == icon
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) folderColor(color).copy(alpha = 0.18f) else c.surfaceAlt)
                            .clickable { icon = key }, contentAlignment = Alignment.Center) {
                            Icon(vec, key, tint = if (sel) folderColor(color) else c.muted, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                if (existing == null) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { course = !course }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(course, { course = it })
                        Text(stringResource(R.string.add_course_folders), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                val meta = FolderMeta(color, icon, desc.trim())
                if (existing == null) {
                    val subs = if (course) listOf(ctx.getString(R.string.lectures) to "lecture", ctx.getString(R.string.seminars) to "seminar", ctx.getString(R.string.labs) to "lab") else emptyList()
                    onDone(Storage.createFolder(parent, name.trim(), meta, subs))
                } else {
                    var f = existing.file
                    if (name.trim() != existing.name) f = Storage.rename(f, name.trim()) ?: f
                    Storage.setMeta(f, meta)
                    onDone(f)
                }
            }) { Text(stringResource(if (existing == null) R.string.create else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
