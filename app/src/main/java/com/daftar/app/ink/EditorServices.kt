package com.daftar.app.ink

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

/** Handwriting → text with ML Kit Digital Ink (works offline once the language model is downloaded). */
object Handwriting {
    suspend fun isReady(lang: String): Boolean {
        val model = model(lang) ?: return false
        return RemoteModelManager.getInstance().isModelDownloaded(model).await()
    }

    suspend fun download(lang: String) {
        val model = model(lang) ?: error("unsupported language")
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await()
    }

    private fun model(lang: String): DigitalInkRecognitionModel? =
        runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang) }.getOrNull()?.let { DigitalInkRecognitionModel.builder(it).build() }

    /**
     * All recognition candidates for [strokes] taken as ONE line (math helper: the best-scoring text is often not the
     * one that parses as a calculation).
     */
    suspend fun candidates(lang: String, strokes: List<Stroke>): List<String> {
        val model = model(lang) ?: return emptyList()
        val rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        try {
            val ink = Ink.builder()
            var t = 0L
            for (s in strokes) {
                val sb = Ink.Stroke.builder()
                var i = 0
                while (i < s.pts.size) { sb.addPoint(Ink.Point.create(s.pts[i], s.pts[i + 1], t)); t += 8; i += 3 }
                ink.addStroke(sb.build())
            }
            return rec.recognize(ink.build()).await().candidates.map { it.text }
        } finally {
            rec.close()
        }
    }

    /** Groups strokes into lines (by vertical overlap) and recognizes each line. */
    suspend fun recognize(lang: String, strokes: List<Stroke>): String {
        val model = model(lang) ?: return ""
        val rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        try {
            val lines = ArrayList<MutableList<Stroke>>()
            val lineBox = ArrayList<RectF>()
            for (s in strokes.sortedBy { it.bounds().centerY() }) {
                val b = s.bounds()
                val idx = lineBox.indexOfFirst { b.centerY() in it.top..it.bottom }
                if (idx >= 0) { lines[idx].add(s); lineBox[idx].union(b) } else { lines.add(mutableListOf(s)); lineBox.add(RectF(b)) }
            }
            val out = ArrayList<String>()
            for (line in lines) {
                val ink = Ink.builder()
                var t = 0L
                // Order strokes by writing direction is unknown (Arabic is RTL) — keep drawing order (= timestamp order).
                for (s in strokes.filter { it in line }) {
                    val sb = Ink.Stroke.builder()
                    var i = 0
                    while (i < s.pts.size) { sb.addPoint(Ink.Point.create(s.pts[i], s.pts[i + 1], t)); t += 8; i += 3 }
                    ink.addStroke(sb.build())
                }
                val r = rec.recognize(ink.build()).await()
                r.candidates.firstOrNull()?.text?.let { out.add(it) }
            }
            return out.joinToString("\n")
        } finally {
            rec.close()
        }
    }
}

/** Continuous dictation with the phone's own speech recognizer (Samsung / Google engine). */
class Dictation(private val ctx: Context, private val onText: (final: String, partial: String) -> Unit, private val onState: (Boolean, Int?) -> Unit) {
    private var sr: SpeechRecognizer? = null
    private var running = false
    private var committed = StringBuilder()
    var lang = "ar-SA"

    val available get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        if (running) return
        running = true
        sr = SpeechRecognizer.createSpeechRecognizer(ctx).apply { setRecognitionListener(listener) }
        listen()
        onState(true, null)
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        if (Build.VERSION.SDK_INT >= 33) i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        sr?.startListening(i)
    }

    fun stop() {
        running = false
        sr?.stopListening(); sr?.destroy(); sr = null
        onState(false, null)
    }

    fun text() = committed.toString().trim()
    fun clear() { committed = StringBuilder() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(r: Bundle?) {
            val p = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            onText(text(), p)
        }
        override fun onResults(r: Bundle?) {
            r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                if (it.isNotBlank()) committed.append(it.trim()).append(' ')
            }
            onText(text(), "")
            if (running) listen()   // keep going until the user stops
        }
        override fun onError(error: Int) {
            if (!running) return
            // "no match" / timeout: just listen again; real errors stop and report
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listen()
            else { running = false; sr?.destroy(); sr = null; onState(false, error) }
        }
    }
}

/**
 * Lecture audio recorder (AAC .m4a).
 * [start] with `live = true` on API 33+ uses one AudioRecord capture ([LiveAudioCapture]) that also streams PCM to a
 * speech recognizer through [openPcmPipe]; anywhere else, or when that capture can't start, it is today's
 * MediaRecorder (no live transcript: [live] = false).
 */
class Recorder(private val ctx: Context) {
    private var mr: MediaRecorder? = null
    private var cap: LiveAudioCapture? = null
    var file: File? = null; private set
    var paused = false; private set

    fun start(out: File, live: Boolean = false) {
        file = out; paused = false
        if (live && Build.VERSION.SDK_INT >= 33) {
            val c = LiveAudioCapture(out)
            if (runCatching { c.start() }.isSuccess) { cap = c; return }
        }
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(32_000)
            r.setOutputFile(out.absolutePath)
            r.prepare(); r.start()
        } catch (e: Throwable) { r.release(); throw e }
        mr = r
    }

    /** True when this recording can feed a live transcript (single AudioRecord capture, API 33+). */
    val live get() = cap != null

    /** Read end of a fresh 16 kHz mono PCM16 pipe for the recognizer (caller closes it); null without live capture. */
    fun openPcmPipe(): android.os.ParcelFileDescriptor? = if (Build.VERSION.SDK_INT >= 33) cap?.openPipe() else null

    fun pause() {
        if (paused) return
        if (Build.VERSION.SDK_INT >= 33 && cap != null) cap?.pause() else runCatching { mr?.pause() }
        paused = true
    }

    fun resume() {
        if (!paused) return
        if (Build.VERSION.SDK_INT >= 33 && cap != null) cap?.resume() else runCatching { mr?.resume() }
        paused = false
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 33) cap?.stop()
        cap = null
        runCatching { mr?.stop() }
        mr?.release(); mr = null
        paused = false
    }

    val active get() = mr != null || cap != null
}

fun audioDuration(f: File): Long = runCatching {
    val p = MediaPlayer(); p.setDataSource(f.absolutePath); p.prepare(); val d = p.duration.toLong(); p.release(); d
}.getOrDefault(0L)

/**
 * Notebook → vector PDF (paper pattern + content), one PDF page per note page.
 * A whiteboard becomes one page cropped to its content plus a margin (scaled down if it exceeds the 14 400 pt PDF limit).
 * Tapes are drawn hidden.
 */
fun exportNoteToPdf(doc: InkDoc, out: File, withPaper: Boolean = true, night: Boolean = false) {
    // "export as shown": night paper colour, dimmed lines and the on-screen ink mapping (saved colours untouched)
    InkRender.setNight(night)
    try { exportNoteToPdfImpl(doc, out, withPaper, night) } finally { InkRender.setNight(false) }
}

private fun exportNoteToPdfImpl(doc: InkDoc, out: File, withPaper: Boolean, night: Boolean) {
    val pdf = PdfDocument()
    try {
        doc.pages.forEachIndexed { i, p ->
            val r = doc.exportRect(i)
            val k = minOf(1f, 14400f / maxOf(r.width(), r.height(), 1f))
            val pw = (r.width() * k).toInt().coerceAtLeast(1)
            val ph = (r.height() * k).toInt().coerceAtLeast(1)
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
            val c: Canvas = page.canvas
            c.drawColor(if (night) InkNight.PAPER else doc.paperColor)
            c.save()
            c.scale(k, k)
            c.translate(-r.left, -r.top)
            if (withPaper) InkRender.drawPaper(c, p, night, clip = r, bounded = !doc.infinite)
            InkRender.drawPageContent(c, p)
            c.restore()
            pdf.finishPage(page)
        }
        out.outputStream().use { pdf.writeTo(it) }
    } finally { pdf.close() }
}
