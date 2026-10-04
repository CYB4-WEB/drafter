package com.daftar.app.ml

import android.graphics.Bitmap
import android.graphics.RectF

/** One recognized line: text + bounding box in the input bitmap's pixels. */
class OcrLine(val text: String, val box: RectF, val rtl: Boolean)
class OcrResult(val lines: List<OcrLine>) { val text: String get() = lines.joinToString("\n") { it.text } }

/**
 * STUB — owned by ml-agent. On-device text recognition for scanned PDFs and photos (Latin + Arabic).
 * [langs]: BCP-47 hints like "en", "ar". Never throws; empty result on failure. Call off the main thread.
 */
object Ocr {
    suspend fun recognize(bmp: Bitmap, langs: List<String> = listOf("en", "ar")): OcrResult = OcrResult(emptyList())
    /** True when everything needed for [langs] is on the device (models may need a one-time download). */
    suspend fun isReady(langs: List<String>): Boolean = false
    suspend fun prepare(langs: List<String>, onProgress: (Float) -> Unit = {}): Boolean = false
}

/** STUB — owned by ml-agent. On-device translation (Arabic ⇄ English at least). Never throws. */
object Translator {
    suspend fun isReady(from: String, to: String): Boolean = false
    suspend fun prepare(from: String, to: String, onProgress: (Float) -> Unit = {}): Boolean = false
    /** Translates [texts] (keeps order); returns the input unchanged when unavailable. */
    suspend fun translate(texts: List<String>, from: String, to: String): List<String> = texts
    /** "ar", "en", or "und". */
    suspend fun detectLanguage(text: String): String = "und"
}
