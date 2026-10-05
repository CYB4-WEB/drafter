package com.daftar.app.ink

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import kotlin.math.max
import kotlin.math.min

/**
 * Night paper (dark pages), on screen only: saved colours never change.
 * - Ink: dark colours are lightened (black → near-white, navy → light blue-grey), bright colours (highlighters, red,
 *   orange…) are kept, hue and saturation are preserved ([ink]).
 * - Page bitmaps (PDF / slides): lightness inverted with the hue kept (invert + 180° hue rotation), squeezed into
 *   [#1C1F24 … #E8EAED] so white paper becomes the night paper colour and photos are not garish ([pageFilter]).
 */
object InkNight {
    /** Night paper colour (DESIGN dark surface). */
    const val PAPER = 0xFF1C1F24.toInt()
    /** Page border on night paper. */
    const val BORDER = 0xFF3A404A.toInt()

    /**
     * Display colour of ink drawn in [c] on night paper (alpha kept). Allocation-free: HSL done with locals.
     * Lightness L < 0.6 maps to 0.93 − 0.55·L (continuous at 0.6); L ≥ 0.6 is unchanged.
     */
    fun ink(c: Int): Int {
        val a = c ushr 24
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (l >= 0.6f) return c
        val d = mx - mn
        var h = 0f; var s = 0f
        if (d > 1e-5f) {
            s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f))
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } / 6f
        }
        val nl = 0.93f - 0.55f * l
        val q = if (nl < 0.5f) nl * (1f + s) else nl + s - nl * s
        val p = 2f * nl - q
        val nr = hue(p, q, h + 1f / 3f); val ng = hue(p, q, h); val nb = hue(p, q, h - 1f / 3f)
        return (a shl 24) or ((nr * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((ng * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or (nb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun hue(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 0.5f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Hue-preserving lightness inversion for page bitmaps (one shared, immutable filter). */
    val pageFilter: ColorMatrixColorFilter by lazy {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        // 180° hue rotation around the luminance axis (0.213, 0.715, 0.072): inverted colours get their hue back
        val hue = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        // squeeze into [paper … light text] so white → #1C1F24 and black → #E8EAED-ish
        val k = (232f - 28f) / 255f
        val range = ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, 28f,
            0f, k, 0f, 0f, 31f,
            0f, 0f, k, 0f, 36f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val m = ColorMatrix(invert)
        m.postConcat(hue)
        m.postConcat(range)
        ColorMatrixColorFilter(m)
    }
}
