package com.daftar.app.ink

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * One microphone capture feeding two consumers (API 33+, because only then can a SpeechRecognizer take its audio from
 * the app via `RecognizerIntent.EXTRA_AUDIO_SOURCE`):
 *  - AAC-LC 64 kbps, 32 kHz mono → MP4 (`.m4a`, same as the MediaRecorder recordings), through MediaCodec + MediaMuxer;
 *  - the same PCM decimated to 16 kHz mono 16-bit, written into a pipe whose read end goes to the speech recognizer.
 *
 * Threading: [start] / [pause] / [resume] / [stop] / [openPipe] are called on the main thread; one capture thread reads
 * the AudioRecord, encodes and feeds the pipe. Every pipe write end is handed to the capture thread through [pending]
 * and closed only by that thread, so a descriptor is never closed while it is being written (no fd-reuse races).
 * The write end is non-blocking: when the recognizer is between sessions or slow, ASR audio is dropped (the pipe holds
 * ~2 s), the recording itself never stalls.
 */
@RequiresApi(33)
internal class LiveAudioCapture(private val out: File) {
    companion object {
        const val RATE = 32_000
        const val ASR_RATE = 16_000
        private const val CHUNK = RATE / 50          // 20 ms
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private var rec: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    private val pending = AtomicReference<ParcelFileDescriptor?>(null)
    private val lock = Object()
    @Volatile private var running = false
    @Volatile private var paused = false
    /** Set by the capture thread when the microphone stopped delivering (another app took it, device error). */
    @Volatile var failed = false; private set

    // capture-thread state
    private var track = -1
    private var muxerStarted = false
    private var samples = 0L
    private var wroteAudio = false
    private var writeEnd: ParcelFileDescriptor? = null
    private var prev: Int = 0

    /** Sets everything up and starts capturing; throws (with everything released) when the device refuses. */
    @SuppressLint("MissingPermission")   // the editor asks for RECORD_AUDIO before recording
    fun start() {
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "AudioRecord unsupported" }
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2) * 4)
            rec = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            val fmt = MediaFormat.createAudioFormat(MIME, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            codec = c
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            r.startRecording()
            check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone busy" }
        } catch (e: Throwable) {
            releaseAll()
            out.delete()
            throw e
        }
        running = true
        thread = Thread({ loop() }, "lecture-capture").apply { start() }
    }

    /** New pipe for the next recognizer session: returns the read end (the caller owns it), the write end goes to the
     *  capture thread. Null when the pipe can't be made. */
    fun openPipe(): ParcelFileDescriptor? {
        if (!running) return null
        return try {
            val (r, w) = ParcelFileDescriptor.createPipe()
            Os.fcntlInt(w.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            // a pipe the thread never adopted is still ours to close
            pending.getAndSet(w)?.let { runCatching { it.close() } }
            r
        } catch (e: Throwable) { null }
    }

    fun pause() { paused = true }

    fun resume() { synchronized(lock) { paused = false; lock.notifyAll() } }

    /** Stops, finishes the MP4 and releases everything (blocks ≤ ~3 s while the encoder drains). */
    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread?.let { runCatching { it.join(3000) } }
        thread = null
        pending.getAndSet(null)?.let { runCatching { it.close() } }
    }

    // ---------------------------------------------------------------- capture thread

    private fun loop() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val r = rec ?: return
        val c = codec ?: return
        val buf = ShortArray(CHUNK)
        val down = ShortArray(CHUNK / 2)
        val bytes = ByteArray(CHUNK)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                if (paused) {
                    runCatching { r.stop() }
                    synchronized(lock) { while (paused && running) lock.wait() }
                    if (!running) break
                    r.startRecording()
                    continue
                }
                val n = r.read(buf, 0, buf.size)
                if (n < 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || n == AudioRecord.ERROR_INVALID_OPERATION) { failed = true; break }
                    continue
                }
                if (n == 0) continue
                encode(c, buf, n, info)
                feed(buf, n, down, bytes)
            }
        } catch (e: Throwable) {
            failed = true
        } finally {
            runCatching { r.stop() }
            finishFile(c, info)
            writeEnd?.let { runCatching { it.close() } }; writeEnd = null
            pending.getAndSet(null)?.let { runCatching { it.close() } }
            releaseAll()
            if (!wroteAudio) out.delete()
        }
    }

    private fun encode(c: MediaCodec, buf: ShortArray, n: Int, info: MediaCodec.BufferInfo) {
        var off = 0
        var misses = 0
        while (off < n) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(c, info, false); if (++misses > 50) return; continue }
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            val cnt = minOf(n - off, ib.remaining() / 2)
            ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(buf, off, cnt)
            c.queueInputBuffer(idx, 0, cnt * 2, samples * 1_000_000L / RATE, 0)
            samples += cnt; off += cnt
        }
        drain(c, info, false)
    }

    /** Moves encoded frames to the muxer; with [eos] waits for the end-of-stream frame (bounded). */
    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo, eos: Boolean) {
        var waits = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!eos || ++waits > 150) return }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (!muxerStarted) { track = m.addTrack(c.outputFormat); m.start(); muxerStarted = true }
                }
                idx >= 0 -> {
                    val ob = c.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (ob != null && info.size > 0 && muxerStarted) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, ob, info)
                        wroteAudio = true
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun finishFile(c: MediaCodec, info: MediaCodec.BufferInfo) {
        runCatching {
            val idx = c.dequeueInputBuffer(50_000)
            if (idx >= 0) c.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(c, info, true)
        }
        if (muxerStarted) runCatching { muxer?.stop() }.onFailure { wroteAudio = false }
    }

    /** 32 kHz → 16 kHz with a [1 2 1]/4 low-pass, then a non-blocking write into the recognizer's pipe. */
    private fun feed(buf: ShortArray, n: Int, down: ShortArray, bytes: ByteArray) {
        pending.getAndSet(null)?.let { np -> writeEnd?.let { runCatching { it.close() } }; writeEnd = np }
        val w = writeEnd ?: return
        var j = 0; var i = 0
        while (i + 1 < n) {
            val b = buf[i].toInt(); val c = buf[i + 1].toInt()
            down[j++] = ((prev + 2 * b + c) shr 2).toShort()
            prev = c; i += 2
        }
        for (k in 0 until j) { val v = down[k].toInt(); bytes[2 * k] = v.toByte(); bytes[2 * k + 1] = (v shr 8).toByte() }
        try {
            Os.write(w.fileDescriptor, bytes, 0, j * 2)   // ≤ PIPE_BUF: all or nothing
        } catch (e: ErrnoException) {
            // EAGAIN: recognizer not reading right now → drop this 20 ms. EPIPE / EBADF: reader gone → stop feeding.
            if (e.errno != OsConstants.EAGAIN) { runCatching { w.close() }; writeEnd = null }
        } catch (e: java.io.InterruptedIOException) {}
    }

    private fun releaseAll() {
        runCatching { rec?.release() }; rec = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { muxer?.release() }; muxer = null
    }
}
