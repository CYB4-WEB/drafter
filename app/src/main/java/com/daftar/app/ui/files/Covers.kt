package com.daftar.app.ui.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.daftar.app.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Folder pictures (files-agent): `.cover.jpg` inside the folder, centre-cropped to 1:1 or 16:9 and at most
 * [MAX_PX] px, JPEG. Decoded thumbnails are kept in a small LruCache bounded by bytes; decoding is always off the
 * main thread.
 */
object Covers {
    const val MAX_PX = 512
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "covers").apply { isDaemon = true } }

    /** ≤ 1/32 of the heap, at most 12 MB: a 512×288 RGB_565 cover is ~290 KB, so dozens fit. */
    private val cache = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 32).coerceAtMost(12L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    /** Last key per folder path, so a recomposed tile shows its picture at once (no flicker). */
    private val lastKey = ConcurrentHashMap<String, String>()

    fun file(dir: File) = Storage.coverFile(dir)

    /** Cached picture of [dir] from the last load, without touching the disk (may be stale or null). */
    fun peek(dir: File): ImageBitmap? = lastKey[dir.absolutePath]?.let { cache.get(it) }

    /** Picture of [dir] or null. Blocking (disk) — call on an IO dispatcher. */
    fun load(dir: File): ImageBitmap? {
        val f = file(dir)
        if (!f.isFile) { lastKey.remove(dir.absolutePath); return null }
        val key = f.absolutePath + ":" + f.lastModified() + ":" + f.length()
        cache.get(key)?.let { lastKey[dir.absolutePath] = key; return it }
        val bmp = runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }.getOrNull() ?: return null
        val img = bmp.asImageBitmap()
        cache.put(key, img)
        lastKey[dir.absolutePath] = key
        return img
    }

    /**
     * Reads [uri] (sampled, EXIF-rotated), centre-crops it to 16:9 ([wide]) or 1:1 and scales it to ≤ [MAX_PX].
     * Blocking — IO thread. Null when the picture can't be read.
     */
    fun prepare(ctx: Context, uri: Uri, wide: Boolean): Bitmap? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PX) sample *= 2
        val src = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rot = runCatching {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        val w = if (rot == 90f || rot == 270f) src.height else src.width
        val h = if (rot == 90f || rot == 270f) src.width else src.height
        val aspect = if (wide) 16f / 9f else 1f
        // centred crop in the rotated picture's coordinates
        var cw = w; var ch = (w / aspect).toInt()
        if (ch > h) { ch = h; cw = (h * aspect).toInt() }
        val scale = minOf(1f, MAX_PX.toFloat() / maxOf(cw, ch))
        val outW = (cw * scale).toInt().coerceAtLeast(1)
        val outH = (ch * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        // source → rotated → crop offset → scale
        m.postRotate(rot)
        when (rot) {
            90f -> m.postTranslate(src.height.toFloat(), 0f)
            180f -> m.postTranslate(src.width.toFloat(), src.height.toFloat())
            270f -> m.postTranslate(0f, src.width.toFloat())
        }
        m.postTranslate(-(w - cw) / 2f, -(h - ch) / 2f)
        m.postScale(scale, scale)
        android.graphics.Canvas(out).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out
    }.getOrNull()

    /** Writes [bmp] as the picture of [dir] on a background thread, then refreshes the library. Recycles [bmp]. */
    fun saveAsync(bmp: Bitmap, dir: File) {
        writer.execute {
            runCatching {
                val f = file(dir)
                val tmp = File(dir, ".cover.jpg.tmp")
                tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
            bmp.recycle()
            main.post { Storage.touch() }
        }
    }

    /** Removes the picture of [dir]. */
    fun remove(dir: File) {
        writer.execute {
            file(dir).delete()
            lastKey.remove(dir.absolutePath)
            main.post { Storage.touch() }
        }
    }
}

/** The folder picture of [dir] (null when it has none), loaded off the main thread and refreshed with the library. */
@Composable
fun rememberCover(dir: File): ImageBitmap? {
    val v = Storage.version
    return produceState(Covers.peek(dir), dir.absolutePath, v) {
        value = withContext(Dispatchers.IO) { Covers.load(dir) }
    }.value
}

@Composable
fun CoverImage(img: ImageBitmap, modifier: Modifier = Modifier) {
    Image(img, null, modifier, contentScale = ContentScale.Crop)
}
