package com.daftar.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Script helpers shared by OCR and translation. */
internal object Script {
    fun isArabic(c: Char): Boolean = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
        c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'
    fun isLatin(c: Char): Boolean = (c in 'A'..'Z') || (c in 'a'..'z') || c in 'À'..'ɏ'

    /** Fraction of the letters/digits of [s] that are Arabic script. */
    fun arabicFraction(s: String): Float {
        var a = 0; var n = 0
        for (c in s) if (c.isLetterOrDigit()) { n++; if (isArabic(c)) a++ }
        return if (n == 0) 0f else a.toFloat() / n
    }
    fun latinFraction(s: String): Float {
        var a = 0; var n = 0
        for (c in s) if (c.isLetterOrDigit()) { n++; if (isLatin(c) || c in '0'..'9') a++ }
        return if (n == 0) 0f else a.toFloat() / n
    }
    /** True when the first strong letters are mostly Arabic script (right-to-left paragraph). */
    fun isRtl(s: String): Boolean {
        var a = 0; var l = 0
        for (c in s) { if (isArabic(c)) a++ else if (isLatin(c)) l++ }
        return a > l
    }

    val arabicScriptLangs = setOf("ar", "fa", "ur")
}

/**
 * The OCR pipeline: strips with overlap (bounded memory), preprocessing, ML Kit (Latin) + Tesseract (Arabic script),
 * merge by script and overlap, column-aware reading order. Boxes are in the input bitmap's pixels.
 */
internal object OcrPipeline {
    /** Strip height in working pixels and the overlap between strips (a line cut by one strip is whole in the next). */
    private const val STRIP = 2400
    private const val OVERLAP = 200
    /** ML Kit and Tesseract get at most this width (a 48 MP photo is scaled to it; text stays readable). */
    private const val MAX_W = 4000

    suspend fun recognize(ctx: Context, bmp: Bitmap, langsIn: List<String>): List<OcrLine> {
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return emptyList()
        val langs = langsIn.map { it.lowercase().substringBefore('-') }.ifEmpty { listOf("en", "ar") }
        val arabicLangs = langs.filter { it in Script.arabicScriptLangs }.mapNotNull { MlStore.tessModel(it) }
            .filter { MlStore.hasTess(ctx, it) }
        val wantLatin = langs.any { it !in Script.arabicScriptLangs }

        val latinOk = wantLatin && LatinEngine.isReady(ctx)
        val tessOk = TessEngine.available()
        val engModel = MlStore.tessModel("en")?.takeIf { MlStore.hasTess(ctx, it) }
        val tessNames = buildList {
            if (tessOk) {
                arabicLangs.forEach { add(it.name) }
                // No Play-services Latin recogniser: let Tesseract read Latin too when its model is on the device.
                if (wantLatin && !latinOk && engModel != null) add(engModel.name)
            }
        }
        val tessLangs = tessNames.joinToString("+").ifEmpty { null }
        if (!latinOk && tessLangs == null) return emptyList()

        val w = bmp.width; val h = bmp.height
        // Tiny inputs (a cropped snippet, a thumbnail): upscale 2× so ML Kit gets ≥ 16 px characters.
        // Measured on Arabic scans, upscaling normal-size text does NOT help Tesseract (see docs/logs/ml-agent.md).
        val k = when {
            max(w, h) < 1000 -> 2f
            w > MAX_W -> MAX_W.toFloat() / w
            else -> 1f
        }
        val stripSrc = (STRIP / k).roundToInt().coerceAtLeast(64)
        val overlapSrc = (OVERLAP / k).roundToInt().coerceAtLeast(16)
        val out = ArrayList<OcrLine>()
        var y0 = 0
        while (y0 < h) {
            coroutineContext.ensureActive()
            val y1 = min(h, y0 + stripSrc)
            val ownTop = if (y0 == 0) 0f else y0 + overlapSrc / 2f
            val ownBottom = if (y1 >= h) h.toFloat() else y1 - overlapSrc / 2f
            val lines = recognizeStrip(ctx, bmp, Rect(0, y0, w, y1), k, latinOk, tessLangs)
            for (l in lines) {
                val cy = l.box.centerY()
                if (cy >= ownTop && cy < ownBottom) out += l
            }
            if (y1 >= h) break
            y0 = y1 - overlapSrc
        }
        return Reading.order(out, w.toFloat())
    }

    private suspend fun recognizeStrip(ctx: Context, src: Bitmap, r: Rect, k: Float, latinOk: Boolean, tessLangs: String?): List<OcrLine> {
        val sw = (r.width() * k).roundToInt().coerceAtLeast(1)
        val sh = (r.height() * k).roundToInt().coerceAtLeast(1)
        val strip = withContext(Dispatchers.Default) {
            runCatching {
                Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(android.graphics.Color.WHITE)  // transparent PNGs → white paper, not black
                    Canvas(b).drawBitmap(src, r, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }.getOrNull()
        } ?: return emptyList()
        try {
            val latin = if (latinOk) LatinEngine.recognize(strip).orEmpty() else emptyList()
            coroutineContext.ensureActive()
            val arabic = if (tessLangs != null) {
                val gray = withContext(Dispatchers.Default) { Preprocess.gray(strip) }
                runTess(ctx, gray, sw, sh, tessLangs)
            } else emptyList()
            val merged = Merge.merge(latin, arabic, tessOnlyLatin = !latinOk)
            // back to input pixels
            return merged.map { l ->
                val b = l.box
                OcrLine(l.text, RectF(b.left / k + r.left, b.top / k + r.top, b.right / k + r.left, b.bottom / k + r.top), l.rtl)
            }
        } finally {
            strip.recycle()
        }
    }

    /** Runs Tesseract off the main thread; a cancelled coroutine flips the flag Tesseract polls between words. */
    private suspend fun runTess(ctx: Context, gray: ByteArray, w: Int, h: Int, langs: String): List<RawLine> = coroutineScope {
        val flag = AtomicBoolean(false)
        val watcher = launch { try { awaitCancellation() } finally { flag.set(true) } }
        try {
            withContext(Dispatchers.Default) { TessEngine.recognize(ctx, gray, w, h, langs, 300, flag) }
        } finally {
            watcher.cancel()
        }
    }
}

/** Grayscale + contrast stretch (1 % / 99 % percentiles). No denoise: a median filter erased Arabic i'jam dots in tests. */
internal object Preprocess {
    fun gray(b: Bitmap): ByteArray {
        val w = b.width; val h = b.height
        val out = ByteArray(w * h)
        val row = IntArray(w)
        val hist = IntArray(256)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            val o = y * w
            for (x in 0 until w) {
                val c = row[x]
                val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                out[o + x] = l.toByte()
                hist[l]++
            }
        }
        val total = w.toLong() * h
        var acc = 0L; var lo = 0
        while (lo < 255 && acc + hist[lo] <= total / 100) { acc += hist[lo]; lo++ }
        acc = 0L; var hi = 255
        while (hi > 0 && acc + hist[hi] <= total / 100) { acc += hist[hi]; hi-- }
        if (hi - lo < 32 || (lo <= 4 && hi >= 251)) return out  // already full range, or nearly blank
        val lut = ByteArray(256) { v -> (((v - lo) * 255f / (hi - lo)).roundToInt().coerceIn(0, 255)).toByte() }
        for (i in out.indices) out[i] = lut[out[i].toInt() and 0xFF]
        return out
    }
}

/** Combines ML Kit (Latin) and Tesseract (Arabic script) lines found in the same image. */
internal object Merge {
    private fun area(r: RectF) = max(0f, r.width()) * max(0f, r.height())
    private fun inter(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top); val r = min(a.right, b.right); val bt = min(a.bottom, b.bottom)
        return if (r > l && bt > t) (r - l) * (bt - t) else 0f
    }

    fun merge(latin: List<RawLine>, tess: List<RawLine>, tessOnlyLatin: Boolean): List<OcrLine> {
        if (tess.isEmpty()) return latin.map { OcrLine(it.text, it.box, Script.isRtl(it.text)) }
        // Tesseract read Latin too (no ML Kit): keep its lines, drop obvious noise.
        if (tessOnlyLatin || latin.isEmpty()) {
            return tess.filter { keepAlone(it) || tessOnlyLatin && it.conf >= 30 }.map { OcrLine(it.text, it.box, Script.isRtl(it.text)) }
        }
        val dropTess = BooleanArray(tess.size)
        val dropLatin = BooleanArray(latin.size)
        for ((i, l) in latin.withIndex()) {
            val la = area(l.box).coerceAtLeast(1f)
            for ((j, t) in tess.withIndex()) {
                val ov = inter(l.box, t.box)
                if (ov <= 0.3f * min(la, area(t.box).coerceAtLeast(1f))) continue
                val ta = area(t.box).coerceAtLeast(1f)
                val tScore = t.conf * Script.arabicFraction(t.text)
                val lScore = l.conf * Script.latinFraction(l.text)
                when {
                    // A short Latin word inside an Arabic line (e.g. "DNA" in an Arabic sentence): the Arabic line has it.
                    la < 0.5f * ta && Script.arabicFraction(t.text) >= 0.5f -> dropLatin[i] = true
                    tScore >= lScore -> dropLatin[i] = true
                    else -> dropTess[j] = true
                }
            }
        }
        val out = ArrayList<OcrLine>()
        latin.forEachIndexed { i, l -> if (!dropLatin[i]) out += OcrLine(l.text, l.box, Script.isRtl(l.text)) }
        tess.forEachIndexed { j, t -> if (!dropTess[j] && keepAlone(t)) out += OcrLine(t.text, t.box, Script.isRtl(t.text)) }
        return out
    }

    /** An Arabic-model line with no Latin rival: keep real Arabic text, drop what the model invents on pictures/rules. */
    private fun keepAlone(t: RawLine): Boolean {
        val letters = t.text.count { it.isLetterOrDigit() }
        if (letters == 0) return false
        val af = Script.arabicFraction(t.text)
        return (af >= 0.5f && t.conf >= 20f) || t.conf >= 60f || (letters >= 12 && af >= 0.8f)
    }
}

/** Reading order: column detection (gutters), then rows top → bottom; RTL rows right → left. */
internal object Reading {
    fun order(lines: List<OcrLine>, pageW: Float): List<OcrLine> {
        if (lines.size < 2) return lines
        val rtlPage = lines.count { it.rtl } * 2 > lines.size
        val heights = lines.map { it.box.height() }.sorted()
        val medH = heights[heights.size / 2].coerceAtLeast(1f)
        val gutters = gutters(lines, pageW, medH)
        if (gutters.isEmpty()) return rows(lines, rtlPage)

        fun columnOf(l: OcrLine): Int = gutters.count { l.box.centerX() > it }
        fun spans(l: OcrLine): Boolean = gutters.any { l.box.left < it - medH * 0.3f && l.box.right > it + medH * 0.3f }

        val sorted = lines.sortedBy { it.box.top }
        val out = ArrayList<OcrLine>(lines.size)
        val section = ArrayList<OcrLine>()
        fun flush() {
            if (section.isEmpty()) return
            val cols = section.groupBy { columnOf(it) }
            val keys = if (rtlPage) cols.keys.sortedDescending() else cols.keys.sorted()
            for (c in keys) out += rows(cols.getValue(c), rtlPage)
            section.clear()
        }
        for (l in sorted) {
            if (spans(l)) { flush(); out += l } else section += l
        }
        flush()
        return out
    }

    /** x positions of vertical white gutters crossed by no (narrow) line, with enough lines on both sides. */
    private fun gutters(lines: List<OcrLine>, pageW: Float, medH: Float): List<Float> {
        val narrow = lines.filter { it.box.width() < pageW * 0.6f }
        if (narrow.size < 4) return emptyList()
        val bins = 200
        val bw = pageW / bins
        val cover = IntArray(bins)
        for (l in narrow) {
            val a = (l.box.left / bw).toInt().coerceIn(0, bins - 1)
            val b = (l.box.right / bw).toInt().coerceIn(0, bins - 1)
            for (i in a..b) cover[i]++
        }
        val minX = narrow.minOf { it.box.left }; val maxX = narrow.maxOf { it.box.right }
        val minRun = max(3, (max(pageW * 0.015f, medH) / bw).toInt())
        val out = ArrayList<Float>()
        var i = (minX / bw).toInt().coerceIn(0, bins - 1)
        val end = (maxX / bw).toInt().coerceIn(0, bins - 1)
        while (i <= end) {
            if (cover[i] == 0) {
                var j = i
                while (j + 1 <= end && cover[j + 1] == 0) j++
                val x = (i + j + 1) / 2f * bw
                if (j - i + 1 >= minRun && x > pageW * 0.1f && x < pageW * 0.9f) {
                    val left = narrow.count { it.box.right <= x }
                    val right = narrow.count { it.box.left >= x }
                    if (left >= 2 && right >= 2) out += x
                }
                i = j + 1
            } else i++
        }
        return out
    }

    /** Groups lines into rows by vertical overlap; within a row RTL lines go right → left. */
    private fun rows(lines: List<OcrLine>, rtlPage: Boolean): List<OcrLine> {
        val sorted = lines.sortedBy { it.box.centerY() }
        val out = ArrayList<OcrLine>(lines.size)
        var row = ArrayList<OcrLine>()
        var top = 0f; var bottom = 0f
        fun flush() {
            if (row.isEmpty()) return
            val rtl = row.count { it.rtl } * 2 > row.size || (row.count { it.rtl } * 2 == row.size && rtlPage)
            out += if (rtl) row.sortedByDescending { it.box.right } else row.sortedBy { it.box.left }
            row = ArrayList()
        }
        for (l in sorted) {
            if (row.isNotEmpty()) {
                val ov = min(bottom, l.box.bottom) - max(top, l.box.top)
                if (ov < 0.5f * min(bottom - top, l.box.height())) flush()
            }
            if (row.isEmpty()) { top = l.box.top; bottom = l.box.bottom } else { top = min(top, l.box.top); bottom = max(bottom, l.box.bottom) }
            row += l
        }
        flush()
        return out
    }
}
