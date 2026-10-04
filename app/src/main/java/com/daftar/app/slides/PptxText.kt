package com.daftar.app.slides

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Text is laid out at K pixels per point so font metrics are computed at a comfortable size, then scaled down. */
const val K = 4f
const val DEFAULT_FONT_PT = 18f

class RunProps(
    var sz: Float? = null, var b: Boolean? = null, var i: Boolean? = null, var u: Boolean? = null,
    var strike: Boolean? = null, var color: Int? = null, var latin: String? = null, var cs: String? = null,
    var baseline: Int? = null, var cap: String? = null, var highlight: Int? = null, var link: Boolean = false,
) {
    fun copy() = RunProps(sz, b, i, u, strike, color, latin, cs, baseline, cap, highlight, link)
    fun merge(o: RunProps?): RunProps {
        if (o == null) return this
        o.sz?.let { sz = it }; o.b?.let { b = it }; o.i?.let { i = it }; o.u?.let { u = it }
        o.strike?.let { strike = it }; o.color?.let { color = it }; o.latin?.let { latin = it }; o.cs?.let { cs = it }
        o.baseline?.let { baseline = it }; o.cap?.let { cap = it }; o.highlight?.let { highlight = it }
        if (o.link) link = true
        return this
    }
}

sealed class Bullet {
    data object None : Bullet()
    class Chr(val ch: String, val font: String?) : Bullet()
    class Num(val scheme: String, val start: Int) : Bullet()
}

class ParaProps(
    var marL: Float? = null, var indent: Float? = null, var algn: String? = null, var rtl: Boolean? = null,
    var lnPct: Float? = null, var lnPts: Float? = null,
    var befPct: Float? = null, var befPts: Float? = null, var aftPct: Float? = null, var aftPts: Float? = null,
    var bullet: Bullet? = null, var buClr: Int? = null, var buSzPct: Float? = null, var buSzPts: Float? = null,
    val run: RunProps = RunProps(),
) {
    fun merge(o: ParaProps?): ParaProps {
        if (o == null) return this
        o.marL?.let { marL = it }; o.indent?.let { indent = it }; o.algn?.let { algn = it }; o.rtl?.let { rtl = it }
        if (o.lnPct != null || o.lnPts != null) { lnPct = o.lnPct; lnPts = o.lnPts }
        if (o.befPct != null || o.befPts != null) { befPct = o.befPct; befPts = o.befPts }
        if (o.aftPct != null || o.aftPts != null) { aftPct = o.aftPct; aftPts = o.aftPts }
        o.bullet?.let { bullet = it }; o.buClr?.let { buClr = it }
        if (o.buSzPct != null || o.buSzPts != null) { buSzPct = o.buSzPct; buSzPts = o.buSzPts }
        run.merge(o.run)
        return this
    }
}

/** One level of the text style chain: a list style (lvlNpPr children) and/or extra run defaults. */
class StyleLayer(val lst: XNode?, val extra: ParaProps? = null)

fun parseRun(n: XNode?, cc: ColorCtx, ph: Int?): RunProps? {
    n ?: return null
    val r = RunProps()
    n.int("sz")?.let { r.sz = it / 100f }
    r.b = n.bool("b"); r.i = n.bool("i")
    n.attr("u")?.let { r.u = it != "none" }
    n.attr("strike")?.let { r.strike = it != "noStrike" }
    r.baseline = n.int("baseline")
    r.cap = n.attr("cap")
    for (c in n.children) when (c.name) {
        "solidFill" -> r.color = colorIn(c, cc, ph)
        "gradFill" -> r.color = c.child("gsLst")?.child("gs")?.let { colorIn(it, cc, ph) }
        "noFill" -> r.color = 0
        "latin" -> r.latin = c.attr("typeface")
        "cs" -> r.cs = c.attr("typeface")
        "highlight" -> r.highlight = colorIn(c, cc, ph)
        "hlinkClick" -> r.link = true
    }
    return r
}

fun parsePara(n: XNode?, cc: ColorCtx, ph: Int?): ParaProps? {
    n ?: return null
    val p = ParaProps()
    n.long("marL")?.let { p.marL = it / EMU_PER_PT }
    n.long("indent")?.let { p.indent = it / EMU_PER_PT }
    p.algn = n.attr("algn")
    p.rtl = n.bool("rtl")
    for (c in n.children) when (c.name) {
        "lnSpc" -> { p.lnPct = c.child("spcPct")?.int("val")?.let { it / 100000f }; p.lnPts = c.child("spcPts")?.int("val")?.let { it / 100f } }
        "spcBef" -> { p.befPct = c.child("spcPct")?.int("val")?.let { it / 100000f }; p.befPts = c.child("spcPts")?.int("val")?.let { it / 100f } }
        "spcAft" -> { p.aftPct = c.child("spcPct")?.int("val")?.let { it / 100000f }; p.aftPts = c.child("spcPts")?.int("val")?.let { it / 100f } }
        "buNone" -> p.bullet = Bullet.None
        "buChar" -> p.bullet = Bullet.Chr(c.attr("char") ?: "•", n.child("buFont")?.attr("typeface"))
        "buAutoNum" -> p.bullet = Bullet.Num(c.attr("type") ?: "arabicPeriod", c.int("startAt") ?: 1)
        "buClr" -> p.buClr = colorIn(c, cc, ph)
        "buSzPct" -> p.buSzPct = c.int("val")?.let { it / 100000f }
        "buSzPts" -> p.buSzPts = c.int("val")?.let { it / 100f }
        "defRPr" -> parseRun(c, cc, ph)?.let { p.run.merge(it) }
    }
    return p
}

fun resolveLevel(chain: List<StyleLayer>, level: Int, cc: ColorCtx, ph: Int?): ParaProps {
    val out = ParaProps()
    for (l in chain) {
        l.extra?.let { out.merge(it) }
        val lst = l.lst ?: continue
        parsePara(lst.child("defPPr"), cc, ph)?.let { out.merge(it) }
        parsePara(lst.child("lvl${level}pPr"), cc, ph)?.let { out.merge(it) }
    }
    return out
}

/** Everything text layout needs from its surroundings. */
class TextEnv(
    val cc: ColorCtx, val theme: Theme, val chain: List<StyleLayer>, val slideNum: Int,
    val defaultColor: Int, val phClr: Int? = null,
)

class BulletDraw(val text: String, val paint: TextPaint, val x: Float)
class PLine(val layout: StaticLayout, val x: Float, val top: Float, val bullet: BulletDraw?)

/** Laid-out text of one body, in K-units; [height] is in points. */
class TextBlock(val lines: List<PLine>, val heightK: Float) {
    val height: Float get() = heightK / K

    fun draw(c: Canvas, x: Float, y: Float) {
        c.save()
        c.translate(x, y)
        c.scale(1f / K, 1f / K)
        for (l in lines) {
            l.bullet?.let { b ->
                c.drawText(b.text, b.x, l.top + l.layout.getLineBaseline(0), b.paint)
            }
            c.save()
            c.translate(l.x, l.top)
            l.layout.draw(c)
            c.restore()
        }
        c.restore()
    }
}

private class RunSpan(val size: Float, val tf: Typeface, val bold: Boolean, val italic: Boolean) : MetricAffectingSpan() {
    private fun apply(p: TextPaint) {
        p.textSize = size
        val want = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        val t = if (want == 0) tf else Typeface.create(tf, want)
        p.typeface = t
        val missing = want and t.style.inv()
        p.isFakeBoldText = missing and Typeface.BOLD != 0
        p.textSkewX = if (missing and Typeface.ITALIC != 0) -0.25f else 0f
    }
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(p: TextPaint) = apply(p)
}

object Fonts {
    private val cache = HashMap<String, Typeface>()

    fun resolveName(name: String?, theme: Theme, cs: Boolean): String {
        val n = name ?: (if (cs) "+mn-cs" else "+mn-lt")
        return when (n) {
            "+mj-lt", "+mj-ea" -> theme.majorLatin
            "+mn-lt", "+mn-ea" -> theme.minorLatin
            "+mj-cs" -> theme.majorCs.ifBlank { theme.majorLatin }
            "+mn-cs" -> theme.minorCs.ifBlank { theme.minorLatin }
            else -> n
        }
    }

    /** Map an Office font name to the closest Android system family by name heuristics. */
    fun typeface(name: String): Typeface = synchronized(cache) {
        cache.getOrPut(name) {
            val n = name.lowercase()
            val family = when {
                listOf("mono", "courier", "consolas", "menlo", "lucida console", "code").any { it in n } -> "monospace"
                "sans" !in n && listOf(
                    "times", "georgia", "cambria", "garamond", "serif", "palatino", "book antiqua", "bookman", "baskerville",
                    "didot", "bodoni", "constantia", "rockwell", "century schoolbook", "traditional arabic", "amiri", "naskh",
                    "lotus", "mitra", "nazanin", "minion", "sakkal", "majalla", "andalus", "scheherazade",
                ).any { it in n } -> "serif"
                "light" in n || "thin" in n -> "sans-serif-light"
                "condensed" in n || "narrow" in n -> "sans-serif-condensed"
                "black" in n || "heavy" in n -> "sans-serif-black"
                "medium" in n || "semibold" in n || "demi" in n -> "sans-serif-medium"
                else -> "sans-serif"
            }
            Typeface.create(family, Typeface.NORMAL)
        }
    }
}

private fun isArabicChar(ch: Char): Boolean {
    val c = ch.code
    return c in 0x0590..0x08FF || c in 0xFB1D..0xFDFF || c in 0xFE70..0xFEFF
}

private fun firstStrongRtl(s: CharSequence): Boolean? {
    for (ch in s) when (Character.getDirectionality(ch)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
    }
    return null
}

private val WINGDINGS = mapOf(
    'l' to "●", 'n' to "■", 'q' to "❑", 'v' to "❖", 'Ø' to "➢", 'ü' to "✔",
    '§' to "▪", 'o' to "□", 'p' to "□", 'u' to "◆", 'à' to "→", 'è' to "➔",
    'ð' to "⇨", 'F' to "☞", '¨' to "◻", 'w' to "⬥", 'x' to "⌧",
)

private fun bulletChar(b: Bullet.Chr): String {
    val f = b.font?.lowercase() ?: ""
    val ch = b.ch.firstOrNull() ?: return "•"
    return when {
        "wingdings" in f -> WINGDINGS[ch] ?: "▪"
        "symbol" in f && (ch == '·' || ch == '') -> "•"
        ch.code in 0xF000..0xF0FF -> WINGDINGS[(ch.code - 0xF000).toChar()] ?: "•"
        else -> b.ch
    }
}

private fun roman(n0: Int): String {
    var n = n0.coerceIn(1, 3999)
    val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val sb = StringBuilder()
    for (i in v.indices) while (n >= v[i]) { sb.append(s[i]); n -= v[i] }
    return sb.toString()
}

private fun alpha(n0: Int): String {
    var n = max(1, n0)
    val sb = StringBuilder()
    while (n > 0) { n--; sb.insert(0, 'A' + n % 26); n /= 26 }
    return sb.toString()
}

private fun arabicIndic(n: Int) = n.toString().map { if (it.isDigit()) (0x0660 + (it - '0')).toChar() else it }.joinToString("")

fun formatAutoNum(scheme: String, n: Int): String {
    val core = when {
        scheme.startsWith("roman") -> roman(n).let { if ("Lc" in scheme) it.lowercase() else it }
        scheme.startsWith("alpha") -> alpha(n).let { if ("Lc" in scheme) it.lowercase() else it }
        scheme.startsWith("hindiNum") || scheme.startsWith("arabicAbjad") -> arabicIndic(n)
        else -> n.toString()
    }
    return when {
        scheme.endsWith("ParenBoth") -> "($core)"
        scheme.endsWith("ParenR") -> "$core)"
        scheme.endsWith("Period") -> "$core."
        scheme.endsWith("Minus") || scheme.endsWith("Dash") -> "$core -"
        else -> core
    }
}

/**
 * Lay out a txBody into a [TextBlock] of width [widthPt].
 * @param fontScale normAutofit font scale; @param lnReduction normAutofit line spacing reduction (0..1)
 */
fun buildText(
    tx: XNode, env: TextEnv, widthPt: Float, wrap: Boolean, fontScale: Float = 1f, lnReduction: Float = 0f,
): TextBlock {
    val wK = max(1, (widthPt * K).toInt())
    val chain = env.chain + StyleLayer(tx.child("lstStyle"))
    val lines = ArrayList<PLine>()
    var y = 0f
    val counters = IntArray(10)
    val schemes = arrayOfNulls<String>(10)
    var first = true
    val levelCache = HashMap<Int, ParaProps>()

    for (p in tx.children("p")) {
        val pPr = p.child("pPr")
        val lvl = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
        val base = levelCache.getOrPut(lvl) { resolveLevel(chain, lvl + 1, env.cc, env.phClr) }
        val props = ParaProps(run = base.run.copy()).merge(base).merge(parsePara(pPr, env.cc, env.phClr))

        val sb = SpannableStringBuilder()
        var firstSize = -1f
        var firstColor = 0
        var firstTf: Typeface? = null

        fun append(text0: String, rp: RunProps) {
            if (text0.isEmpty()) return
            val text = if (rp.cap == "all") text0.uppercase() else text0
            val arabic = text.any(::isArabicChar)
            val fontName = Fonts.resolveName(if (arabic) (rp.cs ?: rp.latin) else rp.latin, env.theme, arabic && rp.cs != null)
            val tf = Fonts.typeface(fontName)
            var size = (rp.sz ?: DEFAULT_FONT_PT) * fontScale * K
            val color = rp.color ?: (if (rp.link) env.cc.scheme("hlink", null) else env.defaultColor)
            if (firstSize < 0f) { firstSize = size; firstColor = color; firstTf = tf }
            val bl = rp.baseline ?: 0
            if (bl != 0) size *= 0.66f
            val s = sb.length
            sb.append(text)
            val e = sb.length
            sb.setSpan(RunSpan(size, tf, rp.b == true, rp.i == true), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (rp.u == true || (rp.link && rp.u == null)) sb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (rp.strike == true) sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rp.highlight?.let { sb.setSpan(BackgroundColorSpan(it), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (bl > 0) sb.setSpan(SuperscriptSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            else if (bl < 0) sb.setSpan(SubscriptSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        for (r in p.children) when (r.name) {
            "r" -> append(r.child("t")?.text ?: "", props.run.copy().merge(parseRun(r.child("rPr"), env.cc, env.phClr)))
            "fld" -> {
                val t = if (r.attr("type") == "slidenum") env.slideNum.toString() else r.child("t")?.text ?: ""
                append(t, props.run.copy().merge(parseRun(r.child("rPr"), env.cc, env.phClr)))
            }
            "br" -> append("\n", props.run.copy().merge(parseRun(r.child("rPr"), env.cc, env.phClr)))
        }
        val hasText = sb.any { !it.isWhitespace() }
        if (sb.isEmpty()) append("​", props.run.copy().merge(parseRun(p.child("endParaRPr"), env.cc, env.phClr)))
        if (firstSize < 0f) { firstSize = DEFAULT_FONT_PT * fontScale * K; firstColor = env.defaultColor }
        val sizePt = firstSize / K

        // Numbering counters: deeper levels restart after a shallower paragraph.
        for (d in lvl + 1 until 10) { counters[d] = 0; schemes[d] = null }
        var bulletText: String? = null
        when (val b = props.bullet) {
            is Bullet.Chr -> if (hasText) bulletText = bulletChar(b)
            is Bullet.Num -> if (hasText) {
                counters[lvl] = if (schemes[lvl] == b.scheme && counters[lvl] > 0) counters[lvl] + 1 else b.start
                schemes[lvl] = b.scheme
                bulletText = formatAutoNum(b.scheme, counters[lvl])
            }
            else -> if (hasText) { counters[lvl] = 0; schemes[lvl] = null }
        }

        // Direction and absolute alignment.
        val rtl = props.rtl ?: firstStrongRtl(sb) ?: false
        var algn = props.algn ?: if (rtl) "r" else "l"
        if (props.rtl == true && algn == "l") algn = "r"
        val justify = algn == "just" || algn == "dist" || algn == "justLow" || algn == "thaiDist"
        val align = when {
            algn == "ctr" -> Layout.Alignment.ALIGN_CENTER
            justify -> Layout.Alignment.ALIGN_NORMAL
            algn == "r" -> if (rtl) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
            else -> if (rtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
        }

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = firstSize; color = firstColor; typeface = firstTf ?: Typeface.SANS_SERIF
        }

        // Bullet paint and hanging indent.
        val marL = (props.marL ?: 0f) * K
        val indent = (props.indent ?: 0f) * K
        var bullet: BulletDraw? = null
        val textFirst: Float
        if (bulletText != null) {
            val bp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = props.buSzPts?.let { it * K * fontScale } ?: (firstSize * (props.buSzPct ?: 1f))
                color = props.buClr ?: firstColor
                typeface = if (props.bullet is Bullet.Num) (firstTf ?: Typeface.SANS_SERIF) else Typeface.SANS_SERIF
            }
            val bw = bp.measureText(bulletText)
            val bx = max(0f, marL + indent)
            // Hanging bullet: text starts at marL when the bullet fits before it, otherwise right after the bullet.
            textFirst = if (indent < 0f && bx + bw <= marL) marL else bx + bw + firstSize * 0.25f
            bp.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
            bullet = BulletDraw(bulletText, bp, bx)
        } else {
            textFirst = max(0f, marL + indent)
        }
        val textRest = max(0f, marL)
        val baseOff = min(textFirst, textRest)
        val layoutW0 = max(1, (wK - baseOff).toInt())
        if (textFirst != textRest) {
            val cap = max(0, layoutW0 - K.toInt() * 8)
            sb.setSpan(
                LeadingMarginSpan.Standard((textFirst - baseOff).toInt().coerceAtMost(cap), (textRest - baseOff).toInt().coerceAtMost(cap)),
                0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        var layoutW = layoutW0
        var xShift = 0f
        if (!wrap) {
            val desired = ceil(Layout.getDesiredWidth(sb, paint)).toInt() + 2 + (textFirst - baseOff).toInt()
            if (desired > layoutW0) {
                layoutW = desired
                xShift = when (align) {
                    Layout.Alignment.ALIGN_CENTER -> -(desired - layoutW0) / 2f
                    Layout.Alignment.ALIGN_OPPOSITE -> if (rtl) 0f else -(desired - layoutW0).toFloat()
                    else -> if (rtl) -(desired - layoutW0).toFloat() else 0f
                }
            }
        }

        // Line spacing: PowerPoint "100%" ≈ 1.2 × font size, close to Android's natural line height.
        val natural = paint.fontMetrics.let { it.descent - it.ascent }
        var mult = 1f
        var add = 0f
        when {
            props.lnPts != null -> add = props.lnPts!! * K * fontScale - natural
            props.lnPct != null -> mult = props.lnPct!!
        }
        if (lnReduction > 0f) mult = max(0.5f, mult - lnReduction)
        val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, layoutW)
            .setAlignment(align)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(add, mult)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (justify) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        val layout = builder.build()

        val bef = if (first) 0f else (props.befPts?.let { it * K * fontScale } ?: props.befPct?.let { it * sizePt * 1.2f * K } ?: 0f)
        val aft = props.aftPts?.let { it * K * fontScale } ?: props.aftPct?.let { it * sizePt * 1.2f * K } ?: 0f
        y += bef
        val lx = (if (rtl) 0f else baseOff) + xShift
        if (bullet != null && rtl) {
            // Mirror: bullet sits at the right, (marL + indent) from the right edge.
            val bx = wK - max(0f, marL + indent)
            bullet = BulletDraw(bullet.text, bullet.paint, bx)
        } else if (bullet != null) {
            bullet = BulletDraw(bullet.text, bullet.paint, bullet.x + xShift)
        }
        lines.add(PLine(layout, lx, y, bullet))
        y += layout.height + aft
        first = false
    }
    return TextBlock(lines, y)
}
