package com.daftar.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.ui.theme.D

enum class WidthClass { Compact, Medium, Expanded }

/** Provided by MainActivity from the current window size (changes live with split-screen / rotation). */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.Expanded }

fun widthClassOf(widthDp: Dp) = when {
    widthDp < 600.dp -> WidthClass.Compact
    widthDp < 840.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

/** Folder icon catalogue: key stored in FolderMeta.icon -> vector. */
val StudyIcons: List<Pair<String, ImageVector>> = listOf(
    "folder" to Icons.Rounded.Folder,
    "lecture" to Icons.Rounded.School,
    "seminar" to Icons.Rounded.Groups,
    "lab" to Icons.Rounded.Science,
    "book" to Icons.AutoMirrored.Rounded.MenuBook,
    "math" to Icons.Rounded.Functions,
    "calc" to Icons.Rounded.Calculate,
    "code" to Icons.Rounded.Code,
    "bio" to Icons.Rounded.Biotech,
    "med" to Icons.Rounded.MedicalServices,
    "law" to Icons.Rounded.Gavel,
    "lang" to Icons.Rounded.Translate,
    "art" to Icons.Rounded.Palette,
    "music" to Icons.Rounded.MusicNote,
    "eng" to Icons.Rounded.Engineering,
    "arch" to Icons.Rounded.Architecture,
    "econ" to Icons.Rounded.TrendingUp,
    "history" to Icons.Rounded.HistoryEdu,
    "globe" to Icons.Rounded.Public,
    "psych" to Icons.Rounded.Psychology,
    "chem" to Icons.Rounded.Colorize,
    "physics" to Icons.Rounded.Bolt,
    "exam" to Icons.Rounded.Quiz,
    "task" to Icons.AutoMirrored.Rounded.Assignment,
    "star" to Icons.Rounded.Star,
    "inbox" to Icons.Rounded.Inbox,
)
private val iconMap = StudyIcons.toMap()
fun studyIcon(key: String): ImageVector = iconMap[key] ?: Icons.Rounded.Folder

fun kindIcon(k: Kind): ImageVector = when (k) {
    Kind.FOLDER -> Icons.Rounded.Folder
    Kind.NOTE -> Icons.Rounded.Draw
    Kind.PDF -> Icons.Rounded.PictureAsPdf
    Kind.PPTX -> Icons.Rounded.Slideshow
    Kind.DOCX -> Icons.Rounded.Description
    Kind.TEXT -> Icons.AutoMirrored.Rounded.Article
    Kind.IMAGE -> Icons.Rounded.Image
    Kind.AUDIO -> Icons.Rounded.Mic
    Kind.OTHER -> Icons.Rounded.InsertDriveFile
}

/** Distinct but quiet colour per file type (badge on file tiles). */
fun kindColor(k: Kind): Color = when (k) {
    Kind.NOTE -> Color(0xFF6366F1)
    Kind.PDF -> Color(0xFFEF4444)
    Kind.PPTX -> Color(0xFFF97316)
    Kind.DOCX -> Color(0xFF3B82F6)
    Kind.TEXT -> Color(0xFF10B981)
    Kind.IMAGE -> Color(0xFF3F8F5B)
    Kind.AUDIO -> Color(0xFF8B5CF6)
    else -> Color(0xFF5E6B78)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = D.c.ink, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = D.c.muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = D.c.muted, style = MaterialTheme.typography.bodyLarge)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

/** Flat card: surface + 1dp line border, 16dp radius. */
fun Modifier.card(c: com.daftar.app.ui.theme.DaftarColors, radius: Dp = 16.dp) =
    this.background(c.surface, RoundedCornerShape(radius)).border(1.dp, c.line, RoundedCornerShape(radius))

/** Top bar used by viewers/editors: back + title + trailing actions. */
@Composable
fun ViewerTopBar(title: String, onBack: () -> Unit, onTitleClick: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(D.c.surface).windowInsetsPadding(WindowInsets.statusBars)
            .height(if (com.daftar.app.data.Prefs.largeControls) 64.dp else 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = D.c.ink) }
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = D.c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier).padding(horizontal = 4.dp),
        )
        actions()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(v, { v = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(fr))
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = { if (v.isNotBlank()) onConfirm(v.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) D.c.danger else D.c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Small rounded chip used for toggles / filters. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, leading: ImageVector? = null, tint: Color? = null) {
    val c = D.c
    Row(
        Modifier.background(if (selected) (tint ?: c.accent).copy(alpha = 0.14f) else c.surfaceAlt, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) (tint ?: c.accent) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, Modifier.size(18.dp), tint = if (selected) (tint ?: c.accent) else c.muted)
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) c.ink else c.muted, maxLines = 1)
    }
}

/** Floating zoom pill used by every viewer: − / percent (tap = fit) / + / fit. */
@Composable
fun ZoomControls(percent: Int, onOut: () -> Unit, onIn: () -> Unit, onFit: () -> Unit, modifier: Modifier = Modifier) {
    val c = D.c
    Row(
        modifier.background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOut, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Remove, stringResource(R.string.zoom_out), tint = c.ink) }
        Text("$percent%", style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1,
            modifier = Modifier.clickable(onClick = onFit).padding(horizontal = 4.dp, vertical = 8.dp))
        IconButton(onClick = onIn, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, stringResource(R.string.zoom_in), tint = c.ink) }
        IconButton(onClick = onFit, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.FitScreen, stringResource(R.string.zoom_fit), tint = c.muted) }
    }
}
