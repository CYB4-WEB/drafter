package com.daftar.app.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.Storage
import com.daftar.app.data.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One flashcard. [deck] = absolute path of the subject folder ("" = General).
 * Images are file names inside `filesDir/study/img/`. SM-2 fields: [ease], [interval] (days), [reps], [due] (epoch ms), [lapses].
 */
@Serializable
data class Flashcard(
    val id: Long,
    val deck: String = "",
    val frontText: String = "",
    val frontImage: String = "",
    val backText: String = "",
    val backImage: String = "",
    /** "Lasso the answer later": the back is still to be taken from [source]. */
    val backPending: Boolean = false,
    val source: String = "",
    val page: Int = -1,
    val created: Long = 0,
    val ease: Double = 2.5,
    val interval: Int = 0,
    val reps: Int = 0,
    val due: Long = 0,
    val lapses: Int = 0,
    val lastReview: Long = 0,
) {
    val isNew get() = reps == 0 && lastReview == 0L
    fun isDue(now: Long) = due <= now
    val hasBack get() = backText.isNotBlank() || backImage.isNotEmpty()
}

/** Review grades (Anki-style four buttons on top of SM-2). */
object Grade { const val AGAIN = 0; const val HARD = 1; const val GOOD = 2; const val EASY = 3 }

/** SM-2 scheduler. Pure functions — easy to reason about and to preview on the grade buttons. */
object Sm2 {
    const val MIN_EASE = 1.3
    /** Again = relearn soon (minutes). */
    const val RELEARN_MS = 10 * 60_000L

    data class Result(val ease: Double, val interval: Int, val reps: Int, val lapses: Int, val dueIn: Long, val due: Long)

    fun schedule(c: Flashcard, grade: Int, now: Long = System.currentTimeMillis()): Result {
        var ease = c.ease
        var reps = c.reps
        var lapses = c.lapses
        val interval: Int
        when (grade) {
            Grade.AGAIN -> {
                if (reps > 0) lapses++
                reps = 0
                ease = max(MIN_EASE, ease - 0.2)
                interval = 0
            }
            Grade.HARD -> {
                interval = if (reps == 0) 1 else max(c.interval + 1, (c.interval * 1.2).roundToInt())
                ease = max(MIN_EASE, ease - 0.15)
                reps++
            }
            Grade.GOOD -> {
                interval = when (reps) { 0 -> 1; 1 -> 6; else -> max(c.interval + 1, (c.interval * ease).roundToInt()) }
                reps++
            }
            else -> {
                interval = when (reps) { 0 -> 4; 1 -> 10; else -> max(c.interval + 2, (c.interval * ease * 1.3).roundToInt()) }
                ease += 0.15
                reps++
            }
        }
        val due = if (interval == 0) now + RELEARN_MS else startOfDay(now) + interval * DAY_MS
        return Result(ease, interval, reps, lapses, due - now, due)
    }

    fun apply(c: Flashcard, grade: Int, now: Long = System.currentTimeMillis()): Flashcard {
        val r = schedule(c, grade, now)
        return c.copy(ease = r.ease, interval = r.interval, reps = r.reps, lapses = r.lapses, due = r.due, lastReview = now)
    }
}

const val DAY_MS = 24 * 3600_000L

fun startOfDay(t: Long): Long {
    val z = ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(t).atZone(z).toLocalDate().atStartOfDay(z).toInstant().toEpochMilli()
}

fun epochDay(t: Long = System.currentTimeMillis()): Long =
    java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

/**
 * Flashcard store: `filesDir/study/cards.json` + `filesDir/study/img/`. Held in memory as a Compose state list.
 * Mutations on the main thread; disk writes on one background thread, atomically (temp file + rename), coalesced.
 */
object Flashcards {
    var version by mutableIntStateOf(0)
        private set
    private val cards = mutableStateListOf<Flashcard>()
    private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "study-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending: List<Flashcard>? = null

    val ctx: Context get() = Storage.appCtx
    val dir: File get() = File(ctx.filesDir, "study").apply { mkdirs() }
    val imgDir: File get() = File(dir, "img").apply { mkdirs() }
    private val file get() = File(dir, "cards.json")

    private fun ensure() {
        if (loaded) return
        loaded = true
        runCatching { if (file.exists()) cards.addAll(json.decodeFromString<List<Flashcard>>(file.readText())) }
    }

    fun all(): List<Flashcard> { ensure(); return cards.toList() }
    fun get(id: Long): Flashcard? { ensure(); return cards.firstOrNull { it.id == id } }
    fun inDeck(deck: String?): List<Flashcard> = all().filter { deck == null || it.deck == deck }
    fun due(deck: String?, now: Long = System.currentTimeMillis()): List<Flashcard> =
        inDeck(deck).filter { it.isDue(now) && (it.hasBack || it.backPending) }.sortedBy { it.due }
    fun dueCount(deck: String? = null, now: Long = System.currentTimeMillis()) = due(deck, now).size
    fun pendingFor(source: File): List<Flashcard> = all().filter { it.backPending && it.source == source.absolutePath }

    fun newId(): Long {
        ensure()
        var id = System.currentTimeMillis()
        while (cards.any { it.id == id }) id++
        return id
    }

    fun upsert(c: Flashcard) {
        ensure()
        val i = cards.indexOfFirst { it.id == c.id }
        if (i >= 0) cards[i] = c else cards.add(c)
        changed()
    }

    fun delete(id: Long) {
        ensure()
        val c = cards.firstOrNull { it.id == id } ?: return
        cards.remove(c)
        val imgs = listOf(c.frontImage, c.backImage).filter { it.isNotEmpty() }
        io.execute { imgs.forEach { File(imgDir, it).delete() } }
        imgs.forEach { ImageCache.drop(it) }
        changed()
    }

    fun review(c: Flashcard, grade: Int) {
        upsert(Sm2.apply(c, grade))
        StudyLog.addReview()
    }

    private fun changed() {
        version++
        pending = cards.toList()
        io.execute {
            val snap = pending ?: return@execute
            pending = null
            atomicWrite(file, json.encodeToString(snap))
        }
    }

    /** Saves [bmp] as a PNG in the image folder (scaled to at most [maxSide] px) and returns its file name. Call off the main thread. */
    fun saveImage(bmp: Bitmap, tag: String, maxSide: Int = 1600): String {
        val name = "${System.currentTimeMillis()}_${(Math.random() * 1e6).toInt()}_$tag.png"
        val big = max(bmp.width, bmp.height)
        val b = if (big > maxSide) {
            val s = maxSide.toFloat() / big
            Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
        } else bmp
        val f = File(imgDir, name)
        val tmp = File(imgDir, "$name.tmp")
        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(f)
        if (b !== bmp) b.recycle()
        return name
    }

    fun deleteImageLater(name: String) {
        if (name.isEmpty()) return
        ImageCache.drop(name)
        io.execute { File(imgDir, name).delete() }
    }

    /** Runs [block] on the IO thread after queued writes, then [then] on the main thread. */
    fun onIo(block: () -> Unit, then: () -> Unit = {}) { io.execute { runCatching(block); main.post(then) } }
}

internal fun atomicWrite(f: File, text: String) {
    runCatching {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

/** Decoded card images, bounded by bytes (1/16 of the heap: review + list screens share it). */
object ImageCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun peek(name: String, maxSide: Int): Bitmap? = cache.get("$name@$maxSide")

    /** Decodes with sampling so the longest side is about [maxSide] px. Call off the main thread. */
    fun load(name: String, maxSide: Int): Bitmap? {
        if (name.isEmpty()) return null
        val key = "$name@$maxSide"
        cache.get(key)?.let { return it }
        val f = File(Flashcards.imgDir, name)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        val b = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        cache.put(key, b)
        return b
    }

    fun drop(name: String) {
        cache.snapshot().keys.filter { it.startsWith("$name@") }.forEach { cache.remove(it) }
    }

    fun trim() = cache.trimToSize(cache.maxSize() / 2)
}
