package com.daftar.app.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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

@Composable
fun FileBadge(kind: Kind, size: Dp = 36.dp) {
    val col = kindColor(kind)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(col.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
        Icon(kindIcon(kind), null, tint = col, modifier = Modifier.size(size * 0.56f))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderTile(e: Entry, onClick: () -> Unit, onLong: () -> Unit) {
    val c = D.c
    val m = e.meta ?: FolderMeta()
    val count = remember(e.file, Storage.version) { Storage.countItems(e.file) }
    Column(
        Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(16.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(e: Entry, onClick: () -> Unit, onLong: () -> Unit, showParent: Boolean = true) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.kind == Kind.FOLDER) FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 36.dp) else FileBadge(e.kind)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileTile(e: Entry, onClick: () -> Unit, onLong: () -> Unit) {
    val c = D.c
    Column(
        Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).padding(16.dp),
    ) {
        Row { FileBadge(e.kind, 44.dp); Spacer(Modifier.weight(1f)); if (e.file.absolutePath in Storage.pins) Icon(Icons.Rounded.PushPin, null, tint = c.muted, modifier = Modifier.size(16.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(kindLabel(e.kind) + " · " + relTime(e.file.lastModified()), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
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
    internal var pickTarget by mutableStateOf<String?>(null)              // action awaiting a destination folder
    internal var target: File? = null
    internal var busy by mutableStateOf(false)

    fun menu(e: Entry) { menuFor = e }
    fun create(dir: File) { createIn = dir }
    fun newFolder(parent: File) { editFolder = parent to null }
    /** Run [action] (note/import/pdf) in [dir], or ask for a folder first when null. */
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsHost(a: Actions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = D.c

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val dir = a.target ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            a.busy = true
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { Storage.import(ctx, it, dir) } }
            a.busy = false
            if (files.size == 1) pane.open(ctx, files[0]) else toast(ctx, ctx.getString(R.string.imported_n, files.size))
        }
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
            if (res != null) pane.open(ctx, res) else toast(ctx, ctx.getString(R.string.error_generic))
        }
    }

    // run a pending quick action once its target is known
    LaunchedEffect(a.pending) {
        val act = a.pending ?: return@LaunchedEffect
        val dir = a.target ?: return@LaunchedEffect
        a.pending = null
        when (act) {
            "note" -> pane.open(ctx, newNote(ctx, dir))
            "import" -> importer.launch(arrayOf("*/*"))
            "pdf" -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "folder" -> a.editFolder = dir to null
        }
    }

    a.createIn?.let { dir ->
        ModalBottomSheet(onDismissRequest = { a.createIn = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.new_item), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(8.dp))
                SheetItem(Icons.Rounded.Draw, stringResource(R.string.new_note)) { a.createIn = null; a.quick("note", dir) }
                SheetItem(Icons.Rounded.CreateNewFolder, stringResource(R.string.new_folder)) { a.createIn = null; a.editFolder = dir to null }
                SheetItem(Icons.Rounded.FileUpload, stringResource(R.string.import_files)) { a.createIn = null; a.quick("import", dir) }
                SheetItem(Icons.Rounded.PictureAsPdf, stringResource(R.string.images_to_pdf)) { a.createIn = null; a.quick("pdf", dir) }
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
        ModalBottomSheet(onDismissRequest = { a.menuFor = null }, containerColor = c.surface) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (e.kind == Kind.FOLDER) FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 40.dp) else FileBadge(e.kind, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(e.name, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SheetItem(Icons.Rounded.FolderOpen, stringResource(R.string.open)) { a.menuFor = null; pane.open(ctx, e.file) }
                SheetItem(Icons.Rounded.DriveFileRenameOutline, stringResource(R.string.rename)) { a.menuFor = null; a.renameFor = e }
                if (e.kind == Kind.FOLDER) SheetItem(Icons.Rounded.Palette, stringResource(R.string.appearance)) { a.menuFor = null; a.editFolder = e.file.parentFile!! to e }
                SheetItem(Icons.AutoMirrored.Rounded.DriveFileMove, stringResource(R.string.move)) { a.menuFor = null; a.moveFor = e }
                SheetItem(Icons.Rounded.ContentCopy, stringResource(R.string.duplicate)) { a.menuFor = null; Storage.duplicate(e.file) }
                if (e.kind != Kind.FOLDER) {
                    SheetItem(Icons.Rounded.Share, stringResource(R.string.share)) { a.menuFor = null; shareFiles(ctx, listOf(e.file)) }
                    if (e.kind != Kind.NOTE) SheetItem(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.open_externally)) { a.menuFor = null; openExternally(ctx, e.file) }
                }
                SheetItem(if (pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, stringResource(if (pinned) R.string.unpin else R.string.pin)) { a.menuFor = null; Storage.togglePin(e.file) }
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
fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) c.danger else c.muted)
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) c.danger else c.ink, style = MaterialTheme.typography.bodyLarge)
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
