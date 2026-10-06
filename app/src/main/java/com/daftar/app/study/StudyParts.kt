package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.data.FolderMeta
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.folderColor
import java.io.File

/** Paper colour behind selection images (ink is drawn dark on transparent, so it always sits on paper, also in dark mode). */
val Paper = Color(0xFFFFFDF8)

/** A subject (deck) as shown in the UI. [path] "" = General. */
data class Subject(val path: String, val name: String, val meta: FolderMeta)

/** Root folders of the library = subjects. */
fun subjects(): List<Subject> =
    Storage.list(Storage.root).filter { it.kind == Kind.FOLDER }.map { Subject(it.file.absolutePath, it.file.name, it.meta ?: FolderMeta()) }

@Composable
fun rememberSubjects(): List<Subject> {
    val v = Storage.version
    return remember(v) { subjects() }
}

/** Subject folder (first folder under the library root) that contains [f], or "" when none. */
fun subjectOf(f: File): String {
    val dir = if (f.isDirectory) f else f.parentFile ?: return ""
    return Storage.crumbs(dir).getOrNull(1)?.absolutePath ?: ""
}

@Composable
fun subjectLabel(path: String): String = if (path.isEmpty()) stringResource(R.string.study_general) else File(path).name

fun subjectMeta(path: String): FolderMeta =
    if (path.isEmpty()) FolderMeta(color = 11, icon = "inbox") else if (File(path).isDirectory) Storage.meta(File(path)) else FolderMeta(color = 11, icon = "folder")

fun subjectColor(path: String): Color = folderColor(subjectMeta(path).color)

/** Chips: General + every subject; the selected one is tinted with its folder colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectChips(selected: String, onPick: (String) -> Unit, extra: List<String> = emptyList()) {
    val subs = rememberSubjects()
    val paths = (listOf("") + subs.map { it.path } + extra).distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { p ->
            Chip(subjectLabel(p), p == selected, { onPick(p) }, tint = subjectColor(p))
        }
    }
}

@Composable
fun BitmapImage(bmp: Bitmap, modifier: Modifier = Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    Box(modifier.background(Paper, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Image(img, null, Modifier.fillMaxWidth().padding(8.dp), contentScale = ContentScale.Fit)
    }
}

/** Flat dialog shell (24dp radius, scrollable body, button row) sized for phones and tablets. */
@Composable
internal fun StudyDialog(title: String, onDismiss: () -> Unit, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = D.c
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 600.dp).fillMaxWidth().imePadding()
                .background(c.surface, RoundedCornerShape(24.dp)).border(1.dp, c.line, RoundedCornerShape(24.dp)),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}
