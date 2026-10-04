package com.daftar.app.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.ink.InkPage
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.kindColor
import com.daftar.app.ui.theme.D
import java.io.File

/**
 * Organize pages: multi-select pages, then rotate, move earlier/later, insert a blank page or delete — previewed live
 * (rotation included), applied in one rewrite on "Apply". Deleting asks for confirmation.
 */
@Composable
internal fun OrganizePagesDialog(
    session: PdfSession,
    ink: List<InkPage>,
    current: Int,
    onDismiss: () -> Unit,
    onApply: (List<PageSpec>) -> Unit,
) {
    val c = D.c
    val n = session.pageCount
    val plan = remember { mutableStateListOf<PageSpec>().apply { addAll(PdfPages.identity(n)) } }
    val selected = remember { mutableStateListOf<Long>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val changed = !PdfPages.isIdentity(plan, n)

    fun displayed(s: PageSpec): Pair<Float, Float> =
        if (s.isBlank) s.w to s.h
        else session.pageSize(s.src).let { (w, h) -> if (PdfPages.norm(s.rotate) % 180 == 90) h to w else w to h }

    fun rotateSel(delta: Int) {
        for (i in plan.indices) {
            val s = plan[i]
            if (s.key !in selected) continue
            plan[i] = if (s.isBlank) { if (PdfPages.norm(delta) % 180 == 90) s.copy(w = s.h, h = s.w) else s } else s.copy(rotate = PdfPages.norm(s.rotate + delta))
        }
    }

    fun moveSel(delta: Int) {
        val idx = plan.indices.filter { plan[it].key in selected }
        val order = if (delta < 0) idx else idx.reversed()
        for (i in order) {
            val j = i + delta
            if (j !in plan.indices || plan[j].key in selected) continue
            val t = plan[j]; plan[j] = plan[i]; plan[i] = t
        }
    }

    fun insertBlank() {
        val at = plan.indices.lastOrNull { plan[it].key in selected }
            ?: plan.indexOfFirst { it.src == current }.takeIf { it >= 0 } ?: plan.lastIndex
        val (w, h) = displayed(plan[at])
        val blank = PageSpec(-1, 0, w, h)
        plan.add(at + 1, blank)
        selected.clear(); selected.add(blank.key)
    }

    fun deleteSel() {
        plan.removeAll { it.key in selected }
        selected.clear()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Row(
                Modifier.fillMaxWidth().background(c.surface).height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = c.ink) }
                Text(
                    stringResource(R.string.pdf_organize_pages), style = MaterialTheme.typography.titleMedium, color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                TextButton(enabled = changed && plan.isNotEmpty(), onClick = {
                    if (n - plan.count { !it.isBlank } > 0) confirmDelete = true else onApply(plan.toList())
                }) { Text(stringResource(R.string.pdf_apply)) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            val has = selected.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().background(c.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolAction(Icons.Rounded.SelectAll, stringResource(if (selected.size == plan.size) R.string.pdf_select_none else R.string.pdf_select_all), true) {
                    if (selected.size == plan.size) selected.clear() else { selected.clear(); selected.addAll(plan.map { it.key }) }
                }
                ToolAction(Icons.Rounded.RotateLeft, stringResource(R.string.pdf_rotate_left), has) { rotateSel(-90) }
                ToolAction(Icons.Rounded.RotateRight, stringResource(R.string.pdf_rotate_right), has) { rotateSel(90) }
                ToolAction(Icons.Rounded.ArrowUpward, stringResource(R.string.pdf_move_earlier), has) { moveSel(-1) }
                ToolAction(Icons.Rounded.ArrowDownward, stringResource(R.string.pdf_move_later), has) { moveSel(1) }
                ToolAction(Icons.AutoMirrored.Rounded.NoteAdd, stringResource(R.string.pdf_insert_blank_short), plan.isNotEmpty()) { insertBlank() }
                ToolAction(Icons.Rounded.DeleteOutline, stringResource(R.string.delete), has && selected.size < plan.size, danger = true) { deleteSel() }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Text(
                if (has) pluralStringResource(R.plurals.pdf_selected_pages, selected.size, selected.size) else stringResource(R.string.pdf_organize_hint),
                style = MaterialTheme.typography.labelMedium, color = c.muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(plan, key = { _, s -> s.key }) { i, s ->
                    val sel = s.key in selected
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable { if (sel) selected.remove(s.key) else selected.add(s.key) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            if (s.isBlank) {
                                val shape = RoundedCornerShape(6.dp)
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio((s.w / s.h).coerceIn(0.2f, 5f)).background(Color.White, shape)
                                        .border(if (sel) 2.dp else 1.dp, if (sel) c.accent else c.line, shape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(stringResource(R.string.pdf_blank_page), style = MaterialTheme.typography.labelMedium, color = Color(0xFF9CA3AF))
                                }
                            } else {
                                PageThumb(session, s.src, s.rotate, ink.getOrNull(s.src), 0, sel)
                            }
                            if (sel) Icon(
                                Icons.Rounded.CheckCircle, null, tint = c.accent,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(Color.White, CircleShape),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val moved = !s.isBlank && (s.src != i || PdfPages.norm(s.rotate) != 0)
                        Text(
                            if (moved) stringResource(R.string.pdf_page_moved, i + 1, s.src + 1) else (i + 1).toString(),
                            style = MaterialTheme.typography.labelMedium, color = if (sel) c.accent else c.muted, maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val removed = n - plan.count { !it.isBlank }
        ConfirmDialog(
            title = stringResource(R.string.pdf_delete_pages_title),
            text = pluralStringResource(R.plurals.pdf_delete_pages_text, removed, removed),
            confirm = stringResource(R.string.delete),
            danger = true,
            onDismiss = { confirmDelete = false },
        ) { confirmDelete = false; onApply(plan.toList()) }
    }
}

@Composable
private fun ToolAction(icon: ImageVector, label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = D.c
    val tint = when { !enabled -> c.line; danger -> c.danger; else -> c.ink }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) (if (danger) c.danger else c.ink) else c.muted, maxLines = 1)
    }
}

/** Merge: order the files (this PDF + the picked ones), name the result. */
@Composable
internal fun MergeDialog(files: List<File>, defaultName: String, onDismiss: () -> Unit, onMerge: (List<File>, String) -> Unit) {
    val c = D.c
    val order = remember { mutableStateListOf<File>().apply { addAll(files) } }
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_merge)) },
        text = {
            Column {
                Text(stringResource(R.string.pdf_merge_order), style = MaterialTheme.typography.labelMedium, color = c.muted)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(order, key = { _, f -> f.absolutePath }) { i, f ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.width(24.dp))
                            Icon(Icons.Rounded.PictureAsPdf, null, tint = kindColor(Kind.PDF), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(f.nameWithoutExtension, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { order.add(i - 1, order.removeAt(i)) }, enabled = i > 0) {
                                Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.pdf_move_up), tint = if (i > 0) c.ink else c.line)
                            }
                            IconButton(onClick = { order.add(i + 1, order.removeAt(i)) }, enabled = i < order.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.pdf_move_down), tint = if (i < order.lastIndex) c.ink else c.line)
                            }
                            IconButton(onClick = { order.removeAt(i) }, enabled = order.size > 2) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.pdf_remove), tint = if (order.size > 2) c.muted else c.line)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pdf_file_name)) },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = order.size >= 2 && name.isNotBlank(), onClick = { onMerge(order.toList(), name.trim()) }) {
                Text(stringResource(R.string.pdf_merge_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** "Saved as …" with Open. */
@Composable
internal fun SavedDialog(file: File, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_saved_title)) },
        text = { Text(stringResource(R.string.saved_to, file.name)) },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
