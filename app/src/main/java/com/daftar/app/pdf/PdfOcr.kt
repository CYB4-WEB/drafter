package com.daftar.app.pdf

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ml.Ocr
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One recognized line in displayed page points. */
class OcrPageLine(val text: String, val box: RectF, val rtl: Boolean)

/** OCR result of one page; [w]×[h] = the page size (points) it was recognized at, to detect a changed PDF. */
class OcrPage(val w: Float, val h: Float, val lines: List<OcrPageLine>)

/**
 * OCR results of one PDF, persisted in the sidecar `.<name>.ocr.json`:
 * `{"v":1,"pages":{"<index>":{"w":595,"h":842,"lines":[{"t":"…","b":[l,t,r,b],"rtl":false}]}}}`.
 * Thread-safe; [version] is observable and bumps when results change.
 */
class OcrStore(val pdf: File) {
    private val file = Storage.sidecar(pdf, "ocr.json")
    private val pages = ConcurrentHashMap<Int, OcrPage>()
    private val texts = ConcurrentHashMap<Int, PageText>()
    var version by mutableIntStateOf(0)
        private set

    val isEmpty: Boolean get() = pages.isEmpty()
    val pageIndices: Set<Int> get() = pages.keys.toSortedSet()

    fun get(i: Int): OcrPage? = pages[i]

    /** Loads the sidecar, dropping pages whose size no longer matches the PDF (pages were edited). Call on IO. */
    fun load(pageSize: (Int) -> Pair<Float, Float>?) {
        pages.clear(); texts.clear()
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val ps = root.optJSONObject("pages") ?: return
            for (k in ps.keys()) {
                val i = k.toIntOrNull() ?: continue
                val o = ps.getJSONObject(k)
                val w = o.optDouble("w", 0.0).toFloat(); val h = o.optDouble("h", 0.0).toFloat()
                val sz = pageSize(i) ?: continue
                if (abs(sz.first - w) > 2f || abs(sz.second - h) > 2f) continue
                val arr = o.optJSONArray("lines") ?: JSONArray()
                val lines = ArrayList<OcrPageLine>(arr.length())
                for (j in 0 until arr.length()) {
                    val l = arr.getJSONObject(j)
                    val b = l.getJSONArray("b")
                    lines.add(OcrPageLine(l.optString("t"), RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()), l.optBoolean("rtl")))
                }
                pages[i] = OcrPage(w, h, lines)
            }
        }.onFailure { Log.e("PdfOcr", "load ocr sidecar", it) }
        bump()
    }

    /** Stores the result of page [i] and writes the sidecar (atomic). Call on IO. */
    fun put(i: Int, p: OcrPage) {
        pages[i] = p
        texts.remove(i)
        save()
        bump()
    }

    private fun bump() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { version++ }
    }

    @Synchronized private fun save() {
        runCatching {
            val ps = JSONObject()
            for ((i, p) in pages.toSortedMap()) {
                val arr = JSONArray()
                p.lines.forEach { l ->
                    arr.put(JSONObject().put("t", l.text).put("rtl", l.rtl)
                        .put("b", JSONArray().put(l.box.left.toDouble()).put(l.box.top.toDouble()).put(l.box.right.toDouble()).put(l.box.bottom.toDouble())))
                }
                ps.put(i.toString(), JSONObject().put("w", p.w.toDouble()).put("h", p.h.toDouble()).put("lines", arr))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("v", 1).put("pages", ps).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.e("PdfOcr", "save ocr sidecar", it) }
    }

    /** Page [i]'s OCR text as a searchable [PageText] (synthetic glyph boxes spread over each line), or null. */
    fun pageText(i: Int): PageText? {
        texts[i]?.let { return it }
        val p = pages[i] ?: return null
        if (p.lines.isEmpty()) return null
        return buildPageText(p.lines).also { texts[i] = it }
    }

    /** Plain text of page [i] (lines joined), or null when not recognized. */
    fun text(i: Int): String? = pages[i]?.lines?.joinToString("\n") { it.text }

    companion object {
        /** Builds a [PageText] from OCR lines; each character gets an equal share of its line box (mirrored for RTL). */
        fun buildPageText(lines: List<OcrPageLine>): PageText {
            val display = StringBuilder()
            val norm = StringBuilder()
            val n2d = ArrayList<Int>(); val n2dEnd = ArrayList<Int>(); val n2g = ArrayList<Int>()
            val glyphs = ArrayList<Float>()
            val boxes = FloatArray(lines.size * 4)
            val sizes = FloatArray(lines.size)
            var g = 0
            lines.forEachIndexed { li, l ->
                val text = Normalizer.normalize(l.text.replace('\n', ' '), Normalizer.Form.NFKC)
                val b = l.box
                boxes[li * 4] = b.left; boxes[li * 4 + 1] = b.top; boxes[li * 4 + 2] = b.right; boxes[li * 4 + 3] = b.bottom
                sizes[li] = b.height() * 0.8f
                val n = text.length.coerceAtLeast(1)
                val cw = b.width() / n
                for ((k, ch) in text.withIndex()) {
                    val ds = display.length
                    display.append(ch)
                    val left = if (l.rtl) b.right - (k + 1) * cw else b.left + k * cw
                    glyphs.add(left); glyphs.add(b.top); glyphs.add(left + cw); glyphs.add(b.bottom)
                    if (!TextFold.isStripped(ch)) {
                        val f = TextFold.fold(ch)
                        if (!(f == ' ' && (norm.isEmpty() || norm[norm.length - 1] == ' '))) {
                            norm.append(f); n2d.add(ds); n2dEnd.add(ds + 1); n2g.add(if (f == ' ') -1 else g)
                        }
                    }
                    g++
                }
                display.append('\n')
                if (norm.isNotEmpty() && norm[norm.length - 1] != ' ') {
                    norm.append(' '); n2d.add(display.length - 1); n2dEnd.add(display.length); n2g.add(-1)
                }
            }
            return PageText(
                display.toString(), norm.toString(), n2d.toIntArray(), n2dEnd.toIntArray(), n2g.toIntArray(), glyphs.toFloatArray(),
                boxes, sizes, FloatArray(0), fromOcr = true,
            )
        }
    }
}

object PdfOcr {
    val LANGS = listOf("en", "ar")

    /**
     * Recognizes [pages] of [pdf] one by one (blocking parts on IO) and stores each result in [store] as soon as it is done,
     * so a cancelled run keeps the pages finished so far. Returns the number of pages recognized.
     */
    suspend fun recognize(
        pdf: File,
        store: OcrStore,
        pages: List<Int>,
        isCancelled: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val src = PdfSource(pdf)
        var done = 0
        try {
            onProgress(0, pages.size)
            for ((k, i) in pages.withIndex()) {
                if (isCancelled()) break
                if (i !in 0 until src.pageCount) continue
                val (w, h) = src.pageSize(i)
                // ~200 dpi, longest side ≤ 3000 px: enough for small print, bounded memory (≤ 36 MB ARGB).
                val s = min(200f / 72f, 3000f / max(w, h))
                val bmp = src.renderFit(i, (w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1)) ?: break
                val sx = bmp.width / w; val sy = bmp.height / h
                val r = try { Ocr.recognize(bmp, LANGS) } finally { bmp.recycle() }
                val lines = r.lines.filter { it.text.isNotBlank() }.map { l ->
                    OcrPageLine(l.text.trim(), RectF(l.box.left / sx, l.box.top / sy, l.box.right / sx, l.box.bottom / sy), l.rtl)
                }.sortedWith(compareBy({ it.box.top }, { if (it.rtl) -it.box.right else it.box.left }))
                store.put(i, OcrPage(w, h, lines))
                done++
                onProgress(k + 1, pages.size)
            }
        } finally {
            src.close()
        }
        done
    }

    /**
     * Text of pages [from]..[to] (1-based, inclusive): the text layer, or the OCR text for pages without one.
     * One document load. Call on IO.
     */
    fun extractTextMerged(file: File, from: Int, to: Int, store: OcrStore): String {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(32L shl 20)).use { doc ->
            val st = PDFTextStripper()
            val sb = StringBuilder()
            val last = min(to, doc.numberOfPages)
            for (p in from..last) {
                st.startPage = p; st.endPage = p
                val t = runCatching { st.getText(doc) }.getOrDefault("")
                val use = if (t.isBlank()) store.text(p - 1) ?: "" else t.trimEnd()
                if (use.isNotBlank()) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(use) }
            }
            return sb.toString()
        }
    }

    /**
     * Writes a copy of [src] with an invisible text layer (rendering mode NEITHER) on every recognized page, using an
     * embedded subset of Amiri (Arabic + Latin), so other apps can search and copy the scan's text.
     * Temp file → rename; the original is untouched. Call on IO.
     */
    fun writeSearchable(ctx: Context, src: File, store: OcrStore, out: File, isCancelled: () -> Boolean, onProgress: (Int, Int) -> Unit): File {
        val tmp = File(out.parentFile, ".${out.name}.tmp")
        try {
            PDDocument.load(src, MemoryUsageSetting.setupMixed(32L shl 20)).use { doc ->
                val font = ctx.resources.openRawResource(R.font.amiri).use { PDType0Font.load(doc, it, true) }
                val supported = HashMap<Char, Boolean>()
                fun ok(c: Char): Boolean = supported.getOrPut(c) { runCatching { font.encode(c.toString()); true }.getOrDefault(false) }
                val pages = store.pageIndices.filter { it in 0 until doc.numberOfPages }
                pages.forEachIndexed { k, i ->
                    if (isCancelled()) throw CancellationException("cancelled")
                    val ocr = store.get(i) ?: return@forEachIndexed
                    val page = doc.getPage(i)
                    val crop = page.cropBox
                    val rot = ((page.rotation % 360) + 360) % 360
                    val pw = crop.width; val ph = crop.height
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER)
                        for (l in ocr.lines) {
                            val clean = buildString { l.text.forEach { c -> append(if (c.isWhitespace() || !ok(c)) ' ' else c) } }.trim()
                            if (clean.isEmpty()) continue
                            val text = if (l.rtl || PdfBlocks.isRtl(clean)) PdfBlocks.visualOrder(clean) else clean
                            val b = l.box
                            val fs = (b.height() * 0.8f).coerceIn(1f, 300f)
                            val natural = runCatching { font.getStringWidth(text) / 1000f * fs }.getOrDefault(0f)
                            if (natural <= 0f) continue
                            val hs = (b.width() / natural * 100f).coerceIn(5f, 1000f)
                            val ox = b.left; val oy = b.bottom - b.height() * 0.2f
                            // displayed (top-left origin, /Rotate applied) → PDF user space
                            val (x, y) = when (rot) {
                                90 -> oy to ox
                                180 -> (pw - ox) to oy
                                270 -> (pw - oy) to (ph - ox)
                                else -> ox to (ph - oy)
                            }
                            val (a, bb, c, d) = when (rot) {
                                90 -> listOf(0f, 1f, -1f, 0f)
                                180 -> listOf(-1f, 0f, 0f, -1f)
                                270 -> listOf(0f, -1f, 1f, 0f)
                                else -> listOf(1f, 0f, 0f, 1f)
                            }
                            cs.setFont(font, fs)
                            cs.setHorizontalScaling(hs)
                            cs.setTextMatrix(Matrix(a, bb, c, d, x + crop.lowerLeftX, y + crop.lowerLeftY))
                            runCatching { cs.showText(text) }.onFailure { Log.w("PdfOcr", "text layer line skipped", it) }
                        }
                        cs.endText()
                    }
                    onProgress(k + 1, pages.size)
                }
                doc.save(tmp)
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            return out
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
