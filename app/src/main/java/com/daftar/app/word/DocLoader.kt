package com.daftar.app.word

import java.io.File

/**
 * Opens any file the Word viewer accepts and returns the shared [DocxDoc] model.
 * The reader is chosen by signature first (a renamed .doc that is really .docx / RTF still opens), then by extension.
 * Throws [LegacyDocException] for encrypted or unreadable legacy documents; other exceptions mean a damaged file.
 */
object DocLoader {

    /** Extensions routed to the Word viewer (mirrors Storage.kindOf for DOCX + TEXT). */
    val extensions = setOf("docx", "doc", "txt", "md", "markdown", "rtf", "csv", "tsv", "log")

    fun load(f: File): DocxDoc {
        val head = ByteArray(8)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        val ext = f.extension.lowercase()
        val zip = n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
        val ole = n >= 4 && (head[0].toInt() and 0xFF) == 0xD0 && (head[1].toInt() and 0xFF) == 0xCF &&
            (head[2].toInt() and 0xFF) == 0x11 && (head[3].toInt() and 0xFF) == 0xE0
        val rtf = n >= 5 && String(head, 0, 5, Charsets.ISO_8859_1) == "{\\rtf"
        return when {
            zip -> DocxParser.parse(f)
            ole -> LegacyDocReader.parse(f)
            rtf -> RtfReader.parse(f)
            ext == "md" || ext == "markdown" -> MarkdownReader.parse(f)
            ext == "csv" || ext == "tsv" -> CsvReader.parse(f)
            ext == "log" -> PlainTextReader.parse(f, log = true)
            ext == "txt" || ext == "rtf" -> PlainTextReader.parse(f, log = false)
            ext == "doc" -> {
                // Some ".doc" files are plain text or HTML saved by other tools: show them as text if they read as text.
                val probe = TextDecode.readFile(f, 4096).text
                if (probe.isNotEmpty() && probe.count { it < ' ' && it !in "\n\r\t" } * 50 < probe.length) PlainTextReader.parse(f, log = false)
                else throw LegacyDocException()
            }
            else -> throw IllegalStateException("unsupported")
        }
    }
}
