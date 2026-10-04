package com.daftar.app.onenote

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.os.Build

// Page layout with android.graphics (units = pt), shared by PDF export and "Import as Daftar note".
// The on-screen canvas (OneCanvas) lays out with Compose so its text stays selectable; both follow the same rules.

internal const val DEFAULT_PT = 11f
internal const val INDENT_PT = 27f
internal const val LABEL_PT = 18f
internal const val AUTO_OUTLINE_PT = 480f
internal const val INK_COLOR_DEFAULT = 0xFF1F2937.toInt()
internal const val LINK_COLOR = 0xFF2563EB.toInt()

/** Default size / colour / style for a OneNote paragraph style id. */
internal class ParaLook(val size: Float, val color: Int?, val bold: Boolean, val italic: Boolean, val mono: Boolean)

internal fun paraLook(style: String?): ParaLook = when (style?.lowercase()) {
    "pagetitle" -> ParaLook(20f, null, false, false, false)
    "h1" -> ParaLook(16f, 0xFF1F3763.toInt(), false, false, false)
    "h2" -> ParaLook(14f, 0xFF2E75B5.toInt(), false, false, false)
    "h3" -> ParaLook(12f, 0xFF1F3763.toInt(), false, false, false)
    "h4" -> ParaLook(12f, 0xFF2E75B5.toInt(), false, true, false)
    "h5" -> ParaLook(11f, 0xFF2E75B5.toInt(), false, false, false)
    "h6" -> ParaLook(11f, 0xFF2E75B5.toInt(), false, true, false)
    "code" -> ParaLook(10f, null, false, false, true)
    "blockquote", "quote", "cite" -> ParaLook(11f, 0xFF595959.toInt(), false, true, false)
    else -> ParaLook(DEFAULT_PT, null, false, false, false)
}

internal sealed class Laid
internal class LaidText(val x: Float, val y: Float, val w: Float, val layout: StaticLayout, val para: OnePara?, val label: StaticLayout?, val labelX: Float) : Laid()
internal class LaidImage(val rect: RectF, val image: OneImage) : Laid()
internal class LaidInk(val ox: Float, val oy: Float, val ink: OneInk) : Laid()
internal class LaidLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Laid()
internal class LaidFile(val rect: RectF, val name: String) : Laid()

internal class LaidPage(val page: OnePage, val items: List<Laid>, val width: Float, val height: Float)

internal object OneLayout {

    fun spannable(p: OnePara, look: ParaLook = paraLook(p.style)): CharSequence {
        val sb = SpannableStringBuilder()
        for (r in p.runs) {
            val s = sb.length
            sb.append(r.text)
            val e = sb.length
            if (s == e) continue
            val bold = r.bold || look.bold; val italic = r.italic || look.italic
            if (bold || italic) sb.setSpan(StyleSpan(if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else Typeface.ITALIC), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.underline) sb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.strike) sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.size > 0f) sb.setSpan(AbsoluteSizeSpan(r.size.toInt().coerceAtLeast(1), false), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val col = if (r.link != null) LINK_COLOR else r.color ?: look.color
            if (col != null) sb.setSpan(ForegroundColorSpan(col), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            r.highlight?.let { sb.setSpan(BackgroundColorSpan(it), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (r.superscript) { sb.setSpan(SuperscriptSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(RelativeSizeSpan(0.7f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (r.subscript) { sb.setSpan(SubscriptSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(RelativeSizeSpan(0.7f), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (look.mono) sb.setSpan(TypefaceSpan("monospace"), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (sb.isEmpty()) sb.append(" ")
        return sb
    }

    fun staticLayout(text: CharSequence, size: Float, width: Float, align: Int, rtl: Boolean, color: Int = INK_COLOR_DEFAULT): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color }
        val w = width.toInt().coerceAtLeast(8)
        val al = when (align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val dir = if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR
        return if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setAlignment(al).setTextDirection(dir).setIncludePad(false).build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, w, al, 1f, 0f, false)
        }
    }

    /** Lays out [page]. [imageSize] returns a picture's pixel size (or null) for images without stored dimensions. */
    fun layout(page: OnePage, imageSize: (Blob) -> IntArray?): LaidPage {
        val out = ArrayList<Laid>()
        var maxX = 0f; var maxY = 0f
        fun grow(x: Float, y: Float) { if (x > maxX) maxX = x; if (y > maxY) maxY = y }

        // title + date line
        if (page.title.isNotBlank()) {
            val tl = staticLayout(page.title, 20f, 560f, 0, isRtl(page.title))
            out.add(LaidText(page.titleX, page.titleY, 560f, tl, null, null, 0f))
            val lineY = page.titleY + tl.height + 4f
            out.add(LaidLine(page.titleX, lineY, page.titleX + 300f, lineY))
            grow(page.titleX + tl.width, lineY)
        }

        fun imageBox(img: OneImage, maxW: Float): Pair<Float, Float> {
            var w = img.widthPt; var h = img.heightPt
            if (w <= 0f || h <= 0f) {
                val s = img.data?.let(imageSize)
                if (s != null) { w = s[0] * 0.75f; h = s[1] * 0.75f } else { w = 160f; h = 40f }
            }
            if (maxW > 0f && w > maxW) { h *= maxW / w; w = maxW }
            return w to h
        }

        fun blocks(list: List<OneBlock>, x: Float, y0: Float, width: Float): Float {
            var y = y0
            for (b in list) {
                val ix = x + b.indent.coerceIn(0, 12) * INDENT_PT
                val avail = (width - (ix - x)).coerceAtLeast(40f)
                when (b) {
                    is OnePara -> {
                        val look = paraLook(b.style)
                        val labelW = if (b.list != null) LABEL_PT + (b.list.length - 2).coerceAtLeast(0) * 5f else 0f
                        val tl = staticLayout(spannable(b, look), look.size, avail - labelW, b.align, b.rtl)
                        val label = b.list?.let { staticLayout(it, b.runs.firstOrNull()?.size?.takeIf { s -> s > 0f } ?: look.size, labelW, 0, b.rtl) }
                        val tx = if (b.rtl) ix else ix + labelW
                        val lx = if (b.rtl) ix + avail - labelW else ix
                        out.add(LaidText(tx, y, avail - labelW, tl, b, label, lx))
                        grow(ix + avail, y + tl.height)
                        y += tl.height + 2f
                    }
                    is OneTable -> {
                        val cols = b.rows.maxOfOrNull { it.size } ?: 0
                        if (cols == 0) continue
                        val given = b.columnWidths.filter { it > 0f }
                        val widths = if (given.size >= cols) b.columnWidths.take(cols).map { it.coerceAtLeast(24f) }
                            else List(cols) { (avail / cols).coerceAtLeast(40f) }
                        val top = y
                        for (row in b.rows) {
                            var cx = ix
                            var rowH = 14f
                            for ((ci, cell) in row.withIndex()) {
                                val cw = widths.getOrElse(ci) { widths.last() }
                                val h = blocks(cell, cx + 4f, y + 3f, cw - 8f) - (y + 3f)
                                if (h + 6f > rowH) rowH = h + 6f
                                cx += cw
                            }
                            if (b.borders) out.add(LaidLine(ix, y, ix + widths.sum(), y))
                            y += rowH
                        }
                        if (b.borders) {
                            out.add(LaidLine(ix, y, ix + widths.sum(), y))
                            var cx = ix
                            out.add(LaidLine(cx, top, cx, y))
                            for (w in widths) { cx += w; out.add(LaidLine(cx, top, cx, y)) }
                        }
                        grow(ix + widths.sum(), y)
                        y += 4f
                    }
                    is OneImageBlock -> {
                        val (w, h) = imageBox(b.image, avail)
                        out.add(LaidImage(RectF(ix, y, ix + w, y + h), b.image))
                        grow(ix + w, y + h)
                        y += h + 4f
                    }
                    is OneInkBlock -> {
                        val bb = b.ink.bounds
                        out.add(LaidInk(ix - bb[0], y - bb[1], b.ink))
                        grow(ix + bb[2] - bb[0], y + bb[3] - bb[1])
                        y += (bb[3] - bb[1]) + 4f
                    }
                    is OneFileBlock -> {
                        val r = RectF(ix, y, ix + 200f, y + 24f)
                        out.add(LaidFile(r, b.file.name))
                        grow(r.right, r.bottom)
                        y += 28f
                    }
                }
            }
            return y
        }

        for (item in page.items) {
            when (item) {
                is OneOutline -> {
                    val w = if (item.width > 20f) item.width else AUTO_OUTLINE_PT
                    blocks(item.blocks, item.x, item.y, w)
                }
                is OneImageItem -> {
                    val (w, h) = imageBox(item.image, 0f)
                    out.add(LaidImage(RectF(item.x, item.y, item.x + w, item.y + h), item.image))
                    grow(item.x + w, item.y + h)
                }
                is OneInkItem -> {
                    out.add(LaidInk(item.x, item.y, item.ink))
                    val bb = item.ink.bounds
                    grow(item.x + bb[2], item.y + bb[3])
                }
                is OneFileItem -> {
                    val r = RectF(item.x, item.y, item.x + 200f, item.y + 24f)
                    out.add(LaidFile(r, item.file.name))
                    grow(r.right, r.bottom)
                }
            }
        }
        val width = maxOf(612f, page.widthPt.takeIf { it in 200f..3000f } ?: 0f, maxX + 36f)
        val height = maxOf(400f, maxY + 48f)
        return LaidPage(page, out, width, height)
    }

    fun isRtl(s: String): Boolean {
        for (ch in s) {
            val d = Character.getDirectionality(ch)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false
        }
        return false
    }

    /** Smooth path through ink points (pt), offset by ([ox], [oy]). */
    fun inkPath(s: OneStroke, ox: Float, oy: Float): Path {
        val p = Path()
        val pts = s.pts
        if (pts.size < 2) return p
        p.moveTo(ox + pts[0], oy + pts[1])
        if (pts.size == 2) { p.lineTo(ox + pts[0] + 0.01f, oy + pts[1]); return p }
        var i = 2
        while (i + 3 < pts.size) {
            val mx = (pts[i] + pts[i + 2]) / 2f; val my = (pts[i + 1] + pts[i + 3]) / 2f
            p.quadTo(ox + pts[i], oy + pts[i + 1], ox + mx, oy + my)
            i += 2
        }
        p.lineTo(ox + pts[pts.size - 2], oy + pts[pts.size - 1])
        return p
    }

    /** Draws a laid page (white paper assumed) onto [c] in pt units. [bitmap] decodes a picture for drawing. */
    fun draw(c: Canvas, page: LaidPage, bitmap: (Blob) -> android.graphics.Bitmap?, fileLabel: String) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9CA3AF.toInt(); strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF3F4F6.toInt() }
        val boxLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD1D5DB.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.75f }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF374151.toInt(); textSize = 9f }
        for (it in page.items) when (it) {
            is LaidText -> {
                c.save(); c.translate(it.x, it.y); it.layout.draw(c); c.restore()
                it.label?.let { l -> c.save(); c.translate(it.labelX, it.y); l.draw(c); c.restore() }
            }
            is LaidLine -> c.drawLine(it.x1, it.y1, it.x2, it.y2, line)
            is LaidImage -> {
                val bmp = it.image.data?.let(bitmap)
                if (bmp != null) c.drawBitmap(bmp, null, it.rect, Paint(Paint.FILTER_BITMAP_FLAG))
                else {
                    c.drawRect(it.rect, box); c.drawRect(it.rect, boxLine)
                    val label = it.image.alt ?: it.image.name ?: ""
                    c.save(); c.clipRect(it.rect); c.drawText(label.take(80), it.rect.left + 4f, it.rect.top + 12f, small); c.restore()
                }
            }
            is LaidInk -> for (s in it.ink.strokes) {
                inkPaint.color = s.color
                inkPaint.strokeWidth = s.width
                c.drawPath(inkPath(s, it.ox, it.oy), inkPaint)
            }
            is LaidFile -> {
                c.drawRoundRect(it.rect, 6f, 6f, box); c.drawRoundRect(it.rect, 6f, 6f, boxLine)
                c.drawText("$fileLabel  ${it.name}".take(48), it.rect.left + 8f, it.rect.centerY() + 3f, small)
            }
        }
    }

    const val WHITE = Color.WHITE
}
