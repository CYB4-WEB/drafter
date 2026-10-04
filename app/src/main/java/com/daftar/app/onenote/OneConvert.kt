package com.daftar.app.onenote

import android.content.Context
import java.io.File

/**
 * Entry points for the converter (convert-agent): OneNote section / package → PDF, plain text or a Daftar note.
 * Blocking; call off the main thread. Throws [OneException] when the source can't be read.
 */
object OneConvert {
    private fun pages(ctx: Context, src: File, cancelled: () -> Boolean): List<PageRef> {
        val book = OneLoader.load(src, File(ctx.cacheDir, "onenote"), cancelled)
        val all = book.sections.flatMap { s -> s.pages.map { PageRef(s, it) } }
        if (all.isEmpty()) throw OneException(book.sections.firstNotNullOfOrNull { it.error } ?: OneError.EMPTY_PACKAGE)
        return all
    }

    /** All pages → one PDF ([progress] 0-100). */
    fun toPdf(ctx: Context, src: File, out: File, progress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        OneExport.pdf(pages(ctx, src, cancelled), out, ctx.getString(com.daftar.app.R.string.one_attachment), progress, cancelled)
    }

    /** All pages → UTF-8 text. */
    fun toText(ctx: Context, src: File, out: File, cancelled: () -> Boolean = { false }) {
        out.writeText(OneExport.text(pages(ctx, src, cancelled)))
    }

    /** All pages → one Daftar note (one note page per OneNote page). */
    fun toNote(ctx: Context, src: File, out: File, cancelled: () -> Boolean = { false }) {
        OneExport.note(pages(ctx, src, cancelled), out, cancelled)
    }
}
