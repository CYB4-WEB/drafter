package com.daftar.app.word

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Streaming .docx parser: ZipFile + XmlPullParser, no POI. Produces [DocxDoc] blocks in reading order.
 * Element names are matched by local name (namespace-aware parser), so both transitional and strict OOXML work.
 */
object DocxParser {

    /** EMU per point (914400 EMU per inch / 72). */
    private const val EMU_PER_PT = 12700f

    fun parse(file: File): DocxDoc {
        if (isOle(file)) throw LegacyDocException()
        ZipFile(file).use { zip ->
            val docEntry = zip.getEntry("word/document.xml") ?: findMainPart(zip) ?: throw IllegalStateException("no document part")
            val ctx = Ctx({ n -> zip.getEntry(n)?.let { zip.getInputStream(it) } }, { zip.getEntry(it) != null })
            zip.getEntry("word/styles.xml")?.let { e -> zip.getInputStream(e).use { ctx.parseStyles(it) } }
            zip.getEntry("word/numbering.xml")?.let { e -> zip.getInputStream(e).use { ctx.parseNumbering(it) } }
            val baseDir = docEntry.name.substringBeforeLast('/', "")
            val rels = ctx.parseRels(relsName(docEntry.name), baseDir)

            val blocks = ArrayList<DocBlock>()
            zip.getInputStream(docEntry).use { input ->
                val p = newParser(input)
                if (p.seekStart("body")) ctx.parseBlocks(p, rels, blocks)
            }

            // Footnotes that were referenced, in order of first reference.
            if (ctx.footnoteNumbers.isNotEmpty()) {
                zip.getEntry("$baseDir/footnotes.xml")?.let { e ->
                    val fnRels = ctx.parseRels(relsName(e.name), baseDir)
                    val notes = zip.getInputStream(e).use { ctx.parseFootnotes(it, fnRels) }
                    val ordered = ctx.footnoteNumbers.entries.sortedBy { it.value }.mapNotNull { notes[it.key] }
                    if (ordered.isNotEmpty()) {
                        blocks.add(DocBlock.Divider)
                        ordered.forEach { blocks.addAll(it) }
                    }
                }
            }

            while (blocks.lastOrNull() == DocBlock.PageBreak) blocks.removeAt(blocks.lastIndex)
            return DocxDoc(file.absolutePath, blocks, headingsOf(blocks), ctx.page, DocKind.DOCX)
        }
    }

    private fun isOle(f: File): Boolean = runCatching {
        f.inputStream().use { s ->
            val h = ByteArray(4); s.read(h) == 4 && (h[0].toInt() and 0xFF) == 0xD0 && (h[1].toInt() and 0xFF) == 0xCF &&
                (h[2].toInt() and 0xFF) == 0x11 && (h[3].toInt() and 0xFF) == 0xE0
        }
    }.getOrDefault(false)

    private fun findMainPart(zip: ZipFile) = zip.getEntry("_rels/.rels")?.let { e ->
        val target = zip.getInputStream(e).use { s ->
            val p = newParser(s)
            var t: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" &&
                    p.a("Type")?.endsWith("/officeDocument") == true) { t = p.a("Target"); break }
            }
            t
        }
        target?.let { zip.getEntry(it.removePrefix("/")) }
    }

    private fun relsName(part: String) = part.substringBeforeLast('/', "") + "/_rels/" + part.substringAfterLast('/') + ".rels"

    private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(input, null)
    }

    // ---------------------------------------------------------------- xml helpers

    private fun XmlPullParser.a(local: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == local) return getAttributeValue(i)
        return null
    }

    private fun XmlPullParser.onOff(): Boolean = a("val")?.let { it != "0" && it != "false" && it != "off" && it != "none" } ?: true

    private fun XmlPullParser.skipTree() {
        if (eventType != XmlPullParser.START_TAG) return
        var d = 1
        while (d > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> d++
                XmlPullParser.END_TAG -> d--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun XmlPullParser.seekStart(local: String): Boolean {
        while (next() != XmlPullParser.END_DOCUMENT) if (eventType == XmlPullParser.START_TAG && name == local) return true
        return false
    }

    /** Iterates the direct and nested start tags of the current element until its end tag. */
    private inline fun XmlPullParser.children(block: (String) -> Unit) {
        val depth = depth
        while (true) {
            val ev = next()
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.END_TAG && this.depth == depth) return
            if (ev == XmlPullParser.START_TAG) block(name)
        }
    }

    private fun String.twips(): Float? = toFloatOrNull()
    private fun hex(v: String?): Int? {
        if (v == null || v.equals("auto", true) || v.length != 6) return null
        return v.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }

    private val highlightColors = mapOf(
        "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
        "blue" to 0xFF0000FF, "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080,
        "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080, "darkRed" to 0xFF800000, "darkYellow" to 0xFF808000,
        "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0, "black" to 0xFF000000, "white" to 0xFFFFFFFF,
    ).mapValues { it.value.toInt() }

    // ---------------------------------------------------------------- property bags

    /** Run properties; null = not specified at this level. */
    private data class RP(
        val b: Boolean? = null, val bCs: Boolean? = null, val i: Boolean? = null, val iCs: Boolean? = null,
        val u: Boolean? = null, val strike: Boolean? = null, val sz: Float? = null, val szCs: Float? = null,
        val color: Int? = null, val colorSet: Boolean = false, val bg: Int? = null, val vert: Int? = null,
        val font: String? = null, val caps: Boolean? = null, val vanish: Boolean? = null, val rStyle: String? = null,
    ) {
        operator fun plus(o: RP?): RP = if (o == null) this else RP(
            o.b ?: b, o.bCs ?: bCs, o.i ?: i, o.iCs ?: iCs, o.u ?: u, o.strike ?: strike, o.sz ?: sz, o.szCs ?: szCs,
            if (o.colorSet) o.color else color, o.colorSet || colorSet, o.bg ?: bg, o.vert ?: vert, o.font ?: font,
            o.caps ?: caps, o.vanish ?: vanish, o.rStyle ?: rStyle,
        )

        fun fmt(cs: Boolean) = RunFmt(
            bold = (if (cs) bCs ?: b else b) ?: false,
            italic = (if (cs) iCs ?: i else i) ?: false,
            underline = u ?: false,
            strike = strike ?: false,
            sizePt = (if (cs) szCs ?: sz else sz) ?: 11f,
            color = color,
            background = bg,
            vert = vert ?: 0,
            mono = font == "mono",
            font = font?.takeIf { it != "sans" },
        )
    }

    private data class PP(
        val jc: String? = null, val before: Float? = null, val after: Float? = null, val line: Float? = null, val lineRule: String? = null,
        val indStart: Float? = null, val indEnd: Float? = null, val hanging: Float? = null, val firstLine: Float? = null,
        val numId: String? = null, val ilvl: Int? = null, val bidi: Boolean? = null, val outline: Int? = null,
        val pStyle: String? = null, val pageBreakBefore: Boolean? = null, val sectBreak: Boolean? = null,
        /** Direct bottom border (a horizontal line paragraph when it has no text). */
        val rule: Boolean? = null,
    ) {
        operator fun plus(o: PP?): PP = if (o == null) this else PP(
            o.jc ?: jc, o.before ?: before, o.after ?: after, o.line ?: line, o.lineRule ?: lineRule,
            o.indStart ?: indStart, o.indEnd ?: indEnd,
            // hanging and firstLine are mutually exclusive: a level that sets one clears the other
            if (o.hanging != null || o.firstLine != null) o.hanging else hanging,
            if (o.hanging != null || o.firstLine != null) o.firstLine else firstLine,
            o.numId ?: numId, o.ilvl ?: ilvl, o.bidi ?: bidi, o.outline ?: outline, o.pStyle ?: pStyle, o.pageBreakBefore ?: pageBreakBefore,
            o.sectBreak, o.rule ?: rule,
        )
    }

    private class Style(
        val id: String, val type: String, val name: String, val basedOn: String?,
        val pp: PP, val rp: RP, val isDefault: Boolean, val tableBorders: Boolean?,
    )

    private class Lvl(val start: Int, val fmt: String, val text: String, val pp: PP, val rp: RP, val isLgl: Boolean)

    private class Drawing(var cx: Float = 0f, var cy: Float = 0f, var embed: String? = null, var alt: String = "") {
        val inner = ArrayList<DocBlock>()
    }

    private fun XmlPullParser.readRP(): RP {
        var r = RP()
        children { n ->
            r = when (n) {
                "b" -> r.copy(b = onOff())
                "bCs" -> r.copy(bCs = onOff())
                "i" -> r.copy(i = onOff())
                "iCs" -> r.copy(iCs = onOff())
                "u" -> r.copy(u = a("val").let { it != null && it != "none" })
                "strike", "dstrike" -> r.copy(strike = onOff())
                "sz" -> r.copy(sz = a("val")?.toFloatOrNull()?.div(2f))
                "szCs" -> r.copy(szCs = a("val")?.toFloatOrNull()?.div(2f))
                "color" -> r.copy(color = hex(a("val")), colorSet = true)
                "highlight" -> a("val")?.let { v -> if (v == "none") r else highlightColors[v]?.let { r.copy(bg = it) } } ?: r
                "shd" -> hex(a("fill"))?.let { if (r.bg == null) r.copy(bg = it) else r } ?: r
                "vertAlign" -> r.copy(vert = when (a("val")) { "superscript" -> 1; "subscript" -> 2; else -> 0 })
                "rFonts" -> {
                    val f = a("ascii") ?: a("hAnsi") ?: a("cs") ?: ""
                    if (f.isEmpty()) r else r.copy(font = fontKeyOf(f) ?: "sans")
                }
                "caps" -> r.copy(caps = onOff())
                "vanish", "webHidden" -> r.copy(vanish = onOff())
                "rStyle" -> r.copy(rStyle = a("val"))
                "rPrChange" -> { skipTree(); r }
                else -> r
            }
        }
        return r
    }

    private fun XmlPullParser.readPP(): PP {
        var p = PP()
        children { n ->
            when (n) {
                "pStyle" -> p = p.copy(pStyle = a("val"))
                "jc" -> p = p.copy(jc = a("val"))
                "bidi" -> p = p.copy(bidi = onOff())
                "pageBreakBefore" -> p = p.copy(pageBreakBefore = onOff())
                "outlineLvl" -> p = p.copy(outline = a("val")?.toIntOrNull())
                "spacing" -> p = p.copy(
                    before = a("before")?.twips() ?: p.before, after = a("after")?.twips() ?: p.after,
                    line = a("line")?.twips() ?: p.line, lineRule = a("lineRule") ?: p.lineRule,
                )
                "ind" -> p = p.copy(
                    indStart = (a("start") ?: a("left"))?.twips() ?: p.indStart,
                    indEnd = (a("end") ?: a("right"))?.twips() ?: p.indEnd,
                    hanging = a("hanging")?.twips() ?: if (a("firstLine") != null) null else p.hanging,
                    firstLine = a("firstLine")?.twips() ?: if (a("hanging") != null) null else p.firstLine,
                )
                "numPr" -> children { m ->
                    when (m) {
                        "numId" -> p = p.copy(numId = a("val"))
                        "ilvl" -> p = p.copy(ilvl = a("val")?.toIntOrNull())
                    }
                }
                "sectPr" -> {
                    // A section break inside a paragraph: every type except "continuous" starts a new page.
                    var continuous = false
                    children { m -> if (m == "type") continuous = a("val") == "continuous" }
                    p = p.copy(sectBreak = !continuous)
                }
                "pBdr" -> {
                    var bottom = false
                    children { m -> if (m == "bottom") a("val").let { v -> if (v != null && v != "none" && v != "nil") bottom = true } }
                    p = p.copy(rule = bottom)
                }
                "rPr", "pPrChange", "tabs", "framePr" -> skipTree()
            }
        }
        return p
    }

    // ---------------------------------------------------------------- per-document context

    private class Ctx(val open: (String) -> InputStream?, val exists: (String) -> Boolean) {
        val styles = HashMap<String, Style>()
        var defRP = RP(sz = 11f)
        var defPP = PP()
        var defaultParaStyle: String? = null
        val abstractNums = HashMap<String, HashMap<Int, Lvl>>()
        val nums = HashMap<String, Pair<String, HashMap<Int, Pair<Int?, Lvl?>>>>()
        val counters = HashMap<String, IntArray>()
        val footnoteNumbers = LinkedHashMap<String, Int>()
        var pid = 0
        var page = PageSpec.A4
        private val rpCache = HashMap<String, RP>()
        private val ppCache = HashMap<String, PP>()

        fun clearCaches() { rpCache.clear(); ppCache.clear() }

        // ------------------------------------------------ styles

        fun parseStyles(input: InputStream) {
            val p = newParser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "rPrDefault" -> p.children { if (it == "rPr") defRP = RP(sz = 11f) + p.readRP() }
                    "pPrDefault" -> p.children { if (it == "pPr") defPP = p.readPP() }
                    "style" -> {
                        val id = p.a("styleId") ?: ""
                        val type = p.a("type") ?: "paragraph"
                        val isDef = p.a("default").let { it == "1" || it == "true" }
                        var name = ""; var basedOn: String? = null; var pp = PP(); var rp = RP(); var borders: Boolean? = null
                        p.children { n ->
                            when (n) {
                                "name" -> name = p.a("val") ?: ""
                                "basedOn" -> basedOn = p.a("val")
                                "pPr" -> pp = p.readPP()
                                "rPr" -> rp = p.readRP()
                                "tblPr" -> p.children { t -> if (t == "tblBorders") borders = readBorders(p) }
                                "tblStylePr", "tcPr", "trPr" -> p.skipTree()
                            }
                        }
                        styles[id] = Style(id, type, name, basedOn, pp, rp, isDef, borders)
                        if (isDef && type == "paragraph") defaultParaStyle = id
                    }
                }
            }
        }

        fun readBorders(p: XmlPullParser): Boolean {
            var any = false
            p.children { val v = p.a("val"); if (v != null && v != "none" && v != "nil") any = true }
            return any
        }

        fun styleRP(id: String?, guard: Int = 0): RP {
            if (id == null || guard > 20) return RP()
            rpCache[id]?.let { return it }
            val s = styles[id] ?: return RP()
            val r = styleRP(s.basedOn, guard + 1) + s.rp
            rpCache[id] = r
            return r
        }

        fun stylePP(id: String?, guard: Int = 0): PP {
            if (id == null || guard > 20) return PP()
            ppCache[id]?.let { return it }
            val s = styles[id] ?: return PP()
            val r = stylePP(s.basedOn, guard + 1) + s.pp
            ppCache[id] = r
            return r
        }

        /** Heading level from the style chain: 1..6, Title = 1, 0 = not a heading. */
        fun headingLevel(id: String?, outline: Int?): Int {
            var cur = id
            var guard = 0
            while (cur != null && guard++ < 20) {
                val s = styles[cur] ?: break
                val n = s.name.lowercase().replace(" ", "")
                val i = s.id.lowercase()
                if (n == "title" || i == "title") return 1
                Regex("^heading(\\d)$").find(n)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
                Regex("^heading(\\d)$").find(i)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
                cur = s.basedOn
            }
            if (outline != null && outline in 0..5) return outline + 1
            return 0
        }

        fun isTitle(id: String?) = styles[id ?: ""]?.let { it.name.equals("title", true) || it.id.equals("title", true) } ?: false

        // ------------------------------------------------ numbering

        fun parseNumbering(input: InputStream) {
            val p = newParser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "abstractNum" -> {
                        val id = p.a("abstractNumId") ?: continue
                        val lvls = HashMap<Int, Lvl>()
                        p.children { n -> if (n == "lvl") { val l = p.a("ilvl")?.toIntOrNull() ?: 0; lvls[l] = readLvl(p) } }
                        abstractNums[id] = lvls
                    }
                    "num" -> {
                        val id = p.a("numId") ?: continue
                        var abs = ""
                        val ov = HashMap<Int, Pair<Int?, Lvl?>>()
                        p.children { n ->
                            when (n) {
                                "abstractNumId" -> abs = p.a("val") ?: ""
                                "lvlOverride" -> {
                                    val l = p.a("ilvl")?.toIntOrNull() ?: 0
                                    var start: Int? = null; var lvl: Lvl? = null
                                    p.children { m ->
                                        when (m) {
                                            "startOverride" -> start = p.a("val")?.toIntOrNull()
                                            "lvl" -> lvl = readLvl(p)
                                        }
                                    }
                                    ov[l] = start to lvl
                                }
                            }
                        }
                        nums[id] = abs to ov
                    }
                }
            }
        }

        fun readLvl(p: XmlPullParser): Lvl {
            var start = 1; var fmt = "decimal"; var text = ""; var pp = PP(); var rp = RP(); var lgl = false
            p.children { n ->
                when (n) {
                    "start" -> start = p.a("val")?.toIntOrNull() ?: 1
                    "numFmt" -> fmt = p.a("val") ?: "decimal"
                    "lvlText" -> text = p.a("val") ?: ""
                    "isLgl" -> lgl = p.onOff()
                    "pPr" -> pp = p.readPP()
                    "rPr" -> rp = p.readRP()
                }
            }
            return Lvl(start, fmt, text, pp, rp, lgl)
        }

        fun lvl(numId: String, ilvl: Int): Lvl? {
            val (abs, ov) = nums[numId] ?: return null
            return ov[ilvl]?.second ?: abstractNums[abs]?.get(ilvl)
        }

        /** Advances the counters of (numId, ilvl) and returns the marker text. */
        fun marker(numId: String, ilvl: Int): Pair<String, Lvl>? {
            val lv = lvl(numId, ilvl) ?: return null
            if (lv.fmt == "none") return "" to lv
            if (lv.fmt == "bullet") return bulletFor(lv.text, ilvl) to lv
            val c = counters.getOrPut(numId) { IntArray(9) { Int.MIN_VALUE } }
            val startOf = { l: Int -> nums[numId]?.second?.get(l)?.first ?: lvl(numId, l)?.start ?: 1 }
            c[ilvl] = if (c[ilvl] == Int.MIN_VALUE) startOf(ilvl) else c[ilvl] + 1
            for (d in ilvl + 1 until 9) c[d] = Int.MIN_VALUE
            val text = Regex("%(\\d)").replace(lv.text) { m ->
                val l = m.groupValues[1].toInt() - 1
                if (l !in 0..8) return@replace ""
                val v = if (c[l] == Int.MIN_VALUE) startOf(l) else c[l]
                val f = if (lv.isLgl && l != ilvl) "decimal" else (lvl(numId, l)?.fmt ?: "decimal")
                formatNumber(v, f)
            }
            return text to lv
        }

        // ------------------------------------------------ rels

        fun parseRels(name: String, baseDir: String): Map<String, Pair<String, Boolean>> {
            val input = open(name) ?: return emptyMap()
            val out = HashMap<String, Pair<String, Boolean>>()
            input.use { s ->
                val p = newParser(s)
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                        val id = p.a("Id") ?: continue
                        val target = p.a("Target") ?: continue
                        val external = p.a("TargetMode").equals("External", true)
                        out[id] = (if (external) target else resolvePath(baseDir, target)) to external
                    }
                }
            }
            return out
        }

        private fun resolvePath(base: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val parts = ArrayList(base.split('/').filter { it.isNotEmpty() })
            for (seg in target.split('/')) {
                when (seg) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); ".", "" -> {}; else -> parts.add(seg) }
            }
            return parts.joinToString("/")
        }

        // ------------------------------------------------ body

        /** Reads block content (paragraphs, tables, content controls) until the end of the current element. */
        fun parseBlocks(p: XmlPullParser, rels: Map<String, Pair<String, Boolean>>, out: MutableList<DocBlock>, footnote: Int = 0) {
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) return
                if (ev == XmlPullParser.END_TAG && p.depth == depth) return
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "p" -> parseParagraph(p, rels, out, footnote)
                    "tbl" -> out.add(parseTable(p, rels, footnote))
                    "sectPr" -> page = readSection(p)
                    // transparent containers: descend into their children
                    "sdt", "sdtContent", "customXml", "smartTag", "ins", "moveTo" -> {}
                    else -> p.skipTree()
                }
            }
        }

        /** Body-level `w:sectPr`: page size and margins (twips → pt). */
        private fun readSection(p: XmlPullParser): PageSpec {
            var spec = PageSpec.A4
            p.children { n ->
                when (n) {
                    "pgSz" -> {
                        val w = p.a("w")?.toFloatOrNull()?.div(20f)
                        val h = p.a("h")?.toFloatOrNull()?.div(20f)
                        if (w != null && h != null && w in 144f..2880f && h in 144f..2880f) spec = spec.copy(w = w, h = h)
                    }
                    "pgMar" -> {
                        fun m(k: String, d: Float) = p.a(k)?.toFloatOrNull()?.let { kotlin.math.abs(it) / 20f }?.coerceIn(0f, 288f) ?: d
                        spec = spec.copy(top = m("top", spec.top), bottom = m("bottom", spec.bottom),
                            left = m("left", spec.left) + m("gutter", 0f), right = m("right", spec.right))
                    }
                }
            }
            // Keep a usable text column even for odd margins.
            if (spec.w - spec.left - spec.right < 144f) spec = spec.copy(left = 54f, right = 54f)
            if (spec.h - spec.top - spec.bottom < 144f) spec = spec.copy(top = 54f, bottom = 54f)
            return spec
        }

        private fun parseTable(p: XmlPullParser, rels: Map<String, Pair<String, Boolean>>, footnote: Int): DocBlock.Table {
            val grid = ArrayList<Float>()
            val rows = ArrayList<DocBlock.Row>()
            var rtl = false
            var borders: Boolean? = null
            var tblStyle: String? = null
            var headerRows = 0
            p.children { n ->
                when (n) {
                    "tblPr" -> p.children { t ->
                        when (t) {
                            "bidiVisual" -> rtl = p.onOff()
                            "tblStyle" -> tblStyle = p.a("val")
                            "tblBorders" -> borders = readBorders(p)
                            else -> p.skipTree()
                        }
                    }
                    "gridCol" -> grid.add((p.a("w")?.toFloatOrNull() ?: 0f) / 20f)
                    "tr" -> {
                        val (row, header) = parseRow(p, rels, footnote)
                        if (header && headerRows == rows.size) headerRows++
                        rows.add(row)
                    }
                    "tblGrid", "sdt", "sdtContent", "customXml" -> {}
                    else -> p.skipTree()
                }
            }
            val styled = tblStyle?.let { id ->
                var cur: String? = id; var g = 0; var r: Boolean? = null
                while (cur != null && r == null && g++ < 10) { val s = styles[cur]; r = s?.tableBorders; cur = s?.basedOn }
                r ?: id.contains("grid", true)
            }
            return DocBlock.Table(rows, if (grid.all { it > 0f }) grid else emptyList(), rtl, borders ?: styled ?: false,
                headerRows.coerceAtMost((rows.size - 1).coerceAtLeast(0)))
        }

        private fun parseRow(p: XmlPullParser, rels: Map<String, Pair<String, Boolean>>, footnote: Int): Pair<DocBlock.Row, Boolean> {
            val cells = ArrayList<DocBlock.Cell>()
            var header = false
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "tc" -> {
                        var span = 1; var fill: Int? = null; var merged = false
                        val blocks = ArrayList<DocBlock>()
                        val d2 = p.depth
                        while (true) {
                            val e2 = p.next()
                            if (e2 == XmlPullParser.END_DOCUMENT || (e2 == XmlPullParser.END_TAG && p.depth == d2)) break
                            if (e2 != XmlPullParser.START_TAG) continue
                            when (p.name) {
                                "tcPr" -> p.children { t ->
                                    when (t) {
                                        "gridSpan" -> span = p.a("val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                        "vMerge" -> merged = p.a("val").let { it == null || it == "continue" }
                                        "shd" -> fill = hex(p.a("fill"))
                                        else -> p.skipTree()
                                    }
                                }
                                "p" -> parseParagraph(p, rels, blocks, footnote)
                                "tbl" -> blocks.add(parseTable(p, rels, footnote))
                                "sdt", "sdtContent", "customXml" -> {}
                                else -> p.skipTree()
                            }
                        }
                        cells.add(DocBlock.Cell(span, if (merged) emptyList() else blocks, fill, merged))
                    }
                    "trPr" -> p.children { t -> if (t == "tblHeader") header = p.onOff() }
                    "tblPrEx" -> p.skipTree()
                    "sdt", "sdtContent", "customXml" -> {}
                    else -> p.skipTree()
                }
            }
            return DocBlock.Row(cells) to header
        }

        /** Parses one `w:p`. May emit several blocks: text segments split by inline images / page breaks. */
        private fun parseParagraph(p: XmlPullParser, rels: Map<String, Pair<String, Boolean>>, out: MutableList<DocBlock>, footnote: Int) {
            val depth = p.depth
            var direct = PP()
            val sb = StringBuilder()
            val spans = ArrayList<Span>()
            var runRP = RP()
            var inRun = false
            val linkStack = ArrayList<String?>()
            val fieldStack = ArrayList<StringBuilder?>()   // instr text while collecting, null after "separate"
            val fieldLinks = ArrayList<String?>()
            val pending = ArrayList<DocBlock>()             // images / dividers waiting to be emitted after the current segment
            var segments = 0
            var emitted = false
            var paraBase: RP? = null
            var paraPP: PP? = null

            fun resolved(): Pair<PP, RP> {
                paraPP?.let { return it to paraBase!! }
                val styleId = direct.pStyle ?: defaultParaStyle
                val pp = defPP + stylePP(styleId) + direct
                val rp = defRP + styleRP(styleId)
                paraPP = pp; paraBase = rp
                return pp to rp
            }

            fun currentLink(): String? = linkStack.lastOrNull { it != null } ?: fieldLinks.lastOrNull { it != null }

            fun addText(s: String, extra: RP? = null) {
                if (s.isEmpty()) return
                val (_, base) = resolved()
                var rp = base + styleRP(runRP.rStyle) + runRP + extra
                if (rp.vanish == true) return
                val text = if (rp.caps == true) s.uppercase() else s
                val cs = text.any { isRtlChar(it) }
                val fmt = rp.fmt(cs)
                val link = currentLink()
                val start = sb.length
                sb.append(text)
                val last = spans.lastOrNull()
                if (last != null && last.end == start && last.fmt == fmt && last.link == link) spans[spans.lastIndex] = Span(last.start, sb.length, fmt, link)
                else spans.add(Span(start, sb.length, fmt, link))
            }

            fun flush(final: Boolean) {
                val (pp, base) = resolved()
                val hasText = sb.isNotEmpty()
                if (!hasText && final && !emitted && pending.isEmpty() && direct.rule == true) {
                    out.add(DocBlock.Divider); emitted = true
                } else if (hasText || (final && !emitted && pending.isEmpty())) {
                    out.add(buildPara(pp, direct, base, sb.toString(), ArrayList(spans), direct.pStyle ?: defaultParaStyle, continuation = segments > 0, footnote = footnote))
                    segments++; emitted = true
                }
                sb.setLength(0); spans.clear()
                if (pending.isNotEmpty()) { out.addAll(pending); pending.clear(); emitted = true }
            }

            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT) break
                if (ev == XmlPullParser.END_TAG) {
                    if (p.depth == depth) break
                    when (p.name) {
                        "r" -> inRun = false
                        "hyperlink" -> if (linkStack.isNotEmpty()) linkStack.removeAt(linkStack.lastIndex)
                    }
                    continue
                }
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "pPr" -> {
                        direct = p.readPP()
                        if (footnote == 0 && (direct.pageBreakBefore == true || (stylePP(direct.pStyle).pageBreakBefore == true))) {
                            if (out.isNotEmpty() && out.last() != DocBlock.PageBreak) out.add(DocBlock.PageBreak)
                        }
                    }
                    "hyperlink" -> {
                        val id = p.a("id")
                        linkStack.add(id?.let { rels[it] }?.takeIf { it.second }?.first)
                    }
                    "r" -> { inRun = true; runRP = RP() }
                    "rPr" -> if (inRun) runRP = p.readRP() else p.skipTree()
                    "t" -> if (inRun) addText(p.nextText() ?: "") else p.skipTree()
                    "tab", "ptab" -> if (inRun) addText("\t")
                    "cr" -> if (inRun) addText("\n")
                    "noBreakHyphen" -> if (inRun) addText("‑")
                    "softHyphen" -> {}
                    "br" -> if (inRun) {
                        if (p.a("type") == "page") { pending.add(DocBlock.PageBreak); flush(false) } else addText("\n")
                    }
                    "sym" -> if (inRun) {
                        val code = p.a("char")?.toIntOrNull(16)
                        if (code != null) addText(String(Character.toChars(if (code >= 0xF000) code - 0xF000 else code)).let { s ->
                            when (s) { "·", "·", "\u0097" -> "•"; else -> s }
                        })
                    }
                    "footnoteReference" -> {
                        val id = p.a("id") ?: ""
                        val n = footnoteNumbers.getOrPut(id) { footnoteNumbers.size + 1 }
                        addText(n.toString(), RP(vert = 1))
                    }
                    "footnoteRef" -> if (footnote > 0) addText(footnote.toString(), RP(vert = 1))
                    "fldChar" -> when (p.a("fldCharType")) {
                        "begin" -> { fieldStack.add(StringBuilder()); fieldLinks.add(null) }
                        "separate" -> if (fieldStack.isNotEmpty()) {
                            val instr = fieldStack.last()?.toString()?.trim() ?: ""
                            if (instr.startsWith("HYPERLINK", true)) {
                                Regex("\"([^\"]+)\"").find(instr)?.groupValues?.get(1)?.takeIf { !instr.contains("\\l") }?.let {
                                    fieldLinks[fieldLinks.lastIndex] = it
                                }
                            }
                            fieldStack[fieldStack.lastIndex] = null
                        }
                        "end" -> if (fieldStack.isNotEmpty()) { fieldStack.removeAt(fieldStack.lastIndex); fieldLinks.removeAt(fieldLinks.lastIndex) }
                    }
                    "instrText" -> { val s = p.nextText() ?: ""; fieldStack.lastOrNull()?.append(s) }
                    "drawing", "pict", "object" -> {
                        val d = Drawing()
                        scanDrawing(p, d, rels, footnote)
                        val target = d.embed?.let { rels[it] }?.takeIf { !it.second }?.first
                        if (target != null && exists(target) && d.cx > 0f && d.cy > 0f) {
                            val (pp, _) = resolved()
                            val rtl = pp.bidi == true
                            pending.add(DocBlock.Image(target, d.cx / EMU_PER_PT, d.cy / EMU_PER_PT, d.alt, alignOf(pp.jc, rtl, rtl), rtl))
                        }
                        pending.addAll(d.inner)
                        if (pending.isNotEmpty()) flush(false)
                    }
                    "Fallback", "del", "moveFrom", "delText", "sdtPr", "sdtEndPr", "commentRangeStart", "rubyPr", "pPrChange", "rPrChange" -> p.skipTree()
                }
            }
            flush(true)
            if (direct.sectBreak == true && footnote == 0) out.add(DocBlock.PageBreak)
        }

        /** Scans a `w:drawing` / `w:pict` subtree for the picture reference, its size, alt text and text boxes. */
        private fun scanDrawing(p: XmlPullParser, d: Drawing, rels: Map<String, Pair<String, Boolean>>, footnote: Int) {
            val depth = p.depth
            while (true) {
                val ev = p.next()
                if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) return
                if (ev != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "extent" -> if (d.cx == 0f) {
                        d.cx = p.a("cx")?.toFloatOrNull() ?: 0f; d.cy = p.a("cy")?.toFloatOrNull() ?: 0f
                    }
                    "docPr" -> d.alt = p.a("descr") ?: p.a("title") ?: ""
                    "blip" -> if (d.embed == null) d.embed = p.a("embed")
                    "shape", "rect" -> if (d.cx == 0f) {
                        val style = p.a("style") ?: ""
                        fun dim(k: String) = Regex("(?:^|;)\\s*$k\\s*:\\s*([0-9.]+)(pt|in|px)?").find(style)?.let { m ->
                            val v = m.groupValues[1].toFloatOrNull() ?: 0f
                            when (m.groupValues[2]) { "in" -> v * 914400f; "px" -> v * 9525f; else -> v * 12700f }
                        } ?: 0f
                        d.cx = dim("width"); d.cy = dim("height")
                    }
                    "imagedata" -> if (d.embed == null) d.embed = p.a("id") ?: p.a("relid")
                    "txbxContent" -> parseBlocks(p, rels, d.inner, footnote)
                    "Fallback" -> p.skipTree()
                }
            }
        }

        fun buildPara(
            pp: PP, direct: PP, base: RP, text: String, spans: List<Span>, styleId: String?, continuation: Boolean, footnote: Int,
            markerFn: (String, Int) -> Pair<String, Lvl>? = { n, l -> marker(n, l) },
        ): DocBlock.Para {
            val heading = if (footnote > 0) 0 else headingLevel(styleId, pp.outline)
            val styleRp = styleRP(styleId)
            var baseRp = base
            if (heading > 0) {
                // Fallback heading sizes when the style chain does not specify one (H1 26sp, H2 22sp, H3 18sp at 1.4 sp/pt).
                if (styleRp.sz == null) baseRp = baseRp.copy(sz = when (heading) { 1 -> if (isTitle(styleId)) 22f else 18.6f; 2 -> 15.7f; 3 -> 12.9f; else -> (base.sz ?: 11f) * 1.05f })
                if (styleRp.b == null) baseRp = baseRp.copy(b = true)
            }
            val fixedSpans = if (heading > 0 && (styleRp.sz == null || styleRp.b == null)) spans.map { s ->
                Span(s.start, s.end, s.fmt.copy(
                    sizePt = if (styleRp.sz == null && s.fmt.sizePt == (base.sz ?: 11f)) baseRp.sz ?: s.fmt.sizePt else s.fmt.sizePt,
                    bold = s.fmt.bold || styleRp.b == null,
                ), s.link)
            } else spans
            val explicitBidi = pp.bidi == true
            val rtl = explicitBidi || firstStrongRtl(text)

            var marker: String? = null
            var markerFmt: RunFmt? = null
            var lvlPP = PP()
            val numId = pp.numId
            if (!continuation && numId != null && numId != "0") {
                markerFn(numId, pp.ilvl ?: 0)?.let { (m, lv) ->
                    if (m.isNotEmpty()) { marker = m; markerFmt = (baseRp + lv.rp).fmt(m.any { isRtlChar(it) }) }
                    lvlPP = lv.pp
                }
            }
            // indent precedence: style < numbering level < direct paragraph formatting
            val effective = defPP + stylePP(styleId) +
                PP(indStart = lvlPP.indStart, indEnd = lvlPP.indEnd, hanging = lvlPP.hanging, firstLine = lvlPP.firstLine) +
                PP(indStart = direct.indStart, indEnd = direct.indEnd, hanging = direct.hanging, firstLine = direct.firstLine)
            fun tw(v: Float?) = (v ?: 0f) / 20f
            val hang = tw(effective.hanging)
            val first = tw(effective.firstLine)
            var indStart = tw(effective.indStart).coerceAtLeast(0f)
            val markerW: Float
            if (marker != null) {
                markerW = hang.coerceAtLeast(18f)
                indStart = (indStart - hang).coerceAtLeast(0f)
            } else {
                markerW = 0f
            }
            val lineMult = when (pp.lineRule) {
                null, "auto" -> (pp.line ?: 240f) / 240f
                else -> ((pp.line ?: 240f) / 20f) / ((baseRp.sz ?: 11f) * 1.17f)
            }.coerceIn(0.8f, 3f)
            return DocBlock.Para(
                pid = pid++,
                text = text,
                spans = fixedSpans,
                basePt = baseRp.sz ?: 11f,
                align = alignOf(pp.jc, explicitBidi, rtl),
                rtl = rtl,
                indStart = indStart.coerceAtMost(216f),
                indEnd = tw(effective.indEnd).coerceIn(0f, 216f),
                firstLine = if (marker != null) 0f else (if (hang > 0f) -hang else first).coerceIn(-144f, 144f),
                marker = marker,
                markerFmt = markerFmt,
                markerWidth = markerW.coerceAtMost(72f),
                before = if (continuation) 0f else ((pp.before ?: 0f) / 20f).coerceIn(0f, 36f),
                after = ((pp.after ?: 0f) / 20f).coerceIn(0f, 36f),
                lineMult = lineMult,
                heading = heading,
            )
        }

        // ------------------------------------------------ footnotes

        fun parseFootnotes(input: InputStream, rels: Map<String, Pair<String, Boolean>>): Map<String, List<DocBlock>> {
            val p = newParser(input)
            val out = HashMap<String, List<DocBlock>>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "footnote") {
                    val id = p.a("id") ?: continue
                    val type = p.a("type")
                    val n = footnoteNumbers[id]
                    if (n == null || (type != null && type != "normal")) { p.skipTree(); continue }
                    val blocks = ArrayList<DocBlock>()
                    parseBlocks(p, rels, blocks, footnote = n)
                    out[id] = blocks
                }
            }
            return out
        }
    }

    // ---------------------------------------------------------------- editor support

    /** Font family key for a font name (shared by the viewer and the editor). null = default sans. */
    fun fontKeyOf(name: String): String? {
        val f = name.lowercase()
        return when {
            f.contains("courier") || f.contains("consolas") || f.contains("mono") || f.contains("menlo") -> "mono"
            f.contains("cairo") -> "cairo"
            f.contains("amiri") || f.contains("traditional arabic") -> "amiri"
            f.contains("tehreer") -> "tehreer"
            f.contains("sans") -> null
            f.contains("times") || f.contains("georgia") || f.contains("cambria") || f.contains("garamond") ||
                f.contains("serif") || f.contains("palatino") || f.contains("book antiqua") || f.contains("minion") -> "serif"
            else -> null
        }
    }

    /**
     * Style / numbering resolution for the editor: the same rules as [parse], applied to the property XML the editor
     * writes, so what the editor shows is what the saved file shows. [ns] = the xmlns declarations of the document root
     * (raw property XML copied from the document may use any prefix declared there). Thread-safe (synchronized).
     */
    internal class StyleSession private constructor(
        private val ctx: Ctx, private val rels: Map<String, Pair<String, Boolean>>, private val ns: String, private val names: MutableSet<String>,
    ) {
        /** Resolved paragraph properties: [para] is an empty-text template (marker text left to [markerText]). */
        class ParaLook(val para: DocBlock.Para, val styleId: String?, val numId: String?, val ilvl: Int, val numFmt: String?) {
            val bullet: Boolean get() = numFmt == "bullet"
        }

        private val lookCache = HashMap<String, ParaLook>()
        private val runCache = HashMap<String, RunFmt>()

        private fun parser(xml: String): XmlPullParser = newParser(java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        @Synchronized
        fun paraLook(pPr: String?): ParaLook = lookCache.getOrPut(pPr ?: "") {
            val direct = if (pPr.isNullOrBlank()) PP() else runCatching {
                val p = parser("<x $ns>$pPr</x>")
                if (p.seekStart("pPr")) p.readPP() else PP()
            }.getOrDefault(PP())
            val styleId = direct.pStyle ?: ctx.defaultParaStyle
            val pp = ctx.defPP + ctx.stylePP(styleId) + direct
            val base = ctx.defRP + ctx.styleRP(styleId)
            val numId = pp.numId?.takeIf { it != "0" }
            val ilvl = (pp.ilvl ?: 0).coerceIn(0, 8)
            val lv = numId?.let { ctx.lvl(it, ilvl) }
            val para = ctx.buildPara(pp, direct, base, "", emptyList(), styleId, continuation = false, footnote = 0,
                markerFn = { _, _ -> lv?.let { (if (it.fmt == "none") "" else "0") to it } })
            ParaLook(para, styleId, if (lv != null) numId else null, ilvl, lv?.fmt)
        }

        /** Resolved run formatting: document defaults + paragraph style + character style + direct [rPr]. */
        @Synchronized
        fun runFmt(styleId: String?, rPr: String?, cs: Boolean): RunFmt = runCache.getOrPut("${styleId ?: ""}|$cs|${rPr ?: ""}") {
            val direct = if (rPr.isNullOrBlank()) RP() else runCatching {
                val p = parser("<x $ns>$rPr</x>")
                if (p.seekStart("rPr")) p.readRP() else RP()
            }.getOrDefault(RP())
            (ctx.defRP + ctx.styleRP(styleId ?: ctx.defaultParaStyle) + ctx.styleRP(direct.rStyle) + direct).fmt(cs)
        }

        /** True when the paragraph style chain (or document default) sets bold / a size, so headings don't need the fallback. */
        @Synchronized
        fun styleSetsSize(styleId: String?): Boolean = ctx.styleRP(styleId ?: ctx.defaultParaStyle).sz != null

        @Synchronized
        fun resetCounters() = ctx.counters.clear()

        /** Next list marker of (numId, ilvl) in document order (call [resetCounters] before a pass). */
        @Synchronized
        fun markerText(numId: String, ilvl: Int): String? = ctx.marker(numId, ilvl)?.first

        /** Read-only preview of preserved body XML (tables, content controls, locked paragraphs). */
        @Synchronized
        fun parseFragment(xml: String): List<DocBlock> = runCatching {
            val out = ArrayList<DocBlock>()
            val p = parser("<w:body $ns>$xml</w:body>")
            if (p.seekStart("body")) ctx.parseBlocks(p, rels, out)
            out as List<DocBlock>
        }.getOrDefault(emptyList())

        /** Style id for a built-in kind: Normal, Title, Heading1..Heading6, Quote — matched by id or name. */
        @Synchronized
        fun styleIdFor(kind: String): String? {
            val want = kind.lowercase()
            ctx.styles.values.firstOrNull { it.type == "paragraph" && it.id.equals(kind, true) }?.let { return it.id }
            return ctx.styles.values.firstOrNull { it.type == "paragraph" && it.name.lowercase().replace(" ", "") == want }?.id
        }

        /** Built-in kind of a paragraph style (see [styleIdFor]) or null for other styles. */
        @Synchronized
        fun kindOf(styleId: String?): String? {
            val s = ctx.styles[styleId ?: ctx.defaultParaStyle ?: return "Normal"] ?: return if (styleId == null) "Normal" else null
            val n = s.name.lowercase().replace(" ", "")
            return when {
                n == "normal" || s.id.equals("Normal", true) -> "Normal"
                n == "title" || s.id.equals("Title", true) -> "Title"
                n == "quote" || s.id.equals("Quote", true) -> "Quote"
                else -> Regex("^heading([1-6])$").find(n)?.let { "Heading" + it.groupValues[1] }
                    ?: Regex("^heading([1-6])$", RegexOption.IGNORE_CASE).find(s.id)?.let { "Heading" + it.groupValues[1] }
            }
        }

        /** True when the paragraph style chain carries numbering (turning a list off then needs numId 0). */
        @Synchronized
        fun styleHasNumbering(styleId: String?): Boolean = ctx.stylePP(styleId ?: ctx.defaultParaStyle).numId.let { it != null && it != "0" }

        /** Adds `w:style` elements (styles the editor appends to styles.xml). */
        @Synchronized
        fun addStyles(xml: String) {
            runCatching { ctx.parseStyles(java.io.ByteArrayInputStream("<w:styles $ns>$xml</w:styles>".toByteArray(Charsets.UTF_8))) }
            ctx.clearCaches(); lookCache.clear(); runCache.clear()
        }

        /** Adds `w:abstractNum` / `w:num` elements (lists the editor appends to numbering.xml). */
        @Synchronized
        fun addNumbering(xml: String) {
            runCatching { ctx.parseNumbering(java.io.ByteArrayInputStream("<w:numbering $ns>$xml</w:numbering>".toByteArray(Charsets.UTF_8))) }
            lookCache.clear()
        }

        @Synchronized
        fun addEntry(name: String) { names.add(name) }

        companion object {
            /** Loads styles, numbering and the main part's relationships of [file]. */
            fun open(file: File, docPart: String, ns: String): StyleSession = ZipFile(file).use { zip ->
                val names = HashSet<String>()
                val en = zip.entries()
                while (en.hasMoreElements()) names.add(en.nextElement().name)
                val ctx = Ctx({ n -> zip.getEntry(n)?.let { zip.getInputStream(it) } }, { it in names })
                zip.getEntry("word/styles.xml")?.let { e -> zip.getInputStream(e).use { ctx.parseStyles(it) } }
                zip.getEntry("word/numbering.xml")?.let { e -> zip.getInputStream(e).use { ctx.parseNumbering(it) } }
                val rels = ctx.parseRels(relsName(docPart), docPart.substringBeforeLast('/', ""))
                StyleSession(ctx, rels, ns, names)
            }
        }
    }

    // ---------------------------------------------------------------- formatting helpers

    /** Maps w:jc to 0 start, 1 center, 2 end, 3 justify. For non-bidi paragraphs left/right are absolute sides. */
    private fun alignOf(jc: String?, explicitBidi: Boolean, rtl: Boolean): Int = when (jc) {
        "center" -> 1
        "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> 3
        "start" -> 0
        "end" -> 2
        "left" -> if (explicitBidi || !rtl) 0 else 2
        "right" -> if (explicitBidi || !rtl) 2 else 0
        else -> 0
    }

    fun isRtlChar(c: Char): Boolean {
        val d = Character.getDirectionality(c)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    fun firstStrongRtl(s: String): Boolean {
        for (c in s) {
            when (Character.getDirectionality(c)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private val safeBullets = setOf("-", "–", "—", "*", "»", "→", "✓", "✔", "■", "□", "●", "○", "◆", "◇", "◦", "▪", "•", ">", "➢", "➤", "►", "▶")

    private fun bulletFor(text: String, level: Int): String =
        if (text in safeBullets) text else when (level % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }

    private val arabicAlpha = "أبتثجحخدذرزسشصضطظعغفقكلمنهوي"
    private val arabicAbjad = "أبجدهوزحطيكلمنسعفصقرشتثخذضظغ"

    fun formatNumber(n: Int, fmt: String): String = when (fmt) {
        "lowerLetter" -> letters(n).lowercase()
        "upperLetter" -> letters(n)
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
        "arabicAlpha" -> if (n >= 1) arabicAlpha[(n - 1) % arabicAlpha.length].toString() else n.toString()
        "arabicAbjad" -> if (n >= 1) arabicAbjad[(n - 1) % arabicAbjad.length].toString() else n.toString()
        "hindiNumbers", "hindiCounting" -> n.toString().map { if (it.isDigit()) ('٠' + (it - '0')) else it }.joinToString("")
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val c = ('A' + (n - 1) % 26)
        return c.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        return buildString { for (i in v.indices) while (x >= v[i]) { append(s[i]); x -= v[i] } }
    }
}
