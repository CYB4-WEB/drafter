package com.daftar.app.convert

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/**
 * Minimal but complete PresentationML package writer: one slide per picture, the picture fitted and centred on a
 * white slide. Pure JVM code (no Android imports) so the generated structure can be checked off-device.
 *
 * Package: [Content_Types].xml, _rels/.rels, docProps/core+app, ppt/presentation.xml (+rels), presProps, viewProps,
 * tableStyles, one slide master with a blank layout and a full theme (colour, font and format schemes), slides + media.
 *
 * Usage: `PptxWriter(out, pageW, pageH, title).use { w -> w.addPicture(...); …; w.finish() }`. Closing without
 * [finish] aborts and deletes the partial file.
 */
class PptxWriter(private val out: File, aspectW: Float, aspectH: Float, private val title: String = "") : Closeable {

    /** Slide size in EMU (long side 12192000 EMU = 13.33 in, short side from the aspect ratio). */
    val slideCx: Long
    val slideCy: Long

    private val zip: ZipOutputStream
    private val mediaExt = ArrayList<String>()
    private var finished = false
    private var closed = false

    init {
        val ratio = (aspectW.coerceAtLeast(1f) / aspectH.coerceAtLeast(1f)).coerceIn(0.2f, 5f)
        if (ratio >= 1f) {
            slideCx = LONG_SIDE
            slideCy = (LONG_SIDE / ratio).roundToLong().coerceIn(MIN_SIDE, MAX_SIDE)
        } else {
            slideCy = LONG_SIDE
            slideCx = (LONG_SIDE * ratio).roundToLong().coerceIn(MIN_SIDE, MAX_SIDE)
        }
        out.parentFile?.mkdirs()
        zip = ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024))
    }

    /** Number of slides written so far. */
    val count: Int get() = mediaExt.size

    /**
     * Adds a slide showing one picture. [wPx]×[hPx] is the picture's pixel size (only the aspect matters), [ext] is
     * "png" or "jpeg", and [write] streams the encoded bytes (it must not close the stream).
     */
    fun addPicture(wPx: Int, hPx: Int, ext: String, write: (OutputStream) -> Unit) {
        check(!finished && !closed)
        val e = if (ext.equals("png", true)) "png" else "jpeg"
        val n = mediaExt.size + 1
        zip.setLevel(Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry("ppt/media/image$n.$e"))
        write(object : FilterOutputStream(zip) {
            override fun write(b: ByteArray, off: Int, len: Int) { zip.write(b, off, len) }
            override fun close() { flush() }
        })
        zip.closeEntry()
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        mediaExt.add(e)

        // Fit the picture inside the slide, keeping its aspect ratio.
        val pw = wPx.coerceAtLeast(1).toDouble(); val ph = hPx.coerceAtLeast(1).toDouble()
        val s = minOf(slideCx / pw, slideCy / ph)
        val cx = (pw * s).roundToLong().coerceIn(1, slideCx)
        val cy = (ph * s).roundToLong().coerceIn(1, slideCy)
        val x = (slideCx - cx) / 2
        val y = (slideCy - cy) / 2
        entry("ppt/slides/slide$n.xml", slideXml(n, x, y, cx, cy))
        entry("ppt/slides/_rels/slide$n.xml.rels", rels(
            Triple("rId1", REL_LAYOUT, "../slideLayouts/slideLayout1.xml"),
            Triple("rId2", REL_IMAGE, "../media/image$n.$e"),
        ))
    }

    /** Writes the remaining parts and closes the zip. Throws if no slide was added. */
    fun finish() {
        check(!finished && !closed)
        require(mediaExt.isNotEmpty()) { "no slides" }
        val n = mediaExt.size
        entry("ppt/slideLayouts/slideLayout1.xml", LAYOUT_XML)
        entry("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels(Triple("rId1", REL_MASTER, "../slideMasters/slideMaster1.xml")))
        entry("ppt/slideMasters/slideMaster1.xml", MASTER_XML)
        entry("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels(
            Triple("rId1", REL_LAYOUT, "../slideLayouts/slideLayout1.xml"),
            Triple("rId2", REL_THEME, "../theme/theme1.xml"),
        ))
        entry("ppt/theme/theme1.xml", THEME_XML)
        entry("ppt/presProps.xml", PRES_PROPS_XML)
        entry("ppt/viewProps.xml", VIEW_PROPS_XML)
        entry("ppt/tableStyles.xml", TABLE_STYLES_XML)
        entry("ppt/presentation.xml", presentationXml(n))
        val presRels = ArrayList<Triple<String, String, String>>()
        presRels.add(Triple("rId1", REL_MASTER, "slideMasters/slideMaster1.xml"))
        for (i in 1..n) presRels.add(Triple("rId${i + 1}", REL_SLIDE, "slides/slide$i.xml"))
        presRels.add(Triple("rId${n + 2}", REL_PRES_PROPS, "presProps.xml"))
        presRels.add(Triple("rId${n + 3}", REL_VIEW_PROPS, "viewProps.xml"))
        presRels.add(Triple("rId${n + 4}", REL_THEME, "theme/theme1.xml"))
        presRels.add(Triple("rId${n + 5}", REL_TABLE_STYLES, "tableStyles.xml"))
        entry("ppt/_rels/presentation.xml.rels", rels(*presRels.toTypedArray()))
        entry("docProps/core.xml", coreXml())
        entry("docProps/app.xml", appXml(n))
        entry("_rels/.rels", rels(
            Triple("rId1", REL_OFFICE_DOC, "ppt/presentation.xml"),
            Triple("rId2", REL_CORE, "docProps/core.xml"),
            Triple("rId3", REL_EXTENDED, "docProps/app.xml"),
        ))
        entry("[Content_Types].xml", contentTypesXml(n))
        zip.finish()
        zip.close()
        finished = true
        closed = true
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { zip.close() }
        if (!finished) out.delete()
    }

    private fun entry(name: String, xml: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ------------------------------------------------------------------------------------------------ parts

    private fun slideXml(n: Int, x: Long, y: Long, cx: Long, cy: Long) = XML_HEAD +
        """<p:sld $NS><p:cSld><p:spTree>$GROUP_PROPS""" +
        """<p:pic><p:nvPicPr><p:cNvPr id="2" name="Picture $n" descr="${esc(title)} $n"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
        """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
        """<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>""" +
        """</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    private fun presentationXml(n: Int) = buildString {
        append(XML_HEAD)
        append("""<p:presentation $NS saveSubsetFonts="1">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>""")
        for (i in 1..n) append("""<p:sldId id="${255 + i}" r:id="rId${i + 1}"/>""")
        append("""</p:sldIdLst><p:sldSz cx="$slideCx" cy="$slideCy"/><p:notesSz cx="6858000" cy="9144000"/>""")
        append("""<p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr>$LVL1_OTHER</p:defaultTextStyle>""")
        append("</p:presentation>")
    }

    private fun contentTypesXml(n: Int) = buildString {
        append(XML_HEAD)
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Default Extension="png" ContentType="image/png"/>""")
        append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
        fun o(part: String, type: String) = append("""<Override PartName="$part" ContentType="$type"/>""")
        o("/ppt/presentation.xml", "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml")
        o("/ppt/slideMasters/slideMaster1.xml", "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml")
        o("/ppt/slideLayouts/slideLayout1.xml", "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml")
        for (i in 1..n) o("/ppt/slides/slide$i.xml", "application/vnd.openxmlformats-officedocument.presentationml.slide+xml")
        o("/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml")
        o("/ppt/presProps.xml", "application/vnd.openxmlformats-officedocument.presentationml.presProps+xml")
        o("/ppt/viewProps.xml", "application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml")
        o("/ppt/tableStyles.xml", "application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml")
        o("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")
        o("/docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml")
        append("</Types>")
    }

    private fun coreXml(): String {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        return XML_HEAD +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" """ +
            """xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""" +
            """<dc:title>${esc(title)}</dc:title><dc:creator>Daftar</dc:creator><cp:lastModifiedBy>Daftar</cp:lastModifiedBy>""" +
            """<cp:revision>1</cp:revision><dcterms:created xsi:type="dcterms:W3CDTF">$now</dcterms:created>""" +
            """<dcterms:modified xsi:type="dcterms:W3CDTF">$now</dcterms:modified></cp:coreProperties>"""
    }

    private fun appXml(n: Int) = XML_HEAD +
        """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" """ +
        """xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""" +
        """<TotalTime>0</TotalTime><Words>0</Words><Application>Daftar</Application><PresentationFormat>Custom</PresentationFormat>""" +
        """<Paragraphs>0</Paragraphs><Slides>$n</Slides><Notes>0</Notes><HiddenSlides>0</HiddenSlides><MMClips>0</MMClips>""" +
        """<ScaleCrop>false</ScaleCrop><LinksUpToDate>false</LinksUpToDate><SharedDoc>false</SharedDoc>""" +
        """<HyperlinksChanged>false</HyperlinksChanged><AppVersion>16.0000</AppVersion></Properties>"""

    private fun rels(vararg r: Triple<String, String, String>) = buildString {
        append(XML_HEAD)
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for ((id, type, target) in r) append("""<Relationship Id="$id" Type="$type" Target="$target"/>""")
        append("</Relationships>")
    }

    companion object {
        private const val LONG_SIDE = 12192000L
        private const val MIN_SIDE = 914400L
        private const val MAX_SIDE = 51206400L

        private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val REL_OFFICE_DOC = "$R/officeDocument"
        private const val REL_CORE = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
        private const val REL_EXTENDED = "$R/extended-properties"
        private const val REL_MASTER = "$R/slideMaster"
        private const val REL_LAYOUT = "$R/slideLayout"
        private const val REL_SLIDE = "$R/slide"
        private const val REL_THEME = "$R/theme"
        private const val REL_IMAGE = "$R/image"
        private const val REL_PRES_PROPS = "$R/presProps"
        private const val REL_VIEW_PROPS = "$R/viewProps"
        private const val REL_TABLE_STYLES = "$R/tableStyles"

        private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""

        private const val GROUP_PROPS = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
            """<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""

        private const val FONT_MN = """<a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/>"""
        private const val FONT_MJ = """<a:latin typeface="+mj-lt"/><a:ea typeface="+mj-ea"/><a:cs typeface="+mj-cs"/>"""
        private const val TX1 = """<a:solidFill><a:schemeClr val="tx1"/></a:solidFill>"""
        private const val PPR = """algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1""""
        private const val LVL1_OTHER = """<a:lvl1pPr marL="0" $PPR><a:defRPr sz="1800" kern="1200">$TX1$FONT_MN</a:defRPr></a:lvl1pPr>"""

        private const val LAYOUT_XML = XML_HEAD +
            """<p:sldLayout $NS type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>$GROUP_PROPS</p:spTree></p:cSld>""" +
            """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

        private const val MASTER_XML = XML_HEAD +
            """<p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>""" +
            """<p:spTree>$GROUP_PROPS</p:spTree></p:cSld>""" +
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" """ +
            """accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""" +
            """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles>""" +
            """<p:titleStyle><a:lvl1pPr $PPR><a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:spcBef><a:spcPct val="0"/></a:spcBef>""" +
            """<a:buNone/><a:defRPr sz="4400" kern="1200">$TX1$FONT_MJ</a:defRPr></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr marL="228600" indent="-228600" $PPR><a:lnSpc><a:spcPct val="90000"/></a:lnSpc>""" +
            """<a:spcBef><a:spcPts val="1000"/></a:spcBef><a:buFont typeface="Arial"/><a:buChar char="&#8226;"/>""" +
            """<a:defRPr sz="2800" kern="1200">$TX1$FONT_MN</a:defRPr></a:lvl1pPr></p:bodyStyle>""" +
            """<p:otherStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr>$LVL1_OTHER</p:otherStyle>""" +
            """</p:txStyles></p:sldMaster>"""

        private const val LN = """cap="flat" cmpd="sng" algn="ctr"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:prstDash val="solid"/><a:miter lim="800000"/></a:ln>"""

        private const val THEME_XML = XML_HEAD +
            """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Daftar"><a:themeElements>""" +
            """<a:clrScheme name="Daftar">""" +
            """<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="1F2937"/></a:dk2><a:lt2><a:srgbClr val="F7F6F2"/></a:lt2>""" +
            """<a:accent1><a:srgbClr val="3B82F6"/></a:accent1><a:accent2><a:srgbClr val="F97316"/></a:accent2>""" +
            """<a:accent3><a:srgbClr val="10B981"/></a:accent3><a:accent4><a:srgbClr val="EF4444"/></a:accent4>""" +
            """<a:accent5><a:srgbClr val="8B6CE0"/></a:accent5><a:accent6><a:srgbClr val="F2C94C"/></a:accent6>""" +
            """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
            """<a:fontScheme name="Daftar">""" +
            """<a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>""" +
            """<a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
            """<a:fmtScheme name="Daftar"><a:fillStyleLst>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:tint val="60000"/></a:schemeClr></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:shade val="80000"/></a:schemeClr></a:solidFill>""" +
            """</a:fillStyleLst><a:lnStyleLst>""" +
            """<a:ln w="6350" $LN<a:ln w="12700" $LN<a:ln w="19050" $LN""" +
            """</a:lnStyleLst><a:effectStyleLst>""" +
            """<a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle>""" +
            """</a:effectStyleLst><a:bgFillStyleLst>""" +
            """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:tint val="95000"/></a:schemeClr></a:solidFill>""" +
            """<a:solidFill><a:schemeClr val="phClr"><a:shade val="90000"/></a:schemeClr></a:solidFill>""" +
            """</a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

        private const val PRES_PROPS_XML = XML_HEAD + """<p:presentationPr $NS/>"""

        private const val VIEW_PROPS_XML = XML_HEAD +
            """<p:viewPr $NS><p:normalViewPr><p:restoredLeft sz="15620"/><p:restoredTop sz="94660"/></p:normalViewPr>""" +
            """<p:gridSpacing cx="76200" cy="76200"/></p:viewPr>"""

        private const val TABLE_STYLES_XML = XML_HEAD +
            """<a:tblStyleLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" def="{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"/>"""

        /** XML-escapes text and drops characters that are illegal in XML 1.0. */
        fun esc(s: String): String = buildString(s.length) {
            for (ch in s) when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\'' -> append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> {}
                else -> append(ch)
            }
        }
    }
}
