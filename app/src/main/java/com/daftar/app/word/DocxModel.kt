package com.daftar.app.word

/**
 * Document model shared by every format the Word viewer opens (docx, doc, rtf, md, txt, log, csv) and by the PDF export.
 * Pure Kotlin (no Compose types) so it is built on a background thread. All geometry is in **points** (1/72 inch):
 * the read layout converts with dp/sp per point × zoom, the print layout and the PDF use the page scale.
 */

/** Fully resolved character formatting for one run (style chain + direct formatting). */
data class RunFmt(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font size in points. */
    val sizePt: Float = 11f,
    /** ARGB or null = document default (theme ink). */
    val color: Int? = null,
    /** Background (highlight or run shading) ARGB or null. */
    val background: Int? = null,
    /** 0 = baseline, 1 = superscript, 2 = subscript. */
    val vert: Int = 0,
    val mono: Boolean = false,
)

/** A formatted range inside a paragraph's plain text. */
class Span(val start: Int, val end: Int, val fmt: RunFmt, val link: String?)

/** Page size and margins in points. */
data class PageSpec(
    val w: Float = 595.3f,
    val h: Float = 841.9f,
    val top: Float = 72f,
    val bottom: Float = 72f,
    val left: Float = 72f,
    val right: Float = 72f,
) {
    val contentW: Float get() = (w - left - right).coerceAtLeast(72f)
    val contentH: Float get() = (h - top - bottom).coerceAtLeast(72f)

    companion object {
        val A4 = PageSpec()
        /** Text formats: A4 with 2 cm margins, like a word processor's default for imported text. */
        val A4Text = PageSpec(top = 56.7f, bottom = 56.7f, left = 56.7f, right = 56.7f)
    }
}

/** Paragraph decoration used by Markdown / text formats. */
const val BOX_NONE = 0
const val BOX_CODE = 1
const val BOX_QUOTE = 2

sealed class DocBlock {
    /** One paragraph (or one segment of a paragraph split by an inline picture). */
    class Para(
        val pid: Int,
        val text: String,
        val spans: List<Span>,
        /** Paragraph default font size (pt); span sizes are rendered relative to it. */
        val basePt: Float,
        /** 0 start, 1 center, 2 end, 3 justify. */
        val align: Int,
        val rtl: Boolean,
        /** Indent of the text lines from the start side, pt (rest lines when hanging). */
        val indStart: Float,
        val indEnd: Float,
        /** First-line indent in pt; negative = hanging (only used when there is no list marker). */
        val firstLine: Float,
        val marker: String?,
        val markerFmt: RunFmt?,
        /** Width reserved for the list marker (hanging indent), pt. */
        val markerWidth: Float,
        /** Spacing before / after, pt. */
        val before: Float,
        val after: Float,
        /** Line spacing multiplier (1.0 = single). */
        val lineMult: Float,
        /** 0 = body, 1..6 heading level (Title counts as 1). */
        val heading: Int,
        /** [BOX_NONE], [BOX_CODE] (shaded, monospace) or [BOX_QUOTE] (bar at the start side). */
        val box: Int = BOX_NONE,
    ) : DocBlock() {
        /** Line height in points for the print layout / PDF (fixed per paragraph, like Word's "multiple" spacing). */
        val lineHeightPt: Float get() = basePt * LINE_FACTOR * lineMult
        /** Text box start offset (without the marker column), pt. */
        val hangPt: Float get() = -kotlin.math.min(0f, firstLine)
        val boxStartPt: Float get() = (indStart - hangPt).coerceAtLeast(0f)
    }

    /**
     * A picture shown as its own block. [entry] is the zip entry (docx) or an absolute file path when [external]
     * (Markdown images next to the file). Size in points.
     */
    class Image(
        val entry: String, val widthPt: Float, val heightPt: Float, val alt: String, val align: Int, val rtl: Boolean,
        val external: Boolean = false,
    ) : DocBlock()

    class Table(
        val rows: List<Row>,
        /** Grid column widths in points (may be empty → equal columns). */
        val grid: List<Float>,
        val rtl: Boolean,
        val borders: Boolean,
        /** Leading rows repeated at the top of each page (w:tblHeader / CSV header). */
        val headerRows: Int = 0,
    ) : DocBlock()

    /** Horizontal rule (footnote separator, Markdown `---`). */
    data object Divider : DocBlock()

    /** Hard page break: the print layout and the PDF start a new page; the read layout shows a rule. */
    data object PageBreak : DocBlock()

    class Row(val cells: List<Cell>)

    class Cell(val span: Int, val blocks: List<DocBlock>, val fill: Int?, val merged: Boolean)
}

/** Line height factor of "single" spacing relative to the font size (Roboto / Noto ≈ 1.17; Word's Calibri ≈ 1.22). */
const val LINE_FACTOR = 1.2f

/** Code / quote box paddings in points (shared by the screen and the PDF so pagination matches). */
fun boxPadV(box: Int): Float = when (box) { BOX_CODE -> 6f; BOX_QUOTE -> 2f; else -> 0f }
fun boxPadStart(box: Int): Float = when (box) { BOX_CODE -> 8f; BOX_QUOTE -> 12f; else -> 0f }
fun boxPadEnd(box: Int): Float = when (box) { BOX_CODE -> 8f; else -> 0f }

/** Table cell paddings in points (Word default: 0.08" left/right, 0 top/bottom; a little air for reading). */
const val CELL_PAD_H = 5.4f
const val CELL_PAD_V = 2f

class Heading(val text: String, val level: Int, val blockIndex: Int)

/** Which reader produced the document; drives defaults (layout, fonts) and notices. */
enum class DocKind { DOCX, DOC, RTF, MD, TXT, LOG, CSV }

/** Parsed CSV / TSV: rows of cells, first row = header. */
class Sheet(val rows: List<Array<String>>, val cols: Int, val rtl: Boolean) {
    /** Column widths in characters (sampled), used to size the grid. */
    val charWidths: IntArray by lazy {
        val w = IntArray(cols) { 3 }
        val step = if (rows.size > 400) rows.size / 400 else 1
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            for (c in r.indices) if (c < cols) w[c] = kotlin.math.max(w[c], kotlin.math.min(r[c].length, 48))
            i += step
        }
        rows.firstOrNull()?.forEachIndexed { c, s -> if (c < cols) w[c] = kotlin.math.max(w[c], kotlin.math.min(s.length, 48)) }
        w
    }
}

class DocxDoc(
    val path: String,
    val blocks: List<DocBlock>,
    val headings: List<Heading>,
    val page: PageSpec = PageSpec.A4,
    val kind: DocKind = DocKind.DOCX,
    /** CSV / TSV grid (then [blocks] is empty and the grid view is used). */
    val sheet: Sheet? = null,
    /** > 0 when only the first [truncatedAt] bytes of a huge file are shown. */
    val truncatedAt: Long = 0L,
) {
    /** Every paragraph in reading order with the index of the top-level block containing it. */
    val paragraphs: List<Pair<DocBlock.Para, Int>> by lazy {
        val out = ArrayList<Pair<DocBlock.Para, Int>>()
        fun walk(b: DocBlock, top: Int) {
            when (b) {
                is DocBlock.Para -> out.add(b to top)
                is DocBlock.Table -> b.rows.forEach { r -> r.cells.forEach { c -> c.blocks.forEach { walk(it, top) } } }
                else -> {}
            }
        }
        blocks.forEachIndexed { i, b -> walk(b, i) }
        out
    }

    fun plainText(): String = buildString {
        sheet?.let { s ->
            s.rows.forEach { r -> append(r.joinToString("\t")).append('\n') }
            return@buildString
        }
        fun walk(b: DocBlock) {
            when (b) {
                is DocBlock.Para -> { if (b.marker != null) append(b.marker).append(' '); append(b.text).append('\n') }
                is DocBlock.Table -> b.rows.forEach { r ->
                    append(r.cells.filter { !it.merged }.joinToString("\t") { c ->
                        buildString { c.blocks.forEach { cb -> if (cb is DocBlock.Para) { if (isNotEmpty()) append(' '); append(cb.text) } } }
                    }).append('\n')
                }
                DocBlock.Divider, DocBlock.PageBreak -> append('\n')
                is DocBlock.Image -> {}
            }
        }
        blocks.forEach(::walk)
    }.trimEnd()
}

/** Builds headings for the outline from top-level paragraphs. */
fun headingsOf(blocks: List<DocBlock>): List<Heading> {
    val out = ArrayList<Heading>()
    blocks.forEachIndexed { i, b ->
        if (b is DocBlock.Para && b.heading > 0 && b.text.isNotBlank()) out.add(Heading(b.text.trim().take(200), b.heading, i))
    }
    return out
}
