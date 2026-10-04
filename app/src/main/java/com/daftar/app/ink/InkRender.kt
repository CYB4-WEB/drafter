package com.daftar.app.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.daftar.app.R

/**
 * Stateless drawing of ink content in page coordinates (points). Used by the live editor,
 * thumbnails and every export (PDF overlay, note → PDF), so they all look identical.
 */
object InkRender {
    // Paints are per thread: exports draw on background threads while the editor draws on the UI thread.
    private val strokeTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    private val bmpTL = ThreadLocal.withInitial { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    private val lineTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val strokePaint get() = strokeTL.get()!!
    private val bmpPaint get() = bmpTL.get()!!
    private val linePaint get() = lineTL.get()!!

    /** Font keys offered in the text tool. */
    val fonts = listOf("sans", "serif", "mono", "cairo", "amiri", "hand")
    private val typefaces = HashMap<String, Typeface>()

    fun init(ctx: Context) {
        typefaces["sans"] = Typeface.SANS_SERIF
        typefaces["serif"] = Typeface.SERIF
        typefaces["mono"] = Typeface.MONOSPACE
        fun load(key: String, res: Int) {
            runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull()?.let { typefaces[key] = it }
        }
        load("cairo", R.font.cairo)
        load("amiri", R.font.amiri)
        load("hand", R.font.caveat)
    }

    fun typeface(key: String, bold: Boolean): Typeface {
        val base = typefaces[key] ?: Typeface.SANS_SERIF
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /** Pressure → width factor. Pressure < 0 means uniform. */
    private fun wf(p: Float) = if (p < 0f) 1f else 0.35f + 1.05f * p.coerceIn(0f, 1f)

    /** Splits a stroke into constant-width smoothed path chunks (cached on the stroke). */
    fun paths(s: Stroke): List<Pair<Path, Float>> {
        s.paths?.let { return it }
        val pts = s.pts
        val n = pts.size / 3
        val out = ArrayList<Pair<Path, Float>>()
        if (n == 0) { s.paths = out; return out }
        val uniform = s.tool == Tool.HIGHLIGHTER || s.tool == Tool.SHAPE
        if (n == 1) {
            val p = Path(); p.moveTo(pts[0], pts[1]); p.lineTo(pts[0] + 0.01f, pts[1])
            out.add(p to s.width * (if (uniform) 1f else wf(pts[2]))); s.paths = out; return out
        }
        var i = 0
        while (i < n - 1) {
            val w0 = if (uniform) 1f else wf(pts[i * 3 + 2])
            val path = Path()
            path.moveTo(pts[i * 3], pts[i * 3 + 1])
            var j = i
            var sum = 0f; var cnt = 0
            while (j < n - 1) {
                val w = if (uniform) 1f else wf(pts[(j + 1) * 3 + 2])
                if (!uniform && cnt > 0 && kotlin.math.abs(w - w0) > 0.12f) break
                val x0 = pts[j * 3]; val y0 = pts[j * 3 + 1]
                val x1 = pts[(j + 1) * 3]; val y1 = pts[(j + 1) * 3 + 1]
                if (j + 1 < n - 1) path.quadTo(x0, y0, (x0 + x1) / 2f, (y0 + y1) / 2f) else path.quadTo(x0, y0, x1, y1)
                sum += w; cnt++; j++
            }
            out.add(path to s.width * (if (cnt > 0) sum / cnt else w0))
            i = j
        }
        s.paths = out
        return out
    }

    fun drawStroke(c: Canvas, s: Stroke, alpha: Float = 1f) {
        val p = strokePaint
        p.color = s.color
        p.alpha = Color.alpha(s.color)
        if (alpha < 1f) p.alpha = (Color.alpha(s.color) * alpha).toInt()
        p.strokeCap = if (s.tool == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        for ((path, w) in paths(s)) { p.strokeWidth = w; c.drawPath(path, p) }
    }

    fun layout(t: TextItem): StaticLayout {
        t.layout?.let { return it }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; textSize = t.size; typeface = typeface(t.font, t.bold)
        }
        val l = StaticLayout.Builder.obtain(t.text, 0, t.text.length, tp, t.w.toInt().coerceAtLeast(20))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        t.layout = l
        return l
    }

    fun drawText(c: Canvas, t: TextItem) {
        c.save(); c.translate(t.x, t.y); layout(t).draw(c); c.restore()
    }

    fun drawImage(c: Canvas, im: ImageItem) {
        im.bitmap()?.let { c.drawBitmap(it, null, RectF(im.x, im.y, im.x + im.w, im.y + im.h), bmpPaint) }
    }

    /** Paper pattern for notebooks (page coordinates, no background fill). */
    fun drawPaper(c: Canvas, page: InkPage, dark: Boolean = false) {
        val lp = linePaint
        when (page.paper) {
            "lined" -> {
                lp.color = if (dark) 0x33FFFFFF else 0xFFC9D8EA.toInt(); lp.strokeWidth = 0.6f
                var y = 72f
                while (y < page.h - 20) { c.drawLine(24f, y, page.w - 24f, y, lp); y += 26f }
                lp.color = 0x55E57373; c.drawLine(64f, 0f, 64f, page.h, lp)
            }
            "grid" -> {
                lp.color = if (dark) 0x26FFFFFF else 0xFFDCE3EC.toInt(); lp.strokeWidth = 0.5f
                var x = 18f; while (x < page.w) { c.drawLine(x, 0f, x, page.h, lp); x += 18f }
                var y = 18f; while (y < page.h) { c.drawLine(0f, y, page.w, y, lp); y += 18f }
            }
            "dots" -> {
                lp.color = if (dark) 0x40FFFFFF else 0xFFB9C2CC.toInt()
                var y = 18f
                while (y < page.h) { var x = 18f; while (x < page.w) { c.drawCircle(x, y, 0.9f, lp); x += 18f }; y += 18f }
            }
            "cornell" -> {
                lp.color = if (dark) 0x33FFFFFF else 0xFFC9D8EA.toInt(); lp.strokeWidth = 0.6f
                var y = 72f
                while (y < page.h - 150) { c.drawLine(24f, y, page.w - 24f, y, lp); y += 26f }
                lp.color = 0xFF9DB3CC.toInt(); lp.strokeWidth = 1f
                c.drawLine(150f, 40f, 150f, page.h - 150f, lp)
                c.drawLine(24f, page.h - 150f, page.w - 24f, page.h - 150f, lp)
            }
        }
    }

    /** Everything a user put on the page (images, ink, text) — no paper/background. */
    fun drawPageContent(c: Canvas, page: InkPage) {
        for (im in page.images) drawImage(c, im)
        for (s in page.strokes) drawStroke(c, s)
        for (t in page.texts) drawText(c, t)
    }
}
