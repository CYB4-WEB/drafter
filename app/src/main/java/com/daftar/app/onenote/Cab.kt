package com.daftar.app.onenote

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Minimal Microsoft Cabinet reader for OneNote packages (.onepkg). Supports stored and MSZIP folders (raw deflate per
 * CFDATA block, the previous 32 KB of output as preset dictionary) and LZX folders ([Lzx]). Quantum folders raise
 * [OneError.PACKAGE_COMPRESSION]. Pure JVM; never trusts sizes from the file.
 */
object Cab {
    class Entry(val name: String, val size: Long, val folder: Int, val offset: Long)
    internal class Folder(val dataStart: Long, val blocks: Int, val type: Int)
    class Archive internal constructor(val entries: List<Entry>, internal val folders: List<Folder>, internal val dataReserve: Int) {
        /** Compression of folder [i]: 0 none, 1 MSZIP, 2 Quantum, 3 LZX. */
        fun compression(i: Int) = folders.getOrNull(i)?.type?.and(0xF) ?: -1
    }

    const val MAGIC = 0x4643534D // "MSCF" little endian

    fun isCab(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { r -> r.length() >= 36 && Integer.reverseBytes(r.readInt()) == MAGIC }
    }.getOrDefault(false)

    private fun RandomAccessFile.u8() = read().also { if (it < 0) throw OneException(OneError.CORRUPT, "cab: eof") }
    private fun RandomAccessFile.u16() = u8() or (u8() shl 8)
    private fun RandomAccessFile.u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)
    private fun RandomAccessFile.zstring(utf8: Boolean): String {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = u8()
            if (c == 0) break
            if (b.size() > 1024) throw OneException(OneError.CORRUPT, "cab: name too long")
            b.write(c)
        }
        return String(b.toByteArray(), if (utf8) Charsets.UTF_8 else charset("windows-1252"))
    }

    fun read(f: File): Archive = RandomAccessFile(f, "r").use { r -> read(r) }

    private fun read(r: RandomAccessFile): Archive {
        val len = r.length()
        if (len < 36 || r.u32() != MAGIC.toLong()) throw OneException(OneError.NOT_ONENOTE, "not a cabinet")
        r.u32()                      // reserved1
        r.u32()                      // cbCabinet
        r.u32()                      // reserved2
        val coffFiles = r.u32()
        r.u32()                      // reserved3
        r.u8(); r.u8()               // version
        val cFolders = r.u16()
        val cFiles = r.u16()
        val flags = r.u16()
        r.u16(); r.u16()             // setID, iCabinet
        var folderReserve = 0
        var dataReserve = 0
        if (flags and 4 != 0) {
            val headerReserve = r.u16()
            folderReserve = r.u8()
            dataReserve = r.u8()
            r.seek(r.filePointer + headerReserve)
        }
        if (flags and 1 != 0) { r.zstring(false); r.zstring(false) }
        if (flags and 2 != 0) { r.zstring(false); r.zstring(false) }
        val folders = ArrayList<Folder>()
        for (i in 0 until cFolders) {
            val start = r.u32(); val blocks = r.u16(); val type = r.u16()
            r.seek(r.filePointer + folderReserve)
            folders.add(Folder(start, blocks, type))
        }
        if (coffFiles >= len) throw OneException(OneError.CORRUPT, "cab: file table")
        r.seek(coffFiles)
        val entries = ArrayList<Entry>()
        for (i in 0 until cFiles) {
            val size = r.u32(); val off = r.u32(); val folder = r.u16()
            r.u16(); r.u16()         // date, time
            val attribs = r.u16()
            val name = r.zstring(attribs and 0x80 != 0)
            entries.add(Entry(name.replace('\\', '/'), size, folder, off))
        }
        return Archive(entries, folders, dataReserve)
    }

    /** Safe relative path for an entry name (no absolute paths, no "..", no empty segments). */
    fun safeName(name: String): String =
        name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/") { seg -> seg.map { if (it < ' ' || it in "<>:\"|?*") '_' else it }.joinToString("") }

    /**
     * Extracts the entries accepted by [accept] into [outDir] and returns them with their files.
     * [cancelled] is polled between blocks.
     */
    fun extract(f: File, outDir: File, accept: (Entry) -> Boolean, cancelled: () -> Boolean = { false }): List<Pair<Entry, File>> {
        RandomAccessFile(f, "r").use { r ->
            val arc = read(r)
            val wanted = arc.entries.filter { it.folder < arc.folders.size && it.size >= 0 && safeName(it.name).isNotEmpty() && accept(it) }
            val result = ArrayList<Pair<Entry, File>>()
            for ((fi, folder) in arc.folders.withIndex()) {
                val files = wanted.filter { it.folder == fi }.sortedBy { it.offset }
                if (files.isEmpty()) continue
                val type = folder.type and 0xF
                if (type != 0 && type != 1 && type != 3) throw OneException(OneError.PACKAGE_COMPRESSION, "cab compression $type")
                // CFDATA blocks of the folder: payload offset, compressed and uncompressed size
                val blocks = ArrayList<LongArray>()
                r.seek(folder.dataStart)
                for (b in 0 until folder.blocks) {
                    r.u32()                              // checksum (not verified: optional in practice)
                    val cbData = r.u16(); val cbUncomp = r.u16()
                    r.seek(r.filePointer + arc.dataReserve)
                    if (r.filePointer + cbData > r.length()) throw OneException(OneError.CORRUPT, "cab: block past end")
                    blocks.add(longArrayOf(r.filePointer, cbData.toLong(), cbUncomp.toLong()))
                    r.seek(r.filePointer + cbData)
                }
                fun payload(b: LongArray): ByteArray { val d = ByteArray(b[1].toInt()); r.seek(b[0]); r.readFully(d); return d }
                var next = 0
                val lzx = if (type == 3) Lzx((folder.type shr 8) and 0x1F) { blocks.getOrNull(next++)?.let { payload(it) } } else null
                val targets = files.map { e -> e to File(outDir, safeName(e.name)) }
                val streams = HashMap<Entry, OutputStream>()
                try {
                    for ((e, out) in targets) {
                        out.parentFile?.mkdirs()
                        streams[e] = BufferedOutputStream(FileOutputStream(out), 64 * 1024)
                    }
                    val endNeeded = files.maxOf { it.offset + it.size }
                    var pos = 0L
                    var hist = ByteArray(0)
                    for (b in blocks) {
                        if (pos >= endNeeded) break
                        if (cancelled()) throw InterruptedException()
                        val out = when (type) {
                            0 -> payload(b)
                            1 -> mszip(payload(b), b[2].toInt(), hist).also { hist = tail(hist, it) }
                            else -> lzx!!.frame(b[2].toInt())
                        }
                        for (e in files) {
                            val s0 = maxOf(e.offset, pos); val t = minOf(e.offset + e.size, pos + out.size)
                            if (s0 < t) streams[e]?.write(out, (s0 - pos).toInt(), (t - s0).toInt())
                        }
                        pos += out.size
                    }
                    if (pos < endNeeded) throw OneException(OneError.CORRUPT, "cab: truncated folder")
                } finally {
                    streams.values.forEach { runCatching { it.close() } }
                }
                result.addAll(targets)
            }
            return result
        }
    }

    private fun tail(hist: ByteArray, out: ByteArray): ByteArray {
        val keep = 32768
        if (out.size >= keep) return out.copyOfRange(out.size - keep, out.size)
        val total = hist.size + out.size
        val from = maxOf(0, total - keep)
        val n = ByteArray(total - from)
        var k = 0
        for (i in from until hist.size) n[k++] = hist[i]
        val start = maxOf(0, from - hist.size)
        System.arraycopy(out, start, n, k, out.size - start)
        return n
    }

    /** One MSZIP block: "CK" + raw deflate data, decoded with the previous output as dictionary. */
    internal fun mszip(data: ByteArray, cbUncomp: Int, hist: ByteArray): ByteArray {
        if (data.size < 2 || data[0] != 'C'.code.toByte() || data[1] != 'K'.code.toByte()) throw OneException(OneError.CORRUPT, "mszip signature")
        val inf = Inflater(true)
        try {
            if (hist.isNotEmpty()) inf.setDictionary(hist)
            val input = ByteArray(data.size - 1)          // payload + one dummy byte (raw inflate may want it)
            System.arraycopy(data, 2, input, 0, data.size - 2)
            inf.setInput(input)
            val out = ByteArray(cbUncomp)
            var n = 0
            while (n < cbUncomp) {
                val got = inf.inflate(out, n, cbUncomp - n)
                if (got == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += got
            }
            if (n != cbUncomp) throw OneException(OneError.CORRUPT, "mszip block short ($n/$cbUncomp)")
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw OneException(OneError.CORRUPT, "mszip: ${e.message}", e)
        } finally {
            inf.end()
        }
    }
}
