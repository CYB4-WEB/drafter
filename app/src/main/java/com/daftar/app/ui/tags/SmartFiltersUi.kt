package com.daftar.app.ui.tags

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Sell
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
import com.daftar.app.data.Entry
import com.daftar.app.data.Kind
import com.daftar.app.data.SmartFilter
import com.daftar.app.data.Storage
import com.daftar.app.data.Tags
import com.daftar.app.ui.FolderPickerDialog
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.card
import com.daftar.app.ui.kindLabel
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================================
// Search helpers (Search screen query handling)
// =====================================================================================

object TagSearch {
    /** A Search screen opened with "smart:<id>" starts with that smart filter selected (Home → Smart filters). */
    const val SMART_PREFIX = "smart:"

    fun smartQuery(id: String) = SMART_PREFIX + id
    fun initialText(query: String) = if (query.startsWith(SMART_PREFIX)) "" else query
    fun initialSmart(query: String): String? = if (query.startsWith(SMART_PREFIX)) query.removePrefix(SMART_PREFIX).ifBlank { null } else null

    /**
     * Search results for the Search screen: plain name search, `#tag` tokens in [q], the tag chips [tagSel] (all required)
     * and the smart filter [smartId]. With none of the tag features this is exactly [Storage.search]. Blocking.
     */
    fun run(ctx: Context, q: String, tagSel: Set<String>, smartId: String?): List<Entry> {
        val (text, groups) = Tags.parseQuery(ctx, q)
        val smart = smartId?.let(Tags::filter)
        if (groups.isEmpty() && tagSel.isEmpty() && smart == null) return Storage.search(text)
        return Tags.search(text, groups, tagSel, smart)
    }

    /** True when the Search screen has something to search for (text, tag chips or a smart filter). */
    fun active(q: String, tagSel: Set<String>, smartId: String?) = q.isNotBlank() || tagSel.isNotEmpty() || smartId != null
}

/** Results of smart filter [id] (newest first), re-evaluated when files or tags change; null when [id] is null. */
@Composable
fun rememberSmartResults(id: String?): List<Entry>? {
    val v = Storage.version
    val tv = Tags.version
    var res by remember(id) { mutableStateOf<List<Entry>?>(if (id == null) null else emptyList()) }
    LaunchedEffect(id, v, tv) {
        if (id == null) { res = null; return@LaunchedEffect }
        val f = Tags.filter(id)
        res = if (f == null) emptyList() else withContext(Dispatchers.IO) { Tags.evaluate(f) }
    }
    return res
}

/** Main colour of a filter: its first tag's label colour (null for filters without tags). */
private fun filterColor(f: SmartFilter) = f.tags.firstNotNullOfOrNull { Tags.tag(it) }?.let { labelColor(it.color) }

// =====================================================================================
// Chips (Library root, Search)
// =====================================================================================

/**
 * One scrollable row: "All" + every smart filter (tap = select / unselect, long-press = edit) + "New filter".
 * [allLabel] null hides the "All" chip.
 */
@Composable
fun SmartFilterChips(selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier, allLabel: String? = stringResource(R.string.tags_all_files)) {
    val filters = remember(Tags.version) { Tags.filters }
    var edit by remember { mutableStateOf<SmartFilter?>(null) }
    var isNew by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (allLabel != null) DotChip(allLabel, selected == null, null, { onSelect(null) })
        filters.forEach { f ->
            DotChip(filterName(f), selected == f.id, filterColor(f), { onSelect(if (selected == f.id) null else f.id) },
                onLong = { isNew = false; edit = f }, leading = if (filterColor(f) == null) Icons.Rounded.FilterAlt else null)
        }
        DotChip(stringResource(R.string.tags_filter_new), false, null, { isNew = true; edit = Tags.newFilter() }, leading = Icons.Rounded.Add)
    }
    edit?.let { f ->
        SmartFilterEditor(f, isNew, onDismiss = { edit = null }, onDeleted = { if (selected == f.id) onSelect(null) }) { saved ->
            edit = null
            if (isNew) onSelect(saved.id)
        }
    }
}

/** Search screen: smart filter chips, then tag chips (multi-select, all required). */
@Composable
fun SearchTagFilters(tagSel: Set<String>, onTags: (Set<String>) -> Unit, smart: String?, onSmart: (String?) -> Unit, modifier: Modifier = Modifier) {
    val tags = remember(Tags.version) { Tags.tags }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmartFilterChips(smart, onSmart, allLabel = null)
        if (tags.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Sell, stringResource(R.string.tags_title), tint = D.c.muted, modifier = Modifier.size(18.dp))
            tags.forEach { t ->
                val on = t.id in tagSel
                DotChip(tagName(t), on, labelColor(t.color), { onTags(if (on) tagSel - t.id else tagSel + t.id) })
            }
        }
    }
}

/** Notes screen: tag chips of the tags used by [notes] (single choice; tap again to clear). Nothing when none is used. */
@Composable
fun NotesTagChips(notes: List<File>, selected: String?, onSelect: (String?) -> Unit) {
    val used = remember(notes, Tags.version) {
        val ids = HashSet<String>()
        notes.forEach { ids.addAll(Tags.idsOf(it)) }
        Tags.tags.filter { it.id in ids }
    }
    if (used.isEmpty()) return
    used.forEach { t ->
        DotChip(tagName(t), selected == t.id, labelColor(t.color), { onSelect(if (selected == t.id) null else t.id) })
    }
}

// =====================================================================================
// Home: "Smart filters" row
// =====================================================================================

/** Section with one card per smart filter and its live count; tap opens Search with the filter, long-press edits. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmartFiltersSection() {
    val c = D.c
    val v = Storage.version
    val tv = Tags.version
    val filters = remember(tv) { Tags.filters }
    var counts by remember { mutableStateOf<Map<String, Int>?>(null) }
    LaunchedEffect(v, tv) { counts = withContext(Dispatchers.IO) { Tags.counts(filters) } }
    var edit by remember { mutableStateOf<SmartFilter?>(null) }
    var isNew by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.tags_smart_filters)) {
        TextButton(onClick = { isNew = true; edit = Tags.newFilter() }) { Text(stringResource(R.string.tags_filter_new)) }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        filters.forEach { f ->
            val col = filterColor(f) ?: c.accent
            Column(
                Modifier.width(156.dp).card(c).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { Nav.tab(Screen.Search(TagSearch.smartQuery(f.id))) }, onLongClick = { isNew = false; edit = f })
                    .padding(14.dp),
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(col.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                    Icon(if (f.hasTagRule) Icons.Rounded.Sell else Icons.Rounded.FilterAlt, null, tint = col, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(filterName(f), style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val n = counts?.get(f.id) ?: 0
                Text(if (counts == null) "…" else pluralStringResource(R.plurals.tags_items, n, n), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        if (filters.isEmpty()) {
            Column(Modifier.width(156.dp).card(c).clip(RoundedCornerShape(16.dp)).clickable { Tags.restoreBuiltins() }.padding(14.dp)) {
                Icon(Icons.Rounded.Restore, null, tint = c.muted)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.tags_filter_restore), style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 2)
            }
        }
    }
    edit?.let { f ->
        SmartFilterEditor(f, isNew, onDismiss = { edit = null }) { edit = null }
    }
}

// =====================================================================================
// Filter editor
// =====================================================================================

private val editableKinds = listOf(Kind.FOLDER, Kind.NOTE, Kind.PDF, Kind.DOCX, Kind.PPTX, Kind.TEXT, Kind.IMAGE, Kind.AUDIO, Kind.ONENOTE)

/**
 * Create / edit a smart filter: name, tags (any / all), type, folder, modified date, untagged only, and how the tag rule
 * joins the other rules (AND / OR). Shows the live number of matches. Built-ins can be edited and deleted too
 * ("Restore built-in filters" brings them back).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SmartFilterEditor(initial: SmartFilter, isNew: Boolean, onDismiss: () -> Unit, onDeleted: () -> Unit = {}, onSaved: (SmartFilter) -> Unit) {
    val c = D.c
    var d by remember(initial.id) { mutableStateOf(initial) }
    var pickFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tags = remember(Tags.version) { Tags.tags }
    val default = defaultFilterName(initial.key)
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(d, Storage.version, Tags.version) {
        count = null
        count = withContext(Dispatchers.IO) { Tags.counts(listOf(d))[d.id] ?: 0 }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.tags_filter_new else R.string.tags_filter_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(d.name, { d = d.copy(name = it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tags_name)) }, placeholder = default?.let { s -> { Text(s) } })

                Label(stringResource(R.string.tags_filter_tags))
                if (tags.isEmpty()) Text(stringResource(R.string.tags_none_yet), color = c.muted, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { t ->
                        val on = t.id in d.tags
                        DotChip(tagName(t), on, labelColor(t.color), { d = d.copy(tags = if (on) d.tags - t.id else d.tags + t.id) })
                    }
                }
                if (d.tags.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DotChip(stringResource(R.string.tags_filter_any_tag), !d.tagsAll, null, { d = d.copy(tagsAll = false) })
                    DotChip(stringResource(R.string.tags_filter_all_tags), d.tagsAll, null, { d = d.copy(tagsAll = true) })
                }

                Label(stringResource(R.string.tags_filter_type))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editableKinds.forEach { k ->
                        val on = k.name in d.kinds
                        DotChip(kindLabel(k), on, null, { d = d.copy(kinds = if (on) d.kinds - k.name else d.kinds + k.name) })
                    }
                }

                Label(stringResource(R.string.tags_filter_folder))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fo = d.folder
                    Icon(Icons.Rounded.Folder, null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when { fo == null -> stringResource(R.string.tags_filter_anywhere); fo.isEmpty() -> stringResource(R.string.files); else -> fo.replace("/", " › ") },
                        color = c.ink, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickFolder = true }) { Text(stringResource(R.string.tags_filter_choose)) }
                    if (fo != null) IconButton(onClick = { d = d.copy(folder = null) }) { Icon(Icons.Rounded.Close, stringResource(R.string.tags_filter_anywhere), tint = c.muted) }
                }

                Label(stringResource(R.string.tags_filter_modified))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Tags.DATE_ANY to R.string.tags_date_any, Tags.DATE_TODAY to R.string.tags_date_today,
                        Tags.DATE_WEEK to R.string.tags_date_week, Tags.DATE_OLDER to R.string.tags_date_older).forEach { (k, l) ->
                        DotChip(stringResource(l), d.date == k, null, { d = d.copy(date = k) })
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { d = d.copy(untagged = !d.untagged) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(d.untagged, { d = d.copy(untagged = it) })
                    Text(stringResource(R.string.tags_filter_untagged_only), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                }

                if (d.hasTagRule && d.hasOtherRule) {
                    Label(stringResource(R.string.tags_filter_combine))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DotChip(stringResource(R.string.tags_filter_and), !d.anyGroup, null, { d = d.copy(anyGroup = false) })
                        DotChip(stringResource(R.string.tags_filter_or), d.anyGroup, null, { d = d.copy(anyGroup = true) })
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(count?.let { pluralStringResource(R.plurals.tags_matches, it, it) } ?: "…", color = c.accent, style = MaterialTheme.typography.labelLarge)
                if (!d.hasTagRule && !d.hasOtherRule) Text(stringResource(R.string.tags_filter_everything), color = c.muted, style = MaterialTheme.typography.bodySmall)

                if (!isNew) Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = c.danger); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tags_filter_delete), color = c.danger)
                    }
                    if (Tags.filters.count { it.key != null } < 4) TextButton(onClick = { Tags.restoreBuiltins() }) { Text(stringResource(R.string.tags_filter_restore)) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = d.name.isNotBlank() || default != null, onClick = { val s = d.copy(name = d.name.trim()); Tags.saveFilter(s); onSaved(s) }) {
                Text(stringResource(R.string.tags_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (pickFolder) FolderPickerDialog(stringResource(R.string.tags_filter_folder), null, onDismiss = { pickFolder = false }) { dir ->
        pickFolder = false
        d = d.copy(folder = Tags.keyOf(dir))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.tags_filter_delete_title, d.name.ifBlank { default ?: "" })) },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; Tags.deleteFilter(initial.id); onDeleted(); onDismiss() }) {
                Text(stringResource(R.string.tags_filter_delete), color = c.danger)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = D.c.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}
