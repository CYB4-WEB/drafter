package com.daftar.app.word

import java.io.File
import java.nio.charset.Charset

/** Largest prefix of a plain-text / Markdown / log file that is read (keeps memory and layout time bounded). */
internal const val MAX_TEXT_BYTES = 8L * 1024 * 1024
/** Largest prefix of a CSV / TSV file that is read, and the row cap. */
internal const val MAX_CSV_BYTES = 24L * 1024 * 1024
internal const val MAX_CSV_ROWS = 200_000
private const val MAX_CSV_COLS = 400

// ------------------------------------------------------------------ decoding

/** Text decoding with BOM detection, strict UTF-8 check and a Windows-1256 / 1252 guess for legacy 8-bit files. */
internal object TextDecode {

    class Read(val text: String, val truncatedAt: Long)

    fun readFile(f: File, limit: Long): Read {
        val len = f.length()
        val n = minOf(len, limit).toInt()
        val bytes = ByteArray(n)
        f.inputStream().use { s ->
            var off = 0
            while (off < n) { val r = s.read(bytes, off, n - off); if (r < 0) break; off += r }
        }
        return Read(decode(bytes), if (len > limit) limit else 0L)
    }

    fun decode(b: ByteArray): String {
        val n = b.size
        if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, n - 3, Charsets.UTF_8)
        if (n >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, (n - 2) and 1.inv(), Charsets.UTF_16LE)
        if (n >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, (n - 2) and 1.inv(), Charsets.UTF_16BE)
        // UTF-16 without BOM: lots of zero bytes on one parity.
        if (n >= 8) {
            var zEven = 0; var zOdd = 0
            val m = minOf(n, 4096)
            for (i in 0 until m) if (b[i].toInt() == 0) { if (i % 2 == 0) zEven++ else zOdd++ }
            if (zOdd > m / 4 && zEven < m / 50) return String(b, 0, n and 1.inv(), Charsets.UTF_16LE)
            if (zEven > m / 4 && zOdd < m / 50) return String(b, 0, n and 1.inv(), Charsets.UTF_16BE)
        }
        val valid = utf8ValidLength(b)
        if (valid >= 0) return String(b, 0, valid, Charsets.UTF_8)
        return String(b, legacyCharset(b))
    }

    /** Length of the valid UTF-8 prefix (a truncated sequence at the very end is dropped), or -1 if the bytes are not UTF-8. */
    private fun utf8ValidLength(b: ByteArray): Int {
        var i = 0
        val n = b.size
        while (i < n) {
            val c = b[i].toInt() and 0xFF
            val need = when {
                c < 0x80 -> 0
                c in 0xC2..0xDF -> 1
                c in 0xE0..0xEF -> 2
                c in 0xF0..0xF4 -> 3
                else -> return -1
            }
            if (i + need >= n && need > 0) return if (n - i <= 3) i else -1
            for (k in 1..need) if ((b[i + k].toInt() and 0xC0) != 0x80) return -1
            i += need + 1
        }
        return n
    }

    /** Arabic text in Windows-1256 is dense in high bytes; Western 1252 text uses them sparsely (accents). */
    fun legacyCharset(b: ByteArray): Charset {
        var high = 0; var letters = 0
        val m = minOf(b.size, 65536)
        for (i in 0 until m) {
            val c = b[i].toInt() and 0xFF
            if (c >= 0xC0) { high++; letters++ } else if ((c or 0x20) in 'a'.code..'z'.code) letters++
        }
        val name = if (letters > 0 && high * 10 > letters * 4) "windows-1256" else "windows-1252"
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }

    fun charsetForCodepage(cp: Int): Charset? = runCatching {
        when (cp) {
            65001 -> Charsets.UTF_8
            932 -> Charset.forName("Shift_JIS")
            936 -> Charset.forName("GBK")
            949 -> Charset.forName("EUC-KR")
            950 -> Charset.forName("Big5")
            10000 -> Charset.forName("x-MacRoman")
            else -> Charset.forName("windows-$cp")
        }
    }.getOrNull()
}

// ------------------------------------------------------------------ paragraph building helpers

/** Accumulates text with formatted spans for one paragraph. */
internal class ParaText {
    val sb = StringBuilder()
    val spans = ArrayList<Span>()

    fun append(s: String, fmt: RunFmt, link: String? = null) {
        if (s.isEmpty()) return
        val start = sb.length
        sb.append(s)
        val last = spans.lastOrNull()
        if (last != null && last.end == start && last.fmt == fmt && last.link == link) spans[spans.lastIndex] = Span(last.start, sb.length, fmt, link)
        else spans.add(Span(start, sb.length, fmt, link))
    }

    val isEmpty get() = sb.isEmpty()
    fun clear() { sb.setLength(0); spans.clear() }
}

internal class PidGen { var next = 0 }

internal fun simplePara(
    pids: PidGen, text: String, spans: List<Span>, basePt: Float, align: Int = 0, rtl: Boolean = DocxParser.firstStrongRtl(text),
    indStart: Float = 0f, indEnd: Float = 0f, firstLine: Float = 0f, marker: String? = null, markerFmt: RunFmt? = null,
    markerWidth: Float = 0f, before: Float = 0f, after: Float = 0f, lineMult: Float = 1f, heading: Int = 0, box: Int = BOX_NONE,
) = DocBlock.Para(pids.next++, text, spans, basePt, align, rtl, indStart, indEnd, firstLine, marker, markerFmt, markerWidth,
    before, after, lineMult, heading, box)

/** Majority direction of a chunk of lines (by first strong character per line). */
private fun mostlyRtl(lines: List<String>): Boolean {
    var r = 0; var l = 0
    for (s in lines) {
        for (c in s) {
            val d = Character.getDirectionality(c)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) { r++; break }
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) { l++; break }
        }
    }
    return r > l
}

private fun expandTabs(s: String, stop: Int): String {
    if (s.indexOf('\t') < 0) return s
    val sb = StringBuilder(s.length + 16)
    var col = 0
    for (c in s) {
        if (c == '\t') { val n = stop - col % stop; repeat(n) { sb.append(' ') }; col += n }
        else { sb.append(c); col = if (c == '\n') 0 else col + 1 }
    }
    return sb.toString()
}

private fun normalizeNewlines(s: String) = if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

/** Removes C0 controls that would render as boxes (keeps tab / newline). */
internal fun stripControls(s: String): String {
    var bad = false
    for (c in s) if (c < ' ' && c != '\n' && c != '\t') { bad = true; break }
    if (!bad) return s
    val sb = StringBuilder(s.length)
    for (c in s) if (c >= ' ' || c == '\n' || c == '\t') sb.append(c)
    return sb.toString()
}

// ------------------------------------------------------------------ .txt / .log

internal object PlainTextReader {
    /** Lines are grouped into paragraph blocks (≤ 40 lines / 4000 chars) so huge files stay light and lazy. */
    fun parse(f: File, log: Boolean): DocxDoc {
        val read = TextDecode.readFile(f, MAX_TEXT_BYTES)
        val text = stripControls(expandTabs(normalizeNewlines(read.text), if (log) 8 else 4))
        val lines = text.split('\n')
        val basePt = if (log) 9f else 11f
        val fmt = RunFmt(sizePt = basePt, mono = log)
        val pids = PidGen()
        val blocks = ArrayList<DocBlock>()
        var i = 0
        var end = lines.size
        while (end > 0 && lines[end - 1].isEmpty()) end--
        while (i < end) {
            var j = i; var chars = 0
            while (j < end && j - i < 40 && chars < 4000) { chars += lines[j].length + 1; j++ }
            val chunk = lines.subList(i, j)
            val s = chunk.joinToString("\n")
            blocks.add(simplePara(pids, s, if (s.isEmpty()) emptyList() else listOf(Span(0, s.length, fmt, null)), basePt,
                rtl = mostlyRtl(chunk), lineMult = if (log) 1.05f else 1.1f))
            i = j
        }
        return DocxDoc(f.absolutePath, blocks, emptyList(), PageSpec.A4Text, if (log) DocKind.LOG else DocKind.TXT, truncatedAt = read.truncatedAt)
    }
}

// ------------------------------------------------------------------ Markdown

internal object MarkdownReader {
    private const val BODY = 11f
    private val headingPt = floatArrayOf(22f, 17f, 14f, 12.5f, 11.5f, 11f)
    private val linkColor = 0xFF2563EB.toInt()
    private val codeBg = 0xFFEEF0F3.toInt()
    private val quoteColor = 0xFF555B66.toInt()

    private val atx = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$")
    private val hr = Regex("^ {0,3}([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    private val fence = Regex("^( {0,3})(`{3,}|~{3,})(.*)$")
    private val listItem = Regex("^([ \\t]*)([-*+]|\\d{1,9}[.)])(?:[ \\t]+(.*)|$)")
    private val tableDelim = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val setextH1 = Regex("^ {0,3}=+[ \\t]*$")
    private val setextH2 = Regex("^ {0,3}-+[ \\t]*$")
    private val imageLine = Regex("^\\s*!\\[([^\\]]*)]\\(\\s*<?([^)\\s>]+)>?(?:\\s+\"[^\"]*\")?\\s*\\)\\s*$")

    fun parse(f: File): DocxDoc {
        val read = TextDecode.readFile(f, MAX_TEXT_BYTES)
        val lines = stripControls(normalizeNewlines(read.text)).split('\n').map { expandTabs(it, 4) }
        val ctx = Ctx(f.parentFile, PidGen())
        val blocks = ArrayList<DocBlock>()
        ctx.blocks(lines, blocks, quote = false)
        return DocxDoc(f.absolutePath, blocks, headingsOf(blocks), PageSpec.A4Text, DocKind.MD, truncatedAt = read.truncatedAt)
    }

    private class Ctx(val dir: File?, val pids: PidGen) {

        fun blocks(lines: List<String>, out: MutableList<DocBlock>, quote: Boolean) {
            var i = 0
            val para = ArrayList<String>()
            fun flushPara() {
                if (para.isEmpty()) return
                out.add(paragraph(para, quote))
                para.clear()
            }
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) { flushPara(); i++; continue }
                // fenced code
                val fm = fence.find(line)
                if (fm != null) {
                    val m = fm
                    flushPara()
                    val marker = m.groupValues[2]
                    val indent = m.groupValues[1].length
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size) {
                        val l = lines[i]
                        val t = l.trimStart()
                        if (t.startsWith(marker[0].toString().repeat(marker.length)) && t.trim().all { it == marker[0] }) { i++; break }
                        code.add(if (l.length >= indent && l.take(indent).isBlank()) l.substring(indent) else l.trimStart())
                        i++
                    }
                    out.add(codeBlock(code, quote))
                    continue
                }
                // indented code (not inside a paragraph)
                if (para.isEmpty() && line.startsWith("    ")) {
                    val code = ArrayList<String>()
                    while (i < lines.size && (lines[i].startsWith("    ") || (lines[i].isBlank() && i + 1 < lines.size && lines[i + 1].startsWith("    ")))) {
                        code.add(if (lines[i].length >= 4) lines[i].substring(4) else ""); i++
                    }
                    out.add(codeBlock(code, quote)); continue
                }
                // ATX heading
                val hm = atx.find(line)
                if (hm != null) {
                    flushPara()
                    out.add(heading(hm.groupValues[2], hm.groupValues[1].length, quote))
                    i++
                    continue
                }
                // setext heading
                if (para.size == 1 && (setextH1.matches(line) || setextH2.matches(line))) {
                    val level = if (setextH1.matches(line)) 1 else 2
                    val t = para[0]; para.clear()
                    out.add(heading(t, level, quote)); i++; continue
                }
                // horizontal rule
                if (hr.matches(line)) { flushPara(); out.add(DocBlock.Divider); i++; continue }
                // block quote
                if (line.trimStart().startsWith(">") && line.length - line.trimStart().length <= 3) {
                    flushPara()
                    val q = ArrayList<String>()
                    while (i < lines.size && lines[i].isNotBlank()) {
                        val t = lines[i].trimStart()
                        if (t.startsWith(">")) q.add(t.removePrefix(">").let { if (it.startsWith(" ")) it.substring(1) else it })
                        else if (q.isNotEmpty()) q.add(t) else break
                        i++
                    }
                    blocks(q, out, quote = true)
                    continue
                }
                // table
                if (line.contains('|') && i + 1 < lines.size && tableDelim.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                    flushPara()
                    val rows = ArrayList<String>()
                    rows.add(line)
                    val delim = lines[i + 1]
                    i += 2
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows.add(lines[i]); i++ }
                    out.add(table(rows, delim, quote))
                    continue
                }
                // list
                if (listItem.containsMatchIn(line) && (para.isEmpty() || !line.startsWith(" "))) {
                    val m = listItem.find(line)!!
                    val bulletLike = m.groupValues[2]
                    // "-" alone right after a paragraph line is a setext heading (handled above); "1." needs a space or end
                    if (para.isEmpty() || bulletLike.first().isDigit() || bulletLike in listOf("-", "*", "+")) {
                        flushPara()
                        i = list(lines, i, out, quote)
                        continue
                    }
                }
                // standalone image
                val im = imageLine.find(line)
                val img = if (im != null) image(im.groupValues[1], im.groupValues[2]) else null
                if (img != null) { flushPara(); out.add(img); i++; continue }
                para.add(line)
                i++
            }
            flushPara()
        }

        private fun heading(raw: String, level: Int, quote: Boolean): DocBlock.Para {
            val size = headingPt[(level - 1).coerceIn(0, 5)]
            val pt = ParaText()
            inline(raw.trim(), RunFmt(sizePt = size, bold = true, color = if (level == 6) quoteColor else null), pt)
            val text = pt.sb.toString()
            return simplePara(pids, text, ArrayList(pt.spans), size, before = if (level <= 2) 16f else 12f, after = 6f,
                heading = level, box = if (quote) BOX_QUOTE else BOX_NONE)
        }

        private fun paragraph(lines: List<String>, quote: Boolean): DocBlock.Para {
            val pt = ParaText()
            val fmt = RunFmt(sizePt = BODY, color = if (quote) quoteColor else null, italic = false)
            lines.forEachIndexed { k, l ->
                val hardBreak = l.endsWith("  ") || l.endsWith("\\")
                val content = l.trim().removeSuffix("\\")
                inline(content, fmt, pt)
                if (k < lines.lastIndex) pt.append(if (hardBreak) "\n" else " ", fmt)
            }
            val text = pt.sb.toString()
            return simplePara(pids, text, ArrayList(pt.spans), BODY, after = 8f, lineMult = 1.15f, box = if (quote) BOX_QUOTE else BOX_NONE)
        }

        private fun codeBlock(code: List<String>, quote: Boolean): DocBlock.Para {
            var end = code.size
            while (end > 0 && code[end - 1].isBlank()) end--
            val text = code.subList(0, end).joinToString("\n")
            val fmt = RunFmt(sizePt = 9.5f, mono = true)
            return simplePara(pids, text, if (text.isEmpty()) emptyList() else listOf(Span(0, text.length, fmt, null)), 9.5f,
                rtl = false, before = 2f, after = 10f, lineMult = 1.05f, box = if (quote) BOX_QUOTE else BOX_CODE)
        }

        /** Parses a list starting at [start]; returns the index after it. Nested levels come from indentation. */
        private fun list(lines: List<String>, start: Int, out: MutableList<DocBlock>, quote: Boolean): Int {
            var i = start
            val indents = ArrayList<Int>()           // indentation of each open level
            val counters = ArrayList<Int>()
            var cur: ParaText? = null
            var curLevel = 0
            var curMarker = ""
            var curOrdered = false
            fun emit() {
                val p = cur ?: return
                val text = p.sb.toString()
                val markerW = if (curOrdered) 22f else 16f
                out.add(simplePara(pids, text, ArrayList(p.spans), BODY, indStart = 8f + curLevel * 20f, marker = curMarker,
                    markerFmt = RunFmt(sizePt = BODY), markerWidth = markerW, after = 3f, lineMult = 1.15f,
                    box = if (quote) BOX_QUOTE else BOX_NONE))
                cur = null
            }
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    // a blank line ends the list unless the next line continues it
                    val next = lines.getOrNull(i + 1)
                    if (next != null && (listItem.containsMatchIn(next) || (next.startsWith("  ") && cur != null))) { i++; continue }
                    break
                }
                val m = listItem.find(line)
                if (m != null && !(hr.matches(line))) {
                    emit()
                    val ind = m.groupValues[1].length
                    while (indents.isNotEmpty() && ind < indents.last() - 1) { indents.removeAt(indents.lastIndex); counters.removeAt(counters.lastIndex) }
                    if (indents.isEmpty() || ind > indents.last() + 1) { indents.add(ind); counters.add(0) }
                    curLevel = (indents.size - 1).coerceAtMost(6)
                    val b = m.groupValues[2]
                    var content = m.groupValues[3]
                    curOrdered = b.first().isDigit()
                    curMarker = if (curOrdered) {
                        val n = if (counters[counters.lastIndex] == 0) b.dropLast(1).toIntOrNull() ?: 1 else counters.last() + 1
                        counters[counters.lastIndex] = n
                        "$n${b.last()}"
                    } else when (curLevel % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
                    val task = Regex("^\\[([ xX])]\\s+").find(content)
                    if (task != null) { curMarker = if (task.groupValues[1] == " ") "☐" else "☑"; content = content.substring(task.range.last + 1) }
                    cur = ParaText().also { inline(content.trim(), RunFmt(sizePt = BODY, color = if (quote) quoteColor else null), it) }
                    i++
                    continue
                }
                // continuation of the current item (indented or lazy)
                val c = cur
                if (c != null && (line.startsWith(" ") || !isBlockStart(line))) {
                    c.append(" ", RunFmt(sizePt = BODY))
                    inline(line.trim(), RunFmt(sizePt = BODY, color = if (quote) quoteColor else null), c)
                    i++
                    continue
                }
                break
            }
            emit()
            return i
        }

        private fun isBlockStart(line: String) = atx.matches(line) || hr.matches(line) || fence.containsMatchIn(line) ||
            line.trimStart().startsWith(">") || line.trimStart().startsWith("|")

        private fun splitRow(line: String): List<String> {
            var s = line.trim()
            if (s.startsWith("|")) s = s.substring(1)
            if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
            val cells = ArrayList<String>()
            val sb = StringBuilder()
            var k = 0
            while (k < s.length) {
                val ch = s[k]
                if (ch == '\\' && k + 1 < s.length && s[k + 1] == '|') { sb.append('|'); k += 2; continue }
                if (ch == '|') { cells.add(sb.toString().trim()); sb.setLength(0) } else sb.append(ch)
                k++
            }
            cells.add(sb.toString().trim())
            return cells
        }

        private fun table(rows: List<String>, delim: String, quote: Boolean): DocBlock.Table {
            val aligns = splitRow(delim).map { d ->
                val l = d.startsWith(":"); val r = d.endsWith(":")
                when { l && r -> 1; r -> 2; else -> 0 }
            }
            val parsed = rows.map { splitRow(it) }
            val cols = parsed.maxOf { it.size }.coerceIn(1, 64)
            val rtl = mostlyRtl(parsed.flatten())
            val outRows = parsed.mapIndexed { r, cells ->
                DocBlock.Row((0 until cols).map { c ->
                    val pt = ParaText()
                    inline(cells.getOrElse(c) { "" }, RunFmt(sizePt = 10f, bold = r == 0), pt)
                    val text = pt.sb.toString()
                    DocBlock.Cell(1, listOf(simplePara(pids, text, ArrayList(pt.spans), 10f, align = aligns.getOrElse(c) { 0 },
                        rtl = if (text.isBlank()) rtl else DocxParser.firstStrongRtl(text), before = 1f, after = 1f)),
                        if (r == 0) 0xFFF1F3F5.toInt() else null, false)
                })
            }
            // Column widths from content length (points), Word-like proportions.
            val grid = (0 until cols).map { c ->
                val len = parsed.maxOf { it.getOrElse(c) { "" }.length }.coerceIn(3, 40)
                len * 6f + 2 * CELL_PAD_H
            }
            return DocBlock.Table(outRows, grid, rtl, borders = true, headerRows = 1)
        }

        private fun image(alt: String, src: String): DocBlock.Image? {
            if (src.contains("://")) return null
            val f = runCatching { File(dir, java.net.URLDecoder.decode(src, "UTF-8")).canonicalFile }.getOrNull() ?: return null
            if (!f.isFile) return null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { android.graphics.BitmapFactory.decodeFile(f.absolutePath, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            val wPt = o.outWidth * 0.75f
            val hPt = o.outHeight * 0.75f
            return DocBlock.Image(f.absolutePath, wPt, hPt, alt, 1, false, external = true)
        }

        // ------------------------------------------------ inline

        /** Appends [s] with Markdown inline formatting (emphasis, code, links, autolinks) applied on top of [base]. */
        fun inline(s: String, base: RunFmt, out: ParaText, link: String? = null) {
            var i = 0
            val plain = StringBuilder()
            fun flush() { if (plain.isNotEmpty()) { out.append(plain.toString(), if (link != null) base.copy(color = linkColor, underline = true) else base, link); plain.setLength(0) } }
            while (i < s.length) {
                val ch = s[i]
                // escapes
                if (ch == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!|~<>\"'") { plain.append(s[i + 1]); i += 2; continue }
                // code span
                if (ch == '`') {
                    var n = 0
                    while (i + n < s.length && s[i + n] == '`') n++
                    val tick = "`".repeat(n)
                    val close = s.indexOf(tick, i + n)
                    if (close > 0) {
                        flush()
                        val code = s.substring(i + n, close).let { if (it.length > 2 && it.startsWith(" ") && it.endsWith(" ")) it.substring(1, it.length - 1) else it }
                        out.append(code, base.copy(mono = true, background = codeBg, sizePt = base.sizePt * 0.92f), link)
                        i = close + n
                        continue
                    }
                }
                // image ![alt](url) → alt text, linked
                if (ch == '!' && i + 1 < s.length && s[i + 1] == '[') {
                    val r = linkAt(s, i + 1)
                    if (r != null) {
                        flush()
                        out.append(r.first.ifBlank { r.second }, base.copy(italic = true, color = linkColor), r.second)
                        i = r.third; continue
                    }
                }
                // link [text](url)
                if (ch == '[') {
                    val r = linkAt(s, i)
                    if (r != null) {
                        flush()
                        inline(r.first, base, out, r.second)
                        i = r.third; continue
                    }
                }
                // autolink <http://…>
                if (ch == '<') {
                    val close = s.indexOf('>', i + 1)
                    if (close > i) {
                        val inner = s.substring(i + 1, close)
                        if (Regex("^(https?://|mailto:)[^\\s<>]+$").matches(inner) || Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE).matches(inner)) {
                            flush()
                            val url = if (inner.contains("://") || inner.startsWith("mailto:")) inner else "mailto:$inner"
                            out.append(inner, base.copy(color = linkColor, underline = true), url)
                            i = close + 1; continue
                        }
                        if (inner.equals("br", true) || inner.equals("br/", true) || inner.equals("br /", true)) { plain.append('\n'); i = close + 1; continue }
                    }
                }
                // bare URL
                if (link == null && (ch == 'h' || ch == 'w') && (s.startsWith("http://", i) || s.startsWith("https://", i) || s.startsWith("www.", i)) &&
                    (i == 0 || !s[i - 1].isLetterOrDigit())) {
                    var e = i
                    while (e < s.length && !s[e].isWhitespace() && s[e] != '<') e++
                    while (e > i && s[e - 1] in ".,;:!?)'\"") e--
                    val url = s.substring(i, e)
                    flush()
                    out.append(url, base.copy(color = linkColor, underline = true), if (url.startsWith("www.")) "https://$url" else url)
                    i = e; continue
                }
                // emphasis / strike
                if (ch == '*' || ch == '_' || ch == '~') {
                    var n = 0
                    while (i + n < s.length && s[i + n] == ch && n < 3) n++
                    val leftFlanking = i + n < s.length && !s[i + n].isWhitespace()
                    val intraword = ch == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    if (ch == '~' && n < 2) { plain.append(ch); i++; continue }
                    if (leftFlanking && !intraword) {
                        val delim = ch.toString().repeat(n)
                        val close = findClose(s, i + n, delim)
                        if (close > i + n) {
                            flush()
                            val f = when {
                                ch == '~' -> base.copy(strike = true)
                                n == 1 -> base.copy(italic = true)
                                n == 2 -> base.copy(bold = true)
                                else -> base.copy(bold = true, italic = true)
                            }
                            inline(s.substring(i + n, close), f, out, link)
                            i = close + n; continue
                        }
                    }
                    repeat(n) { plain.append(ch) }
                    i += n; continue
                }
                plain.append(ch)
                i++
            }
            flush()
        }

        /** Closing delimiter not preceded by whitespace; for '_' it must not be followed by a letter. */
        private fun findClose(s: String, from: Int, delim: String): Int {
            var k = s.indexOf(delim, from)
            while (k > 0) {
                val before = s[k - 1]
                val after = s.getOrNull(k + delim.length)
                val ok = !before.isWhitespace() && (after == null || after != delim[0]) &&
                    !(delim[0] == '_' && after != null && after.isLetterOrDigit())
                if (ok) return k
                k = s.indexOf(delim, k + 1)
            }
            return -1
        }

        /** Parses `[text](url "title")` at [i] (pointing at '['). Returns text, url, index after. */
        private fun linkAt(s: String, i: Int): Triple<String, String, Int>? {
            var depth = 0
            var k = i
            while (k < s.length) {
                when (s[k]) {
                    '\\' -> k++
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) break }
                }
                k++
            }
            if (k >= s.length || k + 1 >= s.length || s[k + 1] != '(') return null
            val close = s.indexOf(')', k + 2)
            if (close < 0) return null
            val inside = s.substring(k + 2, close).trim()
            val url = inside.removePrefix("<").substringBefore('>').substringBefore(' ').trim()
            if (url.isEmpty()) return null
            return Triple(s.substring(i + 1, k), url, close + 1)
        }
    }
}

// ------------------------------------------------------------------ CSV / TSV

internal object CsvReader {
    fun parse(f: File): DocxDoc {
        val read = TextDecode.readFile(f, MAX_CSV_BYTES)
        val text = read.text
        val tsv = f.extension.equals("tsv", true)
        val delim = if (tsv) '\t' else sniffDelimiter(text)
        val rows = ArrayList<Array<String>>()
        val cur = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        var quoted = false
        var truncated = read.truncatedAt
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { cell.append('"'); i += 2; continue }
                    quoted = false; i++; continue
                }
                cell.append(c); i++; continue
            }
            when (c) {
                '"' -> if (cell.isBlank()) { cell.setLength(0); quoted = true } else cell.append(c)
                delim -> { cur.add(cell.toString()); cell.setLength(0) }
                '\r' -> {}
                '\n' -> {
                    cur.add(cell.toString()); cell.setLength(0)
                    if (!(cur.size == 1 && cur[0].isEmpty())) rows.add(cur.take(MAX_CSV_COLS).toTypedArray())
                    cur.clear()
                    if (rows.size >= MAX_CSV_ROWS) { if (truncated == 0L) truncated = i.toLong(); break }
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || cur.isNotEmpty()) { cur.add(cell.toString()); rows.add(cur.take(MAX_CSV_COLS).toTypedArray()) }
        val cols = rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        var r = 0; var l = 0
        for (row in rows.take(200)) for (s in row) {
            if (s.isBlank()) continue
            if (DocxParser.firstStrongRtl(s)) r++ else if (s.any { it.isLetter() }) l++
        }
        val sheet = Sheet(rows, cols, r > l)
        return DocxDoc(f.absolutePath, emptyList(), emptyList(), PageSpec.A4Text, DocKind.CSV, sheet = sheet, truncatedAt = truncated)
    }

    /** Comma unless the first lines clearly use semicolons (European Excel) or tabs. */
    private fun sniffDelimiter(text: String): Char {
        val head = text.take(4096).lineSequence().take(5).toList()
        fun score(ch: Char) = head.sumOf { line -> line.count { it == ch } }
        val comma = score(','); val semi = score(';'); val tab = score('\t')
        return when {
            tab > comma && tab > semi -> '\t'
            semi > comma -> ';'
            else -> ','
        }
    }

    /**
     * Sheet → one table for the PDF export. Rows are built on demand (nothing retained), so a 200k-row sheet
     * only costs the strings already in memory; the header row repeats on every page.
     */
    fun toTable(sheet: Sheet, contentW: Float, pids: PidGen): DocBlock.Table {
        val cols = sheet.cols
        val raw = sheet.charWidths.map { it.coerceIn(3, 40) * 5.2f + 2 * CELL_PAD_H }
        val total = raw.sum()
        val grid = if (total > contentW) raw.map { it * contentW / total } else raw
        fun row(cells: Array<String>, head: Boolean) = DocBlock.Row((0 until cols).map { c ->
            val s = cells.getOrElse(c) { "" }
            val numeric = !head && s.isNotBlank() && s.trim().all { it.isDigit() || it in ".,-+%$€£ " }
            DocBlock.Cell(1, listOf(simplePara(pids, s, if (s.isEmpty()) emptyList() else listOf(Span(0, s.length, RunFmt(sizePt = 8.5f, bold = head), null)),
                8.5f, align = if (numeric) 2 else 0, rtl = if (s.isBlank()) sheet.rtl else DocxParser.firstStrongRtl(s))),
                if (head) 0xFFF1F3F5.toInt() else null, false)
        })
        val rows = object : AbstractList<DocBlock.Row>() {
            override val size get() = sheet.rows.size
            override fun get(index: Int) = row(sheet.rows[index], index == 0)
        }
        return DocBlock.Table(rows, grid, sheet.rtl, true, if (sheet.rows.size > 1) 1 else 0)
    }
}
