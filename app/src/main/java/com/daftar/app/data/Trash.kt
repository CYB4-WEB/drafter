package com.daftar.app.data

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** One recycle-bin entry. [orig] = absolute original path, [deleted] = epoch ms, [kind] = [Kind] name. */
@Serializable
data class TrashItem(
    val id: String,
    val name: String,
    val orig: String,
    val deleted: Long,
    val kind: String,
    /** Pinned paths (the item itself or things inside a folder) to pin again on restore. */
    val pins: List<String> = emptyList(),
) {
    val kindEnum: Kind get() = runCatching { Kind.valueOf(kind) }.getOrDefault(Kind.OTHER)
}

@Serializable
private data class TrashIndex(val items: List<TrashItem> = emptyList())

/**
 * Recycle bin (files-agent). Deleted items live 30 days in `<app files>/.trash/<id>/` — outside the library root
 * (so search, stats and pickers never see them) but on the same volume, so deleting and restoring are renames.
 * Layout of an entry: `data/<name>` (+ its hidden sidecars `data/.<name>.*`) and `versions/` (note version history).
 * The index (`index.json`) records the original path, deletion time and kind.
 */
object Trash {
    const val KEEP_DAYS = 30
    private const val DAY = 24 * 3600_000L
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every change (Compose screens re-read [items]). */
    var version by mutableIntStateOf(0)
        private set

    fun dir(): File = File(Storage.root.parentFile, ".trash").apply { mkdirs() }
    private fun indexFile() = File(dir(), "index.json")
    private fun bump() { if (Looper.myLooper() == Looper.getMainLooper()) version++ else main.post { version++ } }

    private fun read(): List<TrashItem> =
        runCatching { json.decodeFromString<TrashIndex>(indexFile().readText()).items }.getOrDefault(emptyList())

    private fun write(items: List<TrashItem>) {
        val tmp = File(dir(), "index.json.tmp")
        tmp.writeText(json.encodeToString(TrashIndex(items)))
        if (!tmp.renameTo(indexFile())) { indexFile().delete(); tmp.renameTo(indexFile()) }
    }

    /** All entries, newest first (entries whose data vanished are dropped). */
    fun items(): List<TrashItem> = synchronized(lock) {
        val all = read()
        val ok = all.filter { File(dataDir(it), it.name).exists() }
        if (ok.size != all.size) write(ok)
        ok.sortedByDescending { it.deleted }
    }

    fun dataDir(it: TrashItem) = File(File(dir(), it.id), "data")
    fun fileOf(it: TrashItem) = File(dataDir(it), it.name)
    fun daysAgo(it: TrashItem, now: Long = System.currentTimeMillis()) = ((now - it.deleted) / DAY).toInt().coerceAtLeast(0)
    fun daysLeft(it: TrashItem, now: Long = System.currentTimeMillis()) = (KEEP_DAYS - daysAgo(it, now)).coerceAtLeast(0)

    private fun moveFile(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) return true
        if (!src.copyRecursively(dst, overwrite = true)) return false
        src.deleteRecursively(); return true
    }

    /**
     * Moves library item [f] (with sidecars and version history) into the bin. [pins] are the pinned paths it takes
     * along. Returns the entry, or null when the move failed (then nothing changed).
     */
    fun put(f: File, kind: Kind, pins: List<String>): TrashItem? = synchronized(lock) {
        val id = System.currentTimeMillis().toString(36) + "-" + (Math.random() * 1e6).toInt().toString(36)
        val entry = File(dir(), id)
        val data = File(entry, "data")
        data.mkdirs()
        val cars = Storage.sidecars(f)
        if (!moveFile(f, File(data, f.name))) { entry.deleteRecursively(); return null }
        for (c in cars) moveFile(c, File(data, c.name))
        Versions.detach(f, File(entry, "versions"))
        val item = TrashItem(id, f.name, f.absolutePath, System.currentTimeMillis(), kind.name, pins)
        write(read() + item)
        bump()
        item
    }

    /**
     * Puts [item] back in its original folder (recreated when missing; a unique name on a clash). Returns the
     * restored file, or null on failure. Re-pins what was pinned.
     */
    fun restore(item: TrashItem): File? = synchronized(lock) {
        val src = fileOf(item)
        if (!src.exists()) { forget(item); return null }
        val orig = File(item.orig)
        var parent = orig.parentFile ?: Storage.root
        if (!parent.absolutePath.startsWith(Storage.root.absolutePath)) parent = Storage.root
        parent.mkdirs()
        val target = if (!File(parent, item.name).exists()) File(parent, item.name)
        else if (src.isDirectory) Storage.uniqueFile(parent, item.name, "")
        else Storage.uniqueFile(parent, src.nameWithoutExtension, src.extension)
        if (!moveFile(src, target)) return null
        dataDir(item).listFiles()?.filter { it.name.startsWith(".${item.name}.") }?.forEach { c ->
            moveFile(c, File(parent, ".${target.name}." + c.name.removePrefix(".${item.name}.")))
        }
        Versions.attach(File(File(dir(), item.id), "versions"), target)
        File(dir(), item.id).deleteRecursively()
        write(read().filter { it.id != item.id })
        val a = item.orig
        val repin = item.pins.map { p -> if (p == a || p.startsWith("$a/")) target.absolutePath + p.removePrefix(a) else p }
        main.post { Storage.repin(repin); Storage.touch() }
        bump()
        target
    }

    private fun forget(item: TrashItem) {
        File(dir(), item.id).deleteRecursively()
        write(read().filter { it.id != item.id })
        bump()
    }

    /** Deletes [item] for good. Blocking (a big folder takes a while) — call off the main thread. */
    fun deleteForever(item: TrashItem) = synchronized(lock) { forget(item) }

    /** Empties the bin. Blocking. */
    fun empty() = synchronized(lock) {
        dir().listFiles()?.forEach { if (it.name != "index.json") it.deleteRecursively() }
        write(emptyList())
        bump()
    }

    /** Removes entries older than [KEEP_DAYS] and stray entry folders. Blocking (run on a background thread). */
    fun purge() = synchronized(lock) {
        val now = System.currentTimeMillis()
        val all = read()
        val (old, keep) = all.partition { now - it.deleted > KEEP_DAYS * DAY }
        old.forEach { File(dir(), it.id).deleteRecursively() }
        val ids = keep.map { it.id }.toSet()
        // stray folders: a crash between moving data and writing the index — keep a day, then remove
        dir().listFiles()?.forEach {
            if (it.isDirectory && it.name !in ids && now - it.lastModified() > DAY) it.deleteRecursively()
        }
        if (old.isNotEmpty()) { write(keep); bump() }
    }

    /** Total bytes in the bin (for the Settings row). Blocking. */
    fun bytes(): Long = dir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
