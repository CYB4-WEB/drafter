package com.daftar.app.data

import android.os.Handler
import android.os.Looper
import com.daftar.app.ink.InkDoc
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Note version history (files-agent).
 *
 * Snapshots are gzip copies of the note JSON in `<app files>/.versions/<path of the note relative to the library>/`,
 * named `v<created>_<original bytes>.gz`. The directory mirrors the library tree, so moving or renaming a note or a
 * folder only has to move one directory ([moved]); a deleted note takes its versions into the trash entry.
 *
 * Retention: at most one snapshot per [BUCKET_MS] of editing — while the newest snapshot is younger than that it is
 * overwritten with the latest state (so the newest state of every 10-minute window is kept), then a new one starts.
 * Old snapshots are pruned to [MAX_COUNT] and [MAX_AGE_MS]; the newest one is always kept.
 *
 * [capture] is cheap: the editor saves every few seconds while drawing, so the real write is debounced on one
 * background thread ([DEBOUNCE_MS] after the last save) — a one-shot task, never a periodic wakeup.
 */
object Versions {
    const val BUCKET_MS = 10 * 60_000L
    const val MAX_COUNT = 30
    const val MAX_AGE_MS = 30L * 24 * 3600_000L
    private const val DEBOUNCE_MS = 15_000L

    /** Single worker shared by capture / prune; daemon so it never keeps the process alive. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "versions").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val lock = Any()

    /** One snapshot. [time] = when this state was saved (file mtime), [created] = start of its 10-minute window. */
    data class Version(val file: File, val created: Long, val time: Long, val bytes: Long)

    fun baseDir(): File = File(Storage.root.parentFile, ".versions")

    /** Version directory of a library file or folder, or null when [f] is outside the library. */
    fun dirFor(f: File): File? {
        val root = Storage.root.absolutePath
        val p = f.absolutePath
        if (!p.startsWith("$root/")) return null
        return File(baseDir(), p.removePrefix("$root/"))
    }

    /** Called by the note editor after each save (any thread). */
    fun capture(f: File) {
        if (dirFor(f) == null) return
        val key = f.absolutePath
        pending.remove(key)?.cancel(false)
        pending[key] = worker.schedule({
            pending.remove(key)
            synchronized(lock) { runCatching { write(File(key), force = false) } }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
    }

    /** Writes the snapshot right away (blocking). [force] always starts a new snapshot (used before a restore). */
    fun captureNow(f: File, force: Boolean): Version? = synchronized(lock) { runCatching { write(f, force) }.getOrNull() }

    private fun write(f: File, force: Boolean): Version? {
        if (!f.isFile) return null
        val dir = dirFor(f) ?: return null
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val newest = list(f).firstOrNull()
        val created = if (!force && newest != null && now - newest.created < BUCKET_MS) newest.created else now
        val tmp = File(dir, "tmp_${now}.gz")
        GZIPOutputStream(tmp.outputStream().buffered(64 * 1024)).use { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) } }
        var name = "v${created}_${f.length()}.gz"
        if (force && newest != null && newest.created == created) name = "v${created + 1}_${f.length()}.gz"
        val target = File(dir, name)
        if (newest != null && newest.created == created && newest.file != target) newest.file.delete()
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); return null } }
        prune(dir)
        return parse(target)
    }

    private fun parse(g: File): Version? {
        val m = Regex("v(\\d+)_(\\d+)\\.gz").matchEntire(g.name) ?: return null
        return Version(g, m.groupValues[1].toLong(), g.lastModified(), m.groupValues[2].toLong())
    }

    /** Snapshots of [note], newest first. */
    fun list(note: File): List<Version> {
        val dir = dirFor(note) ?: return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.mapNotNull(::parse)?.sortedByDescending { it.created } ?: emptyList()
    }

    private fun prune(dir: File) {
        val all = dir.listFiles()?.filter { it.isFile }?.mapNotNull(::parse)?.sortedByDescending { it.created } ?: return
        val now = System.currentTimeMillis()
        all.forEachIndexed { i, v -> if (i > 0 && (i >= MAX_COUNT || now - v.time > MAX_AGE_MS)) v.file.delete() }
        dir.listFiles()?.filter { it.name.startsWith("tmp_") && now - it.lastModified() > 3600_000L }?.forEach { it.delete() }
    }

    /** Parses a snapshot (on a background thread). */
    @OptIn(ExperimentalSerializationApi::class)
    fun load(v: Version): InkDoc? = runCatching {
        GZIPInputStream(v.file.inputStream().buffered(64 * 1024)).use { json.decodeFromStream<InkDoc>(it) }
    }.getOrNull()

    private fun unpackTo(v: Version, target: File): Boolean = runCatching {
        val tmp = File(target.parentFile, ".${target.name}.restore")
        GZIPInputStream(v.file.inputStream().buffered(64 * 1024)).use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); return false } }
        true
    }.getOrDefault(false)

    /** Replaces [note] with [v]; the current content becomes a version first. Blocking — call off the main thread. */
    fun restore(note: File, v: Version): Boolean {
        synchronized(lock) {
            pending.remove(note.absolutePath)?.cancel(false)
            runCatching { write(note, force = true) }
            val ok = unpackTo(v, note)
            if (ok) Handler(Looper.getMainLooper()).post { Storage.touch() }
            return ok
        }
    }

    /** Writes [v] as a new note next to [note] named [name]; returns it. Blocking. */
    fun saveAsCopy(note: File, v: Version, name: String): File? {
        val target = Storage.uniqueFile(note.parentFile!!, name, Storage.NOTE_EXT)
        if (!unpackTo(v, target)) return null
        Handler(Looper.getMainLooper()).post { Storage.touch() }
        return target
    }

    /** Library item [from] became [to] (rename / move): move its version directory (or a folder's whole subtree). */
    fun moved(from: File, to: File) {
        val a = dirFor(from) ?: return
        if (!a.exists()) return
        val b = dirFor(to) ?: run { a.deleteRecursively(); return }
        synchronized(lock) {
            b.parentFile?.mkdirs()
            if (b.exists()) b.deleteRecursively()
            if (!a.renameTo(b)) { a.copyRecursively(b, overwrite = true); a.deleteRecursively() }
        }
    }

    /** Detaches the version directory of [f] into [dest] (trash). Returns true when there was one. */
    fun detach(f: File, dest: File): Boolean {
        val a = dirFor(f) ?: return false
        if (!a.exists()) return false
        synchronized(lock) {
            dest.parentFile?.mkdirs()
            if (!a.renameTo(dest)) { a.copyRecursively(dest, overwrite = true); a.deleteRecursively() }
        }
        return true
    }

    /** Puts a detached version directory back for library item [f] (restore from trash). */
    fun attach(src: File, f: File) {
        if (!src.exists()) return
        val b = dirFor(f) ?: return
        synchronized(lock) {
            b.parentFile?.mkdirs()
            if (b.exists()) b.deleteRecursively()
            if (!src.renameTo(b)) { src.copyRecursively(b, overwrite = true); src.deleteRecursively() }
        }
    }
}
