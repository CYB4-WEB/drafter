package com.daftar.app.ml

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.TextItem
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

/**
 * Paragraphs of an OCR result in reading order: lines are joined (end-of-line hyphens removed) and a new paragraph
 * starts at a vertical gap, a column change or a direction change. Good input for [Translator.translate].
 */
fun OcrResult.paragraphs(): List<String> {
    if (lines.isEmpty()) return emptyList()
    val hs = lines.map { it.box.height() }.sorted()
    val medH = hs[hs.size / 2].coerceAtLeast(1f)
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var prev: OcrLine? = null
    for (l in lines) {
        val p = prev
        val newPara = p == null || l.rtl != p.rtl || l.box.top - p.box.bottom > medH * 0.8f || l.box.bottom < p.box.top ||
            // indented first line (LTR) / short previous line ending a sentence
            (p.text.trimEnd().lastOrNull()?.let { it in ".!?؟:" } == true && p.box.width() < (lines.maxOf { it.box.width() }) * 0.7f)
        if (newPara && cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
        val t = l.text.trim()
        if (cur.isNotEmpty()) {
            if (cur.endsWith("-") && t.firstOrNull()?.isLowerCase() == true) cur.setLength(cur.length - 1) else cur.append(' ')
        }
        cur.append(t)
        prev = l
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

private sealed interface Phase {
    data object NeedOcrModels : Phase
    class Downloading(val progress: Float) : Phase
    data object Recognizing : Phase
    class NeedTranslateModels(val from: String, val to: String) : Phase
    data object Translating : Phase
    data object Done : Phase
    data object NoText : Phase
    class Failed(val msg: Int) : Phase
}

/**
 * Bottom sheet: recognises the text of [source] (OCR) and, when [translate], translates it. Selectable text with
 * Copy / Share / "Make note from text". [noteDir] + [noteName] name the note; [onNote] opens it.
 * [source] is called once off the main thread; the sheet recycles the bitmap it returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrTextSheet(
    source: suspend () -> Bitmap?,
    translate: Boolean,
    noteDir: File,
    noteName: String,
    onNote: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val langs = remember { if (TessEngine.available()) listOf("en", "ar") else listOf("en") }
    var phase by remember { mutableStateOf<Phase>(Phase.Recognizing) }
    var run by remember { mutableIntStateOf(0) }
    var paragraphs by remember { mutableStateOf<List<String>>(emptyList()) }
    var translated by remember { mutableStateOf<List<String>?>(null) }
    var from by remember { mutableStateOf("und") }
    var to by remember { mutableStateOf<String?>(null) }       // the user's choice
    var activeTo by remember { mutableStateOf<String?>(null) } // the target actually used
    var showOriginal by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val uiLang = LocalConfiguration.current.locales[0]?.language ?: "en"

    // OCR (once) → optional translation (again when the target changes).
    LaunchedEffect(run, to) {
        if (paragraphs.isEmpty()) {
            if (!Ocr.isReady(langs)) { phase = Phase.NeedOcrModels; return@LaunchedEffect }
            phase = Phase.Recognizing
            val bmp = withContext(Dispatchers.IO) { runCatching { source() }.getOrNull() }
            if (bmp == null) { phase = Phase.Failed(R.string.ml_failed); return@LaunchedEffect }
            val res = try { Ocr.recognize(bmp, langs) } finally { bmp.recycle() }
            paragraphs = res.paragraphs()
            if (paragraphs.isEmpty()) { phase = Phase.NoText; return@LaunchedEffect }
        }
        if (!translate) { phase = Phase.Done; return@LaunchedEffect }
        if (from == "und") from = Translator.detectLanguage(paragraphs.joinToString(" ").take(2000)).let { if (it == "und") "en" else it }
        val target = to ?: (if (from == "ar") (if (uiLang != "ar" && TranslateEngine.code(uiLang) != null) uiLang else "en") else "ar")
        activeTo = target
        if (target == from) { translated = paragraphs; phase = Phase.Done; return@LaunchedEffect }
        if (!Translator.isReady(from, target)) { phase = Phase.NeedTranslateModels(from, target); return@LaunchedEffect }
        phase = Phase.Translating
        val tr = Translator.translate(paragraphs, from, target)
        if (tr === paragraphs) { phase = Phase.Failed(R.string.ml_translate_failed); return@LaunchedEffect }
        translated = tr
        phase = Phase.Done
    }

    fun startDownload(job: suspend ((Float) -> Unit) -> Boolean) {
        if (!MlStore.networkAllowed(ctx)) { toast(ctx, ctx.getString(R.string.ml_no_network)); return }
        downloadProgress = 0f
        phase = Phase.Downloading(0f)
        scope.launch {
            val ok = job { p -> downloadProgress = p }
            MlStore.touch()
            if (ok) run++ else phase = Phase.Failed(R.string.ml_download_failed)
        }
    }

    val shown: List<String> = if (translate && !showOriginal) translated ?: paragraphs else paragraphs
    val shownText = shown.joinToString("\n\n")

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(if (translate) R.string.ml_translation else R.string.ml_recognized_text),
                style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(bottom = 8.dp))
            if (translate && (phase == Phase.Done || phase is Phase.NeedTranslateModels || phase == Phase.Translating)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ml_to_language), color = c.muted, style = MaterialTheme.typography.bodySmall)
                    for (l in TranslateEngine.offered) if (l != from) Chip(mlLanguageName(l), l == (to ?: activeTo), { if (to != l) { translated = null; to = l } })
                }
            }
            when (val p = phase) {
                Phase.Recognizing, Phase.Translating -> Progress(stringResource(if (p == Phase.Recognizing) R.string.ml_recognizing else R.string.ml_translating), null)
                is Phase.Downloading -> Progress(stringResource(R.string.ml_downloading, (downloadProgress * 100).toInt()), downloadProgress)
                Phase.NeedOcrModels -> Prompt(
                    stringResource(R.string.ml_need_ocr_models, android.text.format.Formatter.formatShortFileSize(ctx, MlStore.tessModel("ar")?.bytes ?: 0L)),
                    onCancel = onDismiss,
                ) { startDownload { pr -> Ocr.prepare(langs, pr) } }
                is Phase.NeedTranslateModels -> Prompt(
                    stringResource(R.string.ml_need_translate_models, mlLanguageName(p.from), mlLanguageName(p.to),
                        android.text.format.Formatter.formatShortFileSize(ctx, TranslateEngine.MODEL_BYTES_APPROX)),
                    onCancel = onDismiss,
                ) { startDownload { pr -> Translator.prepare(p.from, p.to, pr) } }
                Phase.NoText -> Text(stringResource(R.string.ml_no_text), color = c.muted, modifier = Modifier.padding(vertical = 24.dp))
                is Phase.Failed -> Column {
                    Text(stringResource(p.msg), color = c.muted, modifier = Modifier.padding(vertical = 16.dp))
                    OutlinedButton(onClick = { run++ }) { Text(stringResource(R.string.ml_retry)) }
                }
                Phase.Done -> {
                    Box(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                for (para in shown) Text(para, color = c.ink, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyText(ctx, shownText) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                            Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ml_copy))
                        }
                        OutlinedButton(onClick = { shareText(ctx, shownText) }) {
                            Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ml_share))
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { createTextNote(noteDir, noteName + " (" + ctx.getString(R.string.ml_note_suffix) + ")", shown) }
                                if (f != null) { onDismiss(); onNote(f) } else toast(ctx, ctx.getString(R.string.ml_note_failed))
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.NoteAdd, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.ml_make_note))
                        }
                        if (translate && translated != null) TextButton(onClick = { showOriginal = !showOriginal }) {
                            Text(stringResource(if (showOriginal) R.string.ml_show_translation else R.string.ml_show_original))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(label: String, value: Float?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text(label, color = D.c.muted, modifier = Modifier.padding(bottom = 12.dp))
        if (value == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = D.c.accent, trackColor = D.c.surfaceAlt)
        else LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = D.c.accent, trackColor = D.c.surfaceAlt)
    }
}

@Composable
private fun Prompt(text: String, onCancel: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text, color = D.c.ink, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ml_cancel)) }
            Button(onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = D.c.accent, contentColor = D.c.onAccent)) {
                Text(stringResource(R.string.ml_download))
            }
        }
    }
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(ctx, ctx.getString(R.string.ml_copied))
    }
}

private fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.share))) }
}

/**
 * Writes a notebook with [paragraphs] as text boxes on A4 pages (≈ 70 characters per wrapped line, 36 lines per page).
 * Returns the file, or null on failure.
 */
fun createTextNote(dir: File, name: String, paragraphs: List<String>): File? = runCatching {
    val pageW = 595f; val pageH = 842f; val margin = 48f; val size = 14f
    val lineH = size * 1.45f
    val maxLines = ((pageH - 2 * margin) / lineH).toInt()
    val pages = ArrayList<InkPage>()
    var texts = ArrayList<TextItem>()
    var y = margin
    var id = System.currentTimeMillis()
    fun newPage() { pages += InkPage(w = pageW, h = pageH, paper = "lined", texts = texts); texts = ArrayList(); y = margin }
    for (para in paragraphs) {
        // split very long paragraphs so each text box fits on a page
        val chunks = Sentences.chunks(para, 70 * (maxLines - 2))
        for (chunk in chunks) {
            val lines = chunk.split('\n').sumOf { ceil(it.length / 70.0).toInt().coerceAtLeast(1) }
            val h = lines * lineH
            if (y + h > pageH - margin && texts.isNotEmpty()) newPage()
            texts += TextItem(id = id++, x = margin, y = y, w = pageW - 2 * margin, text = chunk, size = size)
            y += h + lineH * 0.6f
        }
    }
    if (texts.isNotEmpty() || pages.isEmpty()) newPage()
    val f = Storage.uniqueFile(dir, name, Storage.NOTE_EXT)
    InkDoc(pages = pages).save(f)
    Storage.touch()
    Storage.opened(f)
    f
}.getOrNull()
