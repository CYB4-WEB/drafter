package com.daftar.app.onenote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Decoded pictures of an open notebook. Bounded by bytes (1/8 of the heap), decoded with sampling to the size they are
 * shown at; [release] recycles everything when the screen goes away.
 */
internal class OneBitmaps {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val bounds = HashMap<String, IntArray>()
    @Volatile private var released = false

    private fun id(b: Blob) = "${b.path}@${b.offset}"

    /** Pixel size of the picture (null when it is not a format Android can decode, e.g. EMF/WMF). */
    fun size(b: Blob): IntArray? {
        synchronized(bounds) { bounds[id(b)]?.let { return it.takeIf { s -> s[0] > 0 } } }
        val bytes = b.read()
        val r = if (bytes == null) intArrayOf(0, 0) else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            intArrayOf(o.outWidth.coerceAtLeast(0), o.outHeight.coerceAtLeast(0))
        }
        synchronized(bounds) { bounds[id(b)] = r }
        return r.takeIf { it[0] > 0 }
    }

    fun cached(b: Blob, maxPx: Int): Bitmap? = cache.get(key(b, sampleFor(b, maxPx)))?.takeIf { !it.isRecycled }

    /** Picture decoded so its longest side is about [maxPx] (power-of-two sampling). Call off the main thread. */
    fun get(b: Blob, maxPx: Int): Bitmap? {
        if (released) return null
        val sample = sampleFor(b, maxPx)
        val k = key(b, sample)
        cache.get(k)?.let { if (!it.isRecycled) return it }
        val bytes = b.read() ?: return null
        val bmp = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return null
        if (released) { bmp.recycle(); return null }
        cache.put(k, bmp)
        return bmp
    }

    private fun sampleFor(b: Blob, maxPx: Int): Int {
        val s = size(b) ?: return 1
        var sample = 1
        val longest = maxOf(s[0], s[1])
        while (longest / (sample * 2) >= maxPx.coerceAtLeast(64)) sample *= 2
        return sample
    }

    private fun key(b: Blob, sample: Int) = id(b) + "/" + sample

    fun release() {
        released = true
        val all = cache.snapshot().values
        cache.evictAll()
        all.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** Decodes a picture for export (PDF / note) with a size cap, independent of the screen cache. */
internal fun decodeForExport(b: Blob, maxPx: Int): Bitmap? {
    val bytes = b.read() ?: return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    if (o.outWidth <= 0) return null
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
}
