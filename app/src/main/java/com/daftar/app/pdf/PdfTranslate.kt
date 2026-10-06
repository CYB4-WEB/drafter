package com.daftar.app.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.EditorController
import com.daftar.app.ml.Translator
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One translated block: where it sits on the page (displayed points), its source text and its translation. */
class TBlock(val box: RectF, val src: String, val dst: String)

class PageTranslation(val page: Int, val from: String, val to: String, val hash: String, val blocks: List<TBlock>)

/**
 * Translations per page and target language, persisted in the sidecar `.<name>.translate.json`
 * (`{"v":1,"pages":{"<page>:<to>":{"from":"en","hash":"…","blocks":[{"b":[l,t,r,b],"s":"…","d":"…"}]}}}`).
 * An entry is reused only while the page's source text still hashes the same.
 */
class TranslationCache(pdf: File) {
    private val file = Storage.sidecar(pdf, "translate.json")
    private val map = HashMap<String, PageTranslation>()
    private var loaded = false

    @Synchronized private fun ensure() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            val ps = JSONObject(file.readText()).optJSONObject("pages") ?: return
            for (k in ps.keys()) {
                val o = ps.getJSONObject(k)
                val page = k.substringBefore(':').toIntOrNull() ?: continue
                val to = k.substringAfter(':')
                val arr = o.optJSONArray("blocks") ?: continue
                val blocks = (0 until arr.length()).map { j ->
                    val b = arr.getJSONObject(j)
                    val r = b.getJSONArray("b")
                    TBlock(RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat()), b.optString("s"), b.optString("d"))
                }
                map[k] = PageTranslation(page, o.optString("from"), to, o.optString("hash"), blocks)
            }
        }.onFailure { Log.e("PdfTranslate", "load cache", it) }
    }

    @Synchronized fun get(page: Int, to: String): PageTranslation? { ensure(); return map["$page:$to"] }

    @Synchronized fun put(t: PageTranslation) {
        ensure()
        map["${t.page}:${t.to}"] = t
        // Keep the sidecar small: at most 400 page translations (oldest pages dropped first).
        while (map.size > 400) map.remove(map.keys.first())
        runCatching {
            val ps = JSONObject()
            for ((k, v) in map) {
                val arr = JSONArray()
                v.blocks.forEach { b ->
                    arr.put(JSONObject().put("s", b.src).put("d", b.dst)
                        .put("b", JSONArray().put(b.box.left.toDouble()).put(b.box.top.toDouble()).put(b.box.right.toDouble()).put(b.box.bottom.toDouble())))
                }
                ps.put(k, JSONObject().put("from", v.from).put("hash", v.hash).put("blocks", arr))
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("v", 1).put("pages", ps).toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }.onFailure { Log.e("PdfTranslate", "save cache", it) }
    }
}

sealed class TrStatus {
    data object Idle : TrStatus()
    data object Working : TrStatus()
    data object NoText : TrStatus()
    data class Same(val lang: String) : TrStatus()
    data class NeedsModel(val from: String, val to: String) : TrStatus()
    data object Failed : TrStatus()
    data object Done : TrStatus()
}

/** State of "Translate page" (one per opened file version). */
internal class TranslateState {
    var open by mutableStateOf(false)
    var target by mutableStateOf("ar")
    var overlay by mutableStateOf(true)
    var showOriginal by mutableStateOf(false)
    /** Translations to the current [target], by page. */
    val pages = mutableStateMapOf<Int, PageTranslation>()
    var status by mutableStateOf<TrStatus>(TrStatus.Idle)
    var statusPage by mutableIntStateOf(-1)
    var retry by mutableIntStateOf(0)
    /** The model download prompt was shown for this language pair already (then only the inline button remains). */
    var askedPair by mutableStateOf("")

    fun close() { open = false; status = TrStatus.Idle; statusPage = -1 }
}

object PdfTranslate {
    fun langName(ctx: Context, code: String): String = when (code) {
        "ar" -> ctx.getString(R.string.pdf3_lang_ar)
        "en" -> ctx.getString(R.string.pdf3_lang_en)
        else -> code
    }

    private fun hash(blocks: List<TextBlock>): String {
        var h = 1125899906842597L
        blocks.forEach { b -> b.text.forEach { h = 31 * h + it.code } ; h = 31 * h + 10 }
        return java.lang.Long.toHexString(h)
    }

    /** Guesses the page language from its letters when the detector says "und". */
    private fun guess(text: String): String {
        var ar = 0; var lat = 0
        for (c in text) {
            if (c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﻿') ar++
            else if (c in 'a'..'z' || c in 'A'..'Z') lat++
        }
        return if (ar > lat) "ar" else "en"
    }

    /** Detects the language of page [page] (text layer or OCR). Call on IO. */
    suspend fun detect(index: PdfTextIndex, page: Int): String? {
        val raw = runCatching { index.page(page) }.getOrNull() ?: return null
        val t = index.effective(page, raw)
        if (!t.hasText) return null
        val sample = t.display.take(2000)
        val d = runCatching { Translator.detectLanguage(sample) }.getOrDefault("und")
        return if (d == "ar" || d == "en") d else guess(sample)
    }

    /** Translates page [page] to [to], from the cache when the page text did not change. Call on IO. */
    suspend fun translate(index: PdfTextIndex, cache: TranslationCache, page: Int, to: String): Pair<TrStatus, PageTranslation?> {
        val raw = runCatching { index.page(page) }.getOrNull() ?: return TrStatus.NoText to null
        val t = index.effective(page, raw)
        val blocks = PdfBlocks.blocks(t).filter { it.text.any(Char::isLetterOrDigit) }
        if (blocks.isEmpty()) return TrStatus.NoText to null
        val h = hash(blocks)
        cache.get(page, to)?.takeIf { it.hash == h }?.let { return TrStatus.Done to it }
        val sample = blocks.joinToString("\n") { it.text }.take(2000)
        val d = runCatching { Translator.detectLanguage(sample) }.getOrDefault("und")
        val from = if (d == "ar" || d == "en") d else guess(sample)
        if (from == to) return TrStatus.Same(from) to null
        if (!Translator.isReady(from, to)) return TrStatus.NeedsModel(from, to) to null
        val out = Translator.translate(blocks.map { it.text }, from, to)
        if (out.size != blocks.size) return TrStatus.Failed to null
        val tr = PageTranslation(page, from, to, h, blocks.mapIndexed { k, b -> TBlock(RectF(b.box), b.text, out[k]) })
        cache.put(tr)
        return TrStatus.Done to tr
    }
}

/**
 * Draws translations over the original blocks: paper-coloured, slightly see-through boxes with the translated text
 * auto-fitted inside (right-to-left for Arabic). Layouts are built once per block (in page points) and cached.
 */
internal class TranslationOverlay(private val pages: Map<Int, PageTranslation>) : PageOverlay {
    private class Fitted(val layout: StaticLayout, val scale: Float, val box: RectF)

    private val fitted = HashMap<TBlock, Fitted>()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEEFFFDF7.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553B82F6; style = Paint.Style.STROKE; strokeWidth = 0.6f }
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2937.toInt(); isSubpixelText = true }

    override fun draw(page: Int, canvas: Canvas) {
        val tr = pages[page] ?: return
        val rtl = tr.to == "ar"
        for (b in tr.blocks) {
            if (b.dst.isBlank()) continue
            val f = synchronized(fitted) { fitted.getOrPut(b) { fit(b, rtl) } }
            canvas.drawRoundRect(f.box, 1.5f, 1.5f, bg)
            canvas.drawRoundRect(f.box, 1.5f, 1.5f, edge)
            canvas.save()
            canvas.clipRect(f.box)
            canvas.translate(f.box.left + PAD, f.box.top + PAD)
            canvas.scale(1f / f.scale, 1f / f.scale)
            f.layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Largest text size (binary search) whose layout fits the block; text is laid out [S]× larger for precise metrics. */
    private fun fit(b: TBlock, rtl: Boolean): Fitted {
        val box = RectF(b.box).apply { inset(-1.5f, -1f) }
        val w = ((box.width() - 2 * PAD) * S).toInt().coerceAtLeast(8)
        val hMax = (box.height() - 2 * PAD) * S
        fun layout(sizePt: Float): StaticLayout {
            paint.textSize = sizePt * S
            return StaticLayout.Builder.obtain(b.dst, 0, b.dst.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
        }
        var lo = 3f
        var hi = min(36f, max(4f, box.height() * 0.85f))
        var best = layout(lo)
        if (best.height <= hMax) {
            repeat(10) {
                val mid = (lo + hi) / 2f
                val l = layout(mid)
                if (l.height <= hMax) { lo = mid; best = l } else hi = mid
            }
        }
        return Fitted(best, S, box)
    }

    private companion object {
        const val PAD = 1.5f
        const val S = 4f
    }
}

private fun copyText(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF", text))
        toast(ctx, ctx.getString(R.string.copied))
    }
}

/**
 * One-time model download prompt: explains, downloads with progress (cancellable), reports failure with retry.
 * [onReady] runs after a successful download.
 */
@Composable
internal fun ModelDownloadDialog(
    title: String,
    text: String,
    prepare: suspend (onProgress: (Float) -> Unit) -> Boolean,
    onDismiss: () -> Unit,
    onReady: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var failed by remember { mutableStateOf(false) }
    val downloading = job != null
    fun start() {
        failed = false; progress = 0f
        job = scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { prepare { p -> progress = p.coerceIn(0f, 1f) } }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("PdfMl", "model download", t); false
            } finally {
                job = null
            }
            if (ok) onReady() else failed = true
        }
    }
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !downloading),
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(text, color = D.c.ink, style = MaterialTheme.typography.bodyMedium)
                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pdf3_downloading, (progress.coerceAtLeast(0f) * 100).toInt()), color = D.c.muted, style = MaterialTheme.typography.bodySmall)
                }
                if (failed) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.pdf3_download_failed), color = D.c.danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!downloading) TextButton(onClick = ::start) { Text(stringResource(if (failed) R.string.pdf3_retry else R.string.pdf3_download)) }
        },
        dismissButton = {
            TextButton(onClick = { job?.cancel(); job = null; onDismiss() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** "Translate page": pick the target language; the page's language is detected and shown. */
@Composable
internal fun TranslateDialog(index: PdfTextIndex, page: Int, initialTarget: String, onDismiss: () -> Unit, onStart: (target: String) -> Unit) {
    val ctx = LocalContext.current
    var detected by remember { mutableStateOf<String?>(null) }
    var detecting by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf(initialTarget) }
    LaunchedEffect(page) {
        val d = withContext(Dispatchers.IO) { runCatching { PdfTranslate.detect(index, page) }.getOrNull() }
        detected = d; detecting = false
        if (d != null) target = if (d == "ar") "en" else "ar"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf3_translate_page)) },
        text = {
            Column {
                Text(
                    when {
                        detecting -> stringResource(R.string.pdf3_detecting)
                        detected == null -> stringResource(R.string.pdf3_no_text_page)
                        else -> stringResource(R.string.pdf3_detected, PdfTranslate.langName(ctx, detected!!))
                    },
                    color = D.c.muted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pdf3_translate_to), style = MaterialTheme.typography.labelMedium, color = D.c.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.pdf3_lang_ar), target == "ar", { target = "ar" })
                    Chip(stringResource(R.string.pdf3_lang_en), target == "en", { target = "en" })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(target) }) { Text(stringResource(R.string.pdf3_translate)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Translation panel (end side on wide windows, bottom on narrow ones) + the overlay on the page.
 * Follows the page being read: each page is translated when it becomes current (cached).
 */
@Composable
internal fun BoxScope.TranslatePanel(
    state: TranslateState,
    index: PdfTextIndex,
    cache: TranslationCache,
    source: PdfSource,
    ctl: EditorController,
    narrow: Boolean,
    maxHeight: Dp,
    onRecognize: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val c = D.c
    val page = ctl.currentPage

    LaunchedEffect(state.open, state.target, page, state.retry) {
        if (!state.open) return@LaunchedEffect
        delay(250) // let fast page flips settle
        val p = page
        state.statusPage = p
        if (state.pages[p]?.to == state.target) { state.status = TrStatus.Done; return@LaunchedEffect }
        state.status = TrStatus.Working
        val (st, tr) = withContext(Dispatchers.IO) {
            try { PdfTranslate.translate(index, cache, p, state.target) } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                Log.e("PdfTranslate", "translate page", t); TrStatus.Failed to null
            }
        }
        if (tr != null) state.pages[p] = tr
        state.status = st
    }
    // Overlay on the page (redrawn when translations or the toggle change).
    LaunchedEffect(state.open, state.overlay) {
        if (!state.open || !state.overlay) {
            if (source.overlay != null) { source.overlay = null; ctl.refreshPages() }
            return@LaunchedEffect
        }
        snapshotFlow { state.pages.toMap() }.collect { m ->
            source.overlay = if (m.isEmpty()) null else TranslationOverlay(m)
            ctl.refreshPages()
        }
    }
    DisposableEffect(Unit) { onDispose { if (source.overlay != null) { source.overlay = null; runCatching { ctl.refreshPages() } } } }

    if (!state.open) return
    val st = state.status
    if (st is TrStatus.NeedsModel && state.askedPair != "${st.from}-${st.to}") {
        ModelDownloadDialog(
            title = stringResource(R.string.pdf3_translate_model_title),
            text = stringResource(R.string.pdf3_translate_model_text, PdfTranslate.langName(ctx, st.from), PdfTranslate.langName(ctx, st.to)),
            prepare = { cb -> Translator.prepare(st.from, st.to, cb) },
            onDismiss = { state.askedPair = "${st.from}-${st.to}" },
        ) { state.askedPair = "${st.from}-${st.to}"; state.retry++ }
    }

    val shape = RoundedCornerShape(16.dp)
    val tr = state.pages[page]?.takeIf { it.to == state.target }
    Column(
        Modifier.align(if (narrow) Alignment.BottomCenter else Alignment.BottomEnd)
            .padding(8.dp)
            .then(if (narrow) Modifier.fillMaxWidth() else Modifier.width(380.dp))
            .heightIn(max = maxHeight * (if (narrow) 0.45f else 0.62f))
            .background(c.surface, shape).border(1.dp, c.line, shape).clip(shape)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Translate, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.pdf3_translation_title, page + 1), style = MaterialTheme.typography.titleSmall, color = c.ink,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
            IconButton(onClick = { state.overlay = !state.overlay }) {
                Icon(Icons.Rounded.Layers, stringResource(R.string.pdf3_overlay), tint = if (state.overlay) c.accent else c.muted)
            }
            IconButton(enabled = tr != null, onClick = { tr?.let { copyText(ctx, it.blocks.joinToString("\n\n") { b -> b.dst }) } }) {
                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.pdf3_copy_translation), tint = if (tr != null) c.ink else c.line)
            }
            IconButton(onClick = { state.close() }) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.ink) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(stringResource(R.string.pdf3_to_lang, stringResource(R.string.pdf3_lang_ar)), state.target == "ar", {
                if (state.target != "ar") { state.target = "ar"; state.pages.clear() }
            })
            Chip(stringResource(R.string.pdf3_to_lang, stringResource(R.string.pdf3_lang_en)), state.target == "en", {
                if (state.target != "en") { state.target = "en"; state.pages.clear() }
            })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { state.showOriginal = !state.showOriginal }) {
                Text(stringResource(if (state.showOriginal) R.string.pdf3_hide_original else R.string.pdf3_show_original), style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        when {
            tr != null -> SelectionContainer {
                LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(tr.blocks) { _, b ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                b.dst, color = c.ink,
                                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (state.showOriginal) Text(
                                b.src, color = c.muted,
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start),
                                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            st is TrStatus.Working || (st is TrStatus.Done && tr == null) || state.statusPage != page -> Row(
                Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.pdf3_translating), color = c.muted)
            }
            else -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    when (st) {
                        is TrStatus.NoText -> stringResource(R.string.pdf3_no_text_page)
                        is TrStatus.Same -> stringResource(R.string.pdf3_same_language, PdfTranslate.langName(ctx, st.lang))
                        is TrStatus.NeedsModel -> stringResource(R.string.pdf3_translate_model_text, PdfTranslate.langName(ctx, st.from), PdfTranslate.langName(ctx, st.to))
                        else -> stringResource(R.string.pdf3_translate_failed)
                    },
                    color = c.muted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                when (st) {
                    is TrStatus.NoText -> OutlinedButton(onClick = { onRecognize(page) }) { Text(stringResource(R.string.pdf3_recognize_text)) }
                    is TrStatus.NeedsModel -> OutlinedButton(onClick = { state.askedPair = "" }) { Text(stringResource(R.string.pdf3_download)) }
                    is TrStatus.Failed -> OutlinedButton(onClick = { state.retry++ }) { Text(stringResource(R.string.pdf3_retry)) }
                    else -> {}
                }
            }
        }
    }
}
