package com.daftar.app.slides

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.daftar.app.ink.PageSource
import java.io.File

/**
 * Slides of a .pptx as fixed background pages for the ink editor. Page space = slide points.
 * All rendering goes through one lock: the editor's page renderer and the thumbnail loader share the
 * parsed model and caches, and the zip is closed under the same lock.
 */
class PptxSource(val deck: Pptx, labels: Labels) : PageSource {
    private val renderer = PptxRenderer(deck, labels)
    private val lock = Any()
    @Volatile private var closed = false

    override val pageCount: Int get() = deck.slides.size

    override fun pageSize(i: Int): Pair<Float, Float> = deck.widthPt to deck.heightPt

    override fun render(i: Int, dest: Bitmap, m: Matrix) {
        synchronized(lock) {
            if (closed || i !in deck.slides.indices) return
            val c = Canvas(dest)
            c.concat(m)
            try {
                renderer.render(i, c, PptxRenderer.scaleOf(m))
            } catch (_: OutOfMemoryError) {
                renderer.clearCaches()
            } catch (_: Exception) {
                // Leave whatever was drawn; a broken slide must not take the editor down.
            }
        }
    }

    /** Small thumbnail of slide [i], [widthPx] wide. Safe to call from any background thread. */
    fun thumbnail(i: Int, widthPx: Int): Bitmap? {
        val h = (widthPx * deck.heightPt / deck.widthPt).toInt().coerceAtLeast(1)
        val bmp = try { Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { return null }
        bmp.eraseColor(0xFFFFFFFF.toInt())
        val s = widthPx / deck.widthPt
        render(i, bmp, Matrix().apply { setScale(s, s) })
        return if (closed) null else bmp
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            renderer.clearCaches()
            deck.close()
        }
    }

    companion object {
        fun open(file: File, labels: Labels): PptxSource = PptxSource(PptxParser.open(file), labels)
    }
}
