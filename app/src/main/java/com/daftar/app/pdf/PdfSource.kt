package com.daftar.app.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.daftar.app.ink.PageSource
import java.io.File
import java.io.IOException

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
        }
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
