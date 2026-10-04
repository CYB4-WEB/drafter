package com.daftar.app.word

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Engines used by the converter and the viewer. Run off the main thread. */
object DocxExport {

    /** Paginated PDF with real (selectable) text, headings, lists, tables, images, RTL — for every format the Word viewer opens. */
    fun toPdf(src: File, out: File): Boolean = toPdf(src, out) { _ -> true }

    /**
     * Same as [toPdf]; [onPage] is called after each finished page with the number of pages written so far and returns
     * false to cancel (the partial output is deleted).
     */
    fun toPdf(src: File, out: File, onPage: (pagesDone: Int) -> Boolean): Boolean {
        val tmp = File(out.parentFile, ".${out.name}.part")
        val pdf = PdfDocument()
        val images = DocImageSource(src.absolutePath)
        return try {
            val doc = DocLoader.load(src)
            val engine = TextEngine()
            val painter = PdfPainter(doc.page, engine, images)
            var cancelled = false
            val pg = Paginator(doc.page, engine, onPage = { page ->
                painter.draw(pdf, page)
                if (!onPage(page.index + 1)) cancelled = true
            }, cancelled = { cancelled || Thread.currentThread().isInterrupted })
            val sheet = doc.sheet
            if (sheet != null) pg.add(CsvReader.toTable(sheet, doc.page.contentW, PidGen()))
            else pg.addAll(doc.blocks)
            pg.finish()
            if (cancelled) throw Paginator.Cancelled()
            out.parentFile?.mkdirs()
            FileOutputStream(tmp).use { pdf.writeTo(it) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) {
            tmp.delete()
            false
        } finally {
            runCatching { pdf.close() }
            images.close()
        }
    }

    /**
     * Writes a valid .docx from paragraphs (used for PDF→Word, note→Word). Each string = one paragraph; "" = blank line;
     * "\u000C" = page break; '\n' inside a string = line break. Mostly-Arabic paragraphs are right-to-left (w:bidi + w:rtl runs).
     */
    fun writeDocx(paragraphs: List<String>, out: File, title: String? = null): Boolean {
        val tmp = File(out.parentFile, ".${out.name}.part")
        return try {
            out.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(tmp)).use { z ->
                fun put(name: String, body: String) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(body.toByteArray(Charsets.UTF_8))
                    z.closeEntry()
                }
                put("[Content_Types].xml", CONTENT_TYPES)
                put("_rels/.rels", ROOT_RELS)
                put("word/_rels/document.xml.rels", DOC_RELS)
                put("word/styles.xml", STYLES)
                put("word/settings.xml", SETTINGS)
                put("docProps/core.xml", coreXml(title))
                put("docProps/app.xml", APP)
                put("word/document.xml", documentXml(paragraphs, title))
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
            true
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    // ------------------------------------------------------------------ docx parts

    private fun esc(s: String): String {
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
                c < ' ' && c != '\t' -> {}
                c == '￾' || c == '￿' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun strongDir(c: Char): Int = when (Character.getDirectionality(c)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> 1
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> -1
        else -> 0
    }

    private fun mostlyRtl(s: String): Boolean {
        var r = 0; var l = 0
        for (c in s) when (strongDir(c)) { 1 -> r++; -1 -> l++ }
        return r > 0 && r >= l
    }

    /** Splits [s] into runs of one direction; neutrals join the current run. */
    private fun dirRuns(s: String, paraRtl: Boolean): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var cur: Boolean? = null
        for (c in s) {
            val d = strongDir(c)
            val rtl = when (d) { 1 -> true; -1 -> false; else -> null }
            if (rtl != null && cur != null && rtl != cur) { out.add(sb.toString() to cur); sb.setLength(0) }
            if (rtl != null) cur = rtl
            sb.append(c)
        }
        if (sb.isNotEmpty()) out.add(sb.toString() to (cur ?: paraRtl))
        return out
    }

    private fun runXml(text: String, rtl: Boolean, rPr: String = ""): String {
        val sb = StringBuilder()
        val props = (if (rtl) "<w:rtl/>" else "") + rPr
        val pr = if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else ""
        sb.append("<w:r>").append(pr)
        val lines = text.split('\n')
        lines.forEachIndexed { li, line ->
            if (li > 0) sb.append("<w:br/>")
            val parts = line.split('\t')
            parts.forEachIndexed { ti, part ->
                if (ti > 0) sb.append("<w:tab/>")
                if (part.isNotEmpty()) sb.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
            }
        }
        sb.append("</w:r>")
        return sb.toString()
    }

    private fun paragraphXml(text: String, style: String? = null): String {
        if (text == "\u000C") return "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
        val rtl = mostlyRtl(text)
        val sb = StringBuilder("<w:p>")
        val ppr = (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + (if (rtl) "<w:bidi/>" else "")
        if (ppr.isNotEmpty()) sb.append("<w:pPr>").append(ppr).append("</w:pPr>")
        // A form feed inside a paragraph is also a page break.
        val pieces = text.split('\u000C')
        pieces.forEachIndexed { k, piece ->
            if (k > 0) sb.append("<w:r><w:br w:type=\"page\"/></w:r>")
            for ((run, r) in dirRuns(piece, rtl)) sb.append(runXml(run, r))
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun documentXml(paragraphs: List<String>, title: String?): String {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" ")
        sb.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>")
        if (!title.isNullOrBlank()) sb.append(paragraphXml(title.replace('\n', ' '), "Title"))
        for (p in paragraphs) sb.append(paragraphXml(p))
        if (paragraphs.isEmpty() && title.isNullOrBlank()) sb.append("<w:p/>")
        // A4, 2.54 cm margins
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        sb.append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    private fun coreXml(title: String?): String {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            (if (!title.isNullOrBlank()) "<dc:title>${esc(title)}</dc:title>" else "") +
            "<dc:creator>Daftar</dc:creator><cp:lastModifiedBy>Daftar</cp:lastModifiedBy>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified>" +
            "</cp:coreProperties>"
    }

    private const val CONTENT_TYPES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private const val DOC_RELS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings\" Target=\"settings.xml\"/>" +
        "</Relationships>"

    private const val SETTINGS = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:settings xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:defaultTabStop w:val=\"720\"/><w:compat><w:compatSetting w:name=\"compatibilityMode\" w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat>" +
        "</w:settings>"

    private const val APP = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>Daftar</Application></Properties>"

    private const val STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Arial\"/>" +
        "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:spacing w:after=\"240\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>" +
        "<w:rPr><w:b/><w:bCs/><w:color w:val=\"1F2937\"/><w:sz w:val=\"48\"/><w:szCs w:val=\"48\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>" +
        "<w:rPr><w:b/><w:bCs/><w:color w:val=\"2F5496\"/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>" +
        "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
        "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr>" +
        "<w:rPr><w:b/><w:bCs/><w:color w:val=\"2F5496\"/><w:sz w:val=\"26\"/><w:szCs w:val=\"26\"/></w:rPr></w:style>" +
        "</w:styles>"
}

// ------------------------------------------------------------------ images for the PDF

/** Decodes pictures referenced by the model (zip entries of a .docx, or files for Markdown) with sampling. */
internal class DocImageSource(private val path: String) {
    private var zip: ZipFile? = null
    private var zipFailed = false

    @Synchronized
    fun decode(img: DocBlock.Image, targetPx: Int): Bitmap? = runCatching {
        val bytes: ByteArray = if (img.external) {
            val f = File(img.entry)
            if (!f.isFile || f.length() > 48L * 1024 * 1024) return null
            f.readBytes()
        } else {
            if (zipFailed) return null
            val z = zip ?: runCatching { ZipFile(path) }.getOrNull()?.also { zip = it } ?: run { zipFailed = true; return null }
            val e = z.getEntry(img.entry) ?: return null
            if (e.size > 48L * 1024 * 1024) return null
            z.getInputStream(e).use { it.readBytes() }
        }
        decodeSampled(bytes, targetPx)
    }.getOrNull()

    @Synchronized
    fun close() { runCatching { zip?.close() }; zip = null }

    companion object {
        fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            val target = targetPx.coerceIn(32, 4096)
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

// ------------------------------------------------------------------ PDF drawing

/** Draws laid-out pages into a PdfDocument (1 canvas unit = 1 pt; text drawn at [UNIT] px/pt with a 1/UNIT scale). */
internal class PdfPainter(private val spec: PageSpec, private val engine: TextEngine, private val images: DocImageSource) {
    private val measurer = Paginator(spec, engine)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = 0xFF4B5563.toInt() }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun draw(pdf: PdfDocument, page: LaidPage) {
        val info = PdfDocument.PageInfo.Builder(spec.w.roundToInt(), spec.h.roundToInt(), page.index + 1).create()
        val pg = pdf.startPage(info)
        val c = pg.canvas
        c.save()
        c.translate(spec.left, spec.top)
        var y = 0f
        for (s in page.slices) {
            y += s.gap
            when (s) {
                is TextSlice -> drawPara(c, s.para, s.start, s.end, s.first, 0f, spec.contentW, y)
                is ImageSlice -> drawImage(c, s.image, s.w, s.h, 0f, spec.contentW, y)
                is TableSlice -> drawRows(c, s.table, s.rows, s.rowHeights, s.colWidths, 0f, spec.contentW, y)
                is RuleSlice -> { fill.color = 0xFFB0B5BD.toInt(); c.drawRect(0f, y, spec.contentW, y + 0.75f, fill) }
            }
            y += s.height + s.after
        }
        c.restore()
        pdf.finishPage(pg)
    }

    /** Draws characters [start, end) of [p] in the column [colX, colX + colW]; returns the height used (no spacing). */
    private fun drawPara(c: Canvas, p: DocBlock.Para, start: Int, end: Int, first: Boolean, colX: Float, colW: Float, y: Float): Float {
        val tw = engine.textWidth(p, colW)
        val layout: StaticLayout = engine.layout(p, start, end, first, tw, UNIT, withColors = true)
        val padV = boxPadV(p.box)
        val h = 2 * padV + max(1, layout.lineCount) * lineHeight(p)
        val boxW = colW - p.boxStartPt - p.indEnd
        val boxL = if (!p.rtl) colX + p.boxStartPt else colX + p.indEnd
        val boxR = boxL + boxW
        when (p.box) {
            BOX_CODE -> { fill.color = 0xFFF3F4F6.toInt(); c.drawRoundRect(RectF(boxL, y, boxR, y + h), 3f, 3f, fill) }
            BOX_QUOTE -> { fill.color = 0xFFD1D5DB.toInt(); if (!p.rtl) c.drawRect(boxL, y, boxL + 2.5f, y + h, fill) else c.drawRect(boxR - 2.5f, y, boxR, y + h, fill) }
        }
        val padS = boxPadStart(p.box)
        val mW = if (p.marker != null) p.markerWidth else 0f
        val textL = if (!p.rtl) boxL + padS + mW else boxR - padS - mW - tw
        if (first && !p.marker.isNullOrEmpty()) {
            val ml = engine.markerLayout(p, UNIT, true)
            val lw = ml.width / UNIT
            val mx = if (!p.rtl) boxL + padS else boxR - padS - lw
            c.save(); c.translate(mx, y + padV); c.scale(1 / UNIT, 1 / UNIT); ml.draw(c); c.restore()
        }
        c.save()
        c.translate(textL, y + padV)
        c.scale(1 / UNIT, 1 / UNIT)
        layout.draw(c)
        c.restore()
        return h
    }

    private fun drawImage(c: Canvas, img: DocBlock.Image, w: Float, h: Float, colX: Float, colW: Float, y: Float) {
        val x = when (img.align) {
            1 -> colX + (colW - w) / 2
            2 -> if (img.rtl) colX else colX + colW - w
            else -> if (img.rtl) colX + colW - w else colX
        }
        val bmp = images.decode(img, (max(w, h) * 2.5f).roundToInt().coerceIn(64, 2400))
        if (bmp != null) {
            c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), bmpPaint)
            bmp.recycle()
        } else {
            fill.color = 0xFFE5E7EB.toInt()
            c.drawRect(x, y, x + w, y + h, fill)
        }
    }

    private fun drawRows(c: Canvas, t: DocBlock.Table, rows: IntArray, heights: FloatArray, cols: FloatArray, colX: Float, colW: Float, y: Float) {
        val tableW = cols.sum()
        val x0 = if (t.rtl) colX + colW - tableW else colX
        var ry = y
        for (k in rows.indices) {
            val row = t.rows[rows[k]]
            val rh = heights[k]
            var col = 0
            row.cells.forEachIndexed { i, cell ->
                val cw = cellWidth(row, i, cols)
                var before = 0f
                for (q in 0 until min(col, cols.size)) before += cols[q]
                val cx = if (!t.rtl) x0 + before else x0 + tableW - before - cw
                cell.fill?.let { f -> fill.color = f; c.drawRect(cx, ry, cx + cw, ry + rh, fill) }
                if (!cell.merged) drawFlow(c, cell.blocks, cx + CELL_PAD_H, cw - 2 * CELL_PAD_H, ry + CELL_PAD_V)
                if (t.borders) c.drawRect(cx, ry, cx + cw, ry + rh, stroke)
                col += cell.span
            }
            ry += rh
        }
    }

    /** Blocks stacked in a column (table cells) — same spacing as [Paginator.flowHeight]. */
    private fun drawFlow(c: Canvas, blocks: List<DocBlock>, x: Float, w: Float, y0: Float) {
        var y = y0
        blocks.forEachIndexed { i, b ->
            when (b) {
                is DocBlock.Para -> {
                    if (i > 0) y += b.before
                    y += drawPara(c, b, 0, b.text.length, true, x, w, y) + b.after
                }
                is DocBlock.Image -> {
                    val iw = min(max(b.widthPt, 4f), w)
                    val ih = b.heightPt * (iw / max(b.widthPt, 4f))
                    y += Flow.IMAGE_PAD
                    drawImage(c, b, iw, ih, x, w, y)
                    y += ih + Flow.IMAGE_PAD
                }
                is DocBlock.Table -> {
                    y += Flow.NESTED_TABLE_GAP
                    val cols = tableColumns(b, w)
                    val hs = FloatArray(b.rows.size) { measurer.rowHeight(b.rows[it], cols) }
                    drawRows(c, b, IntArray(b.rows.size) { it }, hs, cols, x, w, y)
                    y += hs.sum() + Flow.NESTED_TABLE_GAP
                }
                DocBlock.Divider, DocBlock.PageBreak -> {
                    y += Flow.RULE_PAD
                    fill.color = 0xFFB0B5BD.toInt(); c.drawRect(x, y, x + w, y + 0.75f, fill)
                    y += 1f + Flow.RULE_PAD
                }
            }
        }
    }
}
