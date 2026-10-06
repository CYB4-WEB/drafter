package com.daftar.app.ml

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.math.exp
import com.google.mlkit.nl.translate.Translator as MlTranslator

/** ML Kit on-device translation + language identification behind [Translator]. */
internal object TranslateEngine {
    /** Languages offered in the app (any ML Kit language works through the API). */
    val offered = listOf("ar", "en", "fr", "es", "de", "tr", "ur")
    /** Approximate on-disk size of one ML Kit translation model (ML Kit does not report it). */
    const val MODEL_BYTES_APPROX = 30L * 1024 * 1024

    private val clients = LinkedHashMap<String, MlTranslator>()
    private val clientLock = Mutex()
    private var langId: LanguageIdentifier? = null

    fun code(lang: String): String? = runCatching { TranslateLanguage.fromLanguageTag(lang.lowercase().substringBefore('-')) }.getOrNull()

    private fun model(code: String) = TranslateRemoteModel.Builder(code).build()

    suspend fun isDownloaded(code: String): Boolean = runCatching {
        RemoteModelManager.getInstance().isModelDownloaded(model(code)).awaitOrNull() == true
    }.getOrDefault(false)

    suspend fun downloaded(): List<String> = runCatching {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).awaitOrNull()
            ?.map { it.language }?.sorted()
    }.getOrNull().orEmpty()

    suspend fun delete(code: String): Boolean {
        clientLock.withLock {
            clients.entries.removeAll { (k, v) -> (k.startsWith("$code>") || k.endsWith(">$code")).also { if (it) runCatching { v.close() } } }
        }
        val ok = runCatching { RemoteModelManager.getInstance().deleteDownloadedModel(model(code)).awaitOk() }.getOrDefault(false)
        MlStore.touch()
        return ok
    }

    suspend fun isReady(from: String, to: String): Boolean {
        val f = code(from) ?: return false
        val t = code(to) ?: return false
        if (f == t) return true
        return isDownloaded(f) && isDownloaded(t)
    }

    /**
     * Downloads the models for [from] and [to]. ML Kit has no byte progress, so [onProgress] is an estimate that
     * rises towards 95 % while a model downloads and reaches 1 when done.
     */
    suspend fun prepare(from: String, to: String, onProgress: (Float) -> Unit): Boolean {
        val f = code(from) ?: return false
        val t = code(to) ?: return false
        val ctx = mlContext() ?: return false
        val need = listOf(f, t).distinct().filter { !isDownloaded(it) }
        if (need.isEmpty()) { onProgress(1f); return true }
        if (!MlStore.networkAllowed(ctx)) return false
        val cond = DownloadConditions.Builder().apply { if (MlStore.wifiOnly) requireWifi() }.build()
        for ((i, c) in need.withIndex()) {
            val base = i.toFloat() / need.size
            val span = 1f / need.size
            val ok = coroutineScope {
                val ticker = launch {
                    val t0 = System.currentTimeMillis()
                    while (true) {
                        val s = (System.currentTimeMillis() - t0) / 1000f
                        onProgress(base + span * 0.95f * (1f - exp(-s / 25f)))
                        delay(400)
                    }
                }
                val r = runCatching { RemoteModelManager.getInstance().download(model(c), cond).awaitOk() }.getOrDefault(false)
                ticker.cancel()
                r
            }
            if (!ok) return false
            MlStore.touch()
        }
        // Language ID ships as an optional Play-services module; fetch it with the first translation model.
        runCatching { langIdClient()?.let { PlayModules.install(ctx, it) {} } }
        onProgress(1f)
        return true
    }

    private suspend fun client(f: String, t: String): MlTranslator? = clientLock.withLock {
        val key = "$f>$t"
        clients[key] ?: runCatching {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(f).setTargetLanguage(t).build())
        }.getOrNull()?.also {
            clients[key] = it
            // keep at most 3 open translators (each holds native buffers)
            while (clients.size > 3) { val first = clients.keys.first(); runCatching { clients.remove(first)?.close() } }
        }
    }

    suspend fun translate(texts: List<String>, from: String, to: String): List<String> {
        if (texts.isEmpty()) return texts
        val src = if (from.isBlank() || from == "und" || from == "auto") detect(texts.joinToString(" ").take(2000)) else from
        val f = code(src) ?: return texts
        val t = code(to) ?: return texts
        if (f == t || !isReady(f, t)) return texts
        val c = client(f, t) ?: return texts
        val out = ArrayList<String>(texts.size)
        for (text in texts) {
            coroutineContext.ensureActive()
            out += translateOne(c, text) ?: text
        }
        return out
    }

    /** Keeps line breaks; long paragraphs are cut at sentence ends so each request stays small. */
    private suspend fun translateOne(c: MlTranslator, text: String): String? {
        if (text.isBlank()) return text
        val sb = StringBuilder()
        for ((pi, para) in text.split('\n').withIndex()) {
            if (pi > 0) sb.append('\n')
            if (para.isBlank()) { sb.append(para); continue }
            val parts = Sentences.chunks(para, 400)
            for ((i, p) in parts.withIndex()) {
                coroutineContext.ensureActive()
                val r = runCatching { c.translate(p).awaitOrNull() }.getOrNull() ?: return null
                if (i > 0) sb.append(' ')
                sb.append(r.trim())
            }
        }
        return sb.toString()
    }

    private fun langIdClient(): LanguageIdentifier? = langId ?: runCatching { LanguageIdentification.getClient() }.getOrNull()?.also { langId = it }

    suspend fun detect(text: String): String {
        val s = text.trim()
        if (s.isEmpty()) return "und"
        val ctx = mlContext()
        val id = langIdClient()
        if (ctx != null && id != null && PlayModules.available(ctx, id)) {
            val r = runCatching { id.identifyLanguage(s.take(1000)).awaitOrNull() }.getOrNull()
            if (r != null && r != "und") return r.substringBefore('-')
        }
        // Fallback without the Language ID module: decide by script.
        return when {
            Script.arabicFraction(s) >= 0.5f -> "ar"
            Script.latinFraction(s) >= 0.5f -> "en"
            else -> "und"
        }
    }
}

/** Sentence splitting for Arabic and Latin text. */
internal object Sentences {
    private val ends = setOf('.', '!', '?', '؟', '۔', '؛', ';', '…', '。')

    /** Splits [s] into pieces ≤ [max] chars at sentence ends (else at spaces, else hard). */
    fun chunks(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val sentences = ArrayList<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] in ends && (i + 1 == s.length || s[i + 1].isWhitespace())) {
                sentences += s.substring(start, i + 1); start = i + 1
            }
        }
        if (start < s.length) sentences += s.substring(start)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (raw in sentences) {
            val sent = raw.trim()
            if (sent.isEmpty()) continue
            if (cur.isNotEmpty() && cur.length + 1 + sent.length > max) { out += cur.toString(); cur.clear() }
            if (sent.length > max) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                out += hardSplit(sent, max)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(sent)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun hardSplit(s: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            var j = minOf(s.length, i + max)
            if (j < s.length) {
                val sp = s.lastIndexOf(' ', j)
                if (sp > i + max / 2) j = sp
            }
            out += s.substring(i, j).trim()
            i = j
        }
        return out.filter { it.isNotEmpty() }
    }
}
