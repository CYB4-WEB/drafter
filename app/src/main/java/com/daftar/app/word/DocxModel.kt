package com.daftar.app.word

/**
 * Parsed .docx content. Pure Kotlin (no Compose types) so it can be built on a background thread
 * and turned into AnnotatedStrings lazily while scrolling.
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

sealed class DocBlock {
    /** One paragraph. Dimensions are in dp-like units already (converted from twips). */
    class Para(
        val pid: Int,
        val text: String,
        val spans: List<Span>,
        /** Paragraph default font size (pt); span sizes are rendered relative to it. */
        val basePt: Float,
        /** 0 start, 1 center, 2 end, 3 justify. */
        val align: Int,
        val rtl: Boolean,
        val indStart: Float,
        val indEnd: Float,
        /** First-line indent; negative = hanging (only used when there is no list marker). */
        val firstLine: Float,
        val marker: String?,
        val markerFmt: RunFmt?,
        /** Width reserved for the list marker (hanging indent). */
        val markerWidth: Float,
        val before: Float,
        val after: Float,
        /** Line spacing multiplier (1.0 = single). */
        val lineMult: Float,
        /** 0 = body, 1..6 heading level (Title counts as 1). */
        val heading: Int,
    ) : DocBlock()

    /** An inline picture, shown as its own block. [entry] is the zip entry, size in dp. */
    class Image(val entry: String, val widthDp: Float, val heightDp: Float, val alt: String, val align: Int, val rtl: Boolean) : DocBlock()

    class Table(
        val rows: List<Row>,
        /** Grid column widths in dp (may be empty → equal columns). */
        val grid: List<Float>,
        val rtl: Boolean,
        val borders: Boolean,
    ) : DocBlock()

    /** Page break / footnote separator. */
    data object Divider : DocBlock()

    class Row(val cells: List<Cell>)

    class Cell(val span: Int, val blocks: List<DocBlock>, val fill: Int?, val merged: Boolean)
}

class Heading(val text: String, val level: Int, val blockIndex: Int)

class DocxDoc(
    val path: String,
    val blocks: List<DocBlock>,
    val headings: List<Heading>,
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
        fun walk(b: DocBlock) {
            when (b) {
                is DocBlock.Para -> { if (b.marker != null) append(b.marker).append(' '); append(b.text).append('\n') }
                is DocBlock.Table -> b.rows.forEach { r ->
                    append(r.cells.filter { !it.merged }.joinToString("\t") { c ->
                        buildString { c.blocks.forEach { cb -> if (cb is DocBlock.Para) { if (isNotEmpty()) append(' '); append(cb.text) } } }
                    }).append('\n')
                }
                is DocBlock.Divider -> append('\n')
                is DocBlock.Image -> {}
            }
        }
        blocks.forEach(::walk)
    }.trimEnd()
}
