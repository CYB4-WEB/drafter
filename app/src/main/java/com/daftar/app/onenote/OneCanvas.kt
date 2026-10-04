package com.daftar.app.onenote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/** dp per pt at 100 % zoom (96 dpi, like OneNote / Word at 100 %). */
internal const val DP_PER_PT = 4f / 3f

/** Search matches on the current page: paragraph → ranges, and the focused one. */
internal class PageHits(val ranges: Map<OnePara, List<IntRange>>, val current: Pair<OnePara, IntRange>?) {
    companion object { val None = PageHits(emptyMap(), null) }
}

private val PaperInk = Color(0xFF1F2937)
private val PaperMuted = Color(0xFF6B7280)
private val PaperLine = Color(0xFFD1D5DB)
private val HitColor = Color(0x66FACC15)
private val HitCurrent = Color(0xFFF59E0B)

/**
 * One OneNote page drawn at [zoom] (1 = 100 %): title + date, then every item at its stored position, on white paper.
 * The surface is at least [minW] × [minH] dp so the paper fills the viewport.
 */
@Composable
internal fun OnePageCanvas(
    page: OnePage,
    zoom: Float,
    bitmaps: OneBitmaps,
    hits: PageHits,
    minW: Float,
    minH: Float,
    onLink: (String) -> Unit,
    onFile: (OneAttachment) -> Unit,
) {
    val k = zoom * DP_PER_PT
    val density = LocalDensity.current
    // Page geometry is not UI text: font scale 1 keeps it proportional (the app's text-size setting must not reflow notes).
    val paperDensity = remember(density.density) { Density(density.density, 1f) }
    CompositionLocalProvider(LocalDensity provides paperDensity, LocalLayoutDirection provides LayoutDirection.Ltr) {
        val positions = remember(page) {
            ArrayList<Pair<Float, Float>>().apply {
                add(page.titleX to page.titleY)
                page.items.forEach { add(it.x.coerceAtLeast(0f) to it.y.coerceAtLeast(0f)) }
            }
        }
        Layout(
            content = {
                TitleBlock(page, k)
                for (item in page.items) when (item) {
                    is OneOutline -> OutlineView(item, k, bitmaps, hits, onLink, onFile)
                    is OneImageItem -> ImageView(item.image, k, bitmaps, 0f)
                    is OneInkItem -> InkView(item.ink, k, inline = false)
                    is OneFileItem -> FileChip(item.file, k, onFile)
                }
            },
            modifier = Modifier.background(Color.White).border(1.dp, D.c.line),
        ) { measurables, constraints ->
            val pxPerPt = k * density.density
            val placeables = measurables.mapIndexed { i, m ->
                val item = page.items.getOrNull(i - 1)
                val c = when {
                    i == 0 -> Constraints(maxWidth = (600f * pxPerPt).roundToInt())
                    item is OneOutline && item.width > 20f -> Constraints.fixedWidth((item.width * pxPerPt).roundToInt().coerceAtLeast(1))
                    item is OneOutline -> Constraints(maxWidth = (AUTO_OUTLINE_PT * pxPerPt).roundToInt())
                    else -> Constraints()
                }
                m.measure(c)
            }
            var maxR = 0; var maxB = 0
            placeables.forEachIndexed { i, p ->
                val (x, y) = positions.getOrElse(i) { 0f to 0f }
                maxR = max(maxR, (x * pxPerPt).roundToInt() + p.width)
                maxB = max(maxB, (y * pxPerPt).roundToInt() + p.height)
            }
            val w = max(max((minW * density.density).roundToInt(), maxR + (48f * pxPerPt).roundToInt()), constraints.minWidth)
            val h = max(max((minH * density.density).roundToInt(), maxB + (96f * pxPerPt).roundToInt()), constraints.minHeight)
            layout(w, h) {
                placeables.forEachIndexed { i, p ->
                    val (x, y) = positions.getOrElse(i) { 0f to 0f }
                    p.place((x * pxPerPt).roundToInt(), (y * pxPerPt).roundToInt())
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(page: OnePage, k: Float) {
    Column(Modifier.widthIn(min = (300f * k).dp)) {
        if (page.title.isNotBlank()) {
            val rtl = remember(page.title) { OneLayout.isRtl(page.title) }
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Text(page.title, style = TextStyle(fontSize = (20f * k).sp, color = PaperInk, fontWeight = FontWeight.Light))
            }
        }
        Spacer(Modifier.height((4f * k).dp))
        Box(Modifier.width((300f * k).dp).height(1.dp).background(PaperLine))
        if (page.created > 0) {
            val text = remember(page.created) {
                val d = Date(page.created)
                DateFormat.getDateInstance(DateFormat.FULL).format(d) + "    " + DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
            }
            Text(text, style = TextStyle(fontSize = (9f * k).sp, color = PaperMuted), modifier = Modifier.padding(top = (3f * k).dp))
        }
    }
}

@Composable
private fun OutlineView(o: OneOutline, k: Float, bitmaps: OneBitmaps, hits: PageHits, onLink: (String) -> Unit, onFile: (OneAttachment) -> Unit) {
    Column { Blocks(o.blocks, k, bitmaps, hits, onLink, onFile, if (o.width > 20f) o.width else AUTO_OUTLINE_PT) }
}

@Composable
private fun Blocks(blocks: List<OneBlock>, k: Float, bitmaps: OneBitmaps, hits: PageHits, onLink: (String) -> Unit, onFile: (OneAttachment) -> Unit, widthPt: Float) {
    for (b in blocks) {
        val indent = (b.indent.coerceIn(0, 12) * INDENT_PT)
        Row(Modifier.padding(start = (indent * k).dp)) {
            when (b) {
                is OnePara -> ParaView(b, k, hits, onLink)
                is OneTable -> TableView(b, k, bitmaps, hits, onLink, onFile, widthPt - indent)
                is OneImageBlock -> ImageView(b.image, k, bitmaps, widthPt - indent)
                is OneInkBlock -> InkView(b.ink, k, inline = true)
                is OneFileBlock -> FileChip(b.file, k, onFile)
            }
        }
    }
}

private fun familyOf(font: String?, mono: Boolean): FontFamily? {
    if (mono) return FontFamily.Monospace
    val f = font?.lowercase() ?: return null
    return when {
        f.contains("consolas") || f.contains("courier") || f.contains("mono") -> FontFamily.Monospace
        f.contains("times") || f.contains("cambria") || f.contains("georgia") || f.contains("garamond") -> FontFamily.Serif
        else -> null
    }
}

internal fun annotated(p: OnePara, look: ParaLook, hits: PageHits, k: Float, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    for (r in p.runs) {
        val s = length
        append(r.text)
        val e = length
        if (s == e) continue
        val col = when {
            r.link != null -> Color(LINK_COLOR)
            r.color != null -> Color(r.color)
            look.color != null -> Color(look.color)
            else -> Color.Unspecified
        }
        addStyle(
            SpanStyle(
                color = col,
                fontWeight = if (r.bold || look.bold) FontWeight.Bold else null,
                fontStyle = if (r.italic || look.italic) FontStyle.Italic else null,
                textDecoration = when {
                    r.underline && r.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    r.underline -> TextDecoration.Underline
                    r.strike -> TextDecoration.LineThrough
                    else -> null
                },
                fontSize = if (r.size > 0f) ((if (r.superscript || r.subscript) r.size * 0.7f else r.size) * k).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                background = r.highlight?.let { Color(it) } ?: Color.Unspecified,
                baselineShift = when { r.superscript -> BaselineShift.Superscript; r.subscript -> BaselineShift.Subscript; else -> null },
                fontFamily = familyOf(r.font, look.mono),
            ), s, e,
        )
        r.link?.let { url ->
            addLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Color(LINK_COLOR), textDecoration = TextDecoration.Underline))) { onLink(url) }, s, e)
        }
    }
    hits.ranges[p]?.forEach { rg ->
        val a = rg.first.coerceIn(0, length); val b = (rg.last + 1).coerceIn(0, length)
        if (a < b) addStyle(SpanStyle(background = if (hits.current?.first === p && hits.current.second == rg) HitCurrent else HitColor), a, b)
    }
}

@Composable
private fun ParaView(p: OnePara, k: Float, hits: PageHits, onLink: (String) -> Unit) {
    val look = remember(p.style) { paraLook(p.style) }
    val text = remember(p, hits, k) { annotated(p, look, hits, k, onLink) }
    val base = p.runs.firstOrNull { it.text.isNotBlank() && it.size > 0f }?.size ?: look.size
    val style = TextStyle(
        fontSize = (look.size * k).sp,
        color = look.color?.let { Color(it) } ?: PaperInk,
        textAlign = when (p.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start },
        fontFamily = if (look.mono) FontFamily.Monospace else null,
    )
    CompositionLocalProvider(LocalLayoutDirection provides if (p.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(Modifier.padding(bottom = (2f * k).dp)) {
            if (p.list != null) {
                val lw = LABEL_PT + (p.list.length - 2).coerceAtLeast(0) * 5f
                Text(p.list, style = style.copy(fontSize = (base * k).sp, textAlign = TextAlign.Start), modifier = Modifier.width((lw * k).dp), maxLines = 1)
            }
            Text(if (text.isEmpty()) AnnotatedString(" ") else text, style = style, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun TableView(t: OneTable, k: Float, bitmaps: OneBitmaps, hits: PageHits, onLink: (String) -> Unit, onFile: (OneAttachment) -> Unit, availPt: Float) {
    val cols = t.rows.maxOfOrNull { it.size } ?: return
    if (cols == 0) return
    val given = t.columnWidths.filter { it > 0f }
    val widths = if (given.size >= cols) t.columnWidths.take(cols).map { it.coerceAtLeast(24f) } else List(cols) { (availPt / cols).coerceAtLeast(40f) }
    val line = if (t.borders) PaperLine else Color.Transparent
    Column(Modifier.padding(vertical = (2f * k).dp)) {
        for (row in t.rows) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                for (ci in 0 until cols) {
                    val w = widths.getOrElse(ci) { widths.last() }
                    Box(Modifier.width((w * k).dp).fillMaxHeight().border(0.5.dp, line).padding((4f * k).dp)) {
                        Column { row.getOrNull(ci)?.let { Blocks(it, k, bitmaps, hits, onLink, onFile, w - 8f) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageView(img: OneImage, k: Float, bitmaps: OneBitmaps, maxWidthPt: Float) {
    val density = LocalDensity.current
    val blob = img.data
    val natural by produceState<IntArray?>(null, blob) {
        value = if (blob == null || (img.widthPt > 0f && img.heightPt > 0f)) null else withContext(Dispatchers.IO) { bitmaps.size(blob) }
    }
    var w = img.widthPt; var h = img.heightPt
    if (w <= 0f || h <= 0f) {
        val n = natural
        if (n != null) { w = n[0] * 0.75f; h = n[1] * 0.75f } else { w = 160f; h = 48f }
    }
    if (maxWidthPt > 0f && w > maxWidthPt) { h *= maxWidthPt / w; w = maxWidthPt }
    val maxPx = (max(w, h) * k * density.density).roundToInt().coerceIn(64, 2048)
    val bmp by produceState(blob?.let { bitmaps.cached(it, maxPx) }, blob, maxPx) {
        if (blob != null && value == null) value = withContext(Dispatchers.IO) { bitmaps.get(blob, maxPx) }
        else if (blob != null) { val b = withContext(Dispatchers.IO) { bitmaps.get(blob, maxPx) }; if (b != null) value = b }
    }
    val mod = Modifier.size((w * k).dp, (h * k).dp)
    val b = bmp
    if (b != null && !b.isRecycled) {
        Image(remember(b) { b.asImageBitmap() }, img.alt, mod, contentScale = ContentScale.FillBounds)
    } else {
        Box(mod.background(Color(0xFFF3F4F6)).border(0.5.dp, PaperLine), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding((4f * k).dp)) {
                Icon(Icons.Rounded.BrokenImage, null, tint = PaperMuted, modifier = Modifier.size((14f * k).dp))
                val label = img.alt ?: img.name
                if (label != null) Text(label, style = TextStyle(fontSize = (9f * k).sp, color = PaperMuted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = (4f * k).dp))
            }
        }
    }
}

@Composable
private fun InkView(ink: OneInk, k: Float, inline: Boolean) {
    val b = ink.bounds
    val paths = remember(ink) { ink.strokes.map { s -> s to OneLayout.inkPath(s, if (inline) -b[0] else 0f, if (inline) -b[1] else 0f).asComposePath() } }
    val wPt = if (inline) b[2] - b[0] else b[2]
    val hPt = if (inline) b[3] - b[1] else b[3]
    Canvas(Modifier.size((wPt.coerceAtLeast(1f) * k).dp, (hPt.coerceAtLeast(1f) * k).dp)) {
        val s = k * density
        scale(s, s, pivot = Offset.Zero) {
            for ((st, path) in paths) {
                drawPath(path, Color(st.color), style = Stroke(width = st.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun FileChip(f: OneAttachment, k: Float, onFile: (OneAttachment) -> Unit) {
    Row(
        Modifier.padding(vertical = (2f * k).dp).background(Color(0xFFF3F4F6), RoundedCornerShape((6f * k).dp))
            .border(0.5.dp, PaperLine, RoundedCornerShape((6f * k).dp)).clickable { onFile(f) }
            .padding(horizontal = (8f * k).dp, vertical = (5f * k).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AttachFile, null, tint = PaperMuted, modifier = Modifier.size((14f * k).dp))
        Text(f.name, style = TextStyle(fontSize = (10f * k).sp, color = PaperInk), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = (4f * k).dp).widthIn(max = (240f * k).dp))
    }
}
