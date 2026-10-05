package com.daftar.app.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.daftar.app.ink.PageSource
import java.io.File
import java.io.IOException

/** Extra drawing on top of a rendered page, in displayed page points (e.g. translation boxes). Called on the render thread. */
fun interface PageOverlay {
    fun draw(page: Int, canvas: Canvas)
}

/** Search highlights drawn into rendered pages: every match per page, plus the active match drawn stronger. */
class SearchMarks(val byPage: Map<Int, List<RectF>>, val activePage: Int, val active: List<RectF>)

/**
 * PDF pages for the ink editor, backed by the platform [PdfRenderer].
 * Sizes are in points as displayed (PdfRenderer already applies the page /Rotate), which is
 * exactly the coordinate space the ink layer uses.
 *
 * The constructor throws [IOException] for corrupt files and [SecurityException] for
 * password-protected ones — callers show an error state.
 */
class PdfSource(val file: File) : PageSource {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer
    private val sizes: List<Pair<Float, Float>>
    private val lock = Any()
    @Volatile private var closed = false

    /** Transient search highlights (displayed page points). Set from the UI, read by the render thread. */
    @Volatile var marks: SearchMarks? = null
    private val markPaint = Paint().apply { color = 0xFFFFE27A.toInt(); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }
    private val activePaint = Paint().apply { color = 0xFFFFA94D.toInt(); xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) }

    /** Night paper: rendered pages are post-processed with [NightFilter] (hue-preserving inversion onto dark paper). */
    @Volatile var night: Boolean = false

    /** Drawn after the page and the search marks (before the night filter, so overlays follow the paper colour). */
    @Volatile var overlay: PageOverlay? = null

    init {
        try {
            renderer = PdfRenderer(pfd)
            val n = renderer.pageCount
            if (n <= 0) throw IOException("PDF has no pages")
            sizes = List(n) { i ->
                renderer.openPage(i).use { p -> p.width.toFloat().coerceAtLeast(1f) to p.height.toFloat().coerceAtLeast(1f) }
            }
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }

    override val pageCount: Int get() = sizes.size

    override fun pageSize(i: Int): Pair<Float, Float> = sizes[i.coerceIn(0, sizes.lastIndex)]

    override fun render(i: Int, dest: Bitmap, m: Matrix) {
        if (i !in sizes.indices) return
        synchronized(lock) {
            if (closed) return
            renderer.openPage(i).use { page ->
                page.render(dest, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            drawMarks(i, dest, m)
            overlay?.let { o ->
                runCatching {
                    val c = Canvas(dest)
                    c.concat(m)
                    o.draw(i, c)
                }
            }
            if (night) NightFilter.apply(dest)
        }
    }

    /** Highlighter look: multiply blend keeps the text dark and tints only the paper. */
    private fun drawMarks(i: Int, dest: Bitmap, m: Matrix) {
        val mk = marks ?: return
        val rs = mk.byPage[i]
        if (rs.isNullOrEmpty() && mk.activePage != i) return
        val c = Canvas(dest)
        c.concat(m)
        rs?.forEach { c.drawRect(it, markPaint) }
        if (mk.activePage == i) mk.active.forEach { c.drawRect(it, activePaint) }
    }

    /** Renders page [i] scaled to fit [maxW]×[maxH] pixels (white background). Null if closed. */
    fun renderFit(i: Int, maxW: Int, maxH: Int = Int.MAX_VALUE): Bitmap? {
        val (w, h) = pageSize(i)
        val s = minOf(maxW / w, maxH / h)
        val bw = (w * s).toInt().coerceAtLeast(1)
        val bh = (h * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val m = Matrix().apply { setScale(bw / w, bh / h) }
        synchronized(lock) {
            if (closed) { bmp.recycle(); return null }
            renderer.openPage(i).use { it.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
        return bmp
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

/**
 * Night paper as a post-process on a rendered bitmap: lightness is inverted with hue and chroma kept
 * (c' = c + 255 − max − min per pixel, so red stays red and blue stays blue), then compressed onto a dark paper
 * range so white paper becomes #1A1B1E and black text a soft #E6E6E6.
 */
object NightFilter {
    private val lutR = IntArray(256) { 0x1A + it * (0xE6 - 0x1A) / 255 }
    private val lutG = IntArray(256) { 0x1B + it * (0xE6 - 0x1B) / 255 }
    private val lutB = IntArray(256) { 0x1E + it * (0xE8 - 0x1E) / 255 }

    fun apply(bmp: Bitmap) {
        if (bmp.isRecycled || !bmp.isMutable) return
        val w = bmp.width; val h = bmp.height
        val rows = (65536 / w.coerceAtLeast(1)).coerceIn(1, h.coerceAtLeast(1))
        val buf = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(buf, 0, w, 0, y, w, n)
            for (k in 0 until w * n) buf[k] = map(buf[k])
            bmp.setPixels(buf, 0, w, 0, y, w, n)
            y += n
        }
    }

    fun map(p: Int): Int {
        val a = p ushr 24
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val mx = maxOf(r, maxOf(g, b)); val mn = minOf(r, minOf(g, b))
        val d = 255 - mx - mn
        return (a shl 24) or (lutR[r + d] shl 16) or (lutG[g + d] shl 8) or lutB[b + d]
    }
}
