package com.daftar.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.daftar.app.ink.NoteExport
import com.daftar.app.onenote.OneLoader
import com.daftar.app.pdf.OcrStore
import com.daftar.app.slides.Labels
import com.daftar.app.slides.PptxParser
import com.daftar.app.slides.PptxSource
import com.daftar.app.slides.XNode
import com.daftar.app.slides.phFamily
import com.daftar.app.slides.phOf
import com.daftar.app.slides.plainText
import com.daftar.app.word.DocLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One page of a file as the AI sees it. [index] is 0-based. The caller recycles [image]. */
data class PageContent(val index: Int, val text: String, val image: Bitmap?)

/**
 * What the AI (chat or quiz) can read from a file. Works for PDF, Daftar notes/whiteboards, PPTX slides, DOCX/DOC,
 * TXT/MD/RTF/CSV…, images and OneNote. Text-only formats are split into ~3000-char "pages".
 * Everything runs on [Dispatchers.IO], is cancellable between pages and renders at most one page bitmap at a time
 * inside [parts].
 */
object FileContext {
    /** Long side of page images sent to the model. */
    const val MAX_SIDE = 1600
    /** Caps for one [parts] call. */
    const val MAX_IMAGES = 20
    const val MAX_CHARS = 120_000
    /** Size of a "page" for text-only formats. */
    private const val CHUNK = 3000

    /** True when pages of this file have a visual rendering (sent as images). */
    fun isVisual(file: File): Boolean = when (kind(file)) {
        Kind.PDF, Kind.NOTE, Kind.PPTX, Kind.IMAGE -> true
        else -> false
    }

    /** Storage kind, with legacy `.ppt` routed to the slides reader (it reports 0 pages when it cannot be read). */
    fun kind(file: File): Kind = if (file.extension.equals("ppt", true)) Kind.PPTX else Storage.kindOf(file)

    suspend fun pageCount(file: File): Int = withContext(Dispatchers.IO) {
        runCatching {
            when (kind(file)) {
                Kind.PDF -> pdfPageCount(file)
                Kind.NOTE -> note(file)?.pages?.size ?: 0
                Kind.PPTX -> PptxParser.open(file).use { it.slides.size }
                Kind.IMAGE -> 1
                Kind.DOCX, Kind.TEXT, Kind.ONENOTE -> textPages(file).size
                else -> 0
            }
        }.getOrDefault(0)
    }

    /**
     * Pages [from]..[to] (0-based, inclusive, clamped). Images only when [images] and the format is visual
     * (PDF / note / slides / image); each image ≤ [MAX_SIDE] px on its long side. Caller recycles bitmaps.
     */
    suspend fun pages(file: File, from: Int, to: Int, images: Boolean = true): List<PageContent> {
        val out = ArrayList<PageContent>()
        try {
            forEachPage(file, from, to, { images }) { out.add(it); true }
        } catch (t: Throwable) {
            out.forEach { it.image?.recycle() }
            throw t
        }
        return out
    }

    /**
     * Gemini parts for pages [from]..[to] (0-based, inclusive): a text part per page ("[Page n]" + text) followed by its
     * image, at most [MAX_IMAGES] images and about [MAX_CHARS] characters. Bitmaps are encoded and freed one by one.
     */
    suspend fun parts(file: File, from: Int, to: Int): List<Gemini.Part> {
        val out = ArrayList<Gemini.Part>()
        var chars = 0
        var imgs = 0
        var truncated = false
        forEachPage(file, from, to, { imgs < MAX_IMAGES && chars < MAX_CHARS }) { p ->
            try {
                if (chars >= MAX_CHARS) { truncated = true; return@forEachPage false }
                val room = MAX_CHARS - chars
                val body = p.text.trim().let { if (it.length > room) { truncated = true; it.take(room) + " …" } else it }
                val label = "[Page ${p.index + 1}]"
                val txt = if (body.isEmpty()) "$label${if (p.image != null) "" else " (no text)"}" else "$label\n$body"
                out.add(Gemini.Part.text(txt))
                chars += txt.length
                p.image?.let { b -> out.add(Gemini.Part.image(b, MAX_SIDE)); imgs++ }
            } finally {
                p.image?.recycle()
            }
            true
        }
        if (truncated) out.add(Gemini.Part.text("[The rest was left out to keep the request small.]"))
        return out
    }

    // ------------------------------------------------------------------------------------------------ dispatch

    /** Streams pages to [block] one at a time; [block] returns false to stop. [wantImage] is asked before each page is rendered. */
    private suspend fun forEachPage(file: File, from: Int, to: Int, wantImage: () -> Boolean, block: suspend (PageContent) -> Boolean) =
        withContext(Dispatchers.IO) {
            when (kind(file)) {
                Kind.PDF -> pdfPages(file, from, to, wantImage, block)
                Kind.NOTE -> notePages(file, from, to, wantImage, block)
                Kind.PPTX -> slidePages(file, from, to, wantImage, block)
                Kind.IMAGE -> if (from <= 0 && to >= 0) { block(PageContent(0, "", if (wantImage()) decodeImage(file) else null)) }
                Kind.DOCX, Kind.TEXT, Kind.ONENOTE -> {
                    val ps = textPages(file)
                    if (ps.isEmpty()) return@withContext
                    for (i in from.coerceIn(0, ps.lastIndex)..to.coerceIn(0, ps.lastIndex)) {
                        coroutineContext.ensureActive()
                        if (!block(PageContent(i, ps[i], null))) break
                    }
                }
                else -> {}
            }
            Unit
        }

    // ------------------------------------------------------------------------------------------------ PDF

    private fun pdfPageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }

    private suspend fun pdfPages(file: File, from: Int, to: Int, wantImage: () -> Boolean, block: suspend (PageContent) -> Boolean) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { PdfRenderer(pfd) } catch (t: Throwable) { pfd.close(); throw t }
        val doc: PDDocument? = runCatching { PDDocument.load(file).also { if (it.isEncrypted) it.isAllSecurityToBeRemoved = true } }.getOrNull()
        try {
            val n = renderer.pageCount
            if (n <= 0) return
            val ink = runCatching { InkDoc.load(Storage.sidecar(file, "ink.json")) }.getOrNull()
            val ocr: OcrStore? = runCatching {
                if (!Storage.sidecar(file, "ocr.json").isFile) null
                else OcrStore(file).also { s -> s.load { i -> if (i in 0 until n) renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() } else null } }
            }.getOrNull()
            val stripper = doc?.let { PDFTextStripper().apply { sortByPosition = false } }
            for (i in from.coerceIn(0, n - 1)..to.coerceIn(0, n - 1)) {
                coroutineContext.ensureActive()
                var text = runCatching {
                    if (doc == null || stripper == null || i >= doc.numberOfPages) "" else {
                        stripper.startPage = i + 1; stripper.endPage = i + 1
                        stripper.getText(doc).trim()
                    }
                }.getOrDefault("")
                if (text.isBlank()) text = ocr?.text(i)?.trim().orEmpty()
                val ip = ink?.pages?.getOrNull(i)
                text = withAnnotations(text, ip)
                var bmp: Bitmap? = null
                if (wantImage()) {
                    bmp = runCatching {
                        renderer.openPage(i).use { page ->
                            val pw = page.width.toFloat().coerceAtLeast(1f); val ph = page.height.toFloat().coerceAtLeast(1f)
                            val s = min(MAX_SIDE / max(pw, ph), 3f)
                            val b = Bitmap.createBitmap((pw * s).roundToInt().coerceAtLeast(1), (ph * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            b.eraseColor(android.graphics.Color.WHITE)
                            page.render(b, null, Matrix().apply { setScale(b.width / pw, b.height / ph) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            overlayInk(b, ip, pw, ph)
                            b
                        }
                    }.getOrNull()
                }
                if (!block(PageContent(i, text, bmp))) break
            }
        } finally {
            runCatching { doc?.close() }
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    // ------------------------------------------------------------------------------------------------ notes

    private val noteLock = Any()
    private var noteKey: String? = null
    private var noteDoc: InkDoc? = null

    /** The last loaded note is kept (one entry) so pageCount + pages on the same file parse it once. */
    private fun note(file: File): InkDoc? = synchronized(noteLock) {
        val key = stamp(file)
        if (key != noteKey) { noteDoc = InkDoc.load(file); noteKey = key }
        noteDoc
    }

    private suspend fun notePages(file: File, from: Int, to: Int, wantImage: () -> Boolean, block: suspend (PageContent) -> Boolean) {
        val doc = note(file) ?: return
        if (doc.pages.isEmpty()) return
        for (i in from.coerceIn(0, doc.pages.lastIndex)..to.coerceIn(0, doc.pages.lastIndex)) {
            coroutineContext.ensureActive()
            val page = doc.pages[i]
            val text = page.texts.sortedWith(compareBy({ it.y }, { it.x })).map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            val bmp = if (wantImage()) runCatching {
                val r = doc.exportRect(i)
                val scale = min(2f, MAX_SIDE / max(r.width(), r.height()).coerceAtLeast(1f))
                NoteExport.renderPage(doc, i, scale)
            }.getOrNull() else null
            if (!block(PageContent(i, text, bmp))) break
        }
    }

    // ------------------------------------------------------------------------------------------------ slides

    private suspend fun slidePages(file: File, from: Int, to: Int, wantImage: () -> Boolean, block: suspend (PageContent) -> Boolean) {
        val ctx = Storage.appCtx
        val labels = Labels(ctx.getString(R.string.slides_chart), ctx.getString(R.string.slides_diagram), ctx.getString(R.string.slides_object), ctx.getString(R.string.slides_image))
        val src = PptxSource.open(file, labels)
        try {
            val deck = src.deck
            if (deck.slides.isEmpty()) return
            val ink = runCatching { InkDoc.load(Storage.sidecar(file, "ink.json")) }.getOrNull()
            for (i in from.coerceIn(0, deck.slides.lastIndex)..to.coerceIn(0, deck.slides.lastIndex)) {
                coroutineContext.ensureActive()
                val s = deck.slides[i]
                val text = buildString {
                    if (s.title.isNotBlank()) append(s.title.trim()).append('\n')
                    val body = ArrayList<String>()
                    s.part.root.path("cSld", "spTree")?.let { shapeText(it, body) }
                    body.forEach { append(it).append('\n') }
                    if (s.notes.isNotBlank()) append("Speaker notes: ").append(s.notes.trim()).append('\n')
                }.trim()
                val ip = ink?.pages?.getOrNull(i)
                val bmp = if (wantImage()) runCatching {
                    val w = if (deck.widthPt >= deck.heightPt) MAX_SIDE else (MAX_SIDE * deck.widthPt / deck.heightPt).roundToInt()
                    src.thumbnail(i, w)?.also { overlayInk(it, ip, deck.widthPt, deck.heightPt) }
                }.getOrNull() else null
                if (!block(PageContent(i, withAnnotations(text, ip), bmp))) break
            }
        } finally {
            src.close()
        }
    }

    /** Text of every shape (title placeholder skipped, it is already the slide title; tables row by row). */
    private fun shapeText(tree: XNode, out: MutableList<String>) {
        for (sh in tree.children) when (sh.name) {
            "sp" -> {
                val ph = phOf(sh)
                val type = ph?.attr("type")
                if (ph != null && (phFamily(type ?: "body") == "title" || type == "sldNum" || type == "dt" || type == "ftr")) continue
                val t = plainText(sh.child("txBody")).trim()
                if (t.isNotEmpty()) out.add(t)
            }
            "grpSp" -> shapeText(sh, out)
            "graphicFrame" -> for (tr in sh.findAll("tr")) {
                val row = tr.children("tc").joinToString("\t") { tc -> plainText(tc.child("txBody")).replace('\n', ' ').trim() }
                if (row.isNotBlank()) out.add(row)
            }
            "AlternateContent" -> sh.child("Choice")?.let { shapeText(it, out) }
        }
    }

    // ------------------------------------------------------------------------------------------------ text formats

    private val textMutex = Mutex()
    private var textKey: String? = null
    private var textCache: List<String> = emptyList()

    /** Pages of a text-only format; the last file is cached (one entry, bounded by the file's own text). */
    private suspend fun textPages(file: File): List<String> = textMutex.withLock {
        val key = stamp(file)
        if (key == textKey) return@withLock textCache
        val pages: List<String> = when (kind(file)) {
            Kind.ONENOTE -> {
                val job = coroutineContext[kotlinx.coroutines.Job]
                val book = OneLoader.load(file, File(Storage.appCtx.cacheDir, "onenote")) { job?.isActive == false }
                book.sections.flatMap { sec ->
                    sec.pages.flatMap { p ->
                        val t = (if (book.sections.size > 1) "${sec.name} — " else "") + p.plainText().trim()
                        chunk(t)
                    }
                }
            }
            else -> chunk(DocLoader.load(file).plainText())
        }
        textKey = key; textCache = pages
        pages
    }

    /** Splits [text] into ~[CHUNK]-char pieces at paragraph (then line / space) boundaries. */
    internal fun chunk(text: String): List<String> {
        val t = text.replace("\r\n", "\n").trim()
        if (t.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start < t.length) {
            var end = min(t.length, start + CHUNK)
            if (end < t.length) {
                val min = start + CHUNK / 2
                val para = t.lastIndexOf("\n\n", end).takeIf { it >= min }
                val line = t.lastIndexOf('\n', end).takeIf { it >= min }
                val space = t.lastIndexOf(' ', end).takeIf { it >= min }
                end = (para ?: line ?: space ?: end).coerceAtLeast(start + 1)
            }
            val piece = t.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            start = end
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun stamp(f: File) = "${f.absolutePath}|${f.length()}|${f.lastModified()}"

    /** Typed text boxes the user put on a PDF / slide page (ink sidecar). */
    private fun withAnnotations(text: String, ip: InkPage?): String {
        val notes = ip?.texts?.map { it.text.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (notes.isEmpty()) return text
        return (if (text.isBlank()) "" else "$text\n") + "[User's annotations] " + notes.joinToString(" · ")
    }

    private fun overlayInk(target: Bitmap, ip: InkPage?, pw: Float, ph: Float) {
        if (ip == null || ip.isEmpty()) return
        runCatching {
            val c = Canvas(target)
            val iw = if (ip.w > 0f) ip.w else pw
            val ih = if (ip.h > 0f) ip.h else ph
            c.scale(target.width / iw, target.height / ih)
            InkRender.drawPageContent(c, ip)
        }
    }

    /** Decodes an image with sampling so its long side is about [MAX_SIDE] px. */
    private fun decodeImage(f: File): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val longSide = max(o.outWidth, o.outHeight)
        if (longSide <= 0) return@runCatching null
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val s = max(b.width, b.height)
        if (s <= MAX_SIDE) b else Bitmap.createScaledBitmap(b, b.width * MAX_SIDE / s, b.height * MAX_SIDE / s, true).also { if (it !== b) b.recycle() }
    }.getOrNull()
}
