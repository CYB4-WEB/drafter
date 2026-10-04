package com.daftar.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF processing used by the PDF viewer and by Home / Library quick actions.
 * Every function does blocking IO — call from Dispatchers.IO.
 */
object PdfTools {
    private const val TAG = "PdfTools"

    /** Overlay resolution for annotated export: ink is rasterized at 3× page points (216 dpi). */
    private const val INK_SCALE = 3f
    private const val MAX_BITMAP_SIDE = 4096

    // ---------------------------------------------------------------- images → PDF

    /** Build one PDF from images (each image = one page, A4 width, aspect kept). Returns the new file or null. */
    fun imagesToPdf(ctx: Context, images: List<Uri>, out: File): File? {
        if (images.isEmpty()) return null
        val doc = PdfDocument()
        val tmp = File(out.parentFile, out.name + ".tmp")
        return try {
            var pageNo = 0
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            for (uri in images) {
                val bmp = decodeImage(ctx, uri, 2480) ?: continue
                try {
                    val pw = 595
                    val ph = (pw * bmp.height.toFloat() / bmp.width).roundToInt().coerceIn(1, 842 * 2)
                    pageNo++
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, pageNo).create())
                    val c = page.canvas
                    c.drawColor(Color.WHITE)
                    // Fit inside the page (only differs from full-bleed when the height was capped).
                    val s = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                    val dw = bmp.width * s; val dh = bmp.height * s
                    val left = (pw - dw) / 2f; val top = (ph - dh) / 2f
                    c.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
                    doc.finishPage(page)
                } finally {
                    bmp.recycle()
                }
            }
            if (pageNo == 0) return null
            out.parentFile?.mkdirs()
            FileOutputStream(tmp).use { doc.writeTo(it) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "imagesToPdf failed", t)
            tmp.delete()
            null
        } finally {
            doc.close()
        }
    }

    /** Decodes [uri] downsampled so the long side is ≤ [maxSide], rotated upright using the MediaStore orientation. */
    fun decodeImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        // Exact downscale to maxSide if still larger.
        val long = max(bmp.width, bmp.height)
        if (long > maxSide) {
            val s = maxSide.toFloat() / long
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
            if (scaled != bmp) { bmp.recycle(); bmp = scaled }
        }
        val deg = orientationOf(ctx, uri)
        if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) { bmp.recycle(); bmp = r }
        }
        bmp
    }.onFailure { Log.e(TAG, "decode $uri", it) }.getOrNull()

    /** Orientation in degrees from the MediaStore column when the provider exposes it, else 0. */
    @Suppress("DEPRECATION")
    private fun orientationOf(ctx: Context, uri: Uri): Int = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.ORIENTATION), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(MediaStore.Images.ImageColumns.ORIENTATION)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) ((c.getInt(idx) % 360) + 360) % 360 else 0
        } ?: 0
    }.getOrDefault(0)

    // ---------------------------------------------------------------- annotated export

    /** True when any page of [ink] carries strokes, text or images. */
    fun hasInk(ink: InkDoc?): Boolean = ink?.pages?.any { !it.isEmpty() } == true

    /**
     * Writes [src] with the ink layer burned in to [out]. Original vector content (selectable text) is kept;
     * each annotated page gets one transparent PNG overlay covering its crop box, honouring /Rotate.
     */
    fun exportAnnotated(src: File, ink: InkDoc, out: File): File {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        if (!hasInk(ink)) {
            src.copyTo(tmp, true)
        } else {
            PDDocument.load(src).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val n = doc.numberOfPages
                for (i in 0 until min(n, ink.pages.size)) {
                    val ip = ink.pages[i]
                    if (ip.isEmpty()) continue
                    val page = doc.getPage(i)
                    val crop = page.cropBox
                    val x0 = crop.lowerLeftX; val y0 = crop.lowerLeftY
                    val w = crop.width; val h = crop.height
                    val rot = ((page.rotation % 360) + 360) % 360
                    // Displayed size (what the ink was drawn on).
                    val dispW = if (rot == 90 || rot == 270) h else w
                    val dispH = if (rot == 90 || rot == 270) w else h
                    val bmp = rasterizeInk(ip, dispW, dispH, INK_SCALE)
                    try {
                        val img = LosslessFactory.createFromImage(doc, bmp)
                        val m = when (rot) {
                            90 -> PdfMatrix(0f, h, -w, 0f, x0 + w, y0)
                            180 -> PdfMatrix(-w, 0f, 0f, -h, x0 + w, y0 + h)
                            270 -> PdfMatrix(0f, -h, w, 0f, x0, y0 + h)
                            else -> PdfMatrix(w, 0f, 0f, h, x0, y0)
                        }
                        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.saveGraphicsState()
                            cs.drawImage(img, m)
                            cs.restoreGraphicsState()
                        }
                    } finally {
                        bmp.recycle()
                    }
                }
                doc.save(tmp)
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        return out
    }

    /** Transparent bitmap of the ink of one page; page points [pw]×[ph] scaled by [scale] (capped). */
    private fun rasterizeInk(ip: InkPage, pw: Float, ph: Float, scale: Float): Bitmap {
        val s = min(scale, MAX_BITMAP_SIDE / max(pw, ph))
        val bw = (pw * s).roundToInt().coerceAtLeast(1)
        val bh = (ph * s).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        // Ink coordinates are in the ink page's own size (normally identical to the displayed page size).
        val iw = if (ip.w > 0f) ip.w else pw
        val ih = if (ip.h > 0f) ip.h else ph
        c.scale(bw / iw, bh / ih)
        InkRender.drawPageContent(c, ip)
        return bmp
    }

    /** Draws the ink of [ip] on top of a page bitmap that shows the page at [bw]×[bh] pixels. */
    private fun overlayInk(target: Bitmap, ip: InkPage?, pw: Float, ph: Float) {
        if (ip == null || ip.isEmpty()) return
        val c = Canvas(target)
        val iw = if (ip.w > 0f) ip.w else pw
        val ih = if (ip.h > 0f) ip.h else ph
        c.scale(target.width / iw, target.height / ih)
        InkRender.drawPageContent(c, ip)
    }

    // ---------------------------------------------------------------- text

    /** Text of pages [fromPage]..[toPage] (1-based, inclusive). Empty string when the pages have no text layer. */
    fun extractText(file: File, fromPage: Int, toPage: Int): String =
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val n = doc.numberOfPages
            val stripper = PDFTextStripper().apply {
                startPage = fromPage.coerceIn(1, n)
                endPage = toPage.coerceIn(startPage, n)
                sortByPosition = false
            }
            stripper.getText(doc).trim()
        }

    // ---------------------------------------------------------------- pages

    /**
     * Parses "1-3, 5, 8-10" into 0-based page indices in the given order (descending ranges allowed, e.g. "5-3").
     * Returns null when anything is malformed or out of 1..[pageCount].
     */
    fun parseRanges(text: String, pageCount: Int): List<Int>? {
        val parts = text.split(',', '،', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<Int>()
        for (p in parts) {
            val norm = toAsciiDigits(p).replace('–', '-').replace('—', '-')
            val m = Regex("^(\\d+)\\s*(?:-\\s*(\\d+))?$").find(norm) ?: return null
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: a
            if (a !in 1..pageCount || b !in 1..pageCount) return null
            if (a <= b) for (i in a..b) out.add(i - 1) else for (i in a downTo b) out.add(i - 1)
            if (out.size > 10_000) return null
        }
        return out
    }

    /** Compact label for file names: "1-3,5". */
    fun rangeLabel(text: String): String =
        toAsciiDigits(text).split(',', '،', ';').map { it.replace(" ", "").replace('–', '-').replace('—', '-') }
            .filter { it.isNotEmpty() }.joinToString(",")

    /** Arabic-Indic / Persian digits → ASCII so Arabic keyboards work in page inputs. */
    fun toAsciiDigits(s: String): String = buildString(s.length) {
        for (ch in s) append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                else -> ch
            }
        )
    }

    /** New PDF at [out] with the pages [pages] (0-based) of [src] in that order. */
    fun extractPages(src: File, pages: List<Int>, out: File): File {
        require(pages.isNotEmpty())
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            PDDocument().use { dst ->
                for (p in pages) {
                    if (p !in 0 until doc.numberOfPages) continue
                    dst.importPage(doc.getPage(p))
                }
                dst.save(tmp)
            }
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        return out
    }

    /**
     * Renders [pages] (0-based) of [src] to images in [outDir] named "Page 01.png" (zero padded to the page count width).
     * Resolution: 2.08× page points (150 dpi), long side capped at 4096 px. Ink from [ink] is drawn on top when given.
     * Stops early when [isCancelled] returns true. Returns the files written.
     */
    fun pagesToImages(
        src: File,
        pages: List<Int>,
        outDir: File,
        jpeg: Boolean,
        ink: InkDoc? = null,
        nameFor: (paddedNumber: String) -> String = { "Page $it" },
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<File> {
        outDir.mkdirs()
        val written = ArrayList<File>()
        val source = PdfSource(src)
        try {
            val pad = max(2, source.pageCount.toString().length)
            val scale = 150f / 72f
            onProgress(0, pages.size)
            for ((k, p) in pages.withIndex()) {
                if (isCancelled()) break
                if (p !in 0 until source.pageCount) continue
                val (pw, ph) = source.pageSize(p)
                val s = min(scale, MAX_BITMAP_SIDE / max(pw, ph))
                val bmp = source.renderFit(p, (pw * s).roundToInt(), (ph * s).roundToInt()) ?: break
                try {
                    overlayInk(bmp, ink?.pages?.getOrNull(p), pw, ph)
                    val base = com.daftar.app.data.Storage.sanitize(nameFor((p + 1).toString().padStart(pad, '0')))
                    val f = File(outDir, "$base.${if (jpeg) "jpg" else "png"}")
                    FileOutputStream(f).use { os ->
                        bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, if (jpeg) 92 else 100, os)
                    }
                    written.add(f)
                } finally {
                    bmp.recycle()
                }
                onProgress(k + 1, pages.size)
            }
        } finally {
            source.close()
        }
        return written
    }

    // ---------------------------------------------------------------- print

    /** Opens the system print dialog for [file] (a ready PDF). [ctx] must be an Activity context. */
    fun print(ctx: Context, file: File, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, FilePrintAdapter(file, jobName), PrintAttributes.Builder().build())
    }

    private class FilePrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            val count = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    android.graphics.pdf.PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            val info = PrintDocumentInfo.Builder(com.daftar.app.data.Storage.sanitize(name).ifBlank { "document" } + ".pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(count)
                .build()
            callback.onLayoutFinished(info, oldAttributes == null || oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { inp ->
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancellationSignal?.isCanceled == true) { callback.onWriteCancelled(); return }
                            val r = inp.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                        }
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                Log.e(TAG, "print write failed", t)
                callback.onWriteFailed(t.message)
            }
        }
    }
}
