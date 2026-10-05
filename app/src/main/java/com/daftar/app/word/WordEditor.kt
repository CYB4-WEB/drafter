package com.daftar.app.word

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignJustify
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatColorFill
import androidx.compose.material.icons.rounded.FormatColorText
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatStrikethrough
import androidx.compose.material.icons.rounded.FormatTextdirectionLToR
import androidx.compose.material.icons.rounded.FormatTextdirectionRToL
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.InsertPageBreak
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextFormat
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.daftar.app.R
import com.daftar.app.ui.ZoomControls
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.DaftarColors
import com.daftar.app.ui.theme.LocalColors
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ==================================================================== public entry points

/**
 * Creates an empty Word document "Document.docx" (unique name) in [dir] and marks it to open in edit mode.
 * The lead's create sheet / Home call: `val f = newWordDocument(ctx, dir); pane.open(ctx, f)`.
 */
fun newWordDocument(ctx: Context, dir: File): File {
    dir.mkdirs()
    val base = ctx.getString(R.string.word_new_doc_name)
    var f = File(dir, "$base.docx")
    var n = 2
    while (f.exists()) f = File(dir, "$base $n.docx").also { n++ }
    if (!DocxExport.writeDocx(emptyList(), f)) throw java.io.IOException("could not create ${f.name}")
    WordEditRequests.request(f.absolutePath)
    return f
}

/** Files that should open straight in edit mode (new documents, "Save as .docx to edit"). */
internal object WordEditRequests {
    private val paths = HashSet<String>()
    @Synchronized fun request(path: String) { paths.add(path) }
    @Synchronized fun consume(path: String): Boolean = paths.remove(path)
}

// ==================================================================== metrics / colours

private const val SENT = '​'
private const val EDIT_DP_PER_PT = 4f / 3f

/** Paper colours for the editor's paper layout (always a white sheet, like the print view). */
private val PaperPalette = DaftarColors(
    bg = Color(0xFFF7F6F2), surface = Color.White, surfaceAlt = Color(0xFFF3F4F6), line = Color(0xFFE4E2DC),
    ink = Color(0xFF1F2937), muted = Color(0xFF6B7280), accent = Color(0xFF3B82F6), onAccent = Color.White,
    danger = Color(0xFFB3261E), dark = false,
)

/** [u] = dp per document point for geometry, [sp] = sp per point for text. */
internal class EditMetrics(val paper: Boolean, val u: Float, val sp: Float, val scale: Float)

private val highlightNames = listOf("yellow", "green", "cyan", "magenta", "red", "lightGray", "darkYellow", "blue")
private val highlightColors = mapOf(
    "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF, "blue" to 0xFF0000FF,
    "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080, "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080,
    "darkRed" to 0xFF800000, "darkYellow" to 0xFF808000, "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0, "black" to 0xFF000000,
    "white" to 0xFFFFFFFF,
).mapValues { it.value.toInt() }
private val textColors = listOf("000000", "6B7280", "C00000", "E36C09", "FFC000", "00B050", "0070C0", "002060", "7030A0", "D81B60")
private val sizes = listOf(8f, 9f, 10f, 10.5f, 11f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 36f, 48f, 72f)

// ==================================================================== the editor

/**
 * Edit mode of the Word screen: ribbon, the document as editable paragraphs (paper or reflow layout), find & replace,
 * status bar. [findOpen] toggles the find & replace bar (header button in WordScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WordEditor(
    st: EditorState, images: DocxImages, paperPref: Boolean?, findOpen: Boolean, onCloseFind: () -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val blocks = st.blocks
    val markers = remember(blocks) { st.markers(blocks) }
    var zoom by remember { mutableStateOf(-1f) }
    var readScale by remember { mutableStateOf(1f) }

    // find & replace
    var query by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }
    val hits = remember(blocks, query, findOpen) { if (findOpen) st.findAll(query) else emptyList() }
    val hitMap = remember(hits) { hits.groupBy { it.id }.mapValues { e -> e.value.map { it.start until it.end } } }
    val curHit = hits.getOrNull(hitIndex.coerceAtMost(max(hits.size - 1, 0)))

    // pages + words (background, debounced)
    var pages by remember { mutableStateOf<List<LaidPage>>(emptyList()) }
    var words by remember { mutableIntStateOf(wordCount(blocks)) }
    LaunchedEffect(blocks) {
        delay(if (pages.isEmpty()) 50 else 900)
        val snap = blocks
        val r = withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            val n = wordCount(snap)
            val pg = runCatching {
                val model = toDocBlocks(st, snap, markers)
                val out = ArrayList<LaidPage>()
                val p = Paginator(st.src.page, TextEngine(), onPage = { out.add(it) }, cancelled = { job?.isActive == false })
                p.addAll(model); p.finish()
                out as List<LaidPage>
            }.getOrNull()
            n to pg
        }
        words = r.first
        r.second?.let { pages = it }
    }

    // image picker
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val failed = ctx.getString(R.string.word_image_failed)
            val made = withContext(Dispatchers.IO) { runCatching { importImage(ctx, st, uri) }.getOrNull() }
            if (made == null) toast(ctx, failed)
            else if (st.md) st.insertLines(listOf(made.first))
            else made.second?.let { st.insertBlocks(listOf(it)) }
        }
    }
    var tableDialog by remember { mutableStateOf(false) }

    // focus target off screen → scroll to it first
    LaunchedEffect(st.focusReq) {
        val r = st.focusReq ?: return@LaunchedEffect
        val top = st.topIndexOf(r.id)
        if (top >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == blocks.getOrNull(top)?.id }) list.scrollToItem(top + 1)
    }

    // imePadding on the whole editor: the status bar and the caret's paragraph stay above the keyboard (edge-to-edge window).
    BoxWithConstraints(modifier.fillMaxSize().background(D.c.bg).imePadding()) {
        val narrow = maxWidth < 600.dp
        val viewportW = maxWidth.value
        val page = st.src.page
        val paper = st.docx && (paperPref ?: (viewportW >= 600f))
        val fit = ((viewportW - 32f) / (page.w * EDIT_DP_PER_PT)).coerceIn(0.3f, 3f)
        if (zoom < 0f) zoom = min(fit, 1.25f)
        val m = if (paper) EditMetrics(true, zoom * EDIT_DP_PER_PT, zoom * EDIT_DP_PER_PT, zoom * EDIT_DP_PER_PT / SP_PER_PT)
        else EditMetrics(false, DP_PER_PT * readScale, SP_PER_PT * readScale, readScale)

        Column(Modifier.fillMaxSize()) {
            Ribbon(st, narrow, onImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onTable = { tableDialog = true })
            if (findOpen) FindReplaceBar(
                query, { query = it; hitIndex = 0 }, replaceWith, { replaceWith = it }, hits.size, hitIndex,
                onPrev = { if (hits.isNotEmpty()) { hitIndex = (hitIndex - 1 + hits.size) % hits.size; scope.launch { revealHit(st, list, hits[hitIndex]) } } },
                onNext = { if (hits.isNotEmpty()) { hitIndex = (hitIndex + 1) % hits.size; scope.launch { revealHit(st, list, hits[hitIndex]) } } },
                onReplace = { curHit?.let { st.replace(it, replaceWith) } },
                onReplaceAll = {
                    val n = st.replaceAll(query, replaceWith)
                    toast(ctx, ctx.getString(R.string.word_replaced, n))
                },
                onClose = onCloseFind,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DocumentList(st, list, m, page, viewportW, markers, hitMap, curHit, images)
            }
            StatusBar(st, pages, words, list, if (paper) (zoom * 100).roundToInt() else (readScale * 100).roundToInt(),
                onOut = { if (paper) zoom = (zoom / 1.15f).coerceIn(0.3f, 3f) else readScale = (readScale / 1.1f).coerceIn(0.6f, 2.5f) },
                onIn = { if (paper) zoom = (zoom * 1.15f).coerceIn(0.3f, 3f) else readScale = (readScale * 1.1f).coerceIn(0.6f, 2.5f) },
                onFit = { if (paper) zoom = fit else readScale = 1f }, narrow = narrow)
        }
    }
    if (tableDialog) TableSizeDialog(onDismiss = { tableDialog = false }) { r, c ->
        tableDialog = false
        if (st.md) {
            val head = "| " + (1..c).joinToString(" | ") { "     " } + " |"
            val sep = "|" + (1..c).joinToString("|") { " --- " } + "|"
            st.insertLines(listOf(head, sep) + List(max(r - 1, 1)) { head })
        } else {
            val rtl = st.para(st.focusId)?.let { st.look(it).para.rtl } ?: false
            st.insertBlocks(listOf(DocxEdit.table(st.src, r, c, rtl)))
        }
    }
}

private suspend fun revealHit(st: EditorState, list: LazyListState, h: EHit) {
    val top = st.topIndexOf(h.id)
    if (top >= 0) list.animateScrollToItem(top + 1)
}

/** Copies a picked picture into app storage (≤ 2400 px, JPEG/PNG) and makes the block / Markdown line for it. */
private fun importImage(ctx: Context, st: EditorState, uri: Uri): Pair<String, EBlock?>? {
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width; val h = info.size.height
            val s = max(w, h)
            if (s > 2400) d.setTargetSize((w * 2400f / s).roundToInt().coerceAtLeast(1), (h * 2400f / s).roundToInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    }
    val png = bmp.hasAlpha()
    val ext = if (png) "png" else "jpg"
    val stamp = System.currentTimeMillis()
    val dir = if (st.md) File(st.src.file.parentFile, st.src.file.nameWithoutExtension + "_files") else File(ctx.filesDir, "word-media")
    dir.mkdirs()
    if (!st.md) dir.listFiles()?.forEach { if (stamp - it.lastModified() > 14L * 24 * 3600 * 1000) it.delete() }
    val f = File(dir, "img_$stamp.$ext")
    FileOutputStream(f).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, it) }
    val w = bmp.width; val h = bmp.height
    bmp.recycle()
    if (st.md) return "![](${dir.name}/${f.name})" to null
    val rtl = st.para(st.focusId)?.let { st.look(it).para.rtl } ?: false
    return "" to DocxEdit.picture(st.src, f, w, h, rtl)
}

// ==================================================================== document list

@Composable
private fun DocumentList(
    st: EditorState, list: LazyListState, m: EditMetrics, page: PageSpec, viewportW: Float, markers: Map<Int, String>,
    hits: Map<Int, List<IntRange>>, cur: EHit?, images: DocxImages,
) {
    val density = LocalDensity.current
    val blocks = st.blocks
    val c = D.c
    if (m.paper) {
        val pageW = page.w * m.u
        val scrollsX = pageW + 32f > viewportW + 0.5f
        val paperDensity = remember(density.density) { Density(density.density, 1f) }
        Box(Modifier.fillMaxSize().then(if (scrollsX) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
            CompositionLocalProvider(LocalDensity provides paperDensity, LocalColors provides PaperPalette) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.width(max(viewportW, pageW + 32f).dp).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(top = 16.dp, bottom = 160.dp),
                ) {
                    item(key = "top") { Box(Modifier.width(pageW.dp).height((page.top * m.u).dp).sheet(true, false)) }
                    items(blocks, key = { it.id }) { b ->
                        Box(Modifier.width(pageW.dp).sheet(false, false).padding(start = (page.left * m.u).dp, end = (page.right * m.u).dp)) {
                            BlockEditor(st, b, m, markers, hits, cur, images, (page.contentW * m.u).dp.value)
                        }
                    }
                    item(key = "bottom") { Box(Modifier.width(pageW.dp).height((page.bottom * m.u).dp).sheet(false, true)) }
                }
            }
        }
    } else {
        val card = viewportW >= MAX_TEXT_WIDTH + 48
        val inner = if (card) 40f else if (viewportW >= 600f) 24f else 16f
        val width = min(viewportW - (if (card) 48f else 0f), MAX_TEXT_WIDTH + inner * 2)
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().background(if (card) c.bg else c.surface),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = if (card) 24.dp else 0.dp, bottom = 160.dp),
        ) {
            item(key = "top") { Box(Modifier.width(width.dp).height(24.dp).background(c.surface)) }
            items(blocks, key = { it.id }) { b ->
                Box(Modifier.width(width.dp).background(c.surface).padding(horizontal = inner.dp)) {
                    BlockEditor(st, b, m, markers, hits, cur, images, width - inner * 2)
                }
            }
            item(key = "bottom") { Box(Modifier.width(width.dp).height(32.dp).background(c.surface)) }
        }
    }
}

/** White sheet slice with side borders (and rounded ends for the first / last slice). */
@Composable
private fun Modifier.sheet(top: Boolean, bottom: Boolean): Modifier {
    val c = D.c
    return this.background(Color.White).drawBehind {
        val sw = 1.dp.toPx()
        drawLine(c.line, Offset(sw / 2, 0f), Offset(sw / 2, size.height), sw)
        drawLine(c.line, Offset(size.width - sw / 2, 0f), Offset(size.width - sw / 2, size.height), sw)
        if (top) drawLine(c.line, Offset(0f, sw / 2), Offset(size.width, sw / 2), sw)
        if (bottom) drawLine(c.line, Offset(0f, size.height - sw / 2), Offset(size.width, size.height - sw / 2), sw)
    }
}

@Composable
private fun BlockEditor(
    st: EditorState, b: EBlock, m: EditMetrics, markers: Map<Int, String>, hits: Map<Int, List<IntRange>>, cur: EHit?,
    images: DocxImages, widthDp: Float,
) {
    when (b) {
        is EPara -> ParaField(st, b, m, markers[b.id], hits[b.id], cur?.takeIf { it.id == b.id })
        is ETable -> TableEditor(st, b, m, markers, hits, cur, widthDp)
        is EObject -> ObjectView(st, b, m, images, widthDp)
    }
}

// ==================================================================== paragraph field

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParaField(st: EditorState, p: EPara, m: EditMetrics, marker: String?, hits: List<IntRange>?, cur: EHit?) {
    val c = D.c
    val lk = st.look(p)
    val tp = lk.para
    val fr = remember { FocusRequester() }
    val biv = remember { BringIntoViewRequester() }
    var tfv by remember(p.id) { mutableStateOf(TextFieldValue(SENT + p.text, TextRange(1))) }
    if (tfv.text.length != p.text.length + 1 || !tfv.text.regionMatches(1, p.text, 0, p.text.length)) {
        val s = TextRange(tfv.selection.start.coerceIn(1, p.text.length + 1), tfv.selection.end.coerceIn(1, p.text.length + 1))
        tfv = TextFieldValue(SENT + p.text, s)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var focused by remember { mutableStateOf(false) }
    val transform = remember(p, lk, hits, cur, c, m.paper) { ParaTransform(st, p, lk, hits, cur, c, m.paper) }

    LaunchedEffect(st.focusReq) {
        val r = st.focusReq ?: return@LaunchedEffect
        if (r.id != p.id) return@LaunchedEffect
        val n = p.text.length
        tfv = tfv.copy(selection = TextRange(r.sel.start.coerceIn(0, n) + 1, r.sel.end.coerceIn(0, n) + 1), composition = null)
        runCatching { fr.requestFocus() }
    }
    // Keep the caret above the keyboard when it opens or the caret moves to a new line.
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(ime, focused) {
        if (!focused) return@LaunchedEffect
        delay(60)
        val lay = layout
        val rect = if (lay != null) runCatching {
            lay.getCursorRect(transform.toTransformed(tfv.selection.end).coerceIn(0, lay.layoutInput.text.length))
        }.getOrNull() else null
        runCatching { biv.bringIntoView(rect?.let { Rect(it.left, it.top - 24f, it.right, it.bottom + 48f) }) }
    }

    val dir = if (tp.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val ink = c.ink
    val style = TextStyle(
        color = if (tp.box == BOX_QUOTE) c.muted else ink,
        fontSize = (tp.basePt * m.sp).sp,
        lineHeight = (if (m.paper) LINE_FACTOR * tp.lineMult else max(1.3f, 1.25f * tp.lineMult)).em,
        textAlign = when (tp.align) { 1 -> TextAlign.Center; 2 -> TextAlign.End; 3 -> TextAlign.Justify; else -> TextAlign.Start },
        textDirection = if (tp.rtl) TextDirection.Rtl else TextDirection.Content,
        textIndent = with(LocalDensity.current) {
            when {
                marker == null && tp.firstLine > 0f -> TextIndent(firstLine = (tp.firstLine * m.u).dp.toSp())
                marker == null && tp.hangPt > 0f -> TextIndent(restLine = (tp.hangPt * m.u).dp.toSp())
                else -> TextIndent.None
            }
        },
    )
    CompositionLocalProvider(LocalLayoutDirection provides dir) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = ((if (marker != null) tp.indStart else tp.boxStartPt) * m.u).dp, end = (tp.indEnd * m.u).dp,
                top = (tp.before * m.u * 0.75f).dp, bottom = (tp.after * m.u * 0.75f).dp,
            ),
        ) {
            if (marker != null) {
                Text(
                    marker, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                    style = style.copy(textAlign = TextAlign.Start, textIndent = TextIndent.None, color = ink),
                    modifier = Modifier.width((max(tp.markerWidth, 18f) * m.u).dp),
                )
            }
            BasicTextField(
                value = tfv,
                onValueChange = { v -> onFieldChange(st, p, tfv, v) { tfv = it } },
                textStyle = style,
                cursorBrush = SolidColor(c.accent),
                visualTransformation = transform,
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).bringIntoViewRequester(biv).focusRequester(fr)
                    .onFocusChanged { fs ->
                        focused = fs.isFocused
                        if (fs.isFocused) {
                            st.focusId = p.id
                            st.selectedObject = -1
                            st.selection = TextRange(max(tfv.selection.start - 1, 0), max(tfv.selection.end - 1, 0))
                        }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val sel = tfv.selection
                        when {
                            e.key == Key.Backspace && sel.collapsed && sel.start <= 1 -> { st.backspaceAtStart(p.id); true }
                            e.key == Key.Delete && sel.collapsed && sel.start >= tfv.text.length -> { st.deleteAtEnd(p.id); true }
                            (e.key == Key.Enter || e.key == Key.NumPadEnter) && e.isShiftPressed -> { st.typeText(SOFT_BREAK.toString()); true }
                            e.key == Key.DirectionUp && !e.isShiftPressed -> moveLine(st, p, layout, transform, sel, -1)
                            e.key == Key.DirectionDown && !e.isShiftPressed -> moveLine(st, p, layout, transform, sel, 1)
                            else -> false
                        }
                    },
            )
        }
    }
}

/** Arrow up on the first line / down on the last line moves to the neighbouring paragraph. */
private fun moveLine(st: EditorState, p: EPara, layout: TextLayoutResult?, t: ParaTransform, sel: TextRange, dir: Int): Boolean {
    val lay = layout ?: return false
    val off = t.toTransformed(sel.end).coerceIn(0, lay.layoutInput.text.length)
    val line = lay.getLineForOffset(off)
    if (dir < 0 && line > 0) return false
    if (dir > 0 && line < lay.lineCount - 1) return false
    val next = neighbour(st, p.id, dir) ?: return false
    st.requestFocus(next.id, TextRange(if (dir < 0) next.text.length else 0))
    return true
}

private fun neighbour(st: EditorState, id: Int, dir: Int): EPara? {
    val all = ArrayList<EPara>()
    st.blocks.forEachPara { all.add(it) }
    val i = all.indexOfFirst { it.id == id }
    return all.getOrNull(i + dir)
}

private fun onFieldChange(st: EditorState, p: EPara, old: TextFieldValue, v: TextFieldValue, set: (TextFieldValue) -> Unit) {
    val t = v.text
    if (!t.startsWith(SENT)) {
        // The sentinel was deleted: Backspace at the start of the paragraph (soft keyboards).
        if (old.selection.collapsed && old.selection.start <= 1 && t == old.text.substring(1)) {
            st.backspaceAtStart(p.id)
            return
        }
        val body = t.replace(SENT.toString(), "")
        set(TextFieldValue(SENT + body, TextRange(1 + max(0, v.selection.start - 1).coerceAtMost(body.length))))
        st.onText(p.id, body, TextRange(max(0, v.selection.start - 1)))
        return
    }
    val body = t.substring(1).let { if (it.indexOf(SENT) >= 0) it.replace(SENT.toString(), "") else it }
    val sel = TextRange(max(v.selection.start, 1) - 1, max(v.selection.end, 1) - 1)
    val nv = if (v.selection.start < 1 || v.selection.end < 1) v.copy(selection = TextRange(max(v.selection.start, 1), max(v.selection.end, 1))) else v
    if (body == p.text) {
        if (old.selection != nv.selection) st.pending = null
        set(nv)
        st.focusId = p.id
        st.selection = sel
        return
    }
    set(nv)
    st.onText(p.id, body, sel)
}

/** Run formatting, atoms (shown as their result / a symbol) and find hits for a paragraph field. */
private class ParaTransform(
    val st: EditorState, val p: EPara, val lk: PLook, val hits: List<IntRange>?, val cur: EHit?, val c: DaftarColors, val paper: Boolean,
) : VisualTransformation {
    private var o2t = IntArray(0)

    /** Paragraph offset (without the sentinel) → transformed offset. */
    fun toTransformed(fieldOffset: Int): Int = o2t.getOrElse(fieldOffset) { fieldOffset }

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length != p.text.length + 1) { o2t = IntArray(0); return TransformedText(text, OffsetMapping.Identity) }
        val n = raw.length
        val map = IntArray(n + 1)
        val atomRanges = ArrayList<IntRange>()
        val sb = StringBuilder(n + 8)
        for (i in 0 until n) {
            map[i] = sb.length
            val ch = raw[i]
            if (i > 0 && ch == ATOM) {
                val f = st.src.fmts[p.fmts[i - 1]]
                val d = st.src.atoms[f.atom]?.display ?: "·"
                val s = sb.length
                sb.append(d)
                atomRanges.add(s until sb.length)
            } else sb.append(ch)
        }
        map[n] = sb.length
        o2t = map
        val back = IntArray(sb.length + 1)
        for (i in 0 until n) for (k in map[i] until map[i + 1]) back[k] = i
        back[sb.length] = n

        val out = buildAnnotatedString {
            append(sb.toString())
            if (st.md) markdownStyles(this, sb.toString(), c)
            else {
                var i = 0
                val body = p.text
                while (i < body.length) {
                    val idx = p.fmts[i]
                    var j = i
                    while (j < body.length && p.fmts[j] == idx) j++
                    val f = st.src.fmts[idx]
                    if (f.atom < 0 && st.docx) {
                        val r = st.runFmt(p, lk, idx)
                        addStyle(spanOf(r, lk.para.basePt, f.link != null), map[i + 1], map[j + 1])
                    }
                    i = j
                }
            }
            for (r in atomRanges) addStyle(SpanStyle(color = c.muted, background = c.surfaceAlt), r.first, r.last + 1)
            hits?.forEach { h ->
                val a = map[(h.first + 1).coerceIn(0, n)]
                val b = map[(h.last + 2).coerceIn(0, n)]
                val isCur = cur != null && cur.start == h.first
                if (a < b) addStyle(SpanStyle(background = c.accent.copy(alpha = if (isCur) 0.55f else 0.22f)), a, b)
            }
        }
        return TransformedText(out, object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = map[offset.coerceIn(0, n)]
            override fun transformedToOriginal(offset: Int): Int = back[offset.coerceIn(0, sb.length)]
        })
    }

    private fun spanOf(f: RunFmt, base: Float, link: Boolean): SpanStyle {
        val deco = when {
            (f.underline || link) && f.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            f.underline || link -> TextDecoration.Underline
            f.strike -> TextDecoration.LineThrough
            else -> null
        }
        val ratio = f.sizePt / base
        val color = when {
            f.color != null -> Color(f.color).let { col -> if (paper) { if (col.luminance() > 0.92f) c.ink else col } else docColor(col, c) }
            link -> c.accent
            else -> Color.Unspecified
        }
        return SpanStyle(
            color = color,
            fontSize = when { f.vert != 0 -> (ratio * 0.7f).em; abs(ratio - 1f) > 0.001f -> ratio.em; else -> TextUnit.Unspecified },
            fontWeight = if (f.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (f.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (f.mono) FontFamily.Monospace else WordFonts.family(f.font),
            textDecoration = deco,
            background = f.background?.let { if (paper) Color(it) else docBackground(Color(it), c) } ?: Color.Unspecified,
            baselineShift = when (f.vert) { 1 -> BaselineShift.Superscript; 2 -> BaselineShift.Subscript; else -> null },
        )
    }
}

/** Light Markdown highlighting: emphasis, code, links; markup characters muted. */
private fun markdownStyles(b: AnnotatedString.Builder, s: String, c: DaftarColors) {
    fun mark(re: Regex, style: SpanStyle, open: Int, close: Int = open) {
        for (m in re.findAll(s)) {
            val r = m.range
            b.addStyle(style, r.first, r.last + 1)
            b.addStyle(SpanStyle(color = c.muted), r.first, r.first + open)
            b.addStyle(SpanStyle(color = c.muted), r.last + 1 - close, r.last + 1)
        }
    }
    Regex("^\\u200B?(#{1,6}\\s|>\\s?|\\s*(?:[-*+]|\\d+[.)])\\s)").find(s)?.let { b.addStyle(SpanStyle(color = c.muted), it.range.first, it.range.last + 1) }
    mark(Regex("\\*\\*[^*]+\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold), 2)
    mark(Regex("(?<![*\\w])\\*[^*\\s][^*]*\\*(?!\\*)"), SpanStyle(fontStyle = FontStyle.Italic), 1)
    mark(Regex("~~[^~]+~~"), SpanStyle(textDecoration = TextDecoration.LineThrough), 2)
    mark(Regex("<u>[^<]*</u>"), SpanStyle(textDecoration = TextDecoration.Underline), 3, 4)
    mark(Regex("`[^`]+`"), SpanStyle(fontFamily = FontFamily.Monospace, background = c.surfaceAlt), 1)
    for (m in Regex("!?\\[[^\\]]*\\]\\([^)]*\\)").findAll(s)) b.addStyle(SpanStyle(color = c.accent), m.range.first, m.range.last + 1)
}

// ==================================================================== tables & objects

@Composable
private fun TableEditor(
    st: EditorState, t: ETable, m: EditMetrics, markers: Map<Int, String>, hits: Map<Int, List<IntRange>>, cur: EHit?, widthDp: Float,
) {
    val grid = remember(t.head) { Regex("gridCol\\b[^>]*?w:w=\"(\\d+)\"").findAll(t.head).map { it.groupValues[1].toFloat() }.toList() }
    val rtl = t.head.contains("bidiVisual") && !t.head.contains("bidiVisual w:val=\"0\"")
    val line = D.c.muted.copy(alpha = 0.5f)
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(0.5.dp, line)) {
            for (row in t.rows) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    var col = 0
                    for (cell in row.cells) {
                        val span = Regex("gridSpan\\b[^>]*?w:val=\"(\\d+)\"").find(cell.head)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val w = if (grid.size >= col + span) grid.subList(col, col + span).sum().coerceAtLeast(1f) else span.toFloat() * (grid.average().takeIf { !it.isNaN() }?.toFloat() ?: 1f)
                        col += span
                        Column(Modifier.weight(w).fillMaxHeight().border(0.5.dp, line).padding(horizontal = (CELL_PAD_H * m.u).dp, vertical = (CELL_PAD_V * m.u + 2).dp)) {
                            for (p in cell.paras) ParaField(st, p, m, markers[p.id], hits[p.id], cur?.takeIf { it.id == p.id })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObjectView(st: EditorState, o: EObject, m: EditMetrics, images: DocxImages, widthDp: Float) {
    if (o.kind == OKind.HIDDEN) return
    val c = D.c
    val selected = st.selectedObject == o.id
    Box(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .then(if (selected) Modifier.border(2.dp, c.accent, RoundedCornerShape(4.dp)) else Modifier)
            .clickable { st.selectedObject = if (selected) -1 else o.id },
    ) {
        when (o.kind) {
            OKind.PAGE_BREAK -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Dashed(Modifier.weight(1f), c.muted)
                Text(stringResource(R.string.word_page_break), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(horizontal = 8.dp))
                Dashed(Modifier.weight(1f), c.muted)
            }
            OKind.RULE -> Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(c.muted))
            else -> Column(Modifier.fillMaxWidth()) {
                for (b in o.preview) BlockView(b, images, m.scale, Hits.None, widthDp.dp, allowScroll = false)
            }
        }
        if (o.kind == OKind.LOCKED) Icon(Icons.Rounded.Lock, stringResource(R.string.word_locked), tint = c.muted,
            modifier = Modifier.align(Alignment.TopEnd).size(16.dp))
        if (selected) Row(
            Modifier.align(Alignment.TopEnd).padding(4.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (o.kind == OKind.LOCKED) Text(stringResource(R.string.word_locked), style = MaterialTheme.typography.labelMedium, color = c.muted,
                modifier = Modifier.padding(start = 12.dp).widthIn(max = 220.dp))
            IconButton(onClick = { st.deleteObject(o.id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.word_delete), tint = c.danger) }
        }
    }
}

@Composable
private fun Dashed(modifier: Modifier, color: Color) {
    Box(modifier.height(1.dp).drawBehind {
        drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    })
}

// ==================================================================== EditDoc → viewer model (pagination)

/** Viewer blocks for the current edit state (paragraph ids = edit ids) so the Paginator can count pages. */
private fun toDocBlocks(st: EditorState, blocks: List<EBlock>, markers: Map<Int, String>): List<DocBlock> {
    fun para(p: EPara): DocBlock.Para {
        val lk = st.look(p)
        val t = lk.para
        val text = StringBuilder()
        val spans = ArrayList<Span>()
        var i = 0
        while (i < p.text.length) {
            val idx = p.fmts[i]
            var j = i
            while (j < p.text.length && p.fmts[j] == idx) j++
            val f = st.src.fmts[idx]
            val s = text.length
            if (f.atom >= 0) text.append(st.src.atoms[f.atom]?.display ?: "·")
            else text.append(p.text, i, j)
            val r = if (st.docx) st.runFmt(p, lk, idx) else RunFmt(sizePt = t.basePt, bold = t.heading > 0)
            if (text.length > s) spans.add(Span(s, text.length, r, f.link))
            i = j
        }
        val marker = markers[p.id]
        return DocBlock.Para(p.id, text.toString().replace(SOFT_BREAK, '\n'), spans, t.basePt, t.align, t.rtl, t.indStart, t.indEnd,
            if (marker != null) 0f else t.firstLine, marker, if (marker != null) RunFmt(sizePt = t.basePt) else null,
            if (marker != null) max(t.markerWidth, 18f) else 0f, t.before, t.after, t.lineMult, t.heading, t.box)
    }
    val out = ArrayList<DocBlock>()
    for (b in blocks) when (b) {
        is EPara -> out.add(para(b))
        is ETable -> {
            val grid = Regex("gridCol\\b[^>]*?w:w=\"(\\d+)\"").findAll(b.head).map { it.groupValues[1].toFloat() / 20f }.toList()
            out.add(DocBlock.Table(b.rows.map { r -> DocBlock.Row(r.cells.map { c -> DocBlock.Cell(1, c.paras.map(::para), null, false) }) },
                if (grid.all { it > 0f }) grid else emptyList(), b.head.contains("bidiVisual"), true))
        }
        is EObject -> out.addAll(b.preview)
    }
    return out
}

// ==================================================================== status bar

@Composable
private fun StatusBar(
    st: EditorState, pages: List<LaidPage>, words: Int, list: LazyListState, zoomPct: Int,
    onOut: () -> Unit, onIn: () -> Unit, onFit: () -> Unit, narrow: Boolean,
) {
    val c = D.c
    val first = list.firstVisibleItemIndex
    val pageNo = remember(pages, st.focusId, first, st.blocks) {
        val focusPage = if (st.focusId >= 0) pageOf(pages, st.focusId, st.selection.start) else -1
        if (focusPage >= 0) focusPage + 1 else {
            var found = -1
            val start = (first - 1).coerceAtLeast(0)
            for (k in start until min(st.blocks.size, start + 50)) {
                var id = -1
                when (val b = st.blocks[k]) { is EPara -> id = b.id; is ETable -> id = b.rows.firstOrNull()?.cells?.firstOrNull()?.paras?.firstOrNull()?.id ?: -1; else -> {} }
                if (id >= 0) { found = pageOf(pages, id, 0); if (found >= 0) break }
            }
            if (found >= 0) found + 1 else 1
        }
    }
    val nf = remember { NumberFormat.getIntegerInstance() }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Row(
        Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(start = 16.dp, end = 4.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val status = when {
            st.saving -> stringResource(R.string.word_saving)
            st.saveFailed -> stringResource(R.string.word_save_failed)
            st.dirty -> stringResource(R.string.word_edited)
            st.savedAt > 0 -> stringResource(R.string.word_saved)
            else -> ""
        }
        Text(
            stringResource(R.string.word_status, pageNo.coerceAtMost(max(pages.size, 1)), max(pages.size, 1), nf.format(words)),
            style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (status.isNotEmpty()) Text(
            "  ·  $status", style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = if (st.saveFailed) c.danger else c.muted,
        )
        Spacer(Modifier.weight(1f))
        if (!narrow) ZoomControls(zoomPct, onOut, onIn, onFit, Modifier.padding(vertical = 2.dp))
        else {
            IconButton(onClick = onOut, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Remove, stringResource(R.string.zoom_out), tint = c.muted) }
            IconButton(onClick = onIn, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, stringResource(R.string.zoom_in), tint = c.muted) }
        }
    }
}

// ==================================================================== ribbon

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Ribbon(st: EditorState, narrow: Boolean, onImage: () -> Unit, onTable: () -> Unit) {
    val c = D.c
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RIcon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.word_undo), enabled = st.canUndo) { st.undo() }
            RIcon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.word_redo), enabled = st.canRedo) { st.redo() }
            if (st.src.kind != EditKind.TXT) {
                Sep()
                if (narrow) {
                    StylePicker(st)
                    CharToggles(st, compact = true)
                    RIcon(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.word_bullets), active = isList(st, true)) { st.toggleList(true) }
                    InsertMenu(st, onImage, onTable)
                    RIcon(Icons.Rounded.TextFormat, stringResource(R.string.word_more_format), active = more) { more = true }
                } else {
                    RibbonGroups(st, onImage, onTable)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = c.surface) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonGroups(st, { more = false; onImage() }, { more = false; onTable() })
        }
    }
}

private fun isList(st: EditorState, bullet: Boolean): Boolean {
    val p = st.para(st.focusId) ?: return false
    return if (st.md) Regex(if (bullet) "^\\s*[-*+]\\s" else "^\\s*\\d+[.)]\\s").containsMatchIn(p.text)
    else st.look(p).let { it.numId != null && it.bullet == bullet }
}

@Composable
private fun RibbonGroups(st: EditorState, onImage: () -> Unit, onTable: () -> Unit) {
    val docx = st.docx
    val p = st.para(st.focusId)
    val lk = p?.let { st.look(it) }
    StylePicker(st)
    if (docx) { FontPicker(st); SizePicker(st) }
    Sep()
    CharToggles(st, compact = false)
    if (docx) { ColorPicker(st); HighlightPicker(st) }
    Sep()
    if (docx) {
        val a = lk?.para?.align ?: 0
        RIcon(Icons.Rounded.FormatAlignLeft, stringResource(R.string.word_align_start), active = a == 0) { st.setAlign("left") }
        RIcon(Icons.Rounded.FormatAlignCenter, stringResource(R.string.word_align_center), active = a == 1) { st.setAlign("center") }
        RIcon(Icons.Rounded.FormatAlignRight, stringResource(R.string.word_align_end), active = a == 2) { st.setAlign("right") }
        RIcon(Icons.Rounded.FormatAlignJustify, stringResource(R.string.word_align_justify), active = a == 3) { st.setAlign("both") }
        val rtl = lk?.para?.rtl ?: false
        RIcon(if (rtl) Icons.Rounded.FormatTextdirectionRToL else Icons.Rounded.FormatTextdirectionLToR,
            stringResource(if (rtl) R.string.word_ltr else R.string.word_rtl), active = rtl) { st.setRtl(!rtl) }
        Sep()
    }
    RIcon(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.word_bullets), active = isList(st, true)) { st.toggleList(true) }
    RIcon(Icons.Rounded.FormatListNumbered, stringResource(R.string.word_numbering), active = isList(st, false)) { st.toggleList(false) }
    RIcon(Icons.AutoMirrored.Rounded.FormatIndentDecrease, stringResource(R.string.word_outdent)) { st.indent(-1) }
    RIcon(Icons.AutoMirrored.Rounded.FormatIndentIncrease, stringResource(R.string.word_indent)) { st.indent(1) }
    Sep()
    InsertMenu(st, onImage, onTable)
}

@Composable
private fun CharToggles(st: EditorState, compact: Boolean) {
    val look = if (st.docx) st.caretLook() else RunFmt()
    RIcon(Icons.Rounded.FormatBold, stringResource(R.string.word_bold), active = st.docx && look.bold) { st.toggleBold() }
    RIcon(Icons.Rounded.FormatItalic, stringResource(R.string.word_italic), active = st.docx && look.italic) { st.toggleItalic() }
    RIcon(Icons.Rounded.FormatUnderlined, stringResource(R.string.word_underline), active = st.docx && look.underline) { st.toggleUnderline() }
    if (!compact) RIcon(Icons.Rounded.FormatStrikethrough, stringResource(R.string.word_strike), active = st.docx && look.strike) { st.toggleStrike() }
}

@Composable
private fun Sep() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(D.c.line))
}

@Composable
private fun RIcon(icon: ImageVector, desc: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = D.c
    Box(
        Modifier.size(44.dp).padding(2.dp)
            .background(if (active) c.accent.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = when { !enabled -> c.muted.copy(alpha = 0.4f); active -> c.accent; else -> c.ink }, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DropChip(label: String, desc: String, minWidth: Int = 0, fontFamily: FontFamily? = null, onClick: () -> Unit) {
    val c = D.c
    Row(
        Modifier.padding(horizontal = 2.dp).height(36.dp).widthIn(min = minWidth.dp).background(c.surfaceAlt, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink, maxLines = 1, fontFamily = fontFamily,
            modifier = Modifier.widthIn(max = 120.dp), overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.ArrowDropDown, desc, tint = c.muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun styleLabel(kind: String?): String = stringResource(
    when (kind) {
        "Title" -> R.string.word_style_title; "Heading1" -> R.string.word_style_h1; "Heading2" -> R.string.word_style_h2
        "Heading3" -> R.string.word_style_h3; "Quote" -> R.string.word_style_quote; else -> R.string.word_style_normal
    },
)

@Composable
private fun StylePicker(st: EditorState) {
    var open by remember { mutableStateOf(false) }
    val p = st.para(st.focusId)
    val kind = when {
        p == null -> "Normal"
        st.md -> Regex("^(#{1,3})\\s|^>").find(p.text)?.let { mm -> if (mm.value.startsWith(">")) "Quote" else "Heading" + mm.groupValues[1].length } ?: "Normal"
        else -> st.look(p).kind ?: "Normal"
    }
    Box {
        DropChip(styleLabel(kind), stringResource(R.string.word_style), minWidth = 96) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val kinds = if (st.md) listOf("Normal", "Heading1", "Heading2", "Heading3", "Quote") else listOf("Normal", "Title", "Heading1", "Heading2", "Heading3", "Quote")
            for (k in kinds) {
                val size = when (k) { "Title" -> 22.sp; "Heading1" -> 19.sp; "Heading2" -> 17.sp; "Heading3" -> 15.sp; else -> 14.sp }
                DropdownMenuItem(
                    text = {
                        Text(styleLabel(k), fontSize = size, fontWeight = if (k.startsWith("Heading") || k == "Title") FontWeight.SemiBold else null,
                            fontStyle = if (k == "Quote") FontStyle.Italic else null, color = if (k == kind) D.c.accent else D.c.ink)
                    },
                    onClick = { open = false; st.setStyle(k) },
                )
            }
        }
    }
}

@Composable
private fun FontPicker(st: EditorState) {
    var open by remember { mutableStateOf(false) }
    val cur = st.caretLook().let { if (it.mono) "mono" else it.font }
    Box {
        DropChip(WordFonts.label(cur), stringResource(R.string.word_font), minWidth = 88, fontFamily = WordFonts.family(cur)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (k in WordFonts.keys) DropdownMenuItem(
                text = { Text(WordFonts.label(k), fontFamily = WordFonts.family(k), color = if (k == cur) D.c.accent else D.c.ink) },
                onClick = { open = false; st.setFont(k) },
            )
        }
    }
}

@Composable
private fun SizePicker(st: EditorState) {
    var open by remember { mutableStateOf(false) }
    val cur = st.caretLook().sizePt
    val label = if (abs(cur - cur.roundToInt()) < 0.05f) cur.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", cur)
    RIcon(Icons.Rounded.TextDecrease, stringResource(R.string.word_size_down)) { st.setSize(sizes.lastOrNull { it < cur - 0.05f } ?: max(1f, cur - 1f)) }
    Box {
        DropChip(label, stringResource(R.string.word_size), minWidth = 52) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in sizes) DropdownMenuItem(
                text = { Text(if (s % 1f == 0f) s.toInt().toString() else s.toString(), color = if (abs(s - cur) < 0.05f) D.c.accent else D.c.ink) },
                onClick = { open = false; st.setSize(s) },
            )
        }
    }
    RIcon(Icons.Rounded.TextIncrease, stringResource(R.string.word_size_up)) { st.setSize(sizes.firstOrNull { it > cur + 0.05f } ?: (cur + 2f)) }
}

@Composable
private fun Swatch(color: Color?, selected: Boolean, desc: String, onClick: () -> Unit) {
    val c = D.c
    Box(
        Modifier.padding(4.dp).size(32.dp).clip(CircleShape)
            .background(color ?: c.surfaceAlt, CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Icon(Icons.Rounded.Close, desc, tint = c.muted, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorPicker(st: EditorState) {
    var open by remember { mutableStateOf(false) }
    val cur = st.caretFmt().color
    Box {
        Box(Modifier.size(44.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.FormatColorText, stringResource(R.string.word_color), tint = D.c.ink, modifier = Modifier.size(22.dp))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(20.dp).height(3.dp)
                .background(cur?.takeIf { it != "auto" }?.let { hexColor(it) } ?: D.c.ink))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FlowRow(Modifier.width(220.dp).padding(8.dp)) {
                Swatch(null, cur == null || cur == "auto", stringResource(R.string.word_color_auto)) { open = false; st.setColor("auto") }
                for (h in textColors) Swatch(hexColor(h), cur.equals(h, true), h) { open = false; st.setColor(h) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighlightPicker(st: EditorState) {
    var open by remember { mutableStateOf(false) }
    val cur = st.caretFmt().highlight
    Box {
        Box(Modifier.size(44.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.FormatColorFill, stringResource(R.string.word_highlight), tint = D.c.ink, modifier = Modifier.size(22.dp))
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(20.dp).height(3.dp)
                .background(cur?.let { highlightColors[it] }?.let { Color(it) } ?: Color.Transparent))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FlowRow(Modifier.width(220.dp).padding(8.dp)) {
                Swatch(null, cur == null || cur == "none", stringResource(R.string.word_highlight_none)) { open = false; st.setHighlight("none") }
                for (h in highlightNames) Swatch(Color(highlightColors[h] ?: 0), cur == h, h) { open = false; st.setHighlight(h) }
            }
        }
    }
}

private fun hexColor(h: String): Color? = h.toLongOrNull(16)?.let { Color((0xFF000000 or it).toInt()) }

@Composable
private fun InsertMenu(st: EditorState, onImage: () -> Unit, onTable: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DropChip(stringResource(R.string.word_insert), stringResource(R.string.word_insert)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.word_insert_image)) },
                leadingIcon = { Icon(Icons.Rounded.AddPhotoAlternate, null, tint = D.c.muted) }, onClick = { open = false; onImage() })
            DropdownMenuItem(text = { Text(stringResource(R.string.word_insert_table)) },
                leadingIcon = { Icon(Icons.Rounded.GridOn, null, tint = D.c.muted) }, onClick = { open = false; onTable() })
            if (st.docx) DropdownMenuItem(text = { Text(stringResource(R.string.word_insert_page_break)) },
                leadingIcon = { Icon(Icons.Rounded.InsertPageBreak, null, tint = D.c.muted) },
                onClick = { open = false; st.insertBlocks(listOf(DocxEdit.pageBreak())) })
            DropdownMenuItem(text = { Text(stringResource(R.string.word_insert_rule)) },
                leadingIcon = { Icon(Icons.Rounded.HorizontalRule, null, tint = D.c.muted) },
                onClick = { open = false; if (st.md) st.insertLines(listOf("", "---", "")) else st.insertBlocks(listOf(DocxEdit.rule())) })
        }
    }
}

@Composable
private fun TableSizeDialog(onDismiss: () -> Unit, onOk: (Int, Int) -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var cols by remember { mutableIntStateOf(3) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.word_insert_table)) },
        text = {
            Column {
                Stepper(stringResource(R.string.word_table_rows), rows, 1, 30) { rows = it }
                Stepper(stringResource(R.string.word_table_cols), cols, 1, 10) { cols = it }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(rows, cols) }) { Text(stringResource(R.string.word_insert)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, lo: Int, hi: Int, set: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = D.c.ink)
        IconButton(onClick = { set((value - 1).coerceAtLeast(lo)) }) { Icon(Icons.Rounded.Remove, null, tint = D.c.ink) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = D.c.ink, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { set((value + 1).coerceAtMost(hi)) }) { Icon(Icons.Rounded.Add, null, tint = D.c.ink) }
    }
}

// ==================================================================== find & replace

@Composable
private fun FindReplaceBar(
    query: String, onQuery: (String) -> Unit, with: String, onWith: (String) -> Unit, count: Int, current: Int,
    onPrev: () -> Unit, onNext: () -> Unit, onReplace: () -> Unit, onReplaceAll: () -> Unit, onClose: () -> Unit,
) {
    val c = D.c
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surfaceAlt, unfocusedContainerColor = c.surfaceAlt,
    )
    BoxWithConstraints(Modifier.fillMaxWidth().background(c.surface)) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text(stringResource(R.string.word_find_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.muted) }, shape = RoundedCornerShape(12.dp), colors = colors,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onNext() }),
                    modifier = Modifier.weight(1f).focusRequester(fr))
                if (wide) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(with, onWith, singleLine = true, placeholder = { Text(stringResource(R.string.word_replace_hint)) },
                        shape = RoundedCornerShape(12.dp), colors = colors, modifier = Modifier.weight(1f))
                }
                Text(
                    when { query.isEmpty() -> ""; count == 0 -> stringResource(R.string.word_find_none); else -> stringResource(R.string.word_find_count, current + 1, count) },
                    style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.word_find_prev), tint = c.muted) }
                IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.word_find_next), tint = c.muted) }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.word_find_close), tint = c.muted) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!wide) OutlinedTextField(with, onWith, singleLine = true, placeholder = { Text(stringResource(R.string.word_replace_hint)) },
                    shape = RoundedCornerShape(12.dp), colors = colors, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                TextButton(onClick = onReplace, enabled = count > 0) { Text(stringResource(R.string.word_replace)) }
                TextButton(onClick = onReplaceAll, enabled = count > 0) { Text(stringResource(R.string.word_replace_all)) }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
}
