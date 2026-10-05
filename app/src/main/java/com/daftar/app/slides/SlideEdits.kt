package com.daftar.app.slides

import com.daftar.app.data.Storage
import com.daftar.app.data.json
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Applies a slide edit to a deck on disk (blocking, call on IO), like the PDF page editor:
 * new package to a temp file → structural check + re-parse with [PptxParser] → atomic replace; then the ink sidecar
 * (one page per slide) and the "My notes" sidecar (slide index → text) are remapped to the new slide order.
 * On any failure before the swap the deck and both sidecars are untouched.
 */
object SlideEdits {

    /** [myNotes] = the notes as they are on screen (the caller freezes the live autosave first). Returns the slide to show. */
    fun apply(file: File, op: SlideOp, myNotes: Map<Int, String>): Int {
        val dir = file.parentFile ?: throw IllegalStateException("no parent")
        val tmp = File(dir, ".${file.name}.slides.tmp")
        val inkFile = Storage.sidecar(file, "ink.json")
        val notesFile = Storage.sidecar(file, "mynotes.json")
        val inkTmp = File(dir, ".${file.name}.ink.slides.tmp")
        val notesTmp = File(dir, ".${file.name}.mynotes.slides.tmp")
        try {
            val result = PptxEdit.rewrite(file, op, tmp)
            validate(file, tmp, result.plan.size)

            val size = PptxParser.open(tmp).use { it.widthPt to it.heightPt }
            val ink = InkDoc.load(inkFile)
            val newInk = ink?.let { remapInk(it, result.plan, size) }
            if (newInk != null) {
                if (newInk.pages.all { it.isEmpty() } && newInk.recordings.isEmpty()) inkTmp.delete()
                else newInk.save(inkTmp)
            }
            val newNotes = remapNotes(myNotes, result.plan)
            if (newNotes.isNotEmpty()) notesTmp.writeText(json.encodeToString(newNotes))

            swapIn(tmp, file)
            if (newInk != null) { if (inkTmp.exists()) swapIn(inkTmp, inkFile) else inkFile.delete() }
            if (newNotes.isNotEmpty()) swapIn(notesTmp, notesFile) else notesFile.delete()
            return result.focus
        } finally {
            tmp.delete(); inkTmp.delete(); notesTmp.delete()
        }
    }

    /** Problems that the edit introduced (pre-existing oddities of the original deck are tolerated) + a full re-parse. */
    private fun validate(orig: File, tmp: File, expected: Int) {
        val before = PptxEdit.check(orig).toSet()
        val after = PptxEdit.check(tmp).filter { it !in before }
        if (after.isNotEmpty()) throw PptxEditException("invalid package: ${after.take(3)}")
        PptxParser.open(tmp).use { deck ->
            if (deck.slides.size != expected) throw PptxEditException("slide count ${deck.slides.size} != $expected")
        }
    }

    fun remapInk(ink: InkDoc, plan: List<Slot>, size: Pair<Float, Float>): InkDoc {
        val (w, h) = size
        val pages = plan.map { s ->
            val from = if (s.src >= 0) s.src else s.inkFrom
            (ink.pages.getOrNull(from) ?: InkPage(w = w, h = h, paper = "none")).copy(w = w, h = h, paper = "none")
        }
        return InkDoc(pages = pages, paperColor = ink.paperColor, recordings = ink.recordings)
    }

    fun remapNotes(notes: Map<Int, String>, plan: List<Slot>): Map<Int, String> {
        val out = sortedMapOf<Int, String>()
        plan.forEachIndexed { i, s -> if (s.src >= 0) notes[s.src]?.takeIf { it.isNotBlank() }?.let { out[i] = it } }
        return out
    }

    /** Temp files a crash may have left (older than 10 minutes, so a running edit is never touched). */
    fun cleanStaleTemps(file: File) {
        val dir = file.parentFile ?: return
        val limit = System.currentTimeMillis() - 10 * 60_000L
        for (n in listOf(".${file.name}.slides.tmp", ".${file.name}.ink.slides.tmp", ".${file.name}.mynotes.slides.tmp")) {
            val f = File(dir, n)
            if (f.exists() && f.lastModified() < limit) f.delete()
        }
    }

    private fun swapIn(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
