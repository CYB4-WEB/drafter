package com.daftar.app.ink

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Note → images / Word / plain text (PDF is [exportNoteToPdf]). Everything here runs off the main thread. */
internal object NoteExport {

    /** Pixels allowed for one rendered page (bounded by memory: ≤ 1/16 of the heap, ≤ 40 MP). */
    private fun maxPixels(): Double = min(40_000_000.0, Runtime.getRuntime().maxMemory() / 16.0 / 4.0)

    /**
     * Page [i] as a bitmap at [scale] × its size in points (2× ≈ 144 dpi). Whiteboards are cropped to their content
     * ([InkDoc.exportRect]); huge boards are scaled down to stay within memory. Tapes are drawn hidden.
     */
    fun renderPage(doc: InkDoc, i: Int, scale: Float = 2f): Bitmap {
        val page = doc.pages[i]
        val r = doc.exportRect(i)
        var s = scale.toDouble()
        val px = r.width() * s * r.height() * s
        if (px > maxPixels()) s *= sqrt(maxPixels() / px)
        s = min(s, 16000.0 / max(r.width(), r.height()).coerceAtLeast(1f))
        val w = (r.width() * s).roundToInt().coerceAtLeast(1)
        val h = (r.height() * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(doc.paperColor or 0xFF000000.toInt())
        c.scale(s.toFloat(), s.toFloat())
        c.translate(-r.left, -r.top)
        InkRender.drawPaper(c, page, clip = RectF(r), bounded = !doc.infinite)
        InkRender.drawPageContent(c, page)
        return bmp
    }

    fun writeBitmap(b: Bitmap, out: File, png: Boolean) {
        out.outputStream().buffered().use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, it) }
    }

    /** Writes the given pages as images next to the note ("Name.png", or "Name - 3.png" per page). */
    suspend fun exportImages(doc: InkDoc, pages: List<Int>, dir: File, base: String, png: Boolean, progress: (Int, Int) -> Unit): List<File> {
        val ext = if (png) "png" else "jpg"
        val out = ArrayList<File>()
        try {
            pages.forEachIndexed { k, i ->
                coroutineContext.ensureActive()
                progress(k + 1, pages.size)
                val bmp = renderPage(doc, i)
                try {
                    val f = Storage.uniqueFile(dir, if (pages.size == 1 && doc.pages.size == 1) base else "$base - ${i + 1}", ext)
                    writeBitmap(bmp, f, png)
                    out.add(f)
                } finally { bmp.recycle() }
            }
        } catch (e: Throwable) {
            // cancelled or failed half way: do not leave a partial set behind
            out.forEach { it.delete() }
            throw e
        }
        return out
    }

    /**
     * The note's text, page by page, for Word / plain text: typed text boxes plus (when [lang] is not null and the model
     * is available) recognized handwriting, merged top-to-bottom. Returns the pages' paragraphs and whether handwriting
     * had to be left out.
     */
    suspend fun pageTexts(doc: InkDoc, lang: String?, progress: (Int, Int) -> Unit): Pair<List<List<String>>, Boolean> {
        var hwOk = lang != null && doc.pages.any { p -> p.strokes.any { it.tool == Tool.PEN } }
        var hwMissing = false
        if (hwOk) {
            hwOk = runCatching { if (!Handwriting.isReady(lang!!)) Handwriting.download(lang); true }.getOrDefault(false)
            if (!hwOk) hwMissing = true
        }
        val pages = ArrayList<List<String>>()
        doc.pages.forEachIndexed { i, p ->
            coroutineContext.ensureActive()
            progress(i + 1, doc.pages.size)
            val items = ArrayList<Pair<Float, String>>()
            for (t in p.texts) if (t.text.isNotBlank()) items.add(t.y to t.text.trim())
            if (hwOk) {
                for ((top, line) in lines(p.strokes.filter { it.tool == Tool.PEN })) {
                    coroutineContext.ensureActive()
                    val txt = runCatching { Handwriting.recognize(lang!!, line) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; "" }
                    if (txt.isNotBlank()) items.add(top to txt.trim())
                }
            }
            items.sortBy { it.first }
            pages.add(items.map { it.second })
        }
        return pages to hwMissing
    }

    /** Groups strokes into text lines by vertical overlap; each line with its top (page points). */
    private fun lines(strokes: List<Stroke>): List<Pair<Float, List<Stroke>>> {
        val lines = ArrayList<MutableList<Stroke>>()
        val boxes = ArrayList<RectF>()
        for (s in strokes.sortedBy { it.bounds().centerY() }) {
            val b = s.bounds()
            val idx = boxes.indexOfFirst { b.centerY() in it.top..it.bottom }
            if (idx >= 0) { lines[idx].add(s); boxes[idx].union(b) } else { lines.add(mutableListOf(s)); boxes.add(RectF(b)) }
        }
        // keep the drawing order inside a line (writing direction is unknown: Arabic is right-to-left)
        return lines.indices.map { k -> boxes[k].top to strokes.filter { it in lines[k] } }
    }

    /** Word paragraphs: one per text box / handwriting line, a page break between pages. */
    fun docxParagraphs(pages: List<List<String>>): List<String> {
        val out = ArrayList<String>()
        pages.forEachIndexed { i, ps -> if (i > 0) out.add("\u000C"); out.addAll(ps) }
        return out
    }

    fun plainText(pages: List<List<String>>): String =
        pages.filter { it.isNotEmpty() }.joinToString("\n\n\n") { it.joinToString("\n\n") } + "\n"
}

/** The Activity behind a (possibly wrapped) context, e.g. for the print service. */
internal fun Context.findActivityOrNull(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** "Print" options: all pages, the current page or a range (1-based, inclusive). Returns 0-based page indices. */
@Composable
internal fun PrintDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onPrint: (List<Int>) -> Unit) {
    val c = D.c
    var mode by remember { mutableIntStateOf(0) }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf(pageCount.toString()) }
    val a = from.toIntOrNull(); val b = to.toIntOrNull()
    val rangeOk = a != null && b != null && a in 1..pageCount && b in 1..pageCount && a <= b
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ink_print_title)) },
        text = {
            Column {
                @Composable
                fun Opt(i: Int, label: String) {
                    Row(Modifier.fillMaxWidth().clickable { mode = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == i, { mode = i })
                        Text(label, color = c.ink)
                    }
                }
                Opt(0, stringResource(R.string.ink_print_all))
                Opt(1, stringResource(R.string.ink_print_current, current + 1))
                Opt(2, stringResource(R.string.ink_print_range))
                if (mode == 2) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(from, { from = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true,
                            label = { Text(stringResource(R.string.ink_print_from)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(to, { to = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), singleLine = true,
                            label = { Text(stringResource(R.string.ink_print_to)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    if (!rangeOk) Text(stringResource(R.string.ink_print_range_bad, pageCount), color = c.danger,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = mode != 2 || rangeOk, onClick = {
                onPrint(when (mode) {
                    1 -> listOf(current.coerceIn(0, pageCount - 1))
                    2 -> ((a!! - 1)..(b!! - 1)).toList()
                    else -> (0 until pageCount).toList()
                })
            }) { Text(stringResource(R.string.ink_print_btn)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Result of an export: where it went, with Open / Share. */
@Composable
internal fun ExportResultBar(modifier: Modifier, files: List<File>, onOpen: (File) -> Unit, onShare: (List<File>) -> Unit, onDismiss: () -> Unit) {
    val c = D.c
    val label = if (files.size == 1) stringResource(R.string.saved_to, files[0].name) else stringResource(R.string.ink_saved_n, files.size)
    Row(
        modifier.widthIn(max = 560.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { onOpen(files[0]) }) { Text(stringResource(R.string.open)) }
        TextButton(onClick = { onShare(files) }) { Text(stringResource(R.string.share)) }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
    }
}
