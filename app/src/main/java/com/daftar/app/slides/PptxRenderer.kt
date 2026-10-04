package com.daftar.app.slides

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Localised labels drawn inside placeholders for content we cannot render. */
class Labels(val chart: String, val diagram: String, val obj: String, val image: String)

/** Maps shape EMU coordinates to slide points (scale + translate; group child spaces compose into it). */
private class Xf(val sx: Float, val sy: Float, val tx: Float, val ty: Float) {
    fun x(e: Float) = tx + e * sx
    fun y(e: Float) = ty + e * sy
    companion object { val ROOT = Xf(1f / EMU_PER_PT, 1f / EMU_PER_PT, 0f, 0f) }
}

private enum class Level { MASTER, LAYOUT, SLIDE }

/** Per-render context. */
private class Ctx(
    val slide: SlidePart, val cc: ColorCtx, val theme: Theme, val px: Float, val slideNum: Int,
)

/** A resolved outline. */
private class Line(
    val color: Int, val width: Float, val dash: String?, val head: XNode?, val tail: XNode?, val round: Boolean,
)

private class Geom(val path: Path, val fillable: Boolean, val start: FloatArray?, val end: FloatArray?, val ellipse: Boolean)

/**
 * Bitmap cache for slide media: decodes with inSampleSize to the size actually needed, bounded by bytes
 * (maxMemory / 16; the thumbnail cache takes another 1/16, so a deck screen stays within 1/8 of the heap).
 * Evicted bitmaps are NOT recycled: a PDF page records references to them until the document is written.
 * They are recycled only by [clear] with `recycle = true`, which [PptxSource.close] uses once nothing draws any more.
 */
class ImageCache(private val zip: java.util.zip.ZipFile) {
    private val maxBytes = min(Runtime.getRuntime().maxMemory() / 16, 64L * 1024 * 1024).toInt().coerceAtLeast(4 * 1024 * 1024)
    private val lru = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val bad = HashSet<String>()
    private val dims = HashMap<String, IntArray>()

    fun get(path: String, wantW0: Float, wantH0: Float): Bitmap? {
        if (path in bad) return null
        val wantW = if (wantW0.isFinite()) max(1f, wantW0) else 1f
        val wantH = if (wantH0.isFinite()) max(1f, wantH0) else 1f
        val ext = path.substringAfterLast('.').lowercase()
        if (ext in setOf("emf", "wmf", "svg", "tif", "tiff", "wdp", "jxr", "pict")) { bad.add(path); return null }
        val d = dims.getOrPut(path) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) } } }
            intArrayOf(o.outWidth, o.outHeight)
        }
        if (d[0] <= 0 || d[1] <= 0) { bad.add(path); return null }
        var sample = 1
        while (sample < 64 && d[0] / (sample * 2) >= wantW && d[1] / (sample * 2) >= wantH) sample *= 2
        while (sample < 256 && (d[0].toLong() / sample) * (d[1].toLong() / sample) > 12_000_000L) sample *= 2
        // A sharper cached copy is just as good.
        var s = 1
        while (s <= sample) { lru.get("$path#$s")?.let { return it }; s *= 2 }
        val bmp = try {
            val o = BitmapFactory.Options().apply { inSampleSize = sample }
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, o) } }
        } catch (_: OutOfMemoryError) { lru.evictAll(); null } catch (_: Exception) { null }
        if (bmp == null) { bad.add(path); return null }
        lru.put("$path#$sample", bmp)
        return bmp
    }

    fun clear(recycle: Boolean = false) {
        val all = if (recycle) lru.snapshot().values.toList() else emptyList()
        lru.evictAll()
        all.forEach { if (!it.isRecycled) it.recycle() }
    }
}

class PptxRenderer(private val deck: Pptx, private val labels: Labels) {
    private val images = ImageCache(deck.zip)
    private val extraParts = HashMap<String, Part?>()

    /**
     * Distinct media bitmaps drawn since [markImages] (export uses it to bound what a PDF batch keeps alive).
     * Off until the first [markImages], so the on-screen viewer never pins bitmaps here.
     */
    private val drawn = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
    private var drawnBytes = 0L
    private var tracking = false
    fun markImages() { drawn.clear(); drawnBytes = 0L; tracking = true }
    fun imageBytesSinceMark(): Long = drawnBytes

    private data class TextKey(val node: XNode, val slide: Int, val w: Int, val h: Int)
    private val textCache = object : LinkedHashMap<TextKey, TextBlock>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, TextBlock>?) = size > 600
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun clearCaches(recycle: Boolean = false) {
        images.clear(recycle)
        synchronized(textCache) { textCache.clear() }
        drawn.clear(); drawnBytes = 0L
    }

    /** Draw slide [i] on [c], whose current matrix maps slide points to pixels at [pxPerPt]. */
    fun render(i: Int, c: Canvas, pxPerPt: Float) {
        val s = deck.slides[i]
        val layout = s.layout
        val master = layout.master
        val cc = ColorCtx(master.theme.colors, master.clrMap)
            .withOverride(layout.part.root.child("clrMapOvr"))
            .withOverride(s.part.root.child("clrMapOvr"))
        val ctx = Ctx(s, cc, master.theme, pxPerPt, deck.firstSlideNum + i)
        c.save()
        c.clipRect(0f, 0f, deck.widthPt, deck.heightPt)
        drawBackground(c, ctx)
        if (s.showMasterSp) {
            if (layout.showMasterSp) master.part.root.path("cSld", "spTree")?.let { drawTree(c, it, master.part, Level.MASTER, Xf.ROOT, null, ctx) }
            layout.part.root.path("cSld", "spTree")?.let { drawTree(c, it, layout.part, Level.LAYOUT, Xf.ROOT, null, ctx) }
        }
        s.part.root.path("cSld", "spTree")?.let { drawTree(c, it, s.part, Level.SLIDE, Xf.ROOT, null, ctx) }
        c.restore()
    }

    // ---------------------------------------------------------------- background

    private fun drawBackground(c: Canvas, ctx: Ctx) {
        val s = ctx.slide
        val full = RectF(0f, 0f, deck.widthPt, deck.heightPt)
        for (part in listOf(s.part, s.layout.part, s.layout.master.part)) {
            val bg = part.root.path("cSld", "bg") ?: continue
            val fill: Fill? = bg.child("bgPr")?.let { fillIn(it, part, ctx.cc, null, null) }
                ?: bg.child("bgRef")?.let { ref ->
                    val idx = ref.int("idx") ?: 0
                    val phc = colorIn(ref, ctx.cc, null)
                    val styleNode = when {
                        idx >= 1001 -> ctx.theme.bgFillStyles.getOrNull(idx - 1001)
                        idx >= 1 -> ctx.theme.fillStyles.getOrNull(idx - 1)
                        else -> null
                    }
                    styleNode?.let { fillOf(it, ctx.theme.part, ctx.cc, phc, null) } ?: phc?.let { Fill.Solid(it) }
                }
            if (fill != null) {
                c.drawColor(0xFFFFFFFF.toInt())
                paintFill(c, Path().apply { addRect(full, Path.Direction.CW) }, full, fill, ctx)
                return
            }
        }
        c.drawColor(ctx.cc.scheme("bg1", null))
    }

    // ---------------------------------------------------------------- tree walk

    private fun drawTree(c: Canvas, tree: XNode, part: Part, level: Level, xf: Xf, groupFill: Fill?, ctx: Ctx) {
        for (n in tree.children) {
            try {
                drawNode(c, n, part, level, xf, groupFill, ctx)
            } catch (_: OutOfMemoryError) {
                images.clear()
            } catch (_: Exception) {
                // One malformed shape must not break the whole slide.
            }
        }
    }

    private fun drawNode(c: Canvas, n: XNode, part: Part, level: Level, xf: Xf, groupFill: Fill?, ctx: Ctx) {
        when (n.name) {
            "sp", "cxnSp" -> drawSp(c, n, part, level, xf, groupFill, ctx)
            "pic" -> drawPic(c, n, part, level, xf, ctx)
            "grpSp" -> drawGroup(c, n, part, level, xf, groupFill, ctx)
            "graphicFrame" -> drawFrame(c, n, part, level, xf, ctx)
            "AlternateContent" -> {
                val alt = n.child("Fallback") ?: n.child("Choice") ?: return
                drawTree(c, alt, part, level, xf, groupFill, ctx)
            }
        }
    }

    private fun isHidden(n: XNode): Boolean {
        for (ch in n.children) if (ch.name.startsWith("nv")) return ch.child("cNvPr")?.bool("hidden") == true
        return false
    }

    /** Inherited placeholder shapes (layout, then master) with their parts, most specific first. */
    private fun inherited(n: XNode, level: Level, ctx: Ctx): List<Pair<XNode, Part>> {
        if (level != Level.SLIDE) return emptyList()
        val ph = phOf(n) ?: return emptyList()
        val layout = ctx.slide.layout
        val out = ArrayList<Pair<XNode, Part>>(2)
        val lp = layout.ph.matchForSlide(ph)
        if (lp != null) out.add(lp to layout.part)
        val mp = layout.master.ph.matchForLayout(lp?.let { phOf(it) } ?: ph)
        if (mp != null) out.add(mp to layout.master.part)
        return out
    }

    private fun xfrmOf(n: XNode): XNode? = n.child("spPr")?.child("xfrm") ?: n.child("xfrm")

    private fun rectOf(x: XNode, xf: Xf): RectF? {
        val off = x.child("off") ?: return null
        val ext = x.child("ext") ?: return null
        val l = xf.x((off.long("x") ?: 0L).toFloat())
        val t = xf.y((off.long("y") ?: 0L).toFloat())
        return RectF(l, t, l + (ext.long("cx") ?: 0L) * xf.sx, t + (ext.long("cy") ?: 0L) * xf.sy)
    }

    /** Apply xfrm rotation + flips around the rect centre. */
    private fun transform(c: Canvas, x: XNode, r: RectF, flips: Boolean = true) {
        val rot = (x.long("rot") ?: 0L) / 60000f
        if (rot != 0f) c.rotate(rot, r.centerX(), r.centerY())
        if (flips) {
            val fh = x.bool("flipH") == true
            val fv = x.bool("flipV") == true
            if (fh || fv) c.scale(if (fh) -1f else 1f, if (fv) -1f else 1f, r.centerX(), r.centerY())
        }
    }

    // ---------------------------------------------------------------- shapes

    private fun drawSp(c: Canvas, n: XNode, part: Part, level: Level, xf: Xf, groupFill: Fill?, ctx: Ctx) {
        if (isHidden(n)) return
        if (level != Level.SLIDE && phOf(n) != null) return   // layout/master placeholders are not shown on slides
        val inh = inherited(n, level, ctx)
        val spPr = n.child("spPr")
        val x = spPr?.child("xfrm") ?: inh.firstNotNullOfOrNull { xfrmOf(it.first) } ?: return
        val r = rectOf(x, xf) ?: return
        val geomNode = spPr?.child("prstGeom") ?: spPr?.child("custGeom")
            ?: inh.firstNotNullOfOrNull { it.first.child("spPr")?.let { p -> p.child("prstGeom") ?: p.child("custGeom") } }
        val style = n.child("style") ?: inh.firstNotNullOfOrNull { it.first.child("style") }
        val geom = buildGeom(geomNode, r.width(), r.height())

        val fill = fillIn(spPr, part, ctx.cc, null, groupFill)
            ?: inh.firstNotNullOfOrNull { fillIn(it.first.child("spPr"), it.second, ctx.cc, null, groupFill) }
            ?: styleFill(style, ctx)
        val line = resolveLine(listOfNotNull(spPr?.child("ln")) + inh.mapNotNull { it.first.child("spPr")?.child("ln") }, style, ctx)

        if ((fill != null && fill != Fill.None) || line != null) {
            c.save()
            transform(c, x, r)
            c.translate(r.left, r.top)
            val local = RectF(0f, 0f, r.width(), r.height())
            if (fill != null && geom.fillable) paintFill(c, geom.path, local, fill, ctx)
            if (line != null) strokeGeom(c, geom, line)
            c.restore()
        }

        val tx = n.child("txBody") ?: return
        if (plainText(tx).isBlank()) return
        val textRect = n.child("txXfrm")?.let { rectOf(it, xf) } ?: r
        drawTextBody(c, n, tx, textRect, x, inh, level, style, geom.ellipse, ctx)
    }

    private fun styleFill(style: XNode?, ctx: Ctx): Fill? {
        val ref = style?.child("fillRef") ?: return null
        val idx = ref.int("idx") ?: 0
        if (idx == 0) return Fill.None
        val phc = colorIn(ref, ctx.cc, null)
        val node = if (idx >= 1001) ctx.theme.bgFillStyles.getOrNull(idx - 1001) else ctx.theme.fillStyles.getOrNull(idx - 1)
        return node?.let { fillOf(it, ctx.theme.part, ctx.cc, phc, null) } ?: phc?.let { Fill.Solid(it) }
    }

    /** Outline from explicit `a:ln` nodes (most specific first) and the shape style's lnRef. */
    private fun resolveLine(lns: List<XNode>, style: XNode?, ctx: Ctx): Line? {
        val ref = style?.child("lnRef")
        val refIdx = ref?.int("idx") ?: 0
        val phc = ref?.let { colorIn(it, ctx.cc, null) }
        val themeLn = if (refIdx > 0) ctx.theme.lnStyles.getOrNull(refIdx - 1) else null
        val all = lns + listOfNotNull(themeLn)
        val fillNode = all.firstOrNull { l -> l.children.any { it.name in setOf("noFill", "solidFill", "gradFill", "pattFill") } }
        val color: Int = if (fillNode == null) {
            if (refIdx > 0 && phc != null) phc else return null
        } else {
            val ph = if (fillNode === themeLn) phc else null
            when (val f = fillIn(fillNode, null, ctx.cc, ph, null)) {
                is Fill.Solid -> f.color
                is Fill.Grad -> f.colors[0]
                else -> return null
            }
        }
        val w = all.firstNotNullOfOrNull { it.long("w") } ?: 9525L
        val dash = all.firstNotNullOfOrNull { it.child("prstDash")?.attr("val") }
        val round = all.firstOrNull()?.let { it.attr("cap") == "rnd" || it.child("round") != null } ?: false
        return Line(color, w / EMU_PER_PT, dash, lns.firstNotNullOfOrNull { it.child("headEnd") }, lns.firstNotNullOfOrNull { it.child("tailEnd") }, round)
    }

    private fun lineFromNode(ln: XNode?, ctx: Ctx): Line? {
        ln ?: return null
        val color = when (val f = fillIn(ln, null, ctx.cc, null, null)) {
            is Fill.Solid -> f.color
            is Fill.Grad -> f.colors[0]
            else -> return null
        }
        return Line(color, (ln.long("w") ?: 12700L) / EMU_PER_PT, ln.child("prstDash")?.attr("val"), null, null, false)
    }

    private fun setupStroke(line: Line) {
        strokePaint.color = line.color
        strokePaint.strokeWidth = line.width
        strokePaint.strokeCap = if (line.round) Paint.Cap.ROUND else Paint.Cap.BUTT
        strokePaint.strokeJoin = if (line.round) Paint.Join.ROUND else Paint.Join.MITER
        val w = max(line.width, 0.75f)
        strokePaint.pathEffect = when (line.dash) {
            null, "solid" -> null
            "dot", "sysDot" -> DashPathEffect(floatArrayOf(w, w), 0f)
            "dash", "sysDash" -> DashPathEffect(floatArrayOf(w * 4, w * 3), 0f)
            "lgDash" -> DashPathEffect(floatArrayOf(w * 8, w * 3), 0f)
            "dashDot", "sysDashDot" -> DashPathEffect(floatArrayOf(w * 4, w * 3, w, w * 3), 0f)
            "lgDashDot" -> DashPathEffect(floatArrayOf(w * 8, w * 3, w, w * 3), 0f)
            else -> DashPathEffect(floatArrayOf(w * 4, w * 3, w, w * 3, w, w * 3), 0f)
        }
    }

    private fun strokeGeom(c: Canvas, g: Geom, line: Line) {
        setupStroke(line)
        c.drawPath(g.path, strokePaint)
        strokePaint.pathEffect = null
        if (g.start != null && g.end != null) {
            arrowHead(c, line.tail, g.end, g.start, line)
            arrowHead(c, line.head, g.start, g.end, line)
        }
    }

    private fun arrowHead(c: Canvas, end: XNode?, tip: FloatArray, from: FloatArray, line: Line) {
        val type = end?.attr("type") ?: return
        if (type == "none") return
        fun sz(a: String?) = when (a) { "sm" -> 2f; "lg" -> 5f; else -> 3f }
        val lw = max(line.width, 0.75f)
        val len = lw * sz(end.attr("len"))
        val wid = lw * sz(end.attr("w"))
        val ang = atan2(tip[1] - from[1], tip[0] - from[0])
        val bx = tip[0] - cos(ang) * len
        val by = tip[1] - sin(ang) * len
        val nx = -sin(ang) * wid / 2f
        val ny = cos(ang) * wid / 2f
        fillPaint.shader = null
        fillPaint.color = line.color
        if (type == "oval") { c.drawCircle(tip[0], tip[1], wid / 2f, fillPaint); return }
        val p = Path().apply { moveTo(tip[0], tip[1]); lineTo(bx + nx, by + ny); lineTo(bx - nx, by - ny); close() }
        c.drawPath(p, fillPaint)
    }

    private fun paintFill(c: Canvas, path: Path, bounds: RectF, fill: Fill, ctx: Ctx) {
        when (fill) {
            Fill.None -> {}
            is Fill.Solid -> {
                fillPaint.shader = null; fillPaint.color = fill.color
                c.drawPath(path, fillPaint)
            }
            is Fill.Grad -> {
                fillPaint.color = 0xFF000000.toInt()
                fillPaint.shader = if (fill.radial) {
                    RadialGradient(
                        bounds.centerX(), bounds.centerY(), max(1f, max(bounds.width(), bounds.height()) * 0.71f),
                        fill.colors, fill.pos, Shader.TileMode.CLAMP,
                    )
                } else {
                    val a = Math.toRadians(fill.angleDeg.toDouble())
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    val half = (abs(bounds.width() * dx) + abs(bounds.height() * dy)) / 2f
                    LinearGradient(
                        bounds.centerX() - dx * half, bounds.centerY() - dy * half,
                        bounds.centerX() + dx * half, bounds.centerY() + dy * half,
                        fill.colors, fill.pos, Shader.TileMode.CLAMP,
                    )
                }
                c.drawPath(path, fillPaint)
                fillPaint.shader = null
            }
            is Fill.Image -> {
                c.save()
                c.clipPath(path)
                drawImage(c, fill.media, bounds, if (fill.tile) null else fill.srcRect, fill.alpha, false, ctx)
                c.restore()
            }
        }
    }

    // ---------------------------------------------------------------- geometry

    private fun buildGeom(g: XNode?, w: Float, h: Float): Geom {
        val p = Path()
        if (g == null) { p.addRect(0f, 0f, w, h, Path.Direction.CW); return Geom(p, true, null, null, false) }
        if (g.name == "custGeom") return custGeom(g, w, h)
        val prst = g.attr("prst") ?: "rect"
        val adj = HashMap<String, Float>()
        g.child("avLst")?.children("gd")?.forEach { gd ->
            val f = gd.attr("fmla") ?: return@forEach
            if (f.startsWith("val ")) f.substring(4).trim().toFloatOrNull()?.let { adj[gd.attr("name") ?: ""] = it }
        }
        fun a(name: String, def: Float) = (adj[name] ?: (if (name == "adj1") adj["adj"] else null) ?: def) / 100000f
        val ss = min(w, h)
        fun poly(vararg pts: Float) {
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
            p.close()
        }
        var ellipse = false
        when (prst) {
            "line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4", "bentConnector5",
            "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5" -> {
                p.moveTo(0f, 0f); p.lineTo(w, h)
                return Geom(p, false, floatArrayOf(0f, 0f), floatArrayOf(w, h), false)
            }
            "ellipse", "flowChartConnector", "donut", "cloud", "flowChartOr", "flowChartSummingJunction" -> {
                p.addOval(RectF(0f, 0f, w, h), Path.Direction.CW); ellipse = true
            }
            "roundRect", "round2SameRect", "round1Rect", "round2DiagRect", "flowChartAlternateProcess" -> {
                val r = ss * a("adj", 16667f)
                p.addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW)
            }
            "flowChartTerminator" -> p.addRoundRect(RectF(0f, 0f, w, h), h / 2f, h / 2f, Path.Direction.CW)
            "triangle", "flowChartExtract" -> { val x = w * a("adj", 50000f); poly(x, 0f, w, h, 0f, h) }
            "rtTriangle" -> poly(0f, 0f, w, h, 0f, h)
            "diamond", "flowChartDecision" -> poly(w / 2, 0f, w, h / 2, w / 2, h, 0f, h / 2)
            "parallelogram", "flowChartInputOutput" -> { val o = ss * a("adj", 25000f); poly(o, 0f, w, 0f, w - o, h, 0f, h) }
            "trapezoid" -> { val o = ss * a("adj", 25000f); poly(0f, h, o, 0f, w - o, 0f, w, h) }
            "hexagon" -> { val o = ss * a("adj", 25000f); poly(0f, h / 2, o, 0f, w - o, 0f, w, h / 2, w - o, h, o, h) }
            "octagon" -> { val o = ss * a("adj", 29289f); poly(o, 0f, w - o, 0f, w, o, w, h - o, w - o, h, o, h, 0f, h - o, 0f, o) }
            "pentagon" -> poly(w / 2, 0f, w, h * 0.38f, w * 0.81f, h, w * 0.19f, h, 0f, h * 0.38f)
            "homePlate", "flowChartOffpageConnector" -> { val x = w - ss * a("adj", 50000f); poly(0f, 0f, x, 0f, w, h / 2, x, h, 0f, h) }
            "chevron" -> { val o = ss * a("adj", 50000f); poly(0f, 0f, w - o, 0f, w, h / 2, w - o, h, 0f, h, o, h / 2) }
            "rightArrow" -> {
                val th = h * a("adj1", 50000f); val hl = ss * a("adj2", 50000f)
                val y1 = (h - th) / 2; val y2 = y1 + th; val x1 = w - hl
                poly(0f, y1, x1, y1, x1, 0f, w, h / 2, x1, h, x1, y2, 0f, y2)
            }
            "leftArrow" -> {
                val th = h * a("adj1", 50000f); val hl = ss * a("adj2", 50000f)
                val y1 = (h - th) / 2; val y2 = y1 + th
                poly(w, y1, hl, y1, hl, 0f, 0f, h / 2, hl, h, hl, y2, w, y2)
            }
            "upArrow" -> {
                val th = w * a("adj1", 50000f); val hl = ss * a("adj2", 50000f)
                val x1 = (w - th) / 2; val x2 = x1 + th
                poly(x1, h, x1, hl, 0f, hl, w / 2, 0f, w, hl, x2, hl, x2, h)
            }
            "downArrow" -> {
                val th = w * a("adj1", 50000f); val hl = ss * a("adj2", 50000f)
                val x1 = (w - th) / 2; val x2 = x1 + th; val y = h - hl
                poly(x1, 0f, x2, 0f, x2, y, w, y, w / 2, h, 0f, y, x1, y)
            }
            "leftRightArrow" -> {
                val th = h * a("adj1", 50000f); val hl = ss * a("adj2", 50000f)
                val y1 = (h - th) / 2; val y2 = y1 + th
                poly(0f, h / 2, hl, 0f, hl, y1, w - hl, y1, w - hl, 0f, w, h / 2, w - hl, h, w - hl, y2, hl, y2, hl, h)
            }
            "plus", "mathPlus" -> {
                val o = ss * a("adj", 25000f)
                poly(o, 0f, w - o, 0f, w - o, o, w, o, w, h - o, w - o, h - o, w - o, h, o, h, o, h - o, 0f, h - o, 0f, o, o, o)
            }
            "star5" -> {
                val pts = FloatArray(20)
                for (k in 0 until 10) {
                    val rr = if (k % 2 == 0) 0.5f else 0.19f
                    val ang = Math.toRadians(-90.0 + k * 36.0)
                    pts[k * 2] = w / 2 + (cos(ang) * rr * w).toFloat()
                    pts[k * 2 + 1] = h / 2 + (sin(ang) * rr * h).toFloat() + h * 0.05f
                }
                poly(*pts)
            }
            "snip1Rect" -> { val o = ss * a("adj", 16667f); poly(0f, 0f, w - o, 0f, w, o, w, h, 0f, h) }
            "snip2SameRect" -> { val o = ss * a("adj1", 16667f); poly(o, 0f, w - o, 0f, w, o, w, h, 0f, h, 0f, o) }
            "flowChartDocument", "wave" -> {
                p.moveTo(0f, 0f); p.lineTo(w, 0f); p.lineTo(w, h * 0.83f)
                p.cubicTo(w * 0.75f, h * 0.7f, w * 0.5f, h * 1.05f, 0f, h * 0.9f); p.close()
            }
            "can", "flowChartMagneticDisk" -> {
                val e = h * a("adj", 25000f) / 2
                p.addRoundRect(RectF(0f, 0f, w, h), w / 2, e, Path.Direction.CW)
            }
            else -> p.addRect(0f, 0f, w, h, Path.Direction.CW)
        }
        return Geom(p, true, null, null, ellipse)
    }

    private fun custGeom(g: XNode, w: Float, h: Float): Geom {
        val p = Path()
        var fillable = false
        var first: FloatArray? = null
        var last: FloatArray? = null
        var open = true
        for (sp in g.child("pathLst")?.children("path") ?: emptyList()) {
            val pw = sp.long("w")?.toFloat()?.takeIf { it > 0f }
            val ph = sp.long("h")?.toFloat()?.takeIf { it > 0f }
            val sx = if (pw != null) w / pw else 1f / EMU_PER_PT
            val sy = if (ph != null) h / ph else 1f / EMU_PER_PT
            if (sp.attr("fill") != "none") fillable = true
            var cx = 0f; var cy = 0f
            fun pt(n: XNode): FloatArray = floatArrayOf((n.attr("x")?.toFloatOrNull() ?: 0f) * sx, (n.attr("y")?.toFloatOrNull() ?: 0f) * sy)
            for (cmd in sp.children) {
                val pts = cmd.children("pt").map(::pt)
                when (cmd.name) {
                    "moveTo" -> pts.firstOrNull()?.let { p.moveTo(it[0], it[1]); cx = it[0]; cy = it[1]; if (first == null) first = it }
                    "lnTo" -> pts.firstOrNull()?.let { p.lineTo(it[0], it[1]); cx = it[0]; cy = it[1]; last = it }
                    "cubicBezTo" -> if (pts.size == 3) { p.cubicTo(pts[0][0], pts[0][1], pts[1][0], pts[1][1], pts[2][0], pts[2][1]); cx = pts[2][0]; cy = pts[2][1]; last = pts[2] }
                    "quadBezTo" -> if (pts.size == 2) { p.quadTo(pts[0][0], pts[0][1], pts[1][0], pts[1][1]); cx = pts[1][0]; cy = pts[1][1]; last = pts[1] }
                    "arcTo" -> {
                        val wr = (cmd.attr("wR")?.toFloatOrNull() ?: 0f) * sx
                        val hr = (cmd.attr("hR")?.toFloatOrNull() ?: 0f) * sy
                        val st = (cmd.attr("stAng")?.toFloatOrNull() ?: 0f) / 60000f
                        val sw = (cmd.attr("swAng")?.toFloatOrNull() ?: 0f) / 60000f
                        val sr = Math.toRadians(st.toDouble())
                        val ocx = cx - (wr * cos(sr)).toFloat()
                        val ocy = cy - (hr * sin(sr)).toFloat()
                        p.arcTo(RectF(ocx - wr, ocy - hr, ocx + wr, ocy + hr), st, sw)
                        val er = Math.toRadians((st + sw).toDouble())
                        cx = ocx + (wr * cos(er)).toFloat(); cy = ocy + (hr * sin(er)).toFloat()
                    }
                    "close" -> { p.close(); open = false }
                }
            }
        }
        return Geom(p, fillable, if (open) first else null, if (open) last else null, false)
    }

    // ---------------------------------------------------------------- text

    private fun drawTextBody(
        c: Canvas, n: XNode, tx: XNode, r: RectF, x: XNode, inh: List<Pair<XNode, Part>>, level: Level,
        style: XNode?, ellipse: Boolean, ctx: Ctx,
    ) {
        val bodies = listOfNotNull(tx.child("bodyPr")) + inh.mapNotNull { it.first.child("txBody")?.child("bodyPr") }
        fun battr(name: String) = bodies.firstNotNullOfOrNull { it.attr(name) }
        val lIns = (battr("lIns")?.toLongOrNull() ?: 91440L) / EMU_PER_PT
        val tIns = (battr("tIns")?.toLongOrNull() ?: 45720L) / EMU_PER_PT
        val rIns = (battr("rIns")?.toLongOrNull() ?: 91440L) / EMU_PER_PT
        val bIns = (battr("bIns")?.toLongOrNull() ?: 45720L) / EMU_PER_PT
        val anchor = battr("anchor") ?: "t"
        val wrap = battr("wrap") != "none"
        val vert = battr("vert") ?: "horz"
        val fitHolder = bodies.firstOrNull { b -> b.child("normAutofit") != null || b.child("spAutoFit") != null || b.child("noAutofit") != null }
        val norm = fitHolder?.child("normAutofit")

        val box = RectF(r)
        if (ellipse) box.inset(r.width() * 0.146f, r.height() * 0.146f)
        val vertical = vert == "vert" || vert == "eaVert" || vert == "vert270" || vert == "wordArtVert" || vert == "mongolianVert" || vert == "wordArtVertRtl"
        val outerW = if (vertical) box.height() else box.width()
        val outerH = if (vertical) box.width() else box.height()
        val innerW = max(1f, outerW - lIns - rIns)
        val innerH = max(0f, outerH - tIns - bIns)

        // Style chain.
        val ph = phOf(n)
        val chain = ArrayList<StyleLayer>()
        chain.add(StyleLayer(deck.defaultTextStyle))
        if (ph != null && level == Level.SLIDE) {
            val m = ctx.slide.layout.master
            chain.add(
                StyleLayer(
                    when (phFamily(ph.attr("type") ?: "body")) {
                        "title" -> m.titleStyle
                        "body" -> m.bodyStyle
                        else -> m.otherStyle
                    },
                ),
            )
            inh.asReversed().forEach { chain.add(StyleLayer(it.first.child("txBody")?.child("lstStyle"))) }
        }
        style?.child("fontRef")?.let { fr ->
            val col = colorIn(fr, ctx.cc, null)
            val font = when (fr.attr("idx")) { "major" -> "+mj-lt"; "minor" -> null; else -> null }
            if (col != null || font != null) chain.add(StyleLayer(null, ParaProps(run = RunProps(color = col, latin = font))))
        }
        val env = TextEnv(ctx.cc, ctx.theme, chain, ctx.slideNum, ctx.cc.scheme("tx1", null))

        val key = TextKey(tx, ctx.slide.index, (innerW * 10).toInt(), (innerH * 10).toInt())
        val block = synchronized(textCache) { textCache[key] } ?: run {
            val fs = norm?.int("fontScale")?.let { it / 100000f }
            val red = norm?.int("lnSpcReduction")?.let { it / 100000f } ?: 0f
            var b = buildText(tx, env, innerW, wrap, fs ?: 1f, red)
            if (norm != null && fs == null && b.height > innerH && innerH > 0f) {
                // File saved without a computed scale (e.g. generated decks): shrink like PowerPoint would.
                for (step in listOf(0.9f to 0.1f, 0.8f to 0.2f, 0.7f to 0.2f, 0.6f to 0.2f, 0.5f to 0.2f)) {
                    b = buildText(tx, env, innerW, wrap, step.first, step.second)
                    if (b.height <= innerH) break
                }
            }
            b.also { synchronized(textCache) { textCache[key] = it } }
        }

        val dy = when (anchor) {
            "ctr" -> (innerH - block.height) / 2f
            "b" -> innerH - block.height
            else -> 0f
        }
        c.save()
        val rot = (x.long("rot") ?: 0L) / 60000f + (if (x.bool("flipV") == true) 180f else 0f)
        if (rot != 0f) c.rotate(rot, r.centerX(), r.centerY())
        if (vertical) {
            c.rotate(if (vert == "vert270") -90f else 90f, box.centerX(), box.centerY())
            val left = box.centerX() - outerW / 2f
            val top = box.centerY() - outerH / 2f
            block.draw(c, left + lIns, top + tIns + dy)
        } else {
            block.draw(c, box.left + lIns, box.top + tIns + dy)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- pictures

    private fun drawPic(c: Canvas, n: XNode, part: Part, level: Level, xf: Xf, ctx: Ctx, frame: RectF? = null) {
        if (isHidden(n)) return
        if (level != Level.SLIDE && phOf(n) != null) return
        val inh = inherited(n, level, ctx)
        val spPr = n.child("spPr")
        val x = spPr?.child("xfrm") ?: inh.firstNotNullOfOrNull { xfrmOf(it.first) }
        val r = x?.let { rectOf(it, xf) } ?: frame ?: return
        val blipFill = n.child("blipFill")
        val blip = blipFill?.child("blip")
        val media = part.rel(blip?.attr("r:embed"))?.target
        val geomNode = spPr?.child("prstGeom") ?: spPr?.child("custGeom")
        val geom = buildGeom(geomNode, r.width(), r.height())
        c.save()
        if (x != null) transform(c, x, r)
        c.translate(r.left, r.top)
        val local = RectF(0f, 0f, r.width(), r.height())
        val clip = geomNode != null && geomNode.attr("prst") != "rect"
        if (clip) { c.save(); c.clipPath(geom.path) }
        if (media != null) {
            val alpha = blip?.child("alphaModFix")?.int("amt")?.let { it / 100000f } ?: 1f
            val gray = blip?.child("grayscl") != null
            drawImage(c, media, local, blipFill?.child("srcRect"), alpha, gray, ctx)
        } else {
            placeholderBox(c, local, labels.image)
        }
        if (clip) c.restore()
        resolveLine(listOfNotNull(spPr?.child("ln")), n.child("style"), ctx)?.let { strokeGeom(c, geom, it) }
        c.restore()
    }

    private fun drawImage(c: Canvas, media: String, dst: RectF, srcRect: XNode?, alpha: Float, gray: Boolean, ctx: Ctx) {
        val l = (srcRect?.int("l") ?: 0) / 100000f
        val t = (srcRect?.int("t") ?: 0) / 100000f
        val rr = (srcRect?.int("r") ?: 0) / 100000f
        val b = (srcRect?.int("b") ?: 0) / 100000f
        val fw = max(0.01f, 1f - l - rr)
        val fh = max(0.01f, 1f - t - b)
        val bmp = images.get(media, dst.width() * ctx.px / fw, dst.height() * ctx.px / fh)
        if (bmp == null) { placeholderBox(c, dst, labels.image); return }
        if (tracking && drawn.add(bmp)) drawnBytes += bmp.allocationByteCount
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        // Source window (may extend outside the bitmap for negative crops) mapped onto dst.
        val sl = bw * l; val st = bh * t; val sr = bw * (1f - rr); val sb = bh * (1f - b)
        val il = max(0f, sl); val it = max(0f, st); val ir = min(bw, sr); val ib = min(bh, sb)
        if (ir <= il || ib <= it) return
        val kx = dst.width() / (sr - sl); val ky = dst.height() / (sb - st)
        val d = RectF(dst.left + (il - sl) * kx, dst.top + (it - st) * ky, dst.left + (ir - sl) * kx, dst.top + (ib - st) * ky)
        bmpPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        bmpPaint.colorFilter = if (gray) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
        c.drawBitmap(bmp, Rect(il.toInt(), it.toInt(), ir.toInt(), ib.toInt()), d, bmpPaint)
        bmpPaint.colorFilter = null
        bmpPaint.alpha = 255
    }

    private fun placeholderBox(c: Canvas, r: RectF, label: String) {
        if (r.width() <= 0f || r.height() <= 0f) return
        val rad = min(12f, min(r.width(), r.height()) * 0.08f)
        fillPaint.shader = null
        fillPaint.color = 0xFFF3F2EE.toInt()
        c.drawRoundRect(r, rad, rad, fillPaint)
        strokePaint.pathEffect = null
        strokePaint.color = 0xFFD9D6CE.toInt()
        strokePaint.strokeWidth = 1f
        c.drawRoundRect(r, rad, rad, strokePaint)
        val size = (min(r.width(), r.height()) * 0.12f).coerceIn(6f, 24f)
        if (size * label.length * 0.6f > r.width() * 1.2f || size > r.height()) return
        c.save()
        c.scale(1f / K, 1f / K)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A8F98.toInt(); textSize = size * K; textAlign = Paint.Align.CENTER
        }
        c.drawText(label, r.centerX() * K, r.centerY() * K - (tp.ascent() + tp.descent()) / 2f, tp)
        c.restore()
    }

    // ---------------------------------------------------------------- groups

    private fun drawGroup(c: Canvas, n: XNode, part: Part, level: Level, xf: Xf, groupFill: Fill?, ctx: Ctx) {
        if (isHidden(n)) return
        val gp = n.child("grpSpPr")
        val x = gp?.child("xfrm")
        val gFill = fillIn(gp, part, ctx.cc, null, groupFill) ?: groupFill
        if (x == null) { drawTree(c, n, part, level, xf, gFill, ctx); return }
        val off = x.child("off"); val ext = x.child("ext")
        val chOff = x.child("chOff"); val chExt = x.child("chExt")
        val ox = (off?.long("x") ?: 0L).toFloat(); val oy = (off?.long("y") ?: 0L).toFloat()
        val ex = (ext?.long("cx") ?: 0L).toFloat(); val ey = (ext?.long("cy") ?: 0L).toFloat()
        val cox = (chOff?.long("x") ?: off?.long("x") ?: 0L).toFloat(); val coy = (chOff?.long("y") ?: off?.long("y") ?: 0L).toFloat()
        val cex = (chExt?.long("cx") ?: ext?.long("cx") ?: 0L).toFloat(); val cey = (chExt?.long("cy") ?: ext?.long("cy") ?: 0L).toFloat()
        val kx = if (cex > 0f) ex / cex else 1f
        val ky = if (cey > 0f) ey / cey else 1f
        val child = Xf(xf.sx * kx, xf.sy * ky, xf.tx + xf.sx * (ox - cox * kx), xf.ty + xf.sy * (oy - coy * ky))
        val r = rectOf(x, xf)
        c.save()
        if (r != null) transform(c, x, r)
        drawTree(c, n, part, level, child, gFill, ctx)
        c.restore()
    }

    // ---------------------------------------------------------------- graphic frames

    private fun drawFrame(c: Canvas, n: XNode, part: Part, level: Level, xf: Xf, ctx: Ctx) {
        if (isHidden(n)) return
        if (level != Level.SLIDE && phOf(n) != null) return
        val inh = inherited(n, level, ctx)
        val x = n.child("xfrm") ?: inh.firstNotNullOfOrNull { xfrmOf(it.first) } ?: return
        val r = rectOf(x, xf) ?: return
        val data = n.path("graphic", "graphicData") ?: return
        val uri = data.attr("uri") ?: ""
        val tbl = data.child("tbl")
        when {
            tbl != null -> drawTable(c, tbl, r, part, ctx)
            uri.contains("chart", ignoreCase = true) -> placeholderBox(c, r, labels.chart)
            uri.contains("diagram", ignoreCase = true) -> if (!drawSmartArt(c, data, part, ctx)) placeholderBox(c, r, labels.diagram)
            else -> {
                val pic = data.find("pic")
                if (pic != null) drawPic(c, pic, part, Level.SLIDE, xf, ctx, r) else placeholderBox(c, r, labels.obj)
            }
        }
    }

    /** SmartArt: draw PowerPoint's cached drawing part (dsp:drawing) when present. */
    private fun drawSmartArt(c: Canvas, data: XNode, part: Part, ctx: Ctx): Boolean {
        val dm = data.child("relIds")?.attr("r:dm") ?: return false
        val dataPath = part.rel(dm)?.target ?: return false
        val dataPart = loadPart(dataPath) ?: return false
        val relId = dataPart.root.find("dataModelExt")?.attr("relId") ?: return false
        val drawingPath = part.rel(relId)?.target ?: return false
        val drawing = loadPart(drawingPath) ?: return false
        val tree = drawing.root.child("spTree") ?: return false
        drawTree(c, tree, drawing, Level.SLIDE, Xf.ROOT, null, ctx)
        return true
    }

    private fun loadPart(path: String): Part? = synchronized(extraParts) {
        extraParts.getOrPut(path) { PptxParser.loadPart(deck.zip, path) }
    }

    // ---------------------------------------------------------------- tables

    private class TPart(val fill: Fill?, val txColor: Int?, val bold: Boolean?, val italic: Boolean?, val borders: Map<String, Line?>)

    private fun tableStyle(id: String?, ctx: Ctx): Map<String, TPart> {
        val node = id?.let { deck.tableStyles[it] }
        if (node != null) {
            val out = HashMap<String, TPart>()
            for (p in node.children) {
                val tx = p.child("tcTxStyle")
                val tc = p.child("tcStyle")
                val fill = tc?.child("fill")?.let { fillIn(it, null, ctx.cc, null, null) }
                    ?: tc?.child("fillRef")?.let { ref -> colorIn(ref, ctx.cc, null)?.let { Fill.Solid(it) } }
                val borders = HashMap<String, Line?>()
                tc?.child("tcBdr")?.children?.forEach { e ->
                    val ln = e.child("ln")
                    borders[e.name] = if (ln != null) lineFromNode(ln, ctx)
                    else e.child("lnRef")?.let { ref -> colorIn(ref, ctx.cc, null)?.let { col -> Line(col, 1f, null, null, null, false) } }
                }
                val txColor = tx?.let { colorIn(it, ctx.cc, null) ?: colorIn(it.child("fontRef"), ctx.cc, null) }
                out[p.name] = TPart(fill, txColor, tx?.attr("b")?.let { it == "on" }, tx?.attr("i")?.let { it == "on" }, borders)
            }
            return out
        }
        // Built-in "Medium Style 2" family (PowerPoint's default table look), keyed by accent.
        val accent = when (id?.uppercase()) {
            "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" -> "accent1"
            "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE8}" -> "accent2"
            "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" -> "accent3"
            "{00A15C55-8517-42AA-B614-E9B94910E393}" -> "accent4"
            "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" -> "accent5"
            "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" -> "accent6"
            "{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}" -> "dk1"
            else -> null
        }
        if (accent == null) {
            val thin = Line(0xFFC9C6BE.toInt(), 0.75f, null, null, null, false)
            val all = mapOf("left" to thin, "right" to thin, "top" to thin, "bottom" to thin, "insideH" to thin, "insideV" to thin)
            return mapOf("wholeTbl" to TPart(null, null, null, null, all))
        }
        val base = ctx.cc.scheme(accent, null)
        fun tint(col: Int, t: Float): Int {
            val r = (255 - (255 - ((col shr 16) and 0xFF)) * t).toInt()
            val g = (255 - (255 - ((col shr 8) and 0xFF)) * t).toInt()
            val b = (255 - (255 - (col and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val lt1 = ctx.cc.scheme("lt1", null)
        val white = Line(lt1, 1f, null, null, null, false)
        val all = mapOf("left" to white, "right" to white, "top" to white, "bottom" to white, "insideH" to white, "insideV" to white)
        val head = TPart(Fill.Solid(base), lt1, true, null, emptyMap())
        return mapOf(
            "wholeTbl" to TPart(Fill.Solid(tint(base, 0.2f)), ctx.cc.scheme("dk1", null), null, null, all),
            "band1H" to TPart(Fill.Solid(tint(base, 0.4f)), null, null, null, emptyMap()),
            "band1V" to TPart(Fill.Solid(tint(base, 0.4f)), null, null, null, emptyMap()),
            "firstRow" to TPart(Fill.Solid(base), lt1, true, null, mapOf("bottom" to Line(lt1, 3f, null, null, null, false))),
            "lastRow" to TPart(Fill.Solid(base), lt1, true, null, mapOf("top" to Line(lt1, 3f, null, null, null, false))),
            "firstCol" to head, "lastCol" to head,
        )
    }

    private fun drawTable(c: Canvas, tbl: XNode, frame: RectF, part: Part, ctx: Ctx) {
        val tblPr = tbl.child("tblPr")
        fun flag(n: String) = tblPr?.bool(n) == true
        val styleId = tblPr?.child("tableStyleId")?.text?.trim()
        val style = tableStyle(styleId, ctx)
        val cols = tbl.child("tblGrid")?.children("gridCol")?.map { (it.long("w") ?: 0L) / EMU_PER_PT } ?: return
        if (cols.isEmpty()) return
        val sx = if (cols.sum() > 0f) frame.width() / cols.sum() else 1f
        val colX = FloatArray(cols.size + 1).also { a -> for (i in cols.indices) a[i + 1] = a[i] + cols[i] * sx }
        val rows = tbl.children("tr")
        val nRows = rows.size
        val nCols = cols.size

        fun partsFor(ri: Int, ci: Int): List<TPart> {
            val out = ArrayList<TPart>()
            style["wholeTbl"]?.let(out::add)
            val dataRow = ri - (if (flag("firstRow")) 1 else 0)
            val dataCol = ci - (if (flag("firstCol")) 1 else 0)
            if (flag("bandRow") && dataRow >= 0) style[if (dataRow % 2 == 0) "band1H" else "band2H"]?.let(out::add)
            if (flag("bandCol") && dataCol >= 0) style[if (dataCol % 2 == 0) "band1V" else "band2V"]?.let(out::add)
            if (flag("lastCol") && ci == nCols - 1) style["lastCol"]?.let(out::add)
            if (flag("firstCol") && ci == 0) style["firstCol"]?.let(out::add)
            if (flag("lastRow") && ri == nRows - 1) style["lastRow"]?.let(out::add)
            if (flag("firstRow") && ri == 0) style["firstRow"]?.let(out::add)
            return out
        }

        class Cell(val tc: XNode, val ri: Int, val ci: Int, val cs: Int, val rs: Int, val parts: List<TPart>)
        val cells = ArrayList<Cell>()
        rows.forEachIndexed { ri, tr ->
            var ci = 0
            for (tc in tr.children("tc")) {
                if (ci >= nCols) break
                if (tc.bool("hMerge") != true && tc.bool("vMerge") != true) {
                    cells.add(Cell(tc, ri, ci, (tc.int("gridSpan") ?: 1).coerceIn(1, nCols - ci), (tc.int("rowSpan") ?: 1).coerceIn(1, nRows - ri), partsFor(ri, ci)))
                }
                ci++
            }
        }

        fun envFor(cell: Cell): TextEnv {
            var color: Int? = null; var bold: Boolean? = null; var italic: Boolean? = null
            for (p in cell.parts) { p.txColor?.let { color = it }; p.bold?.let { bold = it }; p.italic?.let { italic = it } }
            val chain = listOf(StyleLayer(deck.defaultTextStyle), StyleLayer(null, ParaProps(run = RunProps(color = color, b = bold, i = italic))))
            return TextEnv(ctx.cc, ctx.theme, chain, ctx.slideNum, ctx.cc.scheme("tx1", null))
        }
        fun margins(tc: XNode): FloatArray {
            val p = tc.child("tcPr")
            return floatArrayOf(
                (p?.long("marL") ?: 91440L) / EMU_PER_PT, (p?.long("marT") ?: 45720L) / EMU_PER_PT,
                (p?.long("marR") ?: 91440L) / EMU_PER_PT, (p?.long("marB") ?: 45720L) / EMU_PER_PT,
            )
        }
        fun textOf(cell: Cell, w: Float): TextBlock? {
            val tx = cell.tc.child("txBody") ?: return null
            if (plainText(tx).isBlank()) return null
            val key = TextKey(tx, ctx.slide.index, (w * 10).toInt(), -1)
            return synchronized(textCache) { textCache[key] } ?: buildText(tx, envFor(cell), w, true).also { synchronized(textCache) { textCache[key] = it } }
        }

        // Row heights grow to fit their text, as in PowerPoint.
        val rowH = FloatArray(nRows) { (rows[it].long("h") ?: 0L) / EMU_PER_PT * sx }
        for (cell in cells) if (cell.rs == 1) {
            val m = margins(cell.tc)
            val w = colX[cell.ci + cell.cs] - colX[cell.ci] - m[0] - m[2]
            val t = textOf(cell, max(1f, w)) ?: continue
            rowH[cell.ri] = max(rowH[cell.ri], t.height + m[1] + m[3])
        }
        val rowY = FloatArray(nRows + 1).also { a -> for (i in 0 until nRows) a[i + 1] = a[i] + rowH[i] }

        // Fills and text.
        for (cell in cells) {
            val rect = RectF(frame.left + colX[cell.ci], frame.top + rowY[cell.ri], frame.left + colX[cell.ci + cell.cs], frame.top + rowY[cell.ri + cell.rs])
            val tcPr = cell.tc.child("tcPr")
            val fill = fillIn(tcPr, part, ctx.cc, null, null) ?: cell.parts.lastOrNull { it.fill != null }?.fill
            if (fill != null) paintFill(c, Path().apply { addRect(rect, Path.Direction.CW) }, rect, fill, ctx)
            val m = margins(cell.tc)
            val w = rect.width() - m[0] - m[2]
            val t = textOf(cell, max(1f, w)) ?: continue
            val innerH = rect.height() - m[1] - m[3]
            val dy = when (tcPr?.attr("anchor")) { "ctr" -> (innerH - t.height) / 2f; "b" -> innerH - t.height; else -> 0f }
            t.draw(c, rect.left + m[0], rect.top + m[1] + dy)
        }
        // Borders on top.
        for (cell in cells) {
            val rect = RectF(frame.left + colX[cell.ci], frame.top + rowY[cell.ri], frame.left + colX[cell.ci + cell.cs], frame.top + rowY[cell.ri + cell.rs])
            val tcPr = cell.tc.child("tcPr")
            fun edge(own: String, outer: String, inner: String, isOuter: Boolean): Line? {
                tcPr?.child(own)?.let { return lineFromNode(it, ctx) }
                var res: Line? = null
                for (p in cell.parts) {
                    val k = if (isOuter) outer else inner
                    if (p.borders.containsKey(k)) res = p.borders[k]
                }
                return res
            }
            val lines = listOf(
                edge("lnT", "top", "insideH", cell.ri == 0) to floatArrayOf(rect.left, rect.top, rect.right, rect.top),
                edge("lnB", "bottom", "insideH", cell.ri + cell.rs == nRows) to floatArrayOf(rect.left, rect.bottom, rect.right, rect.bottom),
                edge("lnL", "left", "insideV", cell.ci == 0) to floatArrayOf(rect.left, rect.top, rect.left, rect.bottom),
                edge("lnR", "right", "insideV", cell.ci + cell.cs == nCols) to floatArrayOf(rect.right, rect.top, rect.right, rect.bottom),
            )
            for ((ln, p) in lines) {
                if (ln == null || ln.width <= 0f) continue
                setupStroke(ln)
                c.drawLine(p[0], p[1], p[2], p[3], strokePaint)
                strokePaint.pathEffect = null
            }
        }
    }

    companion object {
        /** Pixels per point encoded in a page->pixel matrix. */
        fun scaleOf(m: android.graphics.Matrix): Float {
            val v = FloatArray(9)
            m.getValues(v)
            return sqrt(v[0] * v[0] + v[3] * v[3]).takeIf { it > 0f } ?: 1f
        }
    }
}
