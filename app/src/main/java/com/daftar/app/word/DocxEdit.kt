package com.daftar.app.word

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** The file can't be edited here: [reason] = [REASON_FORMAT] (layout the editor can't keep) or [REASON_SIZE]. */
internal class NotEditableException(val reason: Int) : Exception() {
    companion object { const val REASON_FORMAT = 0; const val REASON_SIZE = 1 }
}

/**
 * Loading a document into the editing model and saving it back.
 *
 * DOCX: word/document.xml is split into top-level body elements with [XmlLite] (source offsets kept). Paragraphs made of
 * plain runs, hyperlinks, bookmarks, fields, pictures, footnote references and equations become editable [EPara]s
 * (fields / pictures / references as [ATOM]s); tables whose cells hold such paragraphs become [ETable]s; everything
 * else (tracked changes, comments, content controls, text boxes in odd places…) is an [EObject] written back verbatim.
 * Saving rewrites only document.xml (untouched blocks byte-for-byte) plus the parts that received additions.
 */
internal object DocxEdit {
    const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val REL_IMAGE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    private const val REL_NUMBERING = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering"
    private const val REL_STYLES = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
    private const val CT_NUMBERING = "application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"
    private const val CT_STYLES = "application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"
    private const val TEXT_EDIT_LIMIT = 4L * 1024 * 1024

    /** Editing kind for [f], or null when the format opens read-only. */
    fun kindFor(f: File): EditKind? = when (f.extension.lowercase()) {
        "docx", "docm" -> if (isZip(f)) EditKind.DOCX else null
        "txt" -> EditKind.TXT
        "md", "markdown" -> EditKind.MD
        else -> null
    }

    private fun isZip(f: File) = runCatching {
        f.inputStream().use { s -> val h = ByteArray(4); s.read(h) == 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() && h[2].toInt() == 3 && h[3].toInt() == 4 }
    }.getOrDefault(false)

    fun load(f: File): Pair<EditSource, List<EBlock>> = when (kindFor(f)) {
        EditKind.DOCX -> loadDocx(f)
        EditKind.TXT -> loadText(f, EditKind.TXT)
        EditKind.MD -> loadText(f, EditKind.MD)
        null -> throw NotEditableException(NotEditableException.REASON_FORMAT)
    }

    // ================================================================== DOCX load

    private fun relsName(part: String) = part.substringBeforeLast('/', "") + "/_rels/" + part.substringAfterLast('/') + ".rels"

    private fun readEntry(zip: ZipFile, name: String): String? = zip.getEntry(name)?.let { e ->
        val b = zip.getInputStream(e).use { it.readBytes() }
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) String(b, 3, b.size - 3, Charsets.UTF_8)
        else String(b, Charsets.UTF_8)
    }

    private fun mainPart(zip: ZipFile): String {
        if (zip.getEntry("word/document.xml") != null) return "word/document.xml"
        val rels = readEntry(zip, "_rels/.rels") ?: throw IllegalStateException("no document part")
        val m = Regex("<Relationship\\b[^>]*>").findAll(rels).firstOrNull { it.value.contains("/officeDocument\"") }
            ?: throw IllegalStateException("no document part")
        val t = Regex("Target=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: throw IllegalStateException("no target")
        return t.removePrefix("/")
    }

    fun loadDocx(f: File): Pair<EditSource, List<EBlock>> {
        val docPart: String
        val xml: String
        val numberingXml: String?
        val relsXml: String?
        ZipFile(f).use { zip ->
            docPart = mainPart(zip)
            xml = readEntry(zip, docPart) ?: throw IllegalStateException("no document part")
            numberingXml = readEntry(zip, "word/numbering.xml")
            relsXml = readEntry(zip, relsName(docPart))
        }
        val root = XmlLite.parse(xml, maxDepth = 2, keepBlank = false).filterIsInstance<XEl>().firstOrNull()
            ?: throw IllegalStateException("empty")
        // The editor writes "w:" / "r:" elements: the document must bind w to the transitional namespace.
        if (root.attrs.firstOrNull { it.first == "xmlns:w" }?.second != W_NS || root.qname != "w:document") {
            throw NotEditableException(NotEditableException.REASON_FORMAT)
        }
        val body = root.elements().firstOrNull { it.qname == "w:body" } ?: throw NotEditableException(NotEditableException.REASON_FORMAT)
        val ns = root.attrs.filter { it.first == "xmlns" || it.first.startsWith("xmlns:") }
            .joinToString(" ") { "${it.first}=\"${XmlLite.esc(it.second)}\"" }
        val prefix: String
        val suffix: String
        if (body.selfClosing) {
            prefix = xml.substring(0, body.start) + "<w:body>"
            suffix = "</w:body>" + xml.substring(body.end)
        } else {
            prefix = xml.substring(0, body.openEnd)
            suffix = xml.substring(body.innerEnd)
        }
        val session = DocxParser.StyleSession.open(f, docPart, ns)
        fun maxOf(re: Regex, text: String?): Int = text?.let { t -> re.findAll(t).maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } } ?: 0
        val add = PkgAdditions(
            nextAbs = maxOf(Regex("abstractNumId=\"(\\d+)\""), numberingXml) + 1,
            nextNum = maxOf(Regex("numId=\"(\\d+)\""), numberingXml) + 1,
            nextRel = maxOf(Regex("Id=\"rIdDft(\\d+)\""), relsXml) + 1,
            nextDocPr = max(maxOf(Regex("docPr\\b[^>]*?\\sid=\"(\\d+)\""), xml), 100) + 1,
        )
        val src0 = EditSource(f, EditKind.DOCX)
        val children = if (body.selfClosing) emptyList() else XmlLite.parse(xml, body.openEnd, body.innerEnd, maxDepth = 1, keepBlank = false).filterIsInstance<XEl>()
        var page = PageSpec.A4
        val loader = Loader(xml, src0.fmts, src0.atoms, session)
        val blocks = ArrayList<EBlock>(children.size)
        for (c in children) {
            if (c.local == "sectPr") page = sectionPage(XmlLite.parse(xml, c.start, c.end).first() as XEl)
            blocks.add(loader.block(c))
        }
        val pkg = DocxPkg(docPart, prefix, suffix, ns, session, add, page)
        val src = EditSource(f, EditKind.DOCX, src0.fmts, src0.atoms, pkg)
        if (blocks.none { it is EPara || it is ETable }) {
            // An empty body: give the user a paragraph to type in, before the section properties.
            val at = blocks.indexOfFirst { it is EObject && it.raw.startsWith("<w:sectPr") }.let { if (it < 0) blocks.size else it }
            blocks.add(at, EPara.plain(""))
        }
        return src to blocks
    }

    private fun sectionPage(s: XEl): PageSpec {
        var spec = PageSpec.A4
        s.child("pgSz")?.let { e ->
            val w = e.attr("w")?.toFloatOrNull()?.div(20f); val h = e.attr("h")?.toFloatOrNull()?.div(20f)
            if (w != null && h != null && w in 144f..2880f && h in 144f..2880f) spec = spec.copy(w = w, h = h)
        }
        s.child("pgMar")?.let { e ->
            fun m(k: String, d: Float) = e.attr(k)?.toFloatOrNull()?.let { kotlin.math.abs(it) / 20f }?.coerceIn(0f, 288f) ?: d
            spec = spec.copy(top = m("top", spec.top), bottom = m("bottom", spec.bottom), left = m("left", spec.left) + m("gutter", 0f), right = m("right", spec.right))
        }
        if (spec.w - spec.left - spec.right < 144f) spec = spec.copy(left = 54f, right = 54f)
        if (spec.h - spec.top - spec.bottom < 144f) spec = spec.copy(top = 54f, bottom = 54f)
        return spec
    }

    private class Locked : Exception()

    private class Loader(val src: String, val fmts: FmtTable, val atoms: AtomTable, val session: DocxParser.StyleSession) {

        fun block(shallow: XEl): EBlock {
            val raw = shallow.raw(src)
            return when (shallow.local) {
                "p" -> {
                    val el = XmlLite.parse(src, shallow.start, shallow.end).first() as XEl
                    try { para(el, raw, top = true) } catch (_: Locked) { locked(raw) }
                }
                "tbl" -> {
                    val el = XmlLite.parse(src, shallow.start, shallow.end).first() as XEl
                    try { table(el, raw) } catch (_: Locked) { locked(raw) }
                }
                "sectPr", "bookmarkStart", "bookmarkEnd", "permStart", "permEnd", "proofErr", "commentRangeStart", "commentRangeEnd",
                "moveFromRangeStart", "moveFromRangeEnd", "moveToRangeStart", "moveToRangeEnd", "customXmlInsRangeStart",
                "customXmlInsRangeEnd", "customXmlDelRangeStart", "customXmlDelRangeEnd" -> EObject(EIds.next(), OKind.HIDDEN, raw, emptyList())
                else -> locked(raw)
            }
        }

        fun locked(raw: String): EObject {
            val prev = session.parseFragment(raw)
            return EObject(EIds.next(), if (prev.isEmpty()) OKind.HIDDEN else OKind.LOCKED, raw, prev)
        }

        private fun onOff(e: XEl): Boolean = e.attr("val").let { it == null || !(it == "0" || it == "false" || it == "off") }

        fun cfmt(rPr: XEl?, rawRPr: String?, link: String?): CFmt {
            if (rPr == null) return CFmt(link = link)
            var f = CFmt(rPr = rawRPr, link = link)
            for (c in rPr.elements()) {
                f = when (c.local) {
                    "b" -> f.copy(b = onOff(c))
                    "bCs" -> if (f.b == null) f.copy(b = onOff(c)) else f
                    "i" -> f.copy(i = onOff(c))
                    "iCs" -> if (f.i == null) f.copy(i = onOff(c)) else f
                    "u" -> f.copy(u = c.attr("val").let { it != null && it != "none" })
                    "strike" -> f.copy(strike = onOff(c))
                    "sz" -> f.copy(sizeHalf = c.attr("val")?.toFloatOrNull()?.roundToInt())
                    "szCs" -> if (f.sizeHalf == null) f.copy(sizeHalf = c.attr("val")?.toFloatOrNull()?.roundToInt()) else f
                    "color" -> f.copy(color = c.attr("val"))
                    "highlight" -> f.copy(highlight = c.attr("val"))
                    "rFonts" -> f.copy(font = c.attr("ascii") ?: c.attr("hAnsi") ?: c.attr("cs"))
                    else -> f
                }
            }
            return f
        }

        fun props(pPr: XEl?): PProps {
            if (pPr == null) return PProps()
            var p = PProps()
            for (c in pPr.elements()) {
                p = when (c.local) {
                    "pStyle" -> p.copy(style = c.attr("val"))
                    "jc" -> p.copy(jc = c.attr("val"))
                    "bidi" -> p.copy(bidi = onOff(c))
                    "numPr" -> p.copy(numId = c.child("numId")?.attr("val"), ilvl = c.child("ilvl")?.attr("val")?.toIntOrNull())
                    "ind" -> p.copy(indLeft = (c.attr("left") ?: c.attr("start"))?.toFloatOrNull()?.roundToInt())
                    else -> p
                }
            }
            return p
        }

        /** Paragraph → EPara, or an EObject for picture-only / page-break-only / rule paragraphs. Throws [Locked]. */
        fun para(el: XEl, raw: String, top: Boolean): EBlock {
            val pPr = el.child("pPr")
            if (pPr != null && pPr.any { it.local == "pPrChange" }) throw Locked()
            val sb = StringBuilder()
            val fm = ArrayList<Int>()
            val lead = StringBuilder()
            val trail = StringBuilder()
            var fieldDepth = 0
            var fieldSep = false
            val fieldRaw = StringBuilder()
            val fieldShow = StringBuilder()
            var fieldLink: String? = null
            var atomsOnly = true
            var imageAtoms = 0
            var breakAtoms = 0

            fun addAtom(rawXml: String, kind: AKind, display: String, link: String?) {
                val idx = atoms.add(Atom(rawXml, kind, display.ifEmpty { "·" }))
                sb.append(ATOM); fm.add(fmts.intern(CFmt(link = link, atom = idx)))
                when (kind) { AKind.IMAGE -> imageAtoms++; AKind.BREAK -> breakAtoms++; else -> atomsOnly = false }
            }

            fun addText(s: String, f: Int) { for (ch in s) { sb.append(ch); fm.add(f) }; if (s.isNotEmpty()) atomsOnly = false }

            fun run(r: XEl, link: String?) {
                val rPr = r.child("rPr")
                val rawRPr = rPr?.raw(src)
                val rTag = r.startTag(src)
                val hasField = r.elements().any { it.local == "fldChar" }
                if (fieldDepth > 0 || hasField) {
                    if (fieldDepth > 0 && link != fieldLink) throw Locked()
                    if (fieldDepth == 0) { fieldLink = link; fieldSep = false; fieldRaw.setLength(0); fieldShow.setLength(0) }
                    for (c in r.elements()) when (c.local) {
                        "fldChar" -> when (c.attr("fldCharType")) {
                            "begin" -> fieldDepth++
                            "separate" -> if (fieldDepth == 1) fieldSep = true
                            "end" -> fieldDepth--
                        }
                        "t" -> if (fieldDepth == 1 && fieldSep) fieldShow.append(c.textContent())
                        "delText", "commentReference" -> throw Locked()
                    }
                    fieldRaw.append(r.raw(src))
                    if (fieldDepth <= 0) {
                        fieldDepth = 0
                        addAtom(fieldRaw.toString(), AKind.FIELD, fieldShow.toString(), link)
                    }
                    return
                }
                val f = fmts.intern(cfmt(rPr, rawRPr, link))
                for (c in r.elements()) {
                    when (c.local) {
                        "rPr", "lastRenderedPageBreak" -> {}
                        "t" -> addText(c.textContent(), f)
                        "tab", "ptab" -> addText("\t", f)
                        "cr" -> addText(SOFT_BREAK.toString(), f)
                        "br" -> when (c.attr("type")) {
                            "page", "column" -> addAtom(rTag + (rawRPr ?: "") + c.raw(src) + "</w:r>", AKind.BREAK, "↵", link)
                            else -> addText(SOFT_BREAK.toString(), f)
                        }
                        "noBreakHyphen" -> addText("‑", f)
                        "softHyphen" -> addText("­", f)
                        "drawing", "pict", "object" -> addAtom(rTag + (rawRPr ?: "") + c.raw(src) + "</w:r>", AKind.IMAGE, "▣", link)
                        "footnoteReference", "endnoteReference" -> addAtom(rTag + (rawRPr ?: "") + c.raw(src) + "</w:r>", AKind.NOTE, "*", link)
                        "sym" -> {
                            val code = c.attr("char")?.toIntOrNull(16)
                            val ch = code?.let { String(Character.toChars(if (it >= 0xF000) it - 0xF000 else it)) } ?: "·"
                            addAtom(rTag + (rawRPr ?: "") + c.raw(src) + "</w:r>", AKind.OTHER, ch, link)
                        }
                        "commentReference", "delText", "delInstrText", "instrText" -> throw Locked()
                        else -> addAtom(rTag + (rawRPr ?: "") + c.raw(src) + "</w:r>", AKind.OTHER, "·", link)
                    }
                }
            }

            for (c in el.elements()) {
                when (c.local) {
                    "pPr" -> {}
                    "r" -> run(c, null)
                    "hyperlink" -> {
                        if (fieldDepth > 0) throw Locked()
                        val link = c.startTag(src)
                        for (h in c.elements()) when (h.local) {
                            "r" -> run(h, link)
                            "proofErr" -> {}
                            "bookmarkStart", "permStart" -> lead.append(h.raw(src))
                            "bookmarkEnd", "permEnd" -> trail.append(h.raw(src))
                            else -> throw Locked()
                        }
                        if (fieldDepth > 0) throw Locked()
                    }
                    "proofErr" -> {}
                    "bookmarkStart", "permStart" -> if (fieldDepth > 0) fieldRaw.append(c.raw(src)) else lead.append(c.raw(src))
                    "bookmarkEnd", "permEnd" -> if (fieldDepth > 0) fieldRaw.append(c.raw(src)) else trail.append(c.raw(src))
                    "fldSimple" -> { if (fieldDepth > 0) throw Locked(); addAtom(c.raw(src), AKind.FIELD, c.textContent(), null) }
                    "oMath", "oMathPara" -> { if (fieldDepth > 0) throw Locked(); addAtom(c.raw(src), AKind.EQUATION, c.textContent().ifBlank { "∑" }, null) }
                    else -> throw Locked()
                }
            }
            if (fieldDepth > 0) throw Locked()

            if (top && sb.isNotEmpty() && atomsOnly && imageAtoms + breakAtoms == sb.length) {
                if (breakAtoms == 0) {
                    val prev = session.parseFragment(raw)
                    if (prev.isNotEmpty()) return EObject(EIds.next(), OKind.IMAGE, raw, prev)
                } else if (imageAtoms == 0 && breakAtoms == 1 && atoms[fmts[fm[0]].atom]?.raw?.contains("\"page\"") == true) {
                    return EObject(EIds.next(), OKind.PAGE_BREAK, raw, listOf(DocBlock.PageBreak))
                }
            }
            if (top && sb.isEmpty() && pPr?.child("pBdr")?.child("bottom") != null) {
                return EObject(EIds.next(), OKind.RULE, raw, listOf(DocBlock.Divider))
            }
            return EPara(
                EIds.next(), sb.toString(), fm.toIntArray(), props(pPr), pPr?.raw(src), el.startTag(src),
                lead.toString(), trail.toString(), raw,
            )
        }

        fun table(el: XEl, raw: String): ETable {
            val head = StringBuilder(el.startTag(src))
            val rows = ArrayList<ERow>()
            for (c in el.elements()) when (c.local) {
                "tblPr", "tblGrid" -> { if (rows.isNotEmpty()) throw Locked(); head.append(c.raw(src)) }
                "tr" -> {
                    val rh = StringBuilder(c.startTag(src))
                    val cells = ArrayList<ECell>()
                    for (t in c.elements()) when (t.local) {
                        "trPr", "tblPrEx" -> { if (cells.isNotEmpty()) throw Locked(); rh.append(t.raw(src)) }
                        "tc" -> {
                            val ch = StringBuilder(t.startTag(src))
                            val paras = ArrayList<EPara>()
                            for (x in t.elements()) when (x.local) {
                                "tcPr" -> { if (paras.isNotEmpty()) throw Locked(); ch.append(x.raw(src)) }
                                "p" -> paras.add(para(x, x.raw(src), top = false) as? EPara ?: throw Locked())
                                else -> throw Locked()
                            }
                            if (paras.isEmpty()) throw Locked()
                            cells.add(ECell(ch.toString(), paras))
                        }
                        else -> throw Locked()
                    }
                    if (cells.isEmpty()) throw Locked()
                    rows.add(ERow(rh.toString(), cells))
                }
                else -> throw Locked()
            }
            if (rows.isEmpty()) throw Locked()
            return ETable(EIds.next(), head.toString(), rows, raw)
        }
    }

    // ================================================================== text formats

    private fun loadText(f: File, kind: EditKind): Pair<EditSource, List<EBlock>> {
        if (f.length() > TEXT_EDIT_LIMIT) throw NotEditableException(NotEditableException.REASON_SIZE)
        val bytes = if (f.exists()) f.readBytes() else ByteArray(0)
        val (cs, bom) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to bytes.copyOf(3)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to bytes.copyOf(2)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to bytes.copyOf(2)
            else -> Charsets.UTF_8 to ByteArray(0)
        }
        val text = TextDecode.decode(bytes)
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val norm = text.replace("\r\n", "\n").replace('\r', '\n')
        val trailing = norm.endsWith("\n")
        val body = if (trailing) norm.dropLast(1) else norm
        val blocks = body.split('\n').map { EPara.plain(it.replace(ATOM, ' ')) }
        return EditSource(f, kind, text = TextMeta(cs, bom, newline, trailing)) to blocks
    }

    private fun textOf(src: EditSource, blocks: List<EBlock>): ByteArray {
        val meta = src.text ?: TextMeta(Charsets.UTF_8, ByteArray(0), "\n", true)
        val sb = StringBuilder()
        var first = true
        blocks.forEachPara { p ->
            if (!first) sb.append(meta.newline)
            first = false
            sb.append(p.text.replace(ATOM.toString(), ""))
        }
        if (meta.trailingNewline) sb.append(meta.newline)
        val out = ByteArrayOutputStream()
        out.write(meta.bom)
        out.write(sb.toString().toByteArray(meta.charset))
        return out.toByteArray()
    }

    // ================================================================== saving

    /** Saves [blocks] into [src]'s file (temp + validation + atomic rename, ".bak" kept until success). */
    fun save(src: EditSource, blocks: List<EBlock>) {
        synchronized(src) {
            val f = src.file
            val dir = f.absoluteFile.parentFile ?: throw IllegalStateException("no folder")
            val tmp = File(dir, ".${f.name}.saving")
            val bak = File(dir, ".${f.name}.bak")
            try {
                var pending: PkgAdditions.Pending? = null
                if (src.kind == EditKind.DOCX) {
                    pending = writeDocx(src, blocks, tmp)
                    validateDocx(tmp, src.docx!!.docPart)
                } else {
                    FileOutputStream(tmp).use { it.write(textOf(src, blocks)); it.fd.sync() }
                }
                if (f.exists()) f.copyTo(bak, overwrite = true)
                if (!tmp.renameTo(f)) {
                    tmp.copyTo(f, overwrite = true)
                    tmp.delete()
                }
                bak.delete()
                pending?.let { src.docx!!.add.commit(it) }
            } catch (e: Throwable) {
                tmp.delete()
                // The original is either untouched or restorable from the .bak.
                if (bak.exists() && (!f.exists() || f.length() == 0L)) runCatching { bak.copyTo(f, overwrite = true) }
                throw e
            }
        }
    }

    private fun validateDocx(tmp: File, docPart: String) {
        ZipFile(tmp).use { z ->
            val xml = readEntry(z, docPart) ?: throw IllegalStateException("document part missing")
            XmlLite.parse(xml)
            for (name in listOf("[Content_Types].xml", relsName(docPart), "word/styles.xml", "word/numbering.xml")) {
                readEntry(z, name)?.let { XmlLite.parse(it) }
            }
        }
        DocxParser.parse(tmp)
    }

    private fun insertBefore(xml: String, patterns: List<Regex>, add: String): String {
        if (add.isEmpty()) return xml
        for (re in patterns) {
            val m = re.find(xml) ?: continue
            return xml.substring(0, m.range.first) + add + xml.substring(m.range.first)
        }
        throw IllegalStateException("no insertion point")
    }

    private val closeStyles = Regex("</(\\w+:)?styles>")
    private val firstNum = Regex("<(\\w+:)?num[\\s>]")
    private val numCleanup = Regex("<(\\w+:)?numIdMacAtCleanup\\b")
    private val closeNumbering = Regex("</(\\w+:)?numbering>")
    private val closeRels = Regex("</Relationships>")
    private val closeTypes = Regex("</Types>")

    private fun mime(ext: String) = when (ext.lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "bmp" -> "image/bmp"; "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    /** Writes the new package to [out]; returns the additions it contains. */
    private fun writeDocx(src: EditSource, blocks: List<EBlock>, out: File): PkgAdditions.Pending {
        val pkg = src.docx!!
        val pending = pkg.add.snapshot()
        val w = src.writer
        val doc = StringBuilder(pkg.prefix.length + pkg.suffix.length + blocks.size * 256)
        doc.append(pkg.prefix)
        val tail = ArrayList<EBlock>()
        for (b in blocks) {
            if (b is EObject && b.raw.startsWith("<w:sectPr")) { tail.add(b); continue }
            doc.append(w.blockXml(b))
        }
        // A body whose last block is a table needs a paragraph before the section properties (Word adds one too).
        if (blocks.lastOrNull { it !is EObject || it.kind != OKind.HIDDEN } is ETable) doc.append("<w:p/>")
        tail.forEach { doc.append(w.blockXml(it)) }
        doc.append(pkg.suffix)

        val relsPart = relsName(pkg.docPart)
        val docDir = pkg.docPart.substringBeforeLast('/', "")
        val stylesXml = pending.styles.values.joinToString("")
        val numAbs = pending.abstractNums.joinToString("")
        val numNums = pending.nums.joinToString("")
        ZipFile(src.file).use { zin ->
            val hasStyles = zin.getEntry("word/styles.xml") != null
            val hasNumbering = zin.getEntry("word/numbering.xml") != null
            val newNumbering = !hasNumbering && (numAbs.isNotEmpty() || numNums.isNotEmpty())
            val newStyles = !hasStyles && stylesXml.isNotEmpty()
            val relAdds = StringBuilder()
            for ((id, type, target) in pending.rels) relAdds.append("<Relationship Id=\"$id\" Type=\"$type\" Target=\"${XmlLite.esc(target)}\"/>")
            if (newNumbering) relAdds.append("<Relationship Id=\"rIdDftNum${pkg.add.nextRel}\" Type=\"$REL_NUMBERING\" Target=\"numbering.xml\"/>")
            if (newStyles) relAdds.append("<Relationship Id=\"rIdDftSty${pkg.add.nextRel}\" Type=\"$REL_STYLES\" Target=\"styles.xml\"/>")
            val written = HashSet<String>()
            ZipOutputStream(FileOutputStream(out)).use { z ->
                fun put(name: String, bytes: ByteArray, time: Long = -1L) {
                    if (!written.add(name)) return
                    val e = ZipEntry(name)
                    if (time > 0) e.time = time
                    z.putNextEntry(e); z.write(bytes); z.closeEntry()
                }
                fun putText(name: String, s: String, time: Long = -1L) = put(name, s.toByteArray(Charsets.UTF_8), time)
                var hadRels = false
                var hadTypes = false
                val en = zin.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    when {
                        name == pkg.docPart -> putText(name, doc.toString(), e.time)
                        name == "word/styles.xml" && stylesXml.isNotEmpty() ->
                            putText(name, insertBefore(readEntry(zin, name)!!, listOf(closeStyles), stylesXml), e.time)
                        name == "word/numbering.xml" && (numAbs.isNotEmpty() || numNums.isNotEmpty()) -> {
                            var x = readEntry(zin, name)!!
                            x = insertBefore(x, listOf(firstNum, numCleanup, closeNumbering), numAbs)
                            x = insertBefore(x, listOf(numCleanup, closeNumbering), numNums)
                            putText(name, x, e.time)
                        }
                        name == relsPart -> {
                            hadRels = true
                            putText(name, insertBefore(readEntry(zin, name)!!, listOf(closeRels), relAdds.toString()), e.time)
                        }
                        name == "[Content_Types].xml" -> {
                            hadTypes = true
                            var x = readEntry(zin, name)!!
                            val adds = StringBuilder()
                            val exts = pending.media.keys.map { it.substringAfterLast('.').lowercase() }.toSet()
                            for (ext in exts) {
                                if (!Regex("<Default\\b[^>]*Extension=\"${Regex.escape(ext)}\"", RegexOption.IGNORE_CASE).containsMatchIn(x)) {
                                    adds.append("<Default Extension=\"$ext\" ContentType=\"${mime(ext)}\"/>")
                                }
                            }
                            if (newNumbering && !x.contains("/word/numbering.xml")) adds.append("<Override PartName=\"/word/numbering.xml\" ContentType=\"$CT_NUMBERING\"/>")
                            if (newStyles && !x.contains("/word/styles.xml")) adds.append("<Override PartName=\"/word/styles.xml\" ContentType=\"$CT_STYLES\"/>")
                            if (adds.isNotEmpty()) x = insertBefore(x, listOf(closeTypes), adds.toString())
                            putText(name, x, e.time)
                        }
                        name in pending.media -> {}
                        else -> {
                            if (!written.add(name)) continue
                            val ne = ZipEntry(name).also { it.time = e.time }
                            z.putNextEntry(ne)
                            zin.getInputStream(e).use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                }
                if (!hadTypes) throw IllegalStateException("no content types")
                if (!hadRels) {
                    putText(relsPart, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relAdds</Relationships>")
                }
                if (newNumbering) {
                    putText("word/numbering.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<w:numbering xmlns:w=\"$W_NS\">$numAbs$numNums</w:numbering>")
                }
                if (newStyles) {
                    putText("word/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                        "<w:styles xmlns:w=\"$W_NS\">$stylesXml</w:styles>")
                }
                for ((name, file) in pending.media) put(name, file.readBytes())
            }
            if (docDir != "word") { /* media / numbering paths are relative to word/, which every docx we accept uses */ }
        }
        return pending
    }

    // ================================================================== additions (styles, lists, pictures)

    /** Style id for a built-in kind (adds the definition to styles.xml on the next save when missing); null = Normal. */
    fun ensureStyle(src: EditSource, kind: String): String? {
        val pkg = src.docx ?: return null
        if (kind == "Normal") return null
        pkg.session.styleIdFor(kind)?.let { return it }
        synchronized(pkg.add) {
            pkg.add.styles[kind]?.let { return kind }
            val basedOn = pkg.session.styleIdFor("Normal")?.let { "<w:basedOn w:val=\"$it\"/>" } ?: ""
            val xml = styleXml(kind, basedOn) ?: return null
            pkg.add.styles[kind] = xml
            pkg.session.addStyles(xml)
        }
        return kind
    }

    private fun styleXml(kind: String, basedOn: String): String? {
        fun heading(n: Int, half: Int, before: Int, after: Int) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/>$basedOn<w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"$before\" w:after=\"$after\"/><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/><w:color w:val=\"2F5496\"/><w:sz w:val=\"$half\"/><w:szCs w:val=\"$half\"/></w:rPr></w:style>"
        return when (kind) {
            "Title" -> "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>$basedOn<w:next w:val=\"Normal\"/><w:uiPriority w:val=\"10\"/><w:qFormat/>" +
                "<w:pPr><w:spacing w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/><w:contextualSpacing/></w:pPr>" +
                "<w:rPr><w:b/><w:bCs/><w:color w:val=\"1F2937\"/><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr></w:style>"
            "Heading1" -> heading(1, 32, 360, 120)
            "Heading2" -> heading(2, 26, 240, 80)
            "Heading3" -> heading(3, 24, 200, 60)
            "Quote" -> "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>$basedOn<w:next w:val=\"Normal\"/><w:uiPriority w:val=\"29\"/><w:qFormat/>" +
                "<w:pPr><w:spacing w:before=\"200\" w:after=\"160\"/><w:ind w:left=\"864\" w:right=\"864\"/><w:jc w:val=\"center\"/></w:pPr>" +
                "<w:rPr><w:i/><w:iCs/><w:color w:val=\"404040\"/></w:rPr></w:style>"
            else -> null
        }
    }

    private fun abstractXml(id: Int, bullet: Boolean): String = buildString {
        append("<w:abstractNum w:abstractNumId=\"$id\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            val fmt = if (bullet) "bullet" else when (l % 3) { 0 -> "decimal"; 1 -> "lowerLetter"; else -> "lowerRoman" }
            val text = if (bullet) when (l % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" } else "%${l + 1}."
            append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"$text\"/><w:lvlJc w:val=\"left\"/>")
            append("<w:pPr><w:ind w:left=\"${720 * (l + 1)}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        append("</w:abstractNum>")
    }

    private fun ensureAbs(pkg: DocxPkg, bullet: Boolean): Int {
        val cur = if (bullet) pkg.add.bulletAbs else pkg.add.numberAbs
        if (cur >= 0) return cur
        val id = pkg.add.nextAbs++
        val xml = abstractXml(id, bullet)
        pkg.add.abstractNums.add(xml)
        pkg.session.addNumbering(xml)
        if (bullet) pkg.add.bulletAbs = id else pkg.add.numberAbs = id
        return id
    }

    /** numId of the editor's bullet list (one definition shared by all bullet paragraphs). */
    fun bulletNum(src: EditSource): String? {
        val pkg = src.docx ?: return null
        synchronized(pkg.add) {
            pkg.add.bulletNum?.let { return it }
            val abs = ensureAbs(pkg, true)
            val id = pkg.add.nextNum++
            val xml = "<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$abs\"/></w:num>"
            pkg.add.nums.add(xml)
            pkg.session.addNumbering(xml)
            return id.toString().also { pkg.add.bulletNum = it }
        }
    }

    /** A new numbered list that restarts at 1. */
    fun newNumberedList(src: EditSource): String? {
        val pkg = src.docx ?: return null
        synchronized(pkg.add) {
            val abs = ensureAbs(pkg, false)
            val id = pkg.add.nextNum++
            val xml = "<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$abs\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>"
            pkg.add.nums.add(xml)
            pkg.session.addNumbering(xml)
            return id.toString()
        }
    }

    fun pageBreak(): EObject = EObject(EIds.next(), OKind.PAGE_BREAK, "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>", listOf(DocBlock.PageBreak))

    fun rule(): EObject = EObject(EIds.next(), OKind.RULE,
        "<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p>", listOf(DocBlock.Divider))

    /** A rows × cols bordered table with empty cells spanning the text column. */
    fun table(src: EditSource, rows: Int, cols: Int, rtl: Boolean): ETable {
        val total = (src.page.contentW * 20).roundToInt()
        val cw = total / cols
        val sides = listOf("top", "left", "bottom", "right", "insideH", "insideV")
            .joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" }
        val head = "<w:tbl><w:tblPr>" + (if (rtl) "<w:bidiVisual/>" else "") + "<w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>$sides</w:tblBorders>" +
            "<w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr>" +
            "<w:tblGrid>" + "<w:gridCol w:w=\"$cw\"/>".repeat(cols) + "</w:tblGrid>"
        val props = PProps(bidi = if (rtl) true else null)
        return ETable(EIds.next(), head, List(rows) {
            ERow("<w:tr>", List(cols) { ECell("<w:tc><w:tcPr><w:tcW w:w=\"$cw\" w:type=\"dxa\"/></w:tcPr>", listOf(EPara.plain("", props = props))) })
        })
    }

    /**
     * A picture paragraph for [image] (already copied into app storage). [wPx]/[hPx] = pixel size (shown at 96 dpi,
     * fitted to the text column). The media part and relationship are written on the next save.
     */
    fun picture(src: EditSource, image: File, wPx: Int, hPx: Int, rtl: Boolean): EObject? {
        val pkg = src.docx ?: return null
        var wPt = max(wPx, 1) * 0.75f
        var hPt = max(hPx, 1) * 0.75f
        val maxW = src.page.contentW
        if (wPt > maxW) { hPt *= maxW / wPt; wPt = maxW }
        val maxH = src.page.contentH * 0.9f
        if (hPt > maxH) { wPt *= maxH / hPt; hPt = maxH }
        val ext = image.extension.lowercase().let { if (it in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")) it else "png" }
        val docPr: Int
        val rid: String
        val entry: String
        synchronized(pkg.add) {
            docPr = pkg.add.nextDocPr++
            rid = "rIdDft${pkg.add.nextRel++}"
            entry = "word/media/daftar_${System.currentTimeMillis()}_$docPr.$ext"
            pkg.add.media[entry] = image
            pkg.add.rels.add(Triple(rid, REL_IMAGE, entry.removePrefix("word/")))
        }
        pkg.session.addEntry(entry)
        val cx = (wPt * 12700).roundToInt()
        val cy = (hPt * 12700).roundToInt()
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val raw = "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>" +
            "<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$docPr\" name=\"Picture $docPr\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$a\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>" +
            "<a:graphic xmlns:a=\"$a\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$docPr\" name=\"Picture $docPr\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        return EObject(EIds.next(), OKind.IMAGE, raw, listOf(DocBlock.Image(image.absolutePath, wPt, hPt, "", 1, rtl, external = true)))
    }

    // ================================================================== read-only formats → .docx

    private fun hex(c: Int) = String.format("%06X", c and 0xFFFFFF)

    /**
     * Builds editor blocks for a new .docx ([src] = freshly created package) from a document read by the viewer
     * (rtf / csv / tsv / doc / anything [DocLoader] opens): text, run formatting, headings, alignment, direction,
     * lists, tables, rules and page breaks.
     */
    fun fromModel(src: EditSource, doc: DocxDoc): List<EBlock> {
        val out = ArrayList<EBlock>()
        var listNum: String? = null
        fun para(p: DocBlock.Para, inCell: Boolean): EPara {
            val text = p.text.replace('\n', SOFT_BREAK).replace(ATOM, ' ').replace('\r', ' ')
            val fm = IntArray(text.length)
            for (s in p.spans) {
                val f = s.fmt
                val cf = CFmt(
                    b = if (f.bold) true else null, i = if (f.italic) true else null, u = if (f.underline) true else null,
                    strike = if (f.strike) true else null,
                    sizeHalf = if (kotlin.math.abs(f.sizePt - p.basePt) > 0.1f || p.heading == 0) (f.sizePt * 2).roundToInt() else null,
                    color = f.color?.let { hex(it) }, font = if (f.mono) WordFonts.docxName("mono") else f.font?.let { WordFonts.docxName(it) },
                )
                val idx = src.fmts.intern(cf)
                for (k in s.start.coerceAtLeast(0) until s.end.coerceAtMost(text.length)) fm[k] = idx
            }
            val style = when {
                p.heading in 1..3 -> ensureStyle(src, "Heading${p.heading}")
                p.heading > 3 -> ensureStyle(src, "Heading3")
                p.box == BOX_QUOTE -> ensureStyle(src, "Quote")
                else -> null
            }
            var numId: String? = null
            val m = p.marker
            if (!inCell && !m.isNullOrEmpty()) {
                val numbered = m.any { it.isDigit() } || (m.length <= 4 && m.endsWith("."))
                numId = if (numbered) (listNum ?: newNumberedList(src).also { listNum = it }) else bulletNum(src)
            }
            if (!inCell && m.isNullOrEmpty()) listNum = null
            val jc = when (p.align) { 1 -> "center"; 2 -> "right"; 3 -> "both"; else -> null }
            return EPara(EIds.next(), text, fm, PProps(style = style, jc = jc, bidi = if (p.rtl) true else null, numId = numId, ilvl = if (numId != null) 0 else null))
        }
        fun table(t: DocBlock.Table): ETable {
            val cols = max(1, t.rows.maxOfOrNull { r -> r.cells.size } ?: 1)
            val base = table(src, 1, cols, t.rtl)
            val cellHead = base.rows[0].cells[0].head
            val rows = t.rows.take(2000).map { r ->
                ERow("<w:tr>", List(cols) { c ->
                    val cell = r.cells.getOrNull(c)
                    val paras = cell?.blocks?.filterIsInstance<DocBlock.Para>()?.map { para(it, true) }.orEmpty()
                    ECell(cellHead, paras.ifEmpty { listOf(EPara.plain("")) })
                })
            }
            return ETable(EIds.next(), base.head, rows)
        }
        val sheet = doc.sheet
        if (sheet != null) {
            out.add(table(CsvReader.toTable(sheet, src.page.contentW, PidGen())))
        } else for (b in doc.blocks) {
            when (b) {
                is DocBlock.Para -> out.add(para(b, false))
                is DocBlock.Table -> { listNum = null; out.add(table(b)) }
                is DocBlock.Image -> if (b.external) {
                    val f = File(b.entry)
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath, o) }
                    if (o.outWidth > 0) picture(src, f, o.outWidth, o.outHeight, b.rtl)?.let { out.add(it) }
                }
                DocBlock.Divider -> out.add(rule())
                DocBlock.PageBreak -> out.add(pageBreak())
            }
        }
        if (out.isEmpty() || out.last() !is EPara) out.add(EPara.plain(""))
        return out
    }
}

/** Paragraph / run / table XML for edited blocks (untouched blocks keep their raw XML). */
internal class DocxWriter(private val src: EditSource) {
    private val elCache = HashMap<String, XEl?>()
    private val rprCache = HashMap<Long, String>()

    private fun el(raw: String?): XEl? = if (raw == null) null else synchronized(elCache) {
        elCache.getOrPut(raw) { runCatching { XmlLite.parse(raw).firstOrNull { it is XEl } as XEl? }.getOrNull() }
    }

    fun blockXml(b: EBlock): String = when (b) {
        is EPara -> b.raw ?: paraXml(b)
        is ETable -> b.raw ?: tableXml(b)
        is EObject -> b.raw
    }

    fun tableXml(t: ETable): String = buildString {
        append(t.head)
        for (r in t.rows) {
            append(r.head)
            for (c in r.cells) {
                append(c.head)
                for (p in c.paras) append(p.raw ?: paraXml(p))
                append("</w:tc>")
            }
            append("</w:tr>")
        }
        append("</w:tbl>")
    }

    private fun onOff(e: XEl): Boolean = e.attr("val").let { it == null || !(it == "0" || it == "false" || it == "off") }

    private fun children(e: XEl?, raw: String?): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (e != null && raw != null) for (c in e.elements()) out.add(c.local to c.raw(raw))
        return out
    }

    private fun origProps(pPr: XEl?): PProps {
        if (pPr == null) return PProps()
        var p = PProps()
        for (c in pPr.elements()) p = when (c.local) {
            "pStyle" -> p.copy(style = c.attr("val"))
            "jc" -> p.copy(jc = c.attr("val"))
            "bidi" -> p.copy(bidi = onOff(c))
            "numPr" -> p.copy(numId = c.child("numId")?.attr("val"), ilvl = c.child("ilvl")?.attr("val")?.toIntOrNull())
            "ind" -> p.copy(indLeft = (c.attr("left") ?: c.attr("start"))?.toFloatOrNull()?.roundToInt())
            else -> p
        }
        return p
    }

    /** `w:pPr` of [p]: the original element with the managed properties replaced, in schema order ("" when empty). */
    fun pPrXml(p: EPara): String {
        val orig = el(p.pPr)
        val o = origProps(orig)
        val n = p.props
        val items = children(orig, p.pPr)
        fun replace(name: String, xml: String?) {
            items.removeAll { it.first == name }
            if (xml != null) items.add(name to xml)
        }
        if (n.style != o.style) replace("pStyle", n.style?.let { "<w:pStyle w:val=\"${XmlLite.esc(it)}\"/>" })
        if (n.jc != o.jc) replace("jc", n.jc?.let { "<w:jc w:val=\"${XmlLite.esc(it)}\"/>" })
        if (n.bidi != o.bidi) replace("bidi", when (n.bidi) { true -> "<w:bidi/>"; false -> "<w:bidi w:val=\"0\"/>"; null -> null })
        if (n.numId != o.numId || n.ilvl != o.ilvl) {
            replace("numPr", n.numId?.let { "<w:numPr><w:ilvl w:val=\"${n.ilvl ?: 0}\"/><w:numId w:val=\"${XmlLite.esc(it)}\"/></w:numPr>" })
        }
        if (n.indLeft != o.indLeft) {
            val ind = orig?.child("ind")
            val attrs = ind?.attrs?.filter { it.first.substringAfter(':') !in setOf("left", "start") }?.toMutableList() ?: ArrayList()
            if (n.indLeft != null) attrs.add(0, (if (ind?.attr("start") != null) "w:start" else "w:left") to n.indLeft.toString())
            replace("ind", if (attrs.isEmpty()) null else "<w:ind " + attrs.joinToString(" ") { "${it.first}=\"${XmlLite.esc(it.second)}\"" } + "/>")
        }
        if (items.isEmpty()) return ""
        return "<w:pPr>" + XmlLite.ordered(items, XmlLite.PPR_ORDER).joinToString("") { it.second } + "</w:pPr>"
    }

    private fun origFmt(rPr: XEl?): CFmt {
        if (rPr == null) return CFmt()
        var f = CFmt()
        for (c in rPr.elements()) f = when (c.local) {
            "b" -> f.copy(b = onOff(c))
            "bCs" -> if (f.b == null) f.copy(b = onOff(c)) else f
            "i" -> f.copy(i = onOff(c))
            "iCs" -> if (f.i == null) f.copy(i = onOff(c)) else f
            "u" -> f.copy(u = c.attr("val").let { it != null && it != "none" })
            "strike" -> f.copy(strike = onOff(c))
            "sz" -> f.copy(sizeHalf = c.attr("val")?.toFloatOrNull()?.roundToInt())
            "szCs" -> if (f.sizeHalf == null) f.copy(sizeHalf = c.attr("val")?.toFloatOrNull()?.roundToInt()) else f
            "color" -> f.copy(color = c.attr("val"))
            "highlight" -> f.copy(highlight = c.attr("val"))
            "rFonts" -> f.copy(font = c.attr("ascii") ?: c.attr("hAnsi") ?: c.attr("cs"))
            else -> f
        }
        return f
    }

    /** `w:rPr` for format [idx]; [rtl] true/false adds/removes w:rtl, null keeps it. Cached. */
    fun rPrXml(idx: Int, rtl: Boolean?): String {
        val key = idx.toLong() * 4 + when (rtl) { null -> 0; false -> 1; true -> 2 }
        synchronized(rprCache) { rprCache[key]?.let { return it } }
        val f = src.fmts[idx]
        val orig = el(f.rPr)
        val o = origFmt(orig)
        val items = children(orig, f.rPr)
        fun replace(names: Set<String>, add: List<Pair<String, String>>) {
            items.removeAll { it.first in names }
            items.addAll(add)
        }
        fun flag(name: String, v: Boolean) = name to (if (v) "<w:$name/>" else "<w:$name w:val=\"0\"/>")
        if (f.b != o.b) replace(setOf("b", "bCs"), f.b?.let { listOf(flag("b", it), flag("bCs", it)) }.orEmpty())
        if (f.i != o.i) replace(setOf("i", "iCs"), f.i?.let { listOf(flag("i", it), flag("iCs", it)) }.orEmpty())
        if (f.strike != o.strike) replace(setOf("strike", "dstrike"), f.strike?.let { listOf(flag("strike", it)) }.orEmpty())
        if (f.u != o.u) replace(setOf("u"), f.u?.let { listOf("u" to "<w:u w:val=\"${if (it) "single" else "none"}\"/>") }.orEmpty())
        if (f.sizeHalf != o.sizeHalf) replace(setOf("sz", "szCs"), f.sizeHalf?.let { listOf("sz" to "<w:sz w:val=\"$it\"/>", "szCs" to "<w:szCs w:val=\"$it\"/>") }.orEmpty())
        if (f.color != o.color) replace(setOf("color"), f.color?.let { listOf("color" to "<w:color w:val=\"${XmlLite.esc(it)}\"/>") }.orEmpty())
        if (f.highlight != o.highlight) replace(setOf("highlight"), f.highlight?.let { listOf("highlight" to "<w:highlight w:val=\"${XmlLite.esc(it)}\"/>") }.orEmpty())
        if (f.font != o.font) replace(setOf("rFonts"), f.font?.let { n ->
            val e = XmlLite.esc(n)
            listOf("rFonts" to "<w:rFonts w:ascii=\"$e\" w:hAnsi=\"$e\" w:cs=\"$e\" w:eastAsia=\"$e\"/>")
        }.orEmpty())
        when (rtl) {
            true -> if (items.none { it.first == "rtl" }) items.add("rtl" to "<w:rtl/>")
            false -> items.removeAll { it.first == "rtl" }
            null -> {}
        }
        val out = if (items.isEmpty()) "" else "<w:rPr>" + XmlLite.ordered(items, XmlLite.RPR_ORDER).joinToString("") { it.second } + "</w:rPr>"
        synchronized(rprCache) { rprCache[key] = out }
        return out
    }

    private fun strongDir(c: Char): Int = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> 1
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> -1
        else -> 0
    }

    /** Splits [s] into runs of one direction (neutrals join the current run); direction null = only neutrals. */
    private fun dirRuns(s: String): List<Pair<String, Boolean?>> {
        val out = ArrayList<Pair<String, Boolean?>>()
        val sb = StringBuilder()
        var cur: Boolean? = null
        for (c in s) {
            val rtl = when (strongDir(c)) { 1 -> true; -1 -> false; else -> null }
            if (rtl != null && cur != null && rtl != cur) { out.add(sb.toString() to cur); sb.setLength(0) }
            if (rtl != null) cur = rtl
            sb.append(c)
        }
        if (sb.isNotEmpty()) out.add(sb.toString() to cur)
        return out
    }

    private fun content(seg: String, sb: StringBuilder) {
        val t = StringBuilder()
        fun flush() { if (t.isNotEmpty()) { sb.append("<w:t xml:space=\"preserve\">").append(XmlLite.esc(t.toString())).append("</w:t>"); t.setLength(0) } }
        for (c in seg) when (c) {
            '\t' -> { flush(); sb.append("<w:tab/>") }
            SOFT_BREAK, '\n', '\r' -> { flush(); sb.append("<w:br/>") }
            '­' -> { flush(); sb.append("<w:softHyphen/>") }
            '‑' -> { flush(); sb.append("<w:noBreakHyphen/>") }
            ATOM -> {}
            else -> t.append(c)
        }
        flush()
    }

    fun runsXml(p: EPara): String {
        val sb = StringBuilder()
        val text = p.text
        val n = text.length
        var i = 0
        while (i < n) {
            val link = src.fmts[p.fmts[i]].link
            var j = i
            while (j < n && src.fmts[p.fmts[j]].link == link) j++
            if (link != null) sb.append(link)
            var k = i
            while (k < j) {
                val idx = p.fmts[k]
                var m = k
                while (m < j && p.fmts[m] == idx) m++
                val f = src.fmts[idx]
                if (f.atom >= 0) {
                    for (q in k until m) {
                        if (text[q] == ATOM) src.atoms[f.atom]?.let { sb.append(it.raw) }
                        else { sb.append("<w:r>"); content(text[q].toString(), sb); sb.append("</w:r>") }
                    }
                } else {
                    for ((seg, rtl) in dirRuns(text.substring(k, m))) {
                        sb.append("<w:r>").append(rPrXml(idx, rtl))
                        content(seg, sb)
                        sb.append("</w:r>")
                    }
                }
                k = m
            }
            if (link != null) sb.append("</w:hyperlink>")
            i = j
        }
        return sb.toString()
    }

    fun paraXml(p: EPara): String = p.startTag + pPrXml(p) + p.lead + runsXml(p) + p.trail + "</w:p>"
}
