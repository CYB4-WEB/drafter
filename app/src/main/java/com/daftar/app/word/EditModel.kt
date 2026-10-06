package com.daftar.app.word

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger

/*
 * Editing model. Blocks are immutable: every edit makes a new block (structural sharing), so undo/redo are just
 * previous block lists and the saver can read a snapshot from a background thread. A block that still carries [raw]
 * is untouched and is written back byte-for-byte.
 */

/** Object replacement character: one preserved inline item (picture, field, footnote reference, equation…). */
internal const val ATOM = '￼'
/** Line break inside a paragraph (w:br). '\n' never appears in paragraph text: it splits paragraphs. */
internal const val SOFT_BREAK = ' '

/** Direct run formatting (null = not set on the run → inherited from the styles). */
internal data class CFmt(
    val b: Boolean? = null,
    val i: Boolean? = null,
    val u: Boolean? = null,
    val strike: Boolean? = null,
    /** Size in half-points (w:sz). */
    val sizeHalf: Int? = null,
    /** "RRGGBB" or "auto". */
    val color: String? = null,
    /** Word highlight name ("yellow", …) or "none". */
    val highlight: String? = null,
    /** Font name as written in w:rFonts. */
    val font: String? = null,
    /** Original `w:rPr` element (other properties are kept from it). */
    val rPr: String? = null,
    /** Original `w:hyperlink` start tag when the run is inside a link. */
    val link: String? = null,
    /** Index into [AtomTable] when this character is an [ATOM]. */
    val atom: Int = -1,
)

/** Interned run formats: paragraphs store one Int per character. Append-only and thread-safe. */
internal class FmtTable {
    private val list = ArrayList<CFmt>()
    private val map = HashMap<CFmt, Int>()

    init { intern(CFmt()) }

    @Synchronized fun intern(f: CFmt): Int = map.getOrPut(f) { list.add(f); list.size - 1 }
    @Synchronized operator fun get(i: Int): CFmt = if (i in list.indices) list[i] else list[0]
}

internal enum class AKind { IMAGE, FIELD, NOTE, BREAK, EQUATION, OTHER }

/** A preserved inline item: [raw] is written back as is; [display] is what the editor shows for it. */
internal class Atom(val raw: String, val kind: AKind, val display: String)

internal class AtomTable {
    private val list = ArrayList<Atom>()
    @Synchronized fun add(a: Atom): Int { list.add(a); return list.size - 1 }
    @Synchronized operator fun get(i: Int): Atom? = list.getOrNull(i)
}

/** Paragraph properties the editor manages (everything else in the original w:pPr is preserved). */
internal data class PProps(
    val style: String? = null,
    /** w:jc value: left / center / right / both (or whatever the document used). */
    val jc: String? = null,
    val bidi: Boolean? = null,
    /** Direct numbering; numId "0" = explicitly no list. */
    val numId: String? = null,
    val ilvl: Int? = null,
    /** Start indent in twips (w:ind left/start). */
    val indLeft: Int? = null,
)

internal object EIds {
    private val n = AtomicInteger(1)
    fun next(): Int = n.getAndIncrement()
}

internal sealed class EBlock { abstract val id: Int }

internal class EPara(
    override val id: Int,
    val text: String,
    /** Format index per character (same length as [text]). */
    val fmts: IntArray,
    val props: PProps = PProps(),
    /** Original w:pPr element (null for new paragraphs). */
    val pPr: String? = null,
    val startTag: String = "<w:p>",
    /** Bookmark / permission markers kept at the start / end of the paragraph. */
    val lead: String = "",
    val trail: String = "",
    /** Original XML while the paragraph is untouched. */
    val raw: String? = null,
) : EBlock() {
    fun edit(text: String = this.text, fmts: IntArray = this.fmts, props: PProps = this.props): EPara =
        EPara(id, text, fmts, props, pPr, startTag, lead, trail, null)

    /** A new paragraph with this paragraph's properties (paraId / bookmarks are not copied). */
    fun sibling(text: String, fmts: IntArray, props: PProps = this.props): EPara =
        EPara(EIds.next(), text, fmts, props, pPr, "<w:p>", "", "", null)

    companion object {
        fun plain(text: String, fmt: Int = 0, props: PProps = PProps()) = EPara(EIds.next(), text, IntArray(text.length) { fmt }, props)
    }
}

internal class ECell(val head: String, val paras: List<EPara>)
internal class ERow(val head: String, val cells: List<ECell>)

internal class ETable(override val id: Int, val head: String, val rows: List<ERow>, val raw: String? = null) : EBlock() {
    fun withCells(rows: List<ERow>) = ETable(id, head, rows, null)
}

internal enum class OKind { IMAGE, PAGE_BREAK, RULE, LOCKED, HIDDEN }

/** Preserved, non-editable block (can be deleted unless HIDDEN). [preview] renders it. */
internal class EObject(override val id: Int, val kind: OKind, val raw: String, val preview: List<DocBlock>) : EBlock()

internal enum class EditKind { DOCX, TXT, MD }

internal class TextMeta(val charset: Charset, val bom: ByteArray, val newline: String, val trailingNewline: Boolean)

/** Package-level additions made while editing (styles, lists, pictures); written on the next save. */
internal class PkgAdditions(
    var nextAbs: Int, var nextNum: Int, var nextRel: Int, var nextDocPr: Int,
) {
    val styles = LinkedHashMap<String, String>()
    val abstractNums = ArrayList<String>()
    val nums = ArrayList<String>()
    /** zip entry name → source file. */
    val media = LinkedHashMap<String, File>()
    /** (id, type, target relative to word/). */
    val rels = ArrayList<Triple<String, String, String>>()
    var bulletAbs: Int = -1
    var numberAbs: Int = -1
    var bulletNum: String? = null

    class Pending(
        val styles: Map<String, String>, val abstractNums: List<String>, val nums: List<String>,
        val media: Map<String, File>, val rels: List<Triple<String, String, String>>,
    ) {
        val empty get() = styles.isEmpty() && abstractNums.isEmpty() && nums.isEmpty() && media.isEmpty() && rels.isEmpty()
    }

    @Synchronized fun snapshot() = Pending(LinkedHashMap(styles), ArrayList(abstractNums), ArrayList(nums), LinkedHashMap(media), ArrayList(rels))

    /** After a successful save these parts are in the file: don't add them again. */
    @Synchronized fun commit(p: Pending) {
        p.styles.keys.forEach { styles.remove(it) }
        abstractNums.removeAll(p.abstractNums.toSet())
        nums.removeAll(p.nums.toSet())
        p.media.keys.forEach { media.remove(it) }
        rels.removeAll(p.rels.toSet())
    }
}

internal class DocxPkg(
    val docPart: String,
    /** document.xml up to and including the body start tag. */
    val prefix: String,
    /** document.xml from the body end tag. */
    val suffix: String,
    val ns: String,
    val session: DocxParser.StyleSession,
    val add: PkgAdditions,
    val page: PageSpec,
)

/** Everything about an editing session that isn't the (changing) block list. */
internal class EditSource(
    val file: File,
    val kind: EditKind,
    val fmts: FmtTable = FmtTable(),
    val atoms: AtomTable = AtomTable(),
    val docx: DocxPkg? = null,
    val text: TextMeta? = null,
) {
    val writer by lazy { DocxWriter(this) }
    val page: PageSpec get() = docx?.page ?: PageSpec.A4Text
}

/** Iterates every paragraph (top level and in table cells) in document order. */
internal inline fun List<EBlock>.forEachPara(f: (EPara) -> Unit) {
    for (b in this) when (b) {
        is EPara -> f(b)
        is ETable -> for (r in b.rows) for (c in r.cells) for (p in c.paras) f(p)
        else -> {}
    }
}

/** Words in the document (paragraph text; atoms and objects don't count). */
internal fun wordCount(blocks: List<EBlock>): Int {
    var n = 0
    blocks.forEachPara { p ->
        var inWord = false
        for (c in p.text) {
            val w = Character.isLetterOrDigit(c) || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt()
            if (w && !inWord) n++
            inWord = w
        }
    }
    return n
}
