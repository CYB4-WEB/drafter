package com.daftar.app.slides

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipFile

const val EMU_PER_PT = 12700f

/** One relationship of a package part; [target] is the resolved zip entry name (or the raw URL if external). */
class Rel(val id: String, val type: String, val target: String, val external: Boolean)

/** A parsed XML part plus its relationships. */
class Part(val path: String, val root: XNode, val rels: Map<String, Rel>) {
    fun rel(id: String?): Rel? = if (id == null) null else rels[id]
    fun relOfType(suffix: String): Rel? = rels.values.firstOrNull { !it.external && it.type.endsWith(suffix) }
    fun relsOfType(suffix: String): List<Rel> = rels.values.filter { !it.external && it.type.endsWith(suffix) }
}

class Theme(
    val part: Part?,
    val colors: Map<String, Int>,
    val majorLatin: String, val majorCs: String,
    val minorLatin: String, val minorCs: String,
    val fillStyles: List<XNode>, val lnStyles: List<XNode>, val bgFillStyles: List<XNode>,
)

/** Placeholder shapes of a layout or master, indexed for inheritance lookups. */
class PhIndex(spTree: XNode?) {
    val byIdx = HashMap<Int, XNode>()
    val byType = HashMap<String, XNode>()

    init {
        spTree?.children?.forEach { sh ->
            val ph = phOf(sh) ?: return@forEach
            val type = ph.attr("type") ?: "body"
            ph.int("idx")?.let { byIdx.putIfAbsent(it, sh) }
            byType.putIfAbsent(type, sh)
        }
    }

    /** Layout lookup for a slide placeholder: idx first, then exact type, then type family. */
    fun matchForSlide(ph: XNode): XNode? {
        val type = ph.attr("type") ?: "body"
        val idx = ph.int("idx")
        if (idx != null) byIdx[idx]?.let { cand ->
            // An idx match whose type family differs (e.g. title vs body) is still what PowerPoint uses.
            return cand
        }
        byType[type]?.let { return it }
        val fam = phFamily(type)
        return byType.entries.firstOrNull { phFamily(it.key) == fam }?.value
    }

    /** Master lookup: by type family (title / body / dt / ftr / sldNum ...), then idx. */
    fun matchForLayout(ph: XNode): XNode? {
        val type = ph.attr("type") ?: "body"
        val fam = phFamily(type)
        byType[fam]?.let { return it }
        byType.entries.firstOrNull { phFamily(it.key) == fam }?.let { return it.value }
        return ph.int("idx")?.let { byIdx[it] }
    }
}

fun phFamily(type: String): String = when (type) {
    "title", "ctrTitle" -> "title"
    "body", "subTitle", "obj", "tbl", "chart", "pic", "media", "clipArt", "dgm" -> "body"
    else -> type
}

/** `p:ph` of any shape kind (sp, pic, graphicFrame, grpSp, cxnSp). */
fun phOf(shape: XNode): XNode? {
    for (c in shape.children) if (c.name.startsWith("nv") && c.name.endsWith("Pr")) return c.child("nvPr")?.child("ph")
    return null
}

class MasterPart(
    val part: Part, val theme: Theme, val clrMap: Map<String, String>, val ph: PhIndex,
    val titleStyle: XNode?, val bodyStyle: XNode?, val otherStyle: XNode?,
)

class LayoutPart(val part: Part, val master: MasterPart, val ph: PhIndex) {
    val showMasterSp: Boolean = part.root.bool("showMasterSp") ?: true
}

class Comment(val author: String, val time: Long?, val text: String, val resolved: Boolean, val replies: List<Comment>)

class SlidePart(
    val index: Int, val part: Part, val layout: LayoutPart,
    val notes: String, val comments: List<Comment>, val title: String,
) {
    val showMasterSp: Boolean = part.root.bool("showMasterSp") ?: true
    val hidden: Boolean = part.root.bool("show") == false
}

class TableStyleDef(val parts: Map<String, XNode>)

/** A whole opened deck. Holds the zip open for lazy media access; close when done. */
class Pptx(
    val zip: ZipFile,
    val widthPt: Float, val heightPt: Float,
    val slides: List<SlidePart>,
    val defaultTextStyle: XNode?,
    val tableStyles: Map<String, XNode>,
    val firstSlideNum: Int,
) : Closeable {
    override fun close() { runCatching { zip.close() } }
}

class PptxException(val kind: Kind, msg: String) : IOException(msg) {
    enum class Kind { LEGACY, CORRUPT, EMPTY }
}

object PptxParser {

    fun open(file: File): Pptx {
        // Legacy .ppt and password-protected .pptx are OLE2 compound files (D0 CF 11 E0).
        val head = ByteArray(4)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n == 4 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte())
            throw PptxException(PptxException.Kind.LEGACY, "OLE2 file")
        val zip = try { ZipFile(file) } catch (e: Exception) { throw PptxException(PptxException.Kind.CORRUPT, e.message ?: "zip") }
        try {
            return Loader(zip).load()
        } catch (e: PptxException) {
            zip.close(); throw e
        } catch (e: Throwable) {
            zip.close(); throw PptxException(PptxException.Kind.CORRUPT, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Load any package part with its relationships (used lazily by the renderer, e.g. SmartArt drawings). */
    fun loadPart(zip: ZipFile, path: String): Part? = runCatching { Loader(zip).part(path) }.getOrNull()

    private class Loader(val zip: ZipFile) {
        private val parts = HashMap<String, Part?>()
        private val masters = HashMap<String, MasterPart>()
        private val layouts = HashMap<String, LayoutPart>()
        private val themes = HashMap<String, Theme>()

        fun xml(path: String): XNode? {
            val e = zip.getEntry(path) ?: return null
            return zip.getInputStream(e).use { XNode.parse(it) }
        }

        fun part(path: String): Part? = parts.getOrPut(path) {
            val root = runCatching { xml(path) }.getOrNull() ?: return@getOrPut null
            Part(path, root, rels(path))
        }

        private fun rels(path: String): Map<String, Rel> {
            val dir = path.substringBeforeLast('/', "")
            val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
            val root = runCatching { xml(relsPath) }.getOrNull() ?: return emptyMap()
            val out = HashMap<String, Rel>()
            for (r in root.children("Relationship")) {
                val id = r.attr("Id") ?: continue
                val target = r.attr("Target") ?: continue
                val ext = r.attr("TargetMode") == "External"
                out[id] = Rel(id, r.attr("Type") ?: "", if (ext) target else resolve(dir, target), ext)
            }
            return out
        }

        fun load(): Pptx {
            val ct = zip.getEntry("[Content_Types].xml")
            val presPath = findPresentationPath()
            if (ct == null && presPath == null) throw PptxException(PptxException.Kind.CORRUPT, "not an OOXML package")
            val pres = part(presPath ?: "ppt/presentation.xml") ?: throw PptxException(PptxException.Kind.CORRUPT, "no presentation.xml")
            val sz = pres.root.child("sldSz")
            val w = (sz?.long("cx") ?: 9144000L) / EMU_PER_PT
            val h = (sz?.long("cy") ?: 6858000L) / EMU_PER_PT

            val authorsLegacy = HashMap<String, String>()
            val authorsModern = HashMap<String, String>()
            (pres.relOfType("/commentAuthors")?.target ?: "ppt/commentAuthors.xml").let { p ->
                runCatching { xml(p) }.getOrNull()?.children("cmAuthor")?.forEach { a ->
                    a.attr("id")?.let { id -> authorsLegacy[id] = a.attr("name") ?: "" }
                }
            }
            (pres.relOfType("/authors")?.target ?: "ppt/authors.xml").let { p ->
                runCatching { xml(p) }.getOrNull()?.children("author")?.forEach { a ->
                    a.attr("id")?.let { id -> authorsModern[id] = a.attr("name") ?: "" }
                }
            }

            val tableStyles = HashMap<String, XNode>()
            (pres.relOfType("/tableStyles")?.target ?: "ppt/tableStyles.xml").let { p ->
                runCatching { xml(p) }.getOrNull()?.children("tblStyle")?.forEach { s ->
                    s.attr("styleId")?.let { tableStyles[it] = s }
                }
            }

            val slides = ArrayList<SlidePart>()
            val ids = pres.root.child("sldIdLst")?.children("sldId") ?: emptyList()
            for (sid in ids) {
                val rel = pres.rel(sid.attr("r:id") ?: sid.attr("id")) ?: continue
                val sp = runCatching { part(rel.target) }.getOrNull() ?: continue
                val layout = sp.relOfType("/slideLayout")?.target?.let { layout(it) } ?: continue
                val notes = sp.relOfType("/notesSlide")?.target?.let { runCatching { notesText(it) }.getOrNull() } ?: ""
                val comments = sp.relsOfType("/comments").flatMap { r ->
                    runCatching { comments(r.target, authorsLegacy, authorsModern) }.getOrDefault(emptyList())
                }
                slides.add(SlidePart(slides.size, sp, layout, notes, comments, slideTitle(sp.root)))
            }
            if (slides.isEmpty()) throw PptxException(PptxException.Kind.EMPTY, "no slides")
            return Pptx(
                zip, w, h, slides, pres.root.child("defaultTextStyle"), tableStyles,
                pres.root.int("firstSlideNum") ?: 1,
            )
        }

        private fun findPresentationPath(): String? {
            val root = runCatching { xml("_rels/.rels") }.getOrNull() ?: return null
            return root.children("Relationship").firstOrNull { it.attr("Type")?.endsWith("/officeDocument") == true }
                ?.attr("Target")?.let { resolve("", it) }
        }

        fun layout(path: String): LayoutPart? = layouts[path] ?: run {
            val p = part(path) ?: return null
            val m = p.relOfType("/slideMaster")?.target?.let { master(it) } ?: return null
            LayoutPart(p, m, PhIndex(p.root.path("cSld", "spTree"))).also { layouts[path] = it }
        }

        fun master(path: String): MasterPart? = masters[path] ?: run {
            val p = part(path) ?: return null
            val theme = p.relOfType("/theme")?.target?.let { theme(it) } ?: defaultTheme()
            val map = HashMap<String, String>()
            p.root.child("clrMap")?.let { cm ->
                for (k in CLR_MAP_KEYS) cm.attr(k)?.let { map[k] = it }
            }
            val tx = p.root.child("txStyles")
            MasterPart(
                p, theme, map, PhIndex(p.root.path("cSld", "spTree")),
                tx?.child("titleStyle"), tx?.child("bodyStyle"), tx?.child("otherStyle"),
            ).also { masters[path] = it }
        }

        fun theme(path: String): Theme = themes[path] ?: run {
            val p = part(path) ?: return defaultTheme()
            val el = p.root.child("themeElements")
            val colors = HashMap<String, Int>()
            el?.child("clrScheme")?.children?.forEach { c ->
                val col = c.children.firstOrNull()?.let { simpleColor(it) }
                if (col != null) colors[c.name] = col
            }
            val fonts = el?.child("fontScheme")
            val major = fonts?.child("majorFont")
            val minor = fonts?.child("minorFont")
            val fmt = el?.child("fmtScheme")
            Theme(
                p, if (colors.isEmpty()) DEFAULT_SCHEME else colors,
                major?.child("latin")?.attr("typeface") ?: "Calibri Light", major?.child("cs")?.attr("typeface") ?: "",
                minor?.child("latin")?.attr("typeface") ?: "Calibri", minor?.child("cs")?.attr("typeface") ?: "",
                fmt?.child("fillStyleLst")?.children ?: emptyList(),
                fmt?.child("lnStyleLst")?.children ?: emptyList(),
                fmt?.child("bgFillStyleLst")?.children ?: emptyList(),
            ).also { themes[path] = it }
        }

        private fun defaultTheme() = Theme(null, DEFAULT_SCHEME, "Calibri Light", "", "Calibri", "", emptyList(), emptyList(), emptyList())

        /** Speaker notes = text of the notes page's body placeholder. */
        fun notesText(path: String): String {
            val root = xml(path) ?: return ""
            val tree = root.path("cSld", "spTree") ?: return ""
            val out = StringBuilder()
            for (sh in tree.findAll("sp")) {
                val ph = phOf(sh) ?: continue
                if ((ph.attr("type") ?: "body") != "body") continue
                val t = plainText(sh.child("txBody"))
                if (t.isNotBlank()) { if (out.isNotEmpty()) out.append("\n\n"); out.append(t.trim()) }
            }
            return out.toString()
        }

        fun comments(path: String, legacyAuthors: Map<String, String>, modernAuthors: Map<String, String>): List<Comment> {
            val root = xml(path) ?: return emptyList()
            val cms = root.children("cm")
            if (cms.isEmpty()) return emptyList()
            return if (cms.any { it.child("txBody") != null }) {
                cms.map { cm ->
                    Comment(
                        modernAuthors[cm.attr("authorId")] ?: "",
                        parseTime(cm.attr("created")),
                        plainText(cm.child("txBody")).trim(),
                        cm.attr("status") == "resolved",
                        cm.child("replyLst")?.children("reply")?.map { r ->
                            Comment(modernAuthors[r.attr("authorId")] ?: "", parseTime(r.attr("created")), plainText(r.child("txBody")).trim(), false, emptyList())
                        } ?: emptyList(),
                    )
                }
            } else {
                // Legacy comments: replies (PowerPoint 2013+) reference their parent via p15:threadingInfo/p15:parentCm.
                class Raw(val key: String, val parent: String?, val c: Comment)
                val raws = cms.map { cm ->
                    val parent = cm.find("parentCm")?.let { "${it.attr("authorId")}:${it.attr("idx")}" }
                    Raw(
                        "${cm.attr("authorId")}:${cm.attr("idx")}", parent,
                        Comment(legacyAuthors[cm.attr("authorId")] ?: "", parseTime(cm.attr("dt")), cm.child("text")?.text?.trim() ?: "", false, emptyList()),
                    )
                }
                val keys = raws.map { it.key }.toSet()
                raws.filter { it.parent == null || it.parent !in keys }.map { top ->
                    Comment(top.c.author, top.c.time, top.c.text, false, raws.filter { it.parent == top.key }.map { it.c })
                }
            }
        }

        fun slideTitle(root: XNode): String {
            val tree = root.path("cSld", "spTree") ?: return ""
            for (sh in tree.children) {
                val ph = phOf(sh) ?: continue
                if (phFamily(ph.attr("type") ?: "body") == "title") return plainText(sh.child("txBody")).replace('\n', ' ').trim()
            }
            return ""
        }
    }

    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli() }.getOrNull()
    }

    /** Resolve a relative rel target against the source part's directory into a zip entry name. */
    fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val segs = ArrayList<String>()
        if (dir.isNotEmpty()) segs.addAll(dir.split('/'))
        for (s in target.split('/')) when (s) {
            "", "." -> {}
            ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
            else -> segs.add(s)
        }
        return segs.joinToString("/")
    }
}

/** Paragraph text of a txBody (runs, fields, line breaks), paragraphs separated by '\n'. */
fun plainText(txBody: XNode?): String {
    if (txBody == null) return ""
    val sb = StringBuilder()
    var first = true
    for (p in txBody.children("p")) {
        if (!first) sb.append('\n')
        first = false
        for (r in p.children) when (r.name) {
            "r", "fld" -> sb.append(r.child("t")?.text ?: "")
            "br" -> sb.append('\n')
        }
    }
    return sb.toString()
}

val CLR_MAP_KEYS = listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")

/** Office 2013 default colour scheme, used when a theme is missing. */
val DEFAULT_SCHEME: Map<String, Int> = mapOf(
    "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(), "lt2" to 0xFFE7E6E6.toInt(),
    "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(), "accent3" to 0xFFA5A5A5.toInt(),
    "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(), "accent6" to 0xFF70AD47.toInt(),
    "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt(),
)
