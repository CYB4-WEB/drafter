package com.daftar.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.data.Trash
import com.daftar.app.data.TrashItem
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Original folder of a bin entry as shown to the user ("Files" for the library root). */
@Composable
private fun origFolderLabel(it: TrashItem): String {
    val p = File(it.orig).parentFile ?: return stringResource(R.string.files)
    return if (Storage.isRoot(p) || !p.absolutePath.startsWith(Storage.root.absolutePath)) stringResource(R.string.files) else p.name
}

/** "Deleted 3 days ago · 27 days left". */
@Composable
fun trashAge(it: TrashItem): String {
    val ago = Trash.daysAgo(it)
    val left = Trash.daysLeft(it)
    val a = if (ago == 0) stringResource(R.string.files_deleted_today) else pluralStringResource(R.plurals.files_deleted_days, ago, ago)
    return a + " · " + pluralStringResource(R.plurals.files_days_left, left, left)
}

/** Recycle bin: deleted items kept 30 days, restore / delete forever / empty. */
@Composable
fun TrashScreen() {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    LaunchedEffect(Trash.version) { items = withContext(Dispatchers.IO) { Trash.items() } }
    var confirmEmpty by remember { mutableStateOf(false) }
    var forever by remember { mutableStateOf<TrashItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    val compact = LocalWidthClass.current == WidthClass.Compact

    fun restore(it: TrashItem) {
        scope.launch {
            busy = true
            val f = withContext(Dispatchers.IO) { Trash.restore(it) }
            busy = false
            if (f == null) toast(ctx, ctx.getString(R.string.files_restore_failed))
            else toast(ctx, ctx.getString(R.string.files_restored, f.parentFile?.let { p -> if (Storage.isRoot(p)) ctx.getString(R.string.files) else p.name } ?: ""))
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ViewerTopBar(stringResource(R.string.files_trash), onBack = { pane.back() }) {
            if (!items.isNullOrEmpty()) {
                if (compact) IconButton(onClick = { confirmEmpty = true }) {
                    Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.files_trash_empty_action), tint = c.danger)
                } else TextButton(onClick = { confirmEmpty = true }) {
                    Icon(Icons.Rounded.DeleteSweep, null, tint = c.danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.files_trash_empty_action), color = c.danger)
                }
            }
        }
        val list = items
        Box(Modifier.fillMaxSize()) {
            when {
                list == null -> CircularProgressIndicator(color = c.accent, modifier = Modifier.align(Alignment.Center))
                list.isEmpty() -> EmptyState(Icons.Rounded.DeleteOutline, stringResource(R.string.files_trash_none), Modifier.padding(top = 48.dp))
                else -> LazyColumn(Modifier.fillMaxSize().widthIn(max = 860.dp).align(Alignment.TopCenter),
                    contentPadding = PaddingValues(horizontal = if (compact) D.gutter else D.gutterWide, vertical = 12.dp)) {
                    item {
                        Text(stringResource(R.string.files_trash_desc), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                    }
                    item {
                        Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) {
                            list.forEach { t -> TrashRow(t, onRestore = { restore(t) }, onDelete = { forever = t }) }
                        }
                    }
                }
            }
            if (busy) CircularProgressIndicator(color = c.accent, modifier = Modifier.align(Alignment.Center))
        }
    }

    if (confirmEmpty) ConfirmDialog(stringResource(R.string.files_trash_empty_title), stringResource(R.string.files_trash_empty_text),
        stringResource(R.string.files_trash_empty_action), danger = true, onDismiss = { confirmEmpty = false }) {
        confirmEmpty = false
        scope.launch { busy = true; withContext(Dispatchers.IO) { Trash.empty() }; busy = false }
    }
    forever?.let { t ->
        ConfirmDialog(stringResource(R.string.files_delete_forever), stringResource(R.string.files_delete_forever_text, t.name),
            stringResource(R.string.files_delete_forever), danger = true, onDismiss = { forever = null }) {
            forever = null
            scope.launch { busy = true; withContext(Dispatchers.IO) { Trash.deleteForever(t) }; busy = false }
        }
    }
}

@Composable
private fun TrashRow(t: TrashItem, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = D.c
    val kind = t.kindEnum
    val file = Trash.fileOf(t)
    val meta = remember(t.id) { if (kind == Kind.FOLDER) Storage.meta(file) else null }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind == Kind.FOLDER) FolderGlyph(meta?.color ?: 6, meta?.icon ?: "folder", 36.dp) else FileBadge(kind, 36.dp, file.extension)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (kind == Kind.FOLDER) t.name else file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kindLabel(kind) + " · " + origFolderLabel(t), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trashAge(t), style = MaterialTheme.typography.bodySmall,
                color = if (Trash.daysLeft(t) <= 3) c.danger else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRestore) { Icon(Icons.Rounded.Restore, stringResource(R.string.files_restore), tint = c.accent) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.muted) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.files_restore)) }, { menu = false; onRestore() },
                    leadingIcon = { Icon(Icons.Rounded.Restore, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(R.string.files_delete_forever), color = c.danger) }, { menu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, null, tint = c.danger) })
            }
        }
    }
}

/** "Recycle bin" row for Settings › Storage: item count and size; opens [Screen.Trash]. */
@Composable
fun TrashSettingsRow() {
    val ctx = LocalContext.current
    val c = D.c
    var info by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(Trash.version) { info = withContext(Dispatchers.IO) { Trash.items().size to Trash.bytes() } }
    Row(Modifier.fillMaxWidth().clickable { pane.push(Screen.Trash) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.DeleteOutline, null, tint = c.muted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.files_trash), color = c.ink)
            val i = info
            Text(when {
                i == null -> "…"
                i.first == 0 -> stringResource(R.string.files_trash_settings_empty)
                else -> stringResource(R.string.files_trash_settings_desc, i.first, Formatter.formatShortFileSize(ctx, i.second))
            }, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
    }
}
