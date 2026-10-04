package com.daftar.app.onenote

/**
 * LZX decompressor for cabinet folders (typeCompress 3, window 2^15..2^21). Follows the published LZX format
 * (verbatim / aligned offset / uncompressed blocks, delta-coded tree lengths via a pretree, repeated offsets R0-R2,
 * Intel E8 call translation, 16-bit realignment at every 32 KB frame). Pure JVM.
 *
 * [input] returns the next CFDATA payload (null at the end of the folder); call [frame] once per CFDATA block with that
 * block's uncompressed size.
 */
internal class Lzx(windowBits: Int, private val input: () -> ByteArray?) {
    private val windowSize = 1 shl windowBits
    private val window = ByteArray(windowSize)
    private var windowPos = 0
    private val slots = when (windowBits) { 15 -> 30; 16 -> 32; 17 -> 34; 18 -> 36; 19 -> 38; 20 -> 42; 21 -> 50; else -> throw OneException(OneError.PACKAGE_COMPRESSION, "lzx window $windowBits") }
    private val mainLens = IntArray(NUM_CHARS + 50 * 8)
    private val lengthLens = IntArray(NUM_SECONDARY)
    private val alignedLens = IntArray(8)
    private val mainElements = NUM_CHARS + slots * 8
    private var main = Huff.EMPTY
    private var length = Huff.EMPTY
    private var aligned = Huff.EMPTY

    private var r0 = 1; private var r1 = 1; private var r2 = 1
    private var headerRead = false
    private var intelSize = 0
    private var intelStarted = false
    private var intelPos = 0
    private var frameNo = 0
    private var blockType = 0
    private var blockLength = 0
    private var blockRemaining = 0

    // ---------------------------------------------------------------- bit input (16-bit LE words, MSB first)
    private var buf = ByteArray(0)
    private var pos = 0
    private var bitBuf = 0L
    private var bitsLeft = 0
    private var exhausted = 0

    private fun nextByte(): Int {
        while (pos >= buf.size) {
            val n = input()
            if (n == null) { exhausted++; if (exhausted > 64) throw OneException(OneError.CORRUPT, "lzx: input ended"); return 0 }
            buf = n; pos = 0
        }
        return buf[pos++].toInt() and 0xFF
    }

    private fun ensure(n: Int) {
        while (bitsLeft < n) {
            val b0 = nextByte(); val b1 = nextByte()
            bitBuf = bitBuf or (((b1 shl 8) or b0).toLong() shl (48 - bitsLeft))
            bitsLeft += 16
        }
    }
    private fun peek(n: Int): Int = (bitBuf ushr (64 - n)).toInt()
    private fun remove(n: Int) { bitBuf = bitBuf shl n; bitsLeft -= n }
    private fun bits(n: Int): Int { if (n == 0) return 0; ensure(n); val v = peek(n); remove(n); return v }

    private fun sym(h: Huff): Int {
        if (h.symbols.isEmpty()) throw OneException(OneError.CORRUPT, "lzx: empty tree used")
        ensure(16)
        val p = peek(16)
        var first = 0; var index = 0
        for (len in 1..16) {
            val code = p ushr (16 - len)
            val count = h.counts[len]
            if (code - first < count) { remove(len); return h.symbols[index + code - first] }
            index += count; first = (first + count) shl 1
        }
        throw OneException(OneError.CORRUPT, "lzx: bad huffman code")
    }

    /** Canonical Huffman decoding data (counts per length, symbols ordered by code). */
    private class Huff(val counts: IntArray, val symbols: IntArray) {
        companion object {
            val EMPTY = Huff(IntArray(17), IntArray(0))
            fun of(lens: IntArray, n: Int): Huff {
                val counts = IntArray(17)
                for (i in 0 until n) { val l = lens[i]; if (l !in 0..16) throw OneException(OneError.CORRUPT, "lzx: code length"); counts[l]++ }
                counts[0] = 0
                // over-subscription check (an empty or incomplete tree is allowed)
                var left = 1
                for (len in 1..16) { left = (left shl 1) - counts[len]; if (left < 0) throw OneException(OneError.CORRUPT, "lzx: over-subscribed tree") }
                val offs = IntArray(17)
                for (len in 1 until 16) offs[len + 1] = offs[len] + counts[len]
                val total = (1..16).sumOf { counts[it] }
                val syms = IntArray(total)
                for (i in 0 until n) { val l = lens[i]; if (l != 0) syms[offs[l]++] = i }
                return Huff(counts, syms)
            }
        }
    }

    private fun readLengths(lens: IntArray, first: Int, last: Int) {
        val preLens = IntArray(20) { bits(4) }
        val pre = Huff.of(preLens, 20)
        var x = first
        while (x < last) {
            when (val z = sym(pre)) {
                17 -> { var y = bits(4) + 4; while (y-- > 0 && x < last) lens[x++] = 0 }
                18 -> { var y = bits(5) + 20; while (y-- > 0 && x < last) lens[x++] = 0 }
                19 -> {
                    var y = bits(1) + 4
                    var v = lens[x] - sym(pre); if (v < 0) v += 17
                    while (y-- > 0 && x < last) lens[x++] = v
                }
                else -> { var v = lens[x] - z; if (v < 0) v += 17; lens[x++] = v }
            }
        }
    }

    private fun readBlockHeader() {
        if (blockType == UNCOMPRESSED && blockLength and 1 == 1) nextByte() // odd uncompressed blocks are padded
        blockType = bits(3)
        blockLength = (bits(16) shl 8) or bits(8)
        blockRemaining = blockLength
        when (blockType) {
            ALIGNED, VERBATIM -> {
                if (blockType == ALIGNED) { for (i in 0 until 8) alignedLens[i] = bits(3); aligned = Huff.of(alignedLens, 8) }
                readLengths(mainLens, 0, NUM_CHARS)
                readLengths(mainLens, NUM_CHARS, mainElements)
                main = Huff.of(mainLens, mainElements)
                if (mainLens[0xE8] != 0) intelStarted = true
                readLengths(lengthLens, 0, NUM_SECONDARY)
                length = Huff.of(lengthLens, NUM_SECONDARY)
            }
            UNCOMPRESSED -> {
                intelStarted = true
                // realign to 16 bits: 1-16 bits of padding, then R0-R2 as little-endian 32-bit values
                ensure(16)
                if (bitsLeft > 16) { pos -= 2; if (pos < 0) throw OneException(OneError.CORRUPT, "lzx: realign across blocks") }
                bitBuf = 0; bitsLeft = 0
                r0 = le32(); r1 = le32(); r2 = le32()
            }
            else -> throw OneException(OneError.CORRUPT, "lzx: block type $blockType")
        }
    }

    private fun le32(): Int = nextByte() or (nextByte() shl 8) or (nextByte() shl 16) or (nextByte() shl 24)

    private fun put(b: Int) { window[windowPos] = b.toByte(); windowPos = (windowPos + 1) and (windowSize - 1) }

    private fun copyMatch(off: Int, len: Int) {
        if (off <= 0 || off > windowSize) throw OneException(OneError.CORRUPT, "lzx: match offset")
        var src = (windowPos - off) and (windowSize - 1)
        for (i in 0 until len) { window[windowPos] = window[src]; windowPos = (windowPos + 1) and (windowSize - 1); src = (src + 1) and (windowSize - 1) }
    }

    /** Decodes the next frame of [size] bytes. */
    fun frame(size: Int): ByteArray {
        if (size <= 0 || size > 32768) throw OneException(OneError.CORRUPT, "lzx: frame size $size")
        if (!headerRead) {
            if (bits(1) == 1) intelSize = (bits(16) shl 16) or bits(16)
            headerRead = true
        }
        val start = windowPos
        var todo = size
        while (todo > 0) {
            if (blockRemaining == 0) readBlockHeader()
            var run = minOf(blockRemaining, todo)
            todo -= run
            blockRemaining -= run
            if (blockType == UNCOMPRESSED) {
                while (run > 0) { put(nextByte()); run-- }
            } else {
                while (run > 0) {
                    var m = sym(main)
                    if (m < NUM_CHARS) { put(m); run--; continue }
                    m -= NUM_CHARS
                    var len = m and 7
                    if (len == 7) len += sym(length)
                    len += 2
                    var off = m ushr 3
                    if (off > 2) {
                        if (blockType == ALIGNED) {
                            val extra = EXTRA[off]
                            off = BASE[off] - 2
                            when {
                                extra > 3 -> { off += bits(extra - 3) shl 3; off += sym(aligned) }
                                extra == 3 -> off += sym(aligned)
                                extra > 0 -> off += bits(extra)
                                else -> off = 1
                            }
                        } else {
                            off = if (off != 3) BASE[off] - 2 + bits(EXTRA[off]) else 1
                        }
                        r2 = r1; r1 = r0; r0 = off
                    } else when (off) {
                        0 -> off = r0
                        1 -> { off = r1; r1 = r0; r0 = off }
                        else -> { off = r2; r2 = r0; r0 = off }
                    }
                    copyMatch(off, len)
                    run -= len
                }
            }
            if (run < 0) {
                if (-run > blockRemaining) throw OneException(OneError.CORRUPT, "lzx: match past block")
                blockRemaining += run
                todo += run
                if (todo < 0) throw OneException(OneError.CORRUPT, "lzx: match past frame")
            }
        }
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = window[(start + i) and (windowSize - 1)]
        // frames end on a 16-bit boundary
        if (bitsLeft > 0) ensure(16)
        if (bitsLeft and 15 != 0) remove(bitsLeft and 15)
        if (intelStarted && intelSize != 0 && frameNo < 32768 && size > 10) e8(out)
        intelPos += size
        frameNo++
        return out
    }

    private fun e8(d: ByteArray) {
        var i = 0
        var cur = intelPos
        val end = d.size - 10
        while (i < end) {
            if (d[i].toInt() and 0xFF != 0xE8) { i++; cur++; continue }
            val abs = (d[i + 1].toInt() and 0xFF) or ((d[i + 2].toInt() and 0xFF) shl 8) or ((d[i + 3].toInt() and 0xFF) shl 16) or ((d[i + 4].toInt() and 0xFF) shl 24)
            if (abs >= -cur && abs < intelSize) {
                val rel = if (abs >= 0) abs - cur else abs + intelSize
                d[i + 1] = rel.toByte(); d[i + 2] = (rel shr 8).toByte(); d[i + 3] = (rel shr 16).toByte(); d[i + 4] = (rel shr 24).toByte()
            }
            i += 5; cur += 5
        }
    }

    companion object {
        const val NUM_CHARS = 256
        const val NUM_SECONDARY = 249
        const val VERBATIM = 1
        const val ALIGNED = 2
        const val UNCOMPRESSED = 3
        val EXTRA = IntArray(52).also { e -> var j = 0; var i = 0; while (i < 51) { e[i] = j; e[i + 1] = j; if (i != 0 && j < 17) j++; i += 2 } }
        val BASE = IntArray(52).also { b -> var j = 0; for (i in 0 until 51) { b[i] = j; j += 1 shl EXTRA[i] } }
    }
}
