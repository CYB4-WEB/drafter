package com.daftar.app.ml

import android.content.Context
import android.graphics.RectF
import org.bytedeco.javacpp.Pointer
import org.bytedeco.tesseract.CANCEL_FUNC
import org.bytedeco.tesseract.ETEXT_DESC
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract
import java.util.concurrent.atomic.AtomicBoolean

/** A line found by an engine, in the coordinates of the image that engine saw. */
internal class RawLine(val text: String, val box: RectF, val conf: Float)

/**
 * Tesseract 5 (LSTM only) through the JavaCPP presets. One initialised API is kept for the last language set
 * (initialising `ara` takes a few hundred ms); every call is serialised because TessBaseAPI is not thread-safe.
 */
internal object TessEngine {
    private val lock = Any()
    private var api: TessBaseAPI? = null
    private var apiLangs: String? = null
    @Volatile private var broken = false

    /** False when the native libraries are missing for this ABI (e.g. x86_64 release builds) or failed to load. */
    fun available(): Boolean {
        if (broken) return false
        // Initialising the class runs JavaCPP's Loader, which loads the native libraries.
        return try { Class.forName("org.bytedeco.tesseract.TessBaseAPI", true, TessEngine::class.java.classLoader); true }
        catch (_: Throwable) { broken = true; false }
    }

    /** Frees the cached native API (model deleted, memory pressure). */
    fun release() = synchronized(lock) {
        runCatching { api?.End(); api?.close() }
        api = null; apiLangs = null
    }

    private fun apiFor(ctx: Context, langs: String): TessBaseAPI? {
        if (apiLangs == langs) api?.let { return it }
        release()
        return try {
            val a = TessBaseAPI()
            val rc = a.Init(MlStore.tessDir(ctx).absolutePath, langs, tesseract.OEM_LSTM_ONLY)
            if (rc != 0) { runCatching { a.close() }; null } else {
                a.SetPageSegMode(tesseract.PSM_AUTO)
                // Keep inter-word spaces as in the image; no dictionary penalties for unknown words (names, terms).
                a.SetVariable("preserve_interword_spaces", "1")
                api = a; apiLangs = langs; a
            }
        } catch (t: Throwable) {
            if (t is LinkageError || t is ExceptionInInitializerError) broken = true
            null
        }
    }

    /**
     * Recognises an 8-bit grayscale image ([gray], [w]×[h], row stride [w]). [langs] is a Tesseract language string
     * such as "ara" or "ara+eng". [cancelled] is polled by Tesseract between words. Empty list on any failure.
     */
    fun recognize(ctx: Context, gray: ByteArray, w: Int, h: Int, langs: String, dpi: Int, cancelled: AtomicBoolean): List<RawLine> {
        if (broken || w <= 0 || h <= 0) return emptyList()
        synchronized(lock) {
            val a = apiFor(ctx, langs) ?: return emptyList()
            val out = ArrayList<RawLine>()
            var monitor: ETEXT_DESC? = null
            var cancelFn: CANCEL_FUNC? = null
            try {
                a.SetImage(gray, w, h, 1, w)
                a.SetSourceResolution(dpi.coerceIn(70, 600))
                monitor = runCatching { ETEXT_DESC() }.getOrNull()
                cancelFn = runCatching {
                    object : CANCEL_FUNC() {
                        override fun call(p: Pointer?, words: Int): Boolean = cancelled.get()
                    }
                }.getOrNull()
                if (monitor != null && cancelFn != null) monitor.cancel(cancelFn)
                if (cancelled.get()) return emptyList()
                if (a.Recognize(monitor) != 0 || cancelled.get()) return emptyList()
                val it = a.GetIterator() ?: return emptyList()
                try {
                    val level = tesseract.RIL_TEXTLINE
                    val l = IntArray(1); val t = IntArray(1); val r = IntArray(1); val b = IntArray(1)
                    do {
                        val p = it.GetUTF8Text(level) ?: continue
                        val text = try { p.getString(Charsets.UTF_8) } finally { tesseract.TessDeleteText(p) }
                        val clean = text.replace('\n', ' ').trim()
                        if (clean.isEmpty()) continue
                        if (!it.BoundingBox(level, l, t, r, b)) continue
                        out += RawLine(clean, RectF(l[0].toFloat(), t[0].toFloat(), r[0].toFloat(), b[0].toFloat()), it.Confidence(level))
                    } while (it.Next(level))
                } finally {
                    runCatching { it.close() }
                }
            } catch (t: Throwable) {
                if (t is LinkageError) broken = true
                return emptyList()
            } finally {
                runCatching { a.Clear() }
                runCatching { monitor?.close() }
                runCatching { cancelFn?.close() }
            }
            return out
        }
    }
}
