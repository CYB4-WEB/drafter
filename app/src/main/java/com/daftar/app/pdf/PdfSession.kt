package com.daftar.app.pdf

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.math.min

/**
 * Everything that belongs to one opened version of a PDF: the editor's renderer, a second renderer for thumbnails,
 * the thumbnail cache, the search text index and the outline. A new session is created after every page edit
 * (the file changed), and [dispose] releases all of it.
 *
 * Memory: thumbnails ≤ 1/16 of the heap (max 32 MB, RGB_565), search text ≤ 1/32 (max 8 MB) — together well under
 * the 1/8-per-screen budget.
 */
internal class PdfSession(val file: File, density: Float) {
    val thumbPx = (200 * density).toInt().coerceIn(200, 400)
    private val maxMem = Runtime.getRuntime().maxMemory()
    private var main: PdfSource? = null
    private var thumbs: PdfSource? = null
    private var disposed = false

    private val cache = object : LruCache<Int, Bitmap>(min(maxMem / 16, 32L shl 20).toInt().coerceAtLeast(4 shl 20)) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }

    val textIndex = PdfTextIndex(file, min(maxMem / 32, 8L shl 20))

    /** Outline, loaded on first use of the Outline tab (null = not loaded yet). */
    var outline by mutableStateOf<List<OutlineEntry>?>(null)

    /** Keeps [s] as the editor's source; closes it right away if the screen already left. */
    @Synchronized fun adopt(s: PdfSource): Boolean {
        if (disposed) { s.close(); return false }
        main = s; return true
    }

    val pageCount: Int get() = main?.pageCount ?: 0

    /** Displayed size of page [i] in points. */
    fun pageSize(i: Int): Pair<Float, Float> = main?.pageSize(i) ?: (595f to 842f)

    /** Separate renderer so thumbnails never wait behind the editor's tile rendering. */
    @Synchronized private fun thumbSource(): PdfSource? {
        if (disposed) return null
        return thumbs ?: runCatching { PdfSource(file) }.onFailure { Log.e("PdfSession", "thumb renderer", it) }.getOrNull().also { thumbs = it }
    }

    fun cachedThumb(i: Int): Bitmap? = cache.get(i)?.takeIf { !it.isRecycled }

    /** Thumbnail of page [i] (blocking — call on IO). */
    fun thumb(i: Int): Bitmap? {
        cachedThumb(i)?.let { return it }
        val src = thumbSource() ?: return null
        val argb = src.renderFit(i, thumbPx, thumbPx * 3) ?: return null
        // PdfRenderer needs ARGB_8888; keep half the bytes for the cached copy.
        val small = argb.copy(Bitmap.Config.RGB_565, false) ?: argb
        if (small !== argb) argb.recycle()
        synchronized(this) {
            if (disposed) { small.recycle(); return null }
            cache.put(i, small)
        }
        return small
    }

    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true
        main?.close(); main = null
        thumbs?.close(); thumbs = null
        textIndex.clear()
        val bitmaps = cache.snapshot().values.toList()
        cache.evictAll()
        // Recycle after the current frame: the composables that drew them are gone by then.
        Handler(Looper.getMainLooper()).post { bitmaps.forEach { if (!it.isRecycled) it.recycle() } }
    }
}
