package com.daftar.app.word

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Print layout engine shared by the on-screen print view and the PDF export.
 *
 * Text is measured with android.text.StaticLayout using linear, sub-pixel, unhinted metrics (the same settings the print
 * view gives Compose through TextMotion.Animated + LineBreak.Simple), so line breaks found here at [UNIT] px per point
 * are the ones Compose finds at any zoom. Line pitch is fixed per paragraph ([lineHeight]) so heights are exact.
 * Everything here is in points; the content box origin is the top-left inside the page margins.
 */

/** Pixels per point used for measuring and for drawing into the PDF (drawn with a 1/UNIT canvas scale). */
internal const val UNIT = 8f

/**
 * Pagination measures text slightly narrower than it is drawn. With greedy line breaking a narrower column never needs
 * fewer lines, so what the screen / PDF draws for a slice is never taller than predicted (pixel rounding can't overflow a page).
 */
internal const val SAFETY = 0.75f

/** Line pitch actually used by the print layout / PDF (snapped to 1/8 pt so the PDF draws integral pixels). */
internal fun lineHeight(p: DocBlock.Para): Float = floor(p.lineHeightPt * UNIT) / UNIT

/** Spacing rules shared by [Paginator] and the renderers. */
internal object Flow {
    const val IMAGE_PAD = 4f
    const val RULE_PAD = 6f
    const val TABLE_GAP = 6f
    const val NESTED_TABLE_GAP = 3f
}

sealed class Slice {
    /** Space above the slice (pt). */
    abstract val gap: Float
    /** Height of the slice's own content (pt). */
    abstract val height: Float
    /** Space after the slice (pt). */
    abstract val after: Float
}

/** Characters [start, end) of [para]; [first] = contains the paragraph's first line (marker, first-line indent). */
class TextSlice(
    val para: DocBlock.Para, val start: Int, val end: Int, val first: Boolean, val last: Boolean,
    override val gap: Float, override val height: Float, override val after: Float,
) : Slice()

class ImageSlice(val image: DocBlock.Image, val w: Float, val h: Float, override val gap: Float, override val after: Float) : Slice() {
    override val height get() = h
}

/** Rows of [table] drawn on this page (header rows repeated first when continuing), with their heights. */
class TableSlice(
    val table: DocBlock.Table, val rows: IntArray, val rowHeights: FloatArray, val colWidths: FloatArray,
    override val gap: Float, override val after: Float,
) : Slice() {
    override val height: Float get() = rowHeights.sum()
}

class RuleSlice(override val gap: Float, override val after: Float) : Slice() {
    override val height get() = 1f
}

class LaidPage(val index: Int, val slices: List<Slice>)

/** Builds StaticLayouts for paragraphs (or parts of them) in pixels at a given scale. Not thread-safe: one per thread. */
internal class TextEngine(private val darkInk: Int = 0xFF1F2937.toInt(), private val linkColor: Int = 0xFF2563EB.toInt()) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        hinting = Paint.HINTING_OFF
        typeface = Typeface.DEFAULT
        color = darkInk
    }

    /** Text shown for [p] in the print layout / PDF: tabs become an em space (keeps offsets; no tab stops in Compose). */
    fun displayText(p: DocBlock.Para, start: Int, end: Int): String = p.text.substring(start, end).replace('\t', ' ')

    fun spanned(p: DocBlock.Para, start: Int, end: Int, unit: Float, withColors: Boolean): SpannableString {
        val s = SpannableString(displayText(p, start, end))
        val len = s.length
        for (sp in p.spans) {
            val a = max(sp.start, start) - start
            val b = min(sp.end, end) - start
            if (a >= b || b > len) continue
            val f = sp.fmt
            val size = f.sizePt * (if (f.vert != 0) 0.7f else 1f) * unit
            s.setSpan(SizeSpan(size), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val style = (if (f.bold) Typeface.BOLD else 0) or (if (f.italic) Typeface.ITALIC else 0)
            if (style != 0) s.setSpan(StyleSpan(style), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.mono) s.setSpan(TypefaceSpan("monospace"), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else WordFonts.typeface(f.font)?.let { s.setSpan(FaceSpan(it), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (f.vert == 1) s.setSpan(SuperscriptSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.vert == 2) s.setSpan(SubscriptSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (withColors) {
                if (f.underline || sp.link != null) s.setSpan(UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (f.strike) s.setSpan(StrikethroughSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val col = f.color ?: if (sp.link != null) linkColor else null
                if (col != null && !nearWhite(col)) s.setSpan(ForegroundColorSpan(col), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                f.background?.let { s.setSpan(BackgroundColorSpan(it), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
        }
        return s
    }

    private fun nearWhite(c: Int): Boolean = ((c shr 16) and 0xFF) > 235 && ((c shr 8) and 0xFF) > 235 && (c and 0xFF) > 235

    /** Width available to the text of [p] inside a column of [colW] points. */
    fun textWidth(p: DocBlock.Para, colW: Float): Float =
        (colW - p.boxStartPt - p.indEnd - (if (p.marker != null) p.markerWidth else 0f) - boxPadStart(p.box) - boxPadEnd(p.box)).coerceAtLeast(24f)

    /**
     * Layout of characters [start, end) of [p] at [unit] px per point, [widthPt] wide. [first] applies the first-line
     * indent; continuation slices indent every line like the paragraph's rest lines.
     */
    fun layout(p: DocBlock.Para, start: Int, end: Int, first: Boolean, widthPt: Float, unit: Float, withColors: Boolean = false): StaticLayout {
        val text = spanned(p, start, end, unit, withColors)
        val lh = floor(lineHeight(p) * unit).toInt().coerceAtLeast(1)
        val hang = p.hangPt
        val firstInd = if (first) max(p.firstLine, 0f) else hang
        text.setSpan(FixedLineHeight(lh), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (firstInd > 0f || hang > 0f) {
            text.setSpan(LeadingMarginSpan.Standard(ceil(firstInd * unit).toInt(), ceil(hang * unit).toInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        paint.textSize = p.basePt * unit
        val w = max(1, (widthPt * unit).toInt())
        val align = when (p.align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(align)
            .setTextDirection(when (textDir(p, first)) { 1 -> TextDirectionHeuristics.RTL; -1 -> TextDirectionHeuristics.LTR; else -> TextDirectionHeuristics.FIRSTSTRONG_LTR })
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (p.align == 3) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    /** One-line layout for a list marker. */
    fun markerLayout(p: DocBlock.Para, unit: Float, withColors: Boolean): StaticLayout {
        val m = p.marker ?: ""
        val s = SpannableString(m)
        val f = p.markerFmt
        if (f != null && m.isNotEmpty()) {
            s.setSpan(SizeSpan(f.sizePt * unit), 0, m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.bold) s.setSpan(StyleSpan(Typeface.BOLD), 0, m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (withColors) f.color?.let { if (!nearWhite(it)) s.setSpan(ForegroundColorSpan(it), 0, m.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val lh = floor(lineHeight(p) * unit).toInt().coerceAtLeast(1)
        s.setSpan(FixedLineHeight(lh), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        paint.textSize = p.basePt * unit
        val w = max(1, (p.markerWidth * unit * 3).toInt())
        return StaticLayout.Builder.obtain(s, 0, s.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (p.rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setIncludePad(false).setMaxLines(1).build()
    }

    /** Height in points of a whole paragraph laid out in a column [colW] wide (spacing included). */
    fun paraHeight(p: DocBlock.Para, colW: Float, atTop: Boolean): Float {
        val n = max(1, layout(p, 0, p.text.length, true, textWidth(p, colW) - SAFETY, UNIT).lineCount)
        return (if (atTop) 0f else p.before) + 2 * boxPadV(p.box) + n * lineHeight(p) + p.after
    }
}

/**
 * Base direction for (part of) a paragraph: 1 RTL, -1 LTR, 0 first-strong per line. A continuation slice of a single
 * LTR paragraph keeps LTR even if it starts with Arabic; multi-line text chunks (txt / log / code) stay per line.
 * The print renderer uses the same rule.
 */
internal fun textDir(p: DocBlock.Para, first: Boolean): Int = when {
    p.rtl -> 1
    !first && p.text.indexOf('\n') < 0 -> -1
    else -> 0
}

/** Sets the text size in (float) pixels; AbsoluteSizeSpan would round to whole pixels. */
internal class SizeSpan(private val px: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) { p.textSize = px }
    override fun updateDrawState(p: TextPaint) { p.textSize = px }
}

/** Forces every line to [h] px, distributing the extra space like Compose's LineHeightStyle (proportional, no trim). */
internal class FixedLineHeight(private val h: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val cur = fm.descent - fm.ascent
        if (cur <= 0) return
        val ratio = fm.descent.toFloat() / cur
        val descent = ceil(fm.descent + (h - cur) * ratio).toInt()
        fm.descent = descent
        fm.ascent = descent - h
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

/** Column widths (pt) of [t] fitted to [avail] points. */
internal fun tableColumns(t: DocBlock.Table, avail: Float): FloatArray {
    val cols = max(t.grid.size, t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1).coerceAtLeast(1)
    val raw = FloatArray(cols) { i -> t.grid.getOrNull(i)?.takeIf { it > 0f } ?: 0f }
    if (raw.all { it == 0f }) return FloatArray(cols) { avail / cols }
    val known = raw.filter { it > 0f }
    val fill = if (known.isNotEmpty()) known.average().toFloat() else avail / cols
    for (i in raw.indices) if (raw[i] == 0f) raw[i] = fill
    val total = raw.sum()
    val k = if (total > avail) avail / total else 1f
    return FloatArray(cols) { max(raw[it] * k, 12f) }
}

/** Width of cell [cellIndex] of [row] given the grid columns. */
internal fun cellWidth(row: DocBlock.Row, cellIndex: Int, cols: FloatArray): Float {
    var c = 0
    for (i in 0 until cellIndex) c += row.cells[i].span
    var w = 0f
    val span = row.cells[cellIndex].span
    for (k in c until min(cols.size, c + span)) w += cols[k]
    if (w <= 0f) w = cols.average().toFloat() * span
    return w
}

/**
 * Lays blocks out on pages. Call [add] for blocks in order, then [finish]. With [onPage] set, pages are handed over
 * as soon as they are complete and not kept (PDF export); otherwise [pages] collects them.
 */
internal class Paginator(
    val spec: PageSpec,
    private val engine: TextEngine = TextEngine(),
    private val onPage: ((LaidPage) -> Unit)? = null,
    private val cancelled: () -> Boolean = { false },
) {
    val pages = ArrayList<LaidPage>()
    var pageCount = 0
        private set
    private var cur = ArrayList<Slice>()
    private var y = 0f
    private val W = spec.contentW
    private val H = spec.contentH

    class Cancelled : RuntimeException()

    private fun newPage() {
        val p = LaidPage(pageCount++, cur)
        if (onPage != null) onPage.invoke(p) else pages.add(p)
        cur = ArrayList()
        y = 0f
    }

    fun finish() {
        if (cur.isNotEmpty() || pageCount == 0) newPage()
    }

    fun addAll(blocks: List<DocBlock>) {
        for (i in blocks.indices) {
            if (cancelled()) throw Cancelled()
            add(blocks[i], blocks.getOrNull(i + 1))
        }
    }

    fun add(b: DocBlock, next: DocBlock? = null) {
        when (b) {
            is DocBlock.Para -> addPara(b, next)
            is DocBlock.Image -> addImage(b)
            is DocBlock.Table -> addTable(b)
            DocBlock.Divider -> {
                val need = Flow.RULE_PAD * 2 + 1f
                if (y > 0f && y + need > H) newPage()
                cur.add(RuleSlice(if (y > 0f) Flow.RULE_PAD else 0f, Flow.RULE_PAD))
                y += (if (y > 0f) Flow.RULE_PAD else 0f) + 1f + Flow.RULE_PAD
            }
            DocBlock.PageBreak -> newPage()
        }
    }

    /** Rough minimum height the block after a heading needs on the same page (keep-with-next). */
    private fun minHeightOf(b: DocBlock?): Float = when (b) {
        is DocBlock.Para -> b.before + 2 * boxPadV(b.box) + 2 * lineHeight(b)
        is DocBlock.Image -> min(b.heightPt * min(1f, W / max(1f, b.widthPt)), H / 2)
        is DocBlock.Table -> Flow.TABLE_GAP + 28f
        else -> 0f
    }

    private fun addPara(p: DocBlock.Para, next: DocBlock?) {
        val lh = lineHeight(p)
        val padV = boxPadV(p.box)
        val tw = engine.textWidth(p, W) - SAFETY
        val layout = engine.layout(p, 0, p.text.length, true, tw, UNIT)
        val n = max(1, layout.lineCount)
        var startLine = 0
        while (true) {
            val first = startLine == 0
            val gap = if (first && y > 0f) p.before else 0f
            val remaining = n - startLine
            val whole = gap + 2 * padV + remaining * lh
            if (y + whole <= H + 0.01f) {
                if (first && p.heading > 0 && y > 0f && next != null && y + whole + p.after + minHeightOf(next) > H) {
                    newPage(); continue
                }
                cur.add(TextSlice(p, layout.getLineStart(startLine).coerceAtMost(p.text.length), p.text.length, first, true, gap, 2 * padV + remaining * lh, p.after))
                y += whole + p.after
                return
            }
            var fit = floor((H - y - gap - 2 * padV + 0.01f) / lh).toInt().coerceAtLeast(0)
            if (p.heading > 0 || remaining <= 2) fit = 0
            else {
                if (first && fit == 1) fit = 0               // orphan: never leave a lone first line
                if (fit > 0 && remaining - fit == 1) fit -= 1 // widow: never carry a lone last line
            }
            if (fit <= 0) {
                if (y > 0f) { newPage(); continue }
                // Empty page and still too tall (giant line height / font): force what fits.
                fit = max(1, floor((H - 2 * padV) / lh).toInt())
                if (fit >= remaining) {
                    cur.add(TextSlice(p, layout.getLineStart(startLine), p.text.length, first, true, 0f, 2 * padV + remaining * lh, p.after))
                    y += 2 * padV + remaining * lh + p.after
                    return
                }
            }
            val s = layout.getLineStart(startLine)
            val e = layout.getLineStart(startLine + fit)
            cur.add(TextSlice(p, s, e, first, false, gap, 2 * padV + fit * lh, 0f))
            newPage()
            startLine += fit
        }
    }

    private fun addImage(img: DocBlock.Image) {
        var w = max(img.widthPt, 4f); var h = max(img.heightPt, 4f)
        if (w > W) { h *= W / w; w = W }
        val maxH = H - 2 * Flow.IMAGE_PAD
        if (h > maxH) { w *= maxH / h; h = maxH }
        val gap = if (y > 0f) Flow.IMAGE_PAD else 0f
        if (y > 0f && y + gap + h > H) newPage()
        val g = if (y > 0f) Flow.IMAGE_PAD else 0f
        cur.add(ImageSlice(img, w, h, g, Flow.IMAGE_PAD))
        y += g + h + Flow.IMAGE_PAD
    }

    /** Height of one table row with cells laid out in [cols]. */
    fun rowHeight(row: DocBlock.Row, cols: FloatArray): Float {
        var h = 0f
        row.cells.forEachIndexed { i, cell ->
            if (cell.merged) return@forEachIndexed
            val inner = cellWidth(row, i, cols) - 2 * CELL_PAD_H
            h = max(h, flowHeight(cell.blocks, inner) + 2 * CELL_PAD_V)
        }
        return max(h, 2 * CELL_PAD_V + 8f)
    }

    /** Height of blocks stacked in a column (table cells), using the same spacing rules as the renderers. */
    fun flowHeight(blocks: List<DocBlock>, width: Float): Float {
        var h = 0f
        blocks.forEachIndexed { i, b ->
            h += when (b) {
                is DocBlock.Para -> engine.paraHeight(b, width, atTop = i == 0)
                is DocBlock.Image -> {
                    val w = max(b.widthPt, 4f)
                    b.heightPt * min(1f, width / w) + 2 * Flow.IMAGE_PAD
                }
                is DocBlock.Table -> {
                    val cols = tableColumns(b, width)
                    b.rows.sumOf { rowHeight(it, cols).toDouble() }.toFloat() + 2 * Flow.NESTED_TABLE_GAP
                }
                DocBlock.Divider, DocBlock.PageBreak -> 2 * Flow.RULE_PAD + 1f
            }
        }
        return h
    }

    private fun addTable(t: DocBlock.Table) {
        if (t.rows.isEmpty()) return
        val cols = tableColumns(t, W)
        val hdr = t.headerRows.coerceIn(0, t.rows.size - 1)
        val headerHeights = FloatArray(hdr) { rowHeight(t.rows[it], cols) }
        val hdrH = headerHeights.sum()
        var rows = ArrayList<Int>()
        var heights = ArrayList<Float>()
        var gap = if (y > 0f) Flow.TABLE_GAP else 0f
        var used = gap
        fun flush(last: Boolean) {
            if (rows.isEmpty()) return
            cur.add(TableSlice(t, rows.toIntArray(), heights.toFloatArray(), cols, gap, if (last) Flow.TABLE_GAP else 0f))
            y += used + (if (last) Flow.TABLE_GAP else 0f)
            rows = ArrayList(); heights = ArrayList()
        }
        for (r in t.rows.indices) {
            if (cancelled()) throw Cancelled()
            val rh = if (r < hdr) headerHeights[r] else rowHeight(t.rows[r], cols)
            val fits = y + used + rh <= H + 0.01f
            // Something other than repeated header rows is already on this page → the row may move to the next page.
            val hasBody = rows.any { it >= hdr } || (r < hdr && rows.isNotEmpty())
            if (!fits && (y > 0f || hasBody)) {
                // Never leave the header alone at the bottom of a page: it moves down with the first body row.
                val onlyHeader = rows.isNotEmpty() && rows.all { it < hdr } && r >= hdr
                if (onlyHeader) { rows.clear(); heights.clear(); used = gap }
                flush(false)
                newPage()
                gap = 0f; used = 0f
                if (hdr > 0 && r >= hdr && hdrH + rh <= H) {
                    for (k in 0 until hdr) { rows.add(k); heights.add(headerHeights[k]) }
                    used += hdrH
                }
            }
            rows.add(r); heights.add(rh); used += rh
        }
        flush(true)
    }

    companion object {
        /** Lays out a whole document; [onProgress] gets each finished page (pagination runs on a background thread). */
        fun layoutAll(doc: DocxDoc, cancelled: () -> Boolean = { false }, onProgress: ((Int) -> Unit)? = null): List<LaidPage> {
            val out = ArrayList<LaidPage>()
            val pg = Paginator(doc.page, TextEngine(), onPage = { p -> out.add(p); onProgress?.invoke(out.size) }, cancelled = cancelled)
            pg.addAll(doc.blocks)
            pg.finish()
            return out
        }
    }
}

/** Page and char-offset lookup for find in the print layout. */
internal fun pageOf(pages: List<LaidPage>, pid: Int, offset: Int): Int {
    fun inBlocks(blocks: List<DocBlock>): Boolean = blocks.any { b ->
        when (b) {
            is DocBlock.Para -> b.pid == pid
            is DocBlock.Table -> b.rows.any { r -> r.cells.any { c -> inBlocks(c.blocks) } }
            else -> false
        }
    }
    for (pg in pages) for (s in pg.slices) {
        when (s) {
            is TextSlice -> if (s.para.pid == pid && offset >= s.start && (offset < s.end || (s.last && offset <= s.end))) return pg.index
            is TableSlice -> for (r in s.rows) if (inBlocks(s.table.rows[r].cells.flatMap { it.blocks })) return pg.index
            else -> {}
        }
    }
    return -1
}

internal fun Float.pt2px(unit: Float) = (this * unit).roundToInt()
