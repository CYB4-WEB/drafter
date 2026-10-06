package com.daftar.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** TEXT = plain/markup text read in the Word viewer (txt, md, rtf, csv…). */
enum class Kind { FOLDER, NOTE, PDF, PPTX, DOCX, TEXT, IMAGE, AUDIO, ONENOTE, OTHER }

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
    /** Application context (set in init); for engines that run without a screen. */
    lateinit var appCtx: Context
        private set

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
        Tags.init(ctx)  // tags-agent hook: tags.json (tags, colour labels, smart filters)
        // recycle bin: drop entries older than 30 days, once per start, off the main thread (files-agent)
        Thread({ runCatching { Trash.purge() } }, "trash-purge").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    fun touch() { version++ }

    /** True once [init] ran (engines and background threads may start before the library is set up). */
    fun isReady() = ::root.isInitialized

    private fun saveState() {
        runCatching { stateFile.writeText(json.encodeToString(AppState(recents.toList(), pins.toList()))) }
    }

    fun kindOf(f: File): Kind = if (f.isDirectory) Kind.FOLDER else when (f.extension.lowercase()) {
        NOTE_EXT -> Kind.NOTE
        "pdf" -> Kind.PDF
        "pptx" -> Kind.PPTX
        "docx", "doc" -> Kind.DOCX
        "txt", "md", "markdown", "rtf", "csv", "tsv", "log" -> Kind.TEXT
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> Kind.IMAGE
        "m4a", "mp3", "wav", "aac", "ogg" -> Kind.AUDIO
        "one", "onepkg", "onetoc2" -> Kind.ONENOTE
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
        runCatching { Versions.moved(f, target) }
        relink(f, target)
        Tags.moved(f, target)  // tags-agent hook
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
        runCatching { Versions.moved(f, target) }
        relink(f, target)
        Tags.moved(f, target)  // tags-agent hook
        touch(); return target
    }

    fun duplicate(f: File): File {
        val ext = if (f.isDirectory) "" else f.extension
        val base = if (f.isDirectory) f.name else f.nameWithoutExtension
        val target = uniqueFile(f.parentFile!!, base, ext)
        f.copyRecursively(target)
        for (c in sidecars(f)) c.copyTo(File(c.parentFile, ".${target.name}." + c.name.removePrefix(".${f.name}.")), true)
        Tags.copied(f, target)  // tags-agent hook
        touch(); return target
    }

    /**
     * Moves [f] (with sidecars and note versions) to the recycle bin ([Trash]); it is deleted for good after 30 days.
     * Returns the bin entry (for Undo), or null when it could not be moved — then it is not deleted either.
     */
    fun delete(f: File): TrashItem? {
        val a = f.absolutePath
        fun inside(p: String) = p == a || p.startsWith("$a/")
        val item = Trash.put(f, kindOf(f), pins.filter(::inside)) ?: return null
        Tags.trashed(f, item.id)  // tags-agent hook: tags wait in the bin entry until a restore
        recents.removeAll(::inside)
        pins.removeAll(::inside)
        saveState(); touch()
        return item
    }

    /** Deletes [f] and its sidecars immediately, bypassing the recycle bin (temporary / replaced files). */
    fun deleteNow(f: File) {
        val a = f.absolutePath
        sidecars(f).forEach { it.delete() }
        f.deleteRecursively()
        Versions.dirFor(f)?.deleteRecursively()
        Tags.removed(f)  // tags-agent hook
        recents.removeAll { it == a || it.startsWith("$a/") }
        pins.removeAll { it == a || it.startsWith("$a/") }
        saveState(); touch()
    }

    /** Pins [paths] again (restore from the bin). Main thread. */
    fun repin(paths: List<String>) {
        var changed = false
        for (p in paths) if (p !in pins && File(p).exists()) { pins.add(p); changed = true }
        if (changed) saveState()
        Tags.restoredFromBin()  // tags-agent hook: Trash.restore always ends here — put the item's tags back
    }

    /** Path of the folder picture of [dir] (may not exist). */
    fun coverFile(dir: File) = File(dir, ".cover.jpg")

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
                "application/onenote", "application/msonenote", "application/x-onenote" -> "one"
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

    // ---------------------------------------------------------------------------------
    // Import helpers (files and whole folders from the Storage Access Framework)
    // ---------------------------------------------------------------------------------

    /** A document found under an imported folder; [rel] is its folder path relative to the picked folder ("" = top). */
    data class TreeDoc(val uri: Uri, val name: String, val rel: String, val isDir: Boolean, val size: Long)

    /** Unique file in [dir] for a full file name ("Lecture 1.pdf" -> "Lecture 1 (2).pdf" when taken). */
    fun uniqueNamed(dir: File, fileName: String): File {
        val clean = sanitize(fileName).ifBlank { "file" }
        val dot = clean.lastIndexOf('.')
        return if (dot > 0) uniqueFile(dir, clean.substring(0, dot), clean.substring(dot + 1)) else uniqueFile(dir, clean, "")
    }

    /**
     * Copies [uri] into [destDir] as [name] (unique). [isActive] is polled between buffers so the copy can be cancelled;
     * a partial file is removed on failure or cancel. Does not bump [version] (callers call [touch] once at the end).
     */
    fun copyIn(ctx: Context, uri: Uri, destDir: File, name: String, isActive: () -> Boolean = { true }): File? {
        destDir.mkdirs()
        val target = uniqueNamed(destDir, name)
        return try {
            val inp = ctx.contentResolver.openInputStream(uri) ?: return null
            inp.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (!isActive()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            target
        } catch (e: Exception) {
            target.delete(); null
        }
    }

    /** Display name of a document, adding an extension from its MIME type when the name has none. */
    fun nameWithExt(ctx: Context, uri: Uri, name: String = displayName(ctx, uri)): String {
        if (name.contains('.')) return name
        val ext = ctx.contentResolver.getType(uri)?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext.isNullOrEmpty()) name else "$name.$ext"
    }

    /** Name of the folder picked with OpenDocumentTree. */
    fun treeName(ctx: Context, tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        }
        return name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
    }

    /** Document URI of the picked folder itself (for deleting it after a move). */
    fun treeRootDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /**
     * Walks a picked folder recursively (directories first, parents before children). Hidden entries (".name") are skipped.
     * [isActive] lets a long scan be cancelled.
     */
    fun listTree(ctx: Context, tree: Uri, isActive: () -> Boolean = { true }): List<TreeDoc> {
        val out = ArrayList<TreeDoc>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        fun walk(docId: String, rel: String, depth: Int) {
            if (depth > 32 || !isActive()) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = ArrayList<Pair<String, String>>()
            runCatching {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            out.add(TreeDoc(uri, name, rel, true, 0)); dirs.add(id to name)
                        } else out.add(TreeDoc(uri, name, rel, false, size))
                    }
                }
            }
            for ((id, name) in dirs) walk(id, if (rel.isEmpty()) name else "$rel/$name", depth + 1)
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }

    /** True when the provider says [uri] can be deleted (needed for "move"). */
    fun canDelete(ctx: Context, uri: Uri): Boolean = runCatching {
        if (!DocumentsContract.isDocumentUri(ctx, uri)) return false
        ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
            it.moveToFirst() && (it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
        } ?: false
    }.getOrDefault(false)

    /** Deletes a SAF document (a folder is deleted with its content). */
    fun deleteDocument(ctx: Context, uri: Uri): Boolean = runCatching {
        DocumentsContract.isDocumentUri(ctx, uri) && DocumentsContract.deleteDocument(ctx.contentResolver, uri)
    }.getOrDefault(false)

    /** Creates a library folder for an imported directory (unique name, given look). Does not bump [version]. */
    fun makeImportedFolder(parent: File, name: String, meta: FolderMeta, unique: Boolean): File {
        val d = if (unique) uniqueFile(parent, name, "") else File(parent, sanitize(name).ifBlank { "Folder" })
        d.mkdirs()
        val m = File(d, ".meta.json")
        if (!m.exists()) runCatching { m.writeText(json.encodeToString(meta)) }
        return d
    }

    /** Writes [text] as a new .txt file in [dir]. */
    fun writeText(dir: File, base: String, text: String): File {
        val f = uniqueFile(dir, base, "txt")
        f.writeText(text); touch(); return f
    }

    fun cacheDir(): File = File(appCtx.cacheDir, "work").apply { mkdirs() }
}
