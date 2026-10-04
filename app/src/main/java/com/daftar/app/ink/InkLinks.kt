package com.daftar.app.ink

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Prefs
import com.daftar.app.ui.Chip
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.pane
import com.daftar.app.ui.screenFor
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import com.daftar.app.ui.workspace.Workspace
import java.io.File

/** Opens a link inside Daftar: library files in their viewer, web/video in the in-app browser (unless the user chose another app). */
internal fun openLink(ctx: Context, l: LinkItem) {
    if (l.isFile) {
        val f = File(l.target)
        if (!f.exists()) { toast(ctx, ctx.getString(R.string.ink_link_missing)); return }
        pane.open(ctx, f)
        return
    }
    if (Prefs.linksInApp) { pane.push(Screen.Web(l.target)); return }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        toast(ctx, ctx.getString(R.string.no_app_found))
    }
}

/** Opens a link next to the document that contains it ([host] = the note / PDF / slides file). */
internal fun openLinkSideBySide(ctx: Context, l: LinkItem, host: File?) {
    if (l.isFile) {
        val f = File(l.target)
        if (!f.exists()) { toast(ctx, ctx.getString(R.string.ink_link_missing)); return }
        if (host != null && host.exists()) Workspace.openSideBySide(ctx, host, f) else pane.open(ctx, f)
        return
    }
    val hostScreen = host?.takeIf { it.exists() }?.let { screenFor(it) }
    if (hostScreen != null) Nav.push(Screen.Split(hostScreen, Screen.Web(l.target))) else pane.push(Screen.Web(l.target))
}

/** Long-press menu of a link chip, anchored at the press position ([x], [y] in px inside the canvas box). */
@Composable
internal fun LinkMenu(x: Float, y: Float, link: LinkItem, onDismiss: () -> Unit, onOpen: () -> Unit, onSideBySide: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = D.c
    val dens = LocalDensity.current
    Box(Modifier.offset(x = with(dens) { x.toDp() }, y = with(dens) { y.toDp() }).size(1.dp)) {
        DropdownMenu(true, onDismiss, offset = DpOffset(0.dp, 0.dp)) {
            Text(link.label, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 6.dp))
            DropdownMenuItem({ Text(stringResource(R.string.ink_link_open)) }, onOpen, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.open_side_by_side)) }, onSideBySide, leadingIcon = { Icon(Icons.Rounded.VerticalSplit, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.edit)) }, onEdit, leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = c.muted) })
            DropdownMenuItem({ Text(stringResource(R.string.delete), color = c.danger) }, onDelete, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
        }
    }
}

/**
 * Insert / edit a link: a web or video address (optional label) or a file from the library (PowerPoint, PDF, Word, note…).
 * [onSave] receives (target, label) — target is a normalized http(s) URL or an absolute file path.
 */
@Composable
internal fun LinkDialog(existing: LinkItem?, exclude: File?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val c = D.c
    var fileMode by remember { mutableStateOf(existing?.isFile ?: false) }
    var url by remember { mutableStateOf(if (existing != null && !existing.isFile) existing.target else "") }
    var file by remember { mutableStateOf(existing?.takeIf { it.isFile }?.let { File(it.target) }) }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var picking by remember { mutableStateOf(false) }
    val normalized = LinkItem.normalizeUrl(url)
    val canSave = if (fileMode) file != null else normalized != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.ink_insert_link else R.string.ink_edit_link)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.ink_link_web), !fileMode, { fileMode = false }, leading = Icons.Rounded.Public)
                    Chip(stringResource(R.string.ink_link_file), fileMode, { fileMode = true }, leading = Icons.Rounded.FolderOpen)
                }
                Spacer(Modifier.height(14.dp))
                if (!fileMode) {
                    OutlinedTextField(
                        url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.ink_link_url)) },
                        placeholder = { Text("https://youtube.com/…") },
                        isError = url.isNotBlank() && normalized == null,
                        supportingText = { Text(stringResource(R.string.ink_link_url_hint), color = c.muted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
                            .clickable { picking = true }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val f = file
                        if (f != null) {
                            val (col, tag) = InkRender.fileBadge(f.absolutePath)
                            Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Color(col)), contentAlignment = Alignment.Center) {
                                Text(tag, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(f.name, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        } else {
                            Icon(Icons.Rounded.FolderOpen, null, tint = c.accent)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.ink_link_choose_file), color = c.accent, modifier = Modifier.weight(1f))
                        }
                    }
                    if (file != null) TextButton(onClick = { picking = true }) { Text(stringResource(R.string.ink_link_change_file)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    label, { label = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ink_link_label)) },
                    placeholder = {
                        val def = if (fileMode) file?.let { LinkItem.defaultLabel(it.absolutePath) } else normalized?.let { LinkItem.defaultLabel(it) }
                        Text(def ?: stringResource(R.string.ink_link_label_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = {
                val target = if (fileMode) file!!.absolutePath else normalized!!
                onSave(target, label.trim().ifEmpty { LinkItem.defaultLabel(target) })
            }) { Text(stringResource(if (existing == null) R.string.ink_insert else R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (picking) LibraryFilePickerDialog(
        title = stringResource(R.string.ink_link_choose_file),
        accept = { f -> exclude == null || f.absolutePath != exclude.absolutePath },
        multiple = false,
        onDismiss = { picking = false },
        start = file?.parentFile ?: exclude?.parentFile ?: com.daftar.app.data.Storage.root,
    ) { picked ->
        picking = false
        picked.firstOrNull()?.let { f ->
            if (label.isBlank() || file?.let { LinkItem.defaultLabel(it.absolutePath) } == label) label = ""
            file = f
        }
    }
}
