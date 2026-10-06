package com.daftar.app.slides

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Slide management shared by the rail, the filmstrip and the header menu: which dialog is open and the callback
 * that runs an edit (the screen rewrites the file and reloads the viewer).
 */
@Stable
internal class SlideEditState(val file: File, val deck: Pptx, private val onEdit: (SlideOp) -> Unit) {
    /** Position a new slide is being inserted at (dialog open), or null. */
    var insertAt by mutableStateOf<Int?>(null)
    /** Slide the "+" / menu refers to (layout preselection, duplicate source). */
    var insertRef by mutableStateOf(0)
    var deleteAsk by mutableStateOf<Int?>(null)

    val count get() = deck.slides.size

    fun askInsert(at: Int, ref: Int) { insertRef = ref.coerceIn(0, count - 1); insertAt = at.coerceIn(0, count) }

    fun run(op: SlideOp) = onEdit(op)
}

/** Dialogs of [SlideEditState]; place once in the screen. */
@Composable
internal fun SlideEditDialogs(st: SlideEditState) {
    st.insertAt?.let { at ->
        InsertSlideDialog(st, at, st.insertRef, onDismiss = { st.insertAt = null }) { kind ->
            st.insertAt = null
            st.run(SlideOp.Insert(at, kind))
        }
    }
    st.deleteAsk?.let { i ->
        ConfirmDialog(
            title = stringResource(R.string.slides4_delete_title, i + 1),
            text = stringResource(R.string.slides4_delete_text),
            confirm = stringResource(R.string.slides4_delete_confirm),
            danger = true,
            onDismiss = { st.deleteAsk = null },
        ) {
            st.deleteAsk = null
            st.run(SlideOp.Delete(i))
        }
    }
}

/** Menu entries for slide [i] (long-press menu on a thumbnail and the header menu). */
@Composable
internal fun ColumnScope.SlideMenuItems(st: SlideEditState, i: Int, close: () -> Unit, withInsertBefore: Boolean = true) {
    val c = D.c
    val n = st.count
    val hidden = st.deck.slides.getOrNull(i)?.hidden == true
    @Composable
    fun item(text: String, icon: ImageVector, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
        DropdownMenuItem(
            text = { Text(text, color = if (danger && enabled) c.danger else if (enabled) c.ink else c.muted) },
            leadingIcon = { Icon(icon, null, tint = if (danger && enabled) c.danger else c.muted) },
            enabled = enabled,
            onClick = { close(); onClick() },
        )
    }
    if (withInsertBefore) item(stringResource(R.string.slides4_insert_before), Icons.Rounded.Add) { st.askInsert(i, i) }
    item(stringResource(if (withInsertBefore) R.string.slides4_insert_after else R.string.slides4_insert_after_current), Icons.Rounded.Add) { st.askInsert(i + 1, i) }
    item(stringResource(R.string.slides4_duplicate), Icons.Rounded.ContentCopy) { st.run(SlideOp.Insert(i + 1, NewSlide.Duplicate(i))) }
    item(stringResource(R.string.slides4_move_up), Icons.Rounded.ArrowUpward, enabled = i > 0) { st.run(SlideOp.Move(i, i - 1)) }
    item(stringResource(R.string.slides4_move_down), Icons.Rounded.ArrowDownward, enabled = i < n - 1) { st.run(SlideOp.Move(i, i + 1)) }
    item(
        stringResource(if (hidden) R.string.slides4_unhide else R.string.slides4_hide),
        if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
    ) { st.run(SlideOp.SetHidden(i, !hidden)) }
    item(stringResource(R.string.slides4_delete), Icons.Rounded.DeleteOutline, enabled = n > 1, danger = true) { st.deleteAsk = i }
}

/** Small round "+" placed between thumbnails: inserts a slide at [at]. */
@Composable
internal fun InsertGap(st: SlideEditState, at: Int, modifier: Modifier = Modifier) {
    val c = D.c
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 40.dp, height = 22.dp)
                .clickable(onClickLabel = stringResource(R.string.slides4_insert_here), role = Role.Button) { st.askInsert(at, at - 1) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(18.dp).background(c.surface, CircleShape).border(1.dp, c.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, stringResource(R.string.slides4_insert_here), tint = c.muted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private enum class Kind { BLANK, NOTE, DUPLICATE }

@Composable
private fun InsertSlideDialog(st: SlideEditState, at: Int, ref: Int, onDismiss: () -> Unit, onInsert: (NewSlide) -> Unit) {
    val c = D.c
    var kind by remember { mutableStateOf(Kind.BLANK) }
    var layouts by remember { mutableStateOf<List<LayoutInfo>?>(null) }
    var layout by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    LaunchedEffect(st.file, ref) {
        val (list, cur) = withContext(Dispatchers.IO) {
            runCatching { PptxEdit.layouts(st.file, ref) to PptxEdit.layoutOf(st.file, ref) }.getOrDefault(emptyList<LayoutInfo>() to null)
        }
        layouts = list
        // Like PowerPoint: the new slide uses the layout of the slide before it, except after a title slide.
        val curInfo = list.firstOrNull { it.path == cur }
        layout = (if (curInfo != null && curInfo.type != "title") curInfo else null)?.path
            ?: list.firstOrNull { it.type == "obj" }?.path ?: list.firstOrNull { it.type == "titleOnly" }?.path
            ?: list.firstOrNull { it.type == "blank" }?.path ?: list.firstOrNull()?.path
    }
    val chosen = layouts?.firstOrNull { it.path == layout }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.slides4_insert_title, at + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                KindRow(kind == Kind.BLANK, Icons.Rounded.Dashboard, stringResource(R.string.slides4_kind_blank), stringResource(R.string.slides4_kind_blank_desc)) { kind = Kind.BLANK }
                if (kind == Kind.BLANK) {
                    Column(Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                        Text(stringResource(R.string.slides4_layout), style = MaterialTheme.typography.labelMedium, color = c.muted)
                        Spacer(Modifier.height(6.dp))
                        val l = layouts
                        if (l == null) {
                            CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                l.forEachIndexed { k, info ->
                                    Chip(
                                        info.name.ifBlank { stringResource(R.string.slides4_layout_unnamed, k + 1) },
                                        info.path == layout, { layout = info.path },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            title, { title = it.take(200) }, singleLine = true,
                            label = { Text(stringResource(R.string.slides4_title_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, cursorColor = c.accent),
                        )
                        if (chosen != null && !chosen.hasTitle && title.isNotBlank()) {
                            Text(
                                stringResource(R.string.slides4_title_added), style = MaterialTheme.typography.bodySmall, color = c.muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = c.line)
                KindRow(kind == Kind.NOTE, Icons.Rounded.Draw, stringResource(R.string.slides4_kind_note), stringResource(R.string.slides4_kind_note_desc)) { kind = Kind.NOTE }
                HorizontalDivider(color = c.line)
                KindRow(
                    kind == Kind.DUPLICATE, Icons.Rounded.ContentCopy,
                    stringResource(R.string.slides4_kind_duplicate, ref + 1), stringResource(R.string.slides4_kind_duplicate_desc),
                ) { kind = Kind.DUPLICATE }
            }
        },
        confirmButton = {
            TextButton(
                enabled = kind != Kind.BLANK || layouts != null,
                onClick = {
                    onInsert(
                        when (kind) {
                            Kind.BLANK -> NewSlide.Blank(layout, title.trim())
                            Kind.NOTE -> NewSlide.NotePage
                            Kind.DUPLICATE -> NewSlide.Duplicate(ref)
                        },
                    )
                },
            ) { Text(stringResource(R.string.slides4_insert), color = c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun KindRow(selected: Boolean, icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.muted))
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, tint = if (selected) c.accent else c.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}
