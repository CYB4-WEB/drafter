package com.daftar.app.ink

import android.graphics.RectF
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Raw extent of a stroke's points (no pen-width padding). */
private fun rawBounds(s: Stroke): RectF {
    val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    var i = 0
    while (i + 1 < s.pts.size) { r.union(s.pts[i], s.pts[i + 1]); i += 3 }
    if (r.left > r.right) r.set(0f, 0f, 0f, 0f)
    return r
}

private fun median(v: List<Float>): Float {
    if (v.isEmpty()) return 0f
    val s = v.sorted(); val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
}

/**
 * "Tidy" for lassoed handwriting (Samsung Note Assist style): pen strokes are grouped into lines, each line's baseline is
 * straightened (rotation, ≤ 12°) and every word sits on it, word gaps are evened out, line spacing is made regular, and a
 * light 1-2-1 smoothing removes jitter. Letter shapes are kept (only rigid moves + mild smoothing). Other strokes
 * (highlighter, shapes, tapes) are returned unchanged. Output keeps the input order.
 */
internal object Tidy {
    private class Item(val index: Int, var pts: FloatArray) {
        var b = RectF()
        fun measure() {
            b.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            var i = 0
            while (i + 1 < pts.size) { b.union(pts[i], pts[i + 1]); i += 3 }
        }
        fun move(dx: Float, dy: Float) {
            var i = 0
            while (i + 1 < pts.size) { pts[i] += dx; pts[i + 1] += dy; i += 3 }
            b.offset(dx, dy)
        }
    }

    fun tidy(strokes: List<Stroke>): List<Stroke> {
        val items = ArrayList<Item>()
        for ((k, s) in strokes.withIndex()) if (s.tool == Tool.PEN && s.pts.size >= 3) items.add(Item(k, smooth(s.pts)).also { it.measure() })
        if (items.isEmpty()) return strokes
        val h = max(4f, median(items.map { it.b.height() }.filter { it > 0.5f }).takeIf { it > 0f } ?: 12f)

        // ---- lines: body strokes by vertical centre, small marks (dots, diacritics) to the nearest line ----
        val body = items.filter { it.b.height() >= h * 0.35f || it.b.width() >= h * 1.2f }.sortedBy { it.b.centerY() }
        val small = items.filter { it !in body }
        val lines = ArrayList<MutableList<Item>>()
        val lineCy = ArrayList<Float>()
        for (it in body) {
            val cy = it.b.centerY()
            var best = -1; var bd = Float.MAX_VALUE
            for (k in lines.indices) { val d = abs(lineCy[k] - cy); if (d < bd) { bd = d; best = k } }
            if (best >= 0 && bd < h * 0.8f) {
                lines[best].add(it)
                lineCy[best] = lines[best].map { x -> x.b.centerY() }.average().toFloat()
            } else { lines.add(mutableListOf(it)); lineCy.add(cy) }
        }
        if (lines.isEmpty()) { lines.add(mutableListOf()); lineCy.add(items[0].b.centerY()) }
        for (it in small) {
            var best = 0; var bd = Float.MAX_VALUE
            for (k in lines.indices) { val d = abs(lineCy[k] - it.b.centerY()); if (d < bd) { bd = d; best = k } }
            lines[best].add(it)
        }

        val baselines = FloatArray(lines.size)
        for ((li, line) in lines.withIndex()) {
            val bodies = line.filter { it in body }
            if (bodies.isEmpty()) { baselines[li] = line.maxOf { it.b.bottom }; continue }
            // writing direction from drawing order: first stroke right of the last one → right-to-left (Arabic)
            val byOrder = bodies.sortedBy { it.index }
            val rtl = bodies.size >= 2 && byOrder.first().b.centerX() > byOrder.last().b.centerX()

            // 1. straighten: least-squares line through the bottoms of the body strokes
            if (bodies.size >= 3) {
                val xs = bodies.map { it.b.centerX() }; val ys = bodies.map { it.b.bottom }
                val mx = xs.average().toFloat(); val my = ys.average().toFloat()
                var num = 0f; var den = 0f
                for (k in xs.indices) { num += (xs[k] - mx) * (ys[k] - my); den += (xs[k] - mx) * (xs[k] - mx) }
                val span = xs.max() - xs.min()
                if (den > 0f && span > h * 3f) {
                    val ang = atan(num / den).coerceIn(-0.21f, 0.21f)
                    if (abs(ang) > 0.005f) {
                        val c = cos(-ang); val s = sin(-ang)
                        for (it in line) {
                            val p = it.pts; var i = 0
                            while (i + 1 < p.size) {
                                val x = p[i] - mx; val y = p[i + 1] - my
                                p[i] = mx + x * c - y * s; p[i + 1] = my + x * s + y * c
                                i += 3
                            }
                            it.measure()
                        }
                    }
                }
            }

            // 2. words: strokes whose horizontal extents overlap / nearly touch
            val sorted = line.sortedBy { it.b.left }
            val words = ArrayList<MutableList<Item>>()
            val wordR = ArrayList<Float>()
            for (it in sorted) {
                if (words.isNotEmpty() && it.b.left - wordR.last() < h * 0.45f) {
                    words.last().add(it); wordR[wordR.size - 1] = max(wordR.last(), it.b.right)
                } else { words.add(mutableListOf(it)); wordR.add(it.b.right) }
            }
            val baseline = median(bodies.map { it.b.bottom })
            baselines[li] = baseline
            // 3. every word on the baseline (small corrections only)
            for (w in words) {
                val wb = w.filter { it in body }
                if (wb.isEmpty()) continue
                val dy = (baseline - median(wb.map { it.b.bottom })).coerceIn(-h * 0.6f, h * 0.6f)
                if (abs(dy) > 0.2f) w.forEach { it.move(0f, dy) }
            }
            // 4. even word spacing (needs at least two gaps to know what "even" is)
            if (words.size >= 3) {
                val wl = words.map { w -> w.minOf { it.b.left } }; val wr = words.map { w -> w.maxOf { it.b.right } }
                val gaps = (1 until words.size).map { wl[it] - wr[it - 1] }
                val target = median(gaps).coerceIn(h * 0.5f, h * 1.6f)
                if (!rtl) {
                    var x = wr[0]
                    for (k in 1 until words.size) { val dx = x + target - wl[k]; words[k].forEach { it.move(dx, 0f) }; x = wr[k] + dx }
                } else {
                    var x = wl.last()
                    for (k in words.size - 2 downTo 0) { val dx = x - target - wr[k]; words[k].forEach { it.move(dx, 0f) }; x = wl[k] + dx }
                }
            }
        }

        // 5. regular line spacing (3+ lines), top line stays
        if (lines.size >= 3) {
            val order = lines.indices.sortedBy { baselines[it] }
            val diffs = (1 until order.size).map { baselines[order[it]] - baselines[order[it - 1]] }
            val sp = median(diffs)
            if (sp > h) for ((k, li) in order.withIndex()) {
                val target = baselines[order[0]] + k * sp
                val dy = (target - baselines[li]).coerceIn(-sp * 0.5f, sp * 0.5f)
                if (abs(dy) > 0.2f) lines[li].forEach { it.move(0f, dy) }
            }
        }

        val out = strokes.toMutableList()
        for (it in items) {
            val s = strokes[it.index]
            out[it.index] = Stroke(s.tool, s.color, s.width, it.pts, s.rec, s.t, s.style)
        }
        return out
    }

    /** One 1-2-1 pass on interior points (ends and pressure kept): removes jitter, keeps the letter shapes. */
    private fun smooth(p: FloatArray): FloatArray {
        val n = p.size / 3
        val o = p.copyOf()
        if (n < 5) return o
        for (i in 1 until n - 1) {
            o[i * 3] = 0.25f * p[(i - 1) * 3] + 0.5f * p[i * 3] + 0.25f * p[(i + 1) * 3]
            o[i * 3 + 1] = 0.25f * p[(i - 1) * 3 + 1] + 0.5f * p[i * 3 + 1] + 0.25f * p[(i + 1) * 3 + 1]
        }
        return o
    }
}

/**
 * Math helper geometry (GoodNotes Math Assist style, offline): spots a freshly written "=" (the last two pen strokes:
 * short, flat, stacked, overlapping) and collects the strokes of the expression on the same line, so only that line is
 * sent to the handwriting recognizer — nothing runs for ordinary writing.
 */
object MathAssist {
    /** The expression line ending with an "=": its strokes (page order), the "=" box, and whether it reads right-to-left. */
    class Line(val strokes: List<Stroke>, val eq: RectF, val rightToLeft: Boolean, val charH: Float, val color: Int)

    fun findLine(st: List<Stroke>): Line? {
        var n = st.size - 1
        while (n >= 0 && st[n].tool != Tool.PEN) n--
        if (n < 1) return null
        val b = st[n]; val a = st[n - 1]
        if (a.tool != Tool.PEN) return null
        val ra = rawBounds(a); val rb = rawBounds(b)
        if (!isDash(ra, a) || !isDash(rb, b)) return null
        val wa = ra.width(); val wb = rb.width()
        if (min(wa, wb) / max(wa, wb) < 0.45f) return null
        val overlap = min(ra.right, rb.right) - max(ra.left, rb.left)
        if (overlap < 0.5f * min(wa, wb)) return null
        val sep = abs(ra.centerY() - rb.centerY())
        val w = max(wa, wb)
        if (sep < 0.12f * w || sep > 1.1f * w) return null
        val eq = RectF(ra).apply { union(rb) }
        val charH = max(w * 1.3f, sep * 2.6f)
        val cy = eq.centerY()
        val others = st.filterIndexed { i, s ->
            i != n && i != n - 1 && s.tool == Tool.PEN && run {
                val r = rawBounds(s)
                abs(r.centerY() - cy) < charH * 1.1f && r.height() < charH * 3f
            }
        }
        if (others.isEmpty()) return null
        fun chain(left: Boolean): List<Stroke> {
            val side = others.filter { val r = rawBounds(it); if (left) r.centerX() < eq.left else r.centerX() > eq.right }
                .sortedBy { val r = rawBounds(it); if (left) -r.right else r.left }
            val out = ArrayList<Stroke>()
            var edge = if (left) eq.left else eq.right
            for (s in side) {
                val r = rawBounds(s)
                val gap = if (left) edge - r.right else r.left - edge
                if (gap > charH * 1.6f) break
                out.add(s)
                edge = if (left) min(edge, r.left) else max(edge, r.right)
            }
            return out
        }
        val left = chain(true); val right = chain(false)
        val ltr = left.size >= right.size
        val expr = if (ltr) left else right
        val answerSide = if (ltr) right else left
        if (expr.isEmpty()) return null
        // something already written right after the "=": the user answered it (or it is not a calculation)
        if (answerSide.any { val r = rawBounds(it); (if (ltr) r.left - eq.right else eq.left - r.right) < charH * 0.9f }) return null
        val set = (expr + a + b).toHashSet()
        return Line(st.filter { it in set }, eq, !ltr, charH, b.color)
    }

    private fun isDash(r: RectF, s: Stroke): Boolean {
        val w = r.width(); val h = r.height()
        return w >= 3f && h <= max(w * 0.35f, s.width * 1.5f)
    }
}

/**
 * Small, safe calculator for recognized handwriting: + − × ÷ * / ^ √ % parentheses, decimals, Arabic-Indic digits,
 * implicit multiplication before "(" and "√". A recursive-descent parser over a whitelist of characters — never an
 * eval engine. Returns null for anything it does not fully understand.
 */
internal object MathEval {
    /** "12×7=" → "84" (Arabic-Indic digits in → Arabic-Indic out). Null when there is no complete expression ending in "=". */
    fun answerFor(recognized: String, requireEquals: Boolean = true): String? {
        val raw = recognized.replace("\n", "").trim()
        val eqAt = raw.lastIndexOf('=')
        val expr = when {
            eqAt >= 0 -> { if (raw.substring(eqAt + 1).isNotBlank()) return null; raw.substring(0, eqAt) }
            requireEquals -> return null
            else -> raw
        }
        val arabic = expr.any { it in '٠'..'٩' || it in '۰'..'۹' }
        val v = evaluate(expr) ?: return null
        return format(v, arabic)
    }

    fun evaluate(input: String): Double? {
        val s = normalize(input) ?: return null
        if (s.isEmpty() || s.length > 80) return null
        // a lone number is not a calculation
        if (s.all { it.isDigit() || it == '.' }) return null
        return runCatching {
            val p = Parser(s)
            val v = p.expr()
            if (p.pos != s.length) null else v.takeIf { it.isFinite() && abs(it) < 1e15 }
        }.getOrNull()
    }

    private fun normalize(t: String): String? {
        val sb = StringBuilder()
        for (ch in t) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                '+', '＋' -> sb.append('+')
                '-', '−', '–', '—', '‐' -> sb.append('-')
                '*', '×', 'x', 'X', '✕', '✖', '·', '∙', '•' -> sb.append('*')
                '/', '÷', ':', '∕' -> sb.append('/')
                '^' -> sb.append('^')
                '²' -> sb.append("^2")
                '³' -> sb.append("^3")
                '√', '✓' -> sb.append('r')     // the recognizer sometimes reads √ as a check mark
                '%', '٪' -> sb.append('%')
                '(', '[', '{', '（' -> sb.append('(')
                ')', ']', '}', '）' -> sb.append(')')
                '.', ',', '٫' -> sb.append('.')
                ' ', '‏', '‎', '٬', '\'' -> {}
                else -> return null
            }
        }
        return sb.toString()
    }

    private class Parser(val s: String) {
        var pos = 0
        private var depth = 0
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun expr(): Double {
            if (++depth > 40) error("deep")
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> { depth--; return v }
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= unary() }
                    '/' -> { pos++; val d = unary(); if (d == 0.0) error("div0"); v /= d }
                    '(', 'r' -> v *= unary()                      // 2(3+4), 2√9
                    else -> return v
                }
            }
        }

        private fun unary(): Double = when (peek()) {
            '-' -> { pos++; -unary() }
            '+' -> { pos++; unary() }
            else -> power()
        }

        private fun power(): Double {
            val b = postfix()
            if (peek() == '^') {
                pos++
                val e = unary()
                if (abs(e) > 1000) error("big")
                val r = b.pow(e)
                if (r.isNaN()) error("nan")
                return r
            }
            return b
        }

        private fun postfix(): Double {
            var v = primary()
            while (peek() == '%') { pos++; v /= 100.0 }
            return v
        }

        private fun primary(): Double {
            val c = peek()
            return when {
                c == '(' -> {
                    pos++
                    val v = expr()
                    if (peek() == ')') pos++ else if (pos < s.length) error("paren")   // a missing final ")" is forgiven
                    v
                }
                c == 'r' -> { pos++; val v = postfix(); if (v < 0) error("sqrt"); sqrt(v) }
                c.isDigit() || c == '.' -> {
                    val start = pos
                    var dots = 0
                    while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) { if (s[pos] == '.') dots++; pos++ }
                    if (dots > 1) error("number")
                    val t = s.substring(start, pos)
                    if (t == ".") error("number")
                    t.toDouble()
                }
                else -> error("unexpected")
            }
        }
    }

    fun format(v: Double, arabic: Boolean): String {
        val r = Math.round(v)
        val txt = if (abs(v - r) < 1e-9 * max(1.0, abs(v))) r.toString()
        else BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
        if (!arabic) return txt
        return txt.map { ch -> if (ch in '0'..'9') '٠' + (ch - '0') else if (ch == '.') '٫' else ch }.joinToString("")
    }
}
