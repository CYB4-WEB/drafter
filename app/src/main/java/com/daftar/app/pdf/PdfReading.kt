package com.daftar.app.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.LocalColors
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import java.io.File
import kotlin.math.roundToInt

/** Reading-mode settings and positions (app-private SharedPreferences "pdf3"). */
internal class PdfPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("pdf3", Context.MODE_PRIVATE)
    var fontSize by mutableFloatStateOf(sp.getFloat("read_size", 18f))
    var lineSpacing by mutableFloatStateOf(sp.getFloat("read_spacing", 1.5f))
    var font by mutableStateOf(sp.getString("read_font", "serif") ?: "serif")
    var theme by mutableStateOf(sp.getString("read_theme", "auto") ?: "auto")
    var night by mutableStateOf(sp.getBoolean("night", false))
    var twoPages by mutableStateOf(sp.getBoolean("two_pages", false))
    var coverAlone by mutableStateOf(sp.getBoolean("cover_alone", true))

    fun save() {
        sp.edit().putFloat("read_size", fontSize).putFloat("read_spacing", lineSpacing).putString("read_font", font)
            .putString("read_theme", theme).putBoolean("night", night).putBoolean("two_pages", twoPages)
            .putBoolean("cover_alone", coverAlone).apply()
    }

    private fun posKey(f: File) = "pos:" + f.absolutePath
    fun position(f: File): Triple<String, Int, Int>? = sp.getString(posKey(f), null)?.split('|')?.takeIf { it.size == 3 }
        ?.let { Triple(it[0], it[1].toIntOrNull() ?: 0, it[2].toIntOrNull() ?: 0) }
    fun putPosition(f: File, key: String, page: Int, offset: Int) { sp.edit().putString(posKey(f), "$key|$page|$offset").apply() }
}

/** Items of the reflowed document. [key] is stable across reloads (page + position on the page). */
internal sealed class ReadItem(val key: String, val page: Int) {
    class Marker(page: Int, val ocr: Boolean) : ReadItem("m$page", page)
    class Para(page: Int, k: Int, val text: String, val size: Float, val rtl: Boolean, val chars: Int) : ReadItem("p$page:$k", page)
    class Picture(page: Int, k: Int) : ReadItem("i$page:$k", page)
    class Empty(page: Int, val canOcr: Boolean) : ReadItem("e$page", page)
}

private class ReadTheme(val bg: Color, val ink: Color, val muted: Color, val line: Color)

private fun pageItems(i: Int, t: PageText, size: Pair<Float, Float>): List<ReadItem> {
    val out = ArrayList<ReadItem>()
    out.add(ReadItem.Marker(i, t.fromOcr))
    val items = PdfBlocks.items(t, size.first, size.second)
    items.forEachIndexed { k, it ->
        when (it) {
            is PdfBlocks.Item.Text -> out.add(ReadItem.Para(i, k, it.block.text, it.block.size, it.block.rtl, it.block.text.length))
            is PdfBlocks.Item.Picture -> out.add(ReadItem.Picture(i, k))
        }
    }
    if (!t.hasText) out.add(ReadItem.Empty(i, !t.fromOcr))
    return out
}

private fun copy(ctx: Context, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("PDF", text))
        toast(ctx, ctx.getString(R.string.copied))
    }
}

/**
 * Reading mode: the PDF's text reflowed into a readable column (drawn over the editor, which stays alive underneath).
 * Pages come from the search text index (reading order, Arabic bidi fixed) or OCR for scans.
 */
@Composable
internal fun ReadingMode(
    file: File,
    session: PdfSession,
    prefs: PdfPrefs,
    ocrVersion: Int,
    startPage: Int,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    onRecognize: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val items = remember { mutableStateListOf<ReadItem>() }
    var loaded by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(session.pageCount) }
    var loading by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    // Body text size of the document: the size covering most characters (half-point buckets).
    val hist = remember { HashMap<Int, Int>() }
    var body by remember { mutableFloatStateOf(0f) }
    var restored by remember { mutableStateOf(false) }
    val saved = remember { prefs.position(file) }

    BackHandler { onClose() }

    fun learn(page: List<ReadItem>) {
        for (it in page) if (it is ReadItem.Para && it.size > 0f) {
            val b = (it.size * 2).roundToInt()
            hist[b] = (hist[b] ?: 0) + it.chars
        }
        hist.maxByOrNull { it.value }?.let { body = it.key / 2f }
    }

    LaunchedEffect(ocrVersion) {
        val index = session.textIndex
        val n = session.pageCount
        total = n
        loading = true
        val fresh = items.isEmpty()
        val buffer = ArrayList<ReadItem>()
        val anchor = list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { v -> items.getOrNull(v.index)?.key to v.offset }
        try {
            channelFlow {
                val job = coroutineContext[Job]
                index.walk(0, n - 1, isCancelled = { job?.isActive == false }) { i, raw ->
                    trySend(pageItems(i, index.effective(i, raw), session.pageSize(i)))
                }
            }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO).collect { page ->
                learn(page)
                if (fresh) items.addAll(page) else buffer.addAll(page)
                loaded = (page.firstOrNull()?.page ?: 0) + 1
            }
            if (!fresh) {
                items.clear(); items.addAll(buffer)
                anchor?.let { (k, off) -> items.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { list.scrollToItem(it, -off) } }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("PdfReading", "reflow", t)
        } finally {
            loading = false
        }
    }
    // Restore the remembered position (or open at the page being read) as soon as its item is loaded.
    LaunchedEffect(Unit) {
        snapshotFlow { items.size }.collect {
            if (restored) return@collect
            val k = saved?.first ?: "m$startPage"
            val idx = items.indexOfFirst { it.key == k }
            if (idx >= 0) {
                list.scrollToItem(idx, saved?.third ?: 0); restored = true
            } else if (!loading && items.isNotEmpty()) restored = true
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }.debounce(400).collect { (i, off) ->
            if (!restored) return@collect
            items.getOrNull(i)?.let { prefs.putPosition(file, it.key, it.page, off) }
        }
    }
    DisposableEffect(Unit) { onDispose { prefs.save() } }

    val app = D.c
    val theme = when (prefs.theme) {
        "paper" -> ReadTheme(Color(0xFFFBFAF7), Color(0xFF1F2937), Color(0xFF6B7280), Color(0xFFE4E2DC))
        "sepia" -> ReadTheme(Color(0xFFF4ECD8), Color(0xFF5B4636), Color(0xFF8A7560), Color(0xFFE3D6BA))
        "dark" -> ReadTheme(Color(0xFF15171A), Color(0xFFD9DCE1), Color(0xFF8D949E), Color(0xFF2B3038))
        else -> ReadTheme(app.bg, app.ink, app.muted, app.line)
    }
    val family = when (prefs.font) {
        "serif" -> remember { FontFamily(Font(R.font.amiri)) }
        "sans" -> remember { FontFamily(Font(R.font.cairo)) }
        else -> FontFamily.Default
    }
    val colors = app.copy(bg = theme.bg, surface = theme.bg, ink = theme.ink, muted = theme.muted, line = theme.line, dark = prefs.theme == "dark" || (prefs.theme == "auto" && app.dark))

    CompositionLocalProvider(LocalColors provides colors) {
        Column(Modifier.fillMaxSize().background(theme.bg).pointerInput(Unit) { detectTapGestures { } }) {
            // top bar
            Column(Modifier.fillMaxWidth().background(theme.bg).windowInsetsPadding(WindowInsets.statusBars)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.pdf3_exit_reading), tint = theme.ink) }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(stringResource(R.string.pdf3_reading_mode), style = MaterialTheme.typography.titleMedium, color = theme.ink, maxLines = 1)
                        Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodySmall, color = theme.muted, maxLines = 1)
                    }
                    IconButton(onClick = { settings = !settings }) {
                        Icon(Icons.Rounded.TextFields, stringResource(R.string.pdf3_reading_settings), tint = if (settings) app.accent else theme.ink)
                    }
                }
                if (loading && total > 0) LinearProgressIndicator(
                    progress = { loaded.toFloat() / total }, color = app.accent, trackColor = theme.line, modifier = Modifier.fillMaxWidth().height(2.dp),
                ) else Box(Modifier.fillMaxWidth().height(1.dp).background(theme.line))
                if (settings) ReadingSettings(prefs, theme)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (items.isEmpty() && loading) {
                    CircularProgressIndicator(Modifier.padding(32.dp), color = app.accent)
                }
                val base = TextStyle(
                    fontFamily = family, fontSize = prefs.fontSize.sp, lineHeight = (prefs.fontSize * prefs.lineSpacing).sp,
                    color = theme.ink, textDirection = TextDirection.Content, textAlign = TextAlign.Start,
                )
                SelectionContainer(Modifier.widthIn(max = 760.dp)) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 48.dp),
                    ) {
                        items(items, key = { it.key }) { it ->
                            when (it) {
                                is ReadItem.Marker -> DisableSelection {
                                    PageMarker(it, theme) {
                                        val text = items.filter { p -> p.page == it.page && p is ReadItem.Para }.joinToString("\n\n") { p -> (p as ReadItem.Para).text }
                                        if (text.isBlank()) toast(ctx, ctx.getString(R.string.pdf_no_text)) else copy(ctx, text)
                                    }
                                }
                                is ReadItem.Para -> {
                                    val lvl = if (body > 0f && it.size > 0f && it.chars < 200) when {
                                        it.size >= body * 1.45f -> 1
                                        it.size >= body * 1.18f -> 2
                                        else -> 0
                                    } else 0
                                    val style = when (lvl) {
                                        1 -> base.copy(fontSize = (prefs.fontSize * 1.45f).sp, lineHeight = (prefs.fontSize * 1.45f * 1.25f).sp, fontWeight = FontWeight.Bold)
                                        2 -> base.copy(fontSize = (prefs.fontSize * 1.2f).sp, lineHeight = (prefs.fontSize * 1.2f * 1.3f).sp, fontWeight = FontWeight.SemiBold)
                                        else -> base
                                    }
                                    Text(
                                        it.text, style = style,
                                        modifier = Modifier.fillMaxWidth().padding(top = if (lvl > 0) 14.dp else 0.dp, bottom = (prefs.fontSize * 0.6f).dp),
                                    )
                                }
                                is ReadItem.Picture -> DisableSelection { PictureRow(it.page, theme) { onJump(it.page) } }
                                is ReadItem.Empty -> DisableSelection { EmptyPageRow(it, theme, { onRecognize(it.page) }) { onJump(it.page) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageMarker(m: ReadItem.Marker, theme: ReadTheme, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(theme.line))
        Text(
            stringResource(R.string.pdf_page_label, m.page + 1) + if (m.ocr) " · " + stringResource(R.string.pdf3_ocr_badge) else "",
            style = MaterialTheme.typography.labelMedium, color = theme.muted, modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(1.dp).background(theme.line))
        IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, stringResource(R.string.pdf3_copy_page), tint = theme.muted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun PictureRow(page: Int, theme: ReadTheme, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, theme.line, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Image, null, tint = theme.muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.pdf3_image_row, page + 1), style = MaterialTheme.typography.bodyMedium, color = theme.muted)
    }
}

@Composable
private fun EmptyPageRow(e: ReadItem.Empty, theme: ReadTheme, onRecognize: () -> Unit, onView: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, theme.line, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DocumentScanner, null, tint = theme.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (e.canOcr) R.string.pdf3_no_text_scanned else R.string.pdf3_no_text_found), style = MaterialTheme.typography.bodyMedium, color = theme.muted)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.canOcr) OutlinedButton(onClick = onRecognize) { Text(stringResource(R.string.pdf3_recognize_text)) }
            TextButton(onClick = onView) { Text(stringResource(R.string.pdf3_view_page)) }
        }
    }
}

@Composable
private fun ReadingSettings(prefs: PdfPrefs, theme: ReadTheme) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.pdf3_font_size, prefs.fontSize.roundToInt()), style = MaterialTheme.typography.labelMedium, color = theme.muted)
        Slider(value = prefs.fontSize, onValueChange = { prefs.fontSize = it }, valueRange = 12f..36f, onValueChangeFinished = { prefs.save() })
        Text(stringResource(R.string.pdf3_line_spacing, "%.1f".format(prefs.lineSpacing)), style = MaterialTheme.typography.labelMedium, color = theme.muted)
        Slider(value = prefs.lineSpacing, onValueChange = { prefs.lineSpacing = it }, valueRange = 1.1f..2.2f, onValueChangeFinished = { prefs.save() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.pdf3_font_serif), prefs.font == "serif", { prefs.font = "serif"; prefs.save() })
            Chip(stringResource(R.string.pdf3_font_sans), prefs.font == "sans", { prefs.font = "sans"; prefs.save() })
            Chip(stringResource(R.string.pdf3_font_system), prefs.font == "system", { prefs.font = "system"; prefs.save() })
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.pdf3_theme_auto), prefs.theme == "auto", { prefs.theme = "auto"; prefs.save() })
            Chip(stringResource(R.string.pdf3_theme_paper), prefs.theme == "paper", { prefs.theme = "paper"; prefs.save() })
            Chip(stringResource(R.string.pdf3_theme_sepia), prefs.theme == "sepia", { prefs.theme = "sepia"; prefs.save() })
            Chip(stringResource(R.string.pdf3_theme_dark), prefs.theme == "dark", { prefs.theme = "dark"; prefs.save() })
        }
        Spacer(Modifier.height(8.dp))
    }
}
