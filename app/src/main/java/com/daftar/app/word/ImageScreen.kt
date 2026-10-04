package com.daftar.app.word

import com.daftar.app.ui.pane
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ink.ImageItem
import com.daftar.app.ink.InkDoc
import com.daftar.app.ink.InkPage
import com.daftar.app.pdf.PdfTools
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Nav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.openExternally
import com.daftar.app.ui.shareFiles
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import com.daftar.app.ui.uriFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private sealed interface ImgLoad {
    data object Loading : ImgLoad
    data object Error : ImgLoad
    class Ready(val bmp: Bitmap) : ImgLoad
}

@Composable
fun ImageScreen(path: String) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    var version by remember { mutableIntStateOf(0) }
    val load by produceState<ImgLoad>(ImgLoad.Loading, path, version) {
        value = withContext(Dispatchers.IO) { ImageIo.loadOriented(file, 4096)?.let { ImgLoad.Ready(it) } ?: ImgLoad.Error }
    }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = load as? ImgLoad.Ready

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
        val bmp = ready?.bmp ?: return
        if (busy) return
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val small = ImageIo.scaleDown(bmp, 2000)
                    val pageW = 595f
                    val pageH = pageW * small.height / small.width
                    val item = ImageItem(System.currentTimeMillis(), 0f, 0f, pageW, pageH, ImageItem.encode(small))
                    if (small !== bmp) small.recycle()
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
            if (ready != null) {
                IconButton(onClick = ::annotate, enabled = !busy) {
                    Icon(Icons.Rounded.Draw, stringResource(R.string.word_img_annotate), tint = D.c.muted)
                }
                IconButton(onClick = ::toPdf, enabled = !busy) {
                    Icon(Icons.Rounded.PictureAsPdf, stringResource(R.string.word_img_to_pdf), tint = D.c.muted)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = D.c.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (ready != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.word_img_rotate)) },
                            leadingIcon = { Icon(Icons.Rounded.Rotate90DegreesCw, null, tint = D.c.muted) },
                            enabled = !busy,
                            onClick = { menu = false; rotate() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null, tint = D.c.muted) },
                        onClick = { menu = false; shareFiles(ctx, listOf(file)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.open_externally)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = D.c.muted) },
                        onClick = { menu = false; openExternally(ctx, file) },
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxSize(), color = D.c.accent, trackColor = D.c.surfaceAlt)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val l = load) {
                ImgLoad.Loading -> CircularProgressIndicator(color = D.c.accent)
                ImgLoad.Error -> EmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.word_img_error)) {
                    Button(onClick = { openExternally(ctx, file) }) { Text(stringResource(R.string.open_externally)) }
                }
                is ImgLoad.Ready -> ZoomableImage(l.bmp, file.name)
            }
        }
    }
}

/** Pinch zoom 1×–8× around the fingers, bounded pan, double-tap toggles 2.5× at the tap point. */
@Composable
private fun ZoomableImage(bmp: Bitmap, description: String) {
    val image = remember(bmp) { bmp.asImageBitmap() }
    var scale by remember(bmp) { mutableFloatStateOf(1f) }
    var offset by remember(bmp) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val cw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val ch = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val fit = min(cw / bmp.width, ch / bmp.height)
        val fw = bmp.width * fit
        val fh = bmp.height * fit
        val center = Offset(cw / 2f, ch / 2f)
        fun clamp(o: Offset, s: Float): Offset {
            val mx = max(0f, (fw * s - cw) / 2f)
            val my = max(0f, (fh * s - ch) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(bmp, cw, ch) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val s0 = scale; val o0 = offset
                        val s1 = if (s0 > 1.05f) 1f else 2.5f
                        val k = s1 / s0
                        val o1 = if (s1 == 1f) Offset.Zero else clamp(o0 * k + (tap - center) * (1f - k), s1)
                        scope.launch {
                            animate(0f, 1f, animationSpec = tween(220)) { v, _ ->
                                scale = s0 + (s1 - s0) * v
                                offset = lerp(o0, o1, v)
                            }
                        }
                    })
                }
                .pointerInput(bmp, cw, ch) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val s0 = scale
                        val s1 = (s0 * zoom).coerceIn(1f, 8f)
                        val k = s1 / s0
                        scale = s1
                        offset = clamp(offset * k + (centroid - center) * (1f - k) + pan, s1)
                    }
                }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

/** Bitmap loading / orientation / rotation helpers for the image viewer. */
internal object ImageIo {

    /** Decodes [f] downsampled so the longest side is ≤ [maxPx], with EXIF orientation applied. */
    fun loadOriented(f: File, maxPx: Int): Bitmap? = runCatching {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching {
            ExifInterface(f.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        applyOrientation(b, orientation)
    }.getOrNull()

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

    fun scaleDown(b: Bitmap, maxPx: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= maxPx) return b
        val k = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
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
     * JPEG: lossless, by updating the EXIF orientation tag. PNG / WebP: pixels are rotated and re-encoded.
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
                val b = loadOriented(f, 4096) ?: return false
                val m = Matrix().apply { setRotate(90f) }
                val r = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                if (r !== b) b.recycle()
                val tmp = File(f.parentFile, ".${f.name}.rot.tmp")
                tmp.outputStream().use { out ->
                    val ok = if (f.extension.equals("png", true)) r.compress(Bitmap.CompressFormat.PNG, 100, out)
                    else r.compress(if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 95, out)
                    if (!ok) { tmp.delete(); return false }
                }
                r.recycle()
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) return false }
                true
            }
            else -> false
        }
    }.getOrDefault(false)
}
