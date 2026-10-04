package com.daftar.app.convert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Build
import android.util.Log
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.daftar.app.ink.exportNoteToPdf
import com.daftar.app.pdf.PdfSource
import com.daftar.app.pdf.PdfTools
import com.daftar.app.slides.PptxException
import com.daftar.app.slides.PptxParser
import com.daftar.app.slides.SlidesExport
import com.daftar.app.slides.XNode
import com.daftar.app.slides.phFamily
import com.daftar.app.slides.phOf
import com.daftar.app.slides.plainText
import com.daftar.app.word.DocLoader
import com.daftar.app.word.DocxExport
import com.daftar.app.word.LegacyDocException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** (done, total) — total ≤ 0 means "unknown" (indeterminate progress). */
typealias Progress = (done: Int, total: Int) -> Unit

/**
 * Every conversion engine. All work runs on [Dispatchers.IO]; loops check for cancellation between pages / items and
 * partial outputs are deleted when a run is cancelled or fails. Failures surface as [ConvertException] with a
 * localized message. Engines owned by other agents (SlidesExport, DocxExport) are called, not reimplemented.
 */
object Engines {
    private const val TAG = "Convert"

    /** Longest rendered side and pixel budget for one page bitmap (≈ 80 MB ARGB). */
    private const val MAX_SIDE = 8192
    private const val MAX_PIXELS = 20_000_000f

    /** PDFTextStripper paragraph marker (never present in real text). */
    private const val PARA = " "

    /** Paragraph understood by [DocxExport.writeDocx] as a page break. */
    const val PAGE_BREAK = "\u000C"

    private const val MAX_TEXT_BYTES = 20L * 1024 * 1024

    suspend fun run(ctx: Context, conv: Conv, sources: List<Src>, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput =
        withContext(Dispatchers.IO) {
            if (sources.isEmpty()) throw ConvertException(R.string.convert_no_source)
            if (sources.size < conv.minSources) throw ConvertException(R.string.convert_need_two)
            outDir.mkdirs()
            SlidesExport.bind(ctx)
            try {
                val first = sources.first()
                when (conv) {
                    Conv.PDF_IMAGES -> pdfToImages(ctx, fileOf(first), opt, outDir, progress)
                    Conv.PDF_PPTX -> pdfToPptx(fileOf(first), opt, outDir, progress)
                    Conv.PDF_DOCX -> pdfToDocx(fileOf(first), opt, outDir, progress)
                    Conv.PDF_TXT -> pdfToTxt(fileOf(first), opt, outDir, progress)
                    Conv.IMAGES_PDF -> imagesToPdf(ctx, sources, outDir, progress)
                    Conv.IMAGES_PPTX -> imagesToPptx(ctx, sources, outDir, progress)
                    Conv.IMAGE_FORMAT -> imageFormat(ctx, sources, opt, outDir, progress)
                    Conv.PPTX_PDF -> pptxToPdf(fileOf(first), outDir, progress)
                    Conv.PPTX_IMAGES -> pptxToImages(ctx, fileOf(first), opt, outDir, progress)
                    Conv.PPTX_TXT -> pptxToTxt(ctx, fileOf(first), outDir, progress)
                    Conv.DOCX_PDF -> docxToPdf(fileOf(first), outDir, progress)
                    Conv.DOCX_TXT -> docxToTxt(fileOf(first), outDir, progress)
                    Conv.NOTE_PDF -> noteToPdf(fileOf(first), outDir, progress)
                    Conv.NOTE_IMAGES -> noteToImages(ctx, fileOf(first), opt, outDir, progress)
                    Conv.NOTE_DOCX -> noteToDocx(fileOf(first), outDir, progress)
                    Conv.TEXT_PDF -> textToPdf(ctx, fileOf(first), outDir, progress)
                    Conv.TEXT_DOCX -> textToDocx(fileOf(first), outDir, progress)
                    Conv.MERGE_PDF -> mergePdfs(ctx, sources.map(::fileOf), outDir, progress)
                    Conv.SPLIT_PDF -> splitPdf(ctx, fileOf(first), opt, outDir, progress)
                    Conv.COMPRESS_PDF -> compressPdf(ctx, fileOf(first), opt, outDir, progress)
                }
            } catch (t: Throwable) {
                throw mapError(t)
            }
        }

    private fun mapError(t: Throwable): Throwable {
        if (t is CancellationException || t is ConvertException) return t
        Log.e(TAG, "conversion failed", t)
        return when (t) {
            is InvalidPasswordException, is SecurityException -> ConvertException(R.string.convert_err_password)
            is LegacyDocException -> ConvertException(R.string.convert_err_legacy_doc)
            is PptxException -> ConvertException(if (t.kind == PptxException.Kind.LEGACY) R.string.convert_err_legacy_ppt else R.string.convert_err_failed)
            is OutOfMemoryError -> ConvertException(R.string.convert_err_memory)
            is FileNotFoundException -> ConvertException(R.string.convert_err_read)
            is IOException -> ConvertException(
                if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space", true) == true) R.string.convert_err_space
                else R.string.convert_err_failed,
            )
            else -> ConvertException(R.string.convert_err_failed)
        }
    }

    // ============================================================================================ helpers

    private suspend fun checkActive() = currentCoroutineContext().ensureActive()

    /** Cancellation check for blocking engines owned by other agents (polled from their worker loop). */
    private suspend fun cancelledFlag(): () -> Boolean {
        val job = currentCoroutineContext()[Job]
        return { job?.isActive == false }
    }

    private fun fileOf(s: Src): File {
        val f = s.libFile ?: throw ConvertException(R.string.convert_err_read)
        if (!f.isFile) throw ConvertException(R.string.convert_err_read)
        return f
    }

    /** Runs [block]; on failure or cancellation deletes [targets] (files or folders) and rethrows. */
    private inline fun <T> cleanupOnFail(vararg targets: File, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            targets.forEach { runCatching { it.deleteRecursively() } }
            throw t
        }

    private fun tmpFor(out: File) = File(out.parentFile, ".${out.name}.part")

    private fun moveInto(tmp: File, out: File) {
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
    }

    private fun newDir(parent: File, name: String): File = Storage.uniqueFile(parent, name, "").apply { mkdirs() }

    private fun pagesFor(range: String, count: Int): List<Int> {
        if (count <= 0) throw ConvertException(R.string.convert_err_failed)
        if (range.isBlank()) return (0 until count).toList()
        return PdfTools.parseRanges(range, count) ?: throw ConvertException(R.string.convert_err_range, count)
    }

    private fun pad(n: Int, count: Int) = n.toString().padStart(max(2, count.toString().length), '0')

    /** Opens a PDF for rendering; maps "password protected" and "damaged" to user messages. */
    private fun openPdf(f: File): PdfSource = try {
        PdfSource(f)
    } catch (e: SecurityException) {
        throw ConvertException(R.string.convert_err_password)
    } catch (e: IOException) {
        throw ConvertException(R.string.convert_err_failed)
    }

    /** Renders page [i] at [dpi] on white, bounded by [MAX_SIDE] / [MAX_PIXELS]. */
    private fun renderPage(src: PdfSource, i: Int, dpi: Int): Bitmap {
        val (pw, ph) = src.pageSize(i)
        var s = min(dpi / 72f, MAX_SIDE / max(pw, ph))
        val px = pw * s * ph * s
        if (px > MAX_PIXELS) s *= sqrt(MAX_PIXELS / px)
        val w = (pw * s).roundToInt().coerceAtLeast(1)
        val h = (ph * s).roundToInt().coerceAtLeast(1)
        return src.renderFit(i, w, h) ?: throw CancellationException("source closed")
    }

    private fun loadInk(f: File): InkDoc? =
        runCatching { InkDoc.load(Storage.sidecar(f, "ink.json")) }.getOrNull()?.takeIf { PdfTools.hasInk(it) }

    /** True when [f] has a non-empty ink layer (used to show the "include annotations" option). */
    fun hasInk(f: File): Boolean = Storage.sidecar(f, "ink.json").let { it.exists() && it.length() > 40 }

    private fun drawInk(bmp: Bitmap, ip: InkPage?, pw: Float, ph: Float) {
        if (ip == null || ip.isEmpty()) return
        val c = Canvas(bmp)
        val iw = if (ip.w > 0f) ip.w else pw
        val ih = if (ip.h > 0f) ip.h else ph
        c.scale(bmp.width / iw, bmp.height / ih)
        InkRender.drawPageContent(c, ip)
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(fmt: ImgFmt, quality: Int): Bitmap.CompressFormat = when (fmt) {
        ImgFmt.PNG -> Bitmap.CompressFormat.PNG
        ImgFmt.JPG -> Bitmap.CompressFormat.JPEG
        ImgFmt.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
            if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else Bitmap.CompressFormat.WEBP
    }

    /** Same pixels on an opaque white background (JPEG has no alpha; transparent areas would turn black). */
    private fun opaque(bmp: Bitmap): Bitmap {
        if (!bmp.hasAlpha()) return bmp
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        out.setHasAlpha(false)
        return out
    }

    private fun writeBitmap(bmp: Bitmap, f: File, fmt: ImgFmt, quality: Int) {
        val src = if (fmt == ImgFmt.JPG) opaque(bmp) else bmp
        try {
            FileOutputStream(f).use { os ->
                if (!src.compress(compressFormat(fmt, quality), quality.coerceIn(1, 100), os)) throw IOException("encode failed")
            }
        } finally {
            if (src !== bmp) src.recycle()
        }
    }

    private fun openStream(ctx: Context, s: Src): InputStream? = when (s) {
        is Src.Lib -> FileInputStream(s.file)
        is Src.Device -> ctx.contentResolver.openInputStream(s.uri)
    }

    /**
     * Decodes an image downsampled so its long side is ≤ [maxSide] and its pixel count ≤ [MAX_PIXELS],
     * then applies the EXIF orientation (rotation / mirroring). Null when the image can't be read.
     */
    fun decodeImage(ctx: Context, s: Src, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(ctx, s)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w0 = bounds.outWidth; val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return@runCatching null
        var sample = 1
        while (max(w0, h0) / sample > maxSide * 2 || (w0.toFloat() / sample) * (h0.toFloat() / sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = openStream(ctx, s)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
        val long = max(bmp.width, bmp.height)
        if (long > maxSide) {
            val k = maxSide.toFloat() / long
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).roundToInt().coerceAtLeast(1), (bmp.height * k).roundToInt().coerceAtLeast(1), true)
            if (scaled !== bmp) { bmp.recycle(); bmp = scaled }
        }
        val m = exifMatrix(ctx, s)
        if (m != null) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) { bmp.recycle(); bmp = r }
        }
        bmp
    }.onFailure { Log.w(TAG, "decode ${s.name}", it) }.getOrNull()

    private fun exifMatrix(ctx: Context, s: Src): Matrix? {
        val o = runCatching {
            openStream(ctx, s)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }

    private fun tempMemory(): MemoryUsageSetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(Storage.cacheDir())

    private fun writeText(out: File, text: String) {
        val tmp = tmpFor(out)
        tmp.writeText(text, Charsets.UTF_8)
        moveInto(tmp, out)
    }

    private fun single(out: File) = ConvOutput(listOf(out), out, out.parentFile ?: out)

    private fun many(files: List<File>, folder: File, skipped: Int = 0) =
        ConvOutput(files, if (files.size == 1) files[0] else folder, folder, skipped = skipped)

    // ============================================================================================ PDF →

    private suspend fun pdfToImages(ctx: Context, f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val fmt = if (opt.format == ImgFmt.WEBP) ImgFmt.PNG else opt.format
        val src = openPdf(f)
        try {
            val pages = pagesFor(opt.range, src.pageCount)
            val ink = if (opt.annotations) loadInk(f) else null
            val dir = newDir(outDir, ctx.getString(R.string.convert_name_images, f.nameWithoutExtension))
            val written = ArrayList<File>()
            cleanupOnFail(dir) {
                progress(0, pages.size)
                for ((k, p) in pages.withIndex()) {
                    checkActive()
                    val bmp = renderPage(src, p, opt.dpi)
                    try {
                        val (pw, ph) = src.pageSize(p)
                        drawInk(bmp, ink?.pages?.getOrNull(p), pw, ph)
                        val out = Storage.uniqueFile(dir, ctx.getString(R.string.convert_name_page, pad(p + 1, src.pageCount)), fmt.ext)
                        writeBitmap(bmp, out, fmt, opt.quality)
                        written.add(out)
                    } finally {
                        bmp.recycle()
                    }
                    progress(k + 1, pages.size)
                }
            }
            return many(written, dir)
        } finally {
            src.close()
        }
    }

    private suspend fun pdfToPptx(f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val src = openPdf(f)
        try {
            val pages = pagesFor(opt.range, src.pageCount)
            val ink = if (opt.annotations) loadInk(f) else null
            val (fw, fh) = src.pageSize(pages.first())
            val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pptx")
            cleanupOnFail(out) {
                PptxWriter(out, fw, fh, f.nameWithoutExtension).use { w ->
                    progress(0, pages.size)
                    for ((k, p) in pages.withIndex()) {
                        checkActive()
                        val bmp = renderPage(src, p, opt.dpi)
                        try {
                            val (pw, ph) = src.pageSize(p)
                            drawInk(bmp, ink?.pages?.getOrNull(p), pw, ph)
                            w.addPicture(bmp.width, bmp.height, "jpeg") { os -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, os) }
                        } finally {
                            bmp.recycle()
                        }
                        progress(k + 1, pages.size)
                    }
                    w.finish()
                }
            }
            return single(out)
        } finally {
            src.close()
        }
    }

    /** Text of the selected pages, one entry per page (PDFTextStripper paragraphs marked with [PARA]). */
    private suspend fun pdfPageTexts(f: File, range: String, progress: Progress): List<String> {
        val doc = try {
            PDDocument.load(f, tempMemory())
        } catch (e: InvalidPasswordException) {
            throw ConvertException(R.string.convert_err_password)
        }
        doc.use { d ->
            if (d.isEncrypted) d.isAllSecurityToBeRemoved = true
            val pages = pagesFor(range, d.numberOfPages)
            val st = PDFTextStripper().apply {
                sortByPosition = false
                lineSeparator = "\n"
                paragraphStart = ""
                paragraphEnd = PARA
                pageStart = ""
                pageEnd = ""
            }
            progress(0, pages.size)
            val out = ArrayList<String>(pages.size)
            for ((k, p) in pages.withIndex()) {
                checkActive()
                st.startPage = p + 1
                st.endPage = p + 1
                out.add(st.getText(d))
                progress(k + 1, pages.size)
            }
            return out
        }
    }

    /** One page of stripper output → paragraphs (lines joined, hyphenation at line ends undone). */
    private fun paragraphsOf(pageText: String): List<String> =
        pageText.split(PARA).mapNotNull { para ->
            val sb = StringBuilder()
            for (raw in para.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (sb.isEmpty()) { sb.append(line); continue }
                val hyphen = sb.length > 1 && sb.last() == '-' && sb[sb.length - 2].isLetter() && line.first().isLowerCase()
                if (hyphen) { sb.setLength(sb.length - 1); sb.append(line) } else sb.append(' ').append(line)
            }
            sb.toString().takeIf { it.isNotBlank() }
        }

    private suspend fun pdfToDocx(f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val pages = pdfPageTexts(f, opt.range) { d, t -> progress(d, t + 1) }
        val paras = ArrayList<String>()
        var any = false
        for ((i, t) in pages.withIndex()) {
            val ps = paragraphsOf(t)
            if (ps.isNotEmpty()) any = true
            if (i > 0) paras.add(PAGE_BREAK)
            paras.addAll(ps)
        }
        if (!any) throw ConvertException(R.string.convert_err_no_text)
        checkActive()
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "docx")
        cleanupOnFail(out) {
            val ok = DocxExport.writeDocx(paras, out, f.nameWithoutExtension)
            checkActive()
            if (!ok || !out.isFile || out.length() == 0L) throw ConvertException(R.string.convert_err_engine_word)
        }
        progress(1, 1)
        return single(out)
    }

    private suspend fun pdfToTxt(f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val pages = pdfPageTexts(f, opt.range, progress)
        val text = pages.joinToString("\n\n") { paragraphsOf(it).joinToString("\n") }.trim()
        if (text.isEmpty()) throw ConvertException(R.string.convert_err_no_text)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "txt")
        cleanupOnFail(out) { writeText(out, text + "\n") }
        return single(out)
    }

    // ============================================================================================ images →

    private fun photosName(ctx: Context, sources: List<Src>): String =
        if (sources.size == 1) sources[0].baseName
        else ctx.getString(R.string.convert_name_photos, SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(Date()))

    private fun isLosslessSource(s: Src, bmp: Bitmap): Boolean =
        s.name.substringAfterLast('.', "").lowercase() in setOf("png", "gif", "bmp") && bmp.width.toLong() * bmp.height <= 4_000_000L

    private suspend fun imagesToPdf(ctx: Context, sources: List<Src>, outDir: File, progress: Progress): ConvOutput {
        val out = Storage.uniqueFile(outDir, photosName(ctx, sources), "pdf")
        val tmp = tmpFor(out)
        var skipped = 0
        cleanupOnFail(tmp, out) {
            PDDocument(tempMemory()).use { doc ->
                progress(0, sources.size)
                for ((k, s) in sources.withIndex()) {
                    checkActive()
                    val bmp = decodeImage(ctx, s, 2480)
                    if (bmp == null) { skipped++; progress(k + 1, sources.size); continue }
                    try {
                        // A4 width, height from the image aspect (PDF pages are limited to 14400 pt).
                        val pw = 595f
                        val ph = (pw * bmp.height / bmp.width).coerceIn(1f, 14400f)
                        val img = if (isLosslessSource(s, bmp)) LosslessFactory.createFromImage(doc, bmp) else {
                            val flat = opaque(bmp)
                            try { JPEGFactory.createFromImage(doc, flat, 0.9f) } finally { if (flat !== bmp) flat.recycle() }
                        }
                        val sc = min(pw / bmp.width, ph / bmp.height)
                        val dw = bmp.width * sc; val dh = bmp.height * sc
                        val page = PDPage(PDRectangle(pw, ph))
                        doc.addPage(page)
                        PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, (pw - dw) / 2f, (ph - dh) / 2f, dw, dh) }
                    } finally {
                        bmp.recycle()
                    }
                    progress(k + 1, sources.size)
                }
                if (doc.numberOfPages == 0) throw ConvertException(R.string.convert_err_no_images)
                checkActive()
                doc.save(tmp)
            }
            moveInto(tmp, out)
        }
        return single(out).copy(skipped = skipped)
    }

    private suspend fun imagesToPptx(ctx: Context, sources: List<Src>, outDir: File, progress: Progress): ConvOutput {
        val out = Storage.uniqueFile(outDir, photosName(ctx, sources), "pptx")
        var skipped = 0
        var writer: PptxWriter? = null
        cleanupOnFail(out) {
            try {
                progress(0, sources.size)
                for ((k, s) in sources.withIndex()) {
                    checkActive()
                    val bmp = decodeImage(ctx, s, 3000)
                    if (bmp == null) { skipped++; progress(k + 1, sources.size); continue }
                    try {
                        // The first readable picture decides the slide size.
                        val w = writer ?: PptxWriter(out, bmp.width.toFloat(), bmp.height.toFloat(), photosName(ctx, sources)).also { writer = it }
                        if (isLosslessSource(s, bmp)) {
                            w.addPicture(bmp.width, bmp.height, "png") { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
                        } else {
                            val flat = opaque(bmp)
                            try {
                                w.addPicture(flat.width, flat.height, "jpeg") { os -> flat.compress(Bitmap.CompressFormat.JPEG, 90, os) }
                            } finally {
                                if (flat !== bmp) flat.recycle()
                            }
                        }
                    } finally {
                        bmp.recycle()
                    }
                    progress(k + 1, sources.size)
                }
                val w = writer ?: throw ConvertException(R.string.convert_err_no_images)
                checkActive()
                w.finish()
            } finally {
                writer?.close()
            }
        }
        return single(out).copy(skipped = skipped)
    }

    private suspend fun imageFormat(ctx: Context, sources: List<Src>, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val written = ArrayList<File>()
        var skipped = 0
        try {
            progress(0, sources.size)
            for ((k, s) in sources.withIndex()) {
                checkActive()
                val bmp = decodeImage(ctx, s, MAX_SIDE)
                if (bmp == null) { skipped++; progress(k + 1, sources.size); continue }
                try {
                    val out = Storage.uniqueFile(outDir, s.baseName, opt.format.ext)
                    cleanupOnFail(out) { writeBitmap(bmp, out, opt.format, opt.quality) }
                    written.add(out)
                } finally {
                    bmp.recycle()
                }
                progress(k + 1, sources.size)
            }
        } catch (t: Throwable) {
            written.forEach { it.delete() }
            throw t
        }
        if (written.isEmpty()) throw ConvertException(R.string.convert_err_no_images)
        return many(written, outDir, skipped)
    }

    // ============================================================================================ PowerPoint →

    private suspend fun pptxToPdf(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pdf")
        cleanupOnFail(out) {
            val ok = SlidesExport.toPdf(f, out, { d, t -> progress(d, t) }, cancelledFlag())
            checkActive()
            if (!ok || !out.isFile || out.length() == 0L) throw ConvertException(R.string.convert_err_engine_slides)
        }
        progress(1, 1)
        return single(out)
    }

    private suspend fun pptxToImages(ctx: Context, f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val dir = newDir(outDir, ctx.getString(R.string.convert_name_images, f.nameWithoutExtension))
        val files = cleanupOnFail(dir) {
            val res = SlidesExport.toImages(f, dir, opt.format != ImgFmt.JPG, opt.scale, { d, t -> progress(d, t) }, cancelledFlag())
                .filter { it.isFile && it.length() > 0 }
            checkActive()
            if (res.isEmpty()) throw ConvertException(R.string.convert_err_engine_slides)
            res
        }
        progress(1, 1)
        return many(files, dir)
    }

    private suspend fun pptxToTxt(ctx: Context, f: File, outDir: File, progress: Progress): ConvOutput {
        val sb = StringBuilder()
        PptxParser.open(f).use { deck ->
            progress(0, deck.slides.size)
            for ((k, s) in deck.slides.withIndex()) {
                checkActive()
                if (sb.isNotEmpty()) sb.append("\n\n")
                val title = s.title.trim()
                sb.append(ctx.getString(R.string.convert_txt_slide, deck.firstSlideNum + k))
                if (title.isNotEmpty()) sb.append(" — ").append(title)
                sb.append('\n')
                val body = ArrayList<String>()
                s.part.root.path("cSld", "spTree")?.let { collectShapeText(it, body) }
                for (t in body) sb.append(t).append('\n')
                val notes = s.notes.trim()
                if (notes.isNotEmpty()) sb.append('\n').append(ctx.getString(R.string.convert_txt_notes)).append('\n').append(notes).append('\n')
                progress(k + 1, deck.slides.size)
            }
        }
        val text = sb.toString().trim()
        if (text.isEmpty()) throw ConvertException(R.string.convert_err_no_text_doc)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "txt")
        cleanupOnFail(out) { writeText(out, text + "\n") }
        return single(out)
    }

    /** Text of every shape in a slide's shape tree in z-order (title placeholder skipped; tables row by row). */
    private fun collectShapeText(tree: XNode, out: MutableList<String>) {
        for (sh in tree.children) when (sh.name) {
            "sp" -> {
                val ph = phOf(sh)
                if (ph != null && phFamily(ph.attr("type") ?: "body") == "title") continue
                if (ph != null && (ph.attr("type") == "sldNum" || ph.attr("type") == "dt" || ph.attr("type") == "ftr")) continue
                val t = plainText(sh.child("txBody")).trim()
                if (t.isNotEmpty()) out.add(t)
            }
            "grpSp" -> collectShapeText(sh, out)
            "graphicFrame" -> for (tr in sh.findAll("tr")) {
                val row = tr.children("tc").joinToString("\t") { tc -> plainText(tc.child("txBody")).replace('\n', ' ').trim() }
                if (row.isNotBlank()) out.add(row)
            }
            "AlternateContent" -> sh.child("Choice")?.let { collectShapeText(it, out) }
        }
    }

    // ============================================================================================ Word →

    private suspend fun docxToPdf(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pdf")
        cleanupOnFail(out) {
            val cancelled = cancelledFlag()
            val ok = DocxExport.toPdf(f, out) { _ -> !cancelled() }
            checkActive()
            if (!ok || !out.isFile || out.length() == 0L) throw ConvertException(R.string.convert_err_engine_word)
        }
        progress(1, 1)
        return single(out)
    }

    private suspend fun docxToTxt(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val text = DocLoader.load(f).plainText().trim()
        checkActive()
        if (text.isEmpty()) throw ConvertException(R.string.convert_err_no_text_doc)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "txt")
        cleanupOnFail(out) { writeText(out, text + "\n") }
        progress(1, 1)
        return single(out)
    }

    // ============================================================================================ notes & text →

    private fun loadNote(f: File): InkDoc =
        InkDoc.load(f)?.takeIf { it.pages.isNotEmpty() } ?: throw ConvertException(R.string.convert_err_failed)

    private suspend fun noteToPdf(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val doc = loadNote(f)
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pdf")
        val tmp = tmpFor(out)
        cleanupOnFail(tmp, out) {
            exportNoteToPdf(doc, tmp)
            checkActive()
            moveInto(tmp, out)
        }
        progress(1, 1)
        return single(out)
    }

    private suspend fun noteToImages(ctx: Context, f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val fmt = if (opt.format == ImgFmt.WEBP) ImgFmt.PNG else opt.format
        val doc = loadNote(f)
        val dark = (Color.red(doc.paperColor) * 299 + Color.green(doc.paperColor) * 587 + Color.blue(doc.paperColor) * 114) / 1000 < 110
        val dir = newDir(outDir, ctx.getString(R.string.convert_name_images, f.nameWithoutExtension))
        val written = ArrayList<File>()
        cleanupOnFail(dir) {
            progress(0, doc.pages.size)
            for ((i, p) in doc.pages.withIndex()) {
                checkActive()
                val pw = p.w.coerceAtLeast(1f); val ph = p.h.coerceAtLeast(1f)
                var s = min(2f, 4096f / max(pw, ph))
                if (pw * s * ph * s > MAX_PIXELS) s *= sqrt(MAX_PIXELS / (pw * s * ph * s))
                val bmp = Bitmap.createBitmap((pw * s).roundToInt().coerceAtLeast(1), (ph * s).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bmp.eraseColor(doc.paperColor or 0xFF000000.toInt())
                    val c = Canvas(bmp)
                    c.scale(bmp.width / pw, bmp.height / ph)
                    InkRender.drawPaper(c, p, dark)
                    InkRender.drawPageContent(c, p)
                    val out = Storage.uniqueFile(dir, ctx.getString(R.string.convert_name_page, pad(i + 1, doc.pages.size)), fmt.ext)
                    writeBitmap(bmp, out, fmt, opt.quality)
                    written.add(out)
                } finally {
                    bmp.recycle()
                }
                progress(i + 1, doc.pages.size)
            }
        }
        return many(written, dir)
    }

    private suspend fun noteToDocx(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val doc = loadNote(f)
        val paras = ArrayList<String>()
        var any = false
        for (p in doc.pages) {
            val items = ArrayList<Triple<Float, Float, List<String>>>()
            for (t in p.texts) if (t.text.isNotBlank()) items.add(Triple(t.y, t.x, t.text.trimEnd().split('\n')))
            for (l in p.links) items.add(Triple(l.y, l.x, listOf(if (l.label.isBlank() || l.label == l.target) l.target else "${l.label} (${l.target})")))
            if (items.isEmpty()) continue
            if (any) paras.add(PAGE_BREAK)
            any = true
            items.sortWith(compareBy({ it.first }, { it.second }))
            items.forEachIndexed { i, (_, _, lines) ->
                if (i > 0) paras.add("")
                paras.addAll(lines)
            }
        }
        if (!any) throw ConvertException(R.string.convert_err_note_no_text)
        checkActive()
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "docx")
        cleanupOnFail(out) {
            val ok = DocxExport.writeDocx(paras, out, f.nameWithoutExtension)
            checkActive()
            if (!ok || !out.isFile || out.length() == 0L) throw ConvertException(R.string.convert_err_engine_word)
        }
        progress(1, 1)
        return single(out)
    }

    private fun readTextFile(f: File): String {
        if (f.length() > MAX_TEXT_BYTES) throw ConvertException(R.string.convert_err_too_large)
        val text = TextPdf.decode(f.readBytes())
        return if (f.extension.equals("rtf", true)) TextPdf.rtfToText(text) else text
    }

    private suspend fun textToPdf(ctx: Context, f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        if (f.extension.lowercase() in setOf("rtf", "csv", "tsv")) {
            // The Word engine keeps RTF formatting and lays CSV/TSV out as a table; plain text rendering is the fallback.
            val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pdf")
            val cancelled = cancelledFlag()
            val ok = runCatching { DocxExport.toPdf(f, out) { _ -> !cancelled() } }.getOrDefault(false)
            checkActive()
            if (ok && out.isFile && out.length() > 0L) { progress(1, 1); return single(out) }
            out.delete()
        }
        val text = readTextFile(f)
        val style = when (f.extension.lowercase()) {
            "md", "markdown" -> TextPdf.Style.MARKDOWN
            "csv", "tsv", "log" -> TextPdf.Style.MONO
            else -> TextPdf.Style.PLAIN
        }
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "pdf")
        val tmp = tmpFor(out)
        cleanupOnFail(tmp, out) {
            TextPdf.write(ctx, text, style, tmp, progress)
            moveInto(tmp, out)
        }
        return single(out)
    }

    private suspend fun textToDocx(f: File, outDir: File, progress: Progress): ConvOutput {
        progress(0, 0)
        val raw = readTextFile(f)
        val md = f.extension.lowercase().let { it == "md" || it == "markdown" }
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { if (md) TextPdf.markdownToPlain(it) else it }
        if (lines.all { it.isBlank() }) throw ConvertException(R.string.convert_err_no_text_doc)
        checkActive()
        val out = Storage.uniqueFile(outDir, f.nameWithoutExtension, "docx")
        cleanupOnFail(out) {
            val ok = DocxExport.writeDocx(lines.map { it.replace('\u000C', ' ') }.dropLastWhile { it.isBlank() }, out, f.nameWithoutExtension)
            checkActive()
            if (!ok || !out.isFile || out.length() == 0L) throw ConvertException(R.string.convert_err_engine_word)
        }
        progress(1, 1)
        return single(out)
    }

    // ============================================================================================ PDF tools

    private suspend fun mergePdfs(ctx: Context, files: List<File>, outDir: File, progress: Progress): ConvOutput {
        val out = Storage.uniqueFile(outDir, ctx.getString(R.string.convert_name_merged, files.first().nameWithoutExtension), "pdf")
        val tmp = tmpFor(out)
        val opened = ArrayList<PDDocument>()
        cleanupOnFail(tmp, out) {
            try {
                PDDocument(tempMemory()).use { dst ->
                    val merger = PDFMergerUtility()
                    progress(0, files.size + 1)
                    for ((k, f) in files.withIndex()) {
                        checkActive()
                        val d = try {
                            PDDocument.load(f, tempMemory())
                        } catch (e: InvalidPasswordException) {
                            throw ConvertException(R.string.convert_err_password)
                        }
                        opened.add(d)
                        if (d.isEncrypted) d.isAllSecurityToBeRemoved = true
                        merger.appendDocument(dst, d)
                        progress(k + 1, files.size + 1)
                    }
                    checkActive()
                    dst.documentInformation.title = out.nameWithoutExtension
                    dst.save(tmp)
                }
            } finally {
                opened.forEach { runCatching { it.close() } }
            }
            moveInto(tmp, out)
        }
        progress(files.size + 1, files.size + 1)
        return single(out)
    }

    /** Page groups for a split; validated against [count]. Null when the input is invalid. */
    fun splitGroups(opt: ConvOptions, count: Int): List<List<Int>>? {
        if (count < 1) return null
        return when (opt.splitMode) {
            SplitMode.EVERY -> if (opt.splitEvery < 1) null else (0 until count).chunked(opt.splitEvery)
            SplitMode.RANGES -> {
                val parts = PdfTools.toAsciiDigits(opt.splitRanges).split(',', '،', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) return null
                val groups = ArrayList<List<Int>>(parts.size)
                for (part in parts) groups.add(PdfTools.parseRanges(part, count) ?: return null)
                groups
            }
        }
    }

    private fun groupLabel(g: List<Int>): String {
        val asc = g.zipWithNext().all { (a, b) -> b == a + 1 }
        return when {
            g.size == 1 -> "${g[0] + 1}"
            asc -> "${g.first() + 1}-${g.last() + 1}"
            else -> g.joinToString(",") { "${it + 1}" }.take(40)
        }
    }

    private suspend fun splitPdf(ctx: Context, f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val doc = try {
            PDDocument.load(f, tempMemory())
        } catch (e: InvalidPasswordException) {
            throw ConvertException(R.string.convert_err_password)
        }
        doc.use { src ->
            if (src.isEncrypted) src.isAllSecurityToBeRemoved = true
            val groups = splitGroups(opt, src.numberOfPages) ?: throw ConvertException(R.string.convert_err_split, src.numberOfPages)
            val base = f.nameWithoutExtension
            val dir = newDir(outDir, ctx.getString(R.string.convert_name_split, base))
            val written = ArrayList<File>()
            cleanupOnFail(dir) {
                progress(0, groups.size)
                for ((k, g) in groups.withIndex()) {
                    checkActive()
                    val out = Storage.uniqueFile(dir, "$base ${groupLabel(g)}", "pdf")
                    PDDocument().use { part ->
                        for (p in g) part.importPage(src.getPage(p))
                        part.save(out)
                    }
                    written.add(out)
                    progress(k + 1, groups.size)
                }
            }
            return many(written, dir)
        }
    }

    private suspend fun compressPdf(ctx: Context, f: File, opt: ConvOptions, outDir: File, progress: Progress): ConvOutput {
        val before = f.length()
        val src = openPdf(f)
        val out = Storage.uniqueFile(outDir, ctx.getString(R.string.convert_name_compressed, f.nameWithoutExtension), "pdf")
        val tmp = tmpFor(out)
        try {
            val ink = if (opt.annotations) loadInk(f) else null
            cleanupOnFail(tmp, out) {
                PDDocument(tempMemory()).use { dst ->
                    val n = src.pageCount
                    progress(0, n)
                    for (i in 0 until n) {
                        checkActive()
                        val (pw, ph) = src.pageSize(i)
                        val bmp = renderPage(src, i, opt.dpi)
                        try {
                            drawInk(bmp, ink?.pages?.getOrNull(i), pw, ph)
                            bmp.setHasAlpha(false)
                            val img = JPEGFactory.createFromImage(dst, bmp, opt.quality.coerceIn(10, 100) / 100f)
                            val page = PDPage(PDRectangle(pw, ph))
                            dst.addPage(page)
                            PDPageContentStream(dst, page).use { cs -> cs.drawImage(img, 0f, 0f, pw, ph) }
                        } finally {
                            bmp.recycle()
                        }
                        progress(i + 1, n)
                    }
                    checkActive()
                    dst.save(tmp)
                }
                moveInto(tmp, out)
            }
        } finally {
            src.close()
        }
        return single(out).copy(sizeBefore = before, sizeAfter = out.length())
    }

    /** Page count of a PDF (for range hints), or 0 when it can't be opened. */
    fun pdfPageCount(f: File): Int = runCatching {
        val s = PdfSource(f)
        try { s.pageCount } finally { s.close() }
    }.getOrDefault(0)
}
