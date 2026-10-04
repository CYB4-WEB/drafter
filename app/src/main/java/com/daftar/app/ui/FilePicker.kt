package com.daftar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.theme.D
import java.io.File

/**
 * Pick one or more files from the library. Folders are browsed in place; only files for which [accept] is true are listed.
 * [onPick] receives the files in the order they were selected.
 */
@Composable
fun LibraryFilePickerDialog(
    title: String,
    accept: (File) -> Boolean,
    multiple: Boolean,
    onDismiss: () -> Unit,
    start: File = Storage.root,
    onPick: (List<File>) -> Unit,
) {
    val c = D.c
    var dir by remember { mutableStateOf(if (start.isDirectory) start else Storage.root) }
    val chosen = remember { mutableStateListOf<File>() }
    val entries = remember(dir, Storage.version) {
        Storage.list(dir).filter { it.kind == Kind.FOLDER || accept(it.file) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!Storage.isRoot(dir)) IconButton(onClick = { dir = dir.parentFile ?: Storage.root }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink)
                    }
                    Text(if (Storage.isRoot(dir)) stringResource(R.string.files) else dir.name, style = MaterialTheme.typography.titleSmall,
                        color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                }
                if (entries.isEmpty()) Text(stringResource(R.string.empty_folder), color = c.muted, modifier = Modifier.padding(16.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(entries, key = { it.file.absolutePath }) { e ->
                        val isFolder = e.kind == Kind.FOLDER
                        val idx = chosen.indexOf(e.file)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (idx >= 0) c.accent.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable {
                                    when {
                                        isFolder -> dir = e.file
                                        !multiple -> onPick(listOf(e.file))
                                        idx >= 0 -> chosen.removeAt(idx)
                                        else -> chosen.add(e.file)
                                    }
                                }.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isFolder) FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 32.dp) else FileBadge(e.kind, 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(e.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            when {
                                isFolder -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
                                multiple && idx >= 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}", color = c.accent, style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Rounded.CheckCircle, null, tint = c.accent)
                                }
                                multiple -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = c.line)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) TextButton(onClick = { if (chosen.isNotEmpty()) onPick(chosen.toList()) }, enabled = chosen.isNotEmpty()) {
                Text(stringResource(R.string.select_n, chosen.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
