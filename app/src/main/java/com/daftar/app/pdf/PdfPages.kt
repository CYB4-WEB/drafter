package com.daftar.app.pdf

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.daftar.app.data.Storage
import com.daftar.app.data.json
import com.daftar.app.ink.ImageItem
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import com.daftar.app.ink.LinkItem
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One page of a rebuilt document: page [src] (0-based) of the current file turned [rotate] degrees clockwise,
 * or — when [src] is -1 — a blank page of [w]×[h] points. [key] is a stable identity for lists.
 */
data class PageSpec(val src: Int, val rotate: Int = 0, val w: Float = 0f, val h: Float = 0f, val key: Long = PdfPages.nextKey()) {
    val isBlank get() = src < 0
}

/** One bookmark of the PDF outline, flattened in document order. [page] is -1 when it points nowhere in this file. */
data class OutlineEntry(val id: Int, val parent: Int, val depth: Int, val title: String, val page: Int, val hasChildren: Boolean, val open: Boolean)

/**
 * Page management: every operation (insert blank, delete, rotate, move, reorder) is a plan of [PageSpec]s applied by [rebuild],
 * which also remaps the ink sidecar so strokes stay on their page (and turn with it). All functions block — call on IO.
 */
object PdfPages {
    private const val TAG = "PdfPages"
    private val keys = AtomicLong(1)
    private const val MAX_OUTLINE = 5000

    fun nextKey() = keys.getAndIncrement()

    private fun mem() = MemoryUsageSetting.setupMixed(32L shl 20)

    fun norm(deg: Int) = ((deg % 360) + 360) % 360

    fun identity(n: Int) = List(n) { PageSpec(it) }

    fun isIdentity(plan: List<PageSpec>, n: Int) =
        plan.size == n && plan.withIndex().all { (i, s) -> s.src == i && norm(s.rotate) == 0 }

    /** Plan with a blank page of [w]×[h] after page [after]. */
    fun insertBlank(n: Int, after: Int, w: Float, h: Float): List<PageSpec> =
        identity(n).toMutableList().also { it.add((after + 1).coerceIn(0, n), PageSpec(-1, 0, w, h)) }

    fun delete(n: Int, pages: Set<Int>): List<PageSpec> = identity(n).filter { it.src !in pages }

    fun rotate(n: Int, page: Int, deg: Int): List<PageSpec> = identity(n).map { if (it.src == page) it.copy(rotate = deg) else it }

    /** Plan moving page [page] by [delta] positions (−1 = earlier, +1 = later). */
    fun move(n: Int, page: Int, delta: Int): List<PageSpec> {
        val l = identity(n).toMutableList()
        val to = (page + delta).coerceIn(0, n - 1)
        if (to != page) l.add(to, l.removeAt(page))
        return l
    }

    // ------------------------------------------------------------------ rebuild

    /**
     * Rewrites [file] so its pages follow [plan] and rewrites the ink sidecar [inkFile] from [ink] to match.
     * The new PDF is saved next to the original, checked with PdfRenderer, then swapped in — on any failure the
     * original file and ink stay untouched.
     */
    fun rebuild(file: File, plan: List<PageSpec>, ink: InkDoc, inkFile: File) {
        require(plan.isNotEmpty())
        val dir = file.parentFile ?: throw IllegalStateException("no parent")
        val tmp = File(dir, ".${file.name}.rebuild.tmp")
        val inkTmp = File(dir, ".${file.name}.ink.rebuild.tmp")
        try {
            val sizes: List<Pair<Float, Float>>
            PDDocument.load(file, mem()).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                val old = (0 until doc.numberOfPages).map { doc.getPage(it) }
                sizes = old.map { displayedSize(it) }
                val used = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                val pages = plan.map { spec ->
                    if (spec.isBlank) blankPage(spec.w, spec.h)
                    else {
                        val src = old[spec.src]
                        materialize(src)
                        // The same source twice (not produced by the UI, but legal): give the copy its own dictionary.
                        val p = if (used.add(src.cosObject)) src else PDPage(COSDictionary(src.cosObject))
                        p.rotation = norm(p.rotation + spec.rotate)
                        p
                    }
                }
                val root = doc.pages.cosObject
                val kids = COSArray()
                for (p in pages) { p.cosObject.setItem(COSName.PARENT, root); kids.add(p.cosObject) }
                root.setItem(COSName.KIDS, kids)
                root.setInt(COSName.COUNT, pages.size)
                if (used.size < old.size) dropDeadOutlineLinks(doc, used)
                doc.save(tmp)
            }
            validate(tmp, plan.size)
            writeInk(remapInk(ink, plan, sizes), inkTmp)
            swapIn(tmp, file)
            if (inkTmp.exists()) swapIn(inkTmp, inkFile) else inkFile.delete()
        } finally {
            tmp.delete(); inkTmp.delete()
        }
    }

    /** Displayed size (points) of a page, rotation applied — the space the ink layer uses. */
    private fun displayedSize(p: PDPage): Pair<Float, Float> {
        val c = p.cropBox
        val r = norm(p.rotation)
        return if (r == 90 || r == 270) c.height to c.width else c.width to c.height
    }

    /** Copies attributes a page inherits from intermediate page-tree nodes onto the page, so it can be re-parented safely. */
    private fun materialize(p: PDPage) {
        val d = p.cosObject
        for (key in listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE)) {
            if (!d.containsKey(key)) PDPageTree.getInheritableAttribute(d, key)?.let { d.setItem(key, it) }
        }
    }

    private fun blankPage(w: Float, h: Float): PDPage {
        val box = PDRectangle(w.coerceIn(36f, 14400f), h.coerceIn(36f, 14400f))
        return PDPage(box).apply {
            cropBox = box
            rotation = 0
            resources = PDResources()
        }
    }

    /** Clears bookmarks whose target page was deleted (instead of leaving links to orphaned pages). */
    private fun dropDeadOutlineLinks(doc: PDDocument, kept: Set<COSDictionary>) {
        val root = doc.documentCatalog.documentOutline ?: return
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun walk(node: PDOutlineNode, depth: Int) {
            var next: PDOutlineItem? = node.firstChild
            while (next != null && seen.size < MAX_OUTLINE && seen.add(next.cosObject)) {
                val item: PDOutlineItem = next
                val target = runCatching { item.findDestinationPage(doc) }.getOrNull()
                if (target != null && target.cosObject !in kept) {
                    item.destination = null
                    item.action = null
                }
                if (depth < 32) walk(item, depth + 1)
                next = item.nextSibling
            }
        }
        runCatching { walk(root, 0) }.onFailure { Log.w(TAG, "outline cleanup", it) }
    }

    private fun validate(f: File, expected: Int) {
        val s = PdfSource(f)
        try {
            check(s.pageCount == expected) { "page count ${s.pageCount} != $expected" }
        } finally {
            s.close()
        }
    }

    /** Replaces [target] with [tmp] (atomic rename on the same file system; copy as a fallback). */
    private fun swapIn(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Serialises [ink] to [out]; writes nothing when the layer is empty (the caller then removes the sidecar). */
    private fun writeInk(ink: InkDoc, out: File) {
        if (ink.pages.all { it.isEmpty() } && ink.recordings.isEmpty()) { out.delete(); return }
        out.writeText(json.encodeToString(ink))
    }

    // ------------------------------------------------------------------ ink remap

    /** Ink pages reordered / rotated / inserted like the PDF pages in [plan]. [sizes] = displayed source page sizes. */
    fun remapInk(ink: InkDoc, plan: List<PageSpec>, sizes: List<Pair<Float, Float>>): InkDoc {
        val pages = plan.map { s ->
            if (s.isBlank) InkPage(w = s.w, h = s.h, paper = "none")
            else {
                val (sw, sh) = sizes.getOrElse(s.src) { 595f to 842f }
                val base = ink.pages.getOrNull(s.src) ?: InkPage(w = sw, h = sh, paper = "none")
                rotateInkPage(base, norm(s.rotate))
            }
        }
        return InkDoc(pages = pages, paperColor = ink.paperColor, recordings = ink.recordings)
    }

    /**
     * Turns everything on an ink page [deg] degrees clockwise with the page. Strokes and pictures rotate exactly;
     * text boxes and links stay upright (they have no rotation) and keep their centre on the same spot of the page.
     */
    fun rotateInkPage(p: InkPage, deg: Int): InkPage {
        val d = norm(deg)
        if (d == 0) return p
        val w = p.w; val h = p.h
        val f: (Float, Float) -> Pair<Float, Float> = when (d) {
            90 -> { x, y -> (h - y) to x }
            180 -> { x, y -> (w - x) to (h - y) }
            else -> { x, y -> y to (w - x) }
        }
        val nw = if (d == 180) w else h
        val nh = if (d == 180) h else w
        val texts = p.texts.map { t ->
            val th = runCatching { InkRender.layout(t).height.toFloat() }.getOrDefault(t.size * 1.4f)
            val (cx, cy) = f(t.x + t.w / 2f, t.y + th / 2f)
            t.copy(x = cx - t.w / 2f, y = cy - th / 2f)
        }
        val links = p.links.map { l ->
            val (cx, cy) = f(l.x + l.w / 2f, l.y + LinkItem.LINK_H / 2f)
            l.copy(x = cx - l.w / 2f, y = cy - LinkItem.LINK_H / 2f)
        }
        return p.copy(
            w = nw, h = nh,
            strokes = p.strokes.map { it.mapped(f) },
            texts = texts,
            images = p.images.map { rotateImage(it, d, f) },
            links = links,
        )
    }

    private fun rotateImage(im: ImageItem, deg: Int, f: (Float, Float) -> Pair<Float, Float>): ImageItem {
        val corners = listOf(f(im.x, im.y), f(im.x + im.w, im.y), f(im.x, im.y + im.h), f(im.x + im.w, im.y + im.h))
        val r = RectF(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
        val src = im.bitmap()
        val data = if (src == null) im.data else {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
            try { ImageItem.encode(rotated) } finally { if (rotated !== src) rotated.recycle() }
        }
        return ImageItem(im.id, r.left, r.top, r.width(), r.height(), data)
    }

    // ------------------------------------------------------------------ merge

    /**
     * Joins [sources] (in order) into a new PDF [out]. Each source's ink ([inks], same order; null = none) is carried over as the
     * new file's ink sidecar, so annotations stay editable. [onProgress] gets (done, total) per appended file.
     */
    fun merge(
        sources: List<File>,
        inks: List<InkDoc?>,
        out: File,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
    ): File {
        require(sources.size >= 2)
        val dir = out.parentFile ?: throw IllegalStateException("no parent")
        dir.mkdirs()
        val tmp = File(dir, ".${out.name}.merge.tmp")
        val opened = ArrayList<PDDocument>()
        val inkPages = ArrayList<InkPage>()
        try {
            val dst = PDDocument.load(sources[0], mem()).also { opened.add(it) }
            if (dst.isEncrypted) dst.isAllSecurityToBeRemoved = true
            addInk(inkPages, inks.getOrNull(0), dst.numberOfPages)
            val merger = PDFMergerUtility()
            onProgress(0, sources.size - 1)
            for (k in 1 until sources.size) {
                if (isCancelled()) throw CancellationException("merge cancelled")
                val src = PDDocument.load(sources[k], mem()).also { opened.add(it) }
                val count = src.numberOfPages
                merger.appendDocument(dst, src)
                addInk(inkPages, inks.getOrNull(k), count)
                onProgress(k, sources.size - 1)
            }
            if (isCancelled()) throw CancellationException("merge cancelled")
            dst.save(tmp)
        } catch (t: Throwable) {
            tmp.delete(); throw t
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
        try {
            validate(tmp, inkPages.size)
            if (out.exists()) out.delete()
            swapIn(tmp, out)
        } finally {
            tmp.delete()
        }
        val inkDoc = InkDoc(pages = inkPages)
        if (inkPages.any { !it.isEmpty() }) runCatching { inkDoc.save(Storage.sidecar(out, "ink.json")) }
        return out
    }

    private fun addInk(into: MutableList<InkPage>, ink: InkDoc?, count: Int) {
        for (i in 0 until count) into.add(ink?.pages?.getOrNull(i) ?: InkPage(paper = "none"))
    }

    // ------------------------------------------------------------------ outline

    /** The PDF bookmarks flattened in document order (depth-first), with target pages resolved. Empty when there are none. */
    fun loadOutline(file: File): List<OutlineEntry> =
        PDDocument.load(file, mem()).use { doc ->
            val root = doc.documentCatalog.documentOutline ?: return@use emptyList()
            val pageIndex = IdentityHashMap<COSDictionary, Int>()
            doc.pages.forEachIndexed { i, p -> pageIndex[p.cosObject] = i }
            val out = ArrayList<OutlineEntry>()
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            val ws = Regex("\\s+")
            fun walk(node: PDOutlineNode, depth: Int, parent: Int) {
                var next: PDOutlineItem? = node.firstChild
                while (next != null && out.size < MAX_OUTLINE && seen.add(next.cosObject)) {
                    val item: PDOutlineItem = next
                    val page = runCatching { item.findDestinationPage(doc)?.let { pageIndex[it.cosObject] } }.getOrNull() ?: -1
                    val title = runCatching { item.title }.getOrNull()?.replace(ws, " ")?.trim().orEmpty()
                    val kids = runCatching { item.hasChildren() }.getOrDefault(false)
                    val id = out.size
                    out.add(OutlineEntry(id, parent, depth, title, page, kids, runCatching { item.isNodeOpen }.getOrDefault(false)))
                    if (kids && depth < 32) walk(item, depth + 1, id)
                    next = item.nextSibling
                }
            }
            walk(root, 0, -1)
            out
        }
}
