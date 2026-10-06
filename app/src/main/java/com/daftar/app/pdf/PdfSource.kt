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

    /** Drawn after the page and the search marks (the editor's night mode inverts it with the page, so overlays follow the paper). */
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

