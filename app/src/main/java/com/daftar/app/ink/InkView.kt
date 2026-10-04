package com.daftar.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The page canvas: vertically stacked pages, pinch-zoom / pan / fling, and every ink tool.
 * Pen input draws; with [penOnly] fingers only navigate (palm rejection). The S Pen side button
 * temporarily switches to [stylusButtonTool]; the pen's eraser end always erases.
 */
@SuppressLint("ViewConstructor")
class InkView(context: Context) : View(context) {

    interface Listener {
        fun onChanged() {}
        fun onPageChanged(current: Int, count: Int) {}
        fun onTextRequest(page: Int, x: Float, y: Float, existing: TextItem?) {}
        fun onSelectionChanged(active: Boolean) {}
        fun onSeek(rec: Int, t: Long) {}
        fun onUndoStateChanged(canUndo: Boolean, canRedo: Boolean) {}
        fun onZoomChanged(percent: Int) {}
    }

    var listener: Listener? = null
    var doc: InkDoc = InkDoc(); private set
    private var source: PageSource? = null

    // ---- tool state (set from the toolbar) ----
    var tool = Tool.PEN
    var penColor = 0xFF1F2937.toInt()
    var penWidth = 2.2f
    var hlColor = 0x66F2C94C
    var hlWidth = 14f
    var shapeColor = 0xFF1F2937.toInt()
    var eraserRadiusDp = 12f
    var penOnly = true
    var stylusButtonTool = Tool.ERASER
    var bgColor = 0xFFF7F6F2.toInt()
    var darkPaper = false

    // ---- audio sync ----
    var recId = 0
    var recClockStart = 0L      // elapsedRealtime at recording start
    var recOffset = 0L          // added to elapsed (for resumed recordings)
    var playRec = 0
    var playPos = 0L
        set(v) { field = v; if (playRec != 0) invalidate() }

    // ---- transform ----
    private val density = resources.displayMetrics.density
    private val margin = 16f * density
    private var scale = 1f
    private var fitScale = 1f
    private var sx = 0f
    private var sy = 0f
    private var laidOut = false
    private var pageTops = FloatArray(0)
    private var maxW = 595f
    private var totalH = 842f
    private val gap = 18f
    var currentPage = 0; private set

    // ---- input ----
    private enum class Mode { NONE, DRAW, NAV, SEL_MOVE, SEL_SCALE }
    private var mode = Mode.NONE
    private var activeTool = Tool.PEN
    private var drawPage = -1
    private var pts = FloatArray(512)
    private var npts = 0
    private var lastFx = 0f
    private var lastFy = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastTapTime = 0L
    private var eraseUndoPushed = false
    private var eraserScreen: Pair<Float, Float>? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return mode == Mode.NAV }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(d.focusX, d.focusY, d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false; settleSoon() }
    }).apply { isQuickScaleEnabled = false }

    // ---- selection ----
    private class Sel(val page: Int, val strokes: List<Stroke>, val texts: List<TextItem>, val images: List<ImageItem>, val box: RectF) {
        var dx = 0f; var dy = 0f; var s = 1f
        var changed = false
        fun map(x: Float, y: Float) = Pair(box.left + (x - box.left) * s + dx, box.top + (y - box.top) * s + dy)
    }
    private var sel: Sel? = null
    private var selGrabX = 0f
    private var selGrabY = 0f
    val hasSelection get() = sel != null

    // ---- undo ----
    private val undo = ArrayDeque<List<InkPage>>()
    private val redo = ArrayDeque<List<InkPage>>()

    // ---- background rendering (PDF / slides) ----
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val bgCache = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {}
    private val pending = HashSet<String>()
    private class Tile(val page: Int, val scale: Float, val doc: RectF, val bmp: Bitmap)
    private val tiles = HashMap<Int, Tile>()
    private var generation = 0

    // ---- paints ----
    private val pagePaint = Paint()
    private val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFF3B82F6.toInt()
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }
    private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFF6B7280.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 5 * density), 0f)
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x996B7280.toInt() }

    init {
        isFocusable = true
    }

    // =====================================================================================
    // Document
    // =====================================================================================

    fun setDocument(d: InkDoc, src: PageSource?) {
        doc = d; source = src
        generation++
        bgCache.values.forEach { it.recycle() }; bgCache.clear(); tiles.clear(); pending.clear()
        undo.clear(); redo.clear(); sel = null
        relayout()
        if (width > 0) { computeFit(); scale = fitScale; sx = 0f; sy = 0f; laidOut = true }
        notifyUndo(); invalidate()
    }

    fun release() {
        exec.shutdownNow()
        bgCache.values.forEach { it.recycle() }; bgCache.clear()
        tiles.values.forEach { it.bmp.recycle() }; tiles.clear()
    }

    private fun relayout() {
        val n = doc.pages.size
        pageTops = FloatArray(n)
        var y = 0f
        maxW = 1f
        for (i in 0 until n) {
            pageTops[i] = y
            y += doc.pages[i].h + gap
            maxW = max(maxW, doc.pages[i].w)
        }
        totalH = max(1f, y - gap)
    }

    private fun computeFit() {
        if (width == 0) return
        fitScale = (width - 2 * margin) / maxW
    }

    private fun pageLeft(i: Int) = (maxW - doc.pages[i].w) / 2f
    private fun offX(): Float { val cw = maxW * scale + 2 * margin; return if (cw < width) (width - cw) / 2f else 0f }
    private fun toScreenX(docX: Float) = margin + docX * scale - sx + offX()
    private fun toScreenY(docY: Float) = margin + docY * scale - sy
    private fun toDocX(x: Float) = (x - margin + sx - offX()) / scale
    private fun toDocY(y: Float) = (y - margin + sy) / scale

    private fun pageScreenRect(i: Int): RectF {
        val l = toScreenX(pageLeft(i)); val t = toScreenY(pageTops[i])
        return RectF(l, t, l + doc.pages[i].w * scale, t + doc.pages[i].h * scale)
    }

    /** Page index under a doc-Y coordinate (or -1 if between/outside pages). */
    private fun pageAtDoc(docX: Float, docY: Float, strict: Boolean = true): Int {
        for (i in pageTops.indices) {
            val top = pageTops[i]; val p = doc.pages[i]
            val l = pageLeft(i)
            if (docY >= top - (if (strict) 0f else gap / 2) && docY <= top + p.h + (if (strict) 0f else gap / 2)) {
                if (!strict || (docX >= l && docX <= l + p.w)) return i
            }
        }
        return -1
    }

    /** Screen point -> (page, pageX, pageY). */
    private fun hit(x: Float, y: Float, strict: Boolean = true): Triple<Int, Float, Float>? {
        val dx = toDocX(x); val dy = toDocY(y)
        val i = pageAtDoc(dx, dy, strict)
        if (i < 0) return null
        return Triple(i, dx - pageLeft(i), dy - pageTops[i])
    }

    private fun clamp() {
        val cw = maxW * scale + 2 * margin
        val ch = totalH * scale + 2 * margin
        sx = if (cw <= width) 0f else sx.coerceIn(0f, cw - width)
        sy = sy.coerceIn(0f, max(0f, ch - height))
    }

    private fun updateCurrentPage() {
        if (pageTops.isEmpty()) return
        val cy = toDocY(height * 0.4f)
        var cur = 0
        for (i in pageTops.indices) if (pageTops[i] <= cy) cur = i
        if (cur != currentPage) { currentPage = cur; listener?.onPageChanged(cur, doc.pages.size) }
    }

    fun goToPage(i: Int) {
        if (i !in pageTops.indices) return
        commitSelection()
        scroller.forceFinished(true)
        sy = pageTops[i] * scale
        clamp(); updateCurrentPage()
        currentPage = i; listener?.onPageChanged(i, doc.pages.size)
        settleSoon(); invalidate()
    }

    fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val ns = (scale * factor).coerceIn(fitScale * 0.25f, fitScale * 8f)
        val dx = toDocX(fx); val dy = toDocY(fy)
        scale = ns
        // keep doc point under the focus
        sx = margin + dx * scale + offX() - fx
        sy = margin + dy * scale - fy
        clamp(); updateCurrentPage(); notifyZoom(); invalidate()
    }

    fun zoomToFit() { val cy = toDocY(0f); scale = fitScale; sy = cy * scale; clamp(); settleSoon(); notifyZoom(); invalidate() }

    /** Zoom around the view centre (toolbar buttons). */
    fun zoomBy(factor: Float) { zoomAt(width / 2f, height / 2f, factor); settleSoon() }

    val zoomPercent: Int get() = if (fitScale > 0f) (scale / fitScale * 100f).toInt() else 100

    private var lastZoom = -1
    private fun notifyZoom() { val z = zoomPercent; if (z != lastZoom) { lastZoom = z; listener?.onZoomChanged(z) } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val zoom = if (laidOut && fitScale > 0) scale / fitScale else 1f
        val topDoc = if (laidOut) toDocY(0f) else 0f
        computeFit()
        scale = fitScale * zoom
        sy = max(0f, topDoc * scale)
        laidOut = true
        clamp(); tiles.clear(); settleSoon(); notifyZoom(); invalidate()
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        // Compose hosts views with clipChildren=false: never paint outside our own bounds.
        c.clipRect(0, 0, width, height)
        c.drawColor(bgColor)
        if (doc.pages.isEmpty()) return
        pagePaint.color = doc.paperColor
        borderPaint.color = if (darkPaper) 0xFF2E333B.toInt() else 0xFFDDDAD3.toInt()
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        for (i in doc.pages.indices) {
            val r = pageScreenRect(i)
            if (!RectF.intersects(r, view)) continue
            val page = doc.pages[i]
            c.drawRect(r, pagePaint)
            val src = source
            if (src != null) drawBackground(c, i, r) else {
                c.save(); c.translate(r.left, r.top); c.scale(scale, scale); InkRender.drawPaper(c, page, darkPaper); c.restore()
            }
            c.drawRect(r, borderPaint)

            c.save()
            c.clipRect(r)
            c.translate(r.left, r.top); c.scale(scale, scale)
            // visible region of this page in page coords, for culling
            val vis = RectF((max(0f, -r.left)) / scale, (max(0f, -r.top)) / scale, (width - r.left) / scale, (height - r.top) / scale)
            for (im in page.images) if (RectF.intersects(im.bounds(), vis)) InkRender.drawImage(c, im)
            for (s in page.strokes) {
                if (!RectF.intersects(s.bounds(), vis)) continue
                val ghost = playRec != 0 && s.rec == playRec && s.t > playPos
                InkRender.drawStroke(c, s, if (ghost) 0.18f else 1f)
            }
            for (t in page.texts) InkRender.drawText(c, t)
            sel?.let { if (it.page == i) drawFloating(c, it) }
            if (mode == Mode.DRAW && drawPage == i && npts > 0) drawLive(c)
            c.restore()
        }
        sel?.let { drawSelectionChrome(c, it) }
        eraserScreen?.let { (x, y) -> c.drawCircle(x, y, eraserRadiusDp * density, eraserPaint) }
    }

    private fun drawFloating(c: Canvas, s: Sel) {
        c.save()
        c.translate(s.box.left + s.dx, s.box.top + s.dy)
        c.scale(s.s, s.s)
        c.translate(-s.box.left, -s.box.top)
        for (im in s.images) InkRender.drawImage(c, im)
        for (st in s.strokes) InkRender.drawStroke(c, st)
        for (t in s.texts) InkRender.drawText(c, t)
        c.restore()
    }

    private fun selScreenBox(s: Sel): RectF {
        val (l, t) = s.map(s.box.left, s.box.top)
        val (r, b) = s.map(s.box.right, s.box.bottom)
        val pl = toScreenX(pageLeft(s.page)); val pt = toScreenY(pageTops[s.page])
        return RectF(pl + l * scale, pt + t * scale, pl + r * scale, pt + b * scale)
    }

    private fun drawSelectionChrome(c: Canvas, s: Sel) {
        val b = selScreenBox(s)
        b.inset(-6 * density, -6 * density)
        c.drawRect(b, selPaint)
        c.drawCircle(b.right, b.bottom, 9 * density, handlePaint)
        c.drawCircle(b.right, b.bottom, 5 * density, handleInner)
    }

    private fun liveStroke(): Stroke {
        val arr = pts.copyOf(npts * 3)
        return when (activeTool) {
            Tool.HIGHLIGHTER -> Stroke(Tool.HIGHLIGHTER, hlColor, hlWidth, arr)
            Tool.SHAPE -> Stroke(Tool.SHAPE, shapeColor, penWidth, arr)
            else -> Stroke(Tool.PEN, penColor, penWidth, arr)
        }
    }

    private val lassoPath = Path()
    private fun drawLive(c: Canvas) {
        if (activeTool == Tool.LASSO) {
            lassoPath.reset()
            lassoPath.moveTo(pts[0], pts[1])
            for (k in 1 until npts) lassoPath.lineTo(pts[k * 3], pts[k * 3 + 1])
            lassoPaint.strokeWidth = 1.5f * density / scale
            lassoPaint.pathEffect = DashPathEffect(floatArrayOf(6 * density / scale, 5 * density / scale), 0f)
            c.drawPath(lassoPath, lassoPaint)
        } else if (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER || activeTool == Tool.SHAPE) {
            InkRender.drawStroke(c, liveStroke())
        }
    }

    // ---- PDF / slide backgrounds ----

    private fun maxCacheWidth(i: Int): Int {
        val p = doc.pages[i]
        // cap full-page bitmaps at ~5 MP; zoom beyond that is served by tiles
        return sqrt(5_000_000f * p.w / p.h).toInt()
    }

    private fun drawBackground(c: Canvas, i: Int, r: RectF) {
        val want = min((doc.pages[i].w * scale).toInt(), maxCacheWidth(i)).coerceAtLeast(64)
        val bmp = bgCache[i]
        if (bmp != null && !bmp.isRecycled) c.drawBitmap(bmp, null, r, bmpPaint)
        if (bmp == null || (!scaling && abs(bmp.width - want) > want * 0.2f)) requestPage(i, want)
        val t = tiles[i]
        if (t != null && t.scale == scale && !scaling) {
            val pl = r.left; val pt = r.top
            c.drawBitmap(t.bmp, null, RectF(pl + t.doc.left * scale, pt + t.doc.top * scale, pl + t.doc.right * scale, pt + t.doc.bottom * scale), bmpPaint)
        }
    }

    private fun requestPage(i: Int, w: Int) {
        val src = source ?: return
        val key = "$i:$w"
        if (!pending.add(key)) return
        val p = doc.pages[i]
        val h = (w * p.h / p.w).toInt().coerceAtLeast(1)
        val gen = generation
        exec.execute {
            val b = runCatching {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                val m = Matrix(); m.setScale(w / p.w, h / p.h)
                src.render(i, bmp, m); bmp
            }.getOrNull()
            main.post {
                pending.remove(key)
                if (gen != generation || b == null) { b?.recycle(); return@post }
                bgCache.put(i, b)?.let { if (it !== b) it.recycle() }
                while (bgCache.size > 6) {
                    val eldest = bgCache.entries.first()
                    if (abs(eldest.key - currentPage) <= 1 && bgCache.size <= 8) break
                    bgCache.remove(eldest.key)?.recycle()
                }
                invalidate()
            }
        }
    }

    private val settle = Runnable { requestTiles() }
    private fun settleSoon() { main.removeCallbacks(settle); main.postDelayed(settle, 180) }

    /** When zoomed past the full-page cache resolution, render just the visible part sharply. */
    private fun requestTiles() {
        val src = source ?: return
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val visible = HashSet<Int>()
        for (i in doc.pages.indices) {
            val r = pageScreenRect(i)
            if (!RectF.intersects(r, view)) continue
            visible.add(i)
            val p = doc.pages[i]
            if (p.w * scale <= maxCacheWidth(i) * 1.1f) { tiles.remove(i)?.bmp?.recycle(); continue }
            val inter = RectF(r); inter.intersect(view)
            val tw = inter.width().toInt(); val th = inter.height().toInt()
            if (tw < 8 || th < 8) continue
            val docRect = RectF((inter.left - r.left) / scale, (inter.top - r.top) / scale, (inter.right - r.left) / scale, (inter.bottom - r.top) / scale)
            val sc = scale
            val gen = generation
            val left = r.left - inter.left; val top = r.top - inter.top
            exec.execute {
                val b = runCatching {
                    val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix(); m.setScale(sc, sc); m.postTranslate(left, top)
                    src.render(i, bmp, m); bmp
                }.getOrNull()
                main.post {
                    if (gen != generation || b == null || sc != scale) { b?.recycle(); return@post }
                    tiles.put(i, Tile(i, sc, docRect, b))?.bmp?.recycle()
                    invalidate()
                }
            }
        }
        tiles.keys.filter { it !in visible }.forEach { tiles.remove(it)?.bmp?.recycle() }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat()
            clamp(); updateCurrentPage(); postInvalidateOnAnimation()
            if (scroller.isFinished) settleSoon()
        }
    }

    // =====================================================================================
    // Input
    // =====================================================================================

    private fun isPen(e: MotionEvent, idx: Int = 0): Boolean {
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc.pages.isEmpty()) return false
        if (mode == Mode.NAV || e.actionMasked == MotionEvent.ACTION_DOWN) scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW && !isPen(e, 0)) {
                    // second finger: user wants to zoom, not draw
                    npts = 0; mode = Mode.NAV
                    eraserScreen = null
                }
                if (mode == Mode.NAV) { scaleDetector.onTouchEvent(e); focus(e, -1) }
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.NAV) focus(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> { npts = 0; mode = Mode.NONE; eraserScreen = null; velocity?.recycle(); velocity = null; invalidate() }
        }
        return true
    }

    private fun focus(e: MotionEvent, skip: Int) {
        var fx = 0f; var fy = 0f; var n = 0
        for (k in 0 until e.pointerCount) if (k != skip) { fx += e.getX(k); fy += e.getY(k); n++ }
        if (n > 0) { lastFx = fx / n; lastFy = fy / n }
    }

    private fun onDown(e: MotionEvent) {
        scroller.forceFinished(true)
        downX = e.x; downY = e.y; downTime = SystemClock.uptimeMillis(); moved = false
        velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        val pen = isPen(e)
        if (pen) requestUnbufferedDispatch(e)

        // selection handles take priority for any pointer
        sel?.let { s ->
            val b = selScreenBox(s); b.inset(-6 * density, -6 * density)
            if (hypot(e.x - b.right, e.y - b.bottom) < 26 * density) { mode = Mode.SEL_SCALE; return }
            if (b.contains(e.x, e.y) && (pen || penOnly || tool == Tool.LASSO || tool == Tool.HAND)) {
                mode = Mode.SEL_MOVE; selGrabX = e.x; selGrabY = e.y; return
            }
            commitSelection()
        }

        val buttonDown = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 || (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        activeTool = when {
            e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            pen && buttonDown -> stylusButtonTool
            else -> tool
        }
        val drawWithFinger = !pen && !penOnly && activeTool != Tool.HAND
        if ((pen && activeTool != Tool.HAND) || drawWithFinger) {
            mode = Mode.DRAW
            startTool(e)
        } else {
            mode = Mode.NAV
            lastFx = e.x; lastFy = e.y
        }
    }

    private fun startTool(e: MotionEvent) {
        npts = 0; eraseUndoPushed = false
        val h = hit(e.x, e.y)
        drawPage = h?.first ?: -1
        when (activeTool) {
            Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.LASSO -> if (h != null) addPoint(h.second, h.third, pressureOf(e, -1))
            Tool.ERASER -> { eraserScreen = e.x to e.y; eraseAt(e.x, e.y) }
            Tool.TEXT -> {}
        }
        invalidate()
    }

    private fun pressureOf(e: MotionEvent, hIdx: Int): Float {
        if (!isPen(e)) return 0.6f
        return if (hIdx >= 0) e.getHistoricalPressure(hIdx) else e.pressure
    }

    private fun addPoint(x: Float, y: Float, p: Float) {
        if (npts > 0) {
            val lx = pts[(npts - 1) * 3]; val ly = pts[(npts - 1) * 3 + 1]
            if (hypot(x - lx, y - ly) < 0.6f / scale * density) return
        }
        if ((npts + 1) * 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[npts * 3] = (x * 10f).toInt() / 10f
        pts[npts * 3 + 1] = (y * 10f).toInt() / 10f
        pts[npts * 3 + 2] = (p * 100f).toInt() / 100f
        npts++
    }

    private fun onMove(e: MotionEvent) {
        velocity?.addMovement(e)
        if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
        when (mode) {
            Mode.DRAW -> {
                when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.LASSO -> if (drawPage >= 0) {
                        val pl = pageLeft(drawPage); val pt = pageTops[drawPage]
                        for (k in 0 until e.historySize) {
                            addPoint(toDocX(e.getHistoricalX(k)) - pl, toDocY(e.getHistoricalY(k)) - pt, pressureOf(e, k))
                        }
                        addPoint(toDocX(e.x) - pl, toDocY(e.y) - pt, pressureOf(e, -1))
                    }
                    Tool.ERASER -> {
                        for (k in 0 until e.historySize) eraseAt(e.getHistoricalX(k), e.getHistoricalY(k))
                        eraseAt(e.x, e.y); eraserScreen = e.x to e.y
                    }
                }
                invalidate()
            }
            Mode.NAV -> {
                if (scaling || e.pointerCount > 1) {
                    var fx = 0f; var fy = 0f
                    for (k in 0 until e.pointerCount) { fx += e.getX(k); fy += e.getY(k) }
                    fx /= e.pointerCount; fy /= e.pointerCount
                    sx -= fx - lastFx; sy -= fy - lastFy; lastFx = fx; lastFy = fy
                } else {
                    sx -= e.x - lastFx; sy -= e.y - lastFy; lastFx = e.x; lastFy = e.y
                }
                clamp(); updateCurrentPage(); invalidate()
            }
            Mode.SEL_MOVE -> sel?.let { s ->
                s.dx += (e.x - selGrabX) / scale; s.dy += (e.y - selGrabY) / scale
                selGrabX = e.x; selGrabY = e.y; s.changed = true; invalidate()
            }
            Mode.SEL_SCALE -> sel?.let { s ->
                val b = selScreenBox(s)
                val l = b.left; val t = b.top
                val origW = s.box.width() * scale; val origH = s.box.height() * scale
                val ns = max((e.x - l) / max(origW, 1f), (e.y - t) / max(origH, 1f)).coerceIn(0.15f, 8f)
                s.s = ns; s.changed = true; invalidate()
            }
            Mode.NONE -> {}
        }
    }

    private fun onUp(e: MotionEvent) {
        val wasTap = !moved && SystemClock.uptimeMillis() - downTime < 350
        when (mode) {
            Mode.DRAW -> {
                when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> if (drawPage >= 0 && npts > 0) commitStroke(liveStroke())
                    Tool.SHAPE -> if (drawPage >= 0 && npts > 1) commitStroke(recognizeShape(liveStroke()))
                    Tool.LASSO -> if (drawPage >= 0 && npts > 2) lassoSelect()
                    Tool.TEXT -> if (wasTap) textTap(e.x, e.y)
                    Tool.ERASER -> { eraserScreen = null; if (eraseUndoPushed) changed() }
                }
                npts = 0
            }
            Mode.NAV -> {
                if (wasTap) navTap(e.x, e.y)
                else velocity?.let { v ->
                    v.computeCurrentVelocity(1000)
                    val vx = v.xVelocity; val vy = v.yVelocity
                    if (hypot(vx, vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity * 2) {
                        val cw = maxW * scale + 2 * margin; val ch = totalH * scale + 2 * margin
                        scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, max(0f, cw - width).toInt(), 0, max(0f, ch - height).toInt())
                        postInvalidateOnAnimation()
                    } else settleSoon()
                }
            }
            Mode.SEL_MOVE, Mode.SEL_SCALE -> invalidate()
            Mode.NONE -> {}
        }
        mode = Mode.NONE
        velocity?.recycle(); velocity = null
        invalidate()
    }

    private fun navTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val h = hit(x, y)
        if (playRec != 0 && h != null) {
            nearestStroke(h.first, h.second, h.third, 14f * density / scale)?.let { s ->
                if (s.rec != 0 && s.t >= 0) { listener?.onSeek(s.rec, s.t); return }
            }
        }
        if (tool == Tool.TEXT && h != null) { textTap(x, y); return }
        if (h != null) textAt(h.first, h.second, h.third)?.let { listener?.onTextRequest(h.first, it.x, it.y, it); return }
        if (now - lastTapTime < 300) {
            // double tap: toggle fit <-> 2x
            if (scale > fitScale * 1.2f) zoomAt(x, y, fitScale / scale) else zoomAt(x, y, 2f)
            settleSoon(); lastTapTime = 0
        } else lastTapTime = now
    }

    private fun textTap(x: Float, y: Float) {
        val h = hit(x, y) ?: return
        listener?.onTextRequest(h.first, h.second, h.third, textAt(h.first, h.second, h.third))
    }

    private fun textAt(page: Int, x: Float, y: Float): TextItem? =
        doc.pages[page].texts.lastOrNull { InkRender.layout(it); it.bounds().contains(x, y) }

    // =====================================================================================
    // Edits
    // =====================================================================================

    private fun snapshot() = doc.pages.toList()

    private fun pushUndo() {
        undo.addLast(snapshot()); if (undo.size > 80) undo.removeFirst()
        redo.clear(); notifyUndo()
    }

    private fun notifyUndo() = listener?.onUndoStateChanged(undo.isNotEmpty(), redo.isNotEmpty())

    private fun changed() { listener?.onChanged(); invalidate() }

    private fun setPage(i: Int, p: InkPage) {
        doc.pages = doc.pages.toMutableList().also { it[i] = p }
    }

    fun undo() {
        commitSelection()
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(snapshot()); doc.pages = prev
        relayout(); clamp(); notifyUndo(); changed()
    }

    fun redo() {
        commitSelection()
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot()); doc.pages = next
        relayout(); clamp(); notifyUndo(); changed()
    }

    private fun stampTime(s: Stroke): Stroke =
        if (recId == 0) s else Stroke(s.tool, s.color, s.width, s.pts, recId, SystemClock.elapsedRealtime() - recClockStart + recOffset)

    private fun commitStroke(s: Stroke) {
        pushUndo()
        val p = doc.pages[drawPage]
        setPage(drawPage, p.copy(strokes = p.strokes + stampTime(s)))
        changed()
    }

    private fun eraseAt(x: Float, y: Float) {
        val h = hit(x, y, strict = false) ?: return
        val r = eraserRadiusDp * density / scale
        val page = doc.pages[h.first]
        val keep = page.strokes.filterNot { strokeNear(it, h.second, h.third, r) }
        if (keep.size != page.strokes.size) {
            if (!eraseUndoPushed) { pushUndo(); eraseUndoPushed = true }
            setPage(h.first, page.copy(strokes = keep))
            invalidate()
        }
    }

    private fun strokeNear(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val b = s.bounds()
        if (x < b.left - r || x > b.right + r || y < b.top - r || y > b.bottom + r) return false
        val p = s.pts
        val rr = r + s.width / 2
        if (p.size == 3) return hypot(p[0] - x, p[1] - y) < rr
        var i = 0
        while (i + 5 < p.size) {
            if (segDist(x, y, p[i], p[i + 1], p[i + 3], p[i + 4]) < rr) return true
            i += 3
        }
        return false
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun nearestStroke(page: Int, x: Float, y: Float, r: Float): Stroke? =
        doc.pages[page].strokes.lastOrNull { strokeNear(it, x, y, r) }

    // ---- shapes ----

    private fun recognizeShape(s: Stroke): Stroke {
        val p = s.pts; val n = p.size / 3
        if (n < 2) return s
        val b = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var len = 0f
        for (i in 0 until n) {
            b.union(p[i * 3], p[i * 3 + 1])
            if (i > 0) len += hypot(p[i * 3] - p[(i - 1) * 3], p[i * 3 + 1] - p[(i - 1) * 3 + 1])
        }
        val sx0 = p[0]; val sy0 = p[1]; val ex = p[(n - 1) * 3]; val ey = p[(n - 1) * 3 + 1]
        val chord = hypot(ex - sx0, ey - sy0)
        fun mk(vararg xy: Float): Stroke {
            val out = FloatArray(xy.size / 2 * 3)
            for (k in 0 until xy.size / 2) { out[k * 3] = xy[k * 2]; out[k * 3 + 1] = xy[k * 2 + 1]; out[k * 3 + 2] = -1f }
            return Stroke(Tool.SHAPE, s.color, s.width, out)
        }
        // straight line?
        var maxDev = 0f
        for (i in 0 until n) maxDev = max(maxDev, segDist(p[i * 3], p[i * 3 + 1], sx0, sy0, ex, ey))
        if (chord > 0 && maxDev < chord * 0.09f && chord > len * 0.85f) return mk(sx0, sy0, ex, ey)
        val size = max(b.width(), b.height())
        if (chord > size * 0.3f) return Stroke(Tool.SHAPE, s.color, s.width, s.pts.copyOf().also { a -> for (k in 0 until n) a[k * 3 + 2] = -1f })
        // closed shape: compare area to bounding box
        var area = 0f
        for (i in 0 until n) { val j = (i + 1) % n; area += p[i * 3] * p[j * 3 + 1] - p[j * 3] * p[i * 3 + 1] }
        val ratio = abs(area) / 2f / max(1f, b.width() * b.height())
        return when {
            ratio > 0.86f -> mk(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom, b.left, b.top)
            ratio > 0.62f -> {
                val cx = b.centerX(); val cy = b.centerY(); val rx = b.width() / 2; val ry = b.height() / 2
                val xy = FloatArray(130)
                for (k in 0..64) { val a = (k / 64.0 * 2 * Math.PI); xy[k * 2] = cx + rx * cos(a).toFloat(); xy[k * 2 + 1] = cy + ry * sin(a).toFloat() }
                mk(*xy)
            }
            else -> {
                // triangle: farthest pair + farthest from that line
                var a = 0; var bb = 0; var best = 0f
                val step = max(1, n / 60)
                for (i in 0 until n step step) for (j in i + 1 until n step step) {
                    val d = hypot(p[i * 3] - p[j * 3], p[i * 3 + 1] - p[j * 3 + 1]); if (d > best) { best = d; a = i; bb = j }
                }
                var c = 0; var far = 0f
                for (i in 0 until n) { val d = segDist(p[i * 3], p[i * 3 + 1], p[a * 3], p[a * 3 + 1], p[bb * 3], p[bb * 3 + 1]); if (d > far) { far = d; c = i } }
                mk(p[a * 3], p[a * 3 + 1], p[bb * 3], p[bb * 3 + 1], p[c * 3], p[c * 3 + 1], p[a * 3], p[a * 3 + 1])
            }
        }
    }

    // ---- lasso / selection ----

    private fun inPoly(x: Float, y: Float, poly: FloatArray, n: Int): Boolean {
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 3]; val yi = poly[i * 3 + 1]; val xj = poly[j * 3]; val yj = poly[j * 3 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun lassoSelect() {
        val page = doc.pages[drawPage]
        val poly = pts; val n = npts
        val strokes = page.strokes.filter { s ->
            val m = s.pts.size / 3
            var inside = 0
            for (k in 0 until m) if (inPoly(s.pts[k * 3], s.pts[k * 3 + 1], poly, n)) inside++
            m > 0 && inside >= max(1, (m * 0.5f).toInt())
        }
        val texts = page.texts.filter { InkRender.layout(it); val b = it.bounds(); inPoly(b.centerX(), b.centerY(), poly, n) }
        val images = page.images.filter { val b = it.bounds(); inPoly(b.centerX(), b.centerY(), poly, n) }
        if (strokes.isEmpty() && texts.isEmpty() && images.isEmpty()) return
        lift(drawPage, strokes, texts, images)
    }

    private fun lift(pageIdx: Int, strokes: List<Stroke>, texts: List<TextItem>, images: List<ImageItem>) {
        pushUndo()
        val page = doc.pages[pageIdx]
        val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        strokes.forEach { box.union(it.bounds()) }
        texts.forEach { InkRender.layout(it); box.union(it.bounds()) }
        images.forEach { box.union(it.bounds()) }
        val sIds = strokes.toHashSet()
        val tIds = texts.map { it.id }.toHashSet(); val iIds = images.map { it.id }.toHashSet()
        setPage(pageIdx, page.copy(
            strokes = page.strokes.filterNot { it in sIds },
            texts = page.texts.filterNot { it.id in tIds },
            images = page.images.filterNot { it.id in iIds },
        ))
        sel = Sel(pageIdx, strokes, texts, images, box)
        listener?.onSelectionChanged(true)
        invalidate()
    }

    private fun mappedSelection(s: Sel): Triple<List<Stroke>, List<TextItem>, List<ImageItem>> {
        if (!s.changed) return Triple(s.strokes, s.texts, s.images)
        val st = s.strokes.map { it.mapped({ x, y -> s.map(x, y) }, s.s) }
        val tx = s.texts.map { t -> val (x, y) = s.map(t.x, t.y); t.copy(x = x, y = y, w = t.w * s.s, size = t.size * s.s) }
        val im = s.images.map { i -> val (x, y) = s.map(i.x, i.y); i.copy(x = x, y = y, w = i.w * s.s, h = i.h * s.s).also { it.bmp = i.bmp } }
        return Triple(st, tx, im)
    }

    fun commitSelection() {
        val s = sel ?: return
        sel = null
        val (st, tx, im) = mappedSelection(s)
        val page = doc.pages[s.page]
        setPage(s.page, page.copy(strokes = page.strokes + st, texts = page.texts + tx, images = page.images + im))
        if (!s.changed) undo.removeLastOrNull()   // nothing changed: the lift was not a real edit
        notifyUndo()
        listener?.onSelectionChanged(false)
        changed()
    }

    fun deleteSelection() {
        if (sel == null) return
        sel = null
        listener?.onSelectionChanged(false)
        changed()
    }

    fun recolorSelection(color: Int) {
        val s = sel ?: return
        val ns = Sel(s.page, s.strokes.map { if (it.tool == Tool.HIGHLIGHTER) it.withColor((color and 0x00FFFFFF) or 0x66000000) else it.withColor(color) },
            s.texts.map { it.copy(color = color) }, s.images, s.box).also { it.dx = s.dx; it.dy = s.dy; it.s = s.s; it.changed = true }
        sel = ns; invalidate()
    }

    fun duplicateSelection() {
        val s = sel ?: return
        val (st, tx, im) = mappedSelection(s)
        val page = doc.pages[s.page]
        setPage(s.page, page.copy(strokes = page.strokes + st, texts = page.texts + tx, images = page.images + im))
        val off = 24f
        val now = System.nanoTime()
        sel = Sel(s.page,
            st.map { it.mapped({ x, y -> (x + off) to (y + off) }) },
            tx.mapIndexed { k, t -> t.copy(id = now + k, x = t.x + off, y = t.y + off) },
            im.mapIndexed { k, i -> i.copy(id = now + 1000 + k, x = i.x + off, y = i.y + off).also { it.bmp = i.bmp } },
            RectF(s.box).apply { offset(s.dx + off, s.dy + off); right = left + s.box.width() * s.s; bottom = top + s.box.height() * s.s },
        ).also { it.changed = true }
        invalidate()
    }

    /** Ink strokes of the current selection, mapped to their on-page position (for handwriting recognition). */
    fun selectedStrokes(): List<Stroke> = sel?.let { mappedSelection(it).first.filter { s -> s.tool == Tool.PEN } } ?: emptyList()

    fun selectionBounds(): RectF? = sel?.let { s -> val (l, t) = s.map(s.box.left, s.box.top); val (r, b) = s.map(s.box.right, s.box.bottom); RectF(l, t, r, b) }

    /** Replace the selected pen strokes with a typed text box at the same place. */
    fun replaceSelectionWithText(text: String) {
        val s = sel ?: return
        val b = selectionBounds() ?: return
        val pens = s.strokes.filter { it.tool == Tool.PEN }
        val lines = text.lines().size.coerceAtLeast(1)
        val size = (b.height() / lines / 1.35f).coerceIn(10f, 48f)
        val item = TextItem(System.nanoTime(), b.left, b.top, max(b.width(), size * 4), text, size, pens.firstOrNull()?.color ?: penColor)
        val (st, tx, im) = mappedSelection(s)
        val keep = st.filter { it.tool != Tool.PEN }
        sel = null
        val page = doc.pages[s.page]
        setPage(s.page, page.copy(strokes = page.strokes + keep, texts = page.texts + tx + item, images = page.images + im))
        notifyUndo(); listener?.onSelectionChanged(false); changed()
    }

    // ---- text & images ----

    fun upsertText(page: Int, item: TextItem) {
        commitSelection()
        pushUndo()
        val p = doc.pages[page]
        val exists = p.texts.any { it.id == item.id }
        setPage(page, p.copy(texts = if (exists) p.texts.map { if (it.id == item.id) item else it } else p.texts + item))
        changed()
    }

    fun deleteText(page: Int, id: Long) {
        pushUndo()
        val p = doc.pages[page]
        setPage(page, p.copy(texts = p.texts.filterNot { it.id == id }))
        changed()
    }

    /** Text dropped at the visible centre of the current page (dictation results). */
    fun addTextAtCenter(text: String, size: Float, color: Int, font: String) {
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val p = doc.pages[i]
        val cy = (toDocY(height / 3f) - pageTops[i]).coerceIn(40f, p.h - 60f)
        upsertText(i, TextItem(System.nanoTime(), 40f, cy, p.w - 80f, text, size, color, font))
    }

    fun addImage(b: Bitmap) {
        commitSelection()
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val p = doc.pages[i]
        val w = min(p.w * 0.6f, b.width.toFloat())
        val h = w * b.height / b.width
        val cy = (toDocY(height / 2f) - pageTops[i] - h / 2).coerceIn(10f, max(10f, p.h - h - 10f))
        val item = ImageItem(System.nanoTime(), (p.w - w) / 2, cy, w, h, ImageItem.encode(b)).also { it.bmp = b }
        // Floats as a selection until placed; the undo point is "before the image".
        pushUndo()
        sel = Sel(i, emptyList(), emptyList(), listOf(item), item.bounds()).also { it.changed = true }
        listener?.onSelectionChanged(true)
        changed()
    }

    // ---- pages ----

    fun addPage(after: Int, paper: String) {
        commitSelection()
        pushUndo()
        val ref = doc.pages.getOrNull(after) ?: InkPage()
        doc.pages = doc.pages.toMutableList().also { it.add(after + 1, InkPage(ref.w, ref.h, paper)) }
        relayout(); changed()
        goToPage(after + 1)
    }

    fun deletePage(i: Int) {
        if (doc.pages.size <= 1) return
        commitSelection()
        pushUndo()
        doc.pages = doc.pages.toMutableList().also { it.removeAt(i) }
        relayout(); clamp(); updateCurrentPage(); changed()
        listener?.onPageChanged(currentPage, doc.pages.size)
    }

    fun setPaper(paper: String, all: Boolean) {
        pushUndo()
        doc.pages = doc.pages.mapIndexed { i, p -> if (all || i == currentPage) p.copy(paper = paper) else p }
        changed()
    }

    fun clearPage(i: Int) {
        commitSelection()
        if (doc.pages[i].isEmpty()) return
        pushUndo()
        setPage(i, doc.pages[i].copy(strokes = emptyList(), texts = emptyList(), images = emptyList()))
        changed()
    }

    fun pageStrokes(i: Int): List<Stroke> = doc.pages.getOrNull(i)?.strokes?.filter { it.tool == Tool.PEN } ?: emptyList()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacks(settle)
    }
}
