package com.daftar.app.slides

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Engines used by the converter (PPTX → PDF, PPTX → images). Blocking; call them off the main thread.
 * Neither throws: failures return false / an empty list and leave no partial output behind.
 * Both stop early (as a failure) when the calling thread is interrupted (e.g. `runInterruptible`) or, with the overloads
 * taking callbacks, when `isCancelled()` turns true; those overloads also report progress per slide.
 */
object SlidesExport {

    /** Resolution pictures are decoded at for PDF pages (2 px per point ≈ 144 dpi). */
    private const val PDF_IMAGE_DETAIL = 2f

    /** Most slides one PdfDocument keeps before it is flushed to a part file (it holds every page until written). */
    private const val MAX_PAGES_PER_PART = 40

    @Volatile private var appContext: Context? = null

    /** Lets the engines use localised labels (chart / diagram placeholders). Called by the slides screen. */
    fun bind(ctx: Context) { if (appContext == null) appContext = ctx.applicationContext }

    /**
     * Render every slide into a PDF, one page per slide at the slide size in points. Slides are drawn through the
     * renderer straight onto the PDF canvas, so text and shapes stay vector; pictures are embedded at ~144 dpi.
     * Written to a temporary file next to [out], then renamed.
     */
    fun toPdf(src: File, out: File): Boolean = toPdf(src, out, { _, _ -> })

    /**
     * Same as [toPdf], reporting [progress] (slides done, slide count) after each slide, and stopping (returning false,
     * nothing left behind) as soon as [isCancelled] returns true — e.g. `{ !coroutineContext.isActive }` captured by the caller.
     */
    fun toPdf(src: File, out: File, progress: (done: Int, total: Int) -> Unit, isCancelled: () -> Boolean = { false }): Boolean {
        fun stop() = Thread.currentThread().isInterrupted || runCatching { isCancelled() }.getOrDefault(false)
        val dir = out.absoluteFile.parentFile ?: return false
        val parts = ArrayList<File>()
        val tmp = File(dir, ".${out.name}.part")
        var source: PptxSource? = null
        var doc: PdfDocument? = null
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val s = PptxSource.open(src, labels())
            source = s
            val w = s.deck.widthPt.roundToInt().coerceAtLeast(1)
            val h = s.deck.heightPt.roundToInt().coerceAtLeast(1)
            // A PdfDocument keeps every page (and the pictures it drew) in memory until writeTo(); flush to a part file
            // whenever the pictures held reach this budget, then merge the parts.
            val budget = Runtime.getRuntime().maxMemory() / 8
            var pagesInDoc = 0

            fun flush() {
                val d = doc ?: return
                val part = File.createTempFile("slides-part", ".pdf", Storage.cacheDir())
                FileOutputStream(part).use { d.writeTo(it) }
                d.close()
                doc = null
                parts.add(part)
            }

            val total = s.deck.slides.size
            runCatching { progress(0, total) }
            for (i in s.deck.slides.indices) {
                if (stop()) return false
                if (doc == null) { doc = PdfDocument(); pagesInDoc = 0; s.markImages() }
                val d = doc!!
                val page = d.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val c = page.canvas
                c.scale(w / s.deck.widthPt, h / s.deck.heightPt)   // rounding of the page size only
                s.drawVector(i, c, PDF_IMAGE_DETAIL)
                d.finishPage(page)
                pagesInDoc++
                if (i < s.deck.slides.lastIndex && (s.imageBytesSinceMark() > budget || pagesInDoc >= MAX_PAGES_PER_PART)) flush()
                runCatching { progress(i + 1, total) }
            }
            if (stop()) return false
            if (parts.isEmpty()) {
                FileOutputStream(tmp).use { doc!!.writeTo(it) }
                doc!!.close(); doc = null
            } else {
                flush()
                val m = PDFMergerUtility()
                parts.forEach { m.addSource(it) }
                m.destinationFileName = tmp.absolutePath
                m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            }
            if (stop()) return false
            return replace(tmp, out)
        } catch (_: Throwable) {
            return false
        } finally {
            runCatching { doc?.close() }
            source?.close()            // after the documents: their pages reference the cached pictures
            parts.forEach { it.delete() }
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Render each slide to `Slide 01.png` (or `.jpg`) in [outDir] at [scale] × the slide size in points
     * (capped at ~16 MP per image). One bitmap is reused for every slide and recycled at the end.
     * Returns the files written, in slide order; an empty list on failure (nothing is left behind then).
     */
    fun toImages(src: File, outDir: File, png: Boolean = true, scale: Float = 2f): List<File> =
        toImages(src, outDir, png, scale, { _, _ -> })

    /** Same as [toImages] with [progress] (slides done, slide count) and cooperative cancellation via [isCancelled]. */
    fun toImages(
        src: File, outDir: File, png: Boolean, scale: Float,
        progress: (done: Int, total: Int) -> Unit, isCancelled: () -> Boolean = { false },
    ): List<File> {
        fun stop() = Thread.currentThread().isInterrupted || runCatching { isCancelled() }.getOrDefault(false)
        val written = ArrayList<File>()
        var source: PptxSource? = null
        var bmp: Bitmap? = null
        var ok = false
        try {
            if (!outDir.isDirectory && !outDir.mkdirs()) return emptyList()
            val s = PptxSource.open(src, labels())
            source = s
            val wPt = s.deck.widthPt; val hPt = s.deck.heightPt
            var k = if (scale.isFinite() && scale > 0f) scale else 2f
            val maxPixels = 16_000_000f
            if (wPt * k * hPt * k > maxPixels) k = sqrt(maxPixels / (wPt * hPt))
            val bw = (wPt * k).roundToInt().coerceAtLeast(1)
            val bh = (hPt * k).roundToInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bmp = b
            val m = Matrix().apply { setScale(bw / wPt, bh / hPt) }
            val ext = if (png) "png" else "jpg"
            val digits = maxOf(2, s.deck.slides.size.toString().length)
            val total = s.deck.slides.size
            runCatching { progress(0, total) }
            for (i in s.deck.slides.indices) {
                if (stop()) return emptyList()
                b.eraseColor(0xFFFFFFFF.toInt())
                s.render(i, b, m)
                val name = "Slide " + (i + 1).toString().padStart(digits, '0')
                val f = uniqueIn(outDir, name, ext)
                val part = File(outDir, ".${f.name}.part")
                val good = FileOutputStream(part).use { os ->
                    b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 92, os)
                }
                if (!good || !replace(part, f)) { part.delete(); return emptyList() }
                written.add(f)
                runCatching { progress(i + 1, total) }
            }
            ok = true
            return written
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            bmp?.recycle()
            source?.close()
            if (!ok) written.forEach { it.delete() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun uniqueIn(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var n = 2
        while (f.exists()) { f = File(dir, "$base ($n).$ext"); n++ }
        return f
    }

    /** Move [tmp] over [dst] (same directory, so rename is atomic); copy as a fallback. */
    private fun replace(tmp: File, dst: File): Boolean {
        if (tmp.renameTo(dst)) return true
        return runCatching {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
            true
        }.getOrDefault(false)
    }

    private fun labels(): Labels {
        val ctx = appContext ?: currentApplication()?.also { appContext = it }
        return if (ctx != null) Labels(
            ctx.getString(R.string.slides_chart), ctx.getString(R.string.slides_diagram),
            ctx.getString(R.string.slides_object), ctx.getString(R.string.slides_image),
        ) else Labels("", "", "", "")   // boxes without captions rather than untranslated text
    }

    /** The application context when no slides screen has bound one yet (converter used first). */
    private fun currentApplication(): Context? = runCatching { com.daftar.app.data.Storage.appCtx }.getOrNull()
}
