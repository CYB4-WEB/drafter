package com.daftar.app.slides

import java.io.File

/** STUB — owned by slides-agent. Engines used by the converter. Run off the main thread. */
object SlidesExport {
    /** Render every slide (vector where possible) into a PDF, one page per slide, slide size kept. */
    fun toPdf(src: File, out: File): Boolean = false
    /** Render slides to PNG/JPEG files in [outDir]; returns the files written. */
    fun toImages(src: File, outDir: File, png: Boolean = true, scale: Float = 2f): List<File> = emptyList()
}
