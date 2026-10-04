package com.daftar.app.word

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarColors
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** sp per document point in the read layout at 100% (11pt body → ~15sp, DESIGN body size). */
internal const val SP_PER_PT = 1.4f
/** dp per point for indents / table widths in the read layout. */
internal const val DP_PER_PT = 1.25f
/** dp per point for pictures in the read layout (96-dpi pixels, slightly enlarged for the reading column). */
internal const val IMG_DP_PER_PT = 1.4667f
internal const val MAX_TEXT_WIDTH = 760

/** One find hit: paragraph [pid] (or sheet row/column), char range, top-level block index for scrolling. */
internal class Match(val pid: Int, val start: Int, val end: Int, val top: Int, val col: Int = -1)

/** Search state shared by all paragraphs. */
internal class Hits(val byPid: Map<Int, List<IntRange>>, val current: Match?) {
    companion object { val None = Hits(emptyMap(), null) }
}

/** Colours of a printed page (the print layout is always paper-white, like Word's print view). */
private val PaperInk = Color(0xFF1F2937)
private val PaperLink = Color(0xFF2563EB)
private val PaperRule = Color(0xFFB0B5BD)
private val PaperCode = Color(0xFFF3F4F6)
private val PaperQuote = Color(0xFFD1D5DB)
private val PaperBorder = Color(0xFF4B5563)
private val PaperHit = Color(0xFF3B82F6)

internal fun openLink(ctx: Context, url: String) {
    val uri = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) Uri.parse(url) else Uri.parse("https://$url")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, ctx.getString(R.string.no_app_found)) }
}

/** Document colours are designed for white paper: keep them readable on the dark theme. */
internal fun docColor(col: Color, c: DaftarColors): Color {
    if (!c.dark) return if (col.luminance() > 0.92f) c.ink else col
    val maxC = max(col.red, max(col.green, col.blue)); val minC = min(col.red, min(col.green, col.blue))
    val grey = maxC - minC < 0.12f
    return when {
        grey && col.luminance() < 0.5f -> c.ink
        col.luminance() < 0.35f -> lerp(col, Color.White, 0.5f)
        else -> col
    }
}

internal fun docBackground(col: Color, c: DaftarColors): Color = if (c.dark) col.copy(alpha = 0.3f) else col

/**
 * Annotated text for characters [start, end) of [p]. [paper] = print layout (fixed paper colours, tabs as em spaces
 * exactly like the paginator); otherwise theme-adapted colours for the read layout.
 */
internal fun buildText(
    ctx: Context, p: DocBlock.Para, start: Int, end: Int, c: DaftarColors, paper: Boolean, ranges: List<IntRange>?, cur: Match?,
): AnnotatedString = buildAnnotatedString {
    val raw = p.text.substring(start, end)
    append(if (paper) raw.replace('\t', ' ') else raw)
    val len = end - start
    val linkCol = if (paper) PaperLink else c.accent
    val linkStyle = TextLinkStyles(SpanStyle(color = linkCol, textDecoration = TextDecoration.Underline))
    for (s in p.spans) {
        val a = max(s.start, start) - start
        val b = min(s.end, end) - start
        if (a >= b || b > len) continue
        val f = s.fmt
        val deco = when {
            f.underline && f.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            f.underline -> TextDecoration.Underline
            f.strike -> TextDecoration.LineThrough
            else -> null
        }
        val ratio = (f.sizePt / p.basePt).let { if (paper) it else it.coerceIn(0.4f, 3f) }
        val sizeEm = when {
            f.vert != 0 -> (ratio * 0.7f).em
            abs(ratio - 1f) > 0.001f -> ratio.em
            else -> TextUnit.Unspecified
        }
        val color = f.color?.let { col ->
            if (paper) Color(col).let { if (it.luminance() > 0.92f) PaperInk else it } else docColor(Color(col), c)
        } ?: Color.Unspecified
        addStyle(
            SpanStyle(
                color = color,
                fontSize = sizeEm,
                fontWeight = if (f.bold) FontWeight.Bold else null,
                fontStyle = if (f.italic) FontStyle.Italic else null,
                fontFamily = if (f.mono) FontFamily.Monospace else null,
                textDecoration = deco,
                background = f.background?.let { if (paper) Color(it) else docBackground(Color(it), c) } ?: Color.Unspecified,
                baselineShift = when (f.vert) { 1 -> BaselineShift.Superscript; 2 -> BaselineShift.Subscript; else -> null },
            ),
            a, b,
        )
        if (s.link != null) {
            val url = s.link
            addLink(LinkAnnotation.Url(url, linkStyle) { openLink(ctx, url) }, a, b)
        }
    }
    val hit = if (paper) PaperHit else c.accent
    ranges?.forEach { r ->
        val a = max(r.first, start) - start
        val b = min(r.last + 1, end) - start
        if (a < b) {
            val isCur = cur != null && cur.start == r.first
            addStyle(SpanStyle(background = hit.copy(alpha = if (isCur) 0.55f else 0.22f)), a, b)
        }
    }
}

private fun alignOf(a: Int) = when (a) { 1 -> TextAlign.Center; 2 -> TextAlign.End; 3 -> TextAlign.Justify; else -> TextAlign.Start }

// ================================================================== read (web) layout

@Composable
internal fun BlockView(b: DocBlock, images: DocxImages, scale: Float, hits: Hits, avail: Dp, allowScroll: Boolean) {
    when (b) {
        is DocBlock.Para -> ParaView(b, scale, hits)
        is DocBlock.Image -> ImageBlockView(b, images, scale)
        is DocBlock.Table -> TableView(b, images, scale, hits, avail, allowScroll)
        DocBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).height(1.dp).background(D.c.line))
        DocBlock.PageBreak -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).height(1.dp).background(D.c.line))
    }
}

@Composable
internal fun ParaView(p: DocBlock.Para, scale: Float, hits: Hits) {
    val c = D.c
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val ranges = hits.byPid[p.pid]
    val cur = hits.current?.takeIf { it.pid == p.pid && it.col < 0 }
    val text = remember(p, c, ranges, cur) { buildText(ctx, p, 0, p.text.length, c, false, ranges, cur) }
    val baseSp = (p.basePt * SP_PER_PT * scale).let { if (p.heading > 0) min(it, 36f * scale) else it }
    val lineH = if (p.heading > 0) 1.25f else max(1.3f, 1.25f * p.lineMult)
    val u = DP_PER_PT * scale
    val hang = p.hangPt * u
    val startPad = p.boxStartPt * u
    val indent = with(density) {
        when {
            p.firstLine > 0f -> TextIndent(firstLine = (p.firstLine * u).dp.toSp())
            hang > 0f -> TextIndent(firstLine = 0.sp, restLine = hang.dp.toSp())
            else -> TextIndent.None
        }
    }
    val style = TextStyle(
        color = if (p.box == BOX_QUOTE) c.muted else c.ink,
        fontSize = baseSp.sp,
        lineHeight = lineH.em,
        textAlign = alignOf(p.align),
        textDirection = if (p.rtl) TextDirection.Rtl else TextDirection.Content,
        textIndent = indent,
    )
    val top = (p.before * scale).let { if (p.heading > 0) max(it, 14f * scale) else it }
    CompositionLocalProvider(LocalLayoutDirection provides if (p.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        val boxMod = when (p.box) {
            BOX_CODE -> Modifier.background(c.surfaceAlt, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)
            BOX_QUOTE -> Modifier.quoteBar(c.line).padding(start = 14.dp, top = 2.dp, bottom = 2.dp)
            else -> Modifier
        }
        Box(Modifier.fillMaxWidth().padding(start = startPad.dp, end = (p.indEnd * u).dp, top = top.dp, bottom = (p.after * scale).dp)) {
            Row(Modifier.fillMaxWidth().then(boxMod)) {
                if (p.marker != null) {
                    val mf = p.markerFmt
                    Text(
                        p.marker,
                        style = style.copy(
                            textAlign = TextAlign.Start, textIndent = TextIndent.None,
                            fontWeight = if (mf?.bold == true) FontWeight.Bold else null,
                            color = mf?.color?.let { docColor(Color(it), c) } ?: c.ink,
                        ),
                        maxLines = 1,
                        modifier = Modifier.width((p.markerWidth * u).dp),
                    )
                }
                Text(text, style = style, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A 3dp bar on the start side (quotes). */
private fun Modifier.quoteBar(color: Color): Modifier = this.drawBehind {
    val w = 3.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRect(color, Offset(x, 0f), Size(w, size.height))
}

@Composable
private fun ImageBlockView(b: DocBlock.Image, images: DocxImages, scale: Float) {
    val density = LocalDensity.current
    val w = (b.widthPt * IMG_DP_PER_PT * scale).coerceAtLeast(8f)
    val h = (b.heightPt * IMG_DP_PER_PT * scale).coerceAtLeast(8f)
    val targetPx = with(density) { min(w, MAX_TEXT_WIDTH * 1.5f).dp.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, b.entry, targetPx) {
        value = withContext(Dispatchers.IO) { images.load(b, targetPx) }
    }
    val align = when (b.align) { 1 -> Alignment.Center; 2 -> Alignment.CenterEnd; else -> Alignment.CenterStart }
    CompositionLocalProvider(LocalLayoutDirection provides if (b.rtl) LayoutDirection.Rtl else LocalLayoutDirection.current) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = align) {
            Box(Modifier.widthIn(max = w.dp).fillMaxWidth().aspectRatio(w / h), contentAlignment = Alignment.Center) {
                PictureOrPlaceholder(bmp, b.alt, D.c.surfaceAlt, D.c.muted)
            }
        }
    }
}

@Composable
private fun PictureOrPlaceholder(bmp: ImageBitmap?, alt: String, bg: Color, tint: Color) {
    val desc = alt.ifBlank { stringResource(R.string.word_image) }
    if (bmp != null) Image(bmp, desc, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    else Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Image, desc, tint = tint)
    }
}

@Composable
private fun TableView(t: DocBlock.Table, images: DocxImages, scale: Float, hits: Hits, avail: Dp, allowScroll: Boolean) {
    val c = D.c
    val lineColor = c.muted.copy(alpha = 0.45f)
    val cols = max(t.grid.size, t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1).coerceAtLeast(1)
    val natural = if (t.grid.isNotEmpty()) t.grid.sum() * DP_PER_PT * scale else 0f
    val scroll = allowScroll && natural > avail.value * 1.08f
    val tableWidth = if (scroll) natural.dp else avail
    val outer = if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    CompositionLocalProvider(LocalLayoutDirection provides if (t.rtl) LayoutDirection.Rtl else LocalLayoutDirection.current) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).clipToBounds().then(outer)) {
            Column(
                (if (scroll) Modifier.width(tableWidth) else Modifier.fillMaxWidth())
                    .then(if (t.borders) Modifier.border(0.5.dp, lineColor) else Modifier),
            ) {
                for (row in t.rows) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        var col = 0
                        for (cell in row.cells) {
                            val weight = if (t.grid.size >= col + cell.span && t.grid.isNotEmpty()) {
                                t.grid.subList(col, col + cell.span).sum().coerceAtLeast(1f)
                            } else cell.span.toFloat() * (if (t.grid.isNotEmpty()) t.grid.average().toFloat() else 1f)
                            col += cell.span
                            val cellWidth = tableWidth * (weight / (if (t.grid.isNotEmpty()) max(t.grid.sum(), 1f) else cols.toFloat()))
                            Column(
                                Modifier.weight(weight).fillMaxHeight()
                                    .then(cell.fill?.let { Modifier.background(docBackground(Color(it), c)) } ?: Modifier)
                                    .then(if (t.borders) Modifier.border(0.5.dp, lineColor) else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                for (b in cell.blocks) BlockView(b, images, scale, hits, (cellWidth - 16.dp).coerceAtLeast(24.dp), allowScroll = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================================================================== print layout

/** Scale of a printed page on screen. [k] = pixels per point. */
internal class PaperScale(val k: Float, private val density: Float, private val fontScale: Float) {
    fun dp(pt: Float): Dp = (pt * k / density).dp
    fun sp(pt: Float) = (pt * k / (density * fontScale)).sp
    /** Exact pixel line pitch, floored so the drawn text is never taller than the paginator predicted. */
    fun lineSp(p: DocBlock.Para) = (floor(lineHeight(p) * k) / (density * fontScale)).sp
}

/** One page sheet: white paper, 1dp line border, margins, slices stacked in order. */
@Composable
internal fun PrintPage(page: LaidPage, spec: PageSpec, scale: PaperScale, images: DocxImages, hits: Hits, modifier: Modifier = Modifier) {
    val c = D.c
    Box(
        modifier.width(scale.dp(spec.w)).heightIn(min = scale.dp(spec.h))
            .background(Color.White).border(1.dp, c.line),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                Modifier.fillMaxWidth().absolutePadding(left = scale.dp(spec.left), right = scale.dp(spec.right),
                    top = scale.dp(spec.top), bottom = scale.dp(spec.bottom)),
            ) {
                for (s in page.slices) {
                    when (s) {
                        is TextSlice -> PrintPara(s.para, s.start, s.end, s.first, s.gap, s.after, scale, hits)
                        is ImageSlice -> PrintImage(s.image, s.w, s.h, s.gap, s.after, scale, images)
                        is TableSlice -> PrintTable(s.table, s.rows, s.rowHeights, s.colWidths, s.gap, s.after, scale, images, hits)
                        is RuleSlice -> Box(Modifier.padding(top = scale.dp(s.gap), bottom = scale.dp(s.after)).fillMaxWidth()
                            .height(scale.dp(0.75f)).background(PaperRule))
                    }
                }
            }
        }
    }
}

/** Characters [start, end) of a paragraph on paper (same geometry as the paginator / PDF). */
@Composable
private fun PrintPara(p: DocBlock.Para, start: Int, end: Int, first: Boolean, gap: Float, after: Float, scale: PaperScale, hits: Hits) {
    val c = D.c
    val ctx = LocalContext.current
    val ranges = hits.byPid[p.pid]
    val cur = hits.current?.takeIf { it.pid == p.pid && it.col < 0 }
    val text = remember(p, start, end, ranges, cur) { buildText(ctx, p, start, end, c, true, ranges, cur) }
    val hang = p.hangPt
    val firstInd = if (first) max(p.firstLine, 0f) else hang
    val style = TextStyle(
        color = PaperInk,
        fontSize = scale.sp(p.basePt),
        lineHeight = scale.lineSp(p),
        textAlign = alignOf(p.align),
        textDirection = if (p.rtl) TextDirection.Rtl else TextDirection.Content,
        textIndent = if (firstInd > 0f || hang > 0f) TextIndent(scale.sp(firstInd), scale.sp(hang)) else TextIndent.None,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Proportional, LineHeightStyle.Trim.None),
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        textMotion = TextMotion.Animated,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
    val padV = boxPadV(p.box)
    CompositionLocalProvider(LocalLayoutDirection provides if (p.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        val deco = when (p.box) {
            BOX_CODE -> Modifier.background(PaperCode, RoundedCornerShape(scale.dp(3f)))
            BOX_QUOTE -> Modifier.drawBehind {
                val w = 2.5f * scale.k
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                drawRect(PaperQuote, Offset(x, 0f), Size(w, size.height))
            }
            else -> Modifier
        }
        Box(Modifier.fillMaxWidth().padding(start = scale.dp(p.boxStartPt), end = scale.dp(p.indEnd), top = scale.dp(gap), bottom = scale.dp(after))) {
            Row(Modifier.fillMaxWidth().then(deco).padding(start = scale.dp(boxPadStart(p.box)), end = scale.dp(boxPadEnd(p.box)),
                top = scale.dp(padV), bottom = scale.dp(padV))) {
                if (p.marker != null) {
                    if (first) {
                        val mf = p.markerFmt
                        Text(
                            p.marker,
                            style = style.copy(
                                textAlign = TextAlign.Start, textIndent = TextIndent.None,
                                fontSize = scale.sp(mf?.sizePt ?: p.basePt),
                                fontWeight = if (mf?.bold == true) FontWeight.Bold else null,
                                color = mf?.color?.let { col -> Color(col).let { if (it.luminance() > 0.92f) PaperInk else it } } ?: PaperInk,
                            ),
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                            modifier = Modifier.width(scale.dp(p.markerWidth)),
                        )
                    } else Spacer(Modifier.width(scale.dp(p.markerWidth)))
                }
                Text(text, style = style, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PrintImage(img: DocBlock.Image, w: Float, h: Float, gap: Float, after: Float, scale: PaperScale, images: DocxImages) {
    val targetPx = (max(w, h) * scale.k).toInt().coerceIn(32, 2400)
    val bmp by produceState<ImageBitmap?>(null, img.entry, targetPx) {
        value = withContext(Dispatchers.IO) { images.load(img, targetPx) }
    }
    val align = when (img.align) { 1 -> Alignment.TopCenter; 2 -> Alignment.TopEnd; else -> Alignment.TopStart }
    CompositionLocalProvider(LocalLayoutDirection provides if (img.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().padding(top = scale.dp(gap), bottom = scale.dp(after)), contentAlignment = align) {
            Box(Modifier.size(scale.dp(w), scale.dp(h))) { PictureOrPlaceholder(bmp, img.alt, PaperCode, Color(0xFF9CA3AF)) }
        }
    }
}

@Composable
private fun PrintTable(
    t: DocBlock.Table, rows: IntArray, heights: FloatArray, cols: FloatArray, gap: Float, after: Float,
    scale: PaperScale, images: DocxImages, hits: Hits,
) {
    val stroke = scale.dp(0.5f).coerceAtLeast(0.5.dp)
    CompositionLocalProvider(LocalLayoutDirection provides if (t.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().padding(top = scale.dp(gap), bottom = scale.dp(after))) {
            Column {
                for (k in rows.indices) {
                    val row = t.rows[rows[k]]
                    Row(Modifier.height(IntrinsicSize.Min).heightIn(min = scale.dp(heights[k]))) {
                        row.cells.forEachIndexed { i, cell ->
                            val cw = cellWidth(row, i, cols)
                            Box(
                                Modifier.width(scale.dp(cw)).fillMaxHeight()
                                    .then(cell.fill?.let { Modifier.background(Color(it)) } ?: Modifier)
                                    .then(if (t.borders) Modifier.border(stroke, PaperBorder) else Modifier)
                                    .padding(horizontal = scale.dp(CELL_PAD_H), vertical = scale.dp(CELL_PAD_V)),
                            ) {
                                if (!cell.merged) PrintFlow(cell.blocks, cw - 2 * CELL_PAD_H, scale, images, hits)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Blocks stacked in a table cell, same spacing as Paginator.flowHeight. */
@Composable
private fun PrintFlow(blocks: List<DocBlock>, width: Float, scale: PaperScale, images: DocxImages, hits: Hits) {
    Column(Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is DocBlock.Para -> PrintPara(b, 0, b.text.length, true, if (i == 0) 0f else b.before, b.after, scale, hits)
                is DocBlock.Image -> {
                    val iw = min(max(b.widthPt, 4f), width)
                    PrintImage(b, iw, b.heightPt * (iw / max(b.widthPt, 4f)), Flow.IMAGE_PAD, Flow.IMAGE_PAD, scale, images)
                }
                is DocBlock.Table -> {
                    val cols = tableColumns(b, width)
                    PrintTable(b, IntArray(b.rows.size) { it }, FloatArray(b.rows.size), cols, Flow.NESTED_TABLE_GAP, Flow.NESTED_TABLE_GAP, scale, images, hits)
                }
                DocBlock.Divider, DocBlock.PageBreak -> Box(Modifier.padding(vertical = scale.dp(Flow.RULE_PAD)).fillMaxWidth()
                    .height(scale.dp(0.75f)).background(PaperRule))
            }
        }
    }
}

// ================================================================== CSV / TSV grid

/** Spreadsheet-like grid: row numbers, sticky header row, horizontal scroll, lazy rows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SheetView(sheet: Sheet, zoom: Float, listState: LazyListState, hScroll: ScrollState, hits: Map<Int, Map<Int, List<IntRange>>>, current: Match?) {
    val c = D.c
    val density = LocalDensity.current.density
    val fontSp = (13f * zoom).sp
    val charDp = 7.6f * zoom
    val numW = ((sheet.rows.size.toString().length * 8f + 20f) * zoom).dp
    val colW = remember(sheet, zoom, density) {
        val raw = sheet.charWidths.map { it.coerceIn(3, 40) * charDp + 20f * zoom }
        // Compose constraints top out around 262k px: very wide sheets get proportionally narrower columns.
        val maxTotal = 200_000f / density - numW.value
        val k = if (raw.sum() > maxTotal) maxTotal / raw.sum() else 1f
        raw.map { (it * k).dp }
    }
    val total = colW.fold(numW) { a, b -> a + b }
    val cellPad = (8f * zoom).dp
    CompositionLocalProvider(LocalLayoutDirection provides if (sheet.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().background(c.surface).horizontalScroll(hScroll)) {
            LazyColumn(Modifier.width(total).fillMaxHeight(), state = listState) {
                val header = sheet.rows.firstOrNull()
                if (header != null) stickyHeader(key = "h") {
                    SheetRow(0, header, sheet.cols, colW, numW, fontSp, cellPad, header = true, hits[0], current)
                }
                items(count = (sheet.rows.size - 1).coerceAtLeast(0), key = { it + 1 }) { i ->
                    SheetRow(i + 1, sheet.rows[i + 1], sheet.cols, colW, numW, fontSp, cellPad, header = false, hits[i + 1], current)
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    index: Int, cells: Array<String>, cols: Int, colW: List<Dp>, numW: Dp, fontSp: TextUnit, pad: Dp, header: Boolean,
    rowHits: Map<Int, List<IntRange>>?, current: Match?,
) {
    val c = D.c
    val bg = if (header) c.surfaceAlt else c.surface
    Row(Modifier.height(IntrinsicSize.Min).background(bg)) {
        Box(Modifier.width(numW).fillMaxHeight().background(c.surfaceAlt).border(0.5.dp, c.line).padding(horizontal = 4.dp, vertical = pad / 2),
            contentAlignment = Alignment.Center) {
            Text(if (header) "" else index.toString(), style = MaterialTheme.typography.labelMedium.copy(fontSize = fontSp * 0.85f), color = c.muted, maxLines = 1)
        }
        for (col in 0 until cols) {
            val s = cells.getOrElse(col) { "" }
            val ranges = rowHits?.get(col)
            val text = if (ranges.isNullOrEmpty()) AnnotatedString(s) else buildAnnotatedString {
                append(s)
                ranges.forEach { r ->
                    if (r.first >= 0 && r.last < s.length) {
                        val isCur = current != null && current.pid == index && current.col == col && current.start == r.first
                        addStyle(SpanStyle(background = c.accent.copy(alpha = if (isCur) 0.55f else 0.22f)), r.first, r.last + 1)
                    }
                }
            }
            val numeric = !header && s.isNotBlank() && s.trim().all { it.isDigit() || it in ".,-+%$€£ " }
            Box(Modifier.width(colW[col]).fillMaxHeight().border(0.5.dp, c.line).padding(horizontal = pad, vertical = pad / 2)) {
                Text(
                    text, maxLines = if (header) 2 else 4, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = fontSp, lineHeight = fontSp * 1.35f, color = c.ink,
                        fontWeight = if (header) FontWeight.SemiBold else null,
                        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                        textDirection = TextDirection.Content),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ================================================================== pictures inside the document

/**
 * Decodes pictures (zip entries of the .docx, or image files next to a Markdown file) on demand with sampling.
 * LRU bounded by bytes: at most 1/8 of the app's heap (and never more than 48 MB).
 */
internal class DocxImages(private val path: String) {
    private var zip: ZipFile? = null
    private val budget = min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    private val cache = object : LruCache<String, ImageBitmap>(budget) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var closed = false

    fun load(img: DocBlock.Image, targetPx: Int): ImageBitmap? {
        val bucket = Integer.highestOneBit(targetPx.coerceAtLeast(32)) * 2 // reuse decodes across nearby zoom levels
        val key = "${img.entry}@$bucket"
        cache.get(key)?.let { return it }
        val bytes = synchronized(this) {
            if (closed) return null
            runCatching {
                if (img.external) {
                    val f = File(img.entry)
                    if (!f.isFile || f.length() > 48L * 1024 * 1024) return null
                    f.readBytes()
                } else {
                    val z = zip ?: ZipFile(path).also { zip = it }
                    val e = z.getEntry(img.entry) ?: return null
                    if (e.size > 48L * 1024 * 1024) return null
                    z.getInputStream(e).use { it.readBytes() }
                }
            }.getOrNull()
        } ?: return null
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            val target = bucket.coerceIn(64, 2048)
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
            while (max(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            bmp.asImageBitmap().also { if (!closed) cache.put(key, it) }
        }.getOrNull()
    }

    fun close() {
        synchronized(this) {
            closed = true
            runCatching { zip?.close() }
            zip = null
        }
        cache.evictAll()
    }
}
