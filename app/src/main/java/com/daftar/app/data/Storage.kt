package com.daftar.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

enum class Kind { FOLDER, NOTE, PDF, PPTX, DOCX, IMAGE, AUDIO, OTHER }

@Serializable
data class FolderMeta(val color: Int = 5, val icon: String = "folder", val desc: String = "")

@Serializable
private data class AppState(val recents: List<String> = emptyList(), val pins: List<String> = emptyList())

data class Entry(val file: File, val kind: Kind, val meta: FolderMeta?) {
    val name: String get() = if (kind == Kind.FOLDER) file.name else file.nameWithoutExtension
    val ext: String get() = file.extension.lowercase()
}

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The library lives in app-specific external storage as real folders and files.
 * Per-folder look (colour, icon) is in a hidden `.meta.json`; per-file extras
 * (ink, audio, slide notes) are hidden sidecars named `.<file name>.<suffix>`.
 */
object Storage {
    lateinit var root: File
        private set
    private lateinit var stateFile: File
    private lateinit var appCtx: Context

    /** Bumped on every change so Compose screens re-read the file system. */
    var version by mutableIntStateOf(0)
        private set
    val recents = mutableStateListOf<String>()
    val pins = mutableStateListOf<String>()

    const val NOTE_EXT = "note"

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Library").apply { mkdirs() }
        stateFile = File(ctx.filesDir, "state.json")
        runCatching {
            val s = json.decodeFromString<AppState>(stateFile.readText())
            recents.addAll(s.recents.filter { File(it).exists() })
            pins.addAll(s.pins.filter { File(it).exists() })
        }
    }

    fun touch() { version++ }

    private fun saveState() {
        runCatching { stateFile.writeText(json.encodeToString(AppState(recents.toList(), pins.toList()))) }
    }

    fun kindOf(f: File): Kind = if (f.isDirectory) Kind.FOLDER else when (f.extension.lowercase()) {
        NOTE_EXT -> Kind.NOTE
        "pdf" -> Kind.PDF
        "pptx" -> Kind.PPTX
        "docx" -> Kind.DOCX
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> Kind.IMAGE
        "m4a", "mp3", "wav", "aac", "ogg" -> Kind.AUDIO
        else -> Kind.OTHER
    }

    fun entry(f: File) = Entry(f, kindOf(f), if (f.isDirectory) meta(f) else null)

    fun meta(dir: File): FolderMeta =
        runCatching { json.decodeFromString<FolderMeta>(File(dir, ".meta.json").readText()) }.getOrDefault(FolderMeta())

    fun setMeta(dir: File, m: FolderMeta) {
        File(dir, ".meta.json").writeText(json.encodeToString(m)); touch()
    }

    fun list(dir: File): List<Entry> {
        val all = dir.listFiles()?.filter { !it.name.startsWith(".") }?.map(::entry) ?: emptyList()
        val folders = all.filter { it.kind == Kind.FOLDER }
        val files = all.filter { it.kind != Kind.FOLDER }
        val cmp: Comparator<Entry> = when (Prefs.sortMode) {
            1 -> compareByDescending { it.file.lastModified() }
            2 -> compareBy<Entry>({ it.ext }, { it.name.lowercase() })
            else -> compareBy { it.name.lowercase() }
        }
        return folders.sortedWith(cmp) + files.sortedWith(cmp)
    }

    fun countItems(dir: File) = dir.listFiles()?.count { !it.name.startsWith(".") } ?: 0

    fun isRoot(f: File) = f.absolutePath == root.absolutePath

    /** Path from the library root, for breadcrumbs. */
    fun crumbs(dir: File): List<File> {
        val out = ArrayList<File>()
        var d: File? = dir
        while (d != null && d.absolutePath.startsWith(root.absolutePath)) {
            out.add(0, d); if (isRoot(d)) break; d = d.parentFile
        }
        return out
    }

    fun uniqueFile(dir: File, base: String, ext: String): File {
        val clean = sanitize(base).ifBlank { "Untitled" }
        val dot = if (ext.isEmpty()) "" else ".$ext"
        var f = File(dir, clean + dot)
        var i = 2
        while (f.exists()) { f = File(dir, "$clean ($i)$dot"); i++ }
        return f
    }

    fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

    fun createFolder(parent: File, name: String, meta: FolderMeta, subfolders: List<Pair<String, String>> = emptyList()): File {
        val d = uniqueFile(parent, name, "")
        d.mkdirs()
        File(d, ".meta.json").writeText(json.encodeToString(meta))
        for ((sub, icon) in subfolders) {
            val s = File(d, sanitize(sub)); s.mkdirs()
            File(s, ".meta.json").writeText(json.encodeToString(meta.copy(icon = icon, desc = "")))
        }
        touch()
        return d
    }

    fun sidecars(f: File): List<File> =
        f.parentFile?.listFiles()?.filter { it.name.startsWith(".${f.name}.") } ?: emptyList()

    fun sidecar(f: File, suffix: String) = File(f.parentFile, ".${f.name}.$suffix")

    fun rename(f: File, newName: String): File? {
        val ext = if (f.isDirectory) "" else f.extension
        val target = uniqueFile(f.parentFile!!, newName, ext)
        val cars = sidecars(f)
        if (!f.renameTo(target)) return null
        for (c in cars) c.renameTo(File(c.parentFile, ".${target.name}." + c.name.removePrefix(".${f.name}.")))
        relink(f, target)
        touch(); return target
    }

    fun move(f: File, destDir: File): File? {
        if (destDir.absolutePath.startsWith(f.absolutePath)) return null
        val ext = if (f.isDirectory) "" else f.extension
        val base = if (f.isDirectory) f.name else f.nameWithoutExtension
        val target = uniqueFile(destDir, base, ext)
        val cars = sidecars(f)
        if (!f.renameTo(target)) {
            if (!f.copyRecursively(target)) return null
            f.deleteRecursively()
        }
        for (c in cars) {
            val nc = File(destDir, ".${target.name}." + c.name.removePrefix(".${f.name}."))
            if (!c.renameTo(nc)) { c.copyTo(nc, true); c.delete() }
        }
        relink(f, target)
        touch(); return target
    }

    fun duplicate(f: File): File {
        val ext = if (f.isDirectory) "" else f.extension
        val base = if (f.isDirectory) f.name else f.nameWithoutExtension
        val target = uniqueFile(f.parentFile!!, base, ext)
        f.copyRecursively(target)
        for (c in sidecars(f)) c.copyTo(File(c.parentFile, ".${target.name}." + c.name.removePrefix(".${f.name}.")), true)
        touch(); return target
    }

    fun delete(f: File) {
        sidecars(f).forEach { it.delete() }
        f.deleteRecursively()
        recents.removeAll { it.startsWith(f.absolutePath) }
        pins.removeAll { it.startsWith(f.absolutePath) }
        saveState(); touch()
    }

    private fun relink(from: File, to: File) {
        val a = from.absolutePath
        fun fix(l: MutableList<String>) {
            for (i in l.indices) if (l[i] == a || l[i].startsWith("$a/")) l[i] = to.absolutePath + l[i].removePrefix(a)
        }
        fix(recents); fix(pins); saveState()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun import(ctx: Context, uri: Uri, destDir: File): File? = runCatching {
        val name = displayName(ctx, uri)
        val ext = name.substringAfterLast('.', "").let {
            if (it.isNotEmpty()) it else when (ctx.contentResolver.getType(uri)) {
                "application/pdf" -> "pdf"
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> ""
            }
        }
        val target = uniqueFile(destDir, name.substringBeforeLast('.'), ext)
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> target.outputStream().use { inp.copyTo(it) } }
        touch(); target
    }.getOrNull()

    fun inbox(): File {
        val d = File(root, "Inbox")
        if (!d.exists()) createFolder(root, "Inbox", FolderMeta(color = 10, icon = "inbox"))
        return d
    }

    fun opened(f: File) {
        recents.remove(f.absolutePath); recents.add(0, f.absolutePath)
        while (recents.size > 24) recents.removeAt(recents.lastIndex)
        saveState()
    }

    fun togglePin(f: File) {
        if (!pins.remove(f.absolutePath)) pins.add(f.absolutePath)
        saveState()
    }

    fun search(q: String, limit: Int = 200): List<Entry> {
        if (q.isBlank()) return emptyList()
        val out = ArrayList<Entry>()
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.forEach {
            if (it != root && !it.name.startsWith(".") && it.name.contains(q, ignoreCase = true) && out.size < limit) out.add(entry(it))
        }
        return out
    }

    fun allFolders(): List<File> =
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory && !it.name.startsWith(".") }.toList()

    fun stats(): Triple<Int, Int, Long> {
        var folders = 0; var files = 0; var bytes = 0L
        root.walkTopDown().forEach {
            if (it == root || it.name.startsWith(".")) return@forEach
            if (it.isDirectory) folders++ else { files++; bytes += it.length() }
        }
        return Triple(folders, files, bytes)
    }

    fun cacheDir(): File = File(appCtx.cacheDir, "work").apply { mkdirs() }
}
