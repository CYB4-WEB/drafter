package com.daftar.app.ai

import com.daftar.app.data.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A 0-based inclusive page range the user allowed the AI to read. */
@Serializable
data class SharedRange(val from: Int, val to: Int)

/**
 * One chat message as saved on disk. [role]: "user", "model" or "note" (a small status line, e.g. "Pages 3–5 shared").
 * [image] is the file name of the selection JPEG next to the chat JSON; [page] the 0-based page it came from.
 * [shared] are the page ranges the user allowed while this (user) message was answered — re-sent as text on follow-ups.
 */
@Serializable
data class ChatMsg(
    val id: Long,
    val role: String,
    val text: String,
    val image: String? = null,
    val page: Int? = null,
    val recognized: String? = null,
    val shared: List<SharedRange> = emptyList(),
    val time: Long = 0L,
)

@Serializable
data class ChatData(
    val path: String = "",
    val messages: List<ChatMsg> = emptyList(),
    /** "Always allow for this file": page / whole-file requests are granted without asking. */
    val alwaysAllow: Boolean = false,
)

/**
 * Chats are kept per file in the app-private dir: `filesDir/ai_chats/<sha1(path)>.json` and the selection images as
 * `<sha1>_<id>.jpg` beside it. Never in the library (so they are not synced / shared with the document). Call on IO.
 */
object AiChatStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val dir: File get() = File(Storage.appCtx.filesDir, "ai_chats").apply { mkdirs() }

    fun key(file: File): String =
        MessageDigest.getInstance("SHA-1").digest(file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }

    fun load(file: File): ChatData {
        val f = File(dir, key(file) + ".json")
        if (!f.isFile) return ChatData(file.absolutePath)
        return runCatching { json.decodeFromString<ChatData>(f.readText()) }.getOrElse { ChatData(file.absolutePath) }
    }

    fun save(file: File, data: ChatData) {
        val f = File(dir, key(file) + ".json")
        val tmp = File(dir, f.name + ".tmp")
        runCatching {
            tmp.writeText(json.encodeToString(ChatData.serializer(), data.copy(path = file.absolutePath)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Writes a selection JPEG for message [id]; returns its file name. */
    fun saveImage(file: File, id: Long, jpeg: ByteArray): String? = runCatching {
        val name = "${key(file)}_$id.jpg"
        File(dir, name).writeBytes(jpeg)
        name
    }.getOrNull()

    fun imageFile(name: String): File = File(dir, name)

    fun readImage(name: String): ByteArray? = runCatching { File(dir, name).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** Deletes the chat messages and their images (the "always allow" choice is kept when [keepPermission]). */
    fun clear(file: File, keepPermission: Boolean): ChatData {
        val k = key(file)
        dir.listFiles()?.filter { it.name.startsWith(k + "_") && it.name.endsWith(".jpg") }?.forEach { it.delete() }
        val old = load(file)
        val fresh = ChatData(file.absolutePath, alwaysAllow = keepPermission && old.alwaysAllow)
        save(file, fresh)
        return fresh
    }
}
