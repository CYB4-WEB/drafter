package com.daftar.app.onenote

import java.io.File

// [MS-ONE] interpretation of the object spaces read by OneStore: section → page series → pages → outlines, text runs,
// lists, tables, images, ink and attached files. Pure JVM. Anything unexpected is skipped, never fatal for the section.

/** Property ids ([MS-ONE] 2.1.12) used by the reader. */
internal object P {
    const val PageWidth = 0x14001C01L
    const val PageHeight = 0x14001C02L
    const val OutlineElementChildLevel = 0x0C001C03L
    const val Bold = 0x08001C04L
    const val Italic = 0x08001C05L
    const val Underline = 0x08001C06L
    const val Strikethrough = 0x08001C07L
    const val Superscript = 0x08001C08L
    const val Subscript = 0x08001C09L
    const val Font = 0x1C001C0AL
    const val FontSize = 0x10001C0BL
    const val FontColor = 0x14001C0CL
    const val Highlight = 0x14001C0DL
    const val OffsetFromParentHoriz = 0x14001C14L
    const val OffsetFromParentVert = 0x14001C15L
    const val NumberListFormat = 0x1C001C1AL
    const val LayoutMaxWidth = 0x14001C1BL
    const val LayoutMaxHeight = 0x14001C1CL
    const val ContentChildNodes = 0x24001C1FL
    const val ElementChildNodes = 0x24001C20L
    const val RichEditTextUnicode = 0x1C001C22L
    const val ListNodes = 0x24001C26L
    const val OutlineElementRTL = 0x08001C34L
    const val PictureContainer = 0x20001C3FL
    const val InkScalingX = 0x14001C46L
    const val InkScalingY = 0x14001C47L
    const val ListFont = 0x1C001C52L
    const val TopologyCreationTimeStamp = 0x18001C65L
    const val ListRestart = 0x14001CB7L
    const val LayoutMinimumOutlineWidth = 0x14001CECL
    const val CachedTitleString = 0x1C001CF3L
    const val CreationTimeStamp = 0x14001D09L
    const val CachedTitleStringFromPage = 0x1C001D3CL
    const val RowCount = 0x14001D57L
    const val ColumnCount = 0x14001D58L
    const val TableBordersVisible = 0x08001D5EL
    const val StructureElementChildNodes = 0x24001D5FL
    const val ChildGraphSpaceElementNodes = 0x2C001D63L
    const val TableColumnWidths = 0x1C001D66L
    const val LastModifiedTimeStamp = 0x18001D77L
    const val LastModifiedTime = 0x14001D7AL
    const val IsConflictPage = 0x08001D7CL
    const val EmbeddedFileContainer = 0x20001D9BL
    const val EmbeddedFileName = 0x1C001D9CL
    const val SourceFilepath = 0x1C001D9DL
    const val ImageFilename = 0x1C001DD7L
    const val PageLevel = 0x14001DFFL
    const val TextRunIndex = 0x1C001E12L
    const val TextRunFormatting = 0x24001E13L
    const val Hyperlink = 0x08001E14L
    const val WzHyperlinkUrl = 0x1C001E20L
    const val ImageAltText = 0x1C001E58L
    const val ParagraphStyle = 0x2000342CL
    const val MetaDataObjectsAboveGraphSpace = 0x24003442L
    const val ParagraphStyleId = 0x1C00345AL
    const val ReadingOrderRTL = 0x08003476L
    const val ParagraphAlignment = 0x0C003477L
    const val TextExtendedAscii = 0x1C003498L
    const val PictureWidth = 0x140034CDL
    const val PictureHeight = 0x140034CEL
    // ink
    const val InkStrokeProperties = 0x20003409L
    const val InkDimensions = 0x1C00340AL
    const val InkPath = 0x1C00340BL
    const val InkHeight = 0x1400340CL
    const val InkWidth = 0x1400340DL
    const val InkColor = 0x1400340FL
    const val InkTransparency = 0x0C003414L
    const val InkData = 0x20003415L
    const val InkStrokes = 0x24003416L
    const val InkBoundingBox = 0x1C003418L
}

/** JCID indexes ([MS-ONE] 2.1.13), low 16 bits. */
internal object J {
    const val Section = 0x07
    const val PageSeries = 0x08
    const val Page = 0x0B
    const val Outline = 0x0C
    const val OutlineElement = 0x0D
    const val RichText = 0x0E
    const val Image = 0x11
    const val NumberList = 0x12
    const val InkContainer = 0x14
    const val OutlineGroup = 0x19
    const val Table = 0x22
    const val TableRow = 0x23
    const val TableCell = 0x24
    const val Title = 0x2C
    const val PageMetaData = 0x30
    const val EmbeddedFile = 0x35
    const val PageManifest = 0x37
    const val VersionHistory = 0x3C
}

/** Half-inch layout unit → pt. */
private const val HALF_INCH = 36f
/** HIMETRIC (0.01 mm) → pt. */
private const val HIMETRIC = 72f / 2540f
private const val X_DIM = "{598A6A8F-52C0-4BA0-93AF-AF357411A561}"
private const val Y_DIM = "{B53F9F75-04E0-4498-A7EE-C30DBB5A9011}"

object OneReader {
    /** Reads one section file. Never throws for damaged content inside a readable file; throws [OneException] when the file itself can't be used. */
    fun readSection(f: File, name: String = f.nameWithoutExtension): OneSection {
        val store = OneStore.open(f)
        if (store.encrypted) return OneSection(name, emptyList(), OneError.ENCRYPTED, f)
        val pages = SectionBuilder(store).pages()
        if (pages.isEmpty() && store.legacyVersion) throw OneException(OneError.OLD_FORMAT)
        return OneSection(name, pages, null, f)
    }

    /** Debug helper for tests: warnings collected while reading [f]. */
    fun warnings(f: File): List<String> = runCatching { OneStore.open(f).also { SectionBuilder(it).pages() }.warnings.toList() }.getOrElse { listOf(it.toString()) }
}

private class SectionBuilder(val store: OneStore) {

    fun pages(): List<OnePage> {
        val order = pageSpaceOrder()
        val out = ArrayList<OnePage>()
        for ((gosid, meta) in order) {
            val space = store.spaces[gosid] ?: continue
            try { page(space, meta)?.let { out.add(it) } } catch (e: Exception) { /* a broken page is skipped */ }
            catch (e: StackOverflowError) { }
        }
        return out
    }

    /** Page object spaces in section order (from the page series), each with its metadata from the section space. */
    private fun pageSpaceOrder(): List<Pair<XG, PropSet?>> {
        val root = store.spaces[store.rootGosid]
        val result = ArrayList<Pair<XG, PropSet?>>()
        val seen = HashSet<XG>()
        if (root != null) {
            val section = root.props(root.root(1))
            val series = section?.oids(P.ElementChildNodes) ?: emptyList()
            for (s in series) {
                val ps = root.props(s) ?: continue
                val spaces = ps.osids(P.ChildGraphSpaceElementNodes)
                val metas = ps.oids(P.MetaDataObjectsAboveGraphSpace).map { root.props(it) }
                for ((i, g) in spaces.withIndex()) if (seen.add(g)) result.add(g to metas.getOrNull(i))
            }
        }
        if (result.isEmpty()) {
            // Fallback: every object space except the section root, in file order.
            for (g in store.spaces.keys) if (g != store.rootGosid && seen.add(g)) result.add(g to null)
        }
        return result
    }

    private fun page(space: ObjectSpace, sectionMeta: PropSet?): OnePage? {
        if (!space.ok) return null
        val meta = space.props(space.root(2)) ?: sectionMeta
        if (meta?.bool(P.IsConflictPage) == true) return null
        val rootId = space.root(1) ?: return null
        val rootDecl = space.decl(rootId) ?: return null
        val pageId = when (rootDecl.jcidIndex) {
            J.PageManifest -> space.props(rootId)?.oids(P.ContentChildNodes)?.firstOrNull { space.jcidIndex(it) == J.Page }
                ?: space.props(rootId)?.oids(P.ContentChildNodes)?.firstOrNull()
            J.Page -> rootId
            J.VersionHistory -> return null
            else -> rootId
        } ?: return null
        val pg = space.props(pageId) ?: return null
        val ctx = Ctx(space)

        // Title
        var title = ""
        var titleX = 36f; var titleY = 24f
        for (t in pg.oids(P.StructureElementChildNodes)) {
            if (space.jcidIndex(t) != J.Title) continue
            val tp = space.props(t) ?: continue
            for (o in tp.oids(P.ElementChildNodes)) {
                val op = space.props(o) ?: continue
                val blocks = ctx.outlineBlocks(op)
                val txt = blocks.filterIsInstance<OnePara>().joinToString(" ") { it.text.trim() }.trim()
                if (txt.isNotEmpty() && title.isEmpty()) {
                    title = txt
                    // Title outline offsets are relative to the title node; keep it clear of the page edge like OneNote does.
                    titleX = maxOf(((op.f32(P.OffsetFromParentHoriz) ?: 0f) + (tp.f32(P.OffsetFromParentHoriz) ?: 0f)) * HALF_INCH, 36f)
                    titleY = maxOf(((op.f32(P.OffsetFromParentVert) ?: 0f) + (tp.f32(P.OffsetFromParentVert) ?: 0f)) * HALF_INCH, 20f)
                }
            }
        }
        if (title.isEmpty()) title = meta?.utf16(P.CachedTitleString) ?: pg.utf16(P.CachedTitleStringFromPage) ?: ""
        title = clean(title).replace('\n', ' ').trim()

        // Content
        val items = ArrayList<OneItem>()
        for (c in pg.oids(P.ElementChildNodes)) {
            try { ctx.pageItem(c)?.let { items.add(it) } } catch (e: Exception) { }
        }

        val created = time32(meta?.u32(P.CreationTimeStamp)) ?: filetime(meta?.u64(P.TopologyCreationTimeStamp)) ?: time32(pg.u32(P.CreationTimeStamp)) ?: 0L
        val modified = time32(pg.u32(P.LastModifiedTime)) ?: filetime(pg.u64(P.LastModifiedTimeStamp)) ?: time32(meta?.u32(P.LastModifiedTime)) ?: created
        val level = (meta?.u32(P.PageLevel) ?: 1L).toInt().coerceIn(1, 3)
        val w = (pg.f32(P.PageWidth) ?: 0f) * HALF_INCH
        val h = (pg.f32(P.PageHeight) ?: 0f) * HALF_INCH
        return OnePage(title, created, modified, level, items, w.coerceIn(0f, 20000f), h.coerceIn(0f, 50000f),
            titleX.coerceIn(0f, 5000f), titleY.coerceIn(0f, 5000f))
    }

    private fun time32(v: Long?): Long? = v?.takeIf { it > 0 && it != 0xFFFFFFFFL }?.let { (it + 315532800L) * 1000L }
    private fun filetime(v: Long?): Long? = v?.takeIf { it > 0 }?.let { it / 10000L - 11644473600000L }?.takeIf { it > 0 }

    private inner class Ctx(val space: ObjectSpace) {
        val depthGuard = IntArray(1)

        fun pageItem(id: XG): OneItem? {
            val d = space.decl(id) ?: return null
            val p = space.props(id) ?: return null
            val x = (p.f32(P.OffsetFromParentHoriz) ?: 0f) * HALF_INCH
            val y = (p.f32(P.OffsetFromParentVert) ?: 0f) * HALF_INCH
            return when {
                d.jcidIndex == J.Outline -> {
                    val blocks = outlineBlocks(p)
                    if (blocks.isEmpty()) null
                    else OneOutline(x, y, ((p.f32(P.LayoutMaxWidth) ?: p.f32(P.LayoutMinimumOutlineWidth) ?: 0f) * HALF_INCH).coerceIn(0f, 5000f), blocks)
                }
                d.jcidIndex == J.Image -> image(p)?.let { OneImageItem(x, y, it) }
                d.jcidIndex == J.EmbeddedFile -> attachment(p)?.let { OneFileItem(x, y, it) }
                p.has(P.InkData) -> ink(p)?.let { OneInkItem(x, y, it) }
                else -> null
            }
        }

        // ---------------------------------------------------------- outlines

        fun outlineBlocks(outline: PropSet): List<OneBlock> {
            val out = ArrayList<OneBlock>()
            val counters = IntArray(10)
            for (c in outline.oids(P.ElementChildNodes)) element(c, 0, out, counters)
            return out
        }

        private fun element(id: XG, level: Int, out: MutableList<OneBlock>, counters: IntArray) {
            if (depthGuard[0] > 64) return
            depthGuard[0]++
            try {
                val p = space.props(id) ?: return
                when (space.jcidIndex(id)) {
                    J.OutlineGroup -> {
                        val lv = level + ((p.u8(P.OutlineElementChildLevel) ?: 1) - 1).coerceIn(0, 4)
                        for (c in p.oids(P.ElementChildNodes)) element(c, lv, out, counters)
                    }
                    else -> {
                        val label = listLabel(p, level, counters)
                        var first = true
                        val rtl = p.bool(P.OutlineElementRTL) == true
                        for (c in p.oids(P.ContentChildNodes)) {
                            content(c, level, if (first) label else null, rtl)?.let { out.add(it); first = false }
                        }
                        for (c in p.oids(P.ElementChildNodes)) element(c, level + 1, out, counters)
                    }
                }
            } catch (e: Corrupt) {
            } finally { depthGuard[0]-- }
        }

        /** Bullet or number for an outline element, from its NumberListNode (numbered lists contain U+FFFD as the number placeholder). */
        private fun listLabel(oe: PropSet, level: Int, counters: IntArray): String? {
            val lv = level.coerceIn(0, counters.size - 1)
            val listId = oe.oids(P.ListNodes).firstOrNull()
            val list = listId?.let { space.props(it) }
            if (list == null) { for (i in lv until counters.size) counters[i] = 0; return null }
            for (i in lv + 1 until counters.size) counters[i] = 0
            val fmt = list.utf16(P.NumberListFormat) ?: ""
            val restart = list.u32(P.ListRestart)
            val ph = fmt.indexOf('\uFFFD')
            if (ph >= 0) {
                counters[lv] = if (restart != null && restart in 1..100000) restart.toInt() else counters[lv] + 1
                val style = if (ph + 1 < fmt.length) fmt[ph + 1].code else 0
                val n = counters[lv]
                val num = when (style) {
                    1, 2 -> roman(n).let { if (style == 2) it.lowercase() else it }
                    3, 4 -> letters(n).let { if (style == 4) it.lowercase() else it }
                    else -> n.toString()
                }
                val before = fmt.substring(0, ph).filter { it >= ' ' && it != '\uFFFD' }
                val after = fmt.substring((ph + 2).coerceAtMost(fmt.length)).filter { it >= ' ' && it != '\uFFFD' }
                return before + num + after.ifEmpty { "." }
            }
            counters[lv] = 0
            val font = list.utf16(P.ListFont)?.lowercase() ?: ""
            val ch = fmt.firstOrNull { it >= ' ' }
            return when {
                ch == null -> "•"
                font.contains("wingdings") || font.contains("symbol") -> when (ch) {
                    'o' -> "◦"; '§', 'n', 'q', 'Ø' -> "▪"; 'ü' -> "✓"; else -> "•"
                }
                ch == '·' || ch == '\uF0B7' -> "•"
                ch.code in 0xF000..0xF0FF -> "•"
                else -> ch.toString()
            }
        }

        private fun content(id: XG, level: Int, label: String?, rtl: Boolean): OneBlock? {
            val p = space.props(id) ?: return null
            // Image nodes also carry RichEditTextUnicode (OCR text), so the node type is checked before the text properties.
            return when (space.jcidIndex(id)) {
                J.Image -> image(p)?.let { OneImageBlock(level, it) }
                J.Table -> table(p, level)
                J.EmbeddedFile -> attachment(p)?.let { OneFileBlock(level, it) }
                J.RichText -> paragraph(p, level, label, rtl)
                else -> when {
                    p.has(P.InkData) -> ink(p)?.let { OneInkBlock(level, it) }
                    p.has(P.RichEditTextUnicode) || p.has(P.TextExtendedAscii) -> paragraph(p, level, label, rtl)
                    else -> null
                }
            }
        }

        // ---------------------------------------------------------- rich text

        private fun paragraph(p: PropSet, level: Int, label: String?, rtlOe: Boolean): OnePara {
            val raw = p.utf16(P.RichEditTextUnicode)
                ?: p.bytes(P.TextExtendedAscii)?.let { String(it, charset("windows-1252")).trimEnd('\u0000') }
                ?: ""
            val paraStyle = p.oid(P.ParagraphStyle)?.let { space.props(it) }
            val styleId = paraStyle?.utf16(P.ParagraphStyleId)
            val idx = p.bytes(P.TextRunIndex)?.let { b -> List(b.size / 4) { PropSet.le32(b, it * 4).toInt() } } ?: emptyList()
            val fmts = p.oids(P.TextRunFormatting)
            val runs = ArrayList<OneRun>()
            var start = 0
            var link: String? = null
            val bounds = (idx + raw.length).map { it.coerceIn(0, raw.length) }
            for ((i, endRaw) in bounds.withIndex()) {
                val end = maxOf(endRaw, start)
                if (end > start || i == bounds.lastIndex) {
                    val style = fmts.getOrNull(i)?.let { space.props(it) }
                    var seg = raw.substring(start, end)
                    // Hyperlink field code: U+FDDF HYPERLINK "url" precedes the visible text.
                    val m = HYPERLINK.find(seg)
                    val isLinkRun = style?.bool(P.Hyperlink) == true
                    if (m != null) { link = m.groupValues[1]; seg = seg.removeRange(m.range) }
                    else if (!isLinkRun) link = null
                    val url = style?.utf16(P.WzHyperlinkUrl)?.takeIf { it.isNotBlank() } ?: link.takeIf { isLinkRun || m != null }
                    val text = clean(seg)
                    if (text.isNotEmpty()) runs.add(run(text, style ?: paraStyle, paraStyle, url))
                }
                start = end
            }
            if (runs.isEmpty() && raw.isNotEmpty()) runs.add(run(clean(raw), paraStyle, paraStyle, null))
            val align = when (p.u8(P.ParagraphAlignment)) { 1 -> 1; 2 -> 2; else -> 0 }
            val rtl = rtlOe || p.bool(P.ReadingOrderRTL) == true
            return OnePara(level, runs, label, align, styleId, rtl)
        }

        private fun run(text: String, s: PropSet?, para: PropSet?, link: String?): OneRun {
            fun b(id: Long) = s?.bool(id) ?: para?.bool(id) ?: false
            val size = (s?.u16(P.FontSize) ?: para?.u16(P.FontSize))?.let { it / 2f }?.takeIf { it in 1f..400f } ?: 0f
            return OneRun(
                text = text,
                bold = b(P.Bold), italic = b(P.Italic), underline = b(P.Underline) || link != null, strike = b(P.Strikethrough),
                size = size,
                color = colorRef(s?.u32(P.FontColor) ?: para?.u32(P.FontColor)),
                highlight = colorRef(s?.u32(P.Highlight) ?: para?.u32(P.Highlight)),
                link = link,
                superscript = b(P.Superscript), subscript = b(P.Subscript),
                font = s?.utf16(P.Font) ?: para?.utf16(P.Font),
            )
        }

        // ---------------------------------------------------------- tables

        private fun table(p: PropSet, level: Int): OneTable? {
            val rows = ArrayList<List<List<OneBlock>>>()
            for (r in p.oids(P.ElementChildNodes)) {
                val rp = space.props(r) ?: continue
                val cells = ArrayList<List<OneBlock>>()
                for (c in rp.oids(P.ElementChildNodes)) {
                    val cp = space.props(c)
                    val blocks = ArrayList<OneBlock>()
                    val counters = IntArray(10)
                    if (cp != null) for (e in cp.oids(P.ElementChildNodes)) element(e, 0, blocks, counters)
                    cells.add(blocks)
                }
                rows.add(cells)
            }
            if (rows.isEmpty()) return null
            val widths = p.bytes(P.TableColumnWidths)?.let { b ->
                if (b.isEmpty()) emptyList() else {
                    val n = (b[0].toInt() and 0xFF).coerceAtMost((b.size - 1) / 4)
                    List(n) { java.lang.Float.intBitsToFloat(PropSet.le32(b, 1 + it * 4).toInt()) * HALF_INCH }
                        .map { if (it.isNaN() || it < 0f || it > 5000f) 0f else it }
                }
            } ?: emptyList()
            return OneTable(level, rows, widths, p.bool(P.TableBordersVisible) ?: true)
        }

        // ---------------------------------------------------------- images, files

        fun image(p: PropSet): OneImage? {
            val decl = space.decl(p.oid(P.PictureContainer))
            val blob = decl?.let { store.blobFor(it.fileRef) }
            val w = (p.f32(P.PictureWidth) ?: p.f32(P.LayoutMaxWidth) ?: 0f) * HALF_INCH
            val h = (p.f32(P.PictureHeight) ?: p.f32(P.LayoutMaxHeight) ?: 0f) * HALF_INCH
            val alt = p.utf16(P.ImageAltText)?.let(::clean)?.takeIf { it.isNotBlank() }
            val name = p.utf16(P.ImageFilename)?.takeIf { it.isNotBlank() }
            if (blob == null && alt == null) return null
            return OneImage(blob, w.coerceIn(0f, 10000f), h.coerceIn(0f, 10000f), alt, name)
        }

        fun attachment(p: PropSet): OneAttachment? {
            val decl = space.decl(p.oid(P.EmbeddedFileContainer))
            val blob = decl?.let { store.blobFor(it.fileRef) }
            val name = p.utf16(P.EmbeddedFileName)?.takeIf { it.isNotBlank() }
                ?: p.utf16(P.SourceFilepath)?.substringAfterLast('\\')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: return null
            return OneAttachment(name, blob)
        }

        // ---------------------------------------------------------- ink

        fun ink(container: PropSet): OneInk? {
            val data = space.props(container.oid(P.InkData)) ?: return null
            val sx = container.f32(P.InkScalingX)?.takeIf { it > 0f && it < 1000f } ?: 1f
            val sy = container.f32(P.InkScalingY)?.takeIf { it > 0f && it < 1000f } ?: sx
            // Stroke coordinates are relative to the ink data's bounding box origin (as OneNote and one2html place them).
            val bb = data.bytes(P.InkBoundingBox)?.takeIf { it.size >= 16 }
            val ox = bb?.let { PropSet.le32(it, 0).toInt() } ?: 0
            val oy = bb?.let { PropSet.le32(it, 4).toInt() } ?: 0
            val strokes = ArrayList<OneStroke>()
            for (sid in data.oids(P.InkStrokes)) {
                try { stroke(sid, sx, sy, ox, oy)?.let { strokes.add(it) } } catch (e: Exception) { }
            }
            return if (strokes.isEmpty()) null else OneInk(strokes)
        }

        private fun stroke(id: XG, sx: Float, sy: Float, ox: Int, oy: Int): OneStroke? {
            val sp = space.props(id) ?: return null
            val path = decodeIsfSigned(sp.bytes(P.InkPath) ?: return null)
            val props = space.props(sp.oid(P.InkStrokeProperties))
            val dims = props?.bytes(P.InkDimensions)?.let { b -> List(b.size / 32) { i -> Rd.guidAt(b, i * 32) } } ?: emptyList()
            val nd = dims.size.coerceAtLeast(2)
            val ix = dims.indexOf(X_DIM).takeIf { it >= 0 } ?: 0
            val iy = dims.indexOf(Y_DIM).takeIf { it >= 0 } ?: 1
            val count = path.size / nd
            if (count < 1) return null
            val pts = FloatArray(count * 2)
            // Coordinates are stored as deltas from the previous point (first point absolute), HIMETRIC.
            var x = 0L; var y = 0L
            for (i in 0 until count) {
                x += path[ix * count + i]; y += path[iy * count + i]
                pts[i * 2] = (x - ox) * sx * HIMETRIC
                pts[i * 2 + 1] = (y - oy) * sy * HIMETRIC
            }
            val w = (props?.f32(P.InkWidth) ?: props?.f32(P.InkHeight) ?: 53f) * HIMETRIC
            val colorRaw = props?.u32(P.InkColor)
            val transparency = props?.u8(P.InkTransparency) ?: 0
            val rgb = colorRef(colorRaw) ?: 0xFF000000.toInt()
            val alpha = (255 - transparency).coerceIn(40, 255)
            return OneStroke(pts, w.coerceIn(0.3f, 40f), (rgb and 0x00FFFFFF) or (alpha shl 24), transparency > 0)
        }
    }

    companion object {
        val HYPERLINK = Regex("\uFDDFHYPERLINK\\s+\"([^\"]*)\"")

        /** Removes OneNote control characters; vertical tab (soft line break) becomes a newline. */
        fun clean(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                when {
                    ch == '\u000B' || ch == '\r' -> sb.append('\n')
                    ch == '\t' || ch == '\n' -> sb.append(ch)
                    ch < ' ' -> {}
                    ch == '\uFDDF' || ch == '\uFFFC' || ch == '\uFEFF' -> {}
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** COLORREF (0x00BBGGRR) → ARGB; values with a non-zero high byte mean "automatic". */
        fun colorRef(v: Long?): Int? {
            if (v == null || (v and 0xFF000000L) != 0L) return null
            val r = (v and 0xFF).toInt(); val g = ((v shr 8) and 0xFF).toInt(); val b = ((v shr 16) and 0xFF).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** [MS-ISF] multi-byte encoded signed integers: a count, then the values (sign in bit 0). */
        fun decodeIsfSigned(b: ByteArray): LongArray {
            var i = 0
            fun uint(): Long {
                var v = 0L; var shift = 0
                while (i < b.size) {
                    val x = b[i++].toInt() and 0xFF
                    if (shift < 63) v = v or ((x and 0x7F).toLong() shl shift)
                    shift += 7
                    if (x and 0x80 == 0) break
                }
                return v
            }
            val n = (uint() shr 1).coerceIn(0, 2_000_000)
            val out = LongArray(n.toInt())
            var k = 0
            while (k < out.size && i < b.size) {
                val v = uint()
                out[k++] = if (v and 1L == 1L) -(v ushr 1) else (v ushr 1)
            }
            return if (k == out.size) out else out.copyOf(k)
        }

        fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            val sb = StringBuilder()
            for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
            return sb.toString()
        }

        fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            var x = n
            val sb = StringBuilder()
            while (x > 0) { x--; sb.insert(0, ('A' + x % 26)); x /= 26 }
            return sb.toString()
        }
    }
}
