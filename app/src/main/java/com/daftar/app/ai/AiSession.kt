package com.daftar.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.daftar.app.data.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.roundToInt

/** One AI chat per open file. Created with [rememberAiSession]; the saved chat is loaded the first time it is shown. */
@Composable
fun rememberAiSession(file: File): AiSession {
    val s = remember(file.absolutePath) { AiSession(file) }
    DisposableEffect(s) { onDispose { s.dispose() } }
    return s
}

/**
 * State + logic of the AI chat for one file: messages (persisted by [AiChatStore]), the pending selection attachment,
 * the request loop with the `request_pages` / `request_full_file` tools and the permission / privacy prompts.
 * All Compose-observable; network and file work run off the main thread.
 */
@Stable
class AiSession internal constructor(val file: File) {
    /** The selection waiting in the composer (JPEG bytes ≤ 1600 px; a small preview for the UI). */
    class Attachment(val jpeg: ByteArray, val page: Int, val recognized: String?, val preview: ImageBitmap?)

    enum class Decision { PAGES, WHOLE, DENY }
    /** The AI asks to read pages [from]..[to] (0-based) or the whole file; answered through [answer]. */
    class PermissionAsk(val whole: Boolean, val from: Int, val to: Int, val reason: String, internal val result: CompletableDeferred<Pair<Decision, Boolean>>) {
        fun answer(d: Decision, always: Boolean) { result.complete(d to always) }
    }
    sealed class Status {
        data object Thinking : Status()
        data class Reading(val from: Int, val to: Int) : Status()
    }
    /** A failed request; [kind] null = unexpected error (message in [detail]). */
    class Failure(val kind: Gemini.AiException.Kind?, val detail: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saveLock = Mutex()

    /** Panel visible. */
    var open by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    val messages = mutableStateListOf<ChatMsg>()
    var alwaysAllow by mutableStateOf(false)
        private set
    var attachment by mutableStateOf<Attachment?>(null)
        private set
    /** Text in the composer (kept here so it survives the panel closing / sheet ↔ side switches). */
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<Status>(Status.Thinking)
        private set
    var failure by mutableStateOf<Failure?>(null)
        private set
    var permission by mutableStateOf<PermissionAsk?>(null)
        private set
    /** The one-time privacy notice must be shown before the first request. */
    var privacyAsk by mutableStateOf(false)
        private set
    var pageCount by mutableIntStateOf(-1)
        private set

    private var history: MutableList<Gemini.Msg>? = null
    private var lastId = 0L
    /** Unique, increasing message ids (time based). */
    private fun nextId(): Long {
        val prev = maxOf(lastId, messages.maxOfOrNull { it.id } ?: 0L)
        lastId = maxOf(System.currentTimeMillis(), prev + 1)
        return lastId
    }
    private var job: Job? = null
    private var loadJob: Job? = null
    private var pendingAfterPrivacy: (() -> Unit)? = null

    // ------------------------------------------------------------------------------------------------ panel

    fun show() { open = true; ensureLoaded() }
    fun close() { open = false }

    /**
     * Opens the panel with the lasso selection attached to the composer. Takes ownership of [image] (it is encoded to a
     * JPEG and recycled). [page] is 0-based; [recognizedText] = typed / recognized text inside the selection, if any.
     */
    fun startFromSelection(image: Bitmap, page: Int, recognizedText: String?) {
        show()
        scope.launch {
            val att = withContext(Dispatchers.Default) {
                try {
                    val jpeg = Gemini.Part.image(image, FileContext.MAX_SIDE, 85).bytes
                    Attachment(jpeg, page, recognizedText?.trim()?.ifBlank { null }, preview(jpeg, 480))
                } catch (_: Throwable) { null } finally { if (!image.isRecycled) image.recycle() }
            }
            if (att != null) { attachment = att; failure = null }
        }
    }

    fun removeAttachment() { attachment = null }

    internal fun dispose() {
        permission?.answer(Decision.DENY, false)
        scope.cancel()
    }

    internal fun ensureLoaded() {
        if (loaded || loadJob != null) return
        loadJob = scope.launch {
            val (data, n) = withContext(Dispatchers.IO) { AiChatStore.load(file) to FileContext.pageCount(file) }
            messages.clear(); messages.addAll(data.messages)
            alwaysAllow = data.alwaysAllow
            pageCount = n
            loaded = true
        }
    }

    // ------------------------------------------------------------------------------------------------ actions

    /** Sends [text] (or the composer's draft when null) with the pending attachment. */
    fun send(text: String? = null) {
        val t = (text ?: draft).trim()
        val att = attachment
        if (busy || (t.isEmpty() && att == null)) return
        if (!AiPrefs.hasKey) { failure = Failure(Gemini.AiException.Kind.NO_KEY, null); return }
        if (!AiPrefs.privacyAccepted) {
            pendingAfterPrivacy = { send(text) }
            privacyAsk = true
            return
        }
        if (text == null) draft = ""
        failure = null
        attachment = null
        val id = nextId()
        busy = true
        status = Status.Thinking
        job = scope.launch {
            loadJob?.join()
            val imageName = if (att != null) withContext(Dispatchers.IO) { AiChatStore.saveImage(file, id, att.jpeg) } else null
            val msg = ChatMsg(id, "user", t, image = imageName, page = att?.page, recognized = att?.recognized, time = id)
            messages.add(msg)
            persist()
            runTurn(msg, att?.jpeg)
        }
    }

    fun acceptPrivacy() {
        AiPrefs.acceptPrivacy()
        privacyAsk = false
        pendingAfterPrivacy?.invoke()
        pendingAfterPrivacy = null
    }

    fun declinePrivacy() { privacyAsk = false; pendingAfterPrivacy = null }

    /** Re-runs the last unanswered user message after an error. */
    fun retry() {
        if (busy) return
        val last = messages.lastOrNull { it.role != "note" } ?: return
        if (last.role != "user") return
        failure = null
        busy = true
        status = Status.Thinking
        job = scope.launch {
            val jpeg = last.image?.let { withContext(Dispatchers.IO) { AiChatStore.readImage(it) } }
            runTurn(last, jpeg)
        }
    }

    fun cancel() {
        if (busy) failure = Failure(null, STOPPED)
        job?.cancel()
        permission?.answer(Decision.DENY, false)
    }

    fun dismissFailure() { failure = null }

    /** Clears the chat (messages + images); the "always allow" choice for this file is kept. */
    fun newChat() {
        job?.cancel()
        permission?.answer(Decision.DENY, false)
        messages.clear()
        history = mutableListOf()
        failure = null
        attachment = null
        draft = ""
        scope.launch { saveLock.withLock { withContext(Dispatchers.IO) { AiChatStore.clear(file, keepPermission = true) } } }
    }

    fun changeAlwaysAllow(v: Boolean) { alwaysAllow = v; scope.launch { persist() } }

    /** The selection image + page to hand to the quiz screen: the pending attachment, else the latest one in the chat. */
    suspend fun quizSource(): Triple<Bitmap?, Int?, String> {
        val att = attachment
        val (jpeg, page) = if (att != null) att.jpeg to att.page else {
            val m = messages.lastOrNull { it.image != null }
            (m?.image?.let { withContext(Dispatchers.IO) { AiChatStore.readImage(it) } }) to m?.page
        }
        val bmp = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        return Triple(bmp, page, draft.trim())
    }

    // ------------------------------------------------------------------------------------------------ request loop

    private suspend fun runTurn(msg: ChatMsg, jpeg: ByteArray?) {
        try {
            if (pageCount < 0) pageCount = withContext(Dispatchers.IO) { FileContext.pageCount(file) }
            val base = history ?: withContext(Dispatchers.IO) { buildHistory(msg.id) }.also { history = it }
            val userParts = userParts(msg, jpeg)
            val turn = ArrayList<Gemini.Msg>()
            turn.add(Gemini.Msg("user", userParts))
            val keptText = ArrayList<Gemini.Part>()   // text of allowed pages, kept for follow-ups
            val shared = ArrayList<SharedRange>()
            var rounds = 0
            var answer: String
            while (true) {
                status = Status.Thinking
                val tools = if (rounds < MAX_ROUNDS && pageCount > 0) TOOLS else emptyList()
                val reply = Gemini.generate(base + turn, system = systemPrompt(msg), tools = tools)
                turn.add(reply.asMsg())
                if (reply.calls.isEmpty() || tools.isEmpty()) { answer = reply.text.trim(); break }
                rounds++
                val results = ArrayList<Gemini.Part>()
                val content = ArrayList<Gemini.Part>()
                for (call in reply.calls) {
                    val (res, parts, range) = handleCall(call)
                    results.add(res)
                    content.addAll(parts)
                    if (range != null) shared.add(range)
                }
                keptText.addAll(content.filterIsInstance<Gemini.Part.Text>())
                turn.add(Gemini.Msg("user", results + content))
            }
            if (answer.isEmpty()) throw Gemini.AiException(Gemini.AiException.Kind.BLOCKED, "Empty answer")
            // Follow-ups keep the context: the question (with its selection) + text of the allowed pages + the answer.
            base.add(Gemini.Msg("user", userParts + keptText))
            base.add(Gemini.Msg.model(Gemini.Part.text(answer)))
            val idx = messages.indexOfFirst { it.id == msg.id }
            if (idx >= 0 && shared.isNotEmpty()) messages[idx] = messages[idx].copy(shared = messages[idx].shared + shared)
            messages.add(ChatMsg(nextId(), "model", answer, time = System.currentTimeMillis()))
            persist()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Gemini.AiException) {
            failure = Failure(e.kind, e.message)
        } catch (e: OutOfMemoryError) {
            failure = Failure(null, "memory")
        } catch (e: Exception) {
            failure = Failure(null, e.message ?: e.javaClass.simpleName)
        } finally {
            busy = false
            permission = null
        }
    }

    /** Executes one tool call: asks the user, reads what was allowed. Returns the function result, the page parts and the shared range. */
    private suspend fun handleCall(call: Gemini.Part.Call): Triple<Gemini.Part, List<Gemini.Part>, SharedRange?> {
        val n = pageCount
        val reason = call.args["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val whole = call.name == "request_full_file"
        if (!whole && call.name != "request_pages") return Triple(result(call.name) { put("error", "Unknown function") }, emptyList(), null)
        if (n <= 0) return Triple(result(call.name) { put("error", "This file has no readable pages.") }, emptyList(), null)
        var from = 0
        var to = n - 1
        if (!whole) {
            val a = call.args.intArg("from") ?: call.args.intArg("to")
            val b = call.args.intArg("to") ?: a
            if (a == null || b == null) return Triple(result(call.name) { put("error", "Give 'from' and 'to' page numbers (1-$n).") }, emptyList(), null)
            val lo = minOf(a, b).coerceIn(1, n); val hi = maxOf(a, b).coerceIn(1, n)
            from = lo - 1; to = hi - 1
        }
        val (decision, always) = if (alwaysAllow) (if (whole) Decision.WHOLE else Decision.PAGES) to true else {
            val ask = PermissionAsk(whole, from, to, reason, CompletableDeferred())
            permission = ask
            try { ask.result.await() } finally { if (permission === ask) permission = null }
        }
        if (always && !alwaysAllow && decision != Decision.DENY) { alwaysAllow = true; persist() }
        if (decision == Decision.DENY) {
            note("denied")
            return Triple(result(call.name) {
                put("granted", false)
                put("message", "The user did not allow this. Answer as well as you can with what you have and say briefly what is missing.")
            }, emptyList(), null)
        }
        if (decision == Decision.WHOLE) { from = 0; to = n - 1 }
        status = Status.Reading(from, to)
        val parts = FileContext.parts(file, from, to)
        note("shared:$from:$to")
        val sentTo = lastPageIn(parts) ?: to
        return Triple(result(call.name) {
            put("granted", true)
            put("pages", "${from + 1}-${to + 1}")
            if (sentTo < to) put("note", "Only pages ${from + 1}-${sentTo + 1} fit in this request; ask again for later pages if needed.")
            put("content", "The page text and images follow in this message, each page labelled [Page n].")
        }, parts, SharedRange(from, sentTo.coerceAtLeast(from)))
    }

    private fun lastPageIn(parts: List<Gemini.Part>): Int? = parts.asReversed().firstNotNullOfOrNull { p ->
        (p as? Gemini.Part.Text)?.text?.let { Regex("^\\[Page (\\d+)]").find(it)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) }
    }

    private fun result(name: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = Gemini.Part.Result(name, buildJsonObject(b))

    private fun JsonObject.intArg(k: String): Int? = this[k]?.jsonPrimitive?.let { p -> p.contentOrNull?.trim()?.toIntOrNull() ?: p.doubleOrNull?.roundToInt() }

    private suspend fun note(code: String) {
        messages.add(ChatMsg(nextId(), "note", code, time = System.currentTimeMillis()))
        persist()
    }

    private fun userParts(msg: ChatMsg, jpeg: ByteArray?): List<Gemini.Part> {
        val out = ArrayList<Gemini.Part>()
        val sb = StringBuilder()
        if (jpeg != null) sb.append("[The attached image is what the user selected on page ${(msg.page ?: 0) + 1}.]\n")
        if (!msg.recognized.isNullOrBlank()) sb.append("[Text recognized in the selection (may contain recognition errors): ").append(msg.recognized).append("]\n")
        sb.append(msg.text.ifBlank { "(no text — look at the image)" })
        out.add(Gemini.Part.text(sb.toString()))
        if (jpeg != null) out.add(Gemini.Part.Blob("image/jpeg", jpeg))
        return out
    }

    /** Rebuilds the Gemini history from the saved chat (selection images from disk, allowed pages as text only). */
    private suspend fun buildHistory(excludeId: Long): MutableList<Gemini.Msg> {
        val out = ArrayList<Gemini.Msg>()
        val list = messages.toList().filter { it.role != "note" && it.id != excludeId }
        var budget = FileContext.MAX_CHARS
        for ((k, m) in list.withIndex()) {
            if (m.role == "user") {
                if (list.getOrNull(k + 1)?.role != "model") continue   // unanswered question (e.g. an error): skip
                val parts = ArrayList(userParts(m, m.image?.let { AiChatStore.readImage(it) }))
                for (r in m.shared) {
                    if (budget <= 0) break
                    runCatching { FileContext.pages(file, r.from, r.to, images = false) }.getOrNull()?.forEach { p ->
                        if (budget > 0 && p.text.isNotBlank()) {
                            val t = "[Page ${p.index + 1}]\n" + p.text.take(budget)
                            budget -= t.length
                            parts.add(Gemini.Part.text(t))
                        }
                    }
                }
                out.add(Gemini.Msg("user", parts))
            } else if (m.role == "model" && out.isNotEmpty() && out.last().role == "user") {
                out.add(Gemini.Msg.model(Gemini.Part.text(m.text)))
            }
        }
        return out
    }

    private fun systemPrompt(msg: ChatMsg): String {
        val kind = FileContext.kind(file)
        val kindName = when (kind) {
            Kind.PDF -> "PDF document"
            Kind.NOTE -> "handwritten Daftar note / whiteboard"
            Kind.PPTX -> "PowerPoint slide deck"
            Kind.DOCX -> "Word document"
            Kind.TEXT -> "text document"
            Kind.IMAGE -> "image"
            Kind.ONENOTE -> "OneNote notebook"
            else -> "file"
        }
        val pageWord = if (kind == Kind.PPTX) "slides" else "pages"
        val textOnly = kind == Kind.DOCX || kind == Kind.TEXT || kind == Kind.ONENOTE
        return buildString {
            append("You are the study assistant inside Daftar, a note-taking app for students. ")
            append("Help the student understand, answer and solve things clearly and correctly, like a patient tutor.\n\n")
            append("The student has open the file \"${file.name}\" (${kindName}, ${pageCount.coerceAtLeast(0)} $pageWord")
            if (textOnly) append("; for this format a \"page\" is a ~3000-character part of the text")
            append(").\n")
            if (msg.page != null && msg.image != null) append("The current selection image comes from page ${msg.page + 1}.\n")
            append("\nRules:\n")
            append("- Answer in the language of the student's request (Arabic or English). If the request is only a button prompt, use that prompt's language.\n")
            append("- Use Markdown: short paragraphs, **bold** for key terms, headings and lists when helpful. Write math as plain text; inline formulas may use \$…\$ without LaTeX commands where possible.\n")
            append("- Be concise; for \"solve step by step\" number the steps and give the final answer clearly.\n")
            if (pageCount > 0) {
                append("- If you truly need more of the file (e.g. the question refers to earlier material), call request_pages(from, to, reason) ")
                append("with 1-based page numbers (1-$pageCount), or request_full_file(reason) only when the whole file is needed. ")
                append("Ask for as few pages as possible. The reason is shown to the student: one short sentence in the student's language.\n")
                append("- Never invent the content of pages you have not seen.\n")
            }
        }
    }

    private suspend fun persist() {
        val data = ChatData(file.absolutePath, messages.toList(), alwaysAllow)
        saveLock.withLock { withContext(Dispatchers.IO) { AiChatStore.save(file, data) } }
    }

    companion object {
        const val MAX_ROUNDS = 4
        /** [Failure.detail] when the user stopped the request. */
        const val STOPPED = "stopped"

        val TOOLS = listOf(
            Gemini.Fn(
                "request_pages",
                "Ask the student for permission to read pages of the open file (text and page images). Pages are 1-based.",
                mapOf(
                    "from" to ("INTEGER" to "First page, 1-based"),
                    "to" to ("INTEGER" to "Last page, 1-based, inclusive"),
                    "reason" to ("STRING" to "Short reason shown to the student, in the student's language"),
                ),
                listOf("from", "to", "reason"),
            ),
            Gemini.Fn(
                "request_full_file",
                "Ask the student for permission to read the whole open file. Use only when several distant parts are needed.",
                mapOf("reason" to ("STRING" to "Short reason shown to the student, in the student's language")),
                listOf("reason"),
            ),
        )

        /** A small decoded preview of a JPEG (long side ≈ [side] px). */
        internal fun preview(jpeg: ByteArray, side: Int): ImageBitmap? = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            var s = 1
            while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= side) s *= 2
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }.getOrNull()
    }
}
