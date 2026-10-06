package com.daftar.app.word

/*
 * Tiny offset-preserving XML reader for the editor. Unlike XmlPullParser it remembers where every element starts and
 * ends in the source string, so untouched parts of word/document.xml can be written back byte-for-byte.
 * Enough for OOXML parts (no DTDs); comments, processing instructions and CDATA are handled.
 */

internal sealed class XNode {
    abstract val start: Int
    abstract val end: Int
}

internal class XText(override val start: Int, override val end: Int, val text: String) : XNode()

internal class XEl(
    /** Qualified name as written, e.g. "w:p". */
    val qname: String,
    /** Attributes: qualified name → decoded value, in source order. */
    val attrs: List<Pair<String, String>>,
    override val start: Int,
    /** Index just after the start tag's '>'. */
    val openEnd: Int,
    val selfClosing: Boolean,
) : XNode() {
    /** Index of the end tag's '<' (== [openEnd] when self-closing). */
    var innerEnd: Int = openEnd
    override var end: Int = openEnd
    val children = ArrayList<XNode>()

    val local: String get() = qname.substringAfter(':')
    val prefix: String get() = if (qname.contains(':')) qname.substringBefore(':') else ""

    fun attr(localName: String): String? = attrs.firstOrNull { it.first.substringAfter(':') == localName }?.second
    fun elements(): List<XEl> = children.filterIsInstance<XEl>()
    fun child(localName: String): XEl? = children.firstOrNull { it is XEl && it.local == localName } as XEl?
    fun raw(src: String): String = src.substring(start, end)
    fun startTag(src: String): String = if (selfClosing) src.substring(start, end - 2).trimEnd() + ">" else src.substring(start, openEnd)

    /** Concatenated text of all descendant text nodes. */
    fun textContent(): String = buildString {
        fun walk(n: XNode) { when (n) { is XText -> append(n.text); is XEl -> n.children.forEach(::walk) } }
        children.forEach(::walk)
    }

    /** Depth-first search for an element with [localName]. */
    fun find(localName: String): XEl? {
        for (c in children) if (c is XEl) { if (c.local == localName) return c; c.find(localName)?.let { return it } }
        return null
    }

    fun any(pred: (XEl) -> Boolean): Boolean {
        for (c in children) if (c is XEl && (pred(c) || c.any(pred))) return true
        return false
    }
}

internal class XmlSyntaxException(msg: String) : Exception(msg)

internal object XmlLite {

    /**
     * Parses [src] between [from] and [to] into nodes. Elements deeper than [maxDepth] (1 = only the nodes in range)
     * are still delimited correctly but get no children (cheap scans of huge parts). Whitespace-only text is dropped
     * when [keepBlank] is false.
     */
    fun parse(src: String, from: Int = 0, to: Int = src.length, maxDepth: Int = Int.MAX_VALUE, keepBlank: Boolean = true): List<XNode> {
        val root = ArrayList<XNode>()
        val stack = ArrayList<XEl>()
        var i = from
        while (i < to) {
            val c = src[i]
            if (c != '<') {
                val j = src.indexOf('<', i).let { if (it < 0 || it > to) to else it }
                if (stack.size < maxDepth) {
                    val raw = src.substring(i, j)
                    if (keepBlank || raw.isNotBlank()) {
                        val t = XText(i, j, decode(raw))
                        if (stack.isEmpty()) root.add(t) else stack.last().children.add(t)
                    }
                }
                i = j
                continue
            }
            when {
                src.startsWith("<!--", i) -> { val e = src.indexOf("-->", i + 4); if (e < 0) throw XmlSyntaxException("comment"); i = e + 3 }
                src.startsWith("<![CDATA[", i) -> {
                    val e = src.indexOf("]]>", i + 9); if (e < 0) throw XmlSyntaxException("cdata")
                    if (stack.size < maxDepth) {
                        val t = XText(i, e + 3, src.substring(i + 9, e))
                        if (stack.isEmpty()) root.add(t) else stack.last().children.add(t)
                    }
                    i = e + 3
                }
                src.startsWith("<?", i) -> { val e = src.indexOf("?>", i + 2); if (e < 0) throw XmlSyntaxException("pi"); i = e + 2 }
                src.startsWith("<!", i) -> { val e = src.indexOf('>', i + 2); if (e < 0) throw XmlSyntaxException("decl"); i = e + 1 }
                src.startsWith("</", i) -> {
                    val e = src.indexOf('>', i + 2); if (e < 0) throw XmlSyntaxException("end tag")
                    val name = src.substring(i + 2, e).trim()
                    val el = stack.removeLastOrNull() ?: throw XmlSyntaxException("unexpected </$name>")
                    if (el.qname != name) throw XmlSyntaxException("</$name> closes <${el.qname}>")
                    el.innerEnd = i
                    el.end = e + 1
                    i = e + 1
                }
                else -> {
                    var j = i + 1
                    while (j < to && !src[j].isWhitespace() && src[j] != '>' && src[j] != '/') j++
                    val name = src.substring(i + 1, j)
                    if (name.isEmpty()) throw XmlSyntaxException("empty name")
                    val attrs = ArrayList<Pair<String, String>>(2)
                    var self = false
                    while (true) {
                        while (j < to && src[j].isWhitespace()) j++
                        if (j >= to) throw XmlSyntaxException("eof in tag")
                        if (src[j] == '>') { j++; break }
                        if (src[j] == '/' && j + 1 < to && src[j + 1] == '>') { self = true; j += 2; break }
                        val ns = j
                        while (j < to && src[j] != '=' && !src[j].isWhitespace() && src[j] != '>') j++
                        val an = src.substring(ns, j)
                        while (j < to && src[j].isWhitespace()) j++
                        if (j >= to || src[j] != '=') throw XmlSyntaxException("attribute $an")
                        j++
                        while (j < to && src[j].isWhitespace()) j++
                        val q = src[j]
                        if (q != '"' && q != '\'') throw XmlSyntaxException("quote")
                        val ve = src.indexOf(q, j + 1)
                        if (ve < 0 || ve > to) throw XmlSyntaxException("eof in attribute")
                        attrs.add(an to decode(src.substring(j + 1, ve)))
                        j = ve + 1
                    }
                    val el = XEl(name, attrs, i, j, self)
                    if (stack.size < maxDepth) { if (stack.isEmpty()) root.add(el) else stack.last().children.add(el) }
                    else if (stack.size == maxDepth) { /* not attached: parent is a leaf of the shallow tree */ }
                    if (self) { el.innerEnd = j; el.end = j } else stack.add(el)
                    i = j
                }
            }
        }
        if (stack.isNotEmpty()) throw XmlSyntaxException("unclosed <${stack.last().qname}>")
        return root
    }

    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val e = s.indexOf(';', i)
                if (e > i) {
                    val ent = s.substring(i + 1, e)
                    val rep: String? = when {
                        ent == "amp" -> "&"; ent == "lt" -> "<"; ent == "gt" -> ">"; ent == "quot" -> "\""; ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { sb.append(rep); i = e + 1; continue }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** Escapes text / attribute values; drops characters XML 1.0 can't carry (controls, lone surrogates, U+FFFE/F). */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> {}
                c < ' ' && c != '\t' && c != '\n' && c != '\r' -> {}
                c == '￾' || c == '￿' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** Schema order of w:rPr children (CT_RPr). Word treats out-of-order properties as a corrupt file. */
    val RPR_ORDER = listOf(
        "rStyle", "rFonts", "b", "bCs", "i", "iCs", "caps", "smallCaps", "strike", "dstrike", "outline", "shadow", "emboss",
        "imprint", "noProof", "snapToGrid", "vanish", "webHidden", "color", "spacing", "w", "kern", "position", "sz", "szCs",
        "highlight", "u", "effect", "bdr", "shd", "fitText", "vertAlign", "rtl", "cs", "em", "lang", "eastAsianLayout",
        "specVanish", "oMath", "rPrChange",
    )

    /** Schema order of w:pPr children (CT_PPr). */
    val PPR_ORDER = listOf(
        "pStyle", "keepNext", "keepLines", "pageBreakBefore", "framePr", "widowControl", "numPr", "suppressLineNumbers", "pBdr",
        "shd", "tabs", "suppressAutoHyphens", "kinsoku", "wordWrap", "overflowPunct", "topLinePunct", "autoSpaceDE", "autoSpaceDN",
        "bidi", "adjustRightInd", "snapToGrid", "spacing", "ind", "contextualSpacing", "mirrorIndents", "suppressOverlap", "jc",
        "textDirection", "textAlignment", "textboxTightWrap", "outlineLvl", "divId", "cnfStyle", "rPr", "sectPr", "pPrChange",
    )

    /** Sorts (localName, xml) pairs by [order]; unknown names keep their relative place before the change-tracking tail. */
    fun ordered(items: List<Pair<String, String>>, order: List<String>): List<Pair<String, String>> {
        val idx = { n: String -> order.indexOf(n).let { if (it < 0) order.size - 1 else it } }
        return items.withIndex().sortedWith(compareBy({ idx(it.value.first) }, { it.index })).map { it.value }
    }
}
