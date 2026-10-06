package com.daftar.app.pdf

import android.graphics.RectF
import java.text.Bidi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A paragraph-like block of a page: consecutive lines of similar size that sit close together.
 * [box] is in displayed page points; [size] is the largest font size of its lines (0 when unknown, e.g. OCR without sizes).
 */
class TextBlock(val text: String, val box: RectF, val size: Float, val rtl: Boolean, val lineCount: Int)

/** Groups a page's lines into paragraphs and orders them with the page's pictures (reading mode, translation). */
object PdfBlocks {

    /** True when the first strong character of [s] is right-to-left (Arabic, Hebrew…). */
    fun isRtl(s: String): Boolean {
        for (ch in s) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }

    private fun endsSentence(s: String): Boolean {
        val c = s.trimEnd().lastOrNull() ?: return true
        return c in ".!?:;؟؛。…\"”»)"
    }

    /** Lines → blocks, in reading order. */
    fun blocks(t: PageText): List<TextBlock> {
        val lines = t.lines
        val out = ArrayList<TextBlock>()
        var sb = StringBuilder()
        var box: RectF? = null
        var size = 0f
        var rtl = false
        var count = 0
        var prev: RectF? = null
        var prevText = ""
        // Widest line of the page: a line that ends well before it ends its paragraph.
        var maxW = 0f
        for (i in lines.indices) t.lineBox(i)?.let { if (lines[i].isNotBlank()) maxW = max(maxW, it.width()) }

        fun flush() {
            val b = box
            if (b != null && sb.isNotBlank()) out.add(TextBlock(sb.toString().trim(), b, size, rtl, count))
            sb = StringBuilder(); box = null; size = 0f; count = 0; prev = null; prevText = ""
        }

        for (i in lines.indices) {
            val text = lines[i].trim()
            if (text.isEmpty()) continue
            val lb = t.lineBox(i)?.takeIf { it.width() > 0f && it.height() > 0f }
            val ls = t.lineSize(i)
            val lr = isRtl(text)
            val p = prev
            val b = box
            if (b != null && p != null && lb != null) {
                val h = max(p.height(), 1f)
                val gap = lb.top - p.bottom
                val sameSize = size <= 0f || ls <= 0f || abs(ls - size) <= 0.2f * max(ls, size)
                val overlapX = min(b.right, lb.right) - max(b.left, lb.left) > 0f
                val shortPrev = maxW > 0f && p.width() < maxW * 0.62f && endsSentence(prevText)
                val breakHere = !sameSize || gap > 0.9f * h || gap < -0.5f * h || !overlapX || lr != rtl || shortPrev
                if (breakHere) flush()
            } else if (b != null && lb == null) {
                // no geometry: one line per block is the safest guess
                flush()
            }
            if (box == null) {
                box = lb?.let { RectF(it) } ?: RectF()
                size = ls; rtl = lr
                sb.append(text)
            } else {
                box!!.union(lb!!)
                size = max(size, ls)
                val last = sb.lastOrNull()
                if (!lr && last == '-' && sb.length > 1 && sb[sb.length - 2].isLetter() && text.first().isLowerCase()) {
                    sb.setLength(sb.length - 1); sb.append(text)
                } else sb.append(' ').append(text)
            }
            count++
            prev = lb
            prevText = text
        }
        flush()
        return out
    }

    /** One entry of a page's content in reading order: a text block or a picture (index into [PageText.images]). */
    sealed class Item(val top: Float) {
        class Text(val block: TextBlock) : Item(block.box.top)
        class Picture(val box: RectF) : Item(box.top)
    }

    /** Blocks and pictures merged by vertical position. Pictures covering most of the page (scans) are listed first. */
    fun items(t: PageText, pageW: Float, pageH: Float): List<Item> {
        val list = ArrayList<Item>()
        blocks(t).forEach { list.add(Item.Text(it)) }
        for (k in 0 until t.imageCount) {
            val r = t.imageBox(k)
            val big = r.width() * r.height() > pageW * pageH * 0.6f
            list.add(Item.Picture(if (big) RectF(r.left, -1f, r.right, r.bottom) else r))
        }
        // De-duplicate pictures that are drawn several times at the same spot (tiled scans).
        val seen = HashSet<Long>()
        return list.filter { it !is Item.Picture || seen.add((it.box.left.toLong() shl 32) or (it.box.top.toLong() and 0xffffffffL)) }
            .sortedBy { it.top }
    }

    /** [s] in visual left-to-right order (for writing a text layer whose glyphs advance left to right). */
    fun visualOrder(s: String): String {
        if (s.isEmpty() || !Bidi.requiresBidi(s.toCharArray(), 0, s.length)) return s
        val bidi = Bidi(s, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return s
        val levels = ByteArray(s.length) { bidi.getLevelAt(it).toByte() }
        val arr = Array<Any>(s.length) { i ->
            val c = s[i]
            if ((levels[i].toInt() and 1) == 1) mirror(c) else c
        }
        Bidi.reorderVisually(levels, 0, arr, 0, arr.size)
        return buildString(s.length) { arr.forEach { append(it as Char) } }
    }

    private fun mirror(c: Char): Char = when (c) {
        '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'
        '<' -> '>'; '>' -> '<'; '«' -> '»'; '»' -> '«'
        else -> c
    }
}
