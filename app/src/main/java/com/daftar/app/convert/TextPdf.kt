package com.daftar.app.convert

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Plain text, Markdown and RTF → paginated A4 PDF with real text. Each paragraph takes its direction from its first
 * strong character (Arabic paragraphs are right-aligned and shaped by the platform text stack), long files are laid
 * out in chunks so memory stays bounded. Blocking — call from Dispatchers.IO.
 */
object TextPdf {
    enum class Style { PLAIN, MARKDOWN, MONO }

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 56f
    private const val INK = 0xFF16191D.toInt()
    private const val MUTED = 0xFF6B7280.toInt()
    private const val CODE_BG = 0xFFF1F0EC.toInt()
    private const val LINK = 0xFF1D4ED8.toInt()
    private const val CHUNK = 6000

    suspend fun write(ctx: Context, text: String, style: Style, out: File, progress: Progress) {
        val doc = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = INK
                textSize = if (style == Style.MONO) 9.5f else 11f
                typeface = if (style == Style.MONO) Typeface.MONOSPACE else Typeface.DEFAULT
            }
            val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED; textSize = 8.5f; textAlign = Paint.Align.CENTER }
            val contentW = (PAGE_W - 2 * MARGIN).toInt()
            val contentH = PAGE_H - 2 * MARGIN
            val chunks = chunks(text.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n'))
            val md = if (style == Style.MARKDOWN) Markdown() else null

            var page: PdfDocument.Page? = null
            var pageNo = 0
            var y = 0f
            fun finishPage() {
                val p = page ?: return
                p.canvas.drawText(pageNo.toString(), PAGE_W / 2f, PAGE_H - MARGIN / 2f, footer)
                doc.finishPage(p)
                page = null
            }
            fun startPage(): PdfDocument.Page {
                pageNo++
                y = 0f
                return doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()).also {
                    it.canvas.drawColor(android.graphics.Color.WHITE)
                    page = it
                }
            }

            progress(0, chunks.size)
            for ((ci, chunk) in chunks.withIndex()) {
                currentCoroutineContext().ensureActive()
                val cs: CharSequence = md?.build(chunk) ?: chunk
                val layout = StaticLayout.Builder.obtain(cs, 0, cs.length, paint, contentW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setLineSpacing(0f, if (style == Style.MONO) 1.15f else 1.3f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
                var line = 0
                while (line < layout.lineCount) {
                    val pg = page ?: startPage()
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentH - y) end++
                    if (end == line) {
                        if (y > 0f) { finishPage(); continue }
                        end = line + 1 // a single line taller than the page: draw it clipped
                    }
                    val bottom = layout.getLineBottom(end - 1)
                    val c: Canvas = pg.canvas
                    c.save()
                    c.translate(MARGIN, MARGIN + y - top)
                    c.clipRect(-MARGIN / 2f, top.toFloat(), contentW + MARGIN / 2f, bottom.toFloat())
                    layout.draw(c)
                    c.restore()
                    y += (bottom - top).toFloat()
                    line = end
                    if (line < layout.lineCount) finishPage()
                }
                progress(ci + 1, chunks.size)
            }
            if (page == null && pageNo == 0) startPage() // empty file → one blank page
            finishPage()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    /** Groups whole lines into chunks of about [CHUNK] chars (very long lines are cut at a space). */
    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) } }
        for (raw in text.split('\n')) {
            var line = raw
            while (line.length > CHUNK) {
                var cut = line.lastIndexOf(' ', CHUNK)
                if (cut < CHUNK / 2) cut = CHUNK
                flush(); out.add(line.substring(0, cut)); line = line.substring(cut).trimStart()
            }
            if (sb.isNotEmpty() && sb.length + line.length > CHUNK) flush()
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
            if (sb.isEmpty()) sb.append(' ') // keep empty lines between chunks
        }
        flush()
        return out.ifEmpty { listOf(" ") }
    }

    // ---------------------------------------------------------------------------------------- decoding

    /** Bytes → text: BOM (UTF-8/16), then strict UTF-8, then the Arabic Windows code page, then Latin-1. */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString()
        } catch (_: CharacterCodingException) {
        }
        val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrNull()
        return String(b, cp1256 ?: Charsets.ISO_8859_1)
    }

    /** Minimal RTF reader: text, paragraphs, tabs, \uN and \'hh escapes; skips destinations (fonts, pictures, …). */
    fun rtfToText(rtf: String): String {
        if (!rtf.trimStart().startsWith("{\\rtf")) return rtf
        val out = StringBuilder(rtf.length / 2)
        val skipDest = setOf(
            "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "headerf",
            "footerl", "footerr", "footerf", "object", "themedata", "colorschememapping", "datastore", "latentstyles",
            "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "mmathPr", "fldinst", "filetbl",
            "revtbl", "pgdsctbl", "bkmkstart", "bkmkend", "footnote",
        )
        data class St(var skip: Boolean, var uc: Int)
        val stack = ArrayDeque<St>()
        var st = St(false, 1)
        var charset: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        var pendingSkip = 0
        var i = 0
        val n = rtf.length
        fun emit(s: String) { if (!st.skip) out.append(s) }
        while (i < n) {
            val ch = rtf[i]
            when (ch) {
                '{' -> { stack.addLast(st.copy()); i++ }
                '}' -> { st = stack.removeLastOrNull() ?: st; pendingSkip = 0; i++ }
                '\r', '\n' -> i++
                '\\' -> {
                    i++
                    if (i >= n) break
                    val c2 = rtf[i]
                    when {
                        c2 == '\\' || c2 == '{' || c2 == '}' -> {
                            if (pendingSkip > 0) pendingSkip-- else emit(c2.toString())
                            i++
                        }
                        c2 == '\'' -> {
                            val hex = rtf.substring(i + 1, minOf(i + 3, n))
                            i += 3
                            if (pendingSkip > 0) { pendingSkip--; continue }
                            hex.toIntOrNull(16)?.let { emit(String(byteArrayOf(it.toByte()), charset)) }
                        }
                        c2 == '*' -> { st.skip = true; i++ }
                        c2 == '~' -> { emit(" "); i++ }
                        c2 == '_' -> { emit("-"); i++ }
                        c2 == '-' -> i++
                        c2 == '\n' || c2 == '\r' -> { emit("\n"); i++ }
                        c2.isLetter() -> {
                            val ws = i
                            while (i < n && rtf[i].isLetter()) i++
                            val word = rtf.substring(ws, i)
                            val ps = i
                            if (i < n && (rtf[i] == '-' || rtf[i].isDigit())) { i++; while (i < n && rtf[i].isDigit()) i++ }
                            val param = rtf.substring(ps, i).toIntOrNull()
                            if (i < n && rtf[i] == ' ') i++
                            when (word) {
                                "par", "line", "sect", "page", "row" -> emit("\n")
                                "tab", "cell" -> emit("\t")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "bullet" -> emit("•")
                                "lquote" -> emit("‘")
                                "rquote" -> emit("’")
                                "ldblquote" -> emit("“")
                                "rdblquote" -> emit("”")
                                "uc" -> st.uc = param ?: 1
                                "u" -> if (param != null) {
                                    emit((if (param < 0) param + 65536 else param).toChar().toString())
                                    pendingSkip = st.uc
                                }
                                "ansicpg" -> if (param != null) runCatching { charset = Charset.forName("windows-$param") }
                                else -> if (word in skipDest) st.skip = true
                            }
                        }
                        else -> i++
                    }
                }
                else -> {
                    if (pendingSkip > 0) pendingSkip-- else emit(ch.toString())
                    i++
                }
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    // ---------------------------------------------------------------------------------------- markdown

    private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(\\[[ xX]]\\s+)?(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9}[.)])\\s+(.*)$")
    private val INLINE = Regex(
        "`([^`]+)`" +
            "|\\*\\*(.+?)\\*\\*" +
            "|__(.+?)__" +
            "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])" +
            "|(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])" +
            "|!\\[([^\\]]*)]\\(([^)]*)\\)" +
            "|\\[([^\\]]+)]\\(([^)]*)\\)" +
            "|~~(.+?)~~",
    )
    private val HEADING_SIZES = floatArrayOf(1.75f, 1.45f, 1.25f, 1.12f, 1.02f, 0.95f)

    /** Draws a thin horizontal rule across the line (Markdown `---`). */
    private class RuleSpan : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            val old = paint.color; val oldW = paint.strokeWidth
            paint.color = 0xFFD1D5DB.toInt(); paint.strokeWidth = 0.8f
            val y = (top + bottom) / 2f
            canvas.drawLine(left.toFloat(), y, right.toFloat(), y, paint)
            paint.color = old; paint.strokeWidth = oldW
        }
    }

    /** Stateful (fenced code blocks may span chunks) Markdown → styled text. */
    private class Markdown {
        var inCode = false

        fun build(chunk: String): CharSequence {
            val sb = SpannableStringBuilder()
            for ((i, raw) in chunk.split('\n').withIndex()) {
                if (i > 0) sb.append('\n')
                val start = sb.length
                val t = raw.trimStart()
                if (t.startsWith("```") || t.startsWith("~~~")) { inCode = !inCode; continue }
                if (inCode) {
                    sb.append(raw.ifEmpty { " " })
                    span(sb, start, TypefaceSpan("monospace"), BackgroundColorSpan(CODE_BG), RelativeSizeSpan(0.92f))
                    continue
                }
                val h = HEADING.find(raw)
                if (h != null) {
                    val level = h.groupValues[1].length
                    inline(sb, h.groupValues[2])
                    span(sb, start, RelativeSizeSpan(HEADING_SIZES[level - 1]), StyleSpan(Typeface.BOLD))
                    continue
                }
                if (RULE.matches(raw)) { sb.append(' '); span(sb, start, RuleSpan()); continue }
                val q = QUOTE.find(raw)
                if (q != null) {
                    inline(sb, q.groupValues[1].ifEmpty { " " })
                    span(sb, start, QuoteSpan(0xFFCBD5E1.toInt()), LeadingMarginSpan.Standard(10), ForegroundColorSpan(MUTED))
                    continue
                }
                val b = BULLET.find(raw)
                if (b != null) {
                    val level = b.groupValues[1].replace("\t", "  ").length / 2
                    val task = b.groupValues[2].trim()
                    val mark = when { task.isEmpty() -> "\u2022  "; task.contains('x', true) -> "\u2611  "; else -> "\u2610  " }
                    sb.append(mark)
                    inline(sb, b.groupValues[3])
                    span(sb, start, LeadingMarginSpan.Standard(14 * level, 14 * level + 12))
                    continue
                }
                val o = ORDERED.find(raw)
                if (o != null) {
                    val level = o.groupValues[1].replace("\t", "  ").length / 2
                    sb.append(o.groupValues[2]).append("  ")
                    inline(sb, o.groupValues[3])
                    span(sb, start, LeadingMarginSpan.Standard(14 * level, 14 * level + 16))
                    continue
                }
                inline(sb, raw)
            }
            return sb
        }

        private fun span(sb: SpannableStringBuilder, start: Int, vararg spans: Any) {
            if (sb.length <= start) return
            for (s in spans) sb.setSpan(s, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun inline(sb: SpannableStringBuilder, text: String) {
            var last = 0
            for (m in INLINE.findAll(text)) {
                sb.append(text, last, m.range.first)
                val s = sb.length
                val g = m.groups
                when {
                    g[1] != null -> { sb.append(g[1]!!.value); span(sb, s, TypefaceSpan("monospace"), BackgroundColorSpan(CODE_BG)) }
                    g[2] != null -> { inline(sb, g[2]!!.value); span(sb, s, StyleSpan(Typeface.BOLD)) }
                    g[3] != null -> { inline(sb, g[3]!!.value); span(sb, s, StyleSpan(Typeface.BOLD)) }
                    g[4] != null -> { inline(sb, g[4]!!.value); span(sb, s, StyleSpan(Typeface.ITALIC)) }
                    g[5] != null -> { inline(sb, g[5]!!.value); span(sb, s, StyleSpan(Typeface.ITALIC)) }
                    g[6] != null -> { sb.append("[").append(g[6]!!.value.ifBlank { "image" }).append("]"); span(sb, s, ForegroundColorSpan(MUTED)) }
                    g[8] != null -> { inline(sb, g[8]!!.value); span(sb, s, UnderlineSpan(), ForegroundColorSpan(LINK)) }
                    g[10] != null -> { inline(sb, g[10]!!.value); span(sb, s, StrikethroughSpan()) }
                }
                last = m.range.last + 1
            }
            sb.append(text, last, text.length)
        }
    }

    /** One Markdown line → plain text (for Text → Word): markers removed, bullets kept as "•", links as "text (url)". */
    fun markdownToPlain(line: String): String {
        HEADING.find(line)?.let { return inlinePlain(it.groupValues[2]) }
        if (RULE.matches(line)) return ""
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) return ""
        QUOTE.find(line)?.let { return inlinePlain(it.groupValues[1]) }
        BULLET.find(line)?.let { m ->
            val task = m.groupValues[2].trim()
            val mark = when { task.isEmpty() -> "• "; task.contains('x', true) -> "☑ "; else -> "☐ " }
            return m.groupValues[1] + mark + inlinePlain(m.groupValues[3])
        }
        return inlinePlain(line)
    }

    private fun inlinePlain(text: String): String = INLINE.replace(text) { m ->
        val g = m.groups
        when {
            g[1] != null -> g[1]!!.value
            g[2] != null -> inlinePlain(g[2]!!.value)
            g[3] != null -> inlinePlain(g[3]!!.value)
            g[4] != null -> inlinePlain(g[4]!!.value)
            g[5] != null -> inlinePlain(g[5]!!.value)
            g[6] != null -> g[6]!!.value
            g[8] != null -> {
                val url = g[9]?.value.orEmpty()
                val label = inlinePlain(g[8]!!.value)
                if (url.isBlank() || url == label) label else "$label ($url)"
            }
            g[10] != null -> inlinePlain(g[10]!!.value)
            else -> m.value
        }
    }
}
