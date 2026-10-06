package com.daftar.app.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.daftar.app.ui.theme.D

/** Markdown subset used by AI answers. */
internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    /** [marker] is "•" or "3." ; [indent] nesting level (0..3). */
    data class Item(val marker: String, val text: String, val indent: Int) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object Rule : MdBlock()
}

internal object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(src: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out.add(MdBlock.Para(para.toString().trim())); para.clear() }
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { if (code.isNotEmpty()) code.append('\n'); code.append(lines[i]); i++ }
                    out.add(MdBlock.Code(code.toString()))
                }
                trimmed.startsWith("$$") && trimmed.length > 2 && trimmed.endsWith("$$") && trimmed.length > 4 -> {
                    flush(); out.add(MdBlock.Code(trimmed.removePrefix("$$").removeSuffix("$$").trim()))
                }
                trimmed == "$$" -> {
                    flush()
                    val math = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].trim() != "$$") { if (math.isNotEmpty()) math.append('\n'); math.append(lines[i].trim()); i++ }
                    out.add(MdBlock.Code(math.toString()))
                }
                trimmed.isEmpty() -> flush()
                rule.matches(trimmed) -> { flush(); out.add(MdBlock.Rule) }
                heading.matches(trimmed) -> { flush(); heading.find(trimmed)!!.let { out.add(MdBlock.Heading(it.groupValues[1].length, it.groupValues[2].trimEnd('#', ' '))) } }
                bullet.matches(line) -> { flush(); bullet.find(line)!!.let { out.add(MdBlock.Item("•", it.groupValues[2], indentOf(it.groupValues[1]))) } }
                numbered.matches(line) -> { flush(); numbered.find(line)!!.let { out.add(MdBlock.Item(it.groupValues[2] + ".", it.groupValues[3], indentOf(it.groupValues[1]))) } }
                trimmed.startsWith(">") -> { flush(); out.add(MdBlock.Quote(trimmed.trimStart('>', ' '))) }
                else -> {
                    // a continuation line of a list item joins that item
                    val last = out.lastOrNull()
                    if (para.isEmpty() && last is MdBlock.Item && line.startsWith("  ")) out[out.lastIndex] = last.copy(text = last.text + " " + trimmed)
                    else { if (para.isNotEmpty()) para.append('\n'); para.append(trimmed) }
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun indentOf(ws: String) = (ws.replace("\t", "    ").length / 2).coerceIn(0, 3)

    /** Inline: **bold**, __bold__, *italic*, _italic_, `code`, ~~strike~~ (kept plain), \$math\$ (dollars stripped). */
    fun inline(src: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
        val s = stripMath(src)
        var i = 0
        var bold = false
        var italic = false
        val sb = StringBuilder()
        fun emit() {
            if (sb.isEmpty()) return
            withStyle(SpanStyle(fontWeight = if (bold) FontWeight.SemiBold else null, fontStyle = if (italic) FontStyle.Italic else null)) { append(sb.toString()) }
            sb.clear()
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "*_`#\\[]" -> { sb.append(s[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        emit()
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                        i = end + 1; continue
                    }
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c -> {
                    if (bold || s.indexOf("$c$c", i + 2) > 0) { emit(); bold = !bold; i += 2; continue }
                }
                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> { i += 2; continue }
                c == '*' || c == '_' -> {
                    val prev = s.getOrNull(i - 1)
                    val next = s.getOrNull(i + 1)
                    // '_' inside a word (snake_case) is not emphasis
                    val wordUnderscore = c == '_' && ((prev?.isLetterOrDigit() == true && !italic) || (italic && next?.isLetterOrDigit() == true))
                    val opens = !italic && next != null && !next.isWhitespace() && s.indexOf(c, i + 1) > 0
                    if (!wordUnderscore && (italic || opens)) { emit(); italic = !italic; i++; continue }
                }
            }
            sb.append(c); i++
        }
        emit()
    }

    /** Makes TeX readable: drops the \$ delimiters and a few common commands. */
    fun stripMath(s: String): String {
        if ('$' !in s && '\\' !in s) return s
        var t = s.replace("$$", "").replace(Regex("(?<![\\\\\\d])\\$([^$\\n]+?)\\$")) { it.groupValues[1] }
        t = t.replace("\\(", "").replace("\\)", "").replace("\\[", "").replace("\\]", "")
        val cmds = mapOf(
            "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\le" to "≤", "\\leq" to "≤", "\\ge" to "≥", "\\geq" to "≥",
            "\\neq" to "≠", "\\approx" to "≈", "\\infty" to "∞", "\\pi" to "π", "\\alpha" to "α", "\\beta" to "β", "\\theta" to "θ",
            "\\lambda" to "λ", "\\mu" to "μ", "\\sigma" to "σ", "\\Delta" to "Δ", "\\sum" to "∑", "\\int" to "∫", "\\rightarrow" to "→",
            "\\to" to "→", "\\Rightarrow" to "⇒", "\\left" to "", "\\right" to "", "\\," to " ", "\\;" to " ", "\\quad" to "  ",
        )
        for ((k, v) in cmds.entries.sortedByDescending { it.key.length }) t = t.replace(Regex(Regex.escape(k) + "(?![a-zA-Z])"), v)
        t = t.replace(Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}")) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
        t = t.replace(Regex("\\\\sqrt\\{([^{}]*)\\}")) { "√(${it.groupValues[1]})" }
        t = t.replace(Regex("\\\\(text|mathrm|mathbf|operatorname)\\{([^{}]*)\\}")) { it.groupValues[2] }
        return t
    }
}

/** Renders an AI answer (wrap in a SelectionContainer to make it selectable). */
@Composable
internal fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val c = D.c
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = c.surfaceAlt
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(
                Markdown.inline(b.text, codeBg), color = c.ink,
                style = if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 4.dp),
            )
            is MdBlock.Para -> Text(Markdown.inline(b.text, codeBg), color = c.ink, style = body)
            is MdBlock.Item -> Row(Modifier.fillMaxWidth().padding(start = (b.indent * 16).dp)) {
                Text(b.marker, color = c.muted, style = body, modifier = Modifier.width(if (b.marker == "•") 16.dp else 26.dp))
                Text(Markdown.inline(b.text, codeBg), color = c.ink, style = body, modifier = Modifier.weight(1f))
            }
            is MdBlock.Code -> Text(
                Markdown.stripMath(b.text), color = c.ink, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(10.dp),
            )
            is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(c.line))
                Text(Markdown.inline(b.text, codeBg), color = c.muted, style = body, modifier = Modifier.padding(start = 8.dp).weight(1f))
            }
            MdBlock.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(c.line))
        }
    }
}
