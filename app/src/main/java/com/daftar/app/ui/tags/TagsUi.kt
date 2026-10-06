package com.daftar.app.ui.tags

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Entry
import com.daftar.app.data.Kind
import com.daftar.app.data.SmartFilter
import com.daftar.app.data.Tag
import com.daftar.app.data.Tags
import com.daftar.app.ui.FileBadge
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.theme.D
import java.io.File

// =====================================================================================
// Names & colours
// =====================================================================================

fun labelColor(i: Int): Color = Color(Tags.PALETTE[i.coerceIn(0, Tags.PALETTE.lastIndex)])

private val colorNames = intArrayOf(
    R.string.tags_color_red, R.string.tags_color_orange, R.string.tags_color_yellow, R.string.tags_color_green,
    R.string.tags_color_teal, R.string.tags_color_blue, R.string.tags_color_purple, R.string.tags_color_grey,
)

/** Localized default name of a built-in tag (null for the user's own tags). */
@Composable
fun defaultTagName(key: String?): String? = when (key) {
    Tags.K_REVIEW -> stringResource(R.string.tags_default_review)
    Tags.K_EXAM -> stringResource(R.string.tags_default_exam)
    Tags.K_IMPORTANT -> stringResource(R.string.tags_default_important)
    Tags.K_DONE -> stringResource(R.string.tags_default_done)
    Tags.K_HOMEWORK -> stringResource(R.string.tags_default_homework)
    else -> null
}

@Composable
fun tagName(t: Tag): String = t.name.ifBlank { defaultTagName(t.key) ?: "" }

@Composable
fun defaultFilterName(key: String?): String? = when (key) {
    Tags.F_REVIEW -> stringResource(R.string.tags_filter_review)
    Tags.F_EXAM -> stringResource(R.string.tags_filter_exam)
    Tags.F_RECENT -> stringResource(R.string.tags_filter_recent)
    Tags.F_UNTAGGED -> stringResource(R.string.tags_filter_untagged)
    else -> null
}

@Composable
fun filterName(f: SmartFilter): String = f.name.ifBlank { defaultFilterName(f.key) ?: stringResource(R.string.tags_filter_untitled) }

// =====================================================================================
// Marks on tiles and rows
// =====================================================================================

@Composable
fun TagDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Small colour dots of [file]'s tags (at most [max], then "+n"). Nothing when untagged. */
@Composable
fun TagDots(file: File, max: Int = 4, modifier: Modifier = Modifier) {
    val tags = remember(file.absolutePath, Tags.version) { Tags.tagsOf(file) }
    if (tags.isEmpty()) return
    val names = tags.map { tagName(it) }.joinToString(", ")
    val desc = stringResource(R.string.tags_marks_desc, names)
    Row(modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(max).forEach { TagDot(labelColor(it.color)) }
        if (tags.size > max) Text("+${tags.size - max}", style = MaterialTheme.typography.labelSmall, color = D.c.muted)
    }
}

/** A small read-only tag chip: dot + name on the tag colour at 12 %. */
@Composable
fun MiniTagChip(t: Tag) {
    val col = labelColor(t.color)
    Row(
        Modifier.background(col.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TagDot(col, 6.dp)
        Text(tagName(t), style = MaterialTheme.typography.labelSmall, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 110.dp))
    }
}

/** Tag chips for grid tiles (up to two, then "+n"). Nothing when untagged. */
@Composable
fun TileTags(file: File, modifier: Modifier = Modifier) {
    val tags = remember(file.absolutePath, Tags.version) { Tags.tagsOf(file) }
    if (tags.isEmpty()) return
    Row(modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(2).forEach { MiniTagChip(it) }
        if (tags.size > 2) Text("+${tags.size - 2}", style = MaterialTheme.typography.labelSmall, color = D.c.muted)
    }
}

/** Row marks: colour dots on narrow screens, one or two chips (+ dots for the rest) when there is room. */
@Composable
fun RowTags(file: File, compact: Boolean) {
    val tags = remember(file.absolutePath, Tags.version) { Tags.tagsOf(file) }
    if (tags.isEmpty()) return
    if (compact) { TagDots(file, modifier = Modifier.padding(horizontal = 6.dp)); return }
    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(2).forEach { MiniTagChip(it) }
        tags.drop(2).take(4).forEach { TagDot(labelColor(it.color)) }
    }
}

// =====================================================================================
// Chips (filters)
// =====================================================================================

/** Selectable chip with a colour dot (or none) — long-press for editing. Same shape as the app's Chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DotChip(text: String, selected: Boolean, color: Color?, onClick: () -> Unit, onLong: (() -> Unit)? = null, leading: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val c = D.c
    val tint = color ?: c.accent
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) tint.copy(alpha = 0.14f) else c.surfaceAlt)
            .border(1.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong).height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, Modifier.size(18.dp), tint = if (selected) tint else c.muted)
        else if (color != null) TagDot(color, 10.dp)
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.ink else c.muted, maxLines = 1)
    }
}

// =====================================================================================
// Tag picker (one or several items)
// =====================================================================================

/**
 * Tags of [files]: tick to add / untick to remove (applies at once; with several files a partly used tag shows a dash and a
 * tap adds it to all). Each tag can be renamed, recoloured or deleted; "New tag" creates one and ticks it.
 */
@Composable
fun TagPickerDialog(files: List<File>, onDismiss: () -> Unit) {
    val c = D.c
    val v = Tags.version
    val tags = remember(v) { Tags.tags }
    val usage = remember(v) { Tags.usage() }
    var edit by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.tags_title))
                val sub = if (files.size == 1) (if (files[0].isDirectory) files[0].name else files[0].nameWithoutExtension)
                else pluralStringResource(R.plurals.tags_items_selected, files.size, files.size)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                if (tags.isEmpty()) Text(stringResource(R.string.tags_none_yet), color = c.muted, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(tags, key = { it.id }) { t ->
                        val n = files.count { t.id in Tags.idsOf(it) }
                        val state = when (n) { 0 -> ToggleableState.Off; files.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                        val toggle = { Tags.toggle(files, t.id, state != ToggleableState.On) }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = toggle).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            TriStateCheckbox(state, onClick = toggle, colors = CheckboxDefaults.colors(checkedColor = labelColor(t.color)))
                            TagDot(labelColor(t.color), 12.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(tagName(t), color = c.ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            val used = usage[t.id] ?: 0
                            if (used > 0) Text(used.toString(), color = c.muted, style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = { edit = t }) { Icon(Icons.Rounded.Edit, stringResource(R.string.tags_edit), tint = c.muted, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
                TextButton(onClick = { creating = true }, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tags_new))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tags_done)) } },
    )
    edit?.let { t -> TagEditDialog(t, onDismiss = { edit = null }) { edit = null } }
    if (creating) TagEditDialog(null, onDismiss = { creating = false }) { t -> creating = false; Tags.toggle(files, t.id, true) }
}

/** Create ([existing] = null) or edit a tag: name, 8 label colours, delete (with confirmation). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditDialog(existing: Tag?, onDismiss: () -> Unit, onSaved: (Tag) -> Unit) {
    val c = D.c
    val default = defaultTagName(existing?.key)
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableIntStateOf(existing?.color ?: ((Tags.tags.size * 3) % Tags.PALETTE.size)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val usage = remember(Tags.version) { existing?.let { Tags.usage()[it.id] } ?: 0 }
    val canSave = name.isNotBlank() || default != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.tags_new else R.string.tags_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tags_name)) },
                    placeholder = default?.let { d -> { Text(d) } },
                    leadingIcon = { TagDot(labelColor(color), 14.dp) })
                if (default != null) Text(stringResource(R.string.tags_builtin_hint), style = MaterialTheme.typography.bodySmall, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.tags_color), style = MaterialTheme.typography.labelMedium, color = c.muted)
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tags.PALETTE.indices.forEach { i ->
                        val label = stringResource(colorNames[i])
                        Box(Modifier.size(40.dp).clip(CircleShape).border(2.dp, if (i == color) c.ink else Color.Transparent, CircleShape)
                            .clickable { color = i }.semantics { contentDescription = label }.padding(5.dp).clip(CircleShape).background(labelColor(i)),
                            contentAlignment = Alignment.Center) {
                            if (i == color) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (existing != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = c.danger); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tags_delete), color = c.danger)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                val t = if (existing == null) Tags.createTag(name.ifBlank { default ?: "" }, color)
                else existing.copy(name = name.trim(), color = color).also { Tags.updateTag(it) }
                onSaved(t)
            }) { Text(stringResource(if (existing == null) R.string.tags_create else R.string.tags_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmDelete && existing != null) {
        val n = tagName(existing)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tags_delete_title, n)) },
            text = { Text(if (usage > 0) pluralStringResource(R.plurals.tags_delete_used, usage, usage) else stringResource(R.string.tags_delete_unused)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; Tags.deleteTag(existing.id); onDismiss() }) {
                    Text(stringResource(R.string.tags_delete), color = c.danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// =====================================================================================
// Multi-select (Library)
// =====================================================================================

/** Pick several items of a folder (or a filter result), then tag them together. */
@Composable
fun BulkTagDialog(entries: List<Entry>, onDismiss: () -> Unit) {
    val c = D.c
    var sel by remember { mutableStateOf(setOf<String>()) }
    var picking by remember { mutableStateOf<List<File>?>(null) }
    val all = sel.size == entries.size && entries.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.tags_select_items))
                Text(pluralStringResource(R.plurals.tags_items_selected, sel.size, sel.size), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { sel = if (all) emptySet() else entries.map { it.file.absolutePath }.toSet() }, verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(if (all) ToggleableState.On else if (sel.isEmpty()) ToggleableState.Off else ToggleableState.Indeterminate,
                        onClick = { sel = if (all) emptySet() else entries.map { it.file.absolutePath }.toSet() })
                    Text(stringResource(R.string.tags_select_all), color = c.ink, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider(color = c.line)
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(entries, key = { it.file.absolutePath }) { e ->
                        val p = e.file.absolutePath
                        val on = p in sel
                        val flip = { sel = if (on) sel - p else sel + p }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = flip).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, { flip() })
                            if (e.kind == Kind.FOLDER) FolderGlyph(e.meta?.color ?: 5, e.meta?.icon ?: "folder", 28.dp) else FileBadge(e.kind, 28.dp, e.ext)
                            Spacer(Modifier.width(10.dp))
                            Text(e.name, color = c.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false))
                            TagDots(e.file, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sel.isNotEmpty(), onClick = { picking = entries.filter { it.file.absolutePath in sel }.map { it.file } }) {
                Text(stringResource(R.string.tags_tag_selected))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tags_done)) } },
    )
    picking?.let { files -> TagPickerDialog(files) { picking = null } }
}
