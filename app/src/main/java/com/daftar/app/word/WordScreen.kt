package com.daftar.app.word

import com.daftar.app.ui.pane
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Toc
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.daftar.app.R
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarColors
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/** Text size multiplier, kept in memory for the whole app session. */
private object WordPrefs {
    var scale by mutableFloatStateOf(1f)
}

/** sp per document point at 100% (11pt body → ~15sp, DESIGN body size). */
private const val SP_PER_PT = 1.4f
private const val MAX_TEXT_WIDTH = 760

private sealed interface DocLoad {
    data object Loading : DocLoad
    data object Error : DocLoad
    data object Legacy : DocLoad
    class Ready(val doc: DocxDoc) : DocLoad
}

private class Match(val pid: Int, val start: Int, val end: Int, val top: Int)

/** Search state shared by all paragraphs. */
private class Hits(val byPid: Map<Int, List<IntRange>>, val current: Match?)

@Composable
fun WordScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val load by produceState<DocLoad>(DocLoad.Loading, path) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) DocLoad.Error else DocLoad.Ready(DocxParser.parse(file))
            } catch (_: LegacyDocException) {
                DocLoad.Legacy
            } catch (_: Throwable) {
                DocLoad.Error
            }
        }
    }
    val images = remember(path) { DocxImages(path) }
    DisposableEffect(images) { onDispose { images.close() } }

    val ready = load as? DocLoad.Ready
    val widthClass = LocalWidthClass.current
    val expanded = widthClass == WidthClass.Expanded
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Match>>(emptyList()) }
    var current by remember { mutableIntStateOf(0) }
    var outlinePanel by remember { mutableStateOf(true) }
    var outlineSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scale = WordPrefs.scale

    BackHandler(enabled = finding) { finding = false; query = "" }

    // Find: recompute matches off the main thread (debounced).
    LaunchedEffect(query, ready) {
        val doc = ready?.doc
        if (doc == null || query.isBlank()) { matches = emptyList(); current = 0; return@LaunchedEffect }
        delay(180)
        val q = query
        val found = withContext(Dispatchers.Default) {
            val out = ArrayList<Match>()
            for ((p, top) in doc.paragraphs) {
                var i = p.text.indexOf(q, ignoreCase = true)
                while (i >= 0 && out.size < 5000) {
                    out.add(Match(p.pid, i, i + q.length, top))
                    i = p.text.indexOf(q, i + q.length, ignoreCase = true)
                }
            }
            out
        }
        matches = found
        current = 0
        found.firstOrNull()?.let { listState.animateScrollToItem(it.top + 1) }
    }
    val hits = remember(matches, current) {
        Hits(matches.groupBy { it.pid }.mapValues { e -> e.value.map { it.start until it.end } }, matches.getOrNull(current))
    }
    fun goTo(i: Int) {
        if (matches.isEmpty()) return
        current = (i + matches.size) % matches.size
        scope.launch { listState.animateScrollToItem(matches[current].top + 1) }
    }
    fun jump(block: Int) { scope.launch { listState.animateScrollToItem(block + 1) } }

    Column(Modifier.fillMaxSize().background(D.c.bg)) {
        ViewerTopBar(title = file.nameWithoutExtension, onBack = { pane.back() }) {
            if (ready != null) {
                IconButton(onClick = { finding = !finding; if (!finding) query = "" }) {
                    Icon(Icons.Rounded.Search, stringResource(R.string.word_find), tint = if (finding) D.c.accent else D.c.muted)
                }
                if (widthClass != WidthClass.Compact) {
                    IconButton(onClick = { WordPrefs.scale = (scale - 0.1f).coerceAtLeast(0.8f) }, enabled = scale > 0.81f) {
                        Icon(Icons.Rounded.TextDecrease, stringResource(R.string.word_text_smaller), tint = D.c.muted)
                    }
                    IconButton(onClick = { WordPrefs.scale = (scale + 0.1f).coerceAtMost(1.8f) }, enabled = scale < 1.79f) {
                        Icon(Icons.Rounded.TextIncrease, stringResource(R.string.word_text_larger), tint = D.c.muted)
                    }
                }
                if (ready.doc.headings.isNotEmpty()) {
                    val active = if (expanded) outlinePanel else outlineSheet
                    IconButton(onClick = { if (expanded) outlinePanel = !outlinePanel else outlineSheet = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Toc, stringResource(R.string.word_outline), tint = if (active) D.c.accent else D.c.muted)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ready != null && widthClass == WidthClass.Compact) {
                        MenuRow(stringResource(R.string.word_text_smaller), Icons.Rounded.TextDecrease) {
                            WordPrefs.scale = (scale - 0.1f).coerceAtLeast(0.8f)
                        }
                        MenuRow(stringResource(R.string.word_text_larger), Icons.Rounded.TextIncrease) {
                            WordPrefs.scale = (scale + 0.1f).coerceAtMost(1.8f)
                        }
                    }
                    MenuRow(stringResource(R.string.share), Icons.Rounded.Share) { menu = false; shareFiles(ctx, listOf(file)) }
                    MenuRow(stringResource(R.string.open_externally), Icons.AutoMirrored.Rounded.OpenInNew) { menu = false; openExternally(ctx, file) }
                    if (ready != null) {
                        val copiedMsg = stringResource(R.string.word_copied)
                        MenuRow(stringResource(R.string.word_copy_all), Icons.Rounded.ContentCopy) {
                            menu = false
                            scope.launch {
                                val text = withContext(Dispatchers.Default) { ready.doc.plainText() }
                                copyText(ctx, file.nameWithoutExtension, text)
                                toast(ctx, copiedMsg)
                            }
                        }
                    }
                }
            }
        }
        if (finding && ready != null) {
            FindBar(query, { query = it }, matches.size, current, onPrev = { goTo(current - 1) }, onNext = { goTo(current + 1) },
                onClose = { finding = false; query = "" })
        }

        when (val l = load) {
            DocLoad.Loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = D.c.accent)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.word_loading), color = D.c.muted, style = MaterialTheme.typography.bodyLarge)
            }
            DocLoad.Error, DocLoad.Legacy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Rounded.ErrorOutline, stringResource(if (l == DocLoad.Legacy) R.string.word_legacy else R.string.word_error)) {
                    Button(onClick = { openExternally(ctx, file) }) { Text(stringResource(R.string.open_externally)) }
                }
            }
            is DocLoad.Ready -> {
                if (l.doc.blocks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Rounded.Description, stringResource(R.string.word_empty))
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        DocumentList(l.doc, images, listState, scale, hits, Modifier.weight(1f).fillMaxHeight())
                        if (expanded && outlinePanel && l.doc.headings.isNotEmpty()) {
                            Box(Modifier.width(1.dp).fillMaxHeight().background(D.c.line))
                            Column(Modifier.width(280.dp).fillMaxHeight().background(D.c.surface)) {
                                Text(stringResource(R.string.word_outline), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
                                    modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
                                OutlineList(l.doc.headings, Modifier.weight(1f)) { jump(it.blockIndex) }
                            }
                        }
                    }
                    if (!expanded && outlineSheet) OutlineSheet(l.doc.headings, onDismiss = { outlineSheet = false }) {
                        outlineSheet = false; jump(it.blockIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { Icon(icon, null, tint = D.c.muted) })
}

private fun copyText(ctx: Context, label: String, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun openLink(ctx: Context, url: String) {
    val uri = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) Uri.parse(url) else Uri.parse("https://$url")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, ctx.getString(R.string.no_app_found)) }
}

// ------------------------------------------------------------------ find bar

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, count: Int, current: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(D.c.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text(stringResource(R.string.word_find_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = D.c.muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = D.c.accent, unfocusedBorderColor = D.c.line,
                focusedContainerColor = D.c.surfaceAlt, unfocusedContainerColor = D.c.surfaceAlt,
            ),
            modifier = Modifier.weight(1f).focusRequester(fr),
        )
        Text(
            when {
                query.isBlank() -> ""
                count == 0 -> stringResource(R.string.word_find_none)
                else -> stringResource(R.string.word_find_count, current + 1, count)
            },
            style = MaterialTheme.typography.labelMedium, color = D.c.muted, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.word_find_prev), tint = D.c.muted) }
        IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.word_find_next), tint = D.c.muted) }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.word_find_close), tint = D.c.muted) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.c.line))
}

// ------------------------------------------------------------------ outline

@Composable
private fun OutlineList(headings: List<Heading>, modifier: Modifier = Modifier, onPick: (Heading) -> Unit) {
    val minLevel = headings.minOfOrNull { it.level } ?: 1
    LazyColumn(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        itemsIndexed(headings) { _, h ->
            val depth = (h.level - minLevel).coerceIn(0, 4)
            Text(
                h.text,
                style = if (depth == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = if (depth == 0) D.c.ink else D.c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onPick(h) }
                    .padding(start = (20 + depth * 14).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(headings: List<Heading>, onDismiss: () -> Unit, onPick: (Heading) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = D.c.surface) {
        Text(stringResource(R.string.word_outline), style = MaterialTheme.typography.titleMedium, color = D.c.ink,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp))
        OutlineList(headings, Modifier.navigationBarsPadding(), onPick)
    }
}

// ------------------------------------------------------------------ document

@Composable
private fun DocumentList(doc: DocxDoc, images: DocxImages, listState: LazyListState, scale: Float, hits: Hits, modifier: Modifier) {
    val c = D.c
    BoxWithConstraints(modifier.background(c.bg)) {
        val card = maxWidth >= (MAX_TEXT_WIDTH + 48).dp
        val gutter = if (card) 24.dp else 0.dp
        val inner = if (card) 40.dp else if (maxWidth >= 600.dp) 24.dp else 16.dp
        val pageWidth = min(maxWidth.value - gutter.value * 2, MAX_TEXT_WIDTH.toFloat() + if (card) inner.value * 2 else 0f).dp
        val textWidth = pageWidth - inner * 2
        val pageMod = Modifier.width(pageWidth)
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(if (card) c.bg else c.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = if (card) 24.dp else 0.dp),
            ) {
                item(key = "top") { Spacer(pageMod.height(if (card) 40.dp else 20.dp).pageSegment(c, card, top = true, bottom = false)) }
                itemsIndexed(doc.blocks) { _, b ->
                    Box(pageMod.pageSegment(c, card, top = false, bottom = false).padding(horizontal = inner)) {
                        BlockView(b, images, scale, hits, textWidth, allowScroll = true)
                    }
                }
                item(key = "bottom") { Spacer(pageMod.height(if (card) 48.dp else 32.dp).pageSegment(c, card, top = false, bottom = true)) }
            }
        }
    }
}

/** Draws one horizontal slice of the page card (surface + 1dp line border; rounded caps at the ends). */
private fun Modifier.pageSegment(c: DaftarColors, card: Boolean, top: Boolean, bottom: Boolean): Modifier =
    if (!card) this.background(c.surface) else this.drawBehind {
        val r = 16.dp.toPx(); val sw = 1.dp.toPx()
        val y0 = if (top) 0f else -r * 2
        val y1 = if (bottom) size.height else size.height + r * 2
        clipRect {
            drawRoundRect(c.surface, Offset(0f, y0), Size(size.width, y1 - y0), CornerRadius(r))
            drawRoundRect(c.line, Offset(sw / 2, y0 + sw / 2), Size(size.width - sw, y1 - y0 - sw), CornerRadius(r), style = Stroke(sw))
        }
    }

@Composable
private fun BlockView(b: DocBlock, images: DocxImages, scale: Float, hits: Hits, avail: Dp, allowScroll: Boolean) {
    when (b) {
        is DocBlock.Para -> ParaView(b, scale, hits)
        is DocBlock.Image -> ImageBlockView(b, images)
        is DocBlock.Table -> TableView(b, images, scale, hits, avail, allowScroll)
        DocBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).height(1.dp).background(D.c.line))
    }
}

@Composable
private fun ParaView(p: DocBlock.Para, scale: Float, hits: Hits) {
    val c = D.c
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val ranges = hits.byPid[p.pid]
    val cur = hits.current?.takeIf { it.pid == p.pid }
    val text = remember(p, c, ranges, cur) { buildText(ctx, p, c, ranges, cur) }
    val baseSp = (p.basePt * SP_PER_PT * scale).let { if (p.heading > 0) min(it, 36f * scale) else it }
    val lineH = if (p.heading > 0) 1.25f else max(1.3f, 1.25f * p.lineMult)
    val hang = -min(0f, p.firstLine) * scale
    val startPad = (p.indStart * scale - hang).coerceAtLeast(0f)
    val indent = with(density) {
        when {
            p.firstLine > 0f -> TextIndent(firstLine = (p.firstLine * scale).dp.toSp())
            hang > 0f -> TextIndent(firstLine = 0.sp, restLine = hang.dp.toSp())
            else -> TextIndent.None
        }
    }
    val style = TextStyle(
        color = c.ink,
        fontSize = baseSp.sp,
        lineHeight = lineH.em,
        textAlign = when (p.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; 3 -> TextAlign.Justify; else -> TextAlign.Start },
        textDirection = if (p.rtl) TextDirection.Rtl else TextDirection.Content,
        textIndent = indent,
    )
    val top = (p.before * scale).let { if (p.heading > 0) max(it, 14f * scale) else it }
    CompositionLocalProvider(LocalLayoutDirection provides if (p.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(Modifier.fillMaxWidth().padding(start = startPad.dp, end = (p.indEnd * scale).dp, top = top.dp, bottom = (p.after * scale).dp)) {
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
                    modifier = Modifier.width((p.markerWidth * scale).dp),
                )
            }
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
    }
}

/** Document colours are designed for white paper: keep them readable on the dark theme. */
private fun docColor(col: Color, c: DaftarColors): Color {
    if (!c.dark) return if (col.luminance() > 0.92f) c.ink else col
    val maxC = max(col.red, max(col.green, col.blue)); val minC = min(col.red, min(col.green, col.blue))
    val grey = maxC - minC < 0.12f
    return when {
        grey && col.luminance() < 0.5f -> c.ink
        col.luminance() < 0.35f -> lerp(col, Color.White, 0.5f)
        else -> col
    }
}

private fun docBackground(col: Color, c: DaftarColors): Color = if (c.dark) col.copy(alpha = 0.3f) else col

private fun buildText(ctx: Context, p: DocBlock.Para, c: DaftarColors, ranges: List<IntRange>?, cur: Match?): AnnotatedString = buildAnnotatedString {
    append(p.text)
    val linkStyle = TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))
    for (s in p.spans) {
        if (s.start >= s.end || s.end > p.text.length) continue
        val f = s.fmt
        val deco = when {
            f.underline && f.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            f.underline -> TextDecoration.Underline
            f.strike -> TextDecoration.LineThrough
            else -> null
        }
        val ratio = (f.sizePt / p.basePt).coerceIn(0.4f, 3f)
        val sizeEm = when {
            f.vert != 0 -> (ratio * 0.7f).em
            kotlin.math.abs(ratio - 1f) > 0.01f -> ratio.em
            else -> androidx.compose.ui.unit.TextUnit.Unspecified
        }
        addStyle(
            SpanStyle(
                color = f.color?.let { docColor(Color(it), c) } ?: Color.Unspecified,
                fontSize = sizeEm,
                fontWeight = if (f.bold) FontWeight.Bold else null,
                fontStyle = if (f.italic) FontStyle.Italic else null,
                fontFamily = if (f.mono) FontFamily.Monospace else null,
                textDecoration = deco,
                background = f.background?.let { docBackground(Color(it), c) } ?: Color.Unspecified,
                baselineShift = when (f.vert) { 1 -> BaselineShift.Superscript; 2 -> BaselineShift.Subscript; else -> null },
            ),
            s.start, s.end,
        )
        if (s.link != null) {
            val url = s.link
            addLink(LinkAnnotation.Url(url, linkStyle) { openLink(ctx, url) }, s.start, s.end)
        }
    }
    ranges?.forEach { r ->
        if (r.first >= 0 && r.last < p.text.length) {
            val isCur = cur != null && cur.start == r.first
            addStyle(SpanStyle(background = c.accent.copy(alpha = if (isCur) 0.55f else 0.22f)), r.first, r.last + 1)
        }
    }
}

@Composable
private fun ImageBlockView(b: DocBlock.Image, images: DocxImages) {
    val density = LocalDensity.current
    val w = b.widthDp.coerceAtLeast(8f)
    val h = b.heightDp.coerceAtLeast(8f)
    val targetPx = with(density) { min(w, MAX_TEXT_WIDTH.toFloat()).dp.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, b.entry, targetPx) {
        value = withContext(Dispatchers.IO) { images.load(b.entry, targetPx) }
    }
    val align = when (b.align) { 1 -> Alignment.Center; 2 -> Alignment.CenterEnd; else -> Alignment.CenterStart }
    CompositionLocalProvider(LocalLayoutDirection provides if (b.rtl) LayoutDirection.Rtl else LocalLayoutDirection.current) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = align) {
            Box(Modifier.widthIn(max = w.dp).fillMaxWidth().aspectRatio(w / h), contentAlignment = Alignment.Center) {
                val img = bmp
                if (img != null) {
                    Image(img, b.alt.ifBlank { stringResource(R.string.word_image) }, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.fillMaxSize().background(D.c.surfaceAlt, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Image, b.alt.ifBlank { stringResource(R.string.word_image) }, tint = D.c.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableView(t: DocBlock.Table, images: DocxImages, scale: Float, hits: Hits, avail: Dp, allowScroll: Boolean) {
    val c = D.c
    val lineColor = c.muted.copy(alpha = 0.45f)
    val cols = max(t.grid.size, t.rows.maxOfOrNull { r -> r.cells.sumOf { it.span } } ?: 1).coerceAtLeast(1)
    val natural = if (t.grid.isNotEmpty()) t.grid.sum() * scale else 0f
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

// ------------------------------------------------------------------ images inside the package

/** Decodes pictures from the .docx zip on demand with sampling; small LRU cache bounded by bytes. */
private class DocxImages(private val path: String) {
    private var zip: ZipFile? = null
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var closed = false

    fun load(entry: String, targetPx: Int): ImageBitmap? {
        val key = "$entry@$targetPx"
        cache.get(key)?.let { return it }
        val bytes = synchronized(this) {
            if (closed) return null
            runCatching {
                val z = zip ?: ZipFile(path).also { zip = it }
                val e = z.getEntry(entry) ?: return null
                if (e.size > 64L * 1024 * 1024) return null
                z.getInputStream(e).use { it.readBytes() }
            }.getOrNull()
        } ?: return null
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            val target = targetPx.coerceIn(64, 2048)
            while (o.outWidth / (sample * 2) >= target && o.outHeight / (sample * 2) >= 32) sample *= 2
            while (max(o.outWidth, o.outHeight) / sample > 4096) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            bmp.asImageBitmap().also { cache.put(key, it) }
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
