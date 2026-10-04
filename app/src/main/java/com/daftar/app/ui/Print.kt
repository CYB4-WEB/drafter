package com.daftar.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.daftar.app.R
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.exportNoteToPdf
import com.daftar.app.pdf.PdfTools
import com.daftar.app.slides.SlidesExport
import com.daftar.app.word.DocxExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Everything except audio/other files can be printed (via a PDF made by the converter engines). */
fun canPrint(f: File): Boolean = when (Storage.kindOf(f)) {
    Kind.PDF, Kind.NOTE, Kind.PPTX, Kind.DOCX, Kind.TEXT, Kind.IMAGE -> true
    else -> false
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Opens the system print dialog for [f]. Non-PDF files are first turned into a PDF in the cache
 * (notes with their paper, PDFs with the user's ink burned in). Call from a coroutine on the main thread.
 */
suspend fun printFile(ctx: Context, f: File) {
    val act = ctx.activity() ?: return
    toast(ctx, ctx.getString(R.string.print_preparing))
    val pdf = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(Storage.cacheDir(), "print-" + Storage.sanitize(f.nameWithoutExtension).ifBlank { "document" } + ".pdf")
            out.delete()
            when (Storage.kindOf(f)) {
                Kind.PDF -> {
                    val ink = InkDoc.load(Storage.sidecar(f, "ink.json"))
                    if (PdfTools.hasInk(ink)) PdfTools.exportAnnotated(f, ink!!, out) else f
                }
                Kind.NOTE -> { exportNoteToPdf(InkDoc.load(f) ?: error("unreadable note"), out); out }
                Kind.PPTX -> if (SlidesExport.toPdf(f, out)) out else null
                Kind.DOCX, Kind.TEXT -> if (DocxExport.toPdf(f, out)) out else null
                Kind.IMAGE -> PdfTools.imagesToPdf(ctx, listOf(uriFor(ctx, f)), out)
                else -> null
            }
        }.getOrNull()
    }
    if (pdf == null || !pdf.exists()) { toast(ctx, ctx.getString(R.string.error_generic)); return }
    PdfTools.print(act, pdf, f.nameWithoutExtension)
}
