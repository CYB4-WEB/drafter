package com.daftar.app.onenote

import java.io.File
import java.io.RandomAccessFile

// Pure JVM model of an opened OneNote file (no Android imports: the parser core is compiled and run on the JVM for tests).
// All geometry is in PDF points (1/72 inch). OneNote stores layout in half-inch units (×36) and ink in HIMETRIC.

/** Why a file or a section could not be shown. */
enum class OneError {
    /** Password-protected section (data is encrypted). */
    ENCRYPTED,
    /** OneNote cloud/FSSHTTP packaging (files synced from OneDrive / SharePoint, Office 365 downloads). */
    CLOUD,
    /** A notebook table of contents (.onetoc2): only lists sections, holds no pages. */
    TOC,
    /** OneNote 2003/2007 or an unknown variant of the format. */
    OLD_FORMAT,
    /** Not a OneNote file at all. */
    NOT_ONENOTE,
    /** Structure is damaged or uses something this reader does not understand. */
    CORRUPT,
    /** .onepkg compressed with LZX / Quantum (only stored and MSZIP packages are supported). */
    PACKAGE_COMPRESSION,
    /** .onepkg without any readable section. */
    EMPTY_PACKAGE,
    /** File could not be read (missing, permission, I/O). */
    IO,
}

class OneException(val kind: OneError, message: String? = null, cause: Throwable? = null) : Exception(message ?: kind.name, cause)

/** Bytes stored somewhere inside a file on disk (read on demand so big notebooks never sit in memory). */
class Blob(val path: String, val offset: Long, val length: Long) {
    fun read(limit: Long = 64L * 1024 * 1024): ByteArray? {
        if (length <= 0 || length > limit) return null
        return runCatching {
            RandomAccessFile(path, "r").use { f ->
                if (offset + length > f.length()) return null
                val b = ByteArray(length.toInt())
                f.seek(offset)
                f.readFully(b)
                b
            }
        }.getOrNull()
    }

    /** Short signature used to guess the type ("png", "jpg", "gif", "bmp", "emf", "wmf", "tif" or null). */
    fun imageType(): String? {
        val head = runCatching {
            RandomAccessFile(path, "r").use { f -> f.seek(offset); val b = ByteArray(minOf(length, 48L).toInt()); f.readFully(b); b }
        }.getOrNull() ?: return null
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            at(0) == 0x42 && at(1) == 0x4D -> "bmp"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> "webp"
            at(0) == 0x01 && at(1) == 0 && at(2) == 0 && at(3) == 0 && at(40) == 0x20 && at(41) == 0x45 -> "emf"
            at(0) == 0xD7 && at(1) == 0xCD && at(2) == 0xC6 && at(3) == 0x9A -> "wmf"
            (at(0) == 0x49 && at(1) == 0x49) || (at(0) == 0x4D && at(1) == 0x4D) -> "tif"
            else -> null
        }
    }
}

/** One formatted piece of a paragraph. [size] in pt (0 = paragraph default); colours are ARGB (null = automatic). */
class OneRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val size: Float = 0f,
    val color: Int? = null,
    val highlight: Int? = null,
    val link: String? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val font: String? = null,
)

class OneImage(val data: Blob?, val widthPt: Float, val heightPt: Float, val alt: String?, val name: String?)

/** Ink stroke: [pts] = x, y pairs in pt relative to the ink's origin; [width] in pt; [color] ARGB (alpha < 255 = highlighter). */
class OneStroke(val pts: FloatArray, val width: Float, val color: Int, val highlighter: Boolean)

class OneInk(val strokes: List<OneStroke>) {
    /** Bounds of all points (relative to the ink origin): minX, minY, maxX, maxY; zeros when empty. */
    val bounds: FloatArray by lazy {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.pts.size) {
                val x = s.pts[i]; val y = s.pts[i + 1]
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                i += 2
            }
        }
        if (x0 > x1) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf(x0, y0, x1, y1)
    }
}

class OneAttachment(val name: String, val data: Blob?)

/** Content of an outline (a OneNote "note container"). [indent] = outline level (0 = top). */
sealed class OneBlock {
    abstract val indent: Int
}

/** [list] = bullet / number label ("•", "3.", "b)") or null; [align] 0 start, 1 centre, 2 end; [style] OneNote style id ("h1", "p", "code"…). */
class OnePara(
    override val indent: Int,
    val runs: List<OneRun>,
    val list: String? = null,
    val align: Int = 0,
    val style: String? = null,
    val rtl: Boolean = false,
) : OneBlock() {
    val text: String by lazy { runs.joinToString("") { it.text } }
}

class OneTable(override val indent: Int, val rows: List<List<List<OneBlock>>>, val columnWidths: List<Float>, val borders: Boolean) : OneBlock()
class OneImageBlock(override val indent: Int, val image: OneImage) : OneBlock()
class OneInkBlock(override val indent: Int, val ink: OneInk) : OneBlock()
class OneFileBlock(override val indent: Int, val file: OneAttachment) : OneBlock()

/** Something placed on the page canvas at ([x], [y]) pt. */
sealed class OneItem {
    abstract val x: Float
    abstract val y: Float
}

/** [width] = layout width in pt (0 = automatic). */
class OneOutline(override val x: Float, override val y: Float, val width: Float, val blocks: List<OneBlock>) : OneItem()
class OneImageItem(override val x: Float, override val y: Float, val image: OneImage) : OneItem()
class OneInkItem(override val x: Float, override val y: Float, val ink: OneInk) : OneItem()
class OneFileItem(override val x: Float, override val y: Float, val file: OneAttachment) : OneItem()

/**
 * One page. [created] / [modified] in epoch ms (0 = unknown). [level] 1 = page, 2-3 = sub-pages.
 * [titleX]/[titleY] = where the title sits on the canvas.
 */
class OnePage(
    val title: String,
    val created: Long,
    val modified: Long,
    val level: Int,
    val items: List<OneItem>,
    val widthPt: Float,
    val heightPt: Float,
    val titleX: Float = 36f,
    val titleY: Float = 24f,
) {
    /** Every paragraph of the page (title excluded), in reading order, including table cells. */
    fun paragraphs(): List<OnePara> {
        val out = ArrayList<OnePara>()
        fun walk(blocks: List<OneBlock>) {
            for (b in blocks) when (b) {
                is OnePara -> out.add(b)
                is OneTable -> b.rows.forEach { r -> r.forEach { c -> walk(c) } }
                else -> {}
            }
        }
        items.sortedWith(compareBy({ it.y }, { it.x })).forEach { if (it is OneOutline) walk(it.blocks) }
        return out
    }

    fun plainText(): String = buildString {
        append(title).append('\n')
        fun walk(blocks: List<OneBlock>) {
            for (b in blocks) when (b) {
                is OnePara -> {
                    append("    ".repeat(b.indent.coerceIn(0, 8)))
                    if (b.list != null) append(b.list).append(' ')
                    append(b.text).append('\n')
                }
                is OneTable -> b.rows.forEach { r ->
                    append(r.joinToString("\t") { c -> c.filterIsInstance<OnePara>().joinToString(" ") { it.text } }).append('\n')
                }
                is OneFileBlock -> append("[").append(b.file.name).append("]\n")
                else -> {}
            }
        }
        for (it in items.sortedWith(compareBy({ it.y }, { it.x }))) when (it) {
            is OneOutline -> { walk(it.blocks); append('\n') }
            is OneFileItem -> append("[").append(it.file.name).append("]\n")
            else -> {}
        }
    }
}

/** A section (.one file). [error] set when the section exists but could not be read (e.g. password protected). */
class OneSection(val name: String, val pages: List<OnePage>, val error: OneError? = null, val source: File? = null)

/** What the viewer shows: one section for a .one file, all sections for a .onepkg. */
class OneBook(val name: String, val sections: List<OneSection>) {
    val pageCount get() = sections.sumOf { it.pages.size }
}
