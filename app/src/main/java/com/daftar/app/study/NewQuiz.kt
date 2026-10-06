package com.daftar.app.study

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ai.AiPrefs
import com.daftar.app.ai.FileContext
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.Chip
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.Screen
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.card
import com.daftar.app.ui.FileBadge
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val quizKinds = setOf(Kind.NOTE, Kind.PDF, Kind.PPTX, Kind.DOCX, Kind.TEXT, Kind.IMAGE, Kind.ONENOTE)

/** Files the AI can read (see `ai.FileContext`). */
internal fun quizAccepts(f: File) = f.isFile && !f.name.startsWith(".") && FileContext.kind(f) in quizKinds

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewQuizScreenImpl() {
    val c = D.c
    val r = QuizRequests
    val hasKey = AiPrefs.hasKey
    var picker by remember { mutableStateOf(false) }
    var folderPicker by remember { mutableStateOf(false) }
    var privacy by remember { mutableStateOf(false) }
    val fallbackTitle = stringResource(R.string.quiz_default_title)

    fun leave() { r.cancel(); pane.back() }
    BackHandler { leave() }

    // open the new quiz once it is saved
    val done = r.done
    LaunchedEffect(done) { if (done != null) { r.done = null; pane.replace(Screen.Quiz(done)) } }

    fun start() { if (!AiPrefs.privacyAccepted) privacy = true else r.generate(fallbackTitle) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        ViewerTopBar(stringResource(R.string.quiz_new), onBack = { leave() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = gutter()).padding(bottom = 32.dp)) {
                if (!hasKey) { Spacer(Modifier.height(16.dp)); NoKeyCard() }

                // ---------- source ----------
                SectionTitle(stringResource(R.string.quiz_source))
                SourceCard(onAddFiles = { picker = true }, onAddFolder = { folderPicker = true })

                // ---------- questions ----------
                SectionTitle(stringResource(R.string.quiz_questions))
                OptionsCard()

                // ---------- instructions ----------
                SectionTitle(stringResource(R.string.quiz_instructions))
                OutlinedTextField(
                    r.options.instructions, { r.options = r.options.copy(instructions = it.take(2000)) },
                    Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    placeholder = { Text(stringResource(R.string.quiz_instructions_hint), color = c.muted) },
                    enabled = !r.busy,
                )

                Spacer(Modifier.height(20.dp))
                r.error?.let { e ->
                    AiErrorCard(e, onRetry = { r.error = null; start() }, onDismiss = { r.error = null })
                    Spacer(Modifier.height(12.dp))
                }
                if (r.busy) Progress(r.stage) { r.cancel() }
                else {
                    val canGo = hasKey && !r.imageLoading && (r.hasSource || r.options.instructions.isNotBlank()) && r.options.types.isNotEmpty()
                    Button(
                        onClick = { start() }, enabled = canGo, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.quiz_generate))
                    }
                    if (hasKey && !r.hasSource && r.options.instructions.isBlank())
                        Text(stringResource(R.string.quiz_need_source), style = MaterialTheme.typography.bodySmall, color = c.muted,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }

    if (picker) LibraryFilePickerDialog(stringResource(R.string.quiz_pick_files), accept = ::quizAccepts, multiple = true,
        onDismiss = { picker = false }, start = r.sources.lastOrNull { !it.folder }?.file?.parentFile ?: Storage.root) { files ->
        picker = false
        r.add(files, folder = false)
    }
    if (folderPicker) FolderPickerDialog(onDismiss = { folderPicker = false }) { dir ->
        folderPicker = false
        r.add(listOf(dir), folder = true)
    }
    if (privacy) AiPrivacyDialog(onDismiss = { privacy = false }) { privacy = false; r.generate(fallbackTitle) }
}

@Composable
private fun SourceCard(onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    val c = D.c
    val r = QuizRequests
    Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) {
        var rows = 0
        @Composable fun divider() { if (rows++ > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(c.line)) }
        // selection image
        val img = r.image
        if (img != null || r.imageLoading) {
            divider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SelectionThumb(img?.bytes)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.quiz_selection), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    Text(stringResource(R.string.quiz_selection_desc), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                IconButton(onClick = { r.clearImage() }, enabled = !r.busy) { Icon(Icons.Rounded.Close, stringResource(R.string.quiz_remove), tint = c.muted) }
            }
        }
        // files and folders
        r.sources.forEachIndexed { i, s ->
            key(s.file.absolutePath) {
                divider()
                if (s.folder) FolderSourceRow(s) { r.sources.removeAt(i) }
                else FileSourceRow(i, s) { r.sources.removeAt(i) }
            }
        }
        if (img == null && !r.imageLoading && r.sources.isEmpty()) {
            divider()
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Description, c.muted)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.quiz_no_file), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f))
            }
        }
        divider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAddFiles, enabled = !r.busy) {
                Icon(Icons.Rounded.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quiz_add_files))
            }
            OutlinedButton(onClick = onAddFolder, enabled = !r.busy) {
                Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.quiz_add_folder))
            }
        }
        if (r.sources.isNotEmpty() && (r.sources.size > 1 || r.sources.any { it.folder }))
            Text(stringResource(R.string.quiz_budget_note), style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
    }
}

/** "PDF", "Note", "DOCX"… */
@Composable
private fun kindLabel(f: File): String =
    if (FileContext.kind(f) == Kind.NOTE) stringResource(R.string.quiz_kind_note) else f.extension.uppercase()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileSourceRow(index: Int, s: QuizSource, onRemove: () -> Unit) {
    val c = D.c
    val r = QuizRequests
    val f = s.file
    val count by produceState<Int?>(null, f) {
        value = runCatching { withContext(Dispatchers.IO) { FileContext.pageCount(f) } }.getOrDefault(0)
    }
    // keep the range inside the file once the count is known
    LaunchedEffect(count) {
        val n = count ?: return@LaunchedEffect
        val cur = r.sources.getOrNull(index) ?: return@LaunchedEffect
        if (n > 0) { val a = cur.from.coerceIn(0, n - 1); r.update(index, cur.copy(from = a, to = cur.to.coerceIn(a, n - 1))) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FileBadge(FileContext.kind(f), 40.dp, f.extension)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.bodyLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(kindLabel(f) + " · " + when (val n = count) { null -> stringResource(R.string.quiz_counting); 0 -> stringResource(R.string.quiz_err_read); else -> pluralStringResource(R.plurals.quiz_n_pages, n, n) },
                    style = MaterialTheme.typography.bodySmall, color = if (count == 0) c.danger else c.muted)
            }
            IconButton(onClick = onRemove, enabled = !r.busy) { Icon(Icons.Rounded.Close, stringResource(R.string.quiz_remove), tint = c.muted) }
        }
        val n = count ?: 0
        if (n > 1) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.quiz_whole_file), s.whole, { if (!r.busy) r.update(index, s.copy(whole = true)) })
                Chip(stringResource(R.string.quiz_some_pages), !s.whole, { if (!r.busy) r.update(index, s.copy(whole = false)) })
            }
            if (!s.whole) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PageField(stringResource(R.string.quiz_from), s.from + 1, n) { v -> r.update(index, s.copy(from = v - 1, to = maxOf(s.to, v - 1))) }
                    Spacer(Modifier.width(12.dp))
                    PageField(stringResource(R.string.quiz_to), s.to + 1, n) { v -> r.update(index, s.copy(to = v - 1, from = minOf(s.from, v - 1))) }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.quiz_of_n, n), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
                if (s.to - s.from + 1 > 20 && FileContext.isVisual(f))
                    Text(stringResource(R.string.quiz_many_pages), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun FolderSourceRow(s: QuizSource, onRemove: () -> Unit) {
    val c = D.c
    val files by produceState<List<File>?>(null, s.file) {
        value = runCatching { withContext(Dispatchers.IO) { QuizContext.expandFolder(s.file) } }.getOrDefault(emptyList())
    }
    val meta = remember(s.file) { Storage.meta(s.file) }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FolderGlyph(meta.color, meta.icon, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (Storage.isRoot(s.file)) stringResource(R.string.files) else s.file.name, style = MaterialTheme.typography.bodyLarge, color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = files?.size
            Text(stringResource(R.string.quiz_kind_folder) + " · " + when {
                n == null -> stringResource(R.string.quiz_counting)
                n == 0 -> stringResource(R.string.quiz_folder_empty)
                n > QuizContext.MAX_FILES -> stringResource(R.string.quiz_folder_capped, n, QuizContext.MAX_FILES)
                else -> pluralStringResource(R.plurals.quiz_n_files, n, n)
            }, style = MaterialTheme.typography.bodySmall, color = if (n == 0) c.danger else c.muted)
        }
        IconButton(onClick = onRemove, enabled = !QuizRequests.busy) { Icon(Icons.Rounded.Close, stringResource(R.string.quiz_remove), tint = c.muted) }
    }
}

/** 1-based page number field; commits valid numbers in 1..[max]. */
@Composable
private fun PageField(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf("$value") }
    OutlinedTextField(
        text, { t ->
            text = t.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let { if (it in 1..max) onChange(it) }
        },
        Modifier.width(96.dp), singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !QuizRequests.busy,
    )
}

@Composable
private fun SelectionThumb(bytes: ByteArray?) {
    val c = D.c
    val bmp by produceState<ImageBitmap?>(null, bytes) {
        value = if (bytes == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(Modifier.size(88.dp, 64.dp).clip(RoundedCornerShape(12.dp)).background(Paper).border(1.dp, c.line, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, stringResource(R.string.quiz_selection), Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        else CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard() {
    val c = D.c
    val r = QuizRequests
    val o = r.options
    val busy = r.busy
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        // count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.quiz_count), style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { r.options = o.copy(count = (o.count - 1).coerceAtLeast(1)) }, enabled = !busy && o.count > 1) {
                Icon(Icons.Rounded.Remove, stringResource(R.string.quiz_fewer), tint = c.ink)
            }
            Text("${o.count}", style = MaterialTheme.typography.titleMedium, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 40.dp))
            IconButton(onClick = { r.options = o.copy(count = (o.count + 1).coerceAtMost(30)) }, enabled = !busy && o.count < 30) {
                Icon(Icons.Rounded.Add, stringResource(R.string.quiz_more), tint = c.ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        Label(stringResource(R.string.quiz_types))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QType.all.forEach { t ->
                val on = t in o.types
                Chip(typeName(t), on, {
                    if (!busy) r.options = o.copy(types = if (on) o.types - t else o.types + t)
                }, leading = if (on) Icons.Rounded.Check else null)
            }
        }
        if (o.types.isEmpty()) Text(stringResource(R.string.quiz_pick_type), style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        Label(stringResource(R.string.quiz_difficulty))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("easy" to R.string.quiz_easy, "medium" to R.string.quiz_medium, "hard" to R.string.quiz_hard, "mixed" to R.string.quiz_mixed).forEach { (k, s) ->
                Chip(stringResource(s), o.difficulty == k, { if (!busy) r.options = o.copy(difficulty = k) })
            }
        }
        Spacer(Modifier.height(14.dp))
        Label(stringResource(R.string.quiz_language))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to R.string.quiz_lang_auto, "ar" to R.string.quiz_lang_ar, "en" to R.string.quiz_lang_en).forEach { (k, s) ->
                Chip(stringResource(s), o.language == k, { if (!busy) r.options = o.copy(language = k) })
            }
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t, style = MaterialTheme.typography.labelLarge, color = D.c.muted, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
internal fun typeName(t: String) = stringResource(when (t) {
    QType.MCQ -> R.string.quiz_type_mcq
    QType.TF -> R.string.quiz_type_tf
    QType.SHORT -> R.string.quiz_type_short
    else -> R.string.quiz_type_essay
})

@Composable
private fun Progress(stage: QuizRequests.Stage, onCancel: () -> Unit) {
    val c = D.c
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = c.accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val r = QuizRequests
                Text(when {
                    stage == QuizRequests.Stage.READING && r.readTotal > 0 -> stringResource(R.string.quiz_stage_reading_n, r.readIndex, r.readTotal)
                    else -> stringResource(when (stage) {
                        QuizRequests.Stage.READING -> R.string.quiz_stage_reading
                        QuizRequests.Stage.SAVING -> R.string.quiz_stage_saving
                        else -> R.string.quiz_stage_writing
                    })
                }, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                if (stage == QuizRequests.Stage.READING && r.readName.isNotEmpty())
                    Text(r.readName, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.quiz_stage_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        Spacer(Modifier.height(12.dp))
        val r = QuizRequests
        if (stage == QuizRequests.Stage.READING && r.readTotal > 0)
            LinearProgressIndicator({ r.readIndex.toFloat() / r.readTotal }, Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = c.accent, trackColor = c.surfaceAlt)
        else LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = c.accent, trackColor = c.surfaceAlt)
    }
}
