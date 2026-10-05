package com.daftar.app.ml

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.CancellationException

/** One recognized line: text + bounding box in the input bitmap's pixels. */
class OcrLine(val text: String, val box: RectF, val rtl: Boolean)
class OcrResult(val lines: List<OcrLine>) { val text: String get() = lines.joinToString("\n") { it.text } }

/**
 * On-device text recognition for scanned PDFs and photos (Latin + Arabic), owned by ml-agent.
 *
 * - Latin script (en, fr, es, de, tr, …): Google ML Kit Text Recognition v2 through Google Play services.
 * - Arabic script (ar, fa, ur): Tesseract 5 LSTM with the tessdata_fast model, downloaded once into filesDir.
 *
 * [langs]: BCP-47 hints like "en", "ar" (empty = both). Never throws (cancellation propagates as usual);
 * empty result on failure. Heavy work runs off the main thread; it is fine to call from the main dispatcher.
 * Lines come in reading order (columns detected; Arabic rows right → left); boxes are in [Bitmap] pixels.
 * Best input for scanned pages: render at ~200–300 dpi (text ≥ 20 px high). Huge images are processed in strips.
 */
object Ocr {
    suspend fun recognize(bmp: Bitmap, langs: List<String> = listOf("en", "ar")): OcrResult = try {
        val ctx = mlContext()
        if (ctx == null) OcrResult(emptyList()) else OcrResult(OcrPipeline.recognize(ctx, bmp, langs))
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        OcrResult(emptyList())
    }

    /** True when everything needed for [langs] is on the device (models may need a one-time download). */
    suspend fun isReady(langs: List<String>): Boolean = try {
        val ctx = mlContext()
        if (ctx == null) false else {
            val l = norm(langs)
            val tess = TessEngine.available()
            val arabicOk = l.filter { it in Script.arabicScriptLangs }.all { lang ->
                tess && MlStore.tessModel(lang)?.let { MlStore.hasTess(ctx, it) } == true
            }
            val latinOk = l.none { it !in Script.arabicScriptLangs } || LatinEngine.isReady(ctx) ||
                (tess && MlStore.tessModel("en")?.let { MlStore.hasTess(ctx, it) } == true)
            arabicOk && latinOk
        }
    } catch (c: CancellationException) { throw c } catch (_: Throwable) { false }

    /**
     * Downloads what [langs] needs (Play-services Latin module; Tesseract models for Arabic script, SHA-256 checked).
     * [onProgress] 0..1. Respects the Wi-Fi-only setting. Returns [isReady] afterwards.
     */
    suspend fun prepare(langs: List<String>, onProgress: (Float) -> Unit = {}): Boolean = try {
        val ctx = mlContext()
        if (ctx == null) false else {
            val l = norm(langs)
            val steps = ArrayList<suspend ((Float) -> Unit) -> Boolean>()
            if (l.any { it !in Script.arabicScriptLangs } && !LatinEngine.isReady(ctx)) steps += { p ->
                LatinEngine.prepare(ctx, p) || (TessEngine.available() &&
                    MlStore.tessModel("en")?.let { MlStore.downloadTess(ctx, it, p) } == true)
            }
            if (TessEngine.available()) for (lang in l.filter { it in Script.arabicScriptLangs }) {
                val m = MlStore.tessModel(lang) ?: continue
                if (!MlStore.hasTess(ctx, m)) steps += { p -> MlStore.downloadTess(ctx, m, p) }
            }
            for ((i, step) in steps.withIndex()) {
                step { f -> onProgress((i + f.coerceIn(0f, 1f)) / steps.size) }
            }
            onProgress(1f)
            isReady(langs)
        }
    } catch (c: CancellationException) { throw c } catch (_: Throwable) { false }

    private fun norm(langs: List<String>) = langs.map { it.lowercase().substringBefore('-') }.ifEmpty { listOf("en", "ar") }
}

/**
 * On-device translation (ML Kit Translate; Arabic ⇄ English, also fr/es/de/tr/ur and any ML Kit language), owned by
 * ml-agent. Never throws (cancellation propagates).
 */
object Translator {
    suspend fun isReady(from: String, to: String): Boolean = try { TranslateEngine.isReady(from, to) }
        catch (c: CancellationException) { throw c } catch (_: Throwable) { false }

    /** Downloads both language models (~30 MB each; Wi-Fi only when that setting is on). [onProgress] is estimated. */
    suspend fun prepare(from: String, to: String, onProgress: (Float) -> Unit = {}): Boolean = try { TranslateEngine.prepare(from, to, onProgress) }
        catch (c: CancellationException) { throw c } catch (_: Throwable) { false }

    /**
     * Translates [texts] (keeps order and line breaks; long paragraphs are split at sentence ends); returns the input
     * unchanged when unavailable. [from] may be "und"/"auto" to detect it.
     */
    suspend fun translate(texts: List<String>, from: String, to: String): List<String> = try { TranslateEngine.translate(texts, from, to) }
        catch (c: CancellationException) { throw c } catch (_: Throwable) { texts }

    /** BCP-47 language of [text] ("ar", "en", "fr", …), or "und". Falls back to the script when Language ID is missing. */
    suspend fun detectLanguage(text: String): String = try { TranslateEngine.detect(text) }
        catch (c: CancellationException) { throw c } catch (_: Throwable) { "und" }
}
