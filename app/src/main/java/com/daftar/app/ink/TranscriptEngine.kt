package com.daftar.app.ink

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Whether live transcription can be attempted on this device (it may still refuse at run time). */
object LiveTranscription {
    fun isSupported(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) || SpeechRecognizer.isRecognitionAvailable(ctx)
    }.getOrDefault(false)
}

/**
 * Turns the lecture audio (PCM pipes from [LiveAudioCapture], via [pipe]) into [TranscriptSeg]s in [state] while
 * recording. Main thread only (SpeechRecognizer's rule).
 *
 * Engine choice / fallback ladder (stepped only while a mode has not produced anything yet):
 *  0. on-device recognizer, one segmented session for the whole recording (`EXTRA_SEGMENTED_SESSION`)
 *  1. on-device recognizer, classic sessions restarted after every result / silence timeout
 *  2. default recognizer, segmented   3. default recognizer, classic
 *  → none accepted the audio: [LiveTranscript.Status.UNSUPPORTED] (the recording itself carries on).
 * A mode that worked and then fails is recreated with a back-off; after 6 failures in a row: FAILED.
 * Partial text is never lost: it is committed as a segment on restart, pause, language switch, error and stop.
 * Segment times come from [clock] (the stroke clock), estimated from when speech / the first partial was reported.
 */
@RequiresApi(33)
class LiveTranscriber(
    private val ctx: Context,
    private val state: LiveTranscript,
    private val recId: Int,
    private val pipe: () -> ParcelFileDescriptor?,
    private val clock: () -> Long,
) {
    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var gen = 0
    private var mode = 0
    private var active = false
    private var finishing: (() -> Unit)? = null
    private var readEnd: ParcelFileDescriptor? = null
    private var worked = false           // the current mode produced speech / text
    private var failures = 0
    private var uttStart = -1L
    private var lastEnd = 0L
    private var frozen = -1L
    private val onDeviceOk = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
    private val defaultOk = runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)
    private val finishTimeout = Runnable { complete() }

    private fun now() = if (frozen >= 0) frozen else clock()

    private fun modeUsable(m: Int) = if (m < 2) onDeviceOk else defaultOk
    private fun firstMode(from: Int = 0): Int = (from..3).firstOrNull { modeUsable(it) } ?: 4

    fun start() {
        mode = firstMode()
        if (mode > 3) { state.status = LiveTranscript.Status.UNSUPPORTED; return }
        active = true
        state.status = LiveTranscript.Status.STARTING
        create()
    }

    /** Language switch from the recording bar: keeps what was said so far, restarts in [lang]. */
    fun setLang(lang: String) {
        commitPartial()
        state.lang = lang
        if (state.status == LiveTranscript.Status.UNSUPPORTED || state.status == LiveTranscript.Status.PAUSED || finishing != null) return
        mode = firstMode(); failures = 0
        if (mode > 3) return
        active = true
        state.status = LiveTranscript.Status.STARTING
        create()
    }

    /** Recording paused (or transcript switched off): recognizer destroyed, nothing runs. */
    fun pause() {
        if (finishing != null) return
        commitPartial()
        active = false
        destroyRecognizer(); closeRead()
        if (state.status != LiveTranscript.Status.UNSUPPORTED) state.status = LiveTranscript.Status.PAUSED
    }

    fun resume() {
        if (finishing != null || state.status == LiveTranscript.Status.UNSUPPORTED) return
        if (mode > 3) mode = firstMode()
        if (mode > 3) return
        active = true; failures = 0
        state.status = LiveTranscript.Status.STARTING
        create()
    }

    /** Recording stopped: asks for the final words (≤ 1.5 s), then releases everything and calls [onDone]. */
    fun finish(onDone: () -> Unit) {
        frozen = clock()
        val r = sr
        if (r == null || !active) { commitPartial(); release(); onDone(); return }
        active = false
        finishing = onDone
        runCatching { r.stopListening() }
        main.postDelayed(finishTimeout, 1500)
    }

    /** Synchronous stop (editor disposed): keeps the partial text, no waiting for final results. */
    fun finishNow() {
        frozen = clock()
        finishing = null
        commitPartial()
        release()
    }

    /** Immediate teardown. */
    fun release() {
        active = false
        main.removeCallbacks(finishTimeout)
        destroyRecognizer(); closeRead()
        state.onDevice = false
        if (state.status != LiveTranscript.Status.UNSUPPORTED) state.status = LiveTranscript.Status.OFF
    }

    // ------------------------------------------------------------------ internals

    private fun complete() {
        val cb = finishing ?: return
        finishing = null
        main.removeCallbacks(finishTimeout)
        commitPartial()
        release()
        cb()
    }

    private fun create() {
        destroyRecognizer()
        val g = ++gen
        worked = false
        val r = runCatching {
            if (mode < 2) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        }.getOrNull()
        if (r == null) { stepMode(); return }
        r.setRecognitionListener(Listener(g))
        sr = r
        state.onDevice = mode < 2
        listen()
    }

    private fun intent(p: ParcelFileDescriptor?): Intent {
        val segmented = mode == 0 || mode == 2
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, state.lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (p != null) {
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, LiveAudioCapture.ASR_RATE)
        }
        if (mode < 2) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (segmented) {
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        } else {
            // classic sessions: let a pause in speech end the utterance so text arrives in lecture-sized pieces
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        return i
    }

    private fun listen() {
        if (!active) return
        val r = sr ?: return
        val p = pipe() ?: run { giveUp(LiveTranscript.Status.UNSUPPORTED); return }
        closeRead()
        readEnd = p
        runCatching { r.startListening(intent(p)) }.onFailure { stepMode() }
    }

    private fun restart(g: Int, delayMs: Long = 0) {
        main.postDelayed({ if (active && g == gen) listen() }, delayMs)
    }

    private fun recreate(delayMs: Long) {
        destroyRecognizer()
        main.postDelayed({ if (active && sr == null) create() }, delayMs)
    }

    /** The current mode was refused before producing anything: try the next one. */
    private fun stepMode() {
        val next = firstMode(mode + 1)
        if (next > 3) { giveUp(LiveTranscript.Status.UNSUPPORTED); return }
        mode = next
        main.post { if (active) create() }
    }

    private fun giveUp(s: LiveTranscript.Status) {
        commitPartial()
        active = false
        destroyRecognizer(); closeRead()
        state.onDevice = false
        state.status = s
    }

    private fun destroyRecognizer() {
        gen++
        sr?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        sr = null
    }

    private fun closeRead() { readEnd?.let { runCatching { it.close() } }; readEnd = null }

    private fun best(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun commit(text: String) {
        val t = text.trim()
        state.partial = ""
        if (t.isEmpty()) { uttStart = -1; return }
        val end = now()
        val words = t.count { it == ' ' } + 1
        val start = (if (uttStart >= 0) uttStart else end - (words * 380L).coerceAtMost(15_000)).coerceIn(lastEnd.coerceAtMost(end), end)
        state.segments.add(TranscriptSeg(start, maxOf(end, start + 200), t, recId, state.lang))
        lastEnd = end
        uttStart = -1
        failures = 0
    }

    private fun commitPartial() { if (state.partial.isNotBlank()) commit(state.partial) else state.partial = "" }

    private fun markSpeech(latency: Long) {
        worked = true
        if (uttStart < 0) uttStart = (now() - latency).coerceAtLeast(lastEnd)
    }

    private inner class Listener(private val g: Int) : RecognitionListener {
        private fun live() = g == gen

        override fun onReadyForSpeech(params: Bundle?) {
            if (live() && active) state.status = LiveTranscript.Status.LISTENING
        }
        override fun onBeginningOfSpeech() { if (live()) markSpeech(300) }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(r: Bundle?) {
            if (!live()) return
            val p = best(r)
            if (p.isEmpty()) return
            markSpeech(1000)
            state.partial = p
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (!live()) return
            worked = true
            commit(best(segmentResults).ifEmpty { state.partial })
        }

        override fun onEndOfSegmentedSession() {
            if (!live()) return
            if (finishing != null) { complete(); return }
            commitPartial()
            restart(g)
        }

        override fun onResults(r: Bundle?) {
            if (!live()) return
            commit(best(r).ifEmpty { state.partial })
            if (finishing != null) { complete(); return }
            restart(g)   // classic session over: start the next one at once (audio waits in the new pipe)
        }

        override fun onError(error: Int) {
            if (!live()) return
            if (finishing != null) { complete(); return }
            if (!active) return
            commitPartial()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { worked = true; restart(g); return }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { giveUp(LiveTranscript.Status.UNSUPPORTED); return }
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (mode < 2) {
                        // fetch the on-device model for next time, carry on with the default recognizer now
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) runCatching { sr?.triggerModelDownload(intent(null)) }
                        val next = firstMode(2)
                        if (next <= 3) { mode = next; destroyRecognizer(); main.post { if (active) create() }; return }
                    }
                    giveUp(LiveTranscript.Status.LANG_UNAVAILABLE); return
                }
            }
            if (!worked) { stepMode(); return }
            failures++
            if (failures > 6) { giveUp(LiveTranscript.Status.FAILED); return }
            recreate((failures * 700L).coerceAtMost(4000))
        }
    }
}

/**
 * The editor's handle on live transcription for the recording in progress: hides the API-33 types from the editor and
 * applies the user's on/off switch and the recording's pause state.
 */
class TranscriptSession(private val ctx: Context) {
    val state = LiveTranscript()
    /** This recording uses the shared capture, so a transcript can run (false: MediaRecorder fallback / old API). */
    var capable by androidx.compose.runtime.mutableStateOf(false); private set
    var on by androidx.compose.runtime.mutableStateOf(true); private set
    private var engine: Any? = null
    private var recPaused = false
    private var starter: (() -> Any?)? = null

    fun begin(recorder: Recorder, recId: Int, lang: String, clock: () -> Long) {
        release()
        state.reset(lang)
        recPaused = false
        on = TranscriptPrefs.liveOn(ctx)
        capable = recorder.live && Build.VERSION.SDK_INT >= 33
        if (!capable) { state.status = LiveTranscript.Status.UNSUPPORTED; return }
        starter = { if (Build.VERSION.SDK_INT >= 33) LiveTranscriber(ctx, state, recId, { recorder.openPcmPipe() }, clock).also { it.start() } else null }
        if (on) engine = starter?.invoke()
    }

    fun switchLive(v: Boolean) {
        on = v
        TranscriptPrefs.setLiveOn(ctx, v)
        if (!capable || Build.VERSION.SDK_INT < 33) return
        val e = engine as? LiveTranscriber
        if (v) {
            if (recPaused) return
            if (e == null) engine = starter?.invoke() else e.resume()
        } else {
            e?.pause()
            if (state.status != LiveTranscript.Status.UNSUPPORTED) state.status = LiveTranscript.Status.OFF
        }
    }

    fun setLang(lang: String) {
        val e = if (Build.VERSION.SDK_INT >= 33) engine as? LiveTranscriber else null
        // the engine reads state.lang when it (re)starts; only a running one needs restarting now
        if (e != null && on && !recPaused) e.setLang(lang) else state.lang = lang
    }

    fun pauseRec() { recPaused = true; if (Build.VERSION.SDK_INT >= 33) (engine as? LiveTranscriber)?.pause() }

    fun resumeRec() { recPaused = false; if (on && Build.VERSION.SDK_INT >= 33) (engine as? LiveTranscriber)?.resume() }

    /** Restarts after FAILED (user tapped Retry). */
    fun retry() {
        if (!capable || Build.VERSION.SDK_INT < 33 || recPaused) return
        (engine as? LiveTranscriber)?.release()
        engine = starter?.invoke()
    }

    /** Recording stopped: final words (async, ≤ 1.5 s), then [onDone] with the whole transcript. */
    fun finish(onDone: (List<TranscriptSeg>) -> Unit) {
        val e = if (Build.VERSION.SDK_INT >= 33) engine as? LiveTranscriber else null
        engine = null; starter = null
        if (e == null) { onDone(state.snapshot()); return }
        e.finish { onDone(state.snapshot()) }
    }

    /** Synchronous variant for dispose. */
    fun finishNow() {
        if (Build.VERSION.SDK_INT >= 33) (engine as? LiveTranscriber)?.finishNow()
        engine = null; starter = null
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= 33) (engine as? LiveTranscriber)?.release()
        engine = null; starter = null
    }
}
