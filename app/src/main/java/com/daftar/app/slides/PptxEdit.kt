package com.daftar.app.slides

import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

// Slide-level editing of a .pptx package. Pure JVM (java.util.zip + javax.xml + text splicing, no Android API), so it
// is also run on the desktop JVM to verify the packages it writes. Only the parts an edit needs are rewritten; every
// other entry is copied with identical bytes.

/** A slide layout a new slide can be based on ([path] = zip entry name, [type] = ST_SlideLayoutType, e.g. "obj", "blank"). */
data class LayoutInfo(val path: String, val name: String, val type: String, val hasTitle: Boolean, val placeholders: Int)

/** What a newly inserted slide contains. */
sealed class NewSlide {
    /** Slide on [layout] (null = picked automatically) with the layout's placeholders; [title] fills the title placeholder. */
    data class Blank(val layout: String?, val title: String = "") : NewSlide()

    /** Plain white slide without master graphics, for handwriting. */
    data object NotePage : NewSlide()

    /** Copy of slide [src] (its notes page too; comments are not copied). */
    data class Duplicate(val src: Int) : NewSlide()
}

sealed class SlideOp {
    /** Insert a slide so that it ends up at position [at] (0 = first, n = last). */
    data class Insert(val at: Int, val kind: NewSlide) : SlideOp()
    data class Delete(val index: Int) : SlideOp()
    /** Move slide [from] so that it ends up at position [to]. */
    data class Move(val from: Int, val to: Int) : SlideOp()
    data class SetHidden(val index: Int, val hidden: Boolean) : SlideOp()
}

/** One slide of the new order: [src] = its index before the edit (-1 = new slide), [inkFrom] = slide whose ink it gets (-1 = none). */
data class Slot(val src: Int, val inkFrom: Int)

/** Result of [PptxEdit.rewrite]: the new slide order and the slide to show afterwards. */
class EditResult(val plan: List<Slot>, val focus: Int)

class PptxEditException(msg: String) : IOException(msg)

object PptxEdit {
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"

    /** Parts shared by a duplicated slide instead of being copied (pictures, media, layouts, links to other slides …). */
    private val SHARED = listOf(
        "/slideLayout", "/image", "/media", "/video", "/audio", "/hyperlink", "/slide", "/notesMaster", "/slideMaster",
        "/theme", "/commentAuthors", "/authors", "/presentation",
    )
    /** Parts a duplicated slide drops (comments belong to the original). */
    private val DROPPED = listOf("/comments")

    // ------------------------------------------------------------------ public API

    /** Layouts of the slide master used by slide [refSlide] (all masters' layouts when it cannot be resolved), in master order. */
    fun layouts(file: File, refSlide: Int): List<LayoutInfo> = ZipFile(file).use { z ->
        val pkg = Pkg(z)
        val deck = Deck(pkg)
        val ref = deck.slides.getOrNull(refSlide.coerceIn(0, (deck.slides.size - 1).coerceAtLeast(0)))
        val master = ref?.let { deck.masterOfSlide(it.path) }
        val masters = if (master != null) listOf(master) else deck.masters()
        masters.flatMap { deck.layoutsOf(it) }
    }

    /** Layout path of slide [index] (null when it has none). */
    fun layoutOf(file: File, index: Int): String? = ZipFile(file).use { z ->
        val deck = Deck(Pkg(z))
        deck.slides.getOrNull(index)?.let { deck.layoutOfSlide(it.path) }
    }

    /**
     * Applies [op] to [file] and writes the whole new package to [out] (the original is not touched). Throws on any problem.
     */
    fun rewrite(file: File, op: SlideOp, out: File): EditResult = ZipFile(file).use { z ->
        val pkg = Pkg(z)
        val deck = Deck(pkg)
        val n = deck.slides.size
        if (n == 0) throw PptxEditException("no slides")
        val ids = (0 until n).toMutableList()
        val result: EditResult = when (op) {
            is SlideOp.Insert -> {
                val at = op.at.coerceIn(0, n)
                val entry = when (val k = op.kind) {
                    is NewSlide.Blank -> deck.addBlank(k.layout, k.title, at, notePage = false)
                    NewSlide.NotePage -> deck.addBlank(null, "", at, notePage = true)
                    is NewSlide.Duplicate -> {
                        require(k.src in 0 until n) { "bad source slide" }
                        deck.addDuplicate(k.src)
                    }
                }
                deck.entries.add(at, entry)
                deck.sectionJoin(entry.id)
                val plan = ids.map { Slot(it, it) }.toMutableList()
                plan.add(at, Slot(-1, (op.kind as? NewSlide.Duplicate)?.src ?: -1))
                EditResult(plan, at)
            }
            is SlideOp.Delete -> {
                require(op.index in 0 until n) { "bad slide" }
                if (n <= 1) throw PptxEditException("cannot delete the only slide")
                deck.delete(op.index)
                ids.removeAt(op.index)
                EditResult(ids.map { Slot(it, it) }, op.index.coerceAtMost(n - 2))
            }
            is SlideOp.Move -> {
                require(op.from in 0 until n) { "bad slide" }
                val to = op.to.coerceIn(0, n - 1)
                if (to != op.from) {
                    val e = deck.entries.removeAt(op.from)
                    deck.entries.add(to, e)
                    deck.sectionJoin(e.id)
                    ids.add(to, ids.removeAt(op.from))
                }
                EditResult(ids.map { Slot(it, it) }, to)
            }
            is SlideOp.SetHidden -> {
                require(op.index in 0 until n) { "bad slide" }
                deck.setHidden(op.index, op.hidden)
                EditResult(ids.map { Slot(it, it) }, op.index)
            }
        }
        deck.commit()
        pkg.write(out)
        result
    }

    /**
     * Structural problems of a package: missing content types, XML parts that are not well-formed (all of them, or only
     * [xmlParts] when given), relationship targets that do not exist, duplicate or dangling slide ids. Empty = OK.
     */
    fun check(file: File, xmlParts: Set<String>? = null): List<String> {
        val out = ArrayList<String>()
        try {
            ZipFile(file).use { z ->
                val pkg = Pkg(z)
                val names = pkg.allNames()
                val nameSet = names.toHashSet()
                val ctText = pkg.text("[Content_Types].xml") ?: return listOf("no [Content_Types].xml")
                val defaults = HashMap<String, String>()
                val overrides = HashMap<String, String>()
                for (m in elements(ctText, "Default")) {
                    val a = attrs(m)
                    a["Extension"]?.let { defaults[it.lowercase()] = a["ContentType"] ?: "" }
                }
                for (m in elements(ctText, "Override")) {
                    val a = attrs(m)
                    a["PartName"]?.let { overrides[it.removePrefix("/").lowercase()] = a["ContentType"] ?: "" }
                }
                val lower = names.map { it.lowercase() }.toHashSet()
                for (o in overrides.keys) if (o !in lower) out.add("override without part: /$o")
                for (nm in names) {
                    if (nm == "[Content_Types].xml") continue
                    val ext = nm.substringAfterLast('.', "").lowercase()
                    if (nm.lowercase() !in overrides && ext !in defaults) out.add("no content type: $nm")
                }
                for (nm in names) {
                    val ct = overrides[nm.lowercase()] ?: defaults[nm.substringAfterLast('.', "").lowercase()] ?: ""
                    val isXml = nm.endsWith(".rels") || nm.endsWith(".xml") || ct.endsWith("+xml") || ct.endsWith("/xml")
                    if (!isXml || (xmlParts != null && nm !in xmlParts && !nm.endsWith(".rels") && nm != "[Content_Types].xml")) continue
                    val b = pkg.bytes(nm) ?: continue
                    if (!wellFormed(b)) out.add("not well-formed: $nm")
                }
                for (nm in names) {
                    if (!nm.endsWith(".rels")) continue
                    val src = sourceOfRels(nm) ?: continue
                    val dir = src.substringBeforeLast('/', "")
                    for (r in parseRels(pkg.text(nm) ?: continue)) {
                        if (r.external) continue
                        val t = resolve(dir, r.target)
                        if (t !in nameSet) out.add("missing rel target: $nm ${r.id} -> $t")
                    }
                }
                val deck = Deck(pkg)
                val sids = deck.entries.map { it.id }
                if (sids.toSet().size != sids.size) out.add("duplicate slide ids")
                if (sids.any { it < 256 || it >= 2147483648L }) out.add("slide id out of range")
                val rids = deck.entries.map { it.rid }
                if (rids.toSet().size != rids.size) out.add("duplicate slide rIds")
                for (e in deck.entries) {
                    val r = deck.presRels.firstOrNull { it.id == e.rid }
                    if (r == null) out.add("sldId ${e.id} has no relationship ${e.rid}")
                    else if (resolve(deck.presDir, r.target) !in nameSet) out.add("slide part missing for ${e.rid}")
                }
            }
        } catch (e: Exception) {
            out.add("unreadable: ${e.javaClass.simpleName} ${e.message}")
        }
        return out
    }

    // ------------------------------------------------------------------ package access

    /** Zip package with pending changes. Unchanged entries are streamed straight from the source zip on [write]. */
    private class Pkg(val zip: ZipFile) {
        val names: List<String> = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.distinct().toList()
        private val nameSet = names.toHashSet()
        val changed = HashMap<String, ByteArray>()
        val added = LinkedHashMap<String, ByteArray>()
        val removed = HashSet<String>()

        fun exists(n: String) = (n in nameSet && n !in removed) || n in added
        fun existsIgnoreCase(n: String) = allNames().any { it.equals(n, ignoreCase = true) }

        fun bytes(n: String): ByteArray? = changed[n] ?: added[n] ?: if (n in nameSet && n !in removed) {
            zip.getInputStream(zip.getEntry(n)).use { it.readBytes() }
        } else null

        fun text(n: String): String? = bytes(n)?.let { String(it, Charsets.UTF_8) }

        fun put(n: String, s: String) = putBytes(n, s.toByteArray(Charsets.UTF_8))

        fun putBytes(n: String, b: ByteArray) {
            if (n in nameSet) { changed[n] = b; removed.remove(n) } else added[n] = b
        }

        fun remove(n: String) {
            changed.remove(n); added.remove(n)
            if (n in nameSet) removed.add(n)
        }

        fun allNames(): List<String> = names.filter { it !in removed } + added.keys

        /** Changed and added parts (the ones worth re-validating). */
        fun touched(): Set<String> = changed.keys + added.keys

        fun write(out: File) {
            ZipOutputStream(out.outputStream().buffered(256 * 1024)).use { zo ->
                val buf = ByteArray(64 * 1024)
                for (n in names) {
                    if (n in removed) continue
                    val src = zip.getEntry(n)
                    val e = ZipEntry(n).apply { if (src.time > 0) time = src.time }
                    zo.putNextEntry(e)
                    val b = changed[n]
                    if (b != null) zo.write(b)
                    else zip.getInputStream(src).use { ins ->
                        while (true) { val r = ins.read(buf); if (r < 0) break; zo.write(buf, 0, r) }
                    }
                    zo.closeEntry()
                }
                for ((n, b) in added) {
                    zo.putNextEntry(ZipEntry(n))
                    zo.write(b)
                    zo.closeEntry()
                }
            }
        }
    }

    private class RelE(val xml: String, val id: String, val type: String, val target: String, val external: Boolean)

    /** One `p:sldId` of the slide list: its raw XML, numeric id and relationship id. */
    private class Entry(val xml: String, val id: Long, val rid: String)

    private class SlideRef(val path: String, val entry: Entry)

    /** presentation.xml + its relationships + [Content_Types].xml as editable text. */
    private class Deck(val pkg: Pkg) {
        val presPath: String = run {
            val root = pkg.text("_rels/.rels")
            val t = root?.let { parseRels(it).firstOrNull { r -> r.type.endsWith("/officeDocument") && !r.external } }
            t?.let { resolve("", it.target) } ?: "ppt/presentation.xml"
        }
        val presDir = presPath.substringBeforeLast('/', "")
        val presRelsPath = relsPathOf(presPath)
        var pres: String = pkg.text(presPath) ?: throw PptxEditException("no presentation part")
        var presRelsText: String = pkg.text(presRelsPath) ?: throw PptxEditException("no presentation relationships")
        var presRels: List<RelE> = parseRels(presRelsText)
        var ct: String? = null
        private val listStart: Int
        private val listEnd: Int
        private val pfx: String
        private val rAttr: String
        val entries: MutableList<Entry>
        private val origSection = HashMap<Long, Int>()
        private val moved = LinkedHashSet<Long>()
        private val removedRids = HashSet<String>()

        init {
            val m = Regex("<(?:(\\w+):)?sldIdLst\\b[^>]*?(/?)>").find(pres) ?: throw PptxEditException("no slide list")
            pfx = m.groupValues[1].let { if (it.isEmpty()) "" else "$it:" }
            if (m.groupValues[2] == "/") {
                val open = m.value.dropLast(2) + ">"
                pres = pres.replaceRange(m.range, open + "</${pfx}sldIdLst>")
                listStart = m.range.first + open.length
                listEnd = listStart
            } else {
                listStart = m.range.last + 1
                listEnd = pres.indexOf("</${pfx}sldIdLst>", listStart).takeIf { it >= 0 } ?: throw PptxEditException("bad slide list")
            }
            val inner = pres.substring(listStart, listEnd)
            val rx = Regex("<${Regex.escape(pfx)}sldId\\b[^>]*?(?:/>|>.*?</${Regex.escape(pfx)}sldId>)", RegexOption.DOT_MATCHES_ALL)
            entries = rx.findAll(inner).map { em ->
                val start = em.value.substring(0, em.value.indexOf('>') + 1)
                val a = attrs(start)
                val rid = a.entries.firstOrNull { (k, _) -> k.contains(':') && k.substringAfter(':') == "id" }?.value ?: ""
                Entry(em.value, a["id"]?.toLongOrNull() ?: 0L, rid)
            }.toMutableList()
            rAttr = attrs(entries.firstOrNull()?.xml?.let { it.substring(0, it.indexOf('>') + 1) } ?: "")
                .keys.firstOrNull { it.contains(':') && it.substringAfter(':') == "id" }
                ?: (rPrefix(pres)?.let { "$it:id" } ?: "r:id")
            sections().forEachIndexed { k, s -> for (id in s.ids) origSection.putIfAbsent(id, k) }
        }

        val slides: List<SlideRef>
            get() = entries.mapNotNull { e ->
                val r = presRels.firstOrNull { it.id == e.rid && !it.external } ?: return@mapNotNull null
                SlideRef(resolve(presDir, r.target), e)
            }

        fun contentTypes(): String = ct ?: (pkg.text("[Content_Types].xml") ?: throw PptxEditException("no content types")).also { ct = it }

        // -------------------------------------------------------------- reading layouts / masters

        fun masters(): List<String> = presRels.filter { !it.external && it.type.endsWith("/slideMaster") }.map { resolve(presDir, it.target) }

        fun layoutOfSlide(slidePath: String): String? = relsOf(slidePath).firstOrNull { !it.external && it.type.endsWith("/slideLayout") }
            ?.let { resolve(slidePath.substringBeforeLast('/', ""), it.target) }

        fun masterOfSlide(slidePath: String): String? = layoutOfSlide(slidePath)?.let { lp ->
            relsOf(lp).firstOrNull { !it.external && it.type.endsWith("/slideMaster") }?.let { resolve(lp.substringBeforeLast('/', ""), it.target) }
        }

        fun relsOf(part: String): List<RelE> = pkg.text(relsPathOf(part))?.let { parseRels(it) } ?: emptyList()

        fun layoutsOf(master: String): List<LayoutInfo> {
            val doc = dom(pkg.bytes(master) ?: return emptyList()) ?: return emptyList()
            val rels = relsOf(master)
            val dir = master.substringBeforeLast('/', "")
            val ordered = doc.child(NS_P, "sldLayoutIdLst")?.kids(NS_P, "sldLayoutId")
                ?.mapNotNull { el -> rels.firstOrNull { it.id == el.getAttributeNS(NS_R, "id") } }
                ?: rels.filter { it.type.endsWith("/slideLayout") }
            return ordered.filter { !it.external }.mapNotNull { r ->
                val path = resolve(dir, r.target)
                val ld = pkg.bytes(path)?.let { dom(it) } ?: return@mapNotNull null
                val phs = placeholders(ld)
                LayoutInfo(
                    path, ld.child(NS_P, "cSld")?.getAttribute("name").orEmpty(),
                    ld.getAttribute("type").ifEmpty { "cust" },
                    phs.any { it.type == "title" || it.type == "ctrTitle" },
                    phs.count { it.type !in FOOTER_PH },
                )
            }
        }

        // -------------------------------------------------------------- insert

        private fun refSlide(at: Int): SlideRef = slides.let { s -> s[(at - 1).coerceIn(0, s.size - 1)] }

        fun addBlank(layoutPath: String?, title: String, at: Int, notePage: Boolean): Entry {
            val ref = refSlide(at)
            val master = masterOfSlide(ref.path)
            val layouts = (master?.let { listOf(it) } ?: masters()).flatMap { layoutsOf(it) }
            val all = masters().flatMap { layoutsOf(it) }
            val layout = when {
                layoutPath != null -> (layouts + all).firstOrNull { it.path == layoutPath }
                notePage -> layouts.firstOrNull { it.type == "blank" } ?: layouts.minByOrNull { it.placeholders }
                else -> layouts.firstOrNull { it.type == "obj" } ?: layouts.firstOrNull { it.type == "titleOnly" }
                    ?: layouts.firstOrNull { it.type == "blank" }
            } ?: layouts.firstOrNull() ?: all.firstOrNull() ?: throw PptxEditException("no slide layout")

            val slideDir = ref.path.substringBeforeLast('/', "")
            val path = nextName(slideDir, "slide", "xml")
            val xml = if (notePage) notePageXml() else blankXml(layout.path, master, title.trim())
            pkg.put(path, xml)
            pkg.put(relsPathOf(path), relsXml(listOf(Triple("rId1", "$REL/slideLayout", relative(slideDir, layout.path)))))
            addOverride(path, CT_SLIDE)
            return addPresRel(path)
        }

        private fun notePageXml(): String =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<p:sld xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" showMasterSp="0"><p:cSld>""" +
                """<p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>""" +
                SP_TREE_START + "</p:spTree></p:cSld>" + CLR_MAP_OVR + "</p:sld>"

        private fun blankXml(layoutPath: String, master: String?, title: String): String {
            val ld = pkg.bytes(layoutPath)?.let { dom(it) }
            val phs = ld?.let { placeholders(it) }?.filter { it.type !in FOOTER_PH } ?: emptyList()
            val sb = StringBuilder()
            var id = 2
            var titleDone = title.isEmpty()
            for (ph in phs) {
                val isTitle = ph.type == "title" || ph.type == "ctrTitle"
                val text = if (isTitle && !titleDone) title.also { titleDone = true } else ""
                sb.append(phShape(id, ph, text, null))
                id++
            }
            if (!titleDone) {
                // The layout has no title placeholder: place a title where the master puts it (or a top band).
                val box = master?.let { pkg.bytes(it) }?.let { dom(it) }?.let { md -> placeholders(md).firstOrNull { it.type == "title" }?.box }
                    ?: slideSize().let { (w, h) -> longArrayOf(w / 20, h / 25, w * 9 / 10, h * 9 / 50) }
                sb.append(phShape(id, Ph("title", null, null, null, "Title ${id - 1}", null), title, box))
            }
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
                """<p:sld xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P"><p:cSld>""" +
                SP_TREE_START + sb + "</p:spTree></p:cSld>" + CLR_MAP_OVR + "</p:sld>"
        }

        private fun phShape(id: Int, ph: Ph, text: String, box: LongArray?): String {
            val phAttrs = StringBuilder()
            if (ph.type != null && ph.type != "obj") phAttrs.append(" type=\"").append(esc(ph.type)).append('"')
            ph.orient?.let { phAttrs.append(" orient=\"").append(esc(it)).append('"') }
            ph.sz?.let { phAttrs.append(" sz=\"").append(esc(it)).append('"') }
            ph.idx?.let { phAttrs.append(" idx=\"").append(esc(it)).append('"') }
            val name = esc(ph.name ?: "Placeholder ${id - 1}")
            val spPr = if (box == null) "<p:spPr/>" else
                """<p:spPr><a:xfrm><a:off x="${box[0]}" y="${box[1]}"/><a:ext cx="${box[2]}" cy="${box[3]}"/></a:xfrm></p:spPr>"""
            val textual = ph.type == null || ph.type in TEXT_PH
            val body = if (!textual) "" else {
                val rtl = isRtl(text)
                val lang = if (rtl) "ar-SA" else "en-US"
                val para = if (text.isEmpty()) """<a:p><a:endParaRPr lang="$lang" dirty="0"/></a:p>"""
                else text.split('\n').joinToString("") { line ->
                    (if (rtl) """<a:p><a:pPr rtl="1"/>""" else "<a:p>") +
                        """<a:r><a:rPr lang="$lang" dirty="0"/><a:t>${esc(line)}</a:t></a:r></a:p>"""
                }
                "<p:txBody><a:bodyPr/><a:lstStyle/>$para</p:txBody>"
            }
            return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr>""" +
                """<p:nvPr><p:ph$phAttrs/></p:nvPr></p:nvSpPr>$spPr$body</p:sp>"""
        }

        fun slideSize(): Pair<Long, Long> {
            val m = Regex("<(?:\\w+:)?sldSz\\b[^>]*>").find(pres)
            val a = m?.let { attrs(it.value) } ?: emptyMap()
            return (a["cx"]?.toLongOrNull() ?: 9144000L) to (a["cy"]?.toLongOrNull() ?: 6858000L)
        }

        /** Duplicate of slide [src]: same XML, notes page copied, charts / diagrams / embeddings copied, media shared. */
        fun addDuplicate(src: Int): Entry {
            val ref = slides[src]
            val dir = ref.path.substringBeforeLast('/', "")
            val path = nextName(dir, "slide", "xml")
            val map = HashMap<String, String>()
            map[ref.path] = path
            clonePart(ref.path, path, map, 0)
            addOverride(path, overrideOf(ref.path) ?: CT_SLIDE)
            return addPresRel(path)
        }

        /** Copies [from] to [to] with its relationships; non-shared targets are copied recursively ([map] = old → new paths). */
        private fun clonePart(from: String, to: String, map: HashMap<String, String>, depth: Int) {
            val dir = from.substringBeforeLast('/', "")
            val relsText = pkg.text(relsPathOf(from))
            val dropped = HashSet<String>()
            if (relsText != null) {
                var out: String = relsText
                for (r in parseRels(relsText)) {
                    if (r.external) continue
                    val target = resolve(dir, r.target)
                    val newTarget: String? = when {
                        map.containsKey(target) -> map[target]
                        DROPPED.any { r.type.endsWith(it) } -> null
                        SHARED.any { r.type.endsWith(it) } || depth >= 4 || !pkg.exists(target) -> target
                        else -> {
                            val tdir = target.substringBeforeLast('/', "")
                            val base = target.substringAfterLast('/').substringBeforeLast('.').trimEnd { it.isDigit() }.ifEmpty { "part" }
                            val ext = target.substringAfterLast('.', "xml")
                            val copy = nextName(tdir, base, ext)
                            map[target] = copy
                            clonePart(target, copy, map, depth + 1)
                            overrideOf(target)?.let { addOverride(copy, it) }
                            copy
                        }
                    }
                    if (newTarget == null) {
                        dropped.add(r.id)
                        out = out.replace(r.xml, "")
                    } else if (newTarget != target) {
                        val rel = relative(to.substringBeforeLast('/', ""), newTarget)
                        out = out.replace(r.xml, setAttr(r.xml, "Target", rel))
                    }
                }
                pkg.put(relsPathOf(to), out)
            }
            val bytes = pkg.bytes(from) ?: throw PptxEditException("missing part $from")
            if (dropped.isEmpty()) pkg.putBytes(to, bytes)
            else pkg.put(to, stripExtsReferencing(String(bytes, Charsets.UTF_8), dropped))
        }

        private fun addPresRel(path: String): Entry {
            val rid = newRelId(presRels)
            val relXml = """<Relationship Id="$rid" Type="$REL/slide" Target="${esc(relative(presDir, path))}"/>"""
            presRelsText = insertBeforeClose(presRelsText, "Relationships", relXml)
            presRels = parseRels(presRelsText)
            val used = entries.map { it.id }.toHashSet()
            var id = (entries.maxOfOrNull { it.id } ?: 255L) + 1
            if (id < 256) id = 256
            if (id >= 2147483648L) { id = 256; while (id in used) id++ }
            return Entry("<${pfx}sldId id=\"$id\" $rAttr=\"$rid\"/>", id, rid).also { moved.add(id) }
        }

        // -------------------------------------------------------------- delete / hide

        fun delete(index: Int) {
            val e = entries.removeAt(index)
            val rel = presRels.firstOrNull { it.id == e.rid }
            if (rel != null) {
                presRelsText = presRelsText.replace(rel.xml, "")
                presRels = parseRels(presRelsText)
            }
            removedRids.add(e.rid)
        }

        fun setHidden(index: Int, hidden: Boolean) {
            val path = slides[index].path
            val xml = pkg.text(path) ?: throw PptxEditException("missing slide")
            val m = Regex("<(?:\\w+:)?sld(?=[\\s>/])[^>]*>").find(xml) ?: throw PptxEditException("bad slide")
            var tag = m.value
            tag = if (hidden) setAttr(tag, "show", "0") else removeAttr(tag, "show")
            if (tag != m.value) pkg.put(path, xml.replaceRange(m.range, tag))
        }

        /** Slide [id] (new or moved) joins the section of the slide before it (or after it when first). */
        fun sectionJoin(id: Long) { moved.add(id) }

        // -------------------------------------------------------------- sections (p14:sectionLst)

        class Section(val range: IntRange, val xml: String, val ids: List<Long>)

        private fun sections(): List<Section> {
            val lst = Regex("<(\\w+):sectionLst\\b.*?</\\1:sectionLst>", RegexOption.DOT_MATCHES_ALL).find(pres) ?: return emptyList()
            val base = lst.range.first
            return Regex("<(\\w+):section\\b[^>]*?(?:/>|>.*?</\\1:section>)", RegexOption.DOT_MATCHES_ALL).findAll(lst.value).map { m ->
                val ids = Regex("<\\w+:sldId\\b[^>]*>").findAll(m.value).mapNotNull { attrs(it.value)["id"]?.toLongOrNull() }.toList()
                Section(IntRange(base + m.range.first, base + m.range.last), m.value, ids)
            }.toList()
        }

        private fun rewriteSections() {
            val secs = sections()
            if (secs.isEmpty()) return
            val order = entries.map { it.id }
            val member = HashMap<Long, Int>()
            for (id in order) if (id !in moved) origSection[id]?.let { member[id] = it }
            // Each new / moved / unassigned slide joins its previous neighbour's section (next one when it is first).
            for ((i, id) in order.withIndex()) {
                if (member.containsKey(id)) continue
                val prev = (i - 1 downTo 0).firstNotNullOfOrNull { member[order[it]] }
                val next = (i + 1 until order.size).firstNotNullOfOrNull { k -> order[k].takeIf { it !in moved }?.let { member[it] } }
                member[id] = prev ?: next ?: 0
            }
            // Keep sections contiguous and in list order: a section index never decreases along the slide order.
            var floor = 0
            for (id in order) { val s = maxOf(member[id] ?: 0, floor); member[id] = s; floor = s }
            for ((k, s) in secs.withIndex().reversed()) {
                val idsXml = order.filter { member[it] == k }
                val lstRx = Regex("<(\\w+):sldIdLst\\b[^>]*?(?:/>|>.*?</\\1:sldIdLst>)", RegexOption.DOT_MATCHES_ALL)
                val lm = lstRx.find(s.xml) ?: continue
                val p = lm.groupValues[1]
                val inner = idsXml.joinToString("") { "<$p:sldId id=\"$it\"/>" }
                val newLst = if (inner.isEmpty()) "<$p:sldIdLst/>" else "<$p:sldIdLst>$inner</$p:sldIdLst>"
                val newXml = s.xml.replaceRange(lm.range, newLst)
                pres = pres.replaceRange(s.range, newXml)
            }
        }

        // -------------------------------------------------------------- commit

        fun commit() {
            // Slide list first (sections and custom shows below are located after it, so its offsets stay valid).
            pres = pres.substring(0, listStart) + entries.joinToString("") { it.xml } + pres.substring(listEnd)
            rewriteSections()
            if (removedRids.isNotEmpty()) {
                // Custom shows listing a deleted slide.
                pres = Regex("<(?:\\w+:)?sld\\s[^>]*?/>").replace(pres) { m ->
                    val a = attrs(m.value)
                    if (a.entries.any { (k, v) -> k.contains(':') && k.substringAfter(':') == "id" && v in removedRids }) "" else m.value
                }
            }
            pkg.put(presPath, pres)
            pkg.put(presRelsPath, presRelsText)
            if (removedRids.isNotEmpty()) dropUnreachable()
            ct?.let { pkg.put("[Content_Types].xml", it) }
        }

        /** Removes parts that only the deleted slide(s) reached (slide, notes page, comments …) and their content types. */
        private fun dropUnreachable() {
            val before = reachable(original = true)
            val after = reachable(original = false)
            val dead = before - after
            for (p in dead) {
                pkg.remove(p)
                pkg.remove(relsPathOf(p))
                removeOverride(p)
            }
        }

        private fun reachable(original: Boolean): Set<String> {
            val seen = HashSet<String>()
            val queue = ArrayDeque<String>()
            fun relsText(part: String): String? = relsPathOf(part).let { rp ->
                if (original) pkg.zip.getEntry(rp)?.let { e -> pkg.zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
                else pkg.text(rp)
            }
            fun visit(src: String, text: String?) {
                text ?: return
                val dir = src.substringBeforeLast('/', "")
                for (r in parseRels(text)) {
                    if (r.external) continue
                    val t = resolve(dir, r.target)
                    if (seen.add(t)) queue.add(t)
                }
            }
            visit("", if (original) pkg.zip.getEntry("_rels/.rels")?.let { e -> pkg.zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } } else pkg.text("_rels/.rels"))
            while (queue.isNotEmpty()) { val p = queue.removeFirst(); visit(p, relsText(p)) }
            return seen
        }

        // -------------------------------------------------------------- content types

        fun overrideOf(path: String): String? = elements(contentTypes(), "Override").map { attrs(it) }
            .firstOrNull { it["PartName"]?.removePrefix("/").equals(path, ignoreCase = true) }?.get("ContentType")

        fun addOverride(path: String, type: String) {
            if (overrideOf(path) != null) return
            ct = insertBeforeClose(contentTypes(), "Types", """<Override PartName="/${esc(path)}" ContentType="${esc(type)}"/>""")
        }

        fun removeOverride(path: String) {
            var t = contentTypes()
            for (m in elements(t, "Override")) {
                if (attrs(m)["PartName"]?.removePrefix("/").equals(path, ignoreCase = true)) t = t.replace(m, "")
            }
            ct = t
        }

        // -------------------------------------------------------------- names

        fun nextName(dir: String, base: String, ext: String): String {
            val prefix = (if (dir.isEmpty()) "" else "$dir/") + base
            val rx = Regex(Regex.escape(prefix) + "(\\d+)\\." + Regex.escape(ext), RegexOption.IGNORE_CASE)
            var n = (pkg.allNames().mapNotNull { rx.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
            while (pkg.existsIgnoreCase("$prefix$n.$ext")) n++
            return "$prefix$n.$ext"
        }
    }

    // ------------------------------------------------------------------ placeholders (DOM)

    private class Ph(val type: String?, val idx: String?, val orient: String?, val sz: String?, val name: String?, val box: LongArray?)

    private val FOOTER_PH = setOf("dt", "ftr", "sldNum", "hdr")
    private val TEXT_PH = setOf("title", "ctrTitle", "subTitle", "body", "obj")

    private fun placeholders(root: Element): List<Ph> {
        val tree = root.child(NS_P, "cSld")?.child(NS_P, "spTree") ?: return emptyList()
        return tree.kids(NS_P, "sp").mapNotNull { sp ->
            val nv = sp.child(NS_P, "nvSpPr") ?: return@mapNotNull null
            val ph = nv.child(NS_P, "nvPr")?.child(NS_P, "ph") ?: return@mapNotNull null
            val xfrm = sp.child(NS_P, "spPr")?.child(NS_A, "xfrm")
            val off = xfrm?.child(NS_A, "off")
            val ext = xfrm?.child(NS_A, "ext")
            val box = if (off != null && ext != null) longArrayOf(
                off.getAttribute("x").toLongOrNull() ?: 0, off.getAttribute("y").toLongOrNull() ?: 0,
                ext.getAttribute("cx").toLongOrNull() ?: 0, ext.getAttribute("cy").toLongOrNull() ?: 0,
            ) else null
            Ph(
                ph.getAttribute("type").ifEmpty { null }, ph.getAttribute("idx").ifEmpty { null },
                ph.getAttribute("orient").ifEmpty { null }, ph.getAttribute("sz").ifEmpty { null },
                nv.child(NS_P, "cNvPr")?.getAttribute("name")?.ifEmpty { null }, box,
            )
        }
    }

    private fun Element.kids(ns: String, local: String): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n is Element && n.localName == local && n.namespaceURI == ns) out.add(n)
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(ns: String, local: String): Element? = kids(ns, local).firstOrNull()

    private fun dom(b: ByteArray): Element? = runCatching {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        f.newDocumentBuilder().parse(ByteArrayInputStream(b)).documentElement
    }.getOrNull()

    private fun wellFormed(b: ByteArray): Boolean = runCatching {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        f.newSAXParser().parse(InputSource(ByteArrayInputStream(b)), DefaultHandler())
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ text helpers

    private const val SP_TREE_START = "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
        "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
    private const val CLR_MAP_OVR = "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>"

    private val ATTR_RX = Regex("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /** Attributes of the first start tag in [tag] (values unescaped). */
    private fun attrs(tag: String): Map<String, String> {
        val head = tag.substring(0, (tag.indexOf('>').takeIf { it >= 0 } ?: tag.length))
        val nameEnd = head.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (m in ATTR_RX.findAll(head, nameEnd)) out[m.groupValues[1]] = unesc(m.groups[2]?.value ?: m.groups[3]?.value ?: "")
        return out
    }

    private fun setAttr(tag: String, name: String, value: String): String {
        val rx = Regex("(\\s${Regex.escape(name)}\\s*=\\s*)(\"[^\"]*\"|'[^']*')")
        val v = "\"${esc(value)}\""
        rx.find(tag)?.let { m -> return tag.replaceRange(m.range, m.groupValues[1] + v) }
        val end = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
        return tag.substring(0, end).trimEnd() + " $name=$v" + tag.substring(end)
    }

    private fun removeAttr(tag: String, name: String): String =
        Regex("\\s${Regex.escape(name)}\\s*=\\s*(?:\"[^\"]*\"|'[^']*')").replace(tag, "")

    /** Empty-or-not elements `<X .../>` / `<X ...></X>` (any prefix) as raw strings. */
    private fun elements(text: String, local: String): List<String> =
        Regex("<(?:\\w+:)?$local\\b[^>]*?(?:/>|>.*?</(?:\\w+:)?$local\\s*>)", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.value }.toList()

    private fun parseRels(text: String): List<RelE> = elements(text, "Relationship").map { x ->
        val a = attrs(x)
        RelE(x, a["Id"] ?: "", a["Type"] ?: "", a["Target"] ?: "", a["TargetMode"] == "External")
    }

    private fun relsXml(rels: List<Triple<String, String, String>>): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            rels.joinToString("") { (id, type, target) -> """<Relationship Id="$id" Type="$type" Target="${esc(target)}"/>""" } +
            "</Relationships>"

    private fun newRelId(rels: List<RelE>): String {
        val ids = rels.map { it.id }.toHashSet()
        var n = (rels.mapNotNull { Regex("rId(\\d+)").matchEntire(it.id)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        while ("rId$n" in ids) n++
        return "rId$n"
    }

    private fun insertBeforeClose(text: String, root: String, add: String): String {
        val close = Regex("</(?:\\w+:)?$root\\s*>").findAll(text).lastOrNull()
        if (close != null) return text.substring(0, close.range.first) + add + text.substring(close.range.first)
        val self = Regex("<((?:\\w+:)?$root)\\b([^>]*?)/>").find(text) ?: throw PptxEditException("bad $root")
        return text.replaceRange(self.range, "<${self.groupValues[1]}${self.groupValues[2]}>$add</${self.groupValues[1]}>")
    }

    /** Removes extension blocks (`<x:ext uri=…>…</x:ext>`) that point at relationships that no longer exist. */
    private fun stripExtsReferencing(xml: String, ids: Set<String>): String =
        Regex("<(\\w+):ext\\s[^>]*?\\buri=\"[^\"]*\"[^>]*>.*?</\\1:ext>", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            if (ids.any { m.value.contains("=\"$it\"") }) "" else m.value
        }

    private fun rPrefix(xml: String): String? = Regex("xmlns:(\\w+)=\"${Regex.escape(NS_R)}\"").find(xml)?.groupValues?.get(1)

    private fun relsPathOf(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        return (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
    }

    /** Part a `.rels` entry belongs to ("ppt/_rels/presentation.xml.rels" → "ppt/presentation.xml", "_rels/.rels" → ""). */
    private fun sourceOfRels(rels: String): String? {
        val i = rels.lastIndexOf("_rels/")
        if (i < 0 || !rels.endsWith(".rels")) return null
        val dir = rels.substring(0, i).trimEnd('/')
        val name = rels.substring(i + 6).removeSuffix(".rels")
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    /** Resolves a relationship target against its source directory into a zip entry name. */
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

    /** Relative target from directory [fromDir] to entry [to] ("ppt/slides", "ppt/slideLayouts/x.xml" → "../slideLayouts/x.xml"). */
    fun relative(fromDir: String, to: String): String {
        val a = if (fromDir.isEmpty()) emptyList() else fromDir.split('/')
        val b = to.split('/')
        var k = 0
        while (k < a.size && k < b.size - 1 && a[k] == b[k]) k++
        return (List(a.size - k) { ".." } + b.drop(k)).joinToString("/")
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\'' -> sb.append("&apos;")
            ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
            ch == '\uFFFE' || ch == '\uFFFF' -> {}
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        if (s.indexOf('&') < 0) return s
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"; v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                v.startsWith("#x") -> String(Character.toChars(v.substring(2).toInt(16)))
                else -> String(Character.toChars(v.substring(1).toInt()))
            }
        }
    }

    /** First strong character is right-to-left (Arabic, Hebrew …). */
    fun isRtl(s: String): Boolean {
        for (ch in s) {
            when (Character.getDirectionality(ch)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
            }
        }
        return false
    }
}
