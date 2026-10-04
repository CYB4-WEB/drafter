package com.daftar.app.word

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown for files that cannot be shown (encrypted documents, unreadable legacy files). */
class LegacyDocException : Exception()

/**
 * Minimal OLE2 / Compound File Binary reader: header, FAT (with DIFAT chain), directory, mini stream.
 * Only reads; streams are returned as byte arrays (bounded by [maxStream]).
 */
internal class OleFile(private val raf: RandomAccessFile) {
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val entries = ArrayList<Entry>()
    private var miniStream: ByteArray = ByteArray(0)

    class Entry(val name: String, val type: Int, val start: Int, val size: Long)

    init {
        val h = ByteArray(512)
        raf.seek(0); raf.readFully(h)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getLong(0) != 0xE11AB1A1E011CFD0uL.toLong()) throw IllegalStateException("not OLE")
        val shift = bb.getShort(30).toInt()
        require(shift in 7..16)
        sectorSize = 1 shl shift
        miniSectorSize = 1 shl bb.getShort(32).toInt().coerceIn(4, 10)
        val numFat = bb.getInt(44)
        val firstDir = bb.getInt(48)
        miniCutoff = bb.getInt(56).let { if (it <= 0) 4096 else it }
        val firstMiniFat = bb.getInt(60)
        val numMiniFat = bb.getInt(64)
        var difatSector = bb.getInt(68)
        val numDifat = bb.getInt(72)
        require(numFat in 0..100_000)
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) { val s = bb.getInt(76 + i * 4); if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s) }
        var guard = 0
        while (difatSector >= 0 && fatSectors.size < numFat && guard++ <= numDifat + 1) {
            val d = sector(difatSector)
            val db = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val per = sectorSize / 4 - 1
            for (i in 0 until per) { val s = db.getInt(i * 4); if (s >= 0 && fatSectors.size < numFat) fatSectors.add(s) }
            difatSector = db.getInt(per * 4)
        }
        val perSector = sectorSize / 4
        fat = IntArray(fatSectors.size * perSector)
        fatSectors.forEachIndexed { k, s ->
            val d = ByteBuffer.wrap(sector(s)).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until perSector) fat[k * perSector + i] = d.getInt(i * 4)
        }
        // directory
        val dir = chain(firstDir, Long.MAX_VALUE)
        val db = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        var off = 0
        while (off + 128 <= dir.size) {
            val nameLen = db.getShort(off + 64).toInt().coerceIn(0, 64)
            val name = String(dir, off, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val type = dir[off + 66].toInt()
            val start = db.getInt(off + 116)
            val size = db.getInt(off + 120).toLong() and 0xFFFFFFFFL
            entries.add(Entry(name, type, start, size))
            off += 128
        }
        // mini FAT + mini stream (root entry)
        miniFat = if (firstMiniFat >= 0 && numMiniFat > 0) {
            val m = chain(firstMiniFat, numMiniFat.toLong() * sectorSize)
            val mb = ByteBuffer.wrap(m).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(m.size / 4) { mb.getInt(it * 4) }
        } else IntArray(0)
        entries.firstOrNull { it.type == 5 }?.let { root ->
            if (root.start >= 0 && root.size > 0 && root.size < 256L * 1024 * 1024) miniStream = chain(root.start, root.size)
        }
    }

    private fun sector(i: Int): ByteArray {
        val b = ByteArray(sectorSize)
        val pos = (i.toLong() + 1) * sectorSize
        if (pos + sectorSize > raf.length()) throw IllegalStateException("sector out of range")
        raf.seek(pos); raf.readFully(b)
        return b
    }

    private fun chain(start: Int, size: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var s = start
        var guard = 0
        while (s >= 0 && s < fat.size && out.size() < size && guard++ < fat.size + 1) {
            out.write(sector(s))
            s = fat[s]
        }
        val all = out.toByteArray()
        return if (size < all.size) all.copyOf(size.toInt()) else all
    }

    private fun miniChain(start: Int, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var s = start; var off = 0; var guard = 0
        while (s >= 0 && off < size && guard++ < miniFat.size + 1) {
            val src = s * miniSectorSize
            val n = minOf(miniSectorSize, (size - off).toInt(), miniStream.size - src)
            if (n <= 0) break
            System.arraycopy(miniStream, src, out, off, n)
            off += n
            s = if (s < miniFat.size) miniFat[s] else -1
        }
        return out
    }

    fun has(name: String) = entries.any { it.type == 2 && it.name.equals(name, true) }

    fun stream(name: String, maxStream: Long = 128L * 1024 * 1024): ByteArray? {
        val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, true) } ?: return null
        if (e.size > maxStream) return null
        return if (e.size < miniCutoff) miniChain(e.start, e.size) else chain(e.start, e.size)
    }
}

/**
 * Legacy Word (.doc, Word 97–2003, and Word 6/95) text extraction:
 * WordDocument stream → FIB → Clx in the 0Table/1Table stream → piece table (PlcPcd) → main-document text.
 * Field instructions are dropped (results kept), cell marks become table cells, 0x0C becomes a page break.
 * If the piece table is unusable, printable UTF-16 / CP1252 runs are collected instead. Formatting is not read.
 */
internal object LegacyDocReader {

    fun parse(f: File): DocxDoc {
        RandomAccessFile(f, "r").use { raf ->
            val ole = runCatching { OleFile(raf) }.getOrElse { throw LegacyDocException() }
            if (ole.has("EncryptedPackage") || ole.has("EncryptionInfo")) throw LegacyDocException()
            val wd = ole.stream("WordDocument") ?: throw LegacyDocException()
            if (wd.size < 0x200) throw LegacyDocException()
            val bb = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
            val ident = bb.getShort(0).toInt() and 0xFFFF
            val flags = bb.getShort(0x0A).toInt() and 0xFFFF
            val encrypted = flags and 0x0100 != 0
            if (encrypted) throw LegacyDocException()
            var text: String? = null
            if (ident == 0xA5EC) {
                text = runCatching { pieceText(wd, bb, ole, flags) }.getOrNull()
            } else if (ident == 0xA5DC || ident == 0xA697 || ident == 0xA699) {
                // Word 6 / 95: text stored from fcMin to fcMac in the document's 8-bit code page.
                text = runCatching {
                    val fcMin = bb.getInt(0x18); val fcMac = bb.getInt(0x1C)
                    if (fcMin in 0 until fcMac && fcMac <= wd.size) String(wd, fcMin, fcMac - fcMin, TextDecode.legacyCharset(wd.copyOfRange(fcMin, fcMac)))
                    else null
                }.getOrNull()
            }
            if (text == null || !looksReadable(text)) text = printableRuns(wd)
            if (text == null || text.count { it.isLetterOrDigit() } < 3) throw LegacyDocException()
            return build(f.absolutePath, text)
        }
    }

    private fun pieceText(wd: ByteArray, bb: ByteBuffer, ole: OleFile, flags: Int): String? {
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        val table = ole.stream(tableName) ?: return null
        // FIB: FibBase (32) | csw | fibRgW (csw*2) | cslw | fibRgLw (cslw*4) | cbRgFcLcb | fibRgFcLcb (pairs of 4-byte values)
        var off = 32
        val csw = bb.getShort(off).toInt() and 0xFFFF; off += 2 + csw * 2
        val cslw = bb.getShort(off).toInt() and 0xFFFF; off += 2
        val lwStart = off
        off += cslw * 4
        val cbRgFcLcb = bb.getShort(off).toInt() and 0xFFFF; off += 2
        val ccpText = bb.getInt(lwStart + 3 * 4)
        if (cbRgFcLcb < 34) return null
        val fcClx = bb.getInt(off + 33 * 8)
        val lcbClx = bb.getInt(off + 33 * 8 + 4)
        if (fcClx < 0 || lcbClx <= 0 || fcClx.toLong() + lcbClx > table.size) return null
        val tb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        var p = fcClx
        val end = fcClx + lcbClx
        // skip Prc entries
        while (p < end && table[p].toInt() == 0x01) {
            val cb = tb.getShort(p + 1).toInt() and 0xFFFF
            p += 3 + cb
        }
        if (p >= end || table[p].toInt() != 0x02) return null
        val lcb = tb.getInt(p + 1)
        val plc = p + 5
        if (lcb < 16 || plc + lcb > table.size) return null
        val n = (lcb - 4) / 12
        val cps = IntArray(n + 1) { tb.getInt(plc + it * 4) }
        val pcdBase = plc + (n + 1) * 4
        val sb = StringBuilder()
        val limit = if (ccpText > 0) ccpText else Int.MAX_VALUE
        val cp1252 = java.nio.charset.Charset.forName("windows-1252")
        for (i in 0 until n) {
            val cpStart = cps[i]
            if (cpStart >= limit) break
            val cpEnd = minOf(cps[i + 1], limit)
            val count = cpEnd - cpStart
            if (count <= 0) continue
            val fcRaw = tb.getInt(pcdBase + i * 8 + 2)
            val compressed = fcRaw and 0x40000000 != 0
            val fc = fcRaw and 0x3FFFFFFF
            if (compressed) {
                val start = fc / 2
                if (start < 0 || start + count > wd.size) return null
                sb.append(String(wd, start, count, cp1252))
            } else {
                if (fc < 0 || fc.toLong() + count * 2L > wd.size) return null
                sb.append(String(wd, fc, count * 2, Charsets.UTF_16LE))
            }
            if (sb.length > 20_000_000) break
        }
        return sb.toString()
    }

    /** Rejects extraction results that are mostly control / private-use characters. */
    private fun looksReadable(s: String): Boolean {
        if (s.isBlank()) return false
        var good = 0; var bad = 0
        for (c in s) {
            when {
                c.isLetterOrDigit() || c.isWhitespace() || c in ".,;:!?'\"()-–—/%&*+=<>[]{}@#$€£•…“”‘’«»،؛؟" -> good++
                c < ' ' && c !in "\r\u0007\u000B\u000C\u0013\u0014\u0015\u0001\u0008\u001E\u001F\u0002\u0005\t" -> bad++
                c in ''..'' || c == '�' -> bad++
                else -> good++
            }
        }
        return bad * 20 < good + bad
    }

    /** Fallback: printable UTF-16LE runs (≥ 6 chars) or ASCII/CP1252 runs (≥ 10 chars) from the text area. */
    private fun printableRuns(wd: ByteArray): String? {
        val out = StringBuilder()
        // UTF-16LE runs
        var i = 0x200
        val run = StringBuilder()
        fun flushRun(min: Int) {
            if (run.length >= min && run.count { it.isLetter() } * 2 >= run.length / 2) out.append(run).append('\r')
            run.setLength(0)
        }
        while (i + 1 < wd.size) {
            val c = ((wd[i + 1].toInt() and 0xFF) shl 8 or (wd[i].toInt() and 0xFF)).toChar()
            if (c == '\r' || c == '\u000B') { flushRun(6); i += 2; continue }
            if ((c >= ' ' && c !in '\uD800'..'\uDFFF' && c !in ''..'' && c != '￿' && c != '￾') || c == '\t') { run.append(c); i += 2 }
            else { flushRun(6); i += 2 }
        }
        flushRun(6)
        val utf16 = out.toString()
        // 8-bit runs
        out.setLength(0)
        i = 0x200
        while (i < wd.size) {
            val c = wd[i].toInt() and 0xFF
            if (c == 0x0D) { flushRun(10); i++; continue }
            if (c in 0x20..0x7E || c >= 0xA0 || c == 0x09) { run.append(if (c < 0x80) c.toChar() else (c.toChar())); i++ }
            else { flushRun(10); i++ }
        }
        flushRun(10)
        val ascii = out.toString()
        val best = if (utf16.count { it.isLetter() } >= ascii.count { it.isLetter() }) utf16 else ascii
        return best.takeIf { it.count { c -> c.isLetter() } >= 20 }
    }

    /** Turns the raw Word text stream into blocks (paragraphs, tables from cell marks, page breaks). */
    private fun build(path: String, raw: String): DocxDoc {
        val pids = PidGen()
        val blocks = ArrayList<DocBlock>()
        // 1) strip field instructions: 0x13 instr 0x14 result 0x15
        val sb = StringBuilder(raw.length)
        val fieldStack = ArrayList<Boolean>() // true while in the instruction part
        for (c in raw) {
            when (c) {
                '\u0013' -> fieldStack.add(true)
                '\u0014' -> if (fieldStack.isNotEmpty()) fieldStack[fieldStack.lastIndex] = false
                '\u0015' -> if (fieldStack.isNotEmpty()) fieldStack.removeAt(fieldStack.lastIndex)
                else -> if (fieldStack.none { it }) sb.append(c)
            }
        }
        val text = sb
        val fmt = RunFmt(sizePt = 11f)
        fun para(s: String): DocBlock.Para {
            val clean = clean(s)
            return simplePara(pids, clean, if (clean.isEmpty()) emptyList() else listOf(Span(0, clean.length, fmt, null)), 11f,
                after = 6f, lineMult = 1.1f)
        }
        // 2) split paragraphs / cells / rows
        val cur = StringBuilder()
        val cellParas = ArrayList<DocBlock>()
        val row = ArrayList<DocBlock.Cell>()
        val rows = ArrayList<DocBlock.Row>()
        var lastWasCell = false
        fun closeTable() {
            if (row.isNotEmpty()) { rows.add(DocBlock.Row(ArrayList(row))); row.clear() }
            if (rows.isEmpty()) return
            val cols = rows.maxOf { it.cells.size }
            val norm = rows.map { r -> if (r.cells.size < cols) DocBlock.Row(r.cells + List(cols - r.cells.size) { DocBlock.Cell(1, emptyList(), null, false) }) else r }
            val rtl = norm.flatMap { it.cells }.flatMap { it.blocks }.filterIsInstance<DocBlock.Para>().count { it.rtl } * 2 >
                norm.sumOf { it.cells.size }
            blocks.add(DocBlock.Table(norm, emptyList(), rtl, true))
            rows.clear()
        }
        for (c in text) {
            when (c) {
                '\r' -> {
                    if (lastWasCell && cur.isEmpty() && cellParas.isEmpty()) { lastWasCell = false; continue }
                    if (row.isNotEmpty() || cellParas.isNotEmpty()) cellParas.add(para(cur.toString()))
                    else { closeTable(); blocks.add(para(cur.toString())) }
                    cur.setLength(0)
                    lastWasCell = false
                }
                '\u0007' -> {
                    if (lastWasCell && cur.isEmpty() && cellParas.isEmpty()) {
                        // row-end mark
                        if (row.isNotEmpty()) { rows.add(DocBlock.Row(ArrayList(row))); row.clear() }
                        lastWasCell = false
                    } else {
                        if (cur.isNotEmpty() || cellParas.isEmpty()) cellParas.add(para(cur.toString()))
                        row.add(DocBlock.Cell(1, ArrayList(cellParas), null, false))
                        cellParas.clear(); cur.setLength(0)
                        lastWasCell = true
                    }
                }
                '\u000C' -> {
                    if (cur.isNotEmpty()) { closeTable(); blocks.add(para(cur.toString())); cur.setLength(0) }
                    closeTable()
                    blocks.add(DocBlock.PageBreak)
                    lastWasCell = false
                }
                else -> {
                    if (lastWasCell && row.isEmpty() && rows.isNotEmpty()) closeTable()
                    if (lastWasCell && c != '\r') {
                        // text after a row without a row mark continues the row
                    }
                    cur.append(c)
                    lastWasCell = false
                }
            }
        }
        if (cur.isNotEmpty()) { closeTable(); blocks.add(para(cur.toString())) }
        if (cellParas.isNotEmpty()) row.add(DocBlock.Cell(1, ArrayList(cellParas), null, false))
        closeTable()
        while (blocks.lastOrNull() == DocBlock.PageBreak) blocks.removeAt(blocks.lastIndex)
        // Collapse runs of empty paragraphs (old files often pad with them).
        val compact = ArrayList<DocBlock>()
        var empties = 0
        for (b in blocks) {
            if (b is DocBlock.Para && b.text.isBlank()) { if (++empties > 1) continue } else empties = 0
            compact.add(b)
        }
        return DocxDoc(path, compact, emptyList(), PageSpec.A4, DocKind.DOC)
    }

    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\u000B', '\u000E' -> sb.append('\n')
                '\u001E' -> sb.append('‑')
                '\u001F', '\u0001', '\u0008', '\u0002', '\u0005', '\u0003', '\u0004' -> {}
                '\t' -> sb.append('\t')
                else -> if (c >= ' ' && c !in ''..'') sb.append(c) else if (c in ''..'') sb.append('•')
            }
        }
        return sb.toString().trimEnd()
    }
}
