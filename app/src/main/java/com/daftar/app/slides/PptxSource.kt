package com.daftar.app.slides

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.daftar.app.ink.PageSource
import java.io.File

/**
 * Slides of a .pptx as fixed background pages for the ink editor. Page space = slide points.
 * All rendering goes through one lock: the editor's page renderer, the thumbnail loader, present mode and export
 * share the parsed model and caches, and the zip is closed under the same lock.
 */
class PptxSource(val deck: Pptx, labels: Labels) : PageSource {
    private val renderer = PptxRenderer(deck, labels)
    private val lock = Any()
    @Volatile private var closed = false

    val isClosed: Boolean get() = closed

    override val pageCount: Int get() = deck.slides.size

    override fun pageSize(i: Int): Pair<Float, Float> = deck.widthPt to deck.heightPt

    override fun render(i: Int, dest: Bitmap, m: Matrix) {
        synchronized(lock) {
            if (closed || dest.isRecycled || i !in deck.slides.indices) return
            val c = Canvas(dest)
            c.concat(m)
            draw(i, c, PptxRenderer.scaleOf(m))
        }
    }

    /**
     * Draw slide [i] on a canvas already in slide points (e.g. a PdfDocument page, so text and shapes stay vector).
     * [detailPxPerPt] chooses the resolution pictures are decoded at.
     */
    fun drawVector(i: Int, c: Canvas, detailPxPerPt: Float) {
        synchronized(lock) {
            if (closed || i !in deck.slides.indices) return
            draw(i, c, detailPxPerPt)
        }
    }

    private fun draw(i: Int, c: Canvas, pxPerPt: Float) {
        val save = c.save()
        try {
            renderer.render(i, c, pxPerPt)
        } catch (_: OutOfMemoryError) {
            renderer.clearCaches()
        } catch (_: Exception) {
            // Leave whatever was drawn; a broken slide must not take the editor down.
        } finally {
            c.restoreToCount(save)
        }
    }

    /** Start counting the media drawn from now on (see [imageBytesSinceMark]). */
    fun markImages() = synchronized(lock) { renderer.markImages() }

    /** Bytes of distinct pictures drawn since [markImages]. */
    fun imageBytesSinceMark(): Long = synchronized(lock) { renderer.imageBytesSinceMark() }

    /** Small thumbnail of slide [i], [widthPx] wide. Safe to call from any background thread. */
    fun thumbnail(i: Int, widthPx: Int): Bitmap? {
        val h = (widthPx * deck.heightPt / deck.widthPt).toInt().coerceAtLeast(1)
        val bmp = try { Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
        bmp.eraseColor(0xFFFFFFFF.toInt())
        val s = widthPx / deck.widthPt
        render(i, bmp, Matrix().apply { setScale(s, s) })
        if (closed) { bmp.recycle(); return null }
        return bmp
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Nothing can draw any more (every draw takes this lock and checks [closed]), so pictures can be freed now.
            renderer.clearCaches(recycle = true)
            deck.close()
        }
    }

    companion object {
        fun open(file: File, labels: Labels): PptxSource = PptxSource(PptxParser.open(file), labels)
    }
}
