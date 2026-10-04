package com.daftar.app.word

import java.io.File

/** STUB — owned by docs-agent. Engines used by the converter. Run off the main thread. */
object DocxExport {
    /** Paginated A4 PDF with real (selectable) text, headings, lists, tables, images, RTL. */
    fun toPdf(src: File, out: File): Boolean = false
    /** Write a valid .docx from paragraphs (used for PDF→Word, note→Word). Each string = one paragraph; "" = blank line. */
    fun writeDocx(paragraphs: List<String>, out: File, title: String? = null): Boolean = false
}
