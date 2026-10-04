package com.daftar.app.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.daftar.app.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of one stroke for its tool and pen style. Built incrementally while the user writes ([add] per point, drawn live
 * with [InkRender.drawLive]) or in one pass for saved strokes ([InkRender.geom]) — both paths run the same code, so the
 * stroke never changes look when it is committed, and thumbnails / exports match the editor exactly.
 *
 * - Ballpoint, marker, highlighter, shapes: smoothed constant-width path chunks (stroked).
 * - Fountain, pencil, brush: one filled variable-width outline made of consistently oriented quads (non-zero winding),
 *   so overlaps never double-darken translucent pencil and no seams appear between width changes.
 * - Tape: a rounded strip between its two points.
 */
class StrokeGeom internal constructor(val tool: Int, val style: Int, val width: Float) {
    internal val kind: Int = when {
        tool == Tool.TAPE -> KIND_TAPE
        tool == Tool.PEN && (style == PenStyle.FOUNTAIN || style == PenStyle.BRUSH || style == PenStyle.PENCIL) -> KIND_OUTLINE
        else -> KIND_CHUNKS
    }
    internal val fill: Path? = if (kind != KIND_CHUNKS) Path().apply { fillType = Path.FillType.WINDING } else null
    internal var chunks: Array<Path?> = arrayOfNulls(if (kind == KIND_CHUNKS) 4 else 0)
    internal var chunkW = FloatArray(chunks.size)
    internal var nChunks = 0
    internal var finished = false

    /** Brush: total stroke length, known only for saved strokes (end taper). */
    internal var totalLen = -1f

    // ---- builder state ----
    internal var n = 0
    private var lx = 0f; private var ly = 0f          // last accepted point
    private var mx = 0f; private var my = 0f          // chunk mode: end of the drawn path (last midpoint)
    private var curW = 0f
    private var segInChunk = 0
    private var ws = 0f                               // smoothed width
    private var arc = 0f
    // outline mode: A = point k-2 (normal known), B = point k-1 (normal pending)
    private var ax = 0f; private var ay = 0f; private var aw = 0f; private var anx = 0f; private var any = 0f
    internal var bx = 0f; internal var by = 0f; internal var bw = 0f
    private var dux = 0f; private var duy = 0f       // unit direction A→B

    private val uniform get() = tool == Tool.HIGHLIGHTER || tool == Tool.SHAPE || style == PenStyle.MARKER

    private fun pp(p: Float) = if (p < 0f) 0.6f else p.coerceIn(0f, 1f)

    /** Width (page points) at a point for this tool/style. [ux],[uy] = unit direction of travel (0,0 if unknown). */
    private fun widthAt(p: Float, ux: Float, uy: Float): Float {
        if (tool != Tool.PEN) return width
        return when (style) {
            PenStyle.FOUNTAIN -> {
                // broad nib held at 45° ("/"): strokes along the nib are hairlines, across it are full width
                val sinv = if (ux == 0f && uy == 0f) 0.6f else abs((ux * NIB_UY - uy * NIB_UX))
                width * 1.3f * (0.22f + 0.95f * sinv) * (if (p < 0f) 1f else 0.6f + 0.6f * pp(p))
            }
            PenStyle.BRUSH -> {
                var f = if (p < 0f) 1f else 0.12f + 1.5f * pp(p).pow(1.3f)
                val taper = max(6f, width * 2.5f)
                f *= 0.18f + 0.82f * min(1f, arc / taper).pow(0.6f)
                if (totalLen > 0f) f *= 0.18f + 0.82f * min(1f, max(0f, totalLen - arc) / taper).pow(0.6f)
                width * f
            }
            PenStyle.PENCIL -> width * (if (p < 0f) 1f else 0.75f + 0.5f * pp(p))
            PenStyle.MARKER -> width
            else -> width * (if (p < 0f) 1f else 0.85f + 0.35f * pp(p))   // ballpoint: light pressure response
        }
    }

    fun add(x: Float, y: Float, p: Float) {
        if (kind == KIND_TAPE) { addTapePoint(x, y); return }
        if (n > 0) {
            val dx = x - lx; val dy = y - ly
            if (dx * dx + dy * dy < 1e-6f) return
        }
        if (kind == KIND_CHUNKS) addChunk(x, y, p) else addOutline(x, y, p)
    }

    private fun addChunk(x: Float, y: Float, p: Float) {
        if (n == 0) {
            curW = widthAt(p, 0f, 0f)
            newChunk(x, y, curW)
            mx = x; my = y; lx = x; ly = y; n = 1
            return
        }
        val w = widthAt(p, 0f, 0f)
        if (!uniform && segInChunk > 0 && abs(w - curW) > width * 0.1f) {
            curW = w
            newChunk(mx, my, w)
        }
        val nmx = (lx + x) / 2f; val nmy = (ly + y) / 2f
        chunks[nChunks - 1]!!.quadTo(lx, ly, nmx, nmy)
        segInChunk++
        mx = nmx; my = nmy; lx = x; ly = y; n++
    }

    private fun newChunk(x: Float, y: Float, w: Float) {
        if (nChunks == chunks.size) { chunks = chunks.copyOf(chunks.size * 2); chunkW = chunkW.copyOf(chunks.size) }
        val path = Path(); path.moveTo(x, y)
        chunks[nChunks] = path; chunkW[nChunks] = w; nChunks++
        segInChunk = 0
    }

    private fun addOutline(x: Float, y: Float, p: Float) {
        if (n == 0) {
            ws = widthAt(p, 0f, 0f)
            bx = x; by = y; bw = ws; lx = x; ly = y; n = 1
            return
        }
        val dx = x - bx; val dy = y - by
        val len = sqrt(dx * dx + dy * dy)
        val ux = dx / len; val uy = dy / len
        arc += len
        val raw = widthAt(p, ux, uy)
        ws += (raw - ws) * (if (style == PenStyle.BRUSH) 0.5f else 0.35f)
        val f = fill!!
        if (n == 1) {
            // start point: normal from the first segment, round start cap
            ax = bx; ay = by; aw = bw; anx = -uy; any = ux
            f.addCircle(ax, ay, aw / 2f, Path.Direction.CW)
        } else {
            var nx = -(duy + uy); var ny = dux + ux
            val nl = sqrt(nx * nx + ny * ny)
            if (nl < 1e-3f) { nx = -duy; ny = dux } else { nx /= nl; ny /= nl }
            quad(ax, ay, aw, anx, any, bx, by, bw, nx, ny)
            if (dux * ux + duy * uy < 0.5f) f.addCircle(bx, by, bw / 2f, Path.Direction.CW)   // sharp turn: round joint
            ax = bx; ay = by; aw = bw; anx = nx; any = ny
        }
        dux = ux; duy = uy
        bx = x; by = y; bw = ws; lx = x; ly = y; n++
    }

    /** Quad from A to B, always added clockwise so every piece has winding +1 (no holes where pieces overlap). */
    private fun quad(ax: Float, ay: Float, aw: Float, anx: Float, any: Float, bx: Float, by: Float, bw: Float, bnx: Float, bny: Float) {
        val ha = aw / 2f; val hb = bw / 2f
        val x1 = ax + anx * ha; val y1 = ay + any * ha       // A left
        val x2 = bx + bnx * hb; val y2 = by + bny * hb       // B left
        val x3 = bx - bnx * hb; val y3 = by - bny * hb       // B right
        val x4 = ax - anx * ha; val y4 = ay - any * ha       // A right
        val area = (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y4 - x4 * y3) + (x4 * y1 - x1 * y4)
        val f = fill!!
        f.moveTo(x1, y1)
        if (area >= 0f) { f.lineTo(x2, y2); f.lineTo(x3, y3); f.lineTo(x4, y4) } else { f.lineTo(x4, y4); f.lineTo(x3, y3); f.lineTo(x2, y2) }
        f.close()
    }

    private var tx0 = 0f; private var ty0 = 0f
    private fun addTapePoint(x: Float, y: Float) {
        if (n == 0) { tx0 = x; ty0 = y; n = 1; lx = x; ly = y; return }
        lx = x; ly = y; n = 2
        rebuildTape()
    }

    /** Tape: rounded strip from the first to the latest point (live: follows the finger in a straight line). */
    private fun rebuildTape() {
        val f = fill!!
        f.rewind()
        val dx = lx - tx0; val dy = ly - ty0
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.5f) return
        val r = TMP_RECT.get()!!
        r.set(-width * 0.15f, -width / 2f, len + width * 0.15f, width / 2f)
        val corner = min(width * 0.18f, 6f)
        f.addRoundRect(r, corner, corner, Path.Direction.CW)
        val m = TMP_MATRIX.get()!!
        m.reset()
        m.setRotate(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        m.postTranslate(tx0, ty0)
        f.transform(m)
    }

    fun finish() {
        if (finished) return
        finished = true
        when (kind) {
            KIND_CHUNKS -> if (nChunks > 0) {
                val last = chunks[nChunks - 1]!!
                if (n == 1) last.lineTo(lx + 0.01f, ly) else last.lineTo(lx, ly)
            }
            KIND_OUTLINE -> {
                val f = fill!!
                if (n == 1) f.addCircle(bx, by, bw / 2f, Path.Direction.CW)
                else if (n >= 2) {
                    quad(ax, ay, aw, anx, any, bx, by, bw, -duy, dux)
                    f.addCircle(bx, by, bw / 2f, Path.Direction.CW)
                }
            }
        }
    }

    /** Copy moved by (dx, dy); only for finished geometry. */
    internal fun offsetCopy(dx: Float, dy: Float): StrokeGeom? {
        if (!finished) return null
        val g = StrokeGeom(tool, style, width)
        g.finished = true; g.n = n; g.totalLen = totalLen
        if (fill != null) { g.fill!!.set(fill); g.fill.offset(dx, dy) }
        if (kind == KIND_CHUNKS) {
            g.chunks = arrayOfNulls(max(1, nChunks)); g.chunkW = FloatArray(g.chunks.size); g.nChunks = nChunks
            for (i in 0 until nChunks) { g.chunks[i] = Path(chunks[i]!!).apply { offset(dx, dy) }; g.chunkW[i] = chunkW[i] }
        }
        return g
    }

    // live tail (chunk mode): from the last midpoint to the last point
    internal val tailX0 get() = mx
    internal val tailY0 get() = my
    internal val lastX get() = lx
    internal val lastY get() = ly
    internal val tailW get() = curW
    internal val pendX get() = ax
    internal val pendY get() = ay
    internal val pendW get() = (aw + bw) / 2f

    companion object {
        internal const val KIND_CHUNKS = 0
        internal const val KIND_OUTLINE = 1
        internal const val KIND_TAPE = 2
        // nib direction (unit vector of "/": up-right in y-down screen space)
        private const val NIB_UX = 0.70710677f
        private const val NIB_UY = -0.70710677f
        private val TMP_RECT = ThreadLocal.withInitial { RectF() }
        private val TMP_MATRIX = ThreadLocal.withInitial { Matrix() }
    }
}

/**
 * Stateless drawing of ink content in page coordinates (points). Used by the live editor,
 * thumbnails and every export (PDF overlay, note → PDF), so they all look identical.
 */
object InkRender {
    // Paints are per thread: exports draw on background threads while the editor draws on the UI thread.
    private val strokeTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    }
    private val fillTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val pencilTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.FILL
            shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(0.5f, 0.5f) })
            }
        }
    }
    private val pencilLineTL = ThreadLocal.withInitial {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
            shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(0.5f, 0.5f) })
            }
        }
    }
    private val filterTL = ThreadLocal.withInitial { HashMap<Int, PorterDuffColorFilter>() }
    private val bmpTL = ThreadLocal.withInitial { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    private val lineTL = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val textTL = ThreadLocal.withInitial { TextPaint(Paint.ANTI_ALIAS_FLAG) }
    private val rectTL = ThreadLocal.withInitial { RectF() }
    private val rect2TL = ThreadLocal.withInitial { RectF() }
    private val pathTL = ThreadLocal.withInitial { Path() }
    private val dashTL = ThreadLocal.withInitial { DashPathEffect(floatArrayOf(4f, 3f), 0f) }
    private val dotsTL = ThreadLocal.withInitial { FloatArray(2048) }
    private val strokePaint get() = strokeTL.get()!!
    private val bmpPaint get() = bmpTL.get()!!
    private val linePaint get() = lineTL.get()!!

    /** Tiled grain for the pencil (white with varying alpha; tinted by a colour filter). Deterministic. */
    private val noise: Bitmap by lazy {
        val s = 64
        val px = IntArray(s * s)
        var seed = 0x2545F491
        for (i in px.indices) {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            val v = (seed ushr 1) % 1000 / 1000f
            val a = (95 + 160 * v * v).toInt().coerceIn(0, 255)
            px[i] = (a shl 24) or 0xFFFFFF
        }
        Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
    }

    /** Font keys offered in the text tool. */
    val fonts = listOf("sans", "serif", "mono", "cairo", "amiri", "tehreer", "hand")
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
        load("tehreer", R.font.tehreer)
        load("hand", R.font.caveat)
    }

    fun typeface(key: String, bold: Boolean): Typeface {
        val base = typefaces[key] ?: Typeface.SANS_SERIF
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    // =====================================================================================
    // Strokes
    // =====================================================================================

    /** Cached geometry of a saved stroke (built on first use). */
    fun geom(s: Stroke): StrokeGeom {
        s.geom?.let { if (it.finished) return it }
        val g = StrokeGeom(s.tool, s.style, s.width)
        val pts = s.pts
        val n = pts.size / 3
        if (g.kind == StrokeGeom.KIND_OUTLINE && s.style == PenStyle.BRUSH && n > 1) {
            var len = 0f
            for (i in 1 until n) {
                val dx = pts[i * 3] - pts[(i - 1) * 3]; val dy = pts[i * 3 + 1] - pts[(i - 1) * 3 + 1]
                len += sqrt(dx * dx + dy * dy)
            }
            g.totalLen = len
        }
        for (i in 0 until n) g.add(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2])
        g.finish()
        s.geom = g
        return g
    }

    /** Starts the live geometry for a stroke being drawn; feed points with [StrokeGeom.add]. */
    fun liveGeom(tool: Int, style: Int, width: Float) = StrokeGeom(tool, style, width)

    private fun pencilColor(color: Int): PorterDuffColorFilter {
        val m = filterTL.get()!!
        val key = color or 0xFF000000.toInt()
        m[key]?.let { return it }
        if (m.size > 32) m.clear()
        return PorterDuffColorFilter(key, PorterDuff.Mode.SRC_IN).also { m[key] = it }
    }

    private fun alphaOf(s: Int, color: Int, alpha: Float): Int {
        val base = if (s == PenStyle.MARKER) 255 else Color.alpha(color)
        val a = if (s == PenStyle.PENCIL) base * 0.85f else base.toFloat()
        return (a * alpha).toInt().coerceIn(0, 255)
    }

    /** Draws a saved stroke (tapes are drawn hidden — see [drawTape] for the editor's reveal state). */
    fun drawStroke(c: Canvas, s: Stroke, alpha: Float = 1f) {
        if (s.isTape) { drawTape(c, s, false, alpha); return }
        drawGeom(c, geom(s), s.tool, s.style, s.color, alpha)
    }

    private fun drawGeom(c: Canvas, g: StrokeGeom, tool: Int, style: Int, color: Int, alpha: Float) {
        if (g.kind == StrokeGeom.KIND_OUTLINE) {
            val p = if (style == PenStyle.PENCIL) pencilTL.get()!!.also { it.colorFilter = pencilColor(color) }
            else fillTL.get()!!.also { it.color = color }
            p.alpha = alphaOf(style, color, alpha)
            c.drawPath(g.fill!!, p)
            return
        }
        val p = strokePaint
        p.color = if (tool == Tool.PEN && style == PenStyle.MARKER) color or 0xFF000000.toInt() else color
        p.alpha = alphaOf(if (tool == Tool.PEN) style else -1, color, alpha)
        p.strokeCap = when {
            tool == Tool.HIGHLIGHTER -> Paint.Cap.SQUARE
            tool == Tool.PEN && style == PenStyle.MARKER -> Paint.Cap.BUTT
            else -> Paint.Cap.ROUND
        }
        for (i in 0 until g.nChunks) { p.strokeWidth = g.chunkW[i]; c.drawPath(g.chunks[i]!!, p) }
        p.strokeCap = Paint.Cap.ROUND
    }

    /** Live (unfinished) geometry, including the not-yet-smoothed tail so the ink follows the pen tip exactly. */
    fun drawLive(c: Canvas, g: StrokeGeom, color: Int) {
        val tool = g.tool; val style = g.style
        if (g.kind == StrokeGeom.KIND_TAPE) { drawTapeGeom(c, g, color, false, 1f); return }
        drawGeom(c, g, tool, style, color, 1f)
        if (g.finished || g.n == 0) return
        if (g.kind == StrokeGeom.KIND_CHUNKS) {
            val p = strokePaint
            p.color = if (tool == Tool.PEN && style == PenStyle.MARKER) color or 0xFF000000.toInt() else color
            p.alpha = alphaOf(if (tool == Tool.PEN) style else -1, color, 1f)
            p.strokeCap = if (tool == Tool.HIGHLIGHTER) Paint.Cap.SQUARE else if (style == PenStyle.MARKER && tool == Tool.PEN) Paint.Cap.BUTT else Paint.Cap.ROUND
            p.strokeWidth = g.tailW
            if (g.n == 1) c.drawPoint(g.lastX, g.lastY, p) else c.drawLine(g.tailX0, g.tailY0, g.lastX, g.lastY, p)
            p.strokeCap = Paint.Cap.ROUND
        } else {
            val p = if (style == PenStyle.PENCIL) pencilLineTL.get()!!.also { it.colorFilter = pencilColor(color) }
            else strokePaint.also { it.color = color; it.strokeCap = Paint.Cap.ROUND }
            p.alpha = alphaOf(style, color, 1f)
            if (g.n == 1) { p.strokeWidth = g.bw; c.drawPoint(g.bx, g.by, p) }
            else { p.strokeWidth = g.pendW; c.drawLine(g.pendX, g.pendY, g.bx, g.by, p) }
        }
    }

    // =====================================================================================
    // Tape (self-quiz)
    // =====================================================================================

    /** Pastel tape colours offered by the tape tool. */
    val tapeColors = listOf(0xFFF6D77A, 0xFFA9D6F2, 0xFFF4B6C8, 0xFFB7E3C6, 0xFFD5C7F4, 0xFFF8C59A).map { it.toInt() }

    fun drawTape(c: Canvas, s: Stroke, revealed: Boolean, alpha: Float = 1f) = drawTapeGeom(c, geom(s), s.color, revealed, alpha)

    private fun drawTapeGeom(c: Canvas, g: StrokeGeom, color: Int, revealed: Boolean, alpha: Float) {
        val path = g.fill ?: return
        val f = fillTL.get()!!
        f.color = color or 0xFF000000.toInt()
        f.alpha = ((if (revealed) 40 else 255) * alpha).toInt()
        c.drawPath(path, f)
        val p = strokePaint
        // border: the tape colour darkened by ~22%
        val r = (Color.red(color) * 0.78f).toInt(); val gg = (Color.green(color) * 0.78f).toInt(); val b = (Color.blue(color) * 0.78f).toInt()
        p.color = Color.rgb(r, gg, b)
        p.alpha = ((if (revealed) 200 else 140) * alpha).toInt()
        p.strokeWidth = if (revealed) 1.1f else 0.7f
        if (revealed) p.pathEffect = dashTL.get()
        c.drawPath(path, p)
        p.pathEffect = null
    }

    // =====================================================================================
    // Text, images, links
    // =====================================================================================

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
        val b = im.bitmap() ?: return
        val r = rectTL.get()!!
        r.set(im.x, im.y, im.x + im.w, im.y + im.h)
        c.drawBitmap(b, null, r, bmpPaint)
    }

    /** Badge colour and short label for a linked library file (file-type colours from the design sheet). */
    fun fileBadge(path: String): Pair<Int, String> {
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> 0xFFEF4444.toInt() to "PDF"
            "ppt", "pptx" -> 0xFFF97316.toInt() to "PPT"
            "doc", "docx" -> 0xFF3B82F6.toInt() to "DOC"
            "txt", "md", "markdown", "rtf", "csv", "tsv", "log" -> 0xFF10B981.toInt() to "TXT"
            "note" -> 0xFF6366F1.toInt() to "NOTE"
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> 0xFF3F8F5B.toInt() to "IMG"
            "m4a", "mp3", "wav", "aac", "ogg" -> 0xFF8B5CF6.toInt() to "AUD"
            "" -> 0xFF5E6B78.toInt() to "FILE"
            else -> 0xFF5E6B78.toInt() to ext.take(4).uppercase()
        }
    }

    /** Natural chip width for a label at the default height. */
    fun linkWidth(label: String): Float {
        val tp = textTL.get()!!
        tp.textSize = 12f; tp.typeface = Typeface.DEFAULT
        return (4f + 22f + 7f + tp.measureText(label) + 10f).coerceIn(96f, 340f)
    }

    /** A link chip: rounded surface, type icon (video / globe / coloured file badge) and the ellipsized label. */
    fun drawLink(c: Canvas, l: LinkItem) {
        val k = l.h / LinkItem.LINK_H
        val r = rectTL.get()!!
        r.set(l.x, l.y, l.x + l.w, l.y + l.h)
        val f = fillTL.get()!!
        val p = strokePaint
        f.color = 0xFFFFFFFF.toInt()
        c.drawRoundRect(r, 9f * k, 9f * k, f)
        p.color = 0xFFD5D1C8.toInt(); p.strokeWidth = 1f * k
        c.drawRoundRect(r, 9f * k, 9f * k, p)

        val ix = l.x + 4f * k; val iy = l.y + 4f * k; val s = l.h - 8f * k
        val ir = rect2TL.get()!!
        ir.set(ix, iy, ix + s, iy + s)
        val tp = textTL.get()!!
        when {
            l.isFile -> {
                val (col, tag) = fileBadge(l.target)
                f.color = col
                c.drawRoundRect(ir, 5f * k, 5f * k, f)
                tp.color = Color.WHITE; tp.typeface = Typeface.DEFAULT_BOLD
                tp.textSize = (if (tag.length >= 4) 6.2f else 7.6f) * k
                tp.textAlign = Paint.Align.CENTER
                c.drawText(tag, ir.centerX(), ir.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
                tp.textAlign = Paint.Align.LEFT
            }
            l.isVideo -> {
                f.color = 0xFFE5484D.toInt()
                c.drawRoundRect(ir, 6f * k, 6f * k, f)
                val tri = pathTL.get()!!
                tri.rewind()
                val cx = ir.centerX() + 1f * k; val cy = ir.centerY(); val h = s * 0.22f
                tri.moveTo(cx - h * 0.85f, cy - h); tri.lineTo(cx + h * 1.05f, cy); tri.lineTo(cx - h * 0.85f, cy + h); tri.close()
                f.color = Color.WHITE
                c.drawPath(tri, f)
            }
            else -> {
                val cx = ir.centerX(); val cy = ir.centerY(); val rad = s * 0.40f
                p.color = 0xFF3B82F6.toInt(); p.strokeWidth = 1.5f * k
                c.drawCircle(cx, cy, rad, p)
                ir.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad)
                c.drawOval(ir, p)
                c.drawLine(cx - rad, cy, cx + rad, cy, p)
                p.strokeWidth = 1f * k
                c.drawLine(cx - rad * 0.86f, cy - rad * 0.5f, cx + rad * 0.86f, cy - rad * 0.5f, p)
                c.drawLine(cx - rad * 0.86f, cy + rad * 0.5f, cx + rad * 0.86f, cy + rad * 0.5f, p)
            }
        }

        val tx = ix + s + 7f * k
        val avail = r.right - 8f * k - tx
        if (avail <= 4f) return
        tp.color = 0xFF1F2937.toInt(); tp.typeface = Typeface.DEFAULT; tp.textSize = 12f * k
        val key = avail + k * 100000f
        val shown = if (l.shown != null && l.shownFor == key) l.shown!! else
            TextUtils.ellipsize(l.label, tp, avail, TextUtils.TruncateAt.END).also { l.shown = it; l.shownFor = key }
        c.drawText(shown, 0, shown.length, tx, r.centerY() - (tp.ascent() + tp.descent()) / 2f, tp)
    }

    // =====================================================================================
    // Paper
    // =====================================================================================

    /**
     * Paper pattern for notebooks (page coordinates, no background fill).
     * @param clip only this part (page coordinates) is drawn — keeps huge whiteboards fast
     * @param pxPerUnit current screen pixels per page point; when > 0, lines/dots closer than a few pixels are thinned out
     *   (every 2nd, 4th… — always a subset, so the pattern never shifts) and hairlines keep a visible width
     * @param bounded false for whiteboards: the pattern covers [clip] without page margins (lined has no red margin)
     */
    fun drawPaper(c: Canvas, page: InkPage, dark: Boolean = false, clip: RectF? = null, pxPerUnit: Float = 0f, bounded: Boolean = true) {
        val paper = page.paper
        if (paper != "lined" && paper != "grid" && paper != "dots" && paper != "cornell") return
        val area = rect2TL.get()!!
        if (clip != null) area.set(clip) else area.set(0f, 0f, page.w, page.h)
        if (bounded && !area.intersect(0f, 0f, page.w, page.h)) return
        if (area.isEmpty) return
        val lp = linePaint
        lp.style = Paint.Style.STROKE
        fun lod(spacing: Float, minPx: Float): Float {
            var s = spacing
            if (pxPerUnit > 0f) while (s * pxPerUnit < minPx && s < 1e6f) s *= 2f
            return s
        }
        fun hair(base: Float) = if (pxPerUnit > 0f) max(base, 0.75f / pxPerUnit) else base
        val lined = paper == "lined" || (paper == "cornell" && !bounded)
        when {
            lined -> {
                lp.color = if (dark) 0x33FFFFFF else 0xFFC9D8EA.toInt(); lp.strokeWidth = hair(0.6f)
                val s = lod(26f, 7f)
                if (bounded) {
                    val x0 = max(24f, area.left); val x1 = min(page.w - 24f, area.right)
                    var y = 72f + max(0f, ceil((area.top - 72f) / s)) * s
                    val yEnd = min(page.h - 20f, area.bottom)
                    while (y < yEnd) { c.drawLine(x0, y, x1, y, lp); y += s }
                    if (64f >= area.left && 64f <= area.right) {
                        lp.color = 0x55E57373; c.drawLine(64f, area.top, 64f, area.bottom, lp)
                    }
                } else {
                    var y = ceil(area.top / s) * s
                    while (y <= area.bottom) { c.drawLine(area.left, y, area.right, y, lp); y += s }
                }
            }
            paper == "grid" -> {
                lp.color = if (dark) 0x26FFFFFF else 0xFFDCE3EC.toInt(); lp.strokeWidth = hair(0.5f)
                val s = lod(18f, 9f)
                var x = ceil(area.left / s) * s; if (bounded && x <= 0f) x = s
                while (x <= area.right) { c.drawLine(x, area.top, x, area.bottom, lp); x += s }
                var y = ceil(area.top / s) * s; if (bounded && y <= 0f) y = s
                while (y <= area.bottom) { c.drawLine(area.left, y, area.right, y, lp); y += s }
            }
            paper == "dots" -> {
                lp.color = if (dark) 0x40FFFFFF else 0xFFB9C2CC.toInt()
                val s = lod(18f, 13f)
                lp.strokeWidth = 2f * (if (pxPerUnit > 0f) max(0.9f, 0.9f / pxPerUnit) else 0.9f)
                lp.strokeCap = Paint.Cap.ROUND
                var buf = dotsTL.get()!!
                var n = 0
                var y = ceil(area.top / s) * s; if (bounded && y <= 0f) y = s
                val xStart = ceil(area.left / s) * s
                while (y <= area.bottom) {
                    var x = if (bounded && xStart <= 0f) s else xStart
                    while (x <= area.right) {
                        if (n + 2 > buf.size) {
                            if (buf.size < 65536) { buf = buf.copyOf(buf.size * 2); dotsTL.set(buf) }
                            else { c.drawPoints(buf, 0, n, lp); n = 0 }
                        }
                        buf[n++] = x; buf[n++] = y
                        x += s
                    }
                    y += s
                }
                if (n > 0) c.drawPoints(buf, 0, n, lp)
                lp.strokeCap = Paint.Cap.BUTT
            }
            paper == "cornell" -> {
                lp.color = if (dark) 0x33FFFFFF else 0xFFC9D8EA.toInt(); lp.strokeWidth = hair(0.6f)
                val s = lod(26f, 7f)
                var y = 72f + max(0f, floor((area.top - 72f) / s)) * s
                while (y < min(page.h - 150f, area.bottom)) { c.drawLine(24f, y, page.w - 24f, y, lp); y += s }
                lp.color = 0xFF9DB3CC.toInt(); lp.strokeWidth = hair(1f)
                c.drawLine(150f, 40f, 150f, page.h - 150f, lp)
                c.drawLine(24f, page.h - 150f, page.w - 24f, page.h - 150f, lp)
            }
        }
    }

    /**
     * Everything a user put on the page — no paper/background. Order: images, ink, text, links, then tapes on top
     * (tapes always hidden here: exports and thumbnails never give the answers away).
     */
    fun drawPageContent(c: Canvas, page: InkPage) {
        val imgs = page.images
        for (i in imgs.indices) drawImage(c, imgs[i])
        val st = page.strokes
        var tapes = 0
        for (i in st.indices) { val s = st[i]; if (s.isTape) tapes++ else drawStroke(c, s) }
        val tx = page.texts
        for (i in tx.indices) drawText(c, tx[i])
        val ln = page.links
        for (i in ln.indices) drawLink(c, ln[i])
        if (tapes > 0) for (i in st.indices) { val s = st[i]; if (s.isTape) drawTape(c, s, false) }
    }
}
