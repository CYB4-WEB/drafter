package com.daftar.app.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LongSparseArray
import java.util.IdentityHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Growable int list without boxing. */
internal class IntList(cap: Int = 64) {
    var a = IntArray(cap); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun clear() { size = 0 }
    operator fun get(i: Int) = a[i]
    /** Sorts and removes duplicates (drawing order = original stroke order). */
    fun sortUnique() {
        if (size < 2) return
        java.util.Arrays.sort(a, 0, size)
        var w = 1
        for (i in 1 until size) if (a[i] != a[w - 1]) a[w++] = a[i]
        size = w
    }
}

/**
 * Spatial index of one immutable stroke list: uniform grid buckets ([CELL] page points) of stroke indices, so a tile or a
 * repaint of a small area only looks at the strokes that can touch it. Tapes are listed apart (always drawn on top).
 * Built on the main thread (it also computes every stroke's bounds), read-only afterwards (safe for the tile thread).
 */
internal class StrokeGrid(val list: List<Stroke>) {
    private val cells = HashMap<Long, IntList>()
    private val wide = IntList(8)          // strokes covering too many cells (long ruler lines, big shapes)
    val tapes: IntArray
    var nonTape = 0; private set

    init {
        val tp = IntList(4)
        for (i in list.indices) {
            val s = list[i]
            if (s.isTape) { tp.add(i); continue }
            nonTape++
            val b = s.bounds()
            val x0 = floor(b.left / CELL).toInt(); val x1 = floor(b.right / CELL).toInt()
            val y0 = floor(b.top / CELL).toInt(); val y1 = floor(b.bottom / CELL).toInt()
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 48) { wide.add(i); continue }
            for (y in y0..y1) for (x in x0..x1) cells.getOrPut(key(x, y)) { IntList(8) }.add(i)
        }
        tapes = tp.a.copyOf(tp.size)
    }

    /** Indices of non-tape strokes whose bounds intersect the rectangle (page coords), sorted, unique. */
    fun query(l: Float, t: Float, r: Float, b: Float, out: IntList) {
        out.clear()
        val x0 = floor(l / CELL).toInt(); val x1 = floor(r / CELL).toInt()
        val y0 = floor(t / CELL).toInt(); val y1 = floor(b / CELL).toInt()
        val span = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        if (span > cells.size) {
            for ((k, v) in cells) {
                val cx = (k shr 32).toInt(); val cy = k.toInt()
                if (cx < x0 || cx > x1 || cy < y0 || cy > y1) continue
                for (j in 0 until v.size) out.add(v[j])
            }
        } else {
            for (y in y0..y1) for (x in x0..x1) cells[key(x, y)]?.let { v -> for (j in 0 until v.size) out.add(v[j]) }
        }
        for (j in 0 until wide.size) out.add(wide[j])
        out.sortUnique()
        // exact bounds test (buckets are coarse)
        var w = 0
        for (j in 0 until out.size) {
            val bb = list[out[j]].bounds()
            if (bb.left <= r && bb.right >= l && bb.top <= b && bb.bottom >= t) out.a[w++] = out[j]
        }
        out.size = w
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    companion object { const val CELL = 256f }
}

/**
 * Bitmap tiles of the committed ink of every page, for the **visible region only** (plus a prefetch ring when memory
 * allows), at the settled zoom. Tiles are [TILE] px squares on a grid in *content space* (page coordinates minus the
 * whiteboard growth shift), so a whiteboard growing left/top keeps every tile. Rendering happens on one background thread
 * from immutable stroke lists, visible tiles first; the UI thread only blits bitmaps.
 *
 * When a page's stroke list changes, tiles are reconciled once (not per frame): strokes appended → tiles they touch keep
 * their bitmap and draw the new strokes as clipped vectors until folded in (after the pen rests); strokes removed / moved
 * (erase, undo, lasso) → only tiles intersecting the changed bounds are re-rendered, and they are drawn as clipped vectors
 * meanwhile. Missing tiles use the previous zoom level (scaled) or clipped vectors under a per-frame point budget.
 * Memory: ≤ min(heap / 8, 96 MB) of tiles (LRU, current frame protected), a small bitmap pool, all freed by [clear].
 */
internal class InkTiles(private val pages: () -> List<InkPage>, private val onReady: () -> Unit) {
    private class Tile(val page: Int, val tx: Int, val ty: Int) {
        var bmp: Bitmap? = null
        /** Rendered and nothing to draw: no bitmap kept (sparse whiteboards cost no memory). */
        var empty = false
        val ready get() = (bmp != null || empty) && !dirty
        var list: List<Stroke>? = null
        var count = 0
        var shX = 0f; var shY = 0f            // page shift of [list]
        var dirty = false
        var seq = 0
        var used = 0L
        var rendering: List<Stroke>? = null   // list of the render in flight
    }

    private class Level(val scale: Float) {
        val unit = TILE / scale
        val tiles = LongSparseArray<Tile>()
        val seen = HashMap<Int, List<Stroke>>()
    }

    private class Req(val level: Level, val page: Int, val tx: Int, val ty: Int, val prio: Float)

    private class Relation(val prefix: Boolean, val rects: ArrayList<RectF>) {
        fun hits(l: Float, t: Float, r: Float, b: Float): Boolean {
            for (k in rects.indices) { val q = rects[k]; if (q.left <= r && q.right >= l && q.top <= b && q.bottom >= t) return true }
            return false
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var exec: ExecutorService? = null
    private var cur: Level? = null
    private var prev: Level? = null
    private var frame = 0L
    private var gen = 0
    private var inFlight = 0
    private var paused = false
    private val queue = ArrayList<Req>()
    private val wanted = ArrayList<Req>()
    private val pool = ArrayDeque<Bitmap>()
    private var tileCount = 0
    private val budgetTiles = (min(Runtime.getRuntime().maxMemory() / 8, 96L * 1024 * 1024) / TILE_BYTES).toInt().coerceAtLeast(8)
    private val grids = HashMap<Int, StrokeGrid>()
    private val lastChange = HashMap<Int, Long>()
    private val idx = IntList(256)
    private val blit = Paint()
    private val scaled = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rTmp = RectF()
    private var budgetPts = 0
    private var foldPosted = false
    private val fold = Runnable { foldPosted = false; onReady() }

    /** The page's stroke index (cached per stroke list). */
    fun grid(page: Int, st: List<Stroke>): StrokeGrid {
        grids[page]?.let { if (it.list === st) return it }
        return StrokeGrid(st).also { grids[page] = it }
    }

    fun beginFrame() { frame++; wanted.clear(); budgetPts = VECTOR_BUDGET }

    /**
     * Draws the non-tape strokes of page [i] (canvas in page coordinates, scaled by [scale], page origin at screen
     * ([ox], [oy])). [vis] = visible part of the page (page coords). [settled] = the zoom is not changing right now.
     * [penDown] = a stroke is being drawn (new strokes are folded in only when the pen rests).
     */
    fun draw(c: Canvas, i: Int, page: InkPage, vis: RectF, scale: Float, ox: Float, oy: Float, settled: Boolean, penDown: Boolean) {
        val st = page.strokes
        val g = grid(i, st)
        if (g.nonTape == 0) return
        if (settled && cur?.scale != scale) switchLevel(scale)
        val shX = page.shiftX; val shY = page.shiftY
        cur?.let { reconcile(it, i, st, shX, shY) }
        prev?.let { reconcile(it, i, st, shX, shY) }
        val lvl = cur
        if (lvl == null || lvl.scale != scale) {
            // zoom in progress: whatever level we have, scaled; the rest as vectors
            val any = lvl ?: prev
            if (any == null) { vectors(c, g, vis.left, vis.top, vis.right, vis.bottom); return }
            drawScaledLevel(c, any, i, st, g, vis, shX, shY)
            return
        }
        val u = lvl.unit
        val cl = vis.left - shX; val ct = vis.top - shY; val cr = vis.right - shX; val cb = vis.bottom - shY
        val tx0 = floor(cl / u).toInt(); val tx1 = floor((cr - 1e-3f) / u).toInt()
        val ty0 = floor(ct / u).toInt(); val ty1 = floor((cb - 1e-3f) / u).toInt()
        val visibleTiles = (tx1 - tx0 + 1).toLong() * (ty1 - ty0 + 1)
        if (visibleTiles > budgetTiles - 2 || visibleTiles > 400) {
            // the visible area alone does not fit the budget (tiny heap / giant window): culled vectors
            vectors(c, g, vis.left, vis.top, vis.right, vis.bottom, budget = false); return
        }
        val now = SystemClock.uptimeMillis()
        val foldDue = !penDown && now - (lastChange[i] ?: 0L) >= FOLD_DELAY
        val baseX = Math.round(ox + shX * scale); val baseY = Math.round(oy + shY * scale)
        val cx = (tx0 + tx1) / 2f; val cy = (ty0 + ty1) / 2f
        c.save()
        c.scale(1f / scale, 1f / scale)
        var needTail = false
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val t = lvl.tiles[key(i, tx, ty)]
            if (t != null && t.ready) {
                t.bmp?.let { b -> c.drawBitmap(b, baseX + tx * TILE - ox, baseY + ty * TILE - oy, blit) }
                t.used = frame
                if (t.count < st.size) { needTail = true; if (foldDue) want(lvl, i, tx, ty, 2f + dist(tx, ty, cx, cy)) }
            } else {
                want(lvl, i, tx, ty, dist(tx, ty, cx, cy))
            }
        }
        c.restore()
        // second pass in page coordinates: tails of up-to-date-but-older tiles, and fallbacks for missing ones
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val t = lvl.tiles[key(i, tx, ty)]
            val l = shX + tx * u; val tp = shY + ty * u; val r = l + u; val bt = tp + u
            if (t != null && t.ready) {
                if (needTail && t.count < st.size) tail(c, st, t.count, l, tp, r, bt)
                continue
            }
            val p = prev
            if (p != null && drawFromLevel(c, p, i, st, l, tp, r, bt, shX, shY)) continue
            vectors(c, g, max(l, vis.left), max(tp, vis.top), min(r, vis.right), min(bt, vis.bottom), clip = true)
        }
        if (needTail && !foldDue && !penDown) scheduleFold(i, now)
        // prefetch ring (one tile around the view) while memory allows
        if (visibleTiles * 2 + 16 < budgetTiles) {
            for (ty in ty0 - 1..ty1 + 1) for (tx in tx0 - 1..tx1 + 1) {
                if (ty in ty0..ty1 && tx in tx0..tx1) continue
                val t = lvl.tiles[key(i, tx, ty)]
                if (t != null && t.ready && (t.count == st.size || !foldDue)) { t.used = frame; continue }
                if (t == null && !inPage(page, tx, ty, u)) continue
                want(lvl, i, tx, ty, 100f + dist(tx, ty, cx, cy))
            }
        }
    }

    private fun inPage(page: InkPage, tx: Int, ty: Int, u: Float): Boolean {
        val l = page.shiftX + tx * u; val t = page.shiftY + ty * u
        return l < page.w && t < page.h && l + u > 0f && t + u > 0f
    }

    private fun dist(tx: Int, ty: Int, cx: Float, cy: Float) = abs(tx - cx) + abs(ty - cy)

    private fun want(level: Level, page: Int, tx: Int, ty: Int, prio: Float) { wanted.add(Req(level, page, tx, ty, prio)) }

    private fun scheduleFold(page: Int, now: Long) {
        if (foldPosted) return
        foldPosted = true
        val at = (lastChange[page] ?: now) + FOLD_DELAY - now
        main.postDelayed(fold, max(16L, at))
    }

    /** Strokes appended after a tile was rendered, clipped to the tile. */
    private fun tail(c: Canvas, st: List<Stroke>, from: Int, l: Float, t: Float, r: Float, b: Float) {
        var clipped = false
        for (k in from until st.size) {
            val s = st[k]
            if (s.isTape) continue
            val bb = s.bounds()
            if (bb.left > r || bb.right < l || bb.top > b || bb.bottom < t) continue
            if (!clipped) { c.save(); c.clipRect(l, t, r, b); clipped = true }
            InkRender.drawStroke(c, s)
        }
        if (clipped) c.restore()
    }

    /** Culled vectors for an area (page coords), within the per-frame point budget unless [budget] is false. */
    private fun vectors(c: Canvas, g: StrokeGrid, l: Float, t: Float, r: Float, b: Float, clip: Boolean = false, budget: Boolean = true) {
        if (l >= r || t >= b) return
        g.query(l, t, r, b, idx)
        if (idx.size == 0) return
        if (clip) { c.save(); c.clipRect(l, t, r, b) }
        val st = g.list
        for (j in 0 until idx.size) {
            val s = st[idx[j]]
            if (budget) { if (budgetPts <= 0) break; budgetPts -= s.pts.size / 3 + 4 }
            InkRender.drawStroke(c, s)
        }
        if (clip) c.restore()
    }

    /** Fills the area from up-to-date tiles of another zoom level (scaled). False when they do not cover it. */
    private fun drawFromLevel(c: Canvas, lv: Level, i: Int, st: List<Stroke>, l: Float, t: Float, r: Float, b: Float, shX: Float, shY: Float): Boolean {
        val u = lv.unit
        val x0 = floor((l - shX) / u).toInt(); val x1 = floor((r - shX - 1e-3f) / u).toInt()
        val y0 = floor((t - shY) / u).toInt(); val y1 = floor((b - shY - 1e-3f) / u).toInt()
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > 16) return false
        for (y in y0..y1) for (x in x0..x1) {
            val tl = lv.tiles[key(i, x, y)] ?: return false
            if (!tl.ready || tl.count != st.size) return false
        }
        c.save(); c.clipRect(l, t, r, b)
        for (y in y0..y1) for (x in x0..x1) {
            val tl = lv.tiles[key(i, x, y)]!!
            tl.used = frame
            val bm = tl.bmp ?: continue
            rTmp.set(shX + x * u, shY + y * u, shX + (x + 1) * u, shY + (y + 1) * u)
            c.drawBitmap(bm, null, rTmp, scaled)
        }
        c.restore()
        return true
    }

    /** During a pinch: tiles of [lv] scaled, holes as vectors. */
    private fun drawScaledLevel(c: Canvas, lv: Level, i: Int, st: List<Stroke>, g: StrokeGrid, vis: RectF, shX: Float, shY: Float) {
        val u = lv.unit
        val x0 = floor((vis.left - shX) / u).toInt(); val x1 = floor((vis.right - shX - 1e-3f) / u).toInt()
        val y0 = floor((vis.top - shY) / u).toInt(); val y1 = floor((vis.bottom - shY - 1e-3f) / u).toInt()
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 600) { vectors(c, g, vis.left, vis.top, vis.right, vis.bottom); return }
        for (y in y0..y1) for (x in x0..x1) {
            val tl = lv.tiles[key(i, x, y)]
            val l = shX + x * u; val t = shY + y * u
            if (tl != null && tl.ready) {
                tl.used = frame
                rTmp.set(l, t, l + u, t + u)
                tl.bmp?.let { c.drawBitmap(it, null, rTmp, scaled) }
                if (tl.count < st.size) tail(c, st, tl.count, l, t, l + u, t + u)
            } else vectors(c, g, max(l, vis.left), max(t, vis.top), min(l + u, vis.right), min(t + u, vis.bottom), clip = true)
        }
    }

    private fun switchLevel(scale: Float) {
        val p = prev
        if (p != null && p.scale == scale) { prev = cur; cur = p; return }
        p?.let { dropLevel(it) }
        prev = cur
        cur = Level(scale)
    }

    private fun dropLevel(l: Level) {
        for (k in 0 until l.tiles.size()) l.tiles.valueAt(k).bmp?.let { give(it) }
        tileCount -= l.tiles.size()
        l.tiles.clear()
    }

    /** One pass per stroke-list change: rebase tiles the change does not touch, mark the touched ones. */
    private fun reconcile(lv: Level, i: Int, st: List<Stroke>, shX: Float, shY: Float) {
        if (lv.seen[i] === st) return
        val known = lv.seen.containsKey(i)
        lv.seen[i] = st
        if (known && lv === cur) lastChange[i] = SystemClock.uptimeMillis()
        var rel: IdentityHashMap<List<Stroke>, Relation>? = null
        val u = lv.unit
        for (k in 0 until lv.tiles.size()) {
            val t = lv.tiles.valueAt(k)
            if (t.page != i) continue
            val old = t.list ?: continue
            if (old === st) continue
            val m = rel ?: IdentityHashMap<List<Stroke>, Relation>().also { rel = it }
            val r = m.getOrPut(old) { relate(old, t.shX, t.shY, st, shX, shY) }
            val l = t.tx * u; val tp = t.ty * u
            if (r.hits(l, tp, l + u, tp + u)) { if (!r.prefix) t.dirty = true }
            else if (!t.dirty) { t.list = st; t.count = st.size; t.shX = shX; t.shY = shY }
        }
    }

    /** How [now] differs from [old] (by stroke identity), as content-space rectangles. */
    private fun relate(old: List<Stroke>, oX: Float, oY: Float, now: List<Stroke>, nX: Float, nY: Float): Relation {
        val rects = ArrayList<RectF>()
        fun add(s: Stroke, dx: Float, dy: Float) {
            if (s.isTape) return
            val b = s.bounds()
            if (rects.size >= 24) { rects[0].union(b.left - dx, b.top - dy, b.right - dx, b.bottom - dy); return }
            rects.add(RectF(b.left - dx, b.top - dy, b.right - dx, b.bottom - dy))
        }
        var prefix = now.size >= old.size
        if (prefix) for (k in old.size - 1 downTo 0) if (now[k].identity() !== old[k].identity()) { prefix = false; break }
        if (prefix) {
            for (k in old.size until now.size) add(now[k], nX, nY)
            if (rects.size >= 24) mergeAll(rects)
            return Relation(true, rects)
        }
        val m = IdentityHashMap<Any, Stroke>(old.size * 2)
        for (s in old) m[s.identity()] = s
        for (s in now) if (m.remove(s.identity()) == null) add(s, nX, nY)
        for (s in m.values) add(s, oX, oY)
        if (rects.size >= 24) mergeAll(rects)
        return Relation(false, rects)
    }

    private fun mergeAll(rects: ArrayList<RectF>) {
        val u = RectF(rects[0]); for (r in rects) u.union(r); rects.clear(); rects.add(u)
    }

    /** Starts background renders for the most wanted tiles (call at the end of a frame). */
    fun endFrame() {
        queue.clear()
        if (wanted.isNotEmpty()) { wanted.sortBy { it.prio }; queue.addAll(wanted) }
        dispatch()
        evict()
    }

    private fun dispatch() {
        if (paused) return
        val pages = pages()
        while (inFlight < 2 && queue.isNotEmpty()) {
            val r = queue.removeAt(0)
            if (r.level !== cur && r.level !== prev) continue
            val page = pages.getOrNull(r.page) ?: continue
            val st = page.strokes
            val k = key(r.page, r.tx, r.ty)
            val t = r.level.tiles[k] ?: Tile(r.page, r.tx, r.ty).also { r.level.tiles.put(k, it); tileCount++ }
            if (t.ready && t.list === st) continue
            if (t.rendering === st) continue
            t.rendering = st
            t.used = frame
            val seq = ++t.seq
            val g = grid(r.page, st)
            val u = r.level.unit
            val shX = page.shiftX; val shY = page.shiftY
            val sc = r.level.scale
            val l = shX + r.tx * u; val tp = shY + r.ty * u
            // which strokes touch this tile (main thread: the grid is only read here)
            idx.clear(); g.query(l, tp, l + u, tp + u, idx)
            if (idx.size == 0) {
                t.bmp?.let { give(it) }
                t.bmp = null; t.empty = true; t.dirty = false; t.list = st; t.count = st.size; t.shX = shX; t.shY = shY
                t.rendering = null; t.seq++
                continue
            }
            val todo = idx.a.copyOf(idx.size)
            val reuse = pool.removeLastOrNull()
            val myGen = gen
            val level = r.level
            val ex = exec ?: Executors.newSingleThreadExecutor { run -> Thread(run, "ink-tiles").apply { priority = Thread.MIN_PRIORITY + 1 } }.also { exec = it }
            inFlight++
            val ok = runCatching {
                ex.execute {
                    val bmp = runCatching {
                        val b = reuse ?: Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
                        b.eraseColor(0)
                        val cv = Canvas(b)
                        cv.scale(sc, sc); cv.translate(-l, -tp)
                        for (j in todo) InkRender.drawStroke(cv, st[j])
                        b
                    }.getOrNull()
                    main.post {
                        inFlight--
                        if (t.seq == seq) t.rendering = null
                        if (bmp == null) { dispatch(); return@post }
                        if (myGen != gen || (level !== cur && level !== prev) || t.seq != seq || level.tiles[k] !== t) { give(bmp); dispatch(); return@post }
                        t.bmp?.let { give(it) }
                        t.bmp = bmp; t.empty = false; t.list = st; t.count = st.size; t.shX = shX; t.shY = shY; t.dirty = false
                        // the page changed while this rendered: the next frame reconciles this tile against it
                        if (pages().getOrNull(r.page)?.strokes !== st) level.seen.remove(r.page)
                        onReady()
                        dispatch()
                    }
                }
            }.isSuccess
            if (!ok) { inFlight--; t.rendering = null; reuse?.let { give(it) } }
        }
    }

    private fun give(b: Bitmap) {
        if (b.isRecycled) return
        if (pool.size < POOL_MAX && b.width == TILE && b.height == TILE) pool.addLast(b) else b.recycle()
    }

    /** LRU eviction above the budget; tiles drawn this frame stay. The previous zoom level goes first. */
    private fun evict() {
        if (tileCount <= budgetTiles) return
        for (lv in listOfNotNull(prev, cur)) {
            while (tileCount > budgetTiles) {
                var best = -1; var bestUsed = Long.MAX_VALUE
                for (k in 0 until lv.tiles.size()) {
                    val t = lv.tiles.valueAt(k)
                    if (t.used < bestUsed && t.used != frame) { bestUsed = t.used; best = k }
                }
                if (best < 0) break
                lv.tiles.valueAt(best).bmp?.let { give(it) }
                lv.tiles.removeAt(best); tileCount--
            }
        }
    }

    /** Stops background rendering (editor paused); tiles stay. */
    fun pause() { paused = true; queue.clear(); main.removeCallbacks(fold); foldPosted = false }
    fun resume() { paused = false }

    /** Frees every bitmap (trim memory, document change, release). In-flight renders are discarded. */
    fun clear() {
        gen++
        queue.clear(); wanted.clear()
        cur?.let { dropLevel(it) }; prev?.let { dropLevel(it) }
        cur = null; prev = null
        while (pool.isNotEmpty()) pool.removeLast().recycle()
        tileCount = 0
        grids.clear(); lastChange.clear()
        main.removeCallbacks(fold); foldPosted = false
    }

    /** Lighter trim: drops the previous zoom level and the pool. */
    fun trim() {
        prev?.let { dropLevel(it) }; prev = null
        while (pool.isNotEmpty()) pool.removeLast().recycle()
    }

    fun release() {
        clear()
        exec?.shutdownNow(); exec = null
    }

    private fun key(page: Int, tx: Int, ty: Int): Long =
        (page.toLong() shl 44) or (((tx + OFF).toLong() and 0x3FFFFF) shl 22) or ((ty + OFF).toLong() and 0x3FFFFF)

    companion object {
        const val TILE = 512
        private const val TILE_BYTES = TILE * TILE * 4L
        private const val OFF = 1 shl 21
        private const val POOL_MAX = 6
        /** Max stroke points drawn as fallback vectors per frame (the rest waits for its tile). */
        private const val VECTOR_BUDGET = 60_000
        private const val FOLD_DELAY = 450L
    }
}
