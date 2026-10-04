package com.daftar.app.onenote

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

// [MS-ONESTORE] revision store file reader (OneNote 2010+ .one / .onetoc2; best effort for 2007's node types).
// Pure JVM. Every read is bounds checked; malformed structures throw Corrupt, which callers catch at the smallest
// sensible scope (a node, an object, a page) so one bad structure never takes the whole file down.

internal class Corrupt(msg: String) : Exception(msg)

/** Little-endian bounded reader over a (memory mapped) buffer. Positions are absolute file offsets. */
internal class Rd(private val b: ByteBuffer, var pos: Int, val end: Int) {
    val remaining get() = end - pos
    fun need(n: Int) { if (n < 0 || pos > end - n) throw Corrupt("read past end ($pos+$n > $end)") }
    fun u8(): Int { need(1); return b.get(pos++).toInt() and 0xFF }
    fun u16(): Int { need(2); val v = b.getShort(pos).toInt() and 0xFFFF; pos += 2; return v }
    fun u32(): Long { need(4); val v = b.getInt(pos).toLong() and 0xFFFFFFFFL; pos += 4; return v }
    fun i32(): Int { need(4); val v = b.getInt(pos); pos += 4; return v }
    fun u64(): Long { need(8); val v = b.getLong(pos); pos += 8; return v }
    fun f32(): Float { need(4); val v = b.getFloat(pos); pos += 4; return v }
    fun skip(n: Int) { need(n); pos += n }
    fun bytes(n: Int): ByteArray { need(n); val a = ByteArray(n); for (i in 0 until n) a[i] = b.get(pos + i); pos += n; return a }
    fun guid(): String {
        need(16)
        val d1 = b.getInt(pos).toLong() and 0xFFFFFFFFL
        val d2 = b.getShort(pos + 4).toInt() and 0xFFFF
        val d3 = b.getShort(pos + 6).toInt() and 0xFFFF
        val sb = StringBuilder(38)
        sb.append('{').append(hex(d1, 8)).append('-').append(hex(d2.toLong(), 4)).append('-').append(hex(d3.toLong(), 4)).append('-')
        for (i in 8 until 16) {
            if (i == 10) sb.append('-')
            sb.append(hex((b.get(pos + i).toInt() and 0xFF).toLong(), 2))
        }
        sb.append('}')
        pos += 16
        return sb.toString()
    }
    fun xguid(): XG { val g = guid(); val n = u32(); return XG(g, n) }
    /** StringInStorageBuffer: cch (u32) + UTF-16LE characters. */
    fun storageString(): String {
        val cch = u32()
        if (cch > 0x100000) throw Corrupt("string too long")
        val raw = bytes((cch * 2).toInt())
        return String(raw, Charsets.UTF_16LE)
    }
    fun sub(offset: Long, length: Long): Rd {
        if (offset < 0 || length < 0 || offset + length > b.limit()) throw Corrupt("chunk out of file ($offset+$length)")
        return Rd(b, offset.toInt(), (offset + length).toInt())
    }

    companion object {
        private const val HEX = "0123456789ABCDEF"
        fun hex(v: Long, digits: Int): String {
            val c = CharArray(digits)
            var x = v
            for (i in digits - 1 downTo 0) { c[i] = HEX[(x and 0xF).toInt()]; x = x shr 4 }
            return String(c)
        }
    }
}

/** ExtendedGUID: a GUID plus a counter. */
internal data class XG(val guid: String, val n: Long) {
    val isNil get() = n == 0L && guid == NIL_GUID
    override fun toString() = "$guid#$n"
    companion object {
        const val NIL_GUID = "{00000000-0000-0000-0000-000000000000}"
        val NIL = XG(NIL_GUID, 0)
    }
}

/** File chunk reference (absolute offset + size). Nil = not present. */
internal class Ref(val stp: Long, val cb: Long, val nil: Boolean) {
    val isEmpty get() = nil || cb == 0L
    override fun toString() = if (nil) "nil" else "@$stp+$cb"
}

internal class FileNode(val id: Int, val baseType: Int, val ref: Ref?, val data: Rd)

/** One declared object: its type ([jcid]) and where its property set lives, or (file data objects) its blob reference. */
internal class ObjDecl(val oid: XG, val jcid: Long, val ref: Ref?, val fileRef: String?, val ext: String?, val ids: GlobalIds) {
    val jcidIndex get() = (jcid and 0xFFFF).toInt()
    val isFileData get() = (jcid shr 19) and 1L == 1L
    @Volatile var props: PropSet? = null
    @Volatile var failed = false
}

/** Global identification table of an object group / revision: CompactID.guidIndex → GUID. */
internal class GlobalIds(val map: HashMap<Long, String> = HashMap()) {
    fun resolve(compact: Long): XG {
        val n = compact and 0xFF
        val idx = compact ushr 8
        if (compact == 0L) return XG.NIL
        val g = map[idx] ?: return XG("?$idx", n)
        return XG(g, n)
    }
}

/** Decoded property set. Keys are property ids without the bool-value bit. */
internal class PropSet(val values: HashMap<Long, Any>) {
    operator fun get(id: Long): Any? = values[id and 0x7FFFFFFFL]
    fun has(id: Long) = values.containsKey(id and 0x7FFFFFFFL)
    fun bool(id: Long): Boolean? = values[id and 0x7FFFFFFFL] as? Boolean
    fun bytes(id: Long): ByteArray? = values[id and 0x7FFFFFFFL] as? ByteArray
    fun u8(id: Long): Int? = bytes(id)?.takeIf { it.isNotEmpty() }?.let { it[0].toInt() and 0xFF }
    fun u16(id: Long): Int? = bytes(id)?.takeIf { it.size >= 2 }?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
    fun u32(id: Long): Long? = bytes(id)?.takeIf { it.size >= 4 }?.let { le32(it, 0) }
    fun f32(id: Long): Float? = u32(id)?.let { java.lang.Float.intBitsToFloat(it.toInt()) }?.takeIf { !it.isNaN() && !it.isInfinite() }
    fun u64(id: Long): Long? = bytes(id)?.takeIf { it.size >= 8 }?.let { le32(it, 0) or (le32(it, 4) shl 32) }
    @Suppress("UNCHECKED_CAST")
    fun oids(id: Long): List<XG> = (values[id and 0x7FFFFFFFL] as? OidList)?.ids ?: emptyList()
    fun oid(id: Long): XG? = oids(id).firstOrNull()
    @Suppress("UNCHECKED_CAST")
    fun osids(id: Long): List<XG> = (values[id and 0x7FFFFFFFL] as? OsidList)?.ids ?: emptyList()
    fun utf16(id: Long): String? = bytes(id)?.let { String(it, Charsets.UTF_16LE).trimEnd('\u0000') }

    companion object {
        fun le32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24))
    }
}

internal class OidList(val ids: List<XG>)
internal class OsidList(val ids: List<XG>)
internal class PropArray(val sets: List<PropSet>)

/** One revision of an object space: objects declared in it plus the revision it builds on. */
internal class Revision(val rid: XG, val dependent: XG, val ctx: XG, val role: Long) {
    val objects = HashMap<XG, ObjDecl>()
    val roots = HashMap<Long, XG>()
    var ids = GlobalIds()
}

/** An object space (one page, or the section root). Lookups follow the revision dependency chain. */
internal class ObjectSpace(val gosid: XG, private val revisions: Map<XG, Revision>, private val current: Revision?, private val store: OneStore) {
    val ok get() = current != null

    private inline fun <T> walk(f: (Revision) -> T?): T? {
        var r = current
        var guard = 0
        while (r != null && guard++ < 4096) {
            f(r)?.let { return it }
            if (r.dependent.isNil) return null
            r = revisions[r.dependent]
        }
        return null
    }

    fun root(role: Long): XG? = walk { it.roots[role] }
    fun decl(oid: XG?): ObjDecl? = if (oid == null || oid.isNil) null else walk { it.objects[oid] }
    fun props(oid: XG?): PropSet? = decl(oid)?.let { store.props(it) }
    fun jcidIndex(oid: XG?): Int = decl(oid)?.jcidIndex ?: -1
}

/**
 * Parsed revision store. Opens the file memory-mapped (the OS pages it in on demand, so even large sections cost little heap).
 * Throws [OneException] for files that are not readable OneNote 2010+ sections.
 */
internal class OneStore private constructor(val file: File, private val buf: ByteBuffer) {
    private val size = buf.limit()
    private val commitCounts = HashMap<Long, Long>()
    val spaces = LinkedHashMap<XG, ObjectSpace>()
    var rootGosid: XG = XG.NIL
        private set
    var encrypted = false
        private set
    /** FileDataStoreObject GUID (upper case, with braces) → blob. */
    val fileData = HashMap<String, Blob>()
    /** Non-fatal problems met while reading (for logs / tests). */
    val warnings = ArrayList<String>()

    private fun rd(offset: Long, len: Long) = Rd(buf, 0, size).sub(offset, len)

    fun props(d: ObjDecl): PropSet? {
        d.props?.let { return it }
        if (d.failed || d.ref == null || d.ref.isEmpty) return null
        return try {
            parseObjectPropSet(rd(d.ref.stp, d.ref.cb), d.ids).also { d.props = it }
        } catch (e: Exception) {
            d.failed = true
            warn("object ${d.oid}: ${e.message}")
            null
        } catch (e: StackOverflowError) {
            d.failed = true
            null
        }
    }

    fun blobFor(fileRef: String?): Blob? {
        if (fileRef == null) return null
        val i = fileRef.indexOf('{')
        val j = fileRef.indexOf('}', i + 1)
        if (!fileRef.startsWith("<ifndf>") || i < 0 || j < 0) return null
        return fileData[fileRef.substring(i, j + 1).uppercase()]
    }

    private fun warn(s: String) { if (warnings.size < 200) warnings.add(s) }

    // ------------------------------------------------------------------ header

    private fun readHeader() {
        if (size < 1024) throw OneException(OneError.NOT_ONENOTE, "too small")
        val h = rd(0, 1024)
        val fileType = h.guid()
        h.skip(32) // guidFile, guidLegacyFileVersion
        val format = h.guid()
        when (format) {
            FORMAT_CLOUD -> throw OneException(OneError.CLOUD)
            FORMAT_ONESTORE -> {}
            else -> throw OneException(if (fileType == TYPE_ONE || fileType == TYPE_TOC) OneError.OLD_FORMAT else OneError.NOT_ONENOTE, "format $format")
        }
        when (fileType) {
            TYPE_ONE -> {}
            TYPE_TOC -> throw OneException(OneError.TOC)
            else -> throw OneException(OneError.NOT_ONENOTE, "type $fileType")
        }
        h.pos = 96
        val cTransactions = h.u32()
        h.pos = 160
        val fcrTransactionLog = ref64x32(h)
        val fcrRoot = ref64x32(h)
        if (fcrRoot.isEmpty) throw OneException(OneError.CORRUPT, "no root list")
        try { readTransactionLog(fcrTransactionLog, cTransactions) } catch (e: Exception) { warn("transaction log: ${e.message}"); commitCounts.clear() }
        readRoot(fcrRoot)
    }

    private fun ref64x32(r: Rd): Ref {
        val stp = r.u64(); val cb = r.u32()
        return Ref(stp, cb, stp == -1L)
    }

    /** Committed node count per file node list: the last transaction that touched a list says how many of its nodes are valid. */
    private fun readTransactionLog(first: Ref, transactions: Long) {
        var ref = first
        var seen = 0L
        var guard = 0
        while (!ref.isEmpty && seen < transactions && guard++ < 100000) {
            val r = rd(ref.stp, ref.cb)
            val entries = ((ref.cb - 12) / 8).toInt()
            for (i in 0 until entries) {
                val src = r.u32(); val sw = r.u32()
                if (src == 1L) { seen++; if (seen >= transactions) return }
                else if (src != 0L) commitCounts[src] = sw
            }
            r.pos = (ref.stp + ref.cb - 12).toInt()
            ref = ref64x32(r)
        }
    }

    // ------------------------------------------------------------------ file node lists

    private fun nodeRef(r: Rd, stpFormat: Int, cbFormat: Int): Ref {
        var nil = false
        val stp = when (stpFormat) {
            0 -> r.u64().also { nil = it == -1L }
            1 -> r.u32().also { nil = it == 0xFFFFFFFFL }
            2 -> r.u16().toLong().also { nil = it == 0xFFFFL } * 8
            else -> r.u32().also { nil = it == 0xFFFFFFFFL } * 8
        }
        val cb = when (cbFormat) {
            0 -> r.u32()
            1 -> r.u64()
            2 -> r.u8().toLong() * 8
            else -> r.u16().toLong() * 8
        }
        return Ref(stp, cb, nil)
    }

    /** All committed nodes of the list starting at [first] (follows the fragment chain). */
    fun readList(first: Ref): List<FileNode> {
        val out = ArrayList<FileNode>()
        if (first.isEmpty) return out
        var ref = first
        var listId = -1L
        var limit = Long.MAX_VALUE
        var expectSeq = 0L
        val visited = HashSet<Long>()
        while (!ref.isEmpty && visited.add(ref.stp)) {
            if (ref.cb < 36) { warn("fragment too small"); break }
            val r = rd(ref.stp, ref.cb)
            if (r.u64() != FRAGMENT_MAGIC) { warn("bad fragment magic at ${ref.stp}"); break }
            val id = r.u32(); val seq = r.u32()
            if (listId < 0) { listId = id; limit = commitCounts[id] ?: Long.MAX_VALUE }
            else if (id != listId || seq != expectSeq) { warn("fragment chain mismatch"); break }
            expectSeq = seq + 1
            val nodesEnd = (ref.stp + ref.cb - 20).toInt()
            var terminated = false
            while (r.pos + 4 <= nodesEnd && out.size < limit) {
                val start = r.pos
                val h = r.u32()
                val fid = (h and 0x3FF).toInt()
                val sz = ((h shr 10) and 0x1FFF).toInt()
                if (fid == 0xFF) { terminated = true; break }
                if (fid == 0 || sz < 4 || start + sz > nodesEnd) break
                val stpF = ((h shr 23) and 3).toInt()
                val cbF = ((h shr 25) and 3).toInt()
                val base = ((h shr 27) and 0xF).toInt()
                val body = Rd(buf, r.pos, start + sz)
                try {
                    val nref = if (base == 1 || base == 2) nodeRef(body, stpF, cbF) else null
                    out.add(FileNode(fid, base, nref, body))
                } catch (e: Corrupt) { warn("node 0x${fid.toString(16)}: ${e.message}") }
                r.pos = start + sz
            }
            if (out.size >= limit) break
            // next fragment reference sits right before the footer
            r.pos = nodesEnd
            ref = ref64x32(r)
            if (!terminated && ref.isEmpty) break
        }
        return out
    }

    // ------------------------------------------------------------------ root, object spaces, revisions

    private fun readRoot(root: Ref) {
        val nodes = readList(root)
        val manifests = ArrayList<Pair<XG, Ref>>()
        for (n in nodes) {
            try {
                when (n.id) {
                    0x004 -> rootGosid = n.data.xguid()
                    0x008 -> { val gosid = n.data.xguid(); n.ref?.let { manifests.add(gosid to it) } }
                    0x090 -> n.ref?.let { readFileDataStore(it) }
                    0x07C -> encrypted = true
                }
            } catch (e: Corrupt) { warn("root node: ${e.message}") }
        }
        if (manifests.isEmpty()) throw OneException(OneError.CORRUPT, "no object spaces")
        for ((gosid, ref) in manifests) {
            try { readObjectSpace(gosid, ref) } catch (e: Exception) { warn("object space $gosid: ${e.message}") }
        }
    }

    private fun readFileDataStore(ref: Ref) {
        for (n in readList(ref)) {
            if (n.id != 0x094 || n.ref == null || n.ref.isEmpty) continue
            try {
                val guid = n.data.guid()
                val r = rd(n.ref.stp, n.ref.cb)
                if (r.guid() != FILE_DATA_HEADER) { warn("file data header"); continue }
                val len = r.u64()
                val dataStart = n.ref.stp + 36
                if (len < 0 || dataStart + len > size) { warn("file data length"); continue }
                fileData[guid.uppercase()] = Blob(file.absolutePath, dataStart, len)
            } catch (e: Corrupt) { warn("file data: ${e.message}") }
        }
    }

    private fun readObjectSpace(gosid: XG, manifestRef: Ref) {
        val manifest = readList(manifestRef)
        var revList: Ref? = null
        for (n in manifest) {
            when (n.id) {
                0x010 -> revList = n.ref
                0x07C -> encrypted = true
            }
        }
        val list = revList ?: return
        val revisions = HashMap<XG, Revision>()
        val current = HashMap<String, XG>()   // "ctx|role" → rid
        var rev: Revision? = null
        var lastIds = GlobalIds()
        var tableBuild: HashMap<Long, String>? = null
        for (n in readList(list)) {
            try {
                val d = n.data
                when (n.id) {
                    0x01B -> { // RevisionManifestStart4FND (2007)
                        val rid = d.xguid(); val dep = d.xguid(); d.skip(8); val role = d.u32()
                        rev = Revision(rid, dep, XG.NIL, role).also { revisions[rid] = it; current["${XG.NIL}|$role"] = rid }
                    }
                    0x01E, 0x01F -> {
                        val rid = d.xguid(); val dep = d.xguid(); val role = d.u32(); d.skip(2)
                        val ctx = if (n.id == 0x01F) d.xguid() else XG.NIL
                        rev = Revision(rid, dep, ctx, role).also { revisions[rid] = it; current["$ctx|$role"] = rid }
                        revisions[dep]?.let { lastIds = it.ids }
                    }
                    0x01C -> { rev?.let { lastIds = it.ids }; rev = null }
                    0x021, 0x022 -> tableBuild = HashMap()
                    0x024 -> { val idx = d.u32(); val g = d.guid(); tableBuild?.put(idx, g) }
                    0x025 -> { val from = d.u32(); val to = d.u32(); lastIds.map[from]?.let { tableBuild?.put(to, it) } }
                    0x026 -> {
                        val from = d.u32(); val count = d.u32(); val to = d.u32()
                        for (k in 0 until minOf(count, 1_000_000L)) lastIds.map[from + k]?.let { tableBuild?.put(to + k, it) }
                    }
                    0x028 -> { tableBuild?.let { t -> rev?.ids = GlobalIds(t) }; tableBuild = null }
                    0x0B0 -> { val r = rev; if (r != null && n.ref != null) readObjectGroup(n.ref, r) }
                    0x02D, 0x02E, 0x041, 0x042 -> rev?.let { legacyDeclaration(n, it) }
                    0x059 -> { val c = d.u32(); val role = d.u32(); rev?.let { r -> r.roots[role] = r.ids.resolve(c) } }
                    0x05A -> { val oid = d.xguid(); val role = d.u32(); rev?.roots?.put(role, oid) }
                    0x05C -> { val rid = d.xguid(); val role = d.u32(); current["${XG.NIL}|$role"] = rid }
                    0x05D -> { val rid = d.xguid(); val role = d.u32(); val ctx = d.xguid(); current["$ctx|$role"] = rid }
                    0x07C -> encrypted = true
                }
            } catch (e: Corrupt) { warn("revision node 0x${n.id.toString(16)}: ${e.message}") }
        }
        val cur = current["${XG.NIL}|1"]?.let { revisions[it] }
            ?: revisions.values.lastOrNull { it.ctx.isNil }
        spaces[gosid] = ObjectSpace(gosid, revisions, cur, this)
    }

    /** 2007-style object declarations placed directly in the revision (resolved with the revision's id table). */
    private fun legacyDeclaration(n: FileNode, rev: Revision) {
        val d = n.data
        val ref = n.ref ?: return
        when (n.id) {
            0x02D, 0x02E -> {
                val oid = rev.ids.resolve(d.u32())
                val bits = d.u16().toLong() or (d.u32() shl 16)
                val jci = bits and 0x3FF
                rev.objects[oid] = ObjDecl(oid, 0x00020000L or jci, ref, null, null, rev.ids)
            }
            0x041, 0x042 -> {
                val oid = rev.ids.resolve(d.u32())
                val prev = rev.objects[oid]
                rev.objects[oid] = ObjDecl(oid, prev?.jcid ?: 0x00020000L, ref, null, null, rev.ids)
            }
        }
    }

    private fun readObjectGroup(ref: Ref, rev: Revision) {
        var ids = GlobalIds()
        var table: HashMap<Long, String>? = null
        for (n in readList(ref)) {
            try {
                val d = n.data
                when (n.id) {
                    0x022, 0x021 -> table = HashMap()
                    0x024 -> { val idx = d.u32(); val g = d.guid(); table?.put(idx, g) }
                    0x028 -> { table?.let { ids = GlobalIds(it) }; table = null }
                    0x0A4, 0x0A5, 0x0C4, 0x0C5 -> {
                        val r = n.ref ?: continue
                        val oid = ids.resolve(d.u32())
                        val jcid = d.u32()
                        rev.objects[oid] = ObjDecl(oid, jcid, r, null, null, ids)
                    }
                    0x072, 0x073 -> {
                        val oid = ids.resolve(d.u32())
                        val jcid = d.u32()
                        d.skip(if (n.id == 0x072) 1 else 4)
                        val fileRef = d.storageString()
                        val ext = runCatching { d.storageString() }.getOrNull()
                        rev.objects[oid] = ObjDecl(oid, jcid, null, fileRef, ext, ids)
                    }
                    0x07C -> encrypted = true
                }
            } catch (e: Corrupt) { warn("object group node 0x${n.id.toString(16)}: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ property sets

    private class IdCursor(val oids: List<Long>, val osids: List<Long>, val ctxs: List<Long>) {
        var o = 0; var s = 0; var c = 0
    }

    private fun idStream(r: Rd): Pair<Long, List<Long>> {
        val h = r.u32()
        val count = (h and 0xFFFFFF).toInt()
        if (count * 4L > r.remaining) throw Corrupt("id stream")
        val ids = ArrayList<Long>(count)
        for (i in 0 until count) ids.add(r.u32())
        return h to ids
    }

    private fun parseObjectPropSet(r: Rd, ids: GlobalIds): PropSet {
        val (h, oids) = idStream(r)
        var osids: List<Long> = emptyList()
        var ctxs: List<Long> = emptyList()
        val osidNotPresent = (h shr 31) and 1L == 1L
        val extended = (h shr 30) and 1L == 1L
        if (!osidNotPresent) osids = idStream(r).second
        if (extended) ctxs = idStream(r).second
        return parsePropSet(r, IdCursor(oids, osids, ctxs), ids, 0)
    }

    private fun parsePropSet(r: Rd, cur: IdCursor, ids: GlobalIds, depth: Int): PropSet {
        if (depth > 32) throw Corrupt("property sets nested too deep")
        val count = r.u16()
        val prids = LongArray(count) { r.u32() }
        val values = HashMap<Long, Any>(count * 2)
        for (prid in prids) {
            val key = prid and 0x7FFFFFFFL
            val type = ((prid shr 26) and 0x1F).toInt()
            val v: Any = when (type) {
                0x1 -> Unit
                0x2 -> (prid ushr 31) == 1L
                0x3 -> r.bytes(1)
                0x4 -> r.bytes(2)
                0x5 -> r.bytes(4)
                0x6 -> r.bytes(8)
                0x7 -> { val cb = r.u32(); if (cb > r.remaining) throw Corrupt("property length"); r.bytes(cb.toInt()) }
                0x8 -> OidList(listOf(nextId(cur.oids, cur.o++, ids)))
                0x9 -> { val n = r.u32().toInt(); OidList(List(n.coerceAtLeast(0)) { nextId(cur.oids, cur.o++, ids) }) }
                0xA -> OsidList(listOf(nextId(cur.osids, cur.s++, ids)))
                0xB -> { val n = r.u32().toInt(); OsidList(List(n.coerceAtLeast(0)) { nextId(cur.osids, cur.s++, ids) }) }
                0xC -> OidList(listOf(nextId(cur.ctxs, cur.c++, ids)))
                0xD -> { val n = r.u32().toInt(); OidList(List(n.coerceAtLeast(0)) { nextId(cur.ctxs, cur.c++, ids) }) }
                0x10 -> {
                    val n = r.u32()
                    if (n > 100_000) throw Corrupt("array too long")
                    if (n == 0L) PropArray(emptyList()) else {
                        r.u32() // prid of the element type (property set)
                        PropArray(List(n.toInt()) { parsePropSet(r, cur, ids, depth + 1) })
                    }
                }
                0x11 -> parsePropSet(r, cur, ids, depth + 1)
                else -> throw Corrupt("property type 0x${type.toString(16)}")
            }
            values[key] = v
        }
        return PropSet(values)
    }

    private fun nextId(list: List<Long>, i: Int, ids: GlobalIds): XG {
        if (i >= list.size) throw Corrupt("object id stream exhausted")
        return ids.resolve(list[i])
    }

    companion object {
        const val TYPE_ONE = "{7B5C52E4-D88C-4DA7-AEB1-5378D02996D3}"
        const val TYPE_TOC = "{43FF2FA1-EFD9-4C76-9EE2-10EA5722765F}"
        const val FORMAT_ONESTORE = "{109ADD3F-911B-49F5-A5D0-1791EDC8AED8}"
        const val FORMAT_CLOUD = "{638DE92F-A6D4-4BC1-9A36-B3FC2511A5B7}"
        const val FILE_DATA_HEADER = "{BDE316E7-2665-4511-A4C4-8D4D0B7A9EAC}"
        const val FRAGMENT_MAGIC = -0x5ba9854e0a080b3cL // 0xA4567AB1F5F7F4C4

        fun open(f: File): OneStore {
            if (!f.exists() || !f.canRead()) throw OneException(OneError.IO, "cannot read ${f.name}")
            val len = f.length()
            if (len > Int.MAX_VALUE) throw OneException(OneError.CORRUPT, "file too large")
            val buf = try {
                RandomAccessFile(f, "r").use { raf -> raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len) }
            } catch (e: Exception) { throw OneException(OneError.IO, e.message, e) }
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val s = OneStore(f, buf)
            try { s.readHeader() }
            catch (e: OneException) { throw e }
            catch (e: Exception) { throw OneException(OneError.CORRUPT, e.message, e) }
            return s
        }
    }
}
