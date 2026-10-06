package com.daftar.app.ink

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The page canvas: vertically stacked pages (or one infinite whiteboard), pinch / pen / mouse zoom, pan / fling, and every
 * ink tool. Pen input draws; with [penOnly] fingers only navigate (palm rejection). The S Pen side button temporarily
 * switches to [stylusButtonTool] (with the Hand tool it zooms); the pen's eraser end always erases.
 * Accepts drops (library files, links, text, images) at the drop position.
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
        /** A link chip was tapped. */
        fun onLinkOpen(link: LinkItem) {}
        /** A link chip was long-pressed at view position ([x], [y]) px. */
        fun onLinkMenu(page: Int, link: LinkItem, x: Float, y: Float) {}
        /** Something was dropped that could not be read (missing file, undecodable image). */
        fun onDropFailed() {}
        /** The selected / edited text box changed (null = none). Not called for every move frame. */
        fun onTextBox(ui: TextBoxUi?) {}
        /** A text box was long-pressed at view position ([x], [y]) px (it is now selected). */
        fun onTextMenu(page: Int, item: TextItem, x: Float, y: Float) {}
        /**
         * Math helper: ~600 ms after the pen lifted, the last strokes on [page] look like an expression ending with "=".
         * The host recognizes [line] and calls [insertMathAnswer]. Only called while [mathHelper] is on.
         */
        fun onMathCandidate(page: Int, line: MathAssist.Line) {}
    }

    var listener: Listener? = null
    var doc: InkDoc = InkDoc(); private set
    private var source: PageSource? = null

    // ---- tool state (set from the toolbar) ----
    var tool = Tool.PEN
    var penColor = 0xFF1F2937.toInt()
    var penWidth = 2.2f
    var penStyle = PenStyle.BALL
    var hlColor = 0x66F2C94C
    var hlWidth = 14f
    var shapeColor = 0xFF1F2937.toInt()
    /** Shape tool: the library shape drawn by dragging ([InkShapes] kind), or "" = Auto (freehand → recognized). */
    var shapeKind = ""
    /** Shape being dragged out: start (page coords of [drawPage]) and its live preview. */
    private var shapeDrag = false
    private var shX0 = 0f
    private var shY0 = 0f
    private var shapePreview: Stroke? = null
    var eraserRadiusDp = 12f
    var tapeColor = InkRender.tapeColors[0]
    var tapeWidth = 26f
    var penOnly = true
    var stylusButtonTool = Tool.ERASER
    var bgColor = 0xFFF7F6F2.toInt()
    var darkPaper = false

    /**
     * Night paper (on screen only): notes get the dark paper colour and dimmed lines, PDF / slide page bitmaps are drawn
     * through a hue-preserving inversion, and ink colours are mapped for readability ([InkNight]). Saved colours never change.
     */
    var night = false
        set(v) {
            if (field == v) return
            field = v
            tiles2.night = v; tiles2.clear()
            editItem?.let { textOverlay.apply(it, force = true) }
            invalidate()
        }
    /** Colour [c] as displayed right now (night mapping), for views drawn outside [onDraw] (the text editor). */
    internal fun displayInk(c: Int) = if (night) InkNight.ink(c) else c
    private val nightBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = InkNight.pageFilter }

    // ---- ruler (screen-space straightedge, never saved) ----
    internal val ruler = InkRuler(resources.displayMetrics.density)
    /** Shows / hides the ruler (placed in the middle of the view the first time). */
    var rulerOn: Boolean
        get() = ruler.on
        set(v) {
            if (ruler.on == v) return
            ruler.on = v
            if (v && !ruler.placed && width > 0) ruler.place(width, height)
            invalidate()
        }
    /** Stroke being drawn along a ruler edge: side (−1 top, +1 bottom, 0 = none) and its extent along the ruler. */
    private var ruled = 0
    private var rT0 = 0f
    private var rT1 = 0f
    private var rP = 0.6f
    private val rulA = FloatArray(4)
    private val rTmp = FloatArray(2)
    private val ruledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    // ---- two-page (book spread) view ----
    /** Requested two-page view; spreads are used only in landscape on windows ≥ 600 dp, never on whiteboards. */
    var twoPages = false; private set
    var coverAlone = true; private set
    private var spread = false
    private var pageLefts = FloatArray(0)
    /** Second page of the current spread (-1 = single page view / lone page). */
    var spreadLast = -1; private set
    // defaults for new text boxes (set from the editor; last used values)
    var textFont = "sans"
    var textSize = 16f
    var textBold = false
    var textColor = 0xFF1F2937.toInt()
    var textHint = ""
    /** Math helper on/off (set from the editor, persisted in [InkPrefs]). */
    var mathHelper = true

    // ---- math helper: pending answer (faded until accepted) + its ✓ / × chip ----
    private var mathPage = -1
    private var mathId = 0L
    private var mathCandPage = -1
    private val mathDetect = Runnable {
        val pg = mathCandPage
        if (!mathHelper || pg !in doc.pages.indices || mode == Mode.DRAW) return@Runnable
        MathAssist.findLine(doc.pages[pg].strokes)?.let { listener?.onMathCandidate(pg, it) }
    }
    private val chipOk = RectF()
    private val chipNo = RectF()
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    // ---- audio sync ----
    var recId = 0
    var recClockStart = 0L      // elapsedRealtime at recording start
    var recOffset = 0L          // added to elapsed (for resumed recordings)
    var playRec = 0
    var playPos = 0L
        set(v) { field = v; if (playRec != 0) invalidate() }

    // ---- transform ----
    private val density = resources.displayMetrics.density
    private val marginPx = 16f * density
    private var margin = marginPx
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
    val isWhiteboard get() = doc.infinite

    // ---- input ----
    private enum class Mode { NONE, DRAW, NAV, SEL_MOVE, SEL_SCALE, SEL_ROTATE, SEL_SHAPE, PEN_ZOOM, TEXT_DRAG, TEXT_PINCH, RULER }
    private var mode = Mode.NONE
    private var activeTool = Tool.PEN
    private var drawPage = -1
    private var pts = FloatArray(512)
    private var npts = 0
    private var live: StrokeGeom? = null
    private var lastFx = 0f
    private var lastFy = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var lastTapTime = 0L
    private var eraseUndoPushed = false
    private var eraserOn = false
    private var eraserX = 0f
    private var eraserY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return mode == Mode.NAV }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomAt(d.focusX, d.focusY, d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { scaling = false; settleSoon() }
    }).apply { isQuickScaleEnabled = false }

    // long-press on a link chip or a text box (any tool)
    private var pressLink: LinkItem? = null
    private var pressText: TextItem? = null
    private var pressPage = -1
    private val longPress = Runnable {
        val l = pressLink; val t = pressText
        if ((l == null && t == null) || moved) return@Runnable
        mode = Mode.NONE; npts = 0; live = null; eraserOn = false
        velocity?.recycle(); velocity = null
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (l != null) listener?.onLinkMenu(pressPage, l, downX, downY)
        else if (t != null) { selectText(pressPage, t); listener?.onTextMenu(pressPage, t, downX, downY) }
        invalidate()
    }

    // ---- text boxes: selection (frame + handles) and on-canvas editing ----
    /** The native editor placed over the box being edited; the host adds [TextOverlay.edit] next to this view. */
    internal val textOverlay = TextOverlay(context, this).also { o -> o.onEdited = { ensureCaretVisible(); invalidate() } }
    /** Current zoom in px per page point (used by the text overlay). */
    internal val zoomScale get() = scale
    private var textSelPage = -1
    private var textSelId = 0L
    private var editPage = -1
    private var editItem: TextItem? = null      // box being typed; a new box is not on the page until it is committed
    private var editIsNew = false
    private var tHandle = 0
    private var tStart: TextItem? = null
    private var tStartH = 0f
    private var tGrabX = 0f
    private var tGrabY = 0f
    private var tPinchD0 = 0f
    private var tUndoPushed = false
    private var dismissedText = false           // this touch ended an edit / selection: a Text-tool tap must not create a box
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0xFF3B82F6.toInt() }
    private val textRect = RectF()
    val isEditingText get() = editItem != null
    val hasTextBox get() = editItem != null || textSelPage >= 0

    // ---- selection ----
    private class Sel(
        val page: Int, var strokes: List<Stroke>, val texts: List<TextItem>, val images: List<ImageItem>,
        val links: List<LinkItem>, val box: RectF, val stickers: List<StickerItem> = emptyList(),
    ) {
        var dx = 0f; var dy = 0f; var s = 1f
        /** Rotation in degrees around the (moved, scaled) box centre; only offered when [canRotate]. */
        var rot = 0f
        var changed = false
        /** Text boxes, pictures and link chips have no rotation in the model. */
        val canRotate get() = texts.isEmpty() && images.isEmpty() && links.isEmpty()
        fun mapX(x: Float) = box.left + (x - box.left) * s + dx
        fun mapY(y: Float) = box.top + (y - box.top) * s + dy
        fun cx() = mapX(box.centerX())
        fun cy() = mapY(box.centerY())
        fun map(x: Float, y: Float): Pair<Float, Float> {
            val mx = mapX(x); val my = mapY(y)
            if (rot == 0f) return mx to my
            val r = Math.toRadians(rot.toDouble()); val c = cos(r).toFloat(); val sn = sin(r).toFloat()
            val ox = mx - cx(); val oy = my - cy()
            return (cx() + ox * c - oy * sn) to (cy() + ox * sn + oy * c)
        }
        /** A single library shape (no rotation pending): edited with its own handles. */
        val shape: Stroke? get() = if (strokes.size == 1 && texts.isEmpty() && images.isEmpty() && links.isEmpty() && stickers.isEmpty() &&
            rot == 0f && (InkShapes.isLine(strokes[0].shape) || InkShapes.isBox(strokes[0].shape))) strokes[0] else null
    }
    private var sel: Sel? = null
    private var selGrabX = 0f
    private var selGrabY = 0f
    private var selRot0 = 0f
    private var selRotGrab = 0f
    private var selRotSnap = Float.NaN
    private var shapeHandle = -1
    private var shapeFixX = 0f
    private var shapeFixY = 0f
    private val shapePts = FloatArray(8)
    private val shapeBox = RectF()
    private val unrot = FloatArray(2)
    val hasSelection get() = sel != null
    /** Page of the current lasso selection (-1 = none). */
    val selectionPage: Int get() = sel?.page ?: -1

    // ---- undo ----
    private val undo = ArrayDeque<List<InkPage>>()
    private val redo = ArrayDeque<List<InkPage>>()

    // ---- laser pointer (screen-independent: doc coordinates + time), never saved ----
    private val lzX = FloatArray(LZ_MAX)
    private val lzY = FloatArray(LZ_MAX)
    private val lzT = LongArray(LZ_MAX)
    private var lzHead = 0
    private var lzCount = 0

    // ---- background rendering (PDF / slides) ----
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val bgCache = object : LinkedHashMap<Int, Bitmap>(16, 0.75f, true) {}
    private val pending = HashSet<String>()
    private class Tile(val page: Int, val scale: Float, val doc: RectF, val bmp: Bitmap)
    private val tiles = HashMap<Int, Tile>()
    private var generation = 0

    // ---- ink tiles: committed strokes rasterised per visible tile on a background thread (see InkTiles) ----
    private val tiles2 = InkTiles({ doc.pages }) { invalidate() }
    /** Last time the zoom changed (tiles are rendered for a settled zoom only). */
    private var zoomTime = 0L
    private val zoomSettle = Runnable { invalidate() }
    private var paused = false

    // ---- paints & preallocated draw objects (no allocation in onDraw) ----
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
    }
    private var lassoEffectScale = -1f
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = 0x996B7280.toInt() }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = LASER_COLOR }
    private val laserDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LASER_COLOR }
    private val viewRect = RectF()
    private val pageRect = RectF()
    private val visRect = RectF()
    private val selRect = RectF()
    private val tileRect = RectF()
    private val lassoPath = Path()

    init {
        isFocusable = true
        setOnDragListener { _, ev -> handleDrag(ev) }
    }

    // =====================================================================================
    // Document
    // =====================================================================================

    fun setDocument(d: InkDoc, src: PageSource?) {
        if (editItem != null) { editItem = null; textOverlay.hide() }
        mathPage = -1; mathId = 0L; main.removeCallbacks(mathDetect)
        textSelPage = -1; textSelId = 0L
        doc = d; source = src
        generation++
        bgCache.values.forEach { it.recycle() }; bgCache.clear(); tiles.values.forEach { it.bmp.recycle() }; tiles.clear(); pending.clear()
        tiles2.clear()
        undo.clear(); redo.clear(); sel = null
        margin = if (d.infinite) 0f else marginPx
        if (d.infinite) trimBoard()
        relayout()
        laidOut = false
        if (width > 0) initialView()
        notifyUndo(); invalidate()
    }

    fun release() {
        main.removeCallbacks(longPress)
        main.removeCallbacks(zoomSettle); main.removeCallbacks(settle); main.removeCallbacks(mathDetect)
        exec.shutdownNow()
        tiles2.release()
        generation++
        bgCache.values.forEach { it.recycle() }; bgCache.clear()
        tiles.values.forEach { it.bmp.recycle() }; tiles.clear()
    }

    private fun relayout() {
        val n = doc.pages.size
        pageTops = FloatArray(n)
        pageLefts = FloatArray(n)
        spread = twoPages && !doc.infinite && n > 1 && width > height && width >= 600f * density
        var y = 0f
        maxW = 1f
        if (!spread) {
            for (i in 0 until n) {
                pageTops[i] = y
                y += doc.pages[i].h + gap
                maxW = max(maxW, doc.pages[i].w)
            }
            for (i in 0 until n) pageLefts[i] = (maxW - doc.pages[i].w) / 2f
        } else {
            // book spreads: left page right-aligned to the spine, right page left-aligned (cover alone on the right)
            var half = 1f
            for (p in doc.pages) half = max(half, p.w)
            maxW = half * 2f + gap
            var i = 0
            while (i < n) {
                val single = (i == 0 && coverAlone) || i + 1 >= n
                val a = doc.pages[i]
                if (single) {
                    pageTops[i] = y
                    pageLefts[i] = if (i == 0) half + gap else half - a.w
                    y += a.h + gap; i++
                } else {
                    val b = doc.pages[i + 1]
                    pageTops[i] = y; pageTops[i + 1] = y
                    pageLefts[i] = half - a.w; pageLefts[i + 1] = half + gap
                    y += max(a.h, b.h) + gap; i += 2
                }
            }
            // right-to-left UI (Arabic): books open the other way
            if (layoutDirection == LAYOUT_DIRECTION_RTL) for (k in 0 until n) pageLefts[k] = maxW - pageLefts[k] - doc.pages[k].w
        }
        totalH = max(1f, y - gap)
    }

    /** Two-page view on / off (and cover alone); keeps the current page and zoom. */
    fun setTwoPages(on: Boolean, cover: Boolean) {
        if (twoPages == on && coverAlone == cover) return
        twoPages = on; coverAlone = cover
        if (doc.pages.isEmpty() || doc.infinite) return
        if (width == 0 || !laidOut) { relayout(); return }
        finishEditing()
        relayoutKeep()
    }

    /** Re-lays the pages out (spread change, resize) keeping the zoom and the page at the top of the view. */
    private fun relayoutKeep() {
        val zoom = if (fitScale > 0) scale / fitScale else 1f
        val idx = currentPage.coerceIn(0, max(0, pageTops.size - 1))
        val off = if (pageTops.isNotEmpty()) toDocY(0f) - pageTops[idx] else 0f
        relayout()
        computeFit()
        scale = fitScale * zoom; zoomTime = SystemClock.uptimeMillis()
        sy = if (idx < pageTops.size) max(0f, (pageTops[idx] + off) * scale) else 0f
        clamp(); updateCurrentPage(true)
        tiles.values.forEach { it.bmp.recycle() }; tiles.clear()
        settleSoon(); notifyZoom(); invalidate()
    }

    /** The page under the middle of the view (insertions), else the current page. */
    private fun targetPage(): Int {
        if (doc.infinite) return 0
        hit(width / 2f, height / 2f, strict = false)?.let { return it.first }
        return currentPage.coerceIn(0, doc.pages.size - 1)
    }

    private fun computeFit() {
        if (width == 0) return
        // whiteboards: 100% = a fixed physical size (2dp per point) so growing the board never changes the zoom
        fitScale = if (doc.infinite) 2f * density else (width - 2 * margin) / maxW
    }

    private fun minScale() = fitScale * (if (doc.infinite) 0.1f else 0.25f)
    private fun maxScale() = fitScale * 8f

    private fun initialView() {
        if (!doc.infinite) relayout()
        if (ruler.on && !ruler.placed) ruler.place(width, height)
        computeFit()
        scale = fitScale; zoomTime = SystemClock.uptimeMillis()
        if (doc.infinite && doc.pages.isNotEmpty()) {
            val p = doc.pages[0]
            val b = p.contentBounds()
            val cx = b?.centerX() ?: (p.w / 2f)
            val cy = b?.let { it.top + min(it.height() / 2f, height / scale / 2f - 24f) } ?: (p.h / 2f)
            sx = cx * scale - width / 2f; sy = cy * scale - height / 2f
            growIfNeeded()
        } else { sx = 0f; sy = 0f }
        laidOut = true
        clamp(); notifyZoom()
        if (pendingGo >= 0) { val g = pendingGo; pendingGo = -1; goToPage(g) }
    }

    private var pendingGo = -1

    /** [goToPage] now, or right after the first layout when the view has no size yet (opening at a given page). */
    fun goToPageWhenReady(i: Int) { if (laidOut && width > 0) goToPage(i) else pendingGo = i }

    /** Whiteboards are normalised on open: empty space far from the content (from earlier panning) is trimmed. */
    private fun trimBoard() {
        val p = doc.pages.firstOrNull() ?: return
        val b = p.contentBounds()
        if (b == null) {
            if (p.w > BOARD_W * 2 || p.h > BOARD_H * 2) doc.pages = listOf(p.copy(w = BOARD_W, h = BOARD_H))
            return
        }
        val m = GROW_UNIT * 10
        val dx = -floor(max(0f, b.left - m) / GROW_UNIT) * GROW_UNIT
        val dy = -floor(max(0f, b.top - m) / GROW_UNIT) * GROW_UNIT
        val nw = min(p.w + dx, max(BOARD_W, ceil((b.right + dx + m) / GROW_UNIT) * GROW_UNIT))
        val nh = min(p.h + dy, max(BOARD_H, ceil((b.bottom + dy + m) / GROW_UNIT) * GROW_UNIT))
        if (dx == 0f && dy == 0f && nw >= p.w && nh >= p.h) return
        val shifted = if (dx != 0f || dy != 0f) p.shifted(dx, dy) else p
        doc.pages = listOf(shifted.copy(w = nw, h = nh))
    }

    private fun pageLeft(i: Int) = if (i < pageLefts.size) pageLefts[i] else (maxW - doc.pages[i].w) / 2f
    private fun offX(): Float { val cw = maxW * scale + 2 * margin; return if (cw < width) (width - cw) / 2f else 0f }
    private fun toScreenX(docX: Float) = margin + docX * scale - sx + offX()
    private fun toScreenY(docY: Float) = margin + docY * scale - sy
    private fun toDocX(x: Float) = (x - margin + sx - offX()) / scale
    private fun toDocY(y: Float) = (y - margin + sy) / scale

    private fun pageScreenRect(i: Int, out: RectF): RectF {
        val l = toScreenX(pageLeft(i)); val t = toScreenY(pageTops[i])
        out.set(l, t, l + doc.pages[i].w * scale, t + doc.pages[i].h * scale)
        return out
    }

    /** Page index under a doc-Y coordinate (or -1 if between/outside pages). */
    private fun pageAtDoc(docX: Float, docY: Float, strict: Boolean = true): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in pageTops.indices) {
            val top = pageTops[i]; val p = doc.pages[i]
            val l = pageLeft(i)
            if (docY >= top - (if (strict) 0f else gap / 2) && docY <= top + p.h + (if (strict) 0f else gap / 2)) {
                val d = if (docX < l) l - docX else if (docX > l + p.w) docX - l - p.w else 0f
                if (strict && d > 0f) continue
                if (d < bestD) { bestD = d; best = i }     // spreads: the nearer page of the row
                if (d == 0f) return i
            }
        }
        return best
    }

    /** Screen point -> (page, pageX, pageY). On a whiteboard every point is on the (single) page. */
    private fun hit(x: Float, y: Float, strict: Boolean = true): Triple<Int, Float, Float>? {
        val dx = toDocX(x); val dy = toDocY(y)
        if (doc.infinite) return if (doc.pages.isEmpty()) null else Triple(0, dx, dy)
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

    private fun updateCurrentPage(force: Boolean = false) {
        if (pageTops.isEmpty() || doc.infinite) return
        val cy = toDocY(height * 0.4f)
        var cur = 0
        for (i in pageTops.indices) if (pageTops[i] <= cy && (i == 0 || pageTops[i] != pageTops[i - 1])) cur = i
        val last = spreadOf(cur)
        if (cur != currentPage || last != spreadLast || force) { currentPage = cur; spreadLast = last; listener?.onPageChanged(cur, doc.pages.size) }
    }

    /** Second page of the spread starting at [i], or -1. */
    private fun spreadOf(i: Int): Int = if (spread && i + 1 < pageTops.size && pageTops[i + 1] == pageTops[i]) i + 1 else -1

    /** Scroll so point [yPt] (page points) of page [i] sits in the upper third of the view (search matches). */
    fun goToPage(i: Int, yPt: Float) {
        if (i !in pageTops.indices) return
        commitSelection()
        scroller.forceFinished(true)
        sy = (pageTops[i] + yPt) * scale - height / 3f
        clamp(); updateCurrentPage()
        settleSoon(); invalidate()
    }

    /** Pages whose cached background must be re-rendered (e.g. search highlights changed); old bitmap stays until then. */
    private val staleBg = HashSet<Int>()

    /** Re-render every page background from the source (keeps showing the old bitmaps meanwhile, no flicker). */
    fun refreshBackground() {
        staleBg.addAll(bgCache.keys)
        tiles.values.forEach { it.bmp.recycle() }; tiles.clear()
        settleSoon(); invalidate()
    }

    fun goToPage(i: Int) {
        if (i !in pageTops.indices) return
        commitSelection()
        scroller.forceFinished(true)
        sy = pageTops[i] * scale
        clamp(); updateCurrentPage()
        val first = if (spread && i > 0 && pageTops[i - 1] == pageTops[i]) i - 1 else i
        currentPage = first; spreadLast = spreadOf(first); listener?.onPageChanged(first, doc.pages.size)
        settleSoon(); invalidate()
    }

    fun zoomAt(fx: Float, fy: Float, factor: Float) {
        val ns = (scale * factor).coerceIn(minScale(), maxScale())
        val dx = toDocX(fx); val dy = toDocY(fy)
        if (ns != scale) zoomTime = SystemClock.uptimeMillis()
        scale = ns
        // keep doc point under the focus
        sx = margin + dx * scale + offX() - fx
        sy = margin + dy * scale - fy
        growIfNeeded()
        clamp(); updateCurrentPage(); notifyZoom(); invalidate()
    }

    /** Fit width (paged) / fit all content (whiteboard). */
    fun zoomToFit() {
        if (doc.infinite) { fitContent(); return }
        val cy = toDocY(0f); scale = fitScale; zoomTime = SystemClock.uptimeMillis(); sy = cy * scale; clamp(); settleSoon(); notifyZoom(); invalidate()
    }

    private fun fitContent() {
        val p = doc.pages.firstOrNull() ?: return
        zoomTime = SystemClock.uptimeMillis()
        val b = p.contentBounds()
        if (b == null) {
            scale = fitScale
            sx = p.w / 2f * scale - width / 2f; sy = p.h / 2f * scale - height / 2f
        } else {
            b.inset(-40f, -40f)
            scale = min(width / b.width(), height / b.height()).coerceIn(minScale(), fitScale * 2f)
            sx = margin + b.centerX() * scale + offX() - width / 2f
            sy = margin + b.centerY() * scale - height / 2f
        }
        growIfNeeded(); clamp(); settleSoon(); notifyZoom(); invalidate()
    }

    /** Zoom around the view centre (toolbar buttons). */
    fun zoomBy(factor: Float) { zoomAt(width / 2f, height / 2f, factor); settleSoon() }

    val zoomPercent: Int get() = if (fitScale > 0f) (scale / fitScale * 100f).toInt() else 100

    private var lastZoom = -1
    private fun notifyZoom() { val z = zoomPercent; if (z != lastZoom) { lastZoom = z; listener?.onZoomChanged(z) } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoomTime = SystemClock.uptimeMillis()
        if (!laidOut) { if (doc.pages.isNotEmpty()) initialView(); tiles.values.forEach { it.bmp.recycle() }; tiles.clear(); invalidate(); return }
        if (doc.infinite) {
            // keep the same board point in the middle
            val cx = (oldw / 2f + sx) / scale; val cy = (oldh / 2f + sy) / scale
            computeFit()
            sx = cx * scale - w / 2f; sy = cy * scale - h / 2f
            growIfNeeded()
        } else {
            relayoutKeep()      // also switches spreads on / off with the orientation
        }
        ruler.fit(w, h)
        clamp(); tiles.values.forEach { it.bmp.recycle() }; tiles.clear(); settleSoon(); notifyZoom(); invalidate()
        if (editItem != null) post { ensureCaretVisible() }     // keyboard opened / window resized while typing
    }

    // =====================================================================================
    // Whiteboard growth
    // =====================================================================================

    /** Keeps the board at least half a screen larger than the viewport in every direction (panning never hits an edge). */
    private fun growIfNeeded() {
        if (!doc.infinite || doc.pages.isEmpty() || width == 0 || height == 0) return
        if (mode == Mode.DRAW && npts > 0) return            // never shift coordinates under a live stroke
        val vl = toDocX(0f); val vt = toDocY(0f); val vr = toDocX(width.toFloat()); val vb = toDocY(height.toFloat())
        val ex = (vr - vl) * 0.5f; val ey = (vb - vt) * 0.5f
        growToCover(vl - ex, vt - ey, vr + ex, vb + ey)
    }

    /**
     * Grows the board so it covers the page-coordinate rectangle. Growing left/top shifts all content right/down by a
     * multiple of [GROW_UNIT] (a multiple of every paper period, so lines/dots never jump) and moves the scroll by the
     * same amount: nothing moves on screen.
     */
    private fun growToCover(l: Float, t: Float, r: Float, b: Float): Boolean {
        val p = doc.pages[0]
        // grow by at least a screen beyond what is needed: growth (and the left/top content shift) stays rare while panning
        val extra = max(GROW_UNIT * 2, max(width, height) / scale)
        fun step(x: Float) = ceil((x + extra) / GROW_UNIT) * GROW_UNIT
        var gl = if (l < 0f) step(-l) else 0f
        var gt = if (t < 0f) step(-t) else 0f
        var gr = if (r > p.w) step(r - p.w) else 0f
        var gb = if (b > p.h) step(b - p.h) else 0f
        if (p.w + gl + gr > MAX_BOARD) { gl = 0f; gr = 0f }
        if (p.h + gt + gb > MAX_BOARD) { gt = 0f; gb = 0f }
        if (gl == 0f && gt == 0f && gr == 0f && gb == 0f) return false
        val ax = toScreenX(0f); val ay = toScreenY(0f)
        val shifted = if (gl > 0f || gt > 0f) p.shifted(gl, gt) else p
        setPage(0, shifted.copy(w = p.w + gl + gr, h = p.h + gt + gb))
        relayout()
        sx = margin + gl * scale + offX() - ax
        sy = margin + gt * scale - ay
        if (gl > 0f || gt > 0f) {
            sel?.let { it.dx += gl; it.dy += gt; it.changed = true }
            editItem = editItem?.let { it.copy(x = it.x + gl, y = it.y + gt) }
            tStart = tStart?.let { it.copy(x = it.x + gl, y = it.y + gt) }
            for (k in 0 until LZ_MAX) { lzX[k] += gl; lzY[k] += gt }
            lzLastX += gl; lzLastY += gt
        }
        clamp()
        listener?.onChanged()
        return true
    }

    // =====================================================================================
    // Drawing
    // =====================================================================================

    override fun onDraw(c: Canvas) {
        // Compose hosts views with clipChildren=false: never paint outside our own bounds.
        c.clipRect(0, 0, width, height)
        if (doc.pages.isEmpty()) { c.drawColor(bgColor); return }
        InkRender.setNight(night)
        try {
            tiles2.beginFrame()
            if (doc.infinite) drawBoard(c) else drawPages(c)
            tiles2.endFrame()
        } finally { InkRender.setNight(false) }
        ruler.draw(c, scale, night || darkPaper)
        sel?.let { drawSelectionChrome(c, it) }
        if (hasTextBox) drawTextChrome(c)
        if (eraserOn) c.drawCircle(eraserX, eraserY, eraserRadiusDp * density, eraserPaint)
        if (lzCount > 0) drawLaser(c)
        else if (mode == Mode.DRAW && activeTool == Tool.LASER && lzLastX.isFinite()) {
            // pointer held still: just the dot, no animation
            val x = toScreenX(lzLastX); val y = toScreenY(lzLastY)
            laserDot.alpha = 70; c.drawCircle(x, y, 11f * density, laserDot)
            laserDot.alpha = 255; c.drawCircle(x, y, 4.5f * density, laserDot)
        }
        drawMathChip(c)
    }

    private fun drawPages(c: Canvas) {
        c.drawColor(bgColor)
        pagePaint.color = if (night) InkNight.PAPER else doc.paperColor
        borderPaint.color = if (night) InkNight.BORDER else if (darkPaper) 0xFF2E333B.toInt() else 0xFFDDDAD3.toInt()
        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        for (i in doc.pages.indices) {
            val r = pageScreenRect(i, pageRect)
            if (!RectF.intersects(r, viewRect)) continue
            val page = doc.pages[i]
            // visible region of this page in page coords (culling, paper clip, ink layer)
            pageVis(page, r, visRect)
            c.drawRect(r, pagePaint)
            if (source != null) drawBackground(c, i, r) else {
                c.save(); c.translate(r.left, r.top); c.scale(scale, scale)
                InkRender.drawPaper(c, page, darkPaper || night, visRect, scale)
                c.restore()
            }
            c.drawRect(r, borderPaint)
            c.save()
            c.clipRect(r)
            c.translate(r.left, r.top); c.scale(scale, scale)
            drawContent(c, i, page, visRect, r.left, r.top)
            c.restore()
        }
    }

    private fun drawBoard(c: Canvas) {
        c.drawColor(if (night) InkNight.PAPER else doc.paperColor)
        val page = doc.pages[0]
        visRect.set(toDocX(0f), toDocY(0f), toDocX(width.toFloat()), toDocY(height.toFloat()))
        c.save()
        val ox = toScreenX(0f); val oy = toScreenY(0f)
        c.translate(ox, oy); c.scale(scale, scale)
        InkRender.drawPaper(c, page, darkPaper || night, visRect, scale, bounded = false)
        drawContent(c, 0, page, visRect, ox, oy)
        c.restore()
    }

    /**
     * Page content in page coordinates, culled to [vis]; tapes on top (with the editor's reveal state).
     * ([ox], [oy]) = screen position of the page origin (used to blit the ink layer on whole pixels).
     */
    private fun drawContent(c: Canvas, i: Int, page: InkPage, vis: RectF, ox: Float, oy: Float) {
        val imgs = page.images
        for (k in imgs.indices) {
            val im = imgs[k]
            if (im.x <= vis.right && im.x + im.w >= vis.left && im.y <= vis.bottom && im.y + im.h >= vis.top) InkRender.drawImage(c, im)
        }
        val st = page.strokes
        drawStrokes(c, i, page, vis, ox, oy)
        val sk = page.stickers
        for (k in sk.indices) {
            val it = sk[k]
            // generous cull (rotation): the item's box grown by half its diagonal
            val m = (it.w + it.h) / 2f
            if (it.x - m <= vis.right && it.x + it.w + m >= vis.left && it.y - m <= vis.bottom && it.y + it.h + m >= vis.top) InkStickers.draw(c, it)
        }
        val tx = page.texts
        val editId = if (editPage == i) editItem?.id ?: 0L else 0L
        for (k in tx.indices) {
            val t = tx[k]
            if (editId != 0L && t.id == editId) continue      // the on-canvas editor shows this box
            val lh = InkRender.layout(t).height
            if (t.x <= vis.right && t.x + t.w >= vis.left && t.y <= vis.bottom && t.y + lh >= vis.top) {
                if (mathPage == i && t.id == mathId) {
                    // math helper answer not accepted yet: faded
                    c.saveLayerAlpha(t.x - 2f, t.y - 2f, t.x + t.w + 2f, t.y + lh + 2f, 120)
                    InkRender.drawText(c, t); c.restore()
                } else InkRender.drawText(c, t)
            }
        }
        val ln = page.links
        for (k in ln.indices) {
            val l = ln[k]
            if (l.x <= vis.right && l.x + l.w >= vis.left && l.y <= vis.bottom && l.y + l.h >= vis.top) InkRender.drawLink(c, l)
        }
        if (st.isNotEmpty()) {
            val tp = tiles2.grid(i, st).tapes
            for (k in tp.indices) {
                val s = st[tp[k]]
                if (RectF.intersects(s.bounds(), vis)) InkRender.drawTape(c, s, s.revealed)
            }
        }
        sel?.let { if (it.page == i) drawFloating(c, it) }
        if (mode == Mode.DRAW && drawPage == i && npts > 0) drawLive(c)
    }

    // ---- committed ink ----
    //
    // Re-rasterising every stroke path every frame is what made big pages and whiteboards lag. Committed strokes are
    // served by [InkTiles] (bitmaps of the visible tiles at the settled zoom, rendered off the UI thread); a frame blits
    // them and draws as vectors only what is not covered yet. Audio replay ("ghost" strokes) draws culled vectors.

    private fun zoomSettled(): Boolean {
        if (scaling) return false
        val dt = SystemClock.uptimeMillis() - zoomTime
        if (dt >= ZOOM_SETTLE) return true
        main.removeCallbacks(zoomSettle); main.postDelayed(zoomSettle, ZOOM_SETTLE - dt + 8)
        return false
    }

    private fun drawStrokes(c: Canvas, i: Int, page: InkPage, vis: RectF, ox: Float, oy: Float) {
        val st = page.strokes
        if (st.isEmpty()) return
        if (playRec != 0) {
            val g = tiles2.grid(i, st)
            g.query(vis.left, vis.top, vis.right, vis.bottom, ghostIdx)
            for (j in 0 until ghostIdx.size) {
                val s = st[ghostIdx[j]]
                val ghost = s.rec == playRec && s.t > playPos
                InkRender.drawStroke(c, s, if (ghost) 0.18f else 1f)
            }
            return
        }
        tiles2.draw(c, i, page, vis, scale, ox, oy, zoomSettled(), mode == Mode.DRAW)
    }
    private val ghostIdx = IntList(256)

    private fun pageVis(page: InkPage, r: RectF, out: RectF) {
        out.set(max(0f, -r.left / scale), max(0f, -r.top / scale), min(page.w, (width - r.left) / scale), min(page.h, (height - r.top) / scale))
    }

    /** Editor paused (ON_PAUSE): no background work. */
    fun onPause() {
        paused = true
        tiles2.pause()
        main.removeCallbacks(settle); main.removeCallbacks(zoomSettle); main.removeCallbacks(longPress)
        scroller.forceFinished(true)
        lzCount = 0
    }

    fun onResume() { paused = false; tiles2.resume(); invalidate() }

    /** Memory pressure: drop caches that can be rebuilt (ComponentCallbacks2 levels). */
    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            tiles2.clear()
            tiles.values.forEach { it.bmp.recycle() }; tiles.clear()
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                val keep = bgCache[currentPage]
                bgCache.entries.removeAll { (k, v) -> if (k != currentPage) { v.recycle(); true } else false }
                if (keep == null) bgCache.clear()
            }
        } else tiles2.trim()
        invalidate()
    }

    private val memCallbacks = object : android.content.ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = trimMemory(level)
        override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { context.applicationContext.registerComponentCallbacks(memCallbacks) }
    }

    // ---- text box frame ----

    private fun textHeightPt(t: TextItem): Float =
        if (editItem?.id == t.id && textOverlay.showing) textOverlay.heightPt() else InkRender.layout(t).height.toFloat().coerceAtLeast(t.size * 1.2f)

    /** The selected / edited text box and its page, or null. */
    private fun currentText(): Pair<Int, TextItem>? {
        editItem?.let { return editPage to it }
        if (textSelPage < 0) return null
        val t = doc.pages.getOrNull(textSelPage)?.texts?.firstOrNull { it.id == textSelId } ?: return null
        return textSelPage to t
    }

    private fun textScreenRect(page: Int, t: TextItem, out: RectF): RectF {
        val pl = toScreenX(pageLeft(page)); val pt = toScreenY(pageTops[page])
        out.set(pl + t.x * scale, pt + t.y * scale, pl + (t.x + t.w) * scale, pt + (t.y + textHeightPt(t)) * scale)
        return out
    }

    private fun drawTextChrome(c: Canvas) {
        val (page, t) = currentText() ?: return
        if (page >= pageTops.size) return
        val b = textScreenRect(page, t, textRect)
        if (editItem != null) textOverlay.place(b.left, b.top)
        val pad = 6 * density
        b.inset(-pad, -pad)
        textPaint.style = Paint.Style.STROKE
        textPaint.alpha = if (editItem != null) 150 else 255
        c.drawRect(b, textPaint)
        textPaint.alpha = 255
        val hr = 6 * density
        for (k in 0..3) {
            val x = if (k % 2 == 0) b.left else b.right; val y = if (k < 2) b.top else b.bottom
            c.drawCircle(x, y, hr, handleInner); c.drawCircle(x, y, hr, textPaint)
        }
        // side handles (wrap width): short bars in the middle of the left / right edges
        val bh = min(b.height() * 0.5f, 18 * density); val bw = 3.5f * density; val cy = b.centerY()
        textPaint.style = Paint.Style.FILL
        for (x in floatArrayOf(b.left, b.right)) {
            selRect.set(x - bw, cy - bh / 2f, x + bw, cy + bh / 2f)
            c.drawRoundRect(selRect, bw, bw, handleInner)
            selRect.inset(1f * density, 1f * density)
            c.drawRoundRect(selRect, bw, bw, textPaint)
        }
        textPaint.style = Paint.Style.STROKE
    }

    private fun drawFloating(c: Canvas, s: Sel) {
        c.save()
        if (s.rot != 0f) c.rotate(s.rot, s.cx(), s.cy())
        c.translate(s.box.left + s.dx, s.box.top + s.dy)
        c.scale(s.s, s.s)
        c.translate(-s.box.left, -s.box.top)
        for (k in s.images.indices) InkRender.drawImage(c, s.images[k])
        for (k in s.strokes.indices) { val st = s.strokes[k]; if (!st.isTape) InkRender.drawStroke(c, st) }
        for (k in s.stickers.indices) InkStickers.draw(c, s.stickers[k])
        for (k in s.texts.indices) InkRender.drawText(c, s.texts[k])
        for (k in s.links.indices) InkRender.drawLink(c, s.links[k])
        for (k in s.strokes.indices) { val st = s.strokes[k]; if (st.isTape) InkRender.drawTape(c, st, st.revealed) }
        c.restore()
    }

    private fun selScreenBox(s: Sel, out: RectF): RectF {
        val pl = toScreenX(pageLeft(s.page)); val pt = toScreenY(pageTops[s.page])
        out.set(pl + s.mapX(s.box.left) * scale, pt + s.mapY(s.box.top) * scale, pl + s.mapX(s.box.right) * scale, pt + s.mapY(s.box.bottom) * scale)
        return out
    }

    private fun drawSelectionChrome(c: Canvas, s: Sel) {
        val b = selScreenBox(s, selRect)
        val shape = s.shape
        c.save()
        if (s.rot != 0f) c.rotate(s.rot, b.centerX(), b.centerY())
        b.inset(-6 * density, -6 * density)
        c.drawRect(b, selPaint)
        if (shape != null) {
            val n = shapeHandles(s, shape)
            var k = 0
            while (k < n) {
                c.drawCircle(shapePts[k * 2], shapePts[k * 2 + 1], 9 * density, handlePaint)
                c.drawCircle(shapePts[k * 2], shapePts[k * 2 + 1], 5 * density, handleInner)
                k++
            }
        } else {
            c.drawCircle(b.right, b.bottom, 9 * density, handlePaint)
            c.drawCircle(b.right, b.bottom, 5 * density, handleInner)
        }
        if (s.canRotate) {
            // rotation handle above the top edge
            val hx = b.centerX(); val hy = b.top - ROT_HANDLE * density
            c.drawLine(hx, b.top, hx, hy, textPaint)
            c.drawCircle(hx, hy, 9 * density, handlePaint)
            c.drawCircle(hx, hy, 4 * density, handleInner)
        }
        c.restore()
    }

    /** Screen positions of a library shape's handles into [shapePts]: 2 end points (lines) or 4 box corners. */
    private fun shapeHandles(s: Sel, st: Stroke): Int {
        val pl = toScreenX(pageLeft(s.page)); val pt = toScreenY(pageTops[s.page])
        val p = st.pts
        if (InkShapes.isLine(st.shape)) {
            if (p.size < 6) return 0
            shapePts[0] = pl + s.mapX(p[0]) * scale; shapePts[1] = pt + s.mapY(p[1]) * scale
            shapePts[2] = pl + s.mapX(p[3]) * scale; shapePts[3] = pt + s.mapY(p[4]) * scale
            return 2
        }
        shapeBox.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 1 < p.size) { shapeBox.union(p[i], p[i + 1]); i += 3 }
        val l = pl + s.mapX(shapeBox.left) * scale; val t = pt + s.mapY(shapeBox.top) * scale
        val r = pl + s.mapX(shapeBox.right) * scale; val b = pt + s.mapY(shapeBox.bottom) * scale
        shapePts[0] = l; shapePts[1] = t; shapePts[2] = r; shapePts[3] = t
        shapePts[4] = l; shapePts[5] = b; shapePts[6] = r; shapePts[7] = b
        return 4
    }

    /** ([x], [y]) rotated back into the selection's unrotated frame → [unrot]. */
    private fun unrotate(s: Sel, b: RectF, x: Float, y: Float) {
        if (s.rot == 0f) { unrot[0] = x; unrot[1] = y; return }
        val r = Math.toRadians(-s.rot.toDouble()); val c = cos(r).toFloat(); val sn = sin(r).toFloat()
        val ox = x - b.centerX(); val oy = y - b.centerY()
        unrot[0] = b.centerX() + ox * c - oy * sn; unrot[1] = b.centerY() + ox * sn + oy * c
    }

    private fun liveColor() = when (activeTool) {
        Tool.HIGHLIGHTER -> hlColor
        Tool.SHAPE -> shapeColor
        Tool.TAPE -> tapeColor
        else -> penColor
    }

    private fun drawLive(c: Canvas) {
        if (shapeDrag) { shapePreview?.let { InkRender.drawStroke(c, it) }; return }
        if (ruled != 0) {
            // stroke along the ruler: a straight line, drawn in the tool's look
            val hl = activeTool == Tool.HIGHLIGHTER
            val col = InkRender.inkColor(liveColor())
            val marker = !hl && penStyle == PenStyle.MARKER
            ruledPaint.color = if (marker) col or 0xFF000000.toInt() else col
            if (!hl && penStyle == PenStyle.PENCIL) ruledPaint.alpha = (ruledPaint.alpha * 0.85f).toInt()
            ruledPaint.strokeWidth = if (hl) hlWidth else penWidth
            ruledPaint.strokeCap = if (hl) Paint.Cap.SQUARE else if (marker) Paint.Cap.BUTT else Paint.Cap.ROUND
            c.drawLine(rulA[0], rulA[1], rulA[2], rulA[3], ruledPaint)
            return
        }
        if (activeTool == Tool.LASSO) {
            if (lassoEffectScale != scale) {
                lassoEffectScale = scale
                lassoPaint.pathEffect = DashPathEffect(floatArrayOf(6 * density / scale, 5 * density / scale), 0f)
            }
            lassoPaint.strokeWidth = 1.5f * density / scale
            c.drawPath(lassoPath, lassoPaint)
        } else live?.let { InkRender.drawLive(c, it, liveColor()) }
    }

    private fun drawLaser(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        while (lzCount > 0 && now - lzT[(lzHead - lzCount + LZ_MAX) % LZ_MAX] > LASER_LIFE) lzCount--
        val active = mode == Mode.DRAW && activeTool == Tool.LASER
        if (lzCount == 0) return      // trail gone: no more frames until the pointer moves again
        val first = (lzHead - lzCount + LZ_MAX) % LZ_MAX
        for (pass in 0..1) {
            var px = toScreenX(lzX[first]); var py = toScreenY(lzY[first])
            for (k in 1 until lzCount) {
                val idx = (first + k) % LZ_MAX
                val x = toScreenX(lzX[idx]); val y = toScreenY(lzY[idx])
                val a = (1f - (now - lzT[idx]) / LASER_LIFE.toFloat()).coerceIn(0f, 1f)
                if (pass == 0) { laserPaint.strokeWidth = (6f + 6f * a) * density; laserPaint.alpha = (60 * a).toInt() }
                else { laserPaint.strokeWidth = (1.5f + 2.5f * a) * density; laserPaint.alpha = (255 * a).toInt() }
                c.drawLine(px, py, x, y, laserPaint)
                px = x; py = y
            }
        }
        if (active) {
            val last = (lzHead - 1 + LZ_MAX) % LZ_MAX
            val x = toScreenX(lzX[last]); val y = toScreenY(lzY[last])
            laserDot.alpha = 70; c.drawCircle(x, y, 11f * density, laserDot)
            laserDot.alpha = 255; c.drawCircle(x, y, 4.5f * density, laserDot)
        }
        postInvalidateOnAnimation()
    }

    private var lzLastX = Float.NaN
    private var lzLastY = Float.NaN

    private fun laserAdd(screenX: Float, screenY: Float) {
        val x = toDocX(screenX); val y = toDocY(screenY)
        lzLastX = x; lzLastY = y
        if (lzCount > 0) {
            val last = (lzHead - 1 + LZ_MAX) % LZ_MAX
            if (abs(lzX[last] - x) * scale + abs(lzY[last] - y) * scale < density) { lzT[last] = SystemClock.uptimeMillis(); return }
        }
        lzX[lzHead] = x; lzY[lzHead] = y; lzT[lzHead] = SystemClock.uptimeMillis()
        lzHead = (lzHead + 1) % LZ_MAX
        lzCount = min(lzCount + 1, LZ_MAX)
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
        val bp = if (night) nightBmpPaint else bmpPaint
        if (bmp != null && !bmp.isRecycled) c.drawBitmap(bmp, null, r, bp)
        if (bmp == null || (!scaling && abs(bmp.width - want) > want * 0.2f) || (i in staleBg && !scaling)) {
            if (i in staleBg) { staleBg.remove(i); pending.removeAll { it.startsWith("$i:") } }
            requestPage(i, want)
        }
        val t = tiles[i]
        if (t != null && t.scale == scale && !scaling) {
            val pl = r.left; val pt = r.top
            tileRect.set(pl + t.doc.left * scale, pt + t.doc.top * scale, pl + t.doc.right * scale, pt + t.doc.bottom * scale)
            c.drawBitmap(t.bmp, null, tileRect, bp)
        }
    }

    private fun requestPage(i: Int, w: Int) {
        val src = source ?: return
        val key = "$i:$w"
        if (!pending.add(key)) return
        val p = doc.pages[i]
        val h = (w * p.h / p.w).toInt().coerceAtLeast(1)
        val gen = generation
        runCatching {
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
    }

    private val settle = Runnable { requestTiles() }
    private fun settleSoon() { main.removeCallbacks(settle); main.postDelayed(settle, 180) }

    /** When zoomed past the full-page cache resolution, render just the visible part sharply. */
    private fun requestTiles() {
        val src = source ?: return
        viewRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val visible = HashSet<Int>()
        for (i in doc.pages.indices) {
            val r = RectF(pageScreenRect(i, pageRect))
            if (!RectF.intersects(r, viewRect)) continue
            visible.add(i)
            val p = doc.pages[i]
            if (p.w * scale <= maxCacheWidth(i) * 1.1f) { tiles.remove(i)?.bmp?.recycle(); continue }
            val inter = RectF(r); inter.intersect(viewRect)
            val tw = inter.width().toInt(); val th = inter.height().toInt()
            if (tw < 8 || th < 8) continue
            val docRect = RectF((inter.left - r.left) / scale, (inter.top - r.top) / scale, (inter.right - r.left) / scale, (inter.bottom - r.top) / scale)
            val sc = scale
            val gen = generation
            val left = r.left - inter.left; val top = r.top - inter.top
            runCatching {
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
        }
        tiles.keys.filter { it !in visible }.forEach { tiles.remove(it)?.bmp?.recycle() }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat(); sy = scroller.currY.toFloat()
            clamp(); updateCurrentPage(); postInvalidateOnAnimation()
            if (scroller.isFinished) { growIfNeeded(); settleSoon() }
        }
    }

    // =====================================================================================
    // Input
    // =====================================================================================

    private fun isPen(e: MotionEvent, idx: Int = 0): Boolean {
        val t = e.getToolType(idx)
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) { com.daftar.app.data.Prefs.markStylusSeen(); return true }
        // A mouse (emulator, DeX, Bluetooth mouse) writes like a pen; fingers are never pens.
        return t == MotionEvent.TOOL_TYPE_MOUSE
    }

    // ---- pen tracking / palm rejection (fast handwriting: the palm often lands a moment before the pen tip) ----
    /** Pointer id of the stylus that is drawing while other pointers (palm / fingers) are also down; -1 = none. */
    private var penId = -1
    /** Last time a stylus touched or hovered: finger touches right after it are treated as a resting palm. */
    private var lastPenSeen = 0L
    /** The current gesture started as a resting palm: ignore it until a pen joins or everything lifts. */
    private var palmGesture = false

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (isPen(e)) lastPenSeen = SystemClock.uptimeMillis()
        return super.onHoverEvent(e)
    }

    /** One-pointer copy of [e] for pointer [idx] (keeps tool type, pressure and the full batched history). */
    private fun singlePointer(e: MotionEvent, idx: Int, action: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().also { e.getPointerProperties(idx, it) }.also { it.id = 0 })
        val c = MotionEvent.PointerCoords()
        val hs = if (action == MotionEvent.ACTION_MOVE) e.historySize else 0
        if (hs > 0) e.getHistoricalPointerCoords(idx, 0, c) else e.getPointerCoords(idx, c)
        val first = if (hs > 0) e.getHistoricalEventTime(0) else e.eventTime
        val ev = MotionEvent.obtain(e.downTime, first, action, 1, props, arrayOf(c), e.metaState, e.buttonState,
            e.xPrecision, e.yPrecision, e.deviceId, e.edgeFlags, e.source, e.flags)
        for (k in 1 until hs) {
            val ck = MotionEvent.PointerCoords(); e.getHistoricalPointerCoords(idx, k, ck)
            ev.addBatch(e.getHistoricalEventTime(k), arrayOf(ck), e.metaState)
        }
        if (hs > 0) { val cn = MotionEvent.PointerCoords(); e.getPointerCoords(idx, cn); ev.addBatch(e.eventTime, arrayOf(cn), e.metaState) }
        return ev
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (doc.pages.isEmpty()) return false
        val am = e.actionMasked
        val now = SystemClock.uptimeMillis()
        if (isPen(e, if (am == MotionEvent.ACTION_POINTER_DOWN || am == MotionEvent.ACTION_POINTER_UP) e.actionIndex else 0)) lastPenSeen = now
        if (am == MotionEvent.ACTION_DOWN) {
            penId = -1
            // a finger landing right after the pen hovered/wrote is the writing hand's palm: never scroll or draw with it
            palmGesture = !isPen(e) && penOnly && now - lastPenSeen < PALM_MS
            if (palmGesture) return true
        }
        // the pen joins a gesture that a palm / finger started: hand the gesture to the pen
        if (am == MotionEvent.ACTION_POINTER_DOWN && penId == -1 && isPen(e, e.actionIndex) &&
            (palmGesture || mode == Mode.NAV || mode == Mode.NONE)) {
            scroller.forceFinished(true)
            palmGesture = false
            penId = e.getPointerId(e.actionIndex)
            val ev = singlePointer(e, e.actionIndex, MotionEvent.ACTION_DOWN)
            onDown(ev); ev.recycle()
            return true
        }
        if (palmGesture) { if (am == MotionEvent.ACTION_UP || am == MotionEvent.ACTION_CANCEL) palmGesture = false; return true }
        // the pen draws while other pointers rest on the screen: follow only the pen
        if (penId != -1) {
            val i = e.findPointerIndex(penId)
            when {
                i < 0 -> { penId = -1; return true }
                am == MotionEvent.ACTION_MOVE -> { val ev = singlePointer(e, i, MotionEvent.ACTION_MOVE); onMove(ev); ev.recycle() }
                (am == MotionEvent.ACTION_POINTER_UP && e.actionIndex == i) || am == MotionEvent.ACTION_UP -> {
                    val ev = singlePointer(e, i, MotionEvent.ACTION_UP); onUp(ev); ev.recycle(); penId = -1
                }
                am == MotionEvent.ACTION_CANCEL -> {
                    val ev = singlePointer(e, i, MotionEvent.ACTION_UP); onUp(ev); ev.recycle(); penId = -1   // keep the ink
                }
            }
            return true
        }
        // pen is pointer 0 and a palm/finger joins: remember the pen so the rest of the stroke follows it alone
        if (am == MotionEvent.ACTION_POINTER_DOWN && mode == Mode.DRAW && isPen(e, 0)) { penId = e.getPointerId(0); return true }
        if (mode == Mode.NAV || e.actionMasked == MotionEvent.ACTION_DOWN) scaleDetector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(e)
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.RULER) {
                    // the gesture started on the ruler: two fingers move + rotate it
                    if (e.pointerCount >= 2) ruler.beginTwo(e.getX(0), e.getY(0), e.getX(1), e.getY(1))
                    return true
                }
                if (mode == Mode.TEXT_DRAG && e.pointerCount >= 2) {
                    // second finger on a selected text box: pinch scales the box
                    currentText()?.let { (_, t) ->
                        tStart = t; tStartH = textHeightPt(t)
                        tPinchD0 = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
                        mode = Mode.TEXT_PINCH
                    }
                    return true
                }
                if (mode == Mode.TEXT_PINCH) return true
                if (mode == Mode.DRAW && shapeDrag) { invalidate(); return true }   // second finger: constrain the shape
                if (mode == Mode.DRAW && !isPen(e, 0)) {
                    // second finger: user wants to zoom, not draw
                    npts = 0; live = null; mode = Mode.NAV; ruled = 0
                    eraserOn = false
                    main.removeCallbacks(longPress)
                }
                if (mode == Mode.NAV) { scaleDetector.onTouchEvent(e); focus(e, -1) }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.RULER) {
                    val up = e.actionIndex
                    var a = -1; var b = -1
                    for (k in 0 until e.pointerCount) if (k != up) { if (a < 0) a = k else if (b < 0) b = k }
                    if (a >= 0 && b >= 0) ruler.beginTwo(e.getX(a), e.getY(a), e.getX(b), e.getY(b))
                    else if (a >= 0) ruler.beginDrag(e.getX(a), e.getY(a))
                    return true
                }
                if (mode == Mode.NAV) focus(e, e.actionIndex)
                if (mode == Mode.TEXT_PINCH) { finishTextGesture(); mode = Mode.NONE }
            }
            MotionEvent.ACTION_MOVE -> onMove(e)
            MotionEvent.ACTION_UP -> onUp(e)
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (mode == Mode.DRAW && isPen(e) && npts > 1 && (activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER)) {
                    // Samsung palm rejection cancels the gesture: keep what the pen already wrote
                    val ev = singlePointer(e, 0, MotionEvent.ACTION_UP); onUp(ev); ev.recycle(); return true
                }
                if (mode == Mode.TEXT_DRAG || mode == Mode.TEXT_PINCH) finishTextGesture()
                npts = 0; live = null; ruled = 0; shapeDrag = false; shapePreview = null; mode = Mode.NONE; eraserOn = false; velocity?.recycle(); velocity = null; invalidate()
            }
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
        main.removeCallbacks(mathDetect)
        if (mathPage >= 0) {
            val slack = 6 * density
            if (chipOk.width() > 0f && RectF(chipOk).apply { inset(-slack, -slack) }.contains(e.x, e.y)) { acceptMath(); mode = Mode.NONE; return }
            if (chipNo.width() > 0f && RectF(chipNo).apply { inset(-slack, -slack) }.contains(e.x, e.y)) { rejectMath(); mode = Mode.NONE; return }
        }
        dismissedText = false
        pressLink = null; pressText = null

        // a finger on the ruler moves / rotates it (hit-tested first: two fingers elsewhere still zoom the page)
        if (!pen && ruler.hitBody(e.x, e.y)) {
            mode = Mode.RULER; ruler.beginDrag(e.x, e.y)
            return
        }

        // a selected / edited text box: its frame and handles take priority for any pointer; a touch elsewhere ends it
        if (hasTextBox) {
            val h = textHandleAt(e.x, e.y)
            if (h != 0) {
                val (_, t) = currentText()!!
                tHandle = h; tStart = t; tStartH = textHeightPt(t); tGrabX = e.x; tGrabY = e.y; tUndoPushed = false
                mode = Mode.TEXT_DRAG
                return
            }
            dismissedText = true
            endTextEdit(); clearTextSelection()
        }

        // selection handles take priority for any pointer
        sel?.let { s ->
            val b = selScreenBox(s, selRect)
            unrotate(s, b, e.x, e.y)
            val ux = unrot[0]; val uy = unrot[1]
            b.inset(-6 * density, -6 * density)
            val shape = s.shape
            if (shape != null) {
                val n = shapeHandles(s, shape)
                for (k in 0 until n) if (hypot(ux - shapePts[k * 2], uy - shapePts[k * 2 + 1]) < 26 * density) {
                    beginShapeHandle(s, shape, k); mode = Mode.SEL_SHAPE; return
                }
            } else if (hypot(ux - b.right, uy - b.bottom) < 26 * density) { mode = Mode.SEL_SCALE; return }
            if (s.canRotate && hypot(ux - b.centerX(), uy - (b.top - ROT_HANDLE * density)) < 24 * density) {
                selRot0 = s.rot; selRotSnap = Float.NaN
                selRotGrab = InkShapes.angle(e.x - b.centerX(), e.y - b.centerY())
                mode = Mode.SEL_ROTATE; return
            }
            if (b.contains(ux, uy) && (pen || penOnly || tool == Tool.LASSO || tool == Tool.HAND)) {
                mode = Mode.SEL_MOVE; selGrabX = e.x; selGrabY = e.y; return
            }
            commitSelection()
        }

        val buttonDown = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 || (e.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        if (pen && buttonDown && tool == Tool.HAND) {
            // S Pen side button + vertical drag with the Hand tool = zoom around the press point
            mode = Mode.PEN_ZOOM; lastFx = e.x; lastFy = e.y
            return
        }

        // long-press on a link chip / text box opens its menu (any tool)
        val lk = linkAtScreen(e.x, e.y)
        if (lk != null) { pressLink = lk.second; pressPage = lk.first }
        else textAtScreen(e.x, e.y)?.let { (pg, t) -> pressText = t; pressPage = pg }
        if (pressLink != null || pressText != null) main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())

        activeTool = when {
            e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER -> Tool.ERASER
            pen && buttonDown -> stylusButtonTool
            else -> tool
        }
        val drawWithFinger = !pen && (!penOnly || activeTool == Tool.LASER) && activeTool != Tool.HAND
        if ((pen && activeTool != Tool.HAND) || drawWithFinger) {
            mode = Mode.DRAW
            startTool(e)
        } else {
            mode = Mode.NAV
            lastFx = e.x; lastFy = e.y
        }
    }

    private fun startTool(e: MotionEvent) {
        npts = 0; eraseUndoPushed = false; live = null
        var h = hit(e.x, e.y)
        if (h == null && doc.infinite && doc.pages.isNotEmpty()) {
            val dx = toDocX(e.x) - pageLeft(0); val dy = toDocY(e.y) - pageTops[0]
            if (growToCover(dx - 1f, dy - 1f, dx + 1f, dy + 1f)) h = hit(e.x, e.y)
        }
        drawPage = h?.first ?: -1
        when (activeTool) {
            Tool.PEN -> live = InkRender.liveGeom(Tool.PEN, penStyle, penWidth)
            Tool.HIGHLIGHTER -> live = InkRender.liveGeom(Tool.HIGHLIGHTER, PenStyle.BALL, hlWidth)
            Tool.SHAPE -> if (shapeKind.isEmpty()) live = InkRender.liveGeom(Tool.SHAPE, PenStyle.BALL, penWidth)
            Tool.TAPE -> live = InkRender.liveGeom(Tool.TAPE, PenStyle.BALL, tapeWidth)
            Tool.LASSO -> lassoPath.rewind()
        }
        ruled = 0
        shapeDrag = activeTool == Tool.SHAPE && shapeKind.isNotEmpty() && h != null
        shapePreview = null
        if (shapeDrag) { shX0 = h!!.second; shY0 = h.third }
        when (activeTool) {
            Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.LASSO, Tool.TAPE -> if (h != null) addPoint(h.second, h.third, pressureOf(e, -1))
            Tool.ERASER -> { eraserOn = true; eraserX = e.x; eraserY = e.y; eraseAt(e.x, e.y) }
            Tool.LASER -> { laserAdd(e.x, e.y); postInvalidateOnAnimation() }
            Tool.TEXT -> {}
        }
        // pen / highlighter starting near a ruler edge: a straight line along that edge
        if ((activeTool == Tool.PEN || activeTool == Tool.HIGHLIGHTER) && h != null && ruler.on) {
            val side = ruler.edgeNear(e.x, e.y)
            if (side != 0) {
                ruled = side; live = null
                rT0 = ruler.along(e.x, e.y); rT1 = rT0; rP = pressureOf(e, -1)
                updateRuled()
            }
        }
        invalidate()
    }

    /** Ruled stroke end points (page coords of [drawPage]) from the extent along the ruler, offset by half the width. */
    private fun updateRuled() {
        if (drawPage < 0) return
        val w = if (activeTool == Tool.HIGHLIGHTER) hlWidth else penWidth
        val off = w * scale / 2f
        val pl = pageLeft(drawPage); val pt = pageTops[drawPage]
        ruler.project(rT0, ruled, off, rTmp)
        rulA[0] = toDocX(rTmp[0]) - pl; rulA[1] = toDocY(rTmp[1]) - pt
        ruler.project(rT1, ruled, off, rTmp)
        rulA[2] = toDocX(rTmp[0]) - pl; rulA[3] = toDocY(rTmp[1]) - pt
    }

    /** Starts dragging handle [k] of a library shape: the selection's move / scale is baked in first. */
    private fun beginShapeHandle(s: Sel, st0: Stroke, k: Int) {
        var st = st0
        if (s.dx != 0f || s.dy != 0f || s.s != 1f) {
            st = st0.mapped({ x, y -> s.mapX(x) to s.mapY(y) }, s.s)
            s.strokes = listOf(st); s.dx = 0f; s.dy = 0f; s.s = 1f
            s.box.set(st.bounds())
        }
        shapeHandle = k
        val p = st.pts
        if (InkShapes.isLine(st.shape)) {
            val o = if (k == 0) 3 else 0
            shapeFixX = p[o]; shapeFixY = p[o + 1]
        } else {
            val b = InkShapes.boxOf(st)
            // opposite corner: 0 TL ↔ 3 BR, 1 TR ↔ 2 BL
            shapeFixX = if (k == 0 || k == 2) b.right else b.left
            shapeFixY = if (k < 2) b.bottom else b.top
        }
        if (!s.changed) { s.changed = true }
    }

    private fun dragShapeHandle(s: Sel, e: MotionEvent) {
        val st = s.strokes.firstOrNull() ?: return
        val x = toDocX(e.x) - pageLeft(s.page); val y = toDocY(e.y) - pageTops[s.page]
        val n = if (InkShapes.isLine(st.shape)) {
            if (shapeHandle == 0) InkShapes.rebuilt(st, x, y, shapeFixX, shapeFixY) else InkShapes.rebuilt(st, shapeFixX, shapeFixY, x, y)
        } else {
            val minS = 4f
            val nx = if (abs(x - shapeFixX) < minS) shapeFixX + (if (x >= shapeFixX) minS else -minS) else x
            val ny = if (abs(y - shapeFixY) < minS) shapeFixY + (if (y >= shapeFixY) minS else -minS) else y
            InkShapes.rebuilt(st, shapeFixX, shapeFixY, nx, ny)
        }
        s.strokes = listOf(n)
        s.box.set(n.bounds())
        s.changed = true
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
        val qx = (x * 10f).toInt() / 10f
        val qy = (y * 10f).toInt() / 10f
        val qp = (p * 100f).toInt() / 100f
        pts[npts * 3] = qx; pts[npts * 3 + 1] = qy; pts[npts * 3 + 2] = qp
        npts++
        live?.add(qx, qy, qp)
        if (activeTool == Tool.LASSO) { if (npts == 1) lassoPath.moveTo(qx, qy) else lassoPath.lineTo(qx, qy) }
    }

    private fun onMove(e: MotionEvent) {
        velocity?.addMovement(e)
        if (!moved && hypot(e.x - downX, e.y - downY) > slop) { moved = true; main.removeCallbacks(longPress) }
        when (mode) {
            Mode.DRAW -> {
                if (shapeDrag) {
                    if (drawPage >= 0) {
                        val x = toDocX(e.x) - pageLeft(drawPage); val y = toDocY(e.y) - pageTops[drawPage]
                        val constrain = e.pointerCount >= 2 || (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
                        shapePreview = dragShape(x, y, constrain)
                        invalidate()
                    }
                    return
                }
                if (ruled != 0) {
                    val t = ruler.along(e.x, e.y)
                    if (t < rT0) rT0 = t
                    if (t > rT1) rT1 = t
                    rP = pressureOf(e, -1)
                    updateRuled(); invalidate()
                    return
                }
                when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.LASSO, Tool.TAPE -> if (drawPage >= 0) {
                        val pl = pageLeft(drawPage); val pt = pageTops[drawPage]
                        if (activeTool != Tool.TAPE) for (k in 0 until e.historySize) {
                            addPoint(toDocX(e.getHistoricalX(k)) - pl, toDocY(e.getHistoricalY(k)) - pt, pressureOf(e, k))
                        }
                        addPoint(toDocX(e.x) - pl, toDocY(e.y) - pt, pressureOf(e, -1))
                    }
                    Tool.ERASER -> {
                        for (k in 0 until e.historySize) eraseAt(e.getHistoricalX(k), e.getHistoricalY(k))
                        eraseAt(e.x, e.y); eraserX = e.x; eraserY = e.y
                    }
                    Tool.LASER -> {
                        for (k in 0 until e.historySize) laserAdd(e.getHistoricalX(k), e.getHistoricalY(k))
                        laserAdd(e.x, e.y)
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
                growIfNeeded(); clamp(); updateCurrentPage(); invalidate()
            }
            Mode.PEN_ZOOM -> {
                val f = exp((lastFy - e.y) / (160f * density))
                lastFy = e.y
                zoomAt(downX, downY, f)
            }
            Mode.TEXT_DRAG -> if (moved) dragText(e)
            Mode.TEXT_PINCH -> if (e.pointerCount >= 2) pinchText(e)
            Mode.SEL_MOVE -> sel?.let { s ->
                s.dx += (e.x - selGrabX) / scale; s.dy += (e.y - selGrabY) / scale
                selGrabX = e.x; selGrabY = e.y; s.changed = true; invalidate()
            }
            Mode.SEL_SCALE -> sel?.let { s ->
                val b = selScreenBox(s, selRect)
                unrotate(s, b, e.x, e.y)
                val l = b.left; val t = b.top
                val origW = s.box.width() * scale; val origH = s.box.height() * scale
                val ns = max((unrot[0] - l) / max(origW, 1f), (unrot[1] - t) / max(origH, 1f)).coerceIn(0.15f, 8f)
                s.s = ns; s.changed = true; invalidate()
            }
            Mode.SEL_ROTATE -> sel?.let { s ->
                val b = selScreenBox(s, selRect)
                val raw = selRot0 + InkShapes.angle(e.x - b.centerX(), e.y - b.centerY()) - selRotGrab
                val step = Math.round(raw / 15f) * 15f
                val snap = abs(raw - step) < 3f
                if (snap && selRotSnap != step) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                selRotSnap = if (snap) step else Float.NaN
                var r = (if (snap) step else raw) % 360f
                if (r > 180f) r -= 360f
                if (r < -180f) r += 360f
                s.rot = if (abs(r) < 1e-3f) 0f else r
                s.changed = true; invalidate()
            }
            Mode.SEL_SHAPE -> sel?.let { s -> dragShapeHandle(s, e) }
            Mode.RULER -> {
                if (e.pointerCount >= 2) { if (ruler.two(e.getX(0), e.getY(0), e.getX(1), e.getY(1))) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                else ruler.drag(e.x, e.y)
                invalidate()
            }
            Mode.NONE -> {}
        }
    }

    private fun onUp(e: MotionEvent) {
        main.removeCallbacks(longPress)
        val wasTap = !moved && SystemClock.uptimeMillis() - downTime < 350
        when (mode) {
            Mode.DRAW -> {
                val consumed = wasTap && activeTool != Tool.ERASER && activeTool != Tool.LASER && tapAction(e.x, e.y)
                if (!consumed) when (activeTool) {
                    Tool.PEN, Tool.HIGHLIGHTER -> if (drawPage >= 0 && ruled != 0 && hypot(rulA[2] - rulA[0], rulA[3] - rulA[1]) * scale > density) {
                        commitStroke(makeRuled())
                    } else if (drawPage >= 0 && npts > 0) commitStroke(makeStroke())
                    Tool.SHAPE -> if (shapeDrag) {
                        val st = shapePreview
                        if (!moved || st == null) lassoTap(e.x, e.y)       // tap: select the shape under it (handles)
                        else if (drawPage >= 0) commitStroke(st)
                    } else if (!moved) lassoTap(e.x, e.y)
                    else if (drawPage >= 0 && npts > 1) commitStroke(recognizeShape(makeStroke()))
                    Tool.LASSO -> if (!moved) { val tx = textAtScreen(e.x, e.y); if (tx != null) selectText(tx.first, tx.second) else lassoTap(e.x, e.y) }
                        else if (drawPage >= 0 && npts > 2) lassoSelect()
                    Tool.TAPE -> if (drawPage >= 0 && npts > 1) commitTape()
                    Tool.TEXT -> if (!moved) textTap(e.x, e.y) else textDragCreate(downX, downY, e.x, e.y)
                    Tool.ERASER -> { eraserOn = false; if (eraseUndoPushed) changed() }
                }
                eraserOn = false
                npts = 0; live = null; ruled = 0; shapeDrag = false; shapePreview = null
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
            Mode.PEN_ZOOM -> settleSoon()
            Mode.TEXT_DRAG -> {
                // a tap on a selected (not edited) box edits it in place
                if (!moved && editItem == null && tHandle == H_MOVE) currentText()?.let { (pg, t) -> beginEdit(pg, t, false) }
                else finishTextGesture()
            }
            Mode.TEXT_PINCH -> finishTextGesture()
            Mode.SEL_MOVE, Mode.SEL_SCALE, Mode.SEL_ROTATE, Mode.SEL_SHAPE -> invalidate()
            Mode.RULER -> invalidate()
            Mode.NONE -> {}
        }
        mode = Mode.NONE
        velocity?.recycle(); velocity = null
        growIfNeeded()
        invalidate()
    }

    /** Tap on a link opens it; tap on a tape toggles it. Returns true when the tap was used. */
    private fun tapAction(x: Float, y: Float): Boolean {
        val h = hit(x, y, strict = false) ?: return false
        linkAt(h.first, h.second, h.third)?.let { listener?.onLinkOpen(it); return true }
        tapeAt(h.first, h.second, h.third)?.let { it.revealed = !it.revealed; invalidate(); return true }
        return false
    }

    private fun navTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val h = hit(x, y)
        if (playRec != 0 && h != null) {
            nearestStroke(h.first, h.second, h.third, 14f * density / scale)?.let { s ->
                if (s.rec != 0 && s.t >= 0) { listener?.onSeek(s.rec, s.t); return }
            }
        }
        if (tapAction(x, y)) return
        if (tool == Tool.TEXT && h != null) { textTap(x, y); return }
        if (h != null) textAt(h.first, h.second, h.third)?.let { selectText(h.first, it); return }
        if (h != null) stickerAt(h.first, h.second, h.third)?.let { liftOne(h.first, sticker = it); return }
        if (now - lastTapTime < 300) {
            // double tap (finger, or pen with the Hand tool): toggle fit <-> 2x
            if (scale > fitScale * 1.2f) zoomAt(x, y, fitScale / scale) else zoomAt(x, y, 2f)
            settleSoon(); lastTapTime = 0
        } else lastTapTime = now
    }

    /** Text tool tap: edit the box under the tap in place, or start a new box right there. */
    private fun textTap(x: Float, y: Float) {
        // a tap elsewhere while typing commits that box (in onDown) and starts the next one right here
        val h = hit(x, y) ?: return
        val existing = textAt(h.first, h.second, h.third)
        if (existing != null) { beginEdit(h.first, existing, false); return }
        // inside a table cell: the box fills the cell's width
        doc.pages[h.first].strokes.lastOrNull { InkShapes.tableDims(it.shape) != null && InkShapes.boxOf(it).contains(h.second, h.third) }
            ?.let { InkShapes.cellAt(it, h.second, h.third) }?.let { cell ->
                val pad = max(2f, min(6f, cell.width() * 0.06f))
                val size = min(textSize, max(6f, (cell.height() - 2 * pad) * 0.7f))
                beginEdit(h.first, TextItem(System.nanoTime(), cell.left + pad, cell.top + pad, max(16f, cell.width() - 2 * pad), "", size,
                    textColor, textFont, textBold), true)
                return
            }
        val p = doc.pages[h.first]
        val size = textSize
        var w = if (doc.infinite) 320f else min(320f, max(120f, p.w - 24f))
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var left = if (rtl) h.second - w else h.second
        if (!doc.infinite) {
            w = min(w, p.w - 8f)
            left = left.coerceIn(4f, max(4f, p.w - w - 4f))
        }
        val top = h.third - size * 0.7f
        beginEdit(h.first, TextItem(System.nanoTime(), left, if (doc.infinite) top else top.coerceIn(0f, max(0f, p.h - size)), w, "", size, textColor, textFont, textBold), true)
    }

    /** Text tool drag: the drag sets the box width (and where it starts). */
    private fun textDragCreate(x0: Float, y0: Float, x1: Float, y1: Float) {
        val a = hit(min(x0, x1), min(y0, y1), strict = false) ?: return
        val b = hit(max(x0, x1), max(y0, y1), strict = false) ?: return
        if (a.first != b.first) return
        val p = doc.pages[a.first]
        val w = max(40f, b.second - a.second)
        var left = a.second
        if (!doc.infinite) left = left.coerceIn(0f, max(0f, p.w - w))
        beginEdit(a.first, TextItem(System.nanoTime(), left, a.third, w, "", textSize, textColor, textFont, textBold), true)
    }

    private fun textAt(page: Int, x: Float, y: Float): TextItem? =
        doc.pages[page].texts.lastOrNull { InkRender.layout(it); it.bounds().contains(x, y) }

    private fun textAtScreen(x: Float, y: Float): Pair<Int, TextItem>? {
        val h = hit(x, y, strict = false) ?: return null
        if (h.first !in doc.pages.indices) return null
        return textAt(h.first, h.second, h.third)?.let { h.first to it }
    }

    private fun linkAt(page: Int, x: Float, y: Float): LinkItem? = doc.pages.getOrNull(page)?.links?.lastOrNull { it.contains(x, y) }

    private fun linkAtScreen(x: Float, y: Float): Pair<Int, LinkItem>? {
        val h = hit(x, y, strict = false) ?: return null
        return linkAt(h.first, h.second, h.third)?.let { h.first to it }
    }

    private fun tapeAt(page: Int, x: Float, y: Float): Stroke? = doc.pages.getOrNull(page)?.strokes?.lastOrNull { s ->
        s.isTape && s.pts.size >= 6 && segDist(x, y, s.pts[0], s.pts[1], s.pts[3], s.pts[4]) <= s.width / 2f
    }

    // ---- mouse / trackpad ----

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (doc.pages.isEmpty()) return super.onGenericMotionEvent(e)
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            var hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if ((e.metaState and KeyEvent.META_CTRL_ON) != 0) {
                if (v != 0f) { zoomAt(e.x, e.y, 1.15f.pow(v)); settleSoon() }
                return true
            }
            var vs = v
            if ((e.metaState and KeyEvent.META_SHIFT_ON) != 0 && hs == 0f) { hs = -vs; vs = 0f }
            scroller.forceFinished(true)
            val step = 64f * density
            sy -= vs * step; sx += hs * step
            growIfNeeded(); clamp(); updateCurrentPage(); settleSoon(); invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    // =====================================================================================
    // Drag & drop (round 2 contract: daftar:file:<path>, http(s) URL, plain text, image content URIs)
    // =====================================================================================

    private fun handleDrag(ev: DragEvent): Boolean = when (ev.action) {
        DragEvent.ACTION_DRAG_STARTED -> doc.pages.isNotEmpty()
        DragEvent.ACTION_DROP -> runCatching { handleDrop(ev) }.getOrElse { listener?.onDropFailed(); false }
        else -> true
    }

    private fun dropTarget(x: Float, y: Float): Triple<Int, Float, Float> {
        hit(x, y, strict = false)?.let { (i, px, py) ->
            if (doc.infinite) return Triple(i, px, py)
            val p = doc.pages[i]
            return Triple(i, px.coerceIn(0f, p.w), py.coerceIn(0f, p.h))
        }
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val (cx, cy) = visibleCenter(i)
        return Triple(i, cx, cy)
    }

    private fun handleDrop(ev: DragEvent): Boolean {
        val clip = ev.clipData ?: return false
        if (clip.itemCount == 0 || doc.pages.isEmpty()) return false
        commitSelection()
        val (page, px, py) = dropTarget(ev.x, ev.y)
        var used = false
        var perms: DragAndDropPermissions? = null
        for (k in 0 until clip.itemCount) {
            val item = clip.getItemAt(k)
            val x = px + k * 28f; val y = py + k * 28f
            val text = item.text?.toString()?.trim()
            val uri = item.uri
            when {
                text != null && text.startsWith(FILE_PREFIX) -> {
                    val f = File(text.removePrefix(FILE_PREFIX).lineSequence().first().trim())
                    if (!f.isFile) { listener?.onDropFailed(); continue }
                    if (Storage.kindOf(f) == Kind.IMAGE) decodeAndInsert(Uri.fromFile(f), page, x, y, null)
                    else addLinkAt(page, x, y, f.absolutePath, LinkItem.defaultLabel(f.absolutePath))
                    used = true
                }
                text != null && isWebUrl(text) -> { addLinkAt(page, x, y, text, LinkItem.defaultLabel(text)); used = true }
                uri != null && (uri.scheme == "http" || uri.scheme == "https") -> {
                    val u = uri.toString(); addLinkAt(page, x, y, u, LinkItem.defaultLabel(u)); used = true
                }
                uri != null && isImageUri(uri, clip.description.getMimeType(0)) -> {
                    if (perms == null && uri.scheme == "content") perms = context.findActivity()?.requestDragAndDropPermissions(ev)
                    decodeAndInsert(uri, page, x, y, if (k == clip.itemCount - 1) perms else null)
                    used = true
                }
                !text.isNullOrBlank() -> { addTextAt(page, x, y, text); used = true }
                else -> {
                    val t = runCatching { item.coerceToText(context)?.toString()?.trim() }.getOrNull()
                    if (!t.isNullOrBlank()) { if (isWebUrl(t)) addLinkAt(page, x, y, t, LinkItem.defaultLabel(t)) else addTextAt(page, x, y, t); used = true }
                }
            }
        }
        if (!used) listener?.onDropFailed()
        return used
    }

    private fun isWebUrl(t: String) = !t.contains('\n') && !t.contains(' ') && (t.startsWith("http://", true) || t.startsWith("https://", true))

    private fun isImageUri(uri: Uri, clipMime: String?): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: clipMime ?: ""
        if (mime.startsWith("image/")) return true
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        return ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic")
    }

    private fun decodeAndInsert(uri: Uri, page: Int, x: Float, y: Float, perms: DragAndDropPermissions?) {
        val gen = generation
        val ctx = context
        val ok = runCatching {
            exec.execute {
                val b = loadBitmap(ctx, uri, 2000)
                main.post {
                    perms?.release()
                    if (gen != generation || page >= doc.pages.size) { return@post }
                    if (b == null) listener?.onDropFailed() else insertImageAt(page, x, y, b)
                }
            }
        }.isSuccess
        if (!ok) { perms?.release(); listener?.onDropFailed() }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    // =====================================================================================
    // Edits
    // =====================================================================================

    private fun snapshot() = doc.pages.toList()

    private fun pushUndo() {
        undo.addLast(snapshot()); if (undo.size > 80) undo.removeFirst()
        redo.clear(); notifyUndo()
    }

    private fun notifyUndo() = listener?.onUndoStateChanged(undo.isNotEmpty(), redo.isNotEmpty())

    private fun changed() { if (mode != Mode.DRAW) growIfNeeded(); listener?.onChanged(); invalidate() }

    private fun setPage(i: Int, p: InkPage) {
        doc.pages = doc.pages.toMutableList().also { it[i] = p }
    }

    fun undo() {
        endTextEdit()
        commitSelection()
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(snapshot()); restore(prev)
    }

    fun redo() {
        endTextEdit()
        commitSelection()
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot()); restore(next)
    }

    /** Restores a snapshot; on a whiteboard the scroll follows any growth shift so the view does not jump. */
    private fun restore(pages: List<InkPage>) {
        val before = doc.pages.firstOrNull()
        val ax = toScreenX(0f); val ay = toScreenY(0f)
        doc.pages = pages
        relayout()
        if (doc.infinite && before != null && pages.isNotEmpty()) {
            val dx = pages[0].shiftX - before.shiftX; val dy = pages[0].shiftY - before.shiftY
            sx = margin + dx * scale + offX() - ax
            sy = margin + dy * scale - ay
        }
        clamp(); updateCurrentPage(); notifyUndo(); changed()
        if (textSelPage >= 0 && currentText() == null) { textSelPage = -1; textSelId = 0L }
        if (mathPage >= 0 && doc.pages.getOrNull(mathPage)?.texts?.none { it.id == mathId } != false) { mathPage = -1; mathId = 0L }
        notifyTextUi()
    }

    private fun stampTime(s: Stroke): Stroke =
        if (recId == 0) s else s.withTime(recId, SystemClock.elapsedRealtime() - recClockStart + recOffset)

    private fun makeStroke(): Stroke {
        val arr = pts.copyOf(npts * 3)
        val s = when (activeTool) {
            Tool.HIGHLIGHTER -> Stroke(Tool.HIGHLIGHTER, hlColor, hlWidth, arr)
            Tool.SHAPE -> Stroke(Tool.SHAPE, shapeColor, penWidth, arr)
            else -> Stroke(Tool.PEN, penColor, penWidth, arr, style = penStyle)
        }
        // reuse the geometry built while drawing (the brush is rebuilt once to taper its end)
        live?.let { g -> g.finish(); if (!(s.tool == Tool.PEN && s.style == PenStyle.BRUSH)) s.geom = g }
        return s
    }

    /**
     * Library shape from the drag start to ([x], [y]) (page coords). [constrain] (S Pen button / second finger): square
     * box (circle, equal-sided star…), lines snapped to 45°.
     */
    private fun dragShape(x: Float, y: Float, constrain: Boolean): Stroke {
        var ex = x; var ey = y
        if (constrain) {
            val dx = x - shX0; val dy = y - shY0
            if (InkShapes.isLine(shapeKind)) {
                val len = hypot(dx, dy)
                val a = Math.round(Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()) / 45.0) * 45.0
                val r = Math.toRadians(a)
                ex = shX0 + (cos(r) * len).toFloat(); ey = shY0 + (sin(r) * len).toFloat()
            } else {
                val m = max(abs(dx), abs(dy))
                ex = shX0 + (if (dx < 0) -m else m); ey = shY0 + (if (dy < 0) -m else m)
            }
        }
        if (!InkShapes.isLine(shapeKind)) {
            // never a zero-sized box
            if (abs(ex - shX0) < 1f) ex = shX0 + 1f
            if (abs(ey - shY0) < 1f) ey = shY0 + 1f
        }
        return InkShapes.build(shapeKind, shX0, shY0, ex, ey, shapeColor, penWidth)
    }

    /** The straight stroke drawn along the ruler (two points; geometry built like any saved stroke). */
    private fun makeRuled(): Stroke {
        val p = rP.coerceIn(0f, 1f)
        val q = { v: Float -> (v * 10f).toInt() / 10f }
        val arr = floatArrayOf(q(rulA[0]), q(rulA[1]), p, q(rulA[2]), q(rulA[3]), p)
        return if (activeTool == Tool.HIGHLIGHTER) Stroke(Tool.HIGHLIGHTER, hlColor, hlWidth, arr)
        else Stroke(Tool.PEN, penColor, penWidth, arr, style = penStyle)
    }

    private fun commitStroke(s: Stroke) {
        pushUndo()
        val p = doc.pages[drawPage]
        setPage(drawPage, p.copy(strokes = p.strokes + stampTime(s)))
        changed()
        if (mathHelper && s.tool == Tool.PEN && !paused) {
            mathCandPage = drawPage
            main.removeCallbacks(mathDetect); main.postDelayed(mathDetect, 600)
        }
    }

    private fun commitTape() {
        val x0 = pts[0]; val y0 = pts[1]
        val x1 = pts[(npts - 1) * 3]; val y1 = pts[(npts - 1) * 3 + 1]
        if (hypot(x1 - x0, y1 - y0) < max(6f, tapeWidth * 0.3f)) return
        val s = Stroke(Tool.TAPE, tapeColor, tapeWidth, floatArrayOf(x0, y0, -1f, x1, y1, -1f))
        live?.let { g -> g.finish(); s.geom = g }
        commitStroke(s)
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
        doc.pages[page].strokes.lastOrNull { !it.isTape && strokeNear(it, x, y, r) }

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
        val links = page.links.filter { inPoly(it.x + it.w / 2f, it.y + it.h / 2f, poly, n) }
        val stickers = page.stickers.filter { inPoly(it.x + it.w / 2f, it.y + it.h / 2f, poly, n) }
        if (strokes.isEmpty() && texts.isEmpty() && images.isEmpty() && links.isEmpty() && stickers.isEmpty()) return
        lift(drawPage, strokes, texts, images, links, stickers)
    }

    private fun stickerAt(page: Int, x: Float, y: Float): StickerItem? =
        doc.pages.getOrNull(page)?.stickers?.lastOrNull { it.bounds().contains(x, y) }

    /** Lasso tap: picks the sticker, or else the library shape, under the tap. True when something was selected. */
    private fun lassoTap(x: Float, y: Float): Boolean {
        val h = hit(x, y, strict = false) ?: return false
        stickerAt(h.first, h.second, h.third)?.let { liftOne(h.first, sticker = it); return true }
        val r = 12f * density / scale
        doc.pages[h.first].strokes.lastOrNull { it.shape.isNotEmpty() && strokeNear(it, h.second, h.third, r) }?.let {
            liftOne(h.first, stroke = it); return true
        }
        return false
    }

    private fun liftOne(page: Int, sticker: StickerItem? = null, stroke: Stroke? = null) {
        commitSelection()
        lift(page, listOfNotNull(stroke), emptyList(), emptyList(), emptyList(), listOfNotNull(sticker))
    }

    private fun lift(pageIdx: Int, strokes: List<Stroke>, texts: List<TextItem>, images: List<ImageItem>, links: List<LinkItem>, stickers: List<StickerItem> = emptyList()) {
        pushUndo()
        val page = doc.pages[pageIdx]
        val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        strokes.forEach { box.union(it.bounds()) }
        texts.forEach { InkRender.layout(it); box.union(it.bounds()) }
        images.forEach { box.union(it.bounds()) }
        links.forEach { box.union(it.bounds()) }
        stickers.forEach { box.union(it.bounds()) }
        val sIds = strokes.toHashSet()
        val tIds = texts.map { it.id }.toHashSet(); val iIds = images.map { it.id }.toHashSet(); val lIds = links.map { it.id }.toHashSet()
        val kIds = stickers.map { it.id }.toHashSet()
        setPage(pageIdx, page.copy(
            strokes = page.strokes.filterNot { it in sIds },
            texts = page.texts.filterNot { it.id in tIds },
            images = page.images.filterNot { it.id in iIds },
            links = page.links.filterNot { it.id in lIds },
            stickers = page.stickers.filterNot { it.id in kIds },
        ))
        sel = Sel(pageIdx, strokes, texts, images, links, box, stickers)
        listener?.onSelectionChanged(true)
        invalidate()
    }

    private class Mapped(val strokes: List<Stroke>, val texts: List<TextItem>, val images: List<ImageItem>, val links: List<LinkItem>,
        val stickers: List<StickerItem> = emptyList())

    private fun mappedSelection(s: Sel): Mapped {
        if (!s.changed) return Mapped(s.strokes, s.texts, s.images, s.links, s.stickers)
        val st = s.strokes.map {
            val m = it.mapped({ x, y -> s.map(x, y) }, s.s)
            // a rotated box shape is no longer an upright box: it stays a plain shape (no box handles)
            if (s.rot != 0f && InkShapes.isBox(m.shape)) Stroke(m.tool, m.color, m.width, m.pts, m.rec, m.t, m.style) else m
        }
        val sk = s.stickers.map { k ->
            val (cx, cy) = s.map(k.x + k.w / 2f, k.y + k.h / 2f)
            val w = k.w * s.s; val h = k.h * s.s
            var r = (k.rot + s.rot) % 360f
            if (r > 180f) r -= 360f
            if (r < -180f) r += 360f
            k.copy(x = cx - w / 2f, y = cy - h / 2f, w = w, h = h, rot = r)
        }
        val tx = s.texts.map { t -> t.copy(x = s.mapX(t.x), y = s.mapY(t.y), w = t.w * s.s, size = t.size * s.s) }
        val im = s.images.map { i -> i.copy(x = s.mapX(i.x), y = s.mapY(i.y), w = i.w * s.s, h = i.h * s.s).also { it.bmp = i.bmp } }
        val ln = s.links.map { l -> l.copy(x = s.mapX(l.x), y = s.mapY(l.y), w = l.w * s.s, h = l.h * s.s) }
        return Mapped(st, tx, im, ln, sk)
    }

    private fun putBack(pageIdx: Int, m: Mapped) {
        val page = doc.pages[pageIdx]
        setPage(pageIdx, page.copy(strokes = page.strokes + m.strokes, texts = page.texts + m.texts, images = page.images + m.images, links = page.links + m.links,
            stickers = page.stickers + m.stickers))
    }

    fun commitSelection() {
        val s = sel ?: return
        sel = null
        putBack(s.page, mappedSelection(s))
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
        val ns = Sel(s.page, s.strokes.map {
            when {
                it.isTape -> it
                it.tool == Tool.HIGHLIGHTER -> it.withColor((color and 0x00FFFFFF) or 0x66000000)
                else -> it.withColor(color)
            }
        }, s.texts.map { it.copy(color = color) }, s.images, s.links, s.box, s.stickers.map { it.copy(color = color) })
            .also { it.dx = s.dx; it.dy = s.dy; it.s = s.s; it.rot = s.rot; it.changed = true }
        sel = ns; invalidate()
    }

    fun duplicateSelection() {
        val s = sel ?: return
        val m = mappedSelection(s)
        putBack(s.page, m)
        val off = 24f
        val now = System.nanoTime()
        sel = Sel(s.page,
            m.strokes.map { it.mapped({ x, y -> (x + off) to (y + off) }) },
            m.texts.mapIndexed { k, t -> t.copy(id = now + k, x = t.x + off, y = t.y + off) },
            m.images.mapIndexed { k, i -> i.copy(id = now + 1000 + k, x = i.x + off, y = i.y + off).also { it.bmp = i.bmp } },
            m.links.mapIndexed { k, l -> l.copy(id = now + 2000 + k, x = l.x + off, y = l.y + off) },
            RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE).apply {
                m.strokes.forEach { union(it.bounds()) }; m.texts.forEach { InkRender.layout(it); union(it.bounds()) }
                m.images.forEach { union(it.bounds()) }; m.links.forEach { union(it.bounds()) }; m.stickers.forEach { union(it.bounds()) }
                offset(off, off)
            },
            m.stickers.mapIndexed { k, t -> t.copy(id = now + 3000 + k, x = t.x + off, y = t.y + off) },
        ).also { it.changed = true }
        invalidate()
    }

    /** Ink strokes of the current selection, mapped to their on-page position (for handwriting recognition). */
    fun selectedStrokes(): List<Stroke> = sel?.let { mappedSelection(it).strokes.filter { s -> s.tool == Tool.PEN } } ?: emptyList()

    fun selectionBounds(): RectF? = sel?.let { s -> RectF(s.mapX(s.box.left), s.mapY(s.box.top), s.mapX(s.box.right), s.mapY(s.box.bottom)) }

    /** Replace the selected pen strokes with a typed text box at the same place. */
    fun replaceSelectionWithText(text: String) {
        val s = sel ?: return
        val b = selectionBounds() ?: return
        val pens = s.strokes.filter { it.tool == Tool.PEN }
        val lines = text.lines().size.coerceAtLeast(1)
        val size = (b.height() / lines / 1.35f).coerceIn(10f, 48f)
        val item = TextItem(System.nanoTime(), b.left, b.top, max(b.width(), size * 4), text, size, pens.firstOrNull()?.color ?: penColor)
        val m = mappedSelection(s)
        sel = null
        putBack(s.page, Mapped(m.strokes.filter { it.tool != Tool.PEN }, m.texts + item, m.images, m.links, m.stickers))
        notifyUndo(); listener?.onSelectionChanged(false); changed()
    }

    // ---- text, images, links ----

    /** Centre of the visible part of page [i], in page coordinates. */
    private fun visibleCenterOf(i: Int) = visibleCenter(i)

    private fun visibleCenter(i: Int): Pair<Float, Float> {
        val p = doc.pages[i]
        val x = toDocX(width / 2f) - pageLeft(i)
        val y = toDocY(height / 2f) - pageTops[i]
        return if (doc.infinite) x to y else x.coerceIn(0f, p.w) to y.coerceIn(0f, p.h)
    }

    // ---- text boxes on the canvas ----

    /** Which part of the selected / edited box's frame is under ([x], [y]): a handle, the body (move) or nothing (0). */
    private fun textHandleAt(x: Float, y: Float): Int {
        val (page, t) = currentText() ?: return 0
        if (page >= pageTops.size) return 0
        val b = textScreenRect(page, t, textRect)
        val pad = 6 * density
        b.inset(-pad, -pad)
        val hr = 22 * density
        if (hypot(x - b.left, y - b.top) < hr) return H_TL
        if (hypot(x - b.right, y - b.top) < hr) return H_TR
        if (hypot(x - b.left, y - b.bottom) < hr) return H_BL
        if (hypot(x - b.right, y - b.bottom) < hr) return H_BR
        val inY = y > b.top && y < b.bottom
        if (inY && abs(x - b.left) < 16 * density) return H_L
        if (inY && abs(x - b.right) < 16 * density) return H_R
        // body (while editing, touches inside the box go to the editor; the frame ring moves the box)
        val ring = 14 * density
        if (x > b.left - ring && x < b.right + ring && y > b.top - ring && y < b.bottom + ring) return H_MOVE
        return 0
    }

    /** Applies a live change of the selected / edited box (one undo step per gesture). */
    private fun applyTextLive(n: TextItem) {
        if (editItem != null) { editItem = n; textOverlay.apply(n); invalidate(); return }
        val page = textSelPage
        if (page < 0) return
        if (!tUndoPushed) { pushUndo(); tUndoPushed = true }
        replaceText(page, n)
        invalidate()
    }

    private fun replaceText(page: Int, n: TextItem) {
        val p = doc.pages[page]
        setPage(page, p.copy(texts = p.texts.map { if (it.id == n.id) n else it }))
    }

    private fun dragText(e: MotionEvent) {
        val t0 = tStart ?: return
        val dx = (e.x - tGrabX) / scale; val dy = (e.y - tGrabY) / scale
        val w0 = t0.w; val h0 = max(1f, tStartH)
        val minW = max(24f, t0.size * 1.2f)
        val n = when (tHandle) {
            H_MOVE -> t0.copy(x = t0.x + dx, y = t0.y + dy)
            H_L -> { val w = (w0 - dx).coerceAtLeast(minW); t0.copy(x = t0.x + w0 - w, w = w) }
            H_R -> t0.copy(w = (w0 + dx).coerceAtLeast(minW))
            else -> {
                // corner: scale font and width proportionally, the opposite corner stays put
                val sxSign = if (tHandle == H_TR || tHandle == H_BR) 1f else -1f
                val sySign = if (tHandle == H_BL || tHandle == H_BR) 1f else -1f
                var f = ((w0 + sxSign * dx) * w0 + (h0 + sySign * dy) * h0) / (w0 * w0 + h0 * h0)
                f = f.coerceIn(max(0.05f, 4f / t0.size), 400f / t0.size)
                val w = w0 * f; val h = h0 * f
                t0.copy(size = t0.size * f, w = w, x = if (sxSign < 0) t0.x + w0 - w else t0.x, y = if (sySign < 0) t0.y + h0 - h else t0.y)
            }
        }
        applyTextLive(n)
    }

    private fun pinchText(e: MotionEvent) {
        val t0 = tStart ?: return
        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        val f = (d / tPinchD0).coerceIn(max(0.05f, 4f / t0.size), 400f / t0.size)
        val cx = t0.x + t0.w / 2f; val cy = t0.y + tStartH / 2f
        val w = t0.w * f; val h = tStartH * f
        applyTextLive(t0.copy(size = t0.size * f, w = w, x = cx - w / 2f, y = cy - h / 2f))
    }

    private fun finishTextGesture() {
        tStart = null
        if (tUndoPushed) { tUndoPushed = false; changed() }
        notifyTextUi()
        invalidate()
    }

    private fun notifyTextUi() {
        val cur = currentText()
        listener?.onTextBox(cur?.let { TextBoxUi(it.first, it.second, editItem != null) })
    }

    /** Selects a text box (frame + handles + format bar). */
    fun selectText(page: Int, item: TextItem) {
        commitSelection()
        if (editItem != null && editItem?.id != item.id) endTextEdit()
        textSelPage = page; textSelId = item.id
        notifyTextUi(); invalidate()
    }

    fun clearTextSelection() {
        if (textSelPage < 0 && editItem == null) return
        if (editItem != null) endTextEdit()
        textSelPage = -1; textSelId = 0L
        notifyTextUi(); invalidate()
    }

    /** Starts typing on the canvas in [item] ([isNew]: not on the page yet). */
    private fun beginEdit(page: Int, item: TextItem, isNew: Boolean) {
        commitSelection()
        if (editItem != null) endTextEdit()
        editPage = page; editItem = item; editIsNew = isNew
        textSelPage = page; textSelId = item.id
        textOverlay.show(item, textHint)
        val b = textScreenRect(page, item, textRect)
        textOverlay.place(b.left, b.top)
        notifyTextUi(); invalidate()
    }

    /** Edits the selected box in place. */
    fun editSelectedText() { currentText()?.let { (pg, t) -> if (editItem == null) beginEdit(pg, t, false) } }

    /** Ends on-canvas typing and commits it to the page as one undo step (blank text removes the box). */
    fun endTextEdit() {
        val t = editItem ?: return
        val page = editPage
        val text = textOverlay.text().trimEnd()
        editItem = null
        textOverlay.hide()
        if (page !in doc.pages.indices) { notifyTextUi(); return }
        val existing = doc.pages[page].texts.firstOrNull { it.id == t.id }
        if (text.isBlank()) {
            if (existing != null) deleteText(page, t.id)
            textSelPage = -1; textSelId = 0L
        } else {
            val n = t.copy(text = text)
            if (existing != n) upsertText(page, n)
        }
        notifyTextUi(); invalidate()
    }

    /** Ends typing and drops every selection (back, Done, tool change, leaving the editor). */
    fun finishEditing() {
        endTextEdit(); clearTextSelection(); commitSelection()
    }

    /** Changes the selected / edited box's formatting (live while typing; one undo step otherwise). */
    fun formatText(f: (TextItem) -> TextItem) {
        editItem?.let { val n = f(it); editItem = n; textOverlay.apply(n); notifyTextUi(); invalidate(); return }
        val (page, old) = currentText() ?: return
        val n = f(old)
        if (n == old) return
        pushUndo(); replaceText(page, n); changed(); notifyTextUi()
    }

    fun duplicateText() {
        if (editItem != null) endTextEdit()
        val (page, t) = currentText() ?: return
        val n = t.copy(id = System.nanoTime(), x = t.x + 24f, y = t.y + 24f)
        pushUndo()
        val p = doc.pages[page]
        setPage(page, p.copy(texts = p.texts + n))
        textSelPage = page; textSelId = n.id
        changed(); notifyTextUi()
    }

    fun deleteSelectedText() {
        val e = editItem
        if (e != null) {
            editItem = null; textOverlay.hide()
            if (!editIsNew && editPage in doc.pages.indices) deleteText(editPage, e.id)
        } else currentText()?.let { (pg, t) -> deleteText(pg, t.id) }
        textSelPage = -1; textSelId = 0L
        notifyTextUi(); invalidate()
    }

    /** Opens the classic text dialog for the selected box ("Edit text…" fallback). */
    fun editSelectedTextInDialog() {
        if (editItem != null) endTextEdit()
        val (pg, t) = currentText() ?: return
        listener?.onTextRequest(pg, t.x, t.y, t)
    }

    /** Scrolls so the caret of the on-canvas editor stays visible above the keyboard (and the box horizontally). */
    private fun ensureCaretVisible() {
        if (editItem == null || !textOverlay.showing || width == 0 || height == 0) return
        val (top, bottom) = textOverlay.caretLine() ?: return
        val ed = textOverlay.edit
        val m = 24 * density
        val ct = ed.translationY + top; val cb = ed.translationY + bottom
        var dy = 0f
        if (cb > height - m) dy = cb - (height - m) else if (ct < m) dy = ct - m
        var dx = 0f
        val l = ed.translationX; val r = l + ed.width
        if (ed.width < width - 2 * m) { if (r > width - m) dx = r - (width - m) else if (l < m) dx = l - m }
        if (dx == 0f && dy == 0f) return
        scroller.forceFinished(true)
        sx += dx; sy += dy
        growIfNeeded(); clamp(); updateCurrentPage(); invalidate()
    }

    fun upsertText(page: Int, item: TextItem) {
        commitSelection()
        pushUndo()
        val p = doc.pages[page]
        val exists = p.texts.any { it.id == item.id }
        setPage(page, p.copy(texts = if (exists) p.texts.map { if (it.id == item.id) item else it } else p.texts + item))
        changed()
    }

    fun deleteText(page: Int, id: Long) {
        if (doc.pages.getOrNull(page)?.texts?.none { it.id == id } != false) return
        pushUndo()
        val p = doc.pages[page]
        setPage(page, p.copy(texts = p.texts.filterNot { it.id == id }))
        changed()
    }

    /** Text dropped at the visible centre of the current page (dictation results). */
    fun addTextAtCenter(text: String, size: Float, color: Int, font: String) {
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val p = doc.pages[i]
        if (doc.infinite) {
            val (cx, cy) = visibleCenter(i)
            val w = min(420f, width / scale * 0.8f)
            upsertText(i, TextItem(System.nanoTime(), cx - w / 2f, cy - size, w, text, size, color, font))
            return
        }
        val cy = (toDocY(height / 3f) - pageTops[i]).coerceIn(40f, p.h - 60f)
        upsertText(i, TextItem(System.nanoTime(), 40f, cy, p.w - 80f, text, size, color, font))
    }

    private fun addTextAt(page: Int, x: Float, y: Float, text: String) {
        val p = doc.pages[page]
        val w = if (doc.infinite) 320f else min(320f, p.w - x - 12f).coerceAtLeast(120f)
        val left = if (doc.infinite) x else min(x, p.w - w - 4f).coerceAtLeast(0f)
        upsertText(page, TextItem(System.nanoTime(), left, y, w, text, 16f, penColor))
    }

    /** Sticker / shape size factor: the same on-screen size whatever the zoom (whiteboards zoomed out, pages zoomed in). */
    private fun insertScale() = if (fitScale > 0f) (fitScale / scale).coerceIn(0.4f, 4f) else 1f

    /**
     * Inserts a sticker at the middle of the view (page under it); it floats selected so it can be moved, resized and
     * rotated right away. One undo step.
     */
    fun addSticker(kind: String, text: String, color: Int = InkStickers.defaultColor(kind)) {
        if (doc.pages.isEmpty()) return
        finishEditing()
        val i = targetPage()
        val p = doc.pages[i]
        val (nw, nh) = InkStickers.naturalSize(kind, text)
        val k = insertScale()
        val w = nw * k; val h = nh * k
        val (cx, cy) = visibleCenterOf(i)
        var x = cx - w / 2f; var y = cy - h / 2f
        if (!doc.infinite) { x = x.coerceIn(0f, max(0f, p.w - w)); y = y.coerceIn(0f, max(0f, p.h - h)) }
        val item = StickerItem(System.nanoTime(), x, y, w, h, kind, color, 0f, text)
        pushUndo()
        sel = Sel(i, emptyList(), emptyList(), emptyList(), emptyList(), item.bounds(), listOf(item)).also { it.changed = true }
        listener?.onSelectionChanged(true)
        changed()
    }

    /** Inserts a library shape (pen colour / width) at the middle of the view, floating selected with its handles. */
    fun addShape(shape: String, color: Int = penColor, width: Float = penWidth) {
        if (doc.pages.isEmpty()) return
        finishEditing()
        val i = targetPage()
        val p = doc.pages[i]
        val (dw, dh) = InkShapes.defaultSize(shape)
        var k = insertScale()
        if (!doc.infinite) k = min(k, p.w * 0.9f / dw)
        val w = dw * k; val h = dh * k
        val (cx, cy) = visibleCenterOf(i)
        val st = if (InkShapes.isLine(shape)) InkShapes.build(shape, cx - w / 2f, cy, cx + w / 2f, cy, color, width)
        else InkShapes.build(shape, cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, color, width)
        pushUndo()
        sel = Sel(i, listOf(st), emptyList(), emptyList(), emptyList(), RectF(st.bounds())).also { it.changed = true }
        listener?.onSelectionChanged(true)
        changed()
    }

    /** Insert a picture at the visible centre; it floats selected so the user can move/resize it. */
    fun addImage(b: Bitmap, widthPt: Float = 0f) {
        commitSelection()
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val p = doc.pages[i]
        val maxW = if (widthPt > 0f) widthPt else if (doc.infinite) width / scale * 0.6f else p.w * 0.6f
        val w = min(maxW, b.width.toFloat())
        val h = w * b.height / b.width
        val (cx, cy) = visibleCenter(i)
        val x = if (doc.infinite) cx - w / 2 else (p.w - w) / 2
        val y = if (doc.infinite) cy - h / 2 else (cy - h / 2).coerceIn(10f, max(10f, p.h - h - 10f))
        val item = ImageItem(System.nanoTime(), x, y, w, h, ImageItem.encode(b)).also { it.bmp = b }
        // Floats as a selection until placed; the undo point is "before the image".
        pushUndo()
        sel = Sel(i, emptyList(), emptyList(), listOf(item), emptyList(), item.bounds()).also { it.changed = true }
        listener?.onSelectionChanged(true)
        changed()
    }

    private fun insertImageAt(page: Int, x: Float, y: Float, b: Bitmap) {
        commitSelection()
        val p = doc.pages[page]
        val maxW = if (doc.infinite) width / scale * 0.5f else p.w * 0.6f
        val w = min(maxW, b.width.toFloat()); val h = w * b.height / b.width
        var left = x - w / 2f; var top = y - h / 2f
        if (!doc.infinite) { left = left.coerceIn(0f, max(0f, p.w - w)); top = top.coerceIn(0f, max(0f, p.h - h)) }
        val item = ImageItem(System.nanoTime(), left, top, w, h, ImageItem.encode(b)).also { it.bmp = b }
        pushUndo()
        setPage(page, p.copy(images = p.images + item))
        changed()
    }

    private fun newLink(page: Int, cx: Float, cy: Float, target: String, label: String): LinkItem {
        val p = doc.pages[page]
        val w = InkRender.linkWidth(label)
        var x = cx - w / 2f; var y = cy - LinkItem.LINK_H / 2f
        if (!doc.infinite) { x = x.coerceIn(0f, max(0f, p.w - w)); y = y.coerceIn(0f, max(0f, p.h - LinkItem.LINK_H)) }
        return LinkItem(System.nanoTime(), x, y, label, target, w)
    }

    private fun addLinkAt(page: Int, x: Float, y: Float, target: String, label: String) {
        val item = newLink(page, x, y, target, label)
        pushUndo()
        val p = doc.pages[page]
        setPage(page, p.copy(links = p.links + item))
        changed()
    }

    /** Insert a link chip at the visible centre of the current page; it floats selected so it can be placed. */
    fun addLink(target: String, label: String) {
        commitSelection()
        val i = currentPage.coerceIn(0, doc.pages.size - 1)
        val (cx, cy) = visibleCenter(i)
        val item = newLink(i, cx, cy, target, label)
        pushUndo()
        sel = Sel(i, emptyList(), emptyList(), emptyList(), listOf(item), item.bounds()).also { it.changed = true }
        listener?.onSelectionChanged(true)
        changed()
    }

    /** Replace a link's target/label (keeps position; width follows the new label). */
    fun updateLink(page: Int, id: Long, target: String, label: String) {
        commitSelection()
        val p = doc.pages.getOrNull(page) ?: return
        val old = p.links.firstOrNull { it.id == id } ?: return
        val k = old.h / LinkItem.LINK_H
        pushUndo()
        setPage(page, p.copy(links = p.links.map { if (it.id == id) old.copy(target = target, label = label, w = InkRender.linkWidth(label) * k) else it }))
        changed()
    }

    fun deleteLink(page: Int, id: Long) {
        commitSelection()
        val p = doc.pages.getOrNull(page) ?: return
        if (p.links.none { it.id == id }) return
        pushUndo()
        setPage(page, p.copy(links = p.links.filterNot { it.id == id }))
        changed()
    }

    // ---- math helper ----

    /**
     * Places a math answer as typed text right after the "=" of [line] (one undo step). It is drawn faded with a ✓ / ×
     * chip until accepted; ignored, it simply stays. Skipped when the line's strokes are gone (erased / undone meanwhile).
     */
    fun insertMathAnswer(page: Int, line: MathAssist.Line, answer: String) {
        val p = doc.pages.getOrNull(page) ?: return
        val have = p.strokes.toHashSet()
        if (line.strokes.any { it !in have }) return
        // automatic answers only while nothing new was written after the "=" (the user may have answered it already)
        if (line.strokes.isNotEmpty() && p.strokes.lastOrNull { it.tool == Tool.PEN } !== line.strokes.last()) return
        if (sel != null || editItem != null) return
        val size = (line.charH * 0.85f).coerceIn(8f, 160f)
        val tf = InkRender.typeface("hand", false)
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf }
        val w = tp.measureText(answer) + size * 0.6f
        val gap = size * 0.35f
        val x = if (line.rightToLeft) line.eq.left - gap - w else line.eq.right + gap
        val y = line.eq.centerY() - size * 0.62f
        val item = TextItem(System.nanoTime(), x, y, w, answer, size, line.color or 0xFF000000.toInt(), "hand", false,
            if (line.rightToLeft) TextItem.ALIGN_END else TextItem.ALIGN_START)
        pushUndo()
        setPage(page, p.copy(texts = p.texts + item))
        mathPage = page; mathId = item.id
        changed()
    }

    /** Lasso "Solve": the answer goes right after the selection ("= 42" when the user did not write the "="). */
    fun insertSolveAnswer(answer: String, hadEquals: Boolean) {
        val s = sel ?: return
        val b = selectionBounds() ?: return
        val pens = s.strokes.filter { it.tool == Tool.PEN }
        val h = max(10f, min(b.height(), 120f))
        commitSelection()
        val color = pens.lastOrNull()?.color ?: penColor
        val text = if (hadEquals) answer else "= $answer"
        val line = MathAssist.Line(emptyList(), RectF(b.right - 1f, b.centerY() - 1f, b.right, b.centerY() + 1f), false, h, color)
        insertMathAnswer(s.page, line, text)
    }

    private fun mathItem(): TextItem? = doc.pages.getOrNull(mathPage)?.texts?.firstOrNull { it.id == mathId }

    private fun acceptMath() { mathPage = -1; mathId = 0L; chipOk.setEmpty(); chipNo.setEmpty(); invalidate() }

    private fun rejectMath() {
        val pg = mathPage; val id = mathId
        acceptMath()
        deleteText(pg, id)
    }

    private fun drawMathChip(c: Canvas) {
        if (mathPage < 0) { chipOk.setEmpty(); chipNo.setEmpty(); return }
        val t = mathItem()
        if (t == null || mathPage >= pageTops.size || (editItem?.id == t.id)) { mathPage = -1; chipOk.setEmpty(); chipNo.setEmpty(); return }
        val pl = toScreenX(pageLeft(mathPage)); val pt = toScreenY(pageTops[mathPage])
        val lh = InkRender.layout(t).height
        val r = 13 * density
        val cx = pl + (t.x + t.w) * scale + r + 6 * density
        val cy = pt + (t.y + lh / 2f) * scale
        chipOk.set(cx - r, cy - r, cx + r, cy + r)
        chipNo.set(cx + r + 6 * density, cy - r, cx + 3 * r + 6 * density, cy + r)
        chipPaint.color = 0xFF2F9E6E.toInt(); c.drawOval(chipOk, chipPaint)
        chipPaint.color = 0xFFE7E5E0.toInt(); c.drawOval(chipNo, chipPaint)
        chipStroke.strokeWidth = 2.2f * density
        chipStroke.color = Color.WHITE
        val k = r * 0.42f
        c.drawLine(cx - k, cy, cx - k * 0.2f, cy + k * 0.8f, chipStroke)
        c.drawLine(cx - k * 0.2f, cy + k * 0.8f, cx + k, cy - k * 0.7f, chipStroke)
        chipStroke.color = 0xFF5E6B78.toInt()
        val nx = chipNo.centerX()
        c.drawLine(nx - k * 0.75f, cy - k * 0.75f, nx + k * 0.75f, cy + k * 0.75f, chipStroke)
        c.drawLine(nx + k * 0.75f, cy - k * 0.75f, nx - k * 0.75f, cy + k * 0.75f, chipStroke)
    }

    // ---- lasso extras: tidy, flashcard image ----

    /** Tidies the selected handwriting (lines straightened, spacing evened, jitter smoothed). Undo restores it. */
    fun tidySelection(): Boolean {
        val s = sel ?: return false
        val m = mappedSelection(s)
        if (m.strokes.none { it.tool == Tool.PEN }) return false
        val tidied = Tidy.tidy(m.strokes)
        val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        tidied.forEach { box.union(it.bounds()) }
        m.texts.forEach { InkRender.layout(it); box.union(it.bounds()) }
        m.images.forEach { box.union(it.bounds()) }
        m.links.forEach { box.union(it.bounds()) }
        m.stickers.forEach { box.union(it.bounds()) }
        sel = Sel(s.page, tidied, m.texts, m.images, m.links, box, m.stickers).also { it.changed = true }
        invalidate()
        return true
    }

    /** Typed text inside the selection (top to bottom). */
    fun selectedTexts(): List<String> = sel?.texts?.sortedBy { it.y }?.map { it.text }?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * The selection rendered on the paper colour (tapes hidden), cropped to its bounds + a small margin, at [scale] ×
     * points, at most [maxPx] on the long side. Null when nothing is selected.
     */
    fun selectionBitmap(scale: Float = 2f, maxPx: Int = 1600): Bitmap? {
        val s = sel ?: return null
        val m = mappedSelection(s)
        val b = selectionBounds() ?: return null
        b.inset(-8f, -8f)
        if (b.width() <= 0f || b.height() <= 0f) return null
        val k = min(scale, maxPx / max(b.width(), b.height()))
        val w = (b.width() * k).toInt().coerceIn(1, maxPx); val h = (b.height() * k).toInt().coerceIn(1, maxPx)
        val bmp = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        val c = Canvas(bmp)
        val paper = if (source != null) Color.WHITE else doc.paperColor or 0xFF000000.toInt()
        c.drawColor(paper)
        c.scale(k, k); c.translate(-b.left, -b.top)
        InkRender.drawPageContent(c, InkPage(strokes = m.strokes, texts = m.texts, images = m.images, links = m.links, stickers = m.stickers))
        return bmp
    }

    // ---- tapes ----

    fun hasTapes(): Boolean = doc.pages.any { p -> p.strokes.any { it.isTape } } || sel?.strokes?.any { it.isTape } == true

    /** Reveal or hide every tape (transient — not saved, not undoable). */
    fun setAllTapes(revealed: Boolean) {
        doc.pages.forEach { p -> p.strokes.forEach { if (it.isTape) it.revealed = revealed } }
        sel?.strokes?.forEach { if (it.isTape) it.revealed = revealed }
        invalidate()
    }

    // ---- pages ----

    fun addPage(after: Int, paper: String) {
        if (doc.infinite) return
        finishEditing()
        pushUndo()
        val ref = doc.pages.getOrNull(after) ?: InkPage()
        doc.pages = doc.pages.toMutableList().also { it.add(after + 1, InkPage(ref.w, ref.h, paper)) }
        relayout(); changed()
        goToPage(after + 1)
    }

    fun deletePage(i: Int) {
        if (doc.pages.size <= 1 || doc.infinite) return
        finishEditing()
        pushUndo()
        doc.pages = doc.pages.toMutableList().also { it.removeAt(i) }
        relayout(); clamp(); updateCurrentPage(); changed()
        listener?.onPageChanged(currentPage, doc.pages.size)
    }

    /**
     * Page manager (pages-agent): replaces the whole page list as one undo step — reorder, delete, duplicate, rotate,
     * templates, insert, move out. Relayouts, then scrolls to page [goTo] (≥ 0) and saves through the listener.
     */
    fun applyPages(newPages: List<InkPage>, goTo: Int = -1) {
        if (newPages.isEmpty() || (doc.infinite && newPages.size != 1)) return
        finishEditing()
        pushUndo()
        doc.pages = newPages.toList()
        relayout(); clamp(); updateCurrentPage(); changed()
        listener?.onPageChanged(currentPage, doc.pages.size)
        if (goTo in doc.pages.indices) goToPage(goTo)
    }

    fun setPaper(paper: String, all: Boolean) {
        pushUndo()
        doc.pages = doc.pages.mapIndexed { i, p -> if (all || i == currentPage) p.copy(paper = paper) else p }
        changed()
    }

    fun clearPage(i: Int) {
        finishEditing()
        if (doc.pages[i].isEmpty()) return
        pushUndo()
        setPage(i, doc.pages[i].copy(strokes = emptyList(), texts = emptyList(), images = emptyList(), links = emptyList(), stickers = emptyList()))
        changed()
    }

    fun pageStrokes(i: Int): List<Stroke> = doc.pages.getOrNull(i)?.strokes?.filter { it.tool == Tool.PEN } ?: emptyList()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacks(settle)
        main.removeCallbacks(zoomSettle)
        main.removeCallbacks(longPress)
        runCatching { context.applicationContext.unregisterComponentCallbacks(memCallbacks) }
    }

    companion object {
        /** A finger touch this soon after the pen hovered / wrote is the writing hand resting on the screen. */
        private const val PALM_MS = 450L
        const val FILE_PREFIX = "daftar:file:"
        /** Whiteboard growth step: a multiple of every paper period (lined 26, grid/dots 18) so the pattern never jumps. */
        private const val GROW_UNIT = 234f
        private const val BOARD_W = 2340f
        private const val BOARD_H = 1638f
        private const val MAX_BOARD = 400_000f
        private const val LZ_MAX = 256
        private const val H_MOVE = 1
        private const val H_TL = 2
        private const val H_TR = 3
        private const val H_BL = 4
        private const val H_BR = 5
        private const val H_L = 6
        private const val H_R = 7
        private const val LASER_LIFE = 650L
        /** Distance (dp) of the selection's rotation handle above its frame. */
        private const val ROT_HANDLE = 30f
        private const val ZOOM_SETTLE = 140L
        private const val LASER_COLOR = 0xFFFF3B30.toInt()
    }
}
