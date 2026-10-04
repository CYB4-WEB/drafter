package com.daftar.app.onenote

import java.io.File
import java.io.RandomAccessFile

/** Turns a path into a [OneBook]: a .one section, or a .onepkg package (CAB) extracted into a cache folder. Pure JVM. */
object OneLoader {
    /**
     * Loads [f]. [cacheRoot] receives extracted package sections (old extractions are pruned). Throws [OneException]
     * when nothing can be shown; per-section problems inside a package are reported on the section instead.
     */
    fun load(f: File, cacheRoot: File, cancelled: () -> Boolean = { false }): OneBook {
        if (!f.exists() || !f.canRead()) throw OneException(OneError.IO)
        val ext = f.extension.lowercase()
        if (ext == "onetoc2") throw OneException(OneError.TOC)
        return if (Cab.isCab(f)) loadPackage(f, cacheRoot, cancelled)
        else OneBook(f.nameWithoutExtension, listOf(OneReader.readSection(f)))
    }

    private fun loadPackage(f: File, cacheRoot: File, cancelled: () -> Boolean): OneBook {
        val key = Integer.toHexString((f.absolutePath + "|" + f.length() + "|" + f.lastModified()).hashCode())
        val dir = File(cacheRoot, "pkg_$key")
        val done = File(dir, ".complete")
        val arc = Cab.read(f)
        val sections = arc.entries.filter { e ->
            val n = e.name.lowercase()
            n.endsWith(".one") && !n.contains("onenote_recyclebin/") && !n.startsWith("onenote_recyclebin")
        }
        if (sections.isEmpty()) throw OneException(OneError.EMPTY_PACKAGE)
        val files: List<Pair<Cab.Entry, File>> = if (done.exists()) {
            sections.map { it to File(dir, Cab.safeName(it.name)) }
        } else {
            prune(cacheRoot, keep = 3)
            dir.deleteRecursively()
            dir.mkdirs()
            val wanted = sections.toSet()
            val out = Cab.extract(f, dir, { it in wanted }, cancelled)
            done.writeText("ok")
            out
        }
        val result = ArrayList<OneSection>()
        for ((e, file) in files.sortedBy { order(it.first.name) }) {
            if (cancelled()) throw InterruptedException()
            val name = Cab.safeName(e.name).removeSuffix(".one").removeSuffix(".ONE").replace("/", " › ")
            result.add(
                try { OneReader.readSection(file, name) }
                catch (ex: OneException) { OneSection(name, emptyList(), ex.kind, file) }
                catch (ex: Exception) { OneSection(name, emptyList(), OneError.CORRUPT, file) }
            )
        }
        if (result.isEmpty()) throw OneException(OneError.EMPTY_PACKAGE)
        return OneBook(f.nameWithoutExtension, result)
    }

    /** Top-level sections first, then section groups, each alphabetical (OneNote's own order lives in the .onetoc2). */
    private fun order(name: String): String {
        val depth = name.count { it == '/' }
        return "%03d|%s".format(depth, name.lowercase())
    }

    private fun prune(cacheRoot: File, keep: Int) {
        val dirs = cacheRoot.listFiles { d -> d.isDirectory && d.name.startsWith("pkg_") }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(keep).forEach { runCatching { it.deleteRecursively() } }
    }

    /** True when [f] starts like a OneNote revision store or a cabinet (used to accept files with unusual extensions). */
    fun looksLikeOneNote(f: File): Boolean = runCatching {
        RandomAccessFile(f, "r").use { r ->
            if (r.length() < 16) return false
            val b = ByteArray(16); r.readFully(b)
            val one = byteArrayOf(0xE4.toByte(), 0x52, 0x5C, 0x7B, 0x8C.toByte(), 0xD8.toByte(), 0xA7.toByte(), 0x4D)
            val cab = byteArrayOf(0x4D, 0x53, 0x43, 0x46)
            b.copyOf(8).contentEquals(one) || b.copyOf(4).contentEquals(cab)
        }
    }.getOrDefault(false)
}
