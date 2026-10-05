package com.daftar.app.pdf

import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.Writer
import java.text.Bidi
import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Folding used for search: the page text and the query go through the same function so
 * "أحمد" finds "احمد", "مدرسة" finds "مدرسه", "Test" finds "TEST" and tashkeel / tatweel never block a match.
 */
object TextFold {
    /** Characters dropped entirely: tashkeel, superscript alef, Quranic marks, tatweel, joiners, bidi marks, soft hyphen. */
    fun isStripped(c: Char): Boolean =
        c in 'ً'..'ٟ' || c == 'ٰ' || c in 'ۖ'..'ۭ' || c == 'ـ' ||
            c == '‌' || c == '‍' || c == '‎' || c == '‏' || c == '­' || c == '؜'

    fun fold(c: Char): Char = when (c) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى' -> 'ي'
        'ة' -> 'ه'
        in '٠'..'٩' -> '0' + (c - '٠')
        in '۰'..'۹' -> '0' + (c - '۰')
        else -> if (Character.isWhitespace(c) || c == ' ') ' ' else Character.toLowerCase(c)
    }

    /** Folded query: NFKC (presentation forms, ligatures), folded characters, single spaces, trimmed. */
    fun query(q: String): String {
        val n = Normalizer.normalize(q, Normalizer.Form.NFKC)
        val sb = StringBuilder(n.length)
        for (ch in n) {
            if (isStripped(ch)) continue
            val f = fold(ch)
            if (f == ' ' && (sb.isEmpty() || sb[sb.length - 1] == ' ')) continue
            sb.append(f)
        }
        return sb.toString().trimEnd()
    }
}

/** One match: page (0-based), a one-line snippet with the matched part at [boldStart, boldEnd), and its rectangles in displayed page points. */
class SearchHit(val page: Int, val snippet: String, val boldStart: Int, val boldEnd: Int, val rects: List<RectF>)

sealed class SearchEvent {
    class Hits(val hits: List<SearchHit>) : SearchEvent()
    class Progress(val done: Int, val total: Int) : SearchEvent()
    /** End of a complete scan; [hasText] is false when no page has a text layer (scanned PDF). */
    class Finished(val hasText: Boolean) : SearchEvent()
}

/**
 * Text of one page, ready for searching.
 * [display] is the page text in logical (reading) order with '\n' between lines; [norm] is its folded form.
 * For every char of [norm]: [n2d]/[n2dEnd] = the display range it came from, [n2g] = glyph index (-1 for separators).
 * [glyphs] holds 4 floats (left, top, right, bottom) per glyph, in displayed page points (same space as the ink layer).
 */
class PageText(
    val display: String,
    val norm: String,
    val n2d: IntArray,
    val n2dEnd: IntArray,
    val n2g: IntArray,
    val glyphs: FloatArray,
    /** Per display line: 4 floats (left, top, right, bottom) in displayed page points. */
    val lineBoxes: FloatArray = FloatArray(0),
    /** Per display line: largest font size in points (headings are guessed from it). */
    val lineSizes: FloatArray = FloatArray(0),
    /** Per picture drawn on the page: 4 floats (left, top, right, bottom) in displayed page points. */
    val images: FloatArray = FloatArray(0),
    /** True when this text comes from OCR (scanned page) rather than the PDF's text layer. */
    val fromOcr: Boolean = false,
) {
    val bytes: Long get() = 64L + display.length * 2L + norm.length * 14L + glyphs.size * 4L + lineBoxes.size * 5L + images.size * 4L

    /** The display lines (reading order), same indexing as [lineBoxes] / [lineSizes]. */
    val lines: List<String> by lazy {
        val l = display.split('\n')
        if (l.isNotEmpty() && l.last().isEmpty()) l.dropLast(1) else l
    }

    fun lineBox(i: Int): RectF? {
        val o = i * 4
        return if (o + 3 < lineBoxes.size) RectF(lineBoxes[o], lineBoxes[o + 1], lineBoxes[o + 2], lineBoxes[o + 3]) else null
    }

    fun lineSize(i: Int): Float = lineSizes.getOrNull(i) ?: 0f

    val imageCount: Int get() = images.size / 4

    fun imageBox(i: Int): RectF = RectF(images[i * 4], images[i * 4 + 1], images[i * 4 + 2], images[i * 4 + 3])

    val hasText: Boolean get() = norm.isNotBlank()
}

private class IntBuf(cap: Int = 1024) {
    var a = IntArray(cap); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun toArray() = a.copyOf(n)
    fun clear() { n = 0 }
}

private class FloatBuf(cap: Int = 4096) {
    var a = FloatArray(cap); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun add4(l: Float, t: Float, r: Float, b: Float) {
        if (n + 4 > a.size) a = a.copyOf(a.size * 2)
        a[n++] = l; a[n++] = t; a[n++] = r; a[n++] = b
    }
    fun toArray() = a.copyOf(n)
    fun clear() { n = 0 }
}

/**
 * Search over a PDF with a small per-session cache of page texts (bounded by [budgetBytes]),
 * so refining a query does not re-parse pages that were already read.
 */
class PdfTextIndex(private val file: File, private val budgetBytes: Long) {
    private val cache = HashMap<Int, PageText>()
    private var used = 0L

    @Synchronized private fun cached(i: Int): PageText? = cache[i]

    @Synchronized private fun keep(i: Int, t: PageText) {
        if (cache.containsKey(i) || used + t.bytes > budgetBytes) return
        cache[i] = t; used += t.bytes
    }

    @Synchronized fun clear() { cache.clear(); used = 0 }

    /**
     * Text for pages that have no text layer (scanned) — set by the session from the OCR sidecar.
     * Search, reading mode and translation use it in place of the empty text layer.
     */
    @Volatile var ocrPage: ((Int) -> PageText?)? = null

    /** The text layer of page [i] if it is cached already (no parsing). */
    fun cachedPage(i: Int): PageText? = cached(i)

    /** Text layer when it has text, else the OCR text when there is some, else the (empty) text layer. */
    fun effective(i: Int, t: PageText): PageText = if (t.hasText) t else ocrPage?.invoke(i) ?: t

    /**
     * Blocking walk over pages [from]..[to] (0-based, inclusive) delivering each page's text layer (not merged with OCR —
     * see [effective]). Uses / fills the cache. Call on IO. Throws [CancellationException] once [isCancelled] turns true.
     */
    fun walk(from: Int = 0, to: Int = Int.MAX_VALUE, isCancelled: () -> Boolean = { false }, onPage: (Int, PageText) -> Unit) {
        PDDocument.load(file, MemoryUsageSetting.setupMixed(16L shl 20)).use { doc ->
            val n = doc.numberOfPages
            val a = from.coerceIn(0, (n - 1).coerceAtLeast(0))
            val b = to.coerceIn(a, (n - 1).coerceAtLeast(0))
            val stripper = IndexStripper(cached = ::cached, isCancelled = isCancelled) { idx, text ->
                keep(idx, text)
                onPage(idx, text)
            }
            stripper.startPage = a + 1
            stripper.endPage = b + 1
            stripper.writeText(doc, NullWriter)
        }
    }

    /** Text layer of one page (blocking, cached). */
    fun page(i: Int): PageText? {
        cached(i)?.let { return it }
        var out: PageText? = null
        walk(i, i) { _, t -> out = t }
        return out
    }

    /** Progressive results for [query]: hits page by page plus progress; cancelled with the collecting coroutine. */
    fun search(query: String, maxHits: Int = MAX_HITS): Flow<SearchEvent> = channelFlow {
        val job = coroutineContext[Job]
        searchBlocking(
            query, maxHits,
            isCancelled = { job?.isActive == false },
            onProgress = { d, t -> trySend(SearchEvent.Progress(d, t)) },
            onHits = { trySend(SearchEvent.Hits(it)) },
            onFinished = { trySend(SearchEvent.Finished(it)) },
        )
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** Blocking search (call on IO). Stops at [maxHits] matches or when [isCancelled] turns true. */
    fun searchBlocking(
        query: String,
        maxHits: Int,
        isCancelled: () -> Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
        onHits: (List<SearchHit>) -> Unit,
        onFinished: (hasText: Boolean) -> Unit = {},
    ) {
        val q = TextFold.query(query)
        if (q.isEmpty()) { onFinished(true); return }
        PDDocument.load(file, MemoryUsageSetting.setupMixed(16L shl 20)).use { doc ->
            val total = doc.numberOfPages
            var found = 0
            var hasText = false
            onProgress(0, total)
            val stripper = IndexStripper(
                cached = ::cached,
                isCancelled = isCancelled,
            ) { idx, raw ->
                keep(idx, raw)
                val text = effective(idx, raw)
                if (text.norm.isNotBlank()) hasText = true
                if (found < maxHits) {
                    val hits = matches(idx, text, q, maxHits - found)
                    found += hits.size
                    if (hits.isNotEmpty()) onHits(hits)
                }
                onProgress(idx + 1, total)
                if (found >= maxHits) throw StopSearch()
            }
            try {
                stripper.writeText(doc, NullWriter)
            } catch (_: StopSearch) {
                hasText = true
            }
            onProgress(total, total)
            onFinished(hasText)
        }
    }

    private class StopSearch : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        const val MAX_HITS = 999
        private const val SNIP_BEFORE = 36
        private const val SNIP_AFTER = 64

        /** All non-overlapping matches of the folded query [q] in [t]. */
        fun matches(page: Int, t: PageText, q: String, limit: Int): List<SearchHit> {
            val out = ArrayList<SearchHit>()
            var from = 0
            while (out.size < limit) {
                val s = t.norm.indexOf(q, from)
                if (s < 0) break
                val e = s + q.length
                val ds = t.n2d[s]
                val de = t.n2dEnd[e - 1]
                val rects = ArrayList<RectF>()
                var lastG = -1
                for (k in s until e) {
                    val g = t.n2g[k]
                    if (g < 0 || g == lastG) continue
                    lastG = g
                    val o = g * 4
                    if (o + 3 < t.glyphs.size) rects.add(RectF(t.glyphs[o], t.glyphs[o + 1], t.glyphs[o + 2], t.glyphs[o + 3]))
                }
                val (snip, bs, be) = snippet(t.display, ds, de)
                out.add(SearchHit(page, snip, bs, be, mergeLines(rects)))
                from = e
            }
            return out
        }

        private fun snippet(d: String, ds: Int, de: Int): Triple<String, Int, Int> {
            var a = max(0, ds - SNIP_BEFORE)
            while (a > 0 && a > ds - SNIP_BEFORE - 16 && !d[a - 1].isWhitespace()) a--
            var b = min(d.length, de + SNIP_AFTER)
            while (b < d.length && b < de + SNIP_AFTER + 16 && !d[b].isWhitespace()) b++
            val pre = if (a > 0) "…" else ""
            val post = if (b < d.length) "…" else ""
            val body = d.substring(a, b).replace('\n', ' ')
            val bs = pre.length + (ds - a)
            val be = pre.length + (de - a)
            return Triple(pre + body + post, bs, be)
        }

        /** Unites glyph boxes that sit on the same line, so a match is one rectangle per line. */
        private fun mergeLines(rs: List<RectF>): List<RectF> {
            val out = ArrayList<RectF>()
            for (r in rs) {
                val m = out.firstOrNull { o ->
                    val ov = min(o.bottom, r.bottom) - max(o.top, r.top)
                    ov > 0.5f * min(o.height(), r.height())
                }
                if (m != null) m.union(r) else out.add(RectF(r))
            }
            return out
        }
    }
}

private object NullWriter : Writer() {
    override fun write(cbuf: CharArray, off: Int, len: Int) {}
    override fun write(str: String, off: Int, len: Int) {}
    override fun flush() {}
    override fun close() {}
}

/**
 * Builds a [PageText] for every page that is not cached yet.
 * Glyphs arrive sorted left-to-right per line (sortByPosition); each line is then reordered with the
 * Unicode bidi algorithm at glyph granularity, so Arabic lines come out in reading order (words and letters)
 * while every char still knows the glyph it came from.
 */
private class IndexStripper(
    private val cached: (Int) -> PageText?,
    private val isCancelled: () -> Boolean,
    private val onPage: (Int, PageText) -> Unit,
) : PDFTextStripper() {
    private class GlyphUnit(val text: String, val glyph: Int)

    private val line = ArrayList<GlyphUnit>()
    private val display = StringBuilder()
    private val norm = StringBuilder()
    private val n2d = IntBuf()
    private val n2dEnd = IntBuf()
    private val n2g = IntBuf()
    private val glyphs = FloatBuf()
    private var glyphCount = 0
    private val pt = FloatArray(8)
    private val lineBoxes = FloatBuf(256)
    private val lineSizes = FloatBuf(64)
    private val images = FloatBuf(16)
    private var lineSize = 0f
    private var ll = Float.MAX_VALUE; private var lt = Float.MAX_VALUE; private var lr = -Float.MAX_VALUE; private var lb = -Float.MAX_VALUE
    private var pageRot = 0; private var pageW = 0f; private var pageH = 0f; private var cropX = 0f; private var cropY = 0f

    init {
        sortByPosition = true
        suppressDuplicateOverlappingText = true
    }

    override fun processPage(page: PDPage) {
        if (isCancelled()) throw CancellationException("search cancelled")
        val idx = currentPageNo - 1
        val known = cached(idx)
        if (known != null) { onPage(idx, known); return }
        line.clear(); display.setLength(0); norm.setLength(0)
        n2d.clear(); n2dEnd.clear(); n2g.clear(); glyphs.clear(); glyphCount = 0
        lineBoxes.clear(); lineSizes.clear(); images.clear(); resetLineBox()
        val crop = page.cropBox
        pageRot = ((page.rotation % 360) + 360) % 360
        pageW = crop.width; pageH = crop.height; cropX = crop.lowerLeftX; cropY = crop.lowerLeftY
        super.processPage(page)
        flushLine()
        val t = PageText(
            display.toString(), norm.toString(), n2d.toArray(), n2dEnd.toArray(), n2g.toArray(), glyphs.toArray(),
            lineBoxes.toArray(), lineSizes.a.copyOf(lineSizes.n), images.toArray(),
        )
        onPage(idx, t)
    }

    private fun resetLineBox() {
        ll = Float.MAX_VALUE; lt = Float.MAX_VALUE; lr = -Float.MAX_VALUE; lb = -Float.MAX_VALUE; lineSize = 0f
    }

    /** Pictures: image XObjects (`Do`, forms are walked by the engine itself) and inline images (`BI`). */
    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        runCatching {
            when (operator.name) {
                "Do" -> {
                    val n = operands.firstOrNull() as? COSName
                    if (n != null && resources?.isImageXObject(n) == true) addImage()
                }
                "BI" -> addImage()
            }
        }
        super.processOperator(operator, operands)
    }

    /** The current transformation maps the unit square onto the picture; keep it in displayed page points. */
    private fun addImage() {
        val m = graphicsState.currentTransformationMatrix
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val ux = if (k == 1 || k == 2) 1f else 0f
            val uy = if (k >= 2) 1f else 0f
            val x = m.scaleX * ux + m.shearX * uy + m.translateX - cropX
            val y = m.shearY * ux + m.scaleY * uy + m.translateY - cropY
            val dx: Float; val dy: Float
            when (pageRot) {
                90 -> { dx = y; dy = x }
                180 -> { dx = pageW - x; dy = y }
                270 -> { dx = pageH - y; dy = pageW - x }
                else -> { dx = x; dy = pageH - y }
            }
            l = min(l, dx); tp = min(tp, dy); r = max(r, dx); b = max(b, dy)
        }
        // Skip rules, bullets and other decorations.
        if (r - l < 24f || b - tp < 24f) return
        if (images.n >= 4 * 200) return
        images.add4(l, tp, r, b)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (tp in textPositions) {
            val u = tp.unicode
            if (u.isNullOrEmpty()) continue
            val g = addGlyph(tp)
            val o = g * 4
            ll = min(ll, glyphs.a[o]); lt = min(lt, glyphs.a[o + 1]); lr = max(lr, glyphs.a[o + 2]); lb = max(lb, glyphs.a[o + 3])
            val fs = tp.fontSizeInPt.takeIf { it > 0f && it < 500f } ?: tp.heightDir
            if (fs > lineSize) lineSize = fs
            line.add(GlyphUnit(Normalizer.normalize(u, Normalizer.Form.NFKC), g))
        }
    }

    override fun writeWordSeparator() { line.add(GlyphUnit(" ", -1)) }

    override fun writeLineSeparator() { flushLine() }

    override fun writeParagraphEnd() { flushLine(); super.writeParagraphEnd() }

    private fun flushLine() {
        if (line.isEmpty()) return
        for (u in reorder(line)) append(u)
        line.clear()
        display.append('\n')
        if (ll <= lr) lineBoxes.add4(ll, lt, lr, lb) else lineBoxes.add4(0f, 0f, 0f, 0f)
        lineSizes.add(lineSize)
        resetLineBox()
        if (norm.isNotEmpty() && norm[norm.length - 1] != ' ') {
            norm.append(' '); n2d.add(display.length - 1); n2dEnd.add(display.length); n2g.add(-1)
        }
    }

    private fun append(u: GlyphUnit) {
        val ds = display.length
        display.append(u.text)
        val de = display.length
        for (ch in u.text) {
            if (TextFold.isStripped(ch)) continue
            val f = TextFold.fold(ch)
            if (f == ' ' && (norm.isEmpty() || norm[norm.length - 1] == ' ')) continue
            norm.append(f); n2d.add(ds); n2dEnd.add(de); n2g.add(if (f == ' ') -1 else u.glyph)
        }
    }

    /** Visual (left-to-right) glyph order → logical order, mirroring brackets inside right-to-left runs. */
    private fun reorder(src: List<GlyphUnit>): List<GlyphUnit> {
        val proxy = CharArray(src.size) { i -> src[i].text.firstOrNull { !TextFold.isStripped(it) } ?: src[i].text[0] }
        if (!Bidi.requiresBidi(proxy, 0, proxy.size)) return src
        val bidi = Bidi(String(proxy), Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return src
        val levels = ByteArray(src.size) { bidi.getLevelAt(it).toByte() }
        val arr = Array<Any>(src.size) { i ->
            val u = src[i]
            if ((levels[i].toInt() and 1) == 1 && u.text.length == 1) mirror(u.text[0])?.let { GlyphUnit(it.toString(), u.glyph) } ?: u else u
        }
        Bidi.reorderVisually(levels, 0, arr, 0, arr.size)
        return arr.map { it as GlyphUnit }
    }

    private fun mirror(c: Char): Char? = when (c) {
        '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'
        '<' -> '>'; '>' -> '<'; '«' -> '»'; '»' -> '«'
        else -> null
    }

    /**
     * Glyph box in displayed page points (top-left origin, page /Rotate applied): baseline from the glyph origin to its end,
     * extended below by the descent and above by the glyph height, mapped through the page rotation.
     */
    private fun addGlyph(t: TextPosition): Int {
        val m = t.textMatrix
        val sx = m.translateX; val sy = m.translateY
        var ax = t.endX - sx; var ay = t.endY - sy
        if (ax == 0f && ay == 0f) {
            val l = hypot(m.scaleX, m.shearY).takeIf { it > 0f } ?: 1f
            ax = m.scaleX / l * t.widthDirAdj; ay = m.shearY / l * t.widthDirAdj
        }
        var ux = m.shearX; var uy = m.scaleY
        val ul = hypot(ux, uy)
        if (ul > 0f) { ux /= ul; uy /= ul } else { ux = 0f; uy = 1f }
        val h = t.heightDir.takeIf { it > 0.1f } ?: (t.fontSizeInPt.toFloat().takeIf { it > 0f } ?: 10f) * 0.7f
        val dn = -0.3f * h; val up = 1.15f * h
        pt[0] = sx + ux * dn; pt[1] = sy + uy * dn
        pt[2] = sx + ax + ux * dn; pt[3] = sy + ay + uy * dn
        pt[4] = sx + ux * up; pt[5] = sy + uy * up
        pt[6] = sx + ax + ux * up; pt[7] = sy + ay + uy * up
        val rot = ((t.rotation % 360) + 360) % 360
        val w = t.pageWidth; val ph = t.pageHeight
        var l = Float.MAX_VALUE; var tp = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val x = pt[k * 2]; val y = pt[k * 2 + 1]
            val dx: Float; val dy: Float
            when (rot) {
                90 -> { dx = y; dy = x }
                180 -> { dx = w - x; dy = y }
                270 -> { dx = ph - y; dy = w - x }
                else -> { dx = x; dy = ph - y }
            }
            l = min(l, dx); tp = min(tp, dy); r = max(r, dx); b = max(b, dy)
        }
        glyphs.add4(l, tp, r, b)
        return glyphCount++
    }
}
