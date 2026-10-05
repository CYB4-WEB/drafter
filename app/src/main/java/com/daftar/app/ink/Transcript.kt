package com.daftar.app.ink

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

/**
 * One transcript segment of a lecture recording: [start] / [end] are ms offsets into recording [rec] (the same clock the
 * strokes' `t` uses, so transcript, audio and ink replay line up). Stored inside [Recording.transcript].
 */
@Serializable
data class TranscriptSeg(
    val start: Long,
    val end: Long,
    val text: String,
    val rec: Int = 0,
    /** BCP-47 tag the segment was recognized in ("ar-SA", "en-US"); decides its text direction in the UI. */
    val lang: String = "",
)

/** Live transcript of the recording in progress (Compose state: the strip recomposes, the editor does not). */
class LiveTranscript {
    enum class Status { OFF, STARTING, LISTENING, PAUSED, UNSUPPORTED, LANG_UNAVAILABLE, FAILED }

    val segments = mutableStateListOf<TranscriptSeg>()
    var partial by mutableStateOf("")
    var status by mutableStateOf(Status.OFF)
    var lang by mutableStateOf("ar-SA")
    /** True while the on-device recognizer is in use (shown as "on device" in the strip). */
    var onDevice by mutableStateOf(false)

    fun reset(lang: String) { segments.clear(); partial = ""; status = Status.OFF; this.lang = lang; onDevice = false }
    fun snapshot(): List<TranscriptSeg> = segments.toList()
}

/** Playback position shared by the playback bar (writer) and the transcript panel (reader) only. */
class PlayClock { var pos by mutableLongStateOf(0L) }

/** Per-device transcript settings (own preferences file: no change to the shared ink prefs). */
object TranscriptPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("transcript", Context.MODE_PRIVATE)
    fun liveOn(ctx: Context) = sp(ctx).getBoolean("live", true)
    fun setLiveOn(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("live", v).apply()
    fun stripOpen(ctx: Context) = sp(ctx).getBoolean("strip", true)
    fun setStripOpen(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("strip", v).apply()
}

object TranscriptText {
    /** Index of the segment playing at [pos] ms: the last one that started at or before it (-1 = before the first). */
    fun indexAt(segs: List<TranscriptSeg>, pos: Long): Int {
        var lo = 0; var hi = segs.size - 1; var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (segs[m].start <= pos) { ans = m; lo = m + 1 } else hi = m - 1
        }
        return ans
    }

    /** Whole transcript as plain text; [times] prefixes every segment with its "[m:ss]" position. */
    fun plain(segs: List<TranscriptSeg>, times: Boolean): String = buildString {
        for (s in segs) {
            if (s.text.isBlank()) continue
            if (times) append('[').append(fmtTime(s.start)).append("] ")
            append(s.text.trim())
            append(if (times) "\n" else " ")
        }
    }.trim()

    /** Text body of an exported `.txt`: title line, then one timed line per segment. */
    fun export(title: String, segs: List<TranscriptSeg>): String = title + "\n\n" + plain(segs, times = true) + "\n"

    /**
     * Search folding: lower case, Arabic diacritics / tatweel dropped, alef / ya / ta-marbuta / hamza-seat variants
     * unified, Arabic-Indic digits → ASCII. Length-preserving is not needed (we only test containment).
     */
    fun fold(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            when (ch) {
                in 'ً'..'ْ', 'ٰ', 'ـ' -> {}
                'أ', 'إ', 'آ', 'ٱ' -> b.append('ا')
                'ى' -> b.append('ي')
                'ة' -> b.append('ه')
                'ؤ' -> b.append('و')
                'ئ' -> b.append('ي')
                in '٠'..'٩' -> b.append('0' + (ch - '٠'))
                in '۰'..'۹' -> b.append('0' + (ch - '۰'))
                else -> b.append(ch)
            }
        }
        return b.toString()
    }

    /** Indices of segments containing every word of [query] (folded). */
    fun search(segs: List<TranscriptSeg>, query: String): List<Int> {
        val words = fold(query).split(' ', '\t', '\n').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return segs.indices.filter { i -> val t = fold(segs[i].text); words.all { t.contains(it) } }
    }

    fun isRtl(seg: TranscriptSeg): Boolean =
        if (seg.lang.isNotEmpty()) seg.lang.startsWith("ar") else seg.text.any { it in '؀'..'ۿ' }
}
