package com.daftar.app.word

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.ImageItem
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.ml.OcrTextSheet
import com.daftar.app.pdf.PdfTools
import com.daftar.app.ui.ConvertButton
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Screen
import com.daftar.app.ui.ViewerMenuItems
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.ZoomControls
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberViewerActions
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import com.daftar.app.ui.uriFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal sealed interface ImgLoad {
    data object Loading : ImgLoad
    data object Error : ImgLoad
    /** [base] is the screen-sized decode; [srcW]×[srcH] the full oriented size of the file. */
    class Ready(val base: Bitmap, val srcW: Int, val srcH: Int, val orientation: Int) : ImgLoad
}

/** Zoom / pan of the image: [scale] 1 = fit to the view; [offset] = pan from the centred position (px). */
private class ImageZoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/** A sharper decode of the visible part of a big photo (region decoding), in oriented source pixels. */
private class Tile(val bmp: ImageBitmap, val raw: Bitmap, val rect: Rect)

@Composable
fun ImageScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    val actions = rememberViewerActions(file)
    var version by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zoom = remember(path, version) { ImageZoom() }
    // ml-agent: OCR / translate sheet (null = closed, false = copy text, true = translate)
    var ocrSheet by remember { mutableStateOf<Boolean?>(null) }

    val savedFmt = stringResource(R.string.saved_to)
    val pdfFailed = stringResource(R.string.word_img_pdf_failed)
    val noteFailed = stringResource(R.string.word_img_note_failed)
    val rotateFailed = stringResource(R.string.word_img_rotate_failed)

    fun toPdf() {
        if (busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = file.parentFile ?: Storage.root
                    PdfTools.imagesToPdf(ctx, listOf(uriFor(ctx, file)), Storage.uniqueFile(dir, file.nameWithoutExtension, "pdf"))
                }.getOrNull()
            }
            busy = false
            if (out != null && out.exists()) { Storage.touch(); toast(ctx, String.format(savedFmt, out.name)) } else toast(ctx, pdfFailed)
        }
    }

    fun annotate() {
        if (busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val small = ImageIo.loadOriented(file, 2000) ?: return@runCatching null
                    val pageW = 595f
                    val pageH = pageW * small.height / small.width
                    val item = ImageItem(System.currentTimeMillis(), 0f, 0f, pageW, pageH, ImageItem.encode(small))
                    small.recycle()
                    val doc = InkDoc(pages = listOf(InkPage(w = pageW, h = pageH, paper = "blank", images = listOf(item))))
                    val dir = file.parentFile ?: Storage.root
                    val f = Storage.uniqueFile(dir, file.nameWithoutExtension, Storage.NOTE_EXT)
                    doc.save(f)
                    f
                }.getOrNull()
            }
            busy = false
            if (out != null && out.exists()) {
                Storage.touch()
                Storage.opened(out)
                pane.replace(Screen.Note(out.absolutePath))
            } else toast(ctx, noteFailed)
        }
    }

    fun rotate() {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ImageIo.rotateFile(file) }
            busy = false
            if (ok) { Storage.touch(); version++ } else toast(ctx, rotateFailed)
        }
    }

    Column(Modifier.fillMaxSize().background(D.c.bg)) {
        ViewerTopBar(title = file.nameWithoutExtension, onBack = { pane.back() }) {
            ConvertButton(actions)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.ink) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    ViewerMenuItems(actions, close = { menu = false })
                    HorizontalDivider(color = D.c.line)
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.word_img_rotate)) },
                        leadingIcon = { Icon(Icons.Rounded.Rotate90DegreesCw, null, tint = D.c.muted) },
                        enabled = !busy, onClick = { menu = false; rotate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.word_img_annotate)) },
                        leadingIcon = { Icon(Icons.Rounded.Draw, null, tint = D.c.muted) },
                        enabled = !busy, onClick = { menu = false; annotate() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ml_copy_text)) },
                        leadingIcon = { Icon(Icons.Rounded.TextSnippet, null, tint = D.c.muted) },
                        onClick = { menu = false; ocrSheet = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ml_translate)) },
                        leadingIcon = { Icon(Icons.Rounded.Translate, null, tint = D.c.muted) },
                        onClick = { menu = false; ocrSheet = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.word_img_to_pdf)) },
                        leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null, tint = D.c.muted) },
                        enabled = !busy, onClick = { menu = false; toPdf() },
                    )
                }
            }
        }
        ocrSheet?.let { tr ->
            OcrTextSheet(
                source = { ImageIo.loadOriented(file, 4096) },
                translate = tr,
                noteDir = file.parentFile ?: Storage.root,
                noteName = file.nameWithoutExtension,
                onNote = { f -> pane.push(Screen.Note(f.absolutePath)) },
                onDismiss = { ocrSheet = null },
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxSize(), color = D.c.accent, trackColor = D.c.surfaceAlt)
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val vw = constraints.maxWidth.coerceAtLeast(1)
            val vh = constraints.maxHeight.coerceAtLeast(1)
            // Decoded once per file / rotation for the view size at that time (not again on every resize).
            val load by produceState<ImgLoad>(ImgLoad.Loading, path, version) {
                value = withContext(Dispatchers.IO) { ImageIo.loadBase(file, vw, vh) ?: ImgLoad.Error }
            }
            when (val l = load) {
                ImgLoad.Loading -> CircularProgressIndicator(color = D.c.accent)
                ImgLoad.Error -> EmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.word_img_error)) {
                    Button(onClick = { openExternally(ctx, file) }) { Text(stringResource(R.string.open_externally)) }
                }
                is ImgLoad.Ready -> {
                    DisposableEffect(l) { onDispose { l.base.recycle() } }
                    ZoomableImage(file, l, zoom, vw.toFloat(), vh.toFloat(), file.name)
                    val fit = min(vw.toFloat() / l.srcW, vh.toFloat() / l.srcH)
                    val maxScale = maxScaleFor(fit)
                    fun zoomTo(s: Float) {
                        val s0 = zoom.scale
                        val s1 = s.coerceIn(1f, maxScale)
                        val k = s1 / s0
                        val o0 = zoom.offset
                        val o1 = clampOffset(o0 * k, s1, l, vw.toFloat(), vh.toFloat())
                        scope.launch {
                            animate(0f, 1f, animationSpec = tween(180)) { v, _ ->
                                zoom.scale = s0 + (s1 - s0) * v
                                zoom.offset = lerp(o0, o1, v)
                            }
                        }
                    }
                    ZoomControls(
                        percent = (fit * zoom.scale * 100).roundToInt(),
                        onOut = { zoomTo(zoom.scale / 1.25f) },
                        onIn = { zoomTo(zoom.scale * 1.25f) },
                        onFit = { zoomTo(1f) },
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
                    )
                }
            }
        }
    }
}

/** Up to 8× the fit size, and at least 4 screen pixels per image pixel for big photos (capped). */
private fun maxScaleFor(fit: Float): Float = max(8f, 4f / max(fit, 0.01f)).coerceAtMost(48f)

private fun clampOffset(o: Offset, s: Float, l: ImgLoad.Ready, cw: Float, ch: Float): Offset {
    val fit = min(cw / l.srcW, ch / l.srcH)
    val fw = l.srcW * fit; val fh = l.srcH * fit
    val mx = max(0f, (fw * s - cw) / 2f)
    val my = max(0f, (fh * s - ch) / 2f)
    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
}

/** Pinch (fingers) zoom around the fingers, one-pointer pan (finger or pen), double-tap 2.5× at the tap point, Ctrl+wheel. */
@Composable
private fun ZoomableImage(file: File, l: ImgLoad.Ready, zoom: ImageZoom, cw: Float, ch: Float, description: String) {
    val base = remember(l) { l.base.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val fit = min(cw / l.srcW, ch / l.srcH)
    val maxScale = maxScaleFor(fit)
    val center = Offset(cw / 2f, ch / 2f)
    var tile by remember(l) { mutableStateOf<Tile?>(null) }
    val decoder by produceState<BitmapRegionDecoder?>(null, l) {
        // Only worth it when the base decode is smaller than the file (big photos).
        value = if (l.base.width < l.srcW || l.base.height < l.srcH) withContext(Dispatchers.IO) { ImageIo.regionDecoder(file) } else null
    }
    DisposableEffect(decoder) {
        val d = decoder
        // recycle() waits for a running decode; keep that wait off the main thread
        onDispose { if (d != null) Thread { runCatching { d.recycle() } }.start() }
    }
    DisposableEffect(l) { onDispose { tile?.raw?.recycle(); tile = null } }

    fun zoomAround(factor: Float, at: Offset) {
        val s0 = zoom.scale
        val s1 = (s0 * factor).coerceIn(1f, maxScale)
        val k = s1 / s0
        zoom.scale = s1
        zoom.offset = clampOffset(zoom.offset * k + (at - center) * (1f - k), s1, l, cw, ch)
    }

    // Region decode the visible area when the screen-sized base would be upscaled (debounced after gestures settle).
    val baseDensity = l.base.width.toFloat() / l.srcW
    LaunchedEffect(l, zoom.scale, zoom.offset, decoder) {
        val dec = decoder ?: return@LaunchedEffect
        val shown = fit * zoom.scale
        if (shown <= baseDensity * 1.15f) { tile?.raw?.recycle(); tile = null; return@LaunchedEffect }
        delay(160)
        val fw = l.srcW * shown; val fh = l.srcH * shown
        val left = (cw - fw) / 2f + zoom.offset.x
        val top = (ch - fh) / 2f + zoom.offset.y
        val r = Rect(
            ((0f - left) / shown).toInt().coerceIn(0, l.srcW), ((0f - top) / shown).toInt().coerceIn(0, l.srcH),
            ceil((cw - left) / shown).toInt().coerceIn(0, l.srcW), ceil((ch - top) / shown).toInt().coerceIn(0, l.srcH),
        )
        if (r.width() <= 0 || r.height() <= 0) return@LaunchedEffect
        val cur = tile
        if (cur != null && cur.rect.contains(r) && cur.raw.width.toFloat() / cur.rect.width() >= shown * 0.9f) return@LaunchedEffect
        val t = withContext(Dispatchers.IO) { ImageIo.decodeRegion(dec, l, r, shown, (cw * ch).toLong()) }
        if (t != null) {
            val old = tile
            tile = Tile(t.asImageBitmap(), t, r)
            old?.raw?.recycle()
        }
    }

    Canvas(
        Modifier.fillMaxSize().clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(l, cw, ch) {
                detectTapGestures(onDoubleTap = { tap ->
                    val s0 = zoom.scale; val o0 = zoom.offset
                    val s1 = if (s0 > 1.05f) 1f else 2.5f.coerceAtMost(maxScale)
                    val k = s1 / s0
                    val o1 = if (s1 == 1f) Offset.Zero else clampOffset(o0 * k + (tap - center) * (1f - k), s1, l, cw, ch)
                    scope.launch {
                        animate(0f, 1f, animationSpec = tween(220)) { v, _ ->
                            zoom.scale = s0 + (s1 - s0) * v
                            zoom.offset = lerp(o0, o1, v)
                        }
                    }
                })
            }
            .pointerInput(l, cw, ch) {
                detectTransformGestures { centroid, pan, z, _ ->
                    zoomAround(z, centroid)
                    zoom.offset = clampOffset(zoom.offset + pan, zoom.scale, l, cw, ch)
                }
            }
            .ctrlWheelZoom { f, at -> zoomAround(f, at) },
    ) {
        val shown = fit * zoom.scale
        val fw = l.srcW * shown; val fh = l.srcH * shown
        val left = (cw - fw) / 2f + zoom.offset.x
        val top = (ch - fh) / 2f + zoom.offset.y
        drawImage(
            base, srcOffset = IntOffset.Zero, srcSize = IntSize(base.width, base.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(fw.roundToInt().coerceAtLeast(1), fh.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
        tile?.let { t ->
            val r = t.rect
            drawImage(
                t.bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(t.bmp.width, t.bmp.height),
                dstOffset = IntOffset((left + r.left * shown).roundToInt(), (top + r.top * shown).roundToInt()),
                dstSize = IntSize((r.width() * shown).roundToInt().coerceAtLeast(1), (r.height() * shown).roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

/** Bitmap loading / orientation / rotation helpers for the image viewer. */
internal object ImageIo {

    private fun orientationOf(f: File): Int = runCatching {
        ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun swapsAxes(o: Int) = o == ExifInterface.ORIENTATION_ROTATE_90 || o == ExifInterface.ORIENTATION_ROTATE_270 ||
        o == ExifInterface.ORIENTATION_TRANSPOSE || o == ExifInterface.ORIENTATION_TRANSVERSE

    /**
     * Screen-bounded decode: at most about 2× the view's pixel count (and ≤ 1/16 of the heap), with EXIF orientation.
     * A 48 MP photo on a 2960×1848 view decodes to ≈ 3 MP instead of 4096² (64 MB); detail comes from region decoding.
     */
    fun loadBase(f: File, viewW: Int, viewH: Int): ImgLoad.Ready? = runCatching {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val orientation = orientationOf(f)
        val heapPixels = Runtime.getRuntime().maxMemory() / 16 / 4
        val budget = min(viewW.toLong() * viewH * 2, heapPixels).coerceAtLeast(1_000_000L)
        var sample = 1
        while ((o.outWidth.toLong() / sample) * (o.outHeight.toLong() / sample) > budget) sample *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val oriented = applyOrientation(b, orientation)
        val (sw, sh) = if (swapsAxes(orientation)) o.outHeight to o.outWidth else o.outWidth to o.outHeight
        ImgLoad.Ready(oriented, sw, sh, orientation)
    }.getOrNull()

    /** Decodes [f] downsampled so the longest side is ≤ [maxPx], with EXIF orientation applied. */
    fun loadOriented(f: File, maxPx: Int): Bitmap? = runCatching {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        applyOrientation(b, orientationOf(f))
    }.getOrNull()

    @Suppress("DEPRECATION")
    fun regionDecoder(f: File): BitmapRegionDecoder? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(f.absolutePath) else BitmapRegionDecoder.newInstance(f.absolutePath, false)
    }.getOrNull()

    /**
     * Decodes oriented source rect [r] at about [shown] screen px per source px (power-of-two sampling), at most
     * ~2× [viewPixels] pixels, and returns it oriented.
     */
    fun decodeRegion(dec: BitmapRegionDecoder, l: ImgLoad.Ready, r: Rect, shown: Float, viewPixels: Long): Bitmap? = runCatching {
        val raw = toRaw(r, l.orientation, l.srcW, l.srcH)
        var sample = 1
        while (sample * 2 <= (1f / shown)) sample *= 2
        val cap = min(viewPixels * 2, Runtime.getRuntime().maxMemory() / 16 / 4)
        while ((raw.width().toLong() / sample) * (raw.height().toLong() / sample) > cap) sample *= 2
        val b = dec.decodeRegion(raw, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        applyOrientation(b, l.orientation)
    }.getOrNull()

    /** Maps a rect in oriented coordinates (image as shown, [w]×[h]) back to the raw (stored) pixel grid. */
    private fun toRaw(r: Rect, o: Int, w: Int, h: Int): Rect {
        // raw dimensions
        val rw = if (swapsAxes(o)) h else w
        val rh = if (swapsAxes(o)) w else h
        fun map(x: Int, y: Int): Pair<Int, Int> = when (o) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> (rw - x) to y
            ExifInterface.ORIENTATION_ROTATE_180 -> (rw - x) to (rh - y)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> x to (rh - y)
            ExifInterface.ORIENTATION_TRANSPOSE -> y to x
            ExifInterface.ORIENTATION_ROTATE_90 -> y to (rh - x)
            ExifInterface.ORIENTATION_TRANSVERSE -> (rw - y) to (rh - x)
            ExifInterface.ORIENTATION_ROTATE_270 -> (rw - y) to x
            else -> x to y
        }
        val (x1, y1) = map(r.left, r.top)
        val (x2, y2) = map(r.right, r.bottom)
        return Rect(min(x1, x2).coerceIn(0, rw), min(y1, y2).coerceIn(0, rh), max(x1, x2).coerceIn(0, rw), max(y1, y2).coerceIn(0, rh))
    }

    private fun applyOrientation(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return b
        }
        val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (r !== b) b.recycle()
        return r
    }

    /** EXIF orientation after one more 90° clockwise turn (mirrored values stay mirrored). */
    private fun nextOrientation(o: Int): Int = when (o) {
        ExifInterface.ORIENTATION_ROTATE_90 -> ExifInterface.ORIENTATION_ROTATE_180
        ExifInterface.ORIENTATION_ROTATE_180 -> ExifInterface.ORIENTATION_ROTATE_270
        ExifInterface.ORIENTATION_ROTATE_270 -> ExifInterface.ORIENTATION_NORMAL
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifInterface.ORIENTATION_TRANSVERSE
        ExifInterface.ORIENTATION_TRANSVERSE -> ExifInterface.ORIENTATION_FLIP_VERTICAL
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifInterface.ORIENTATION_TRANSPOSE
        ExifInterface.ORIENTATION_TRANSPOSE -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
        else -> ExifInterface.ORIENTATION_ROTATE_90
    }

    /**
     * Rotates the image file 90° clockwise and saves it.
     * JPEG: lossless, by updating the EXIF orientation tag. PNG / WebP: pixels are rotated and re-encoded at full
     * resolution when that fits in a quarter of the heap (otherwise ≤ 4096 px).
     */
    @Suppress("DEPRECATION")
    fun rotateFile(f: File): Boolean = runCatching {
        when (f.extension.lowercase()) {
            "jpg", "jpeg" -> {
                val e = ExifInterface(f.absolutePath)
                val cur = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                e.setAttribute(ExifInterface.TAG_ORIENTATION, nextOrientation(cur).toString())
                e.saveAttributes()
                true
            }
            "png", "webp" -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, o)
                val full = o.outWidth.toLong() * o.outHeight * 4 * 2 < Runtime.getRuntime().maxMemory() / 4
                val b = loadOriented(f, if (full) max(o.outWidth, o.outHeight) else 4096) ?: return false
                val m = Matrix().apply { setRotate(90f) }
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                if (r !== b) b.recycle()
                val tmp = File(f.parentFile, ".${f.name}.rot.tmp")
                tmp.outputStream().use { out ->
                    val ok = if (f.extension.equals("png", true)) r.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else r.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 95, out)
                    if (!ok) { tmp.delete(); r.recycle(); return false }
                }
                r.recycle()
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return false }
                true
            }
            else -> false
        }
    }.getOrDefault(false)
}
