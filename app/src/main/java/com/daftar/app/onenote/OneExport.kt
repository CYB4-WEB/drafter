package com.daftar.app.onenote

import android.graphics.pdf.PdfDocument
import com.daftar.app.data.Storage
import com.daftar.app.ink.ImageItem
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.Stroke
import com.daftar.app.ink.TextItem
import com.daftar.app.ink.Tool
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.min

/** Page of a book with the section it belongs to (exports name things after both). */
internal class PageRef(val section: OneSection, val page: OnePage)

/** Writing OneNote pages out as PDF, plain text or Daftar notes. Run off the main thread; [cancelled] is polled per page. */
internal object OneExport {
    private const val MAX_PDF_H = 14000f

    /** Exports [pages] to one PDF (one PDF page per OneNote page; very long pages are split). */
    fun pdf(pages: List<PageRef>, out: File, fileLabel: String, progress: (Int) -> Unit, cancelled: () -> Boolean) {
        val doc = PdfDocument()
        try {
            var n = 1
            for ((i, ref) in pages.withIndex()) {
                if (cancelled()) throw InterruptedException()
                val laid = OneLayout.layout(ref.page) { b -> sizeOf(b) }
                val parts = ceil(laid.height / MAX_PDF_H).toInt().coerceAtLeast(1)
                val bitmaps = HashMap<String, android.graphics.Bitmap?>()
                for (k in 0 until parts) {
                    val h = min(MAX_PDF_H, laid.height - k * MAX_PDF_H)
                    val pg = doc.startPage(PdfDocument.PageInfo.Builder(laid.width.toInt(), h.toInt().coerceAtLeast(1), n++).create())
                    val c = pg.canvas
                    c.drawColor(OneLayout.WHITE)
                    c.translate(0f, -k * MAX_PDF_H)
                    OneLayout.draw(c, laid, { b -> bitmaps.getOrPut("${b.offset}") { decodeForExport(b, 2000) } }, fileLabel)
                    doc.finishPage(pg)
                }
                bitmaps.values.forEach { it?.recycle() }
                progress(((i + 1) * 100) / pages.size)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } catch (e: Throwable) {
            out.delete()
            throw e
        } finally {
            doc.close()
        }
    }

    fun text(pages: List<PageRef>): String = buildString {
        var lastSection: OneSection? = null
        for (r in pages) {
            if (r.section !== lastSection && pages.any { it.section !== r.section }) {
                append("=== ").append(r.section.name).append(" ===\n\n")
            }
            lastSection = r.section
            append(r.page.plainText().trimEnd()).append("\n\n")
        }
    }

    /** One Daftar note with one note page per OneNote page: text boxes, pictures and ink keep their places. */
    fun note(pages: List<PageRef>, out: File, cancelled: () -> Boolean) {
        var id = System.currentTimeMillis() * 1000
        val inkPages = ArrayList<InkPage>()
        for (ref in pages) {
            if (cancelled()) throw InterruptedException()
            val laid = OneLayout.layout(ref.page) { b -> sizeOf(b) }
            val texts = ArrayList<TextItem>()
            val images = ArrayList<ImageItem>()
            val strokes = ArrayList<Stroke>()
            for (it in laid.items) when (it) {
                is LaidText -> {
                    val p = it.para
                    val label = p?.list?.let { l -> "$l " } ?: ""
                    val body = p?.text ?: it.layout.text.toString()
                    if (body.isBlank() && label.isEmpty()) continue
                    val look = paraLook(p?.style)
                    val first = p?.runs?.firstOrNull { r -> r.text.isNotBlank() }
                    val size = first?.size?.takeIf { s -> s > 0f } ?: if (p == null) 20f else look.size
                    val color = first?.link?.let { LINK_COLOR } ?: first?.color ?: look.color ?: 0xFF1C1B19.toInt()
                    val bold = p != null && p.runs.filter { r -> r.text.isNotBlank() }.let { rs -> rs.isNotEmpty() && rs.all { r -> r.bold } }
                    val x = if (label.isNotEmpty() && !(p?.rtl ?: false)) it.labelX else it.x
                    texts.add(TextItem(id++, x, it.y, it.w + (it.x - x), label + body, size, color, if (look.mono) "mono" else "sans", bold || look.bold))
                }
                is LaidImage -> {
                    val bmp = it.image.data?.let { b -> decodeForExport(b, 2000) } ?: continue
                    images.add(ImageItem(id++, it.rect.left, it.rect.top, it.rect.width(), it.rect.height(), ImageItem.encode(bmp)))
                    bmp.recycle()
                }
                is LaidInk -> for (s in it.ink.strokes) {
                    val n = s.pts.size / 2
                    if (n < 1) continue
                    val pts = FloatArray(n * 3)
                    for (k in 0 until n) { pts[k * 3] = it.ox + s.pts[k * 2]; pts[k * 3 + 1] = it.oy + s.pts[k * 2 + 1]; pts[k * 3 + 2] = 0.6f }
                    strokes.add(Stroke(if (s.highlighter) Tool.HIGHLIGHTER else Tool.PEN, s.color, s.width, pts))
                }
                is LaidLine -> strokes.add(Stroke(Tool.PEN, 0xFF9CA3AF.toInt(), 0.75f, floatArrayOf(it.x1, it.y1, 0.6f, it.x2, it.y2, 0.6f)))
                is LaidFile -> texts.add(TextItem(id++, it.rect.left, it.rect.top, it.rect.width(), "[" + it.name + "]", 11f))
            }
            inkPages.add(InkPage(w = maxOf(595f, laid.width), h = maxOf(842f, laid.height), paper = "plain", strokes = strokes, texts = texts, images = images))
        }
        InkDoc(pages = inkPages).save(out)
        Storage.touch()
    }

    private val sizeCache = HashMap<String, IntArray?>()
    private fun sizeOf(b: Blob): IntArray? = synchronized(sizeCache) {
        sizeCache.getOrPut("${b.path}@${b.offset}") {
            val bytes = b.read() ?: return@getOrPut null
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth > 0) intArrayOf(o.outWidth, o.outHeight) else null
        }
    }

    /** Writes an attachment's bytes to [out]. */
    fun saveAttachment(a: OneAttachment, out: File): Boolean {
        val bytes = a.data?.read(256L * 1024 * 1024) ?: return false
        return runCatching { out.writeBytes(bytes); true }.getOrDefault(false)
    }
}
