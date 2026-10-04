package com.daftar.app.slides

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Tiny read-only DOM for OOXML parts. Element names are stored without their namespace prefix
 * ("p:sp" -> "sp"); attribute names keep the raw qualified name ("r:embed") so that colliding
 * local names (p:sldId has both `id` and `r:id`) stay distinct. [attr] looks up the exact name first,
 * then any attribute whose local name matches.
 */
class XNode(val name: String, private val attrs: Map<String, String>) {
    val children = ArrayList<XNode>(4)
    private var textBuf: StringBuilder? = null

    /** Concatenated direct text content (whitespace preserved). */
    val text: String get() = textBuf?.toString() ?: ""

    internal fun appendText(s: String) {
        (textBuf ?: StringBuilder().also { textBuf = it }).append(s)
    }

    fun attr(n: String): String? {
        attrs[n]?.let { return it }
        if (n.indexOf(':') >= 0) return null
        for ((k, v) in attrs) {
            val c = k.indexOf(':')
            if (c >= 0 && k.length - c - 1 == n.length && k.endsWith(n)) return v
        }
        return null
    }

    fun int(n: String): Int? = attr(n)?.trim()?.toIntOrNull()
    fun long(n: String): Long? = attr(n)?.trim()?.toLongOrNull()
    fun bool(n: String): Boolean? = when (attr(n)?.trim()) {
        "1", "true", "on" -> true
        "0", "false", "off" -> false
        else -> null
    }

    fun child(n: String): XNode? {
        for (c in children) if (c.name == n) return c
        return null
    }

    fun children(n: String): List<XNode> = children.filter { it.name == n }

    /** Follow a path of child names, e.g. path("nvSpPr", "nvPr", "ph"). */
    fun path(vararg names: String): XNode? {
        var cur: XNode = this
        for (n in names) cur = cur.child(n) ?: return null
        return cur
    }

    /** First descendant (depth-first) with the given name. */
    fun find(n: String): XNode? {
        for (c in children) {
            if (c.name == n) return c
            c.find(n)?.let { return it }
        }
        return null
    }

    fun findAll(n: String, out: MutableList<XNode> = ArrayList()): MutableList<XNode> {
        for (c in children) {
            if (c.name == n) out.add(c)
            c.findAll(n, out)
        }
        return out
    }

    override fun toString() = "<$name>"

    companion object {
        fun parse(input: InputStream): XNode {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            val stack = ArrayList<XNode>()
            var root: XNode? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val n = p.attributeCount
                        val map = if (n == 0) emptyMap() else HashMap<String, String>(n * 2).also {
                            for (i in 0 until n) it[p.getAttributeName(i)] = p.getAttributeValue(i)
                        }
                        val raw = p.name
                        val node = XNode(raw.substring(raw.indexOf(':') + 1), map)
                        if (stack.isEmpty()) root = node else stack[stack.lastIndex].children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.END_TAG -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    XmlPullParser.TEXT -> if (stack.isNotEmpty()) {
                        val top = stack[stack.lastIndex]
                        // Only text-bearing leaves matter (a:t, p:text, dc:*); skip indentation between elements.
                        val t = p.text
                        if (t != null && (top.name == "t" || top.name == "text" || t.isNotBlank())) top.appendText(t)
                    }
                }
                ev = p.next()
            }
            return root ?: throw IllegalStateException("empty xml")
        }
    }
}
