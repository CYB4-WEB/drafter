package com.daftar.app.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Browse library folders and pick one (the folder being shown). Shows how many AI-readable files the folder holds
 * (recursive, counted off the main thread).
 */
@Composable
internal fun FolderPickerDialog(onDismiss: () -> Unit, start: File = Storage.root, onPick: (File) -> Unit) {
    val c = D.c
    var dir by remember { mutableStateOf(if (start.isDirectory) start else Storage.root) }
    val folders = remember(dir, Storage.version) { Storage.list(dir).filter { it.kind == Kind.FOLDER } }
    val count by produceState<Int?>(null, dir) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { QuizContext.expandFolder(dir).size }.getOrDefault(0) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quiz_pick_folder)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!Storage.isRoot(dir)) IconButton(onClick = { dir = dir.parentFile ?: Storage.root }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink)
                    }
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(if (Storage.isRoot(dir)) stringResource(R.string.files) else dir.name, style = MaterialTheme.typography.titleSmall,
                            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (val n = count) { null -> stringResource(R.string.quiz_counting); 0 -> stringResource(R.string.quiz_folder_empty)
                            else -> pluralStringResource(R.plurals.quiz_n_files, n, n) }, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(folders, key = { it.file.absolutePath }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { dir = e.file }.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FolderGlyph(e.meta?.color ?: 6, e.meta?.icon ?: "folder", 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(e.name, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.muted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(dir) }, enabled = (count ?: 0) > 0) { Text(stringResource(R.string.quiz_use_folder)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
