package com.daftar.app.study

import com.daftar.app.R
import com.daftar.app.ai.FileContext
import com.daftar.app.ai.Gemini
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * One row of the New-quiz source list: a library file (optionally a page range, 0-based inclusive) or a folder
 * (every AI-readable file inside it, recursively).
 */
data class QuizSource(val file: File, val folder: Boolean = false, val whole: Boolean = true, val from: Int = 0, val to: Int = 0)

/** A source as saved in the quiz JSON. [pages] = 1-based range label ("" = whole file); [files] = names read from a folder. */
@Serializable
data class QuizSourceInfo(
    val name: String,
    val path: String = "",
    val pages: String = "",
    val folder: Boolean = false,
    val files: List<String> = emptyList(),
)

/** Builds the Gemini material from several files within one shared budget. */
internal object QuizContext {
    /** Files read at most (a folder with more is cut, alphabetically). */
    const val MAX_FILES = 40
    /** Shared caps (same as one `FileContext.parts` call), minus room for headers. */
    private const val CHAR_BUDGET = FileContext.MAX_CHARS - 4_000
    private const val IMAGE_BUDGET = FileContext.MAX_IMAGES
    /** A page with less text than this probably needs its image (handwriting, scans, diagrams). */
    private const val LOW_TEXT = 300

    /** AI-readable files inside [dir], recursive, skipping hidden / internal entries, sorted by path. Call off the main thread. */
    fun expandFolder(dir: File): List<File> =
        dir.walkTopDown()
            .onEnter { it == dir || !it.name.startsWith(".") }
            .filter { it.isFile && quizAccepts(it) }
            .sortedBy { it.absolutePath.lowercase() }
            .toList()

    class Built(val parts: List<Gemini.Part>, val infos: List<QuizSourceInfo>, val fileCount: Int)

    private class Item(val file: File, val label: String, val from: Int, val to: Int, val count: Int) {
        var texts: List<Pair<Int, String>> = emptyList()
        var charShare = 0
        var imageShare = 0
        val images = HashMap<Int, Gemini.Part.Blob>()
        val visual get() = FileContext.isVisual(file)
    }

    /**
     * Reads every source. [progress] (index 1-based, total, file name) is called on the main thread before each file.
     * Throws [QuizException] when nothing could be read.
     */
    suspend fun build(sources: List<QuizSource>, progress: suspend (Int, Int, String) -> Unit): Built {
        // ---- resolve files ----
        val infos = ArrayList<QuizSourceInfo>()
        val wanted = ArrayList<Triple<File, String, IntRange?>>()   // file, label, range (null = whole)
        val seen = HashSet<String>()
        for (s in sources) {
            if (s.folder) {
                val files = withContext(Dispatchers.IO) { expandFolder(s.file) }
                val names = ArrayList<String>()
                files.forEach { f ->
                    if (seen.add(f.absolutePath)) {
                        val rel = f.absolutePath.removePrefix(s.file.parentFile?.absolutePath ?: "").trimStart('/')
                        wanted += Triple(f, rel, null); names += rel
                    }
                }
                infos += QuizSourceInfo(s.file.name, s.file.absolutePath, folder = true, files = names)
            } else if (seen.add(s.file.absolutePath)) {
                wanted += Triple(s.file, s.file.name, if (s.whole) null else s.from..s.to)
                infos += QuizSourceInfo(s.file.name, s.file.absolutePath)
            }
        }
        val todo = wanted.take(MAX_FILES)

        // ---- pass 1: page counts + text ----
        val items = ArrayList<Item>()
        todo.forEachIndexed { i, (f, label, range) ->
            coroutineContext.ensureActive()
            withContext(Dispatchers.Main) { progress(i + 1, todo.size, f.name) }
            val n = FileContext.pageCount(f)
            if (n <= 0) return@forEachIndexed
            val a = range?.first?.coerceIn(0, n - 1) ?: 0
            val b = range?.last?.coerceIn(a, n - 1) ?: (n - 1)
            val item = Item(f, label, a, b, n)
            item.texts = runCatching { FileContext.pages(f, a, b, images = false).map { it.index to it.text.trim() } }.getOrDefault(emptyList())
            if (item.texts.isEmpty() && !item.visual) return@forEachIndexed
            items += item
            // the page-range label saved with the quiz
            if (range != null && n > 1) infos.indexOfFirst { it.path == f.absolutePath && !it.folder }.takeIf { it >= 0 }?.let { k ->
                infos[k] = infos[k].copy(pages = if (a == b) "${a + 1}" else "${a + 1}–${b + 1}")
            }
        }
        if (items.isEmpty()) throw QuizException(R.string.quiz_err_read)

        // ---- budgets: water-filling (small files get all they need, the rest share what is left) ----
        val textDemand = items.map { it.texts.sumOf { (_, t) -> t.length + 12 } }
        waterFill(textDemand, CHAR_BUDGET).forEachIndexed { k, v -> items[k].charShare = v }
        val many = items.size > 3
        val imagePages = items.map { it ->
            if (!it.visual) emptyList()
            else {
                val all = (it.from..it.to).toList()
                val low = all.filter { p -> (it.texts.firstOrNull { (i, _) -> i == p }?.second?.length ?: 0) < LOW_TEXT }
                // with many files images are only for pages whose text is not enough; otherwise low-text pages first
                if (many) low else low + (all - low.toSet())
            }
        }
        waterFill(imagePages.map { it.size }, IMAGE_BUDGET).forEachIndexed { k, v -> items[k].imageShare = v }

        // ---- pass 2: page images, one bitmap at a time ----
        val withImages = items.indices.filter { items[it].imageShare > 0 }
        withImages.forEachIndexed { j, k ->
            val it = items[k]
            withContext(Dispatchers.Main) { progress(j + 1, withImages.size, it.file.name) }
            for (p in imagePages[k].take(it.imageShare)) {
                coroutineContext.ensureActive()
                val pages = runCatching { FileContext.pages(it.file, p, p, images = true) }.getOrDefault(emptyList())
                pages.forEach { pc ->
                    try { pc.image?.let { b -> it.images[pc.index] = withContext(Dispatchers.Default) { Gemini.Part.image(b, FileContext.MAX_SIDE) } } }
                    finally { pc.image?.recycle() }
                }
            }
        }

        // ---- assemble ----
        val parts = ArrayList<Gemini.Part>()
        if (items.size > 1 || wanted.size > 1) parts += Gemini.Part.text(
            "The material comes from ${items.size} file(s). Each file starts with a \"=== File: name ===\" line. " +
                "Fill each question's \"source\" field with the file name it is based on.")
        items.forEachIndexed { k, it ->
            val range = if (it.count > 1) " (pages ${it.from + 1}–${it.to + 1} of ${it.count})" else ""
            parts += Gemini.Part.text("=== File ${k + 1} of ${items.size}: \"${it.label}\"$range ===")
            var left = it.charShare
            var cut = false
            val byIndex = it.texts.toMap()
            for (p in it.from..it.to) {
                val t = byIndex[p].orEmpty()
                val img = it.images[p]
                if (t.isEmpty() && img == null) continue
                val label = if (it.count > 1) "[Page ${p + 1}]" else ""
                if (t.isNotEmpty() && left > 0) {
                    val body = if (t.length > left) { cut = true; t.take(left) + " …" } else t
                    left -= body.length + label.length
                    parts += Gemini.Part.text(if (label.isEmpty()) body else "$label\n$body")
                } else if (t.isNotEmpty()) cut = true
                else if (label.isNotEmpty()) parts += Gemini.Part.text(label)
                if (img != null) parts += img
            }
            if (cut) parts += Gemini.Part.text("[The rest of this file was left out to keep the request small.]")
        }
        if (wanted.size > todo.size) parts += Gemini.Part.text("[${wanted.size - todo.size} more file(s) were not included.]")
        return Built(parts, infos, items.size)
    }

    /** Splits [budget] over [demands] fairly: ascending demands take what they need, the rest share equally. */
    private fun waterFill(demands: List<Int>, budget: Int): IntArray {
        val out = IntArray(demands.size)
        var left = budget
        val order = demands.indices.sortedBy { demands[it] }
        order.forEachIndexed { pos, i ->
            val share = left / (order.size - pos)
            val give = minOf(demands[i], share)
            out[i] = give
            left -= give
        }
        return out
    }
}
