package com.daftar.app.word

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset

/**
 * RTF reader: text with bold / italic / underline / strike / size / colour / super-sub, paragraphs with alignment,
 * indents, spacing and direction, headings from the style sheet or \outlinelevel, tables (\cell / \row), page breaks.
 * Control words are stripped, `\uN` gives Unicode (skipping the `\ucN` fallback characters) and `\'hh` bytes are decoded
 * with the font's \fcharset or the document's \ansicpg (Arabic 1256 included). Pictures, headers, footers and other
 * destinations are skipped.
 */
internal object RtfReader {

    private val skipDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
        "footerl", "footerr", "footerf", "footnote", "annotation", "object", "fldinst", "xmlnstbl", "listtable",
        "listoverridetable", "revtbl", "rsidtbl", "generator", "themedata", "colorschememapping", "datastore",
        "latentstyles", "pgdsctbl", "mmathPr", "bkmkstart", "bkmkend", "filetbl", "nonshppict", "shpinst", "sp",
        "atnid", "atnauthor", "atrfstart", "atrfend", "comment", "author", "operator", "title", "subject", "keywords",
        "doccomm", "company", "category", "template", "listpicture", "protusertbl", "userprops", "wgrffmtfilter",
        "fchars", "lchars", "aftnsep", "aftnsepc", "ftnsep", "ftnsepc", "ftncn", "aftncn", "pn", "pnseclvl",
        "passwordhash", "panose", "falt", "objdata", "objclass", "result",
    )

    private class CharState(
        var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false,
        var halfPts: Int = 24, var font: Int = -1, var color: Int = 0, var highlight: Int = 0, var vert: Int = 0,
        var uc: Int = 1, var skip: Boolean = false, var hidden: Boolean = false,
    ) {
        fun copy() = CharState(bold, italic, underline, strike, halfPts, font, color, highlight, vert, uc, skip, hidden)
        fun reset() { bold = false; italic = false; underline = false; strike = false; halfPts = 24; color = 0; highlight = 0; vert = 0; hidden = false }
    }

    private class ParaState(
        var align: Char = 'l', var rtl: Boolean = false, var li: Int = 0, var ri: Int = 0, var fi: Int = 0,
        var sb: Int = 0, var sa: Int = 0, var style: Int = 0, var outline: Int = -1, var inTable: Boolean = false, var sl: Int = 0,
        var alignSet: Boolean = false,
    ) {
        fun reset() { align = 'l'; rtl = false; li = 0; ri = 0; fi = 0; sb = 0; sa = 0; style = 0; outline = -1; inTable = false; sl = 0; alignSet = false }
    }

    fun parse(f: File): DocxDoc {
        val bytes = f.readBytes()
        return Parser(bytes, f.absolutePath).run()
    }

    private class Parser(val b: ByteArray, val path: String) {
        var pos = 0
        var codepage: Charset = Charset.forName("windows-1252")
        val fontCharset = HashMap<Int, Charset?>()
        val symbolFonts = HashSet<Int>()
        val colors = ArrayList<Int?>()
        val styleHeading = HashMap<Int, Int>()
        val pids = PidGen()
        val blocks = ArrayList<DocBlock>()
        var cs = CharState()
        val stack = ArrayList<CharState>()
        val ps = ParaState()
        val para = ParaText()
        val pendingBytes = ByteArrayOutputStream()
        var ucSkip = 0
        var defaultFont = 0
        // table state
        val cellBlocks = ArrayList<DocBlock>()
        val rowCells = ArrayList<DocBlock.Cell>()
        val tableRows = ArrayList<DocBlock.Row>()
        val cellX = ArrayList<Int>()
        var tableRtl = false
        var pageW = 11906f / 20f; var pageH = 16838f / 20f
        var margL = 1800f / 20f; var margR = 1800f / 20f; var margT = 1440f / 20f; var margB = 1440f / 20f

        fun run(): DocxDoc {
            if (!String(b, 0, minOf(b.size, 5), Charsets.ISO_8859_1).startsWith("{\\rtf")) throw IllegalStateException("not rtf")
            while (pos < b.size) {
                val c = b[pos].toInt() and 0xFF
                when (c) {
                    '{'.code -> { flushBytes(); stack.add(cs.copy()); pos++ }
                    '}'.code -> { flushBytes(); if (stack.isNotEmpty()) cs = stack.removeAt(stack.lastIndex); pos++ }
                    '\\'.code -> controlWord()
                    '\r'.code, '\n'.code -> pos++
                    else -> { text(c); pos++ }
                }
            }
            flushBytes()
            endParagraph(final = true)
            closeTable()
            val page = PageSpec(pageW, pageH, margT, margB, margL, margR).let { s ->
                if (s.w !in 144f..2880f || s.h !in 144f..2880f || s.contentW < 144f || s.contentH < 144f) PageSpec.A4 else s
            }
            while (blocks.lastOrNull() == DocBlock.PageBreak) blocks.removeAt(blocks.lastIndex)
            return DocxDoc(path, blocks, headingsOf(blocks), page, DocKind.RTF)
        }

        // ------------------------------------------------ tokens

        private fun text(c: Int) {
            if (cs.skip) return
            if (ucSkip > 0) { ucSkip--; return }
            if (c >= 0x80) { pendingBytes.write(c); return }
            flushBytes()
            if (c == '\t'.code) appendText("\t") else if (c >= 0x20) appendText(c.toChar().toString())
        }

        private fun controlWord() {
            pos++ // backslash
            if (pos >= b.size) return
            val c = b[pos].toInt() and 0xFF
            if (!isAlpha(c)) {
                // control symbol
                pos++
                when (c.toChar()) {
                    '\'' -> {
                        if (pos + 2 <= b.size) {
                            val v = String(b, pos, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                            pos += 2
                            if (v != null && !cs.skip) { if (ucSkip > 0) ucSkip-- else pendingBytes.write(v) }
                        }
                    }
                    '\\', '{', '}' -> { flushBytes(); if (!cs.skip) { if (ucSkip > 0) ucSkip-- else appendText(c.toChar().toString()) } }
                    '~' -> sym(" ")
                    '_' -> sym("‑")
                    '-' -> {}
                    // {\* … } = ignorable destination we don't understand: skip the whole group
                    '*' -> cs.skip = true
                    '\n', '\r' -> { flushBytes(); endParagraph() }
                    '\t' -> sym("\t")
                }
                return
            }
            val start = pos
            while (pos < b.size && isAlpha(b[pos].toInt() and 0xFF)) pos++
            val word = String(b, start, pos - start, Charsets.ISO_8859_1)
            var param: Int? = null
            if (pos < b.size && (b[pos] == '-'.code.toByte() || isDigit(b[pos].toInt()))) {
                val ps0 = pos
                pos++
                while (pos < b.size && isDigit(b[pos].toInt())) pos++
                param = String(b, ps0, pos - ps0, Charsets.ISO_8859_1).toIntOrNull()
            }
            if (pos < b.size && b[pos] == ' '.code.toByte()) pos++
            if (word != "u" && word != "bin") flushBytes()
            handle(word, param)
        }

        private fun sym(s: String) { flushBytes(); if (!cs.skip) { if (ucSkip > 0) ucSkip-- else appendText(s) } }

        private fun isAlpha(c: Int) = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code)
        private fun isDigit(c: Int) = c in '0'.code..'9'.code

        private fun handle(w: String, p: Int?) {
            if (w in skipDestinations) {
                when (w) {
                    "fonttbl" -> { parseFontTable(); return }
                    "colortbl" -> { parseColorTable(); return }
                    "stylesheet" -> { parseStyleSheet(); return }
                }
                cs.skip = true
                return
            }
            if (cs.skip) {
                if (w == "bin" && p != null) pos = (pos + p).coerceAtMost(b.size)
                return
            }
            when (w) {
                "ansicpg" -> p?.let { TextDecode.charsetForCodepage(it)?.let { c -> codepage = c } }
                "deff" -> defaultFont = p ?: 0
                "u" -> {
                    flushBytes()
                    var v = p ?: return
                    if (v < 0) v += 65536
                    appendText(v.toChar().toString())
                    ucSkip = cs.uc
                }
                "uc" -> cs.uc = (p ?: 1).coerceIn(0, 8)
                "bin" -> if (p != null) pos = (pos + p).coerceAtMost(b.size)
                "par" -> endParagraph()
                "line" -> appendText("\n")
                "tab" -> appendText("\t")
                "page" -> { endParagraph(); closeTable(); blocks.add(DocBlock.PageBreak) }
                "sect" -> endParagraph()
                "emdash" -> appendText("—")
                "endash" -> appendText("–")
                "bullet" -> appendText("•")
                "lquote" -> appendText("‘")
                "rquote" -> appendText("’")
                "ldblquote" -> appendText("“")
                "rdblquote" -> appendText("”")
                "emspace" -> appendText(" ")
                "enspace" -> appendText(" ")
                "zwj" -> appendText("‍")
                "zwnj" -> appendText("‌")
                "ltrmark" -> appendText("‎")
                "rtlmark" -> appendText("‏")
                // character formatting
                "plain" -> cs.reset()
                "b" -> cs.bold = p != 0
                "i" -> cs.italic = p != 0
                "ul", "uld", "uldb", "ulw", "ulth", "uldash" -> cs.underline = p != 0
                "ulnone" -> cs.underline = false
                "strike", "striked" -> cs.strike = p != 0
                "fs" -> cs.halfPts = (p ?: 24).coerceIn(4, 400)
                "f" -> cs.font = p ?: 0
                "cf" -> cs.color = p ?: 0
                "highlight", "cb", "chcbpat" -> cs.highlight = p ?: 0
                "super" -> cs.vert = 1
                "sub" -> cs.vert = 2
                "nosupersub" -> cs.vert = 0
                "v" -> cs.hidden = p != 0
                // paragraph formatting
                "pard" -> ps.reset()
                "ql" -> { ps.align = 'l'; ps.alignSet = true }
                "qr" -> { ps.align = 'r'; ps.alignSet = true }
                "qc" -> { ps.align = 'c'; ps.alignSet = true }
                "qj", "qd" -> { ps.align = 'j'; ps.alignSet = true }
                "rtlpar" -> ps.rtl = true
                "ltrpar" -> ps.rtl = false
                "li", "lin" -> ps.li = p ?: 0
                "ri", "rin" -> ps.ri = p ?: 0
                "fi" -> ps.fi = p ?: 0
                "sb" -> ps.sb = p ?: 0
                "sa" -> ps.sa = p ?: 0
                "sl" -> ps.sl = p ?: 0
                "s" -> ps.style = p ?: 0
                "outlinelevel" -> ps.outline = p ?: -1
                "intbl" -> ps.inTable = true
                // tables
                "trowd" -> { cellX.clear(); tableRtl = false }
                "rtlrow" -> tableRtl = true
                "cellx" -> p?.let { cellX.add(it) }
                "cell" -> endCell()
                "row" -> endRow()
                // page setup
                "paperw" -> p?.let { pageW = it / 20f }
                "paperh" -> p?.let { pageH = it / 20f }
                "margl" -> p?.let { margL = it / 20f }
                "margr" -> p?.let { margR = it / 20f }
                "margt" -> p?.let { margT = it / 20f }
                "margb" -> p?.let { margB = it / 20f }
            }
        }

        // ------------------------------------------------ text accumulation

        private fun currentCharset(): Charset {
            val f = if (cs.font >= 0) cs.font else defaultFont
            return fontCharset[f] ?: codepage
        }

        private fun flushBytes() {
            if (pendingBytes.size() == 0) return
            val bytes = pendingBytes.toByteArray()
            pendingBytes.reset()
            if (cs.skip) return
            val f = if (cs.font >= 0) cs.font else defaultFont
            val s = if (f in symbolFonts) String(CharArray(bytes.size) { i -> symbolChar(bytes[i].toInt() and 0xFF) })
            else String(bytes, currentCharset())
            appendText(s)
        }

        private fun symbolChar(c: Int): Char = when (c) {
            0xB7 -> '•'; 0xA7 -> '▪'; 0xD8 -> '➢'; 0xFC -> '✓'; 0x6F -> '○'; 0x71 -> '❑'; 0x76 -> '❖'; 0xA8 -> '□'
            else -> if (c in 0x20..0x7E) c.toChar() else '•'
        }

        private fun appendText(s: String) {
            if (cs.skip || cs.hidden) return
            val col = colors.getOrNull(cs.color)
            val bg = colors.getOrNull(cs.highlight)
            val fmt = RunFmt(
                bold = cs.bold, italic = cs.italic, underline = cs.underline, strike = cs.strike,
                sizePt = cs.halfPts / 2f, color = col, background = bg, vert = cs.vert,
                mono = false,
            )
            para.append(s, fmt)
        }

        private fun buildParagraph(): DocBlock.Para {
            val text = para.sb.toString()
            val spans = ArrayList(para.spans)
            para.clear()
            val heading = styleHeading[ps.style] ?: if (ps.outline in 0..5) ps.outline + 1 else 0
            val base = spans.groupBy { it.fmt.sizePt }.maxByOrNull { e -> e.value.sumOf { it.end - it.start } }?.key ?: 12f
            val rtl = ps.rtl || DocxParser.firstStrongRtl(text)
            // \ql / \qr are physical sides; map to start / end for the paragraph direction. No explicit alignment = start.
            val align = when {
                !ps.alignSet -> 0
                ps.align == 'c' -> 1
                ps.align == 'j' -> 3
                ps.align == 'r' -> if (rtl) 0 else 2
                else -> if (rtl) 2 else 0
            }
            val li = ps.li / 20f; val fi = ps.fi / 20f
            val line = when {
                ps.sl > 0 -> (ps.sl / 20f) / (base * LINE_FACTOR)
                ps.sl < 0 -> (-ps.sl / 20f) / (base * LINE_FACTOR)
                else -> 1f
            }.coerceIn(0.8f, 3f)
            return simplePara(
                pids, text, spans, base, align, rtl,
                indStart = li.coerceIn(0f, 216f), indEnd = (ps.ri / 20f).coerceIn(0f, 216f), firstLine = fi.coerceIn(-144f, 144f),
                before = (ps.sb / 20f).coerceIn(0f, 48f), after = (ps.sa / 20f).coerceIn(0f, 48f), lineMult = line,
                heading = heading,
            ).let { p ->
                if (heading > 0 && spans.none { it.fmt.bold }) {
                    DocBlock.Para(p.pid, p.text, p.spans.map { Span(it.start, it.end, it.fmt.copy(bold = true), it.link) }, p.basePt, p.align, p.rtl,
                        p.indStart, p.indEnd, p.firstLine, p.marker, p.markerFmt, p.markerWidth, maxOf(p.before, 10f), maxOf(p.after, 4f), p.lineMult, heading)
                } else p
            }
        }

        private fun endParagraph(final: Boolean = false) {
            flushBytes()
            if (final && para.isEmpty) return
            if (ps.inTable) {
                cellBlocks.add(buildParagraph())
                return
            }
            closeTable()
            blocks.add(buildParagraph())
        }

        private fun endCell() {
            flushBytes()
            if (!para.isEmpty || cellBlocks.isEmpty()) cellBlocks.add(buildParagraph())
            rowCells.add(DocBlock.Cell(1, ArrayList(cellBlocks), null, false))
            cellBlocks.clear()
        }

        private fun endRow() {
            flushBytes()
            if (!para.isEmpty) endCell()
            if (rowCells.isNotEmpty()) tableRows.add(DocBlock.Row(ArrayList(rowCells)))
            rowCells.clear()
            if (cellX.isNotEmpty()) lastCellX = ArrayList(cellX)
        }

        var lastCellX: List<Int> = emptyList()

        private fun closeTable() {
            if (rowCells.isNotEmpty()) endRow()
            if (tableRows.isEmpty()) return
            val cols = tableRows.maxOf { it.cells.size }
            val grid = if (lastCellX.size == cols) {
                var prev = 0
                lastCellX.map { x -> val w = (x - prev) / 20f; prev = x; w.coerceAtLeast(18f) }
            } else emptyList()
            blocks.add(DocBlock.Table(ArrayList(tableRows), grid, tableRtl, borders = true))
            tableRows.clear()
        }

        // ------------------------------------------------ tables in the header

        /** Reads the group content until its closing brace (the opening brace was already consumed). */
        private fun groupBytes(): ByteArray {
            val start = pos
            var depth = 1
            while (pos < b.size && depth > 0) {
                when (b[pos].toInt()) {
                    '\\'.code -> pos++
                    '{'.code -> depth++
                    '}'.code -> depth--
                }
                pos++
            }
            val end = (pos - 1).coerceAtLeast(start)
            // the closing brace of this group was consumed here: restore the state pushed by '{'
            if (stack.isNotEmpty()) cs = stack.removeAt(stack.lastIndex)
            return b.copyOfRange(start, end)
        }

        private fun parseFontTable() {
            val s = String(groupBytes(), Charsets.ISO_8859_1)
            Regex("\\\\f(\\d+)([^;]*);").findAll(s).forEach { m ->
                val idx = m.groupValues[1].toInt()
                val body = m.groupValues[2]
                val cset = Regex("\\\\fcharset(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
                val cp = Regex("\\\\cpg(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
                if (cset == 2 || body.contains("Symbol", true) || body.contains("Wingdings", true)) symbolFonts.add(idx)
                val charset = cp?.let { TextDecode.charsetForCodepage(it) } ?: cset?.let { charsetFor(it) }
                if (charset != null) fontCharset[idx] = charset
            }
        }

        private fun charsetFor(fcharset: Int): Charset? = when (fcharset) {
            0, 1 -> null // ANSI / default → \ansicpg
            161 -> TextDecode.charsetForCodepage(1253)
            162 -> TextDecode.charsetForCodepage(1254)
            163 -> TextDecode.charsetForCodepage(1258)
            177 -> TextDecode.charsetForCodepage(1255)
            178, 179, 180, 181 -> TextDecode.charsetForCodepage(1256)
            186 -> TextDecode.charsetForCodepage(1257)
            204 -> TextDecode.charsetForCodepage(1251)
            222 -> TextDecode.charsetForCodepage(874)
            238 -> TextDecode.charsetForCodepage(1250)
            128 -> TextDecode.charsetForCodepage(932)
            129 -> TextDecode.charsetForCodepage(949)
            134 -> TextDecode.charsetForCodepage(936)
            136 -> TextDecode.charsetForCodepage(950)
            77 -> TextDecode.charsetForCodepage(10000)
            else -> null
        }

        private fun parseColorTable() {
            val s = String(groupBytes(), Charsets.ISO_8859_1)
            colors.clear()
            for (entry in s.split(';').dropLast(1)) {
                val r = Regex("\\\\red(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull()
                val g = Regex("\\\\green(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull()
                val bl = Regex("\\\\blue(\\d+)").find(entry)?.groupValues?.get(1)?.toIntOrNull()
                colors.add(if (r == null && g == null && bl == null) null else (0xFF shl 24) or ((r ?: 0) shl 16) or ((g ?: 0) shl 8) or (bl ?: 0))
            }
        }

        private fun parseStyleSheet() {
            val s = String(groupBytes(), Charsets.ISO_8859_1)
            val controlWord = Regex("\\\\[a-zA-Z]+-?\\d* ?|\\\\'[0-9a-fA-F]{2}|\\\\.")
            Regex("\\{([^{}]*)}").findAll(s).forEach { m ->
                val body = m.groupValues[1]
                val idx = Regex("\\\\s(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val name = body.replace(controlWord, "").substringBefore(';').trim().lowercase()
                val level = Regex("^heading\\s*(\\d)$").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (name == "title") 1 else null
                if (level != null) styleHeading[idx] = level.coerceIn(1, 6)
            }
        }
    }
}
