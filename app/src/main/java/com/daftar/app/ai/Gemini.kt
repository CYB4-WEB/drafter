package com.daftar.app.ai

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.daftar.app.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Minimal Gemini REST client (generateContent) — no SDK, so the APK stays small.
 *
 * Usage:
 *   val reply = Gemini.generate(listOf(Gemini.Msg.user(Gemini.Part.text("Explain"), Gemini.Part.image(bmp))),
 *                               system = "You are a tutor…", tools = listOf(fnDecl))
 *   reply.text / reply.calls
 *
 * Errors are thrown as [AiException] with a user-presentable [AiException.kind].
 */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    // ---------------- model ----------------
    sealed class Part {
        data class Text(val text: String) : Part()
        /** Inline image/PDF bytes (base64 in the request). */
        data class Blob(val mime: String, val bytes: ByteArray) : Part()
        data class Call(val name: String, val args: JsonObject) : Part()
        data class Result(val name: String, val response: JsonObject) : Part()

        companion object {
            fun text(t: String) = Text(t)
            /** JPEG-encodes [b] after scaling its longest side to [maxSide] px (keeps requests small and fast). */
            fun image(b: Bitmap, maxSide: Int = 1600, quality: Int = 85): Blob {
                val s = maxOf(b.width, b.height)
                val src = if (s > maxSide) Bitmap.createScaledBitmap(b, b.width * maxSide / s, b.height * maxSide / s, true) else b
                val out = ByteArrayOutputStream()
                // JPEG has no alpha: draw on white so transparent ink/paper does not turn black
                val flat = if (src.hasAlpha()) Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
                    val c = android.graphics.Canvas(it); c.drawColor(android.graphics.Color.WHITE); c.drawBitmap(src, 0f, 0f, null)
                } else src
                flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (flat !== src) flat.recycle()
                if (src !== b) src.recycle()
                return Blob("image/jpeg", out.toByteArray())
            }
        }
    }

    data class Msg(val role: String, val parts: List<Part>) {
        companion object {
            fun user(vararg p: Part) = Msg("user", p.toList())
            fun model(vararg p: Part) = Msg("model", p.toList())
        }
    }

    /** A function the model may call; [params] maps name → (type "STRING"/"INTEGER"/"BOOLEAN", description). */
    data class Fn(val name: String, val description: String, val params: Map<String, Pair<String, String>>, val required: List<String> = emptyList())

    data class Reply(val text: String, val calls: List<Part.Call>, val parts: List<Part>) {
        /** The model turn to append to the history before sending function results back. */
        fun asMsg() = Msg("model", parts)
    }

    class AiException(val kind: Kind, message: String) : Exception(message) {
        enum class Kind { NO_KEY, BAD_KEY, QUOTA, NETWORK, BLOCKED, SERVER }
    }

    // ---------------- calls ----------------
    /**
     * One generateContent call. [jsonSchema] (Gemini OpenAPI-subset schema) forces a JSON reply in [Reply.text].
     * Runs on IO; cancellable between network steps.
     */
    suspend fun generate(
        history: List<Msg>,
        system: String? = null,
        tools: List<Fn> = emptyList(),
        jsonSchema: JsonObject? = null,
        temperature: Double? = null,
        model: String = AiPrefs.model,
    ): Reply = withContext(Dispatchers.IO) {
        AiPrefs.init()
        val key = AiPrefs.apiKey
        if (key.isBlank()) throw AiException(AiException.Kind.NO_KEY, "No API key")
        val body = buildJsonObject {
            put("contents", JsonArray(history.map { encode(it) }))
            if (system != null) putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
            if (tools.isNotEmpty()) putJsonArray("tools") { addJsonObject { put("functionDeclarations", JsonArray(tools.map { encodeFn(it) })) } }
            putJsonObject("generationConfig") {
                if (temperature != null) put("temperature", temperature)
                if (jsonSchema != null) { put("responseMimeType", "application/json"); put("responseSchema", jsonSchema) }
            }
        }
        val res = try { post("$BASE/models/$model:generateContent", key, body.toString()) }
        catch (e: AiException) {
            // the configured model was retired: fall back once to Google's rolling alias
            if (e.kind == AiException.Kind.SERVER && e.message?.startsWith("404") == true && model != "gemini-flash-latest")
                return@withContext generate(history, system, tools, jsonSchema, temperature, "gemini-flash-latest")
            throw e
        }
        coroutineContext.ensureActive()
        parse(res)
    }

    /** Model ids available to this key that can generate content (for Settings → AI → model). */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        AiPrefs.init()
        val key = AiPrefs.apiKey
        if (key.isBlank()) throw AiException(AiException.Kind.NO_KEY, "No API key")
        val j = Json.parseToJsonElement(request("$BASE/models?pageSize=200", key, null)).jsonObject
        j["models"]?.jsonArray.orEmpty().mapNotNull { m ->
            val o = m.jsonObject
            val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if ("generateContent" in methods) o["name"]?.jsonPrimitive?.content?.removePrefix("models/") else null
        }.filter { it.startsWith("gemini") }.sorted()
    }

    // ---------------- encoding ----------------
    private fun encode(m: Msg) = buildJsonObject {
        put("role", m.role)
        putJsonArray("parts") {
            m.parts.forEach { p ->
                when (p) {
                    is Part.Text -> addJsonObject { put("text", p.text) }
                    is Part.Blob -> addJsonObject { putJsonObject("inlineData") { put("mimeType", p.mime); put("data", Base64.encodeToString(p.bytes, Base64.NO_WRAP)) } }
                    is Part.Call -> addJsonObject { putJsonObject("functionCall") { put("name", p.name); put("args", p.args) } }
                    is Part.Result -> addJsonObject { putJsonObject("functionResponse") { put("name", p.name); put("response", p.response) } }
                }
            }
        }
    }

    private fun encodeFn(f: Fn) = buildJsonObject {
        put("name", f.name); put("description", f.description)
        if (f.params.isNotEmpty()) putJsonObject("parameters") {
            put("type", "OBJECT")
            putJsonObject("properties") { f.params.forEach { (k, v) -> putJsonObject(k) { put("type", v.first); put("description", v.second) } } }
            if (f.required.isNotEmpty()) put("required", JsonArray(f.required.map { JsonPrimitive(it) }))
        }
    }

    private fun parse(res: String): Reply {
        val j = Json.parseToJsonElement(res).jsonObject
        val cand = j["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        if (cand == null) {
            val reason = j["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw AiException(AiException.Kind.BLOCKED, reason ?: "No answer")
        }
        val parts = cand["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty().mapNotNull { pe ->
            val p = pe.jsonObject
            when {
                p["thought"]?.jsonPrimitive?.booleanOrNull == true -> null
                p["text"] != null -> Part.Text(p["text"]!!.jsonPrimitive.content)
                p["functionCall"] != null -> p["functionCall"]!!.jsonObject.let { Part.Call(it["name"]!!.jsonPrimitive.content, it["args"]?.jsonObject ?: JsonObject(emptyMap())) }
                else -> null
            }
        }
        if (parts.isEmpty()) {
            val fr = cand["finishReason"]?.jsonPrimitive?.contentOrNull
            throw AiException(AiException.Kind.BLOCKED, fr ?: "Empty answer")
        }
        return Reply(parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, parts.filterIsInstance<Part.Call>(), parts)
    }

    // ---------------- http ----------------
    private fun post(url: String, key: String, body: String) = request(url, key, body)

    private fun request(url: String, key: String, body: String?): String {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw AiException(AiException.Kind.NETWORK, e.message ?: "network") }
        try {
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            c.setRequestProperty("x-goog-api-key", key)
            // lets the key be restricted to this app (Google Cloud → Credentials → Android apps)
            c.setRequestProperty("X-Android-Package", Storage.appCtx.packageName)
            certSha1?.let { c.setRequestProperty("X-Android-Cert", it) }
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return text
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content }.getOrDefault(text.take(200))
            throw AiException(when (code) {
                400 -> if (msg.contains("API key", true)) AiException.Kind.BAD_KEY else AiException.Kind.SERVER
                401, 403 -> AiException.Kind.BAD_KEY
                429 -> AiException.Kind.QUOTA
                else -> AiException.Kind.SERVER
            }, "$code $msg")
        } catch (e: AiException) { throw e
        } catch (e: java.io.IOException) { throw AiException(AiException.Kind.NETWORK, e.message ?: "network")
        } finally { c.disconnect() }
    }

    /** SHA-1 of the app's signing certificate (uppercase hex, no colons) for Android-restricted API keys. */
    private val certSha1: String? by lazy {
        runCatching {
            val pm = Storage.appCtx.packageManager
            val name = Storage.appCtx.packageName
            val sig = if (Build.VERSION.SDK_INT >= 28)
                pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners[0]
            else @Suppress("DEPRECATION") pm.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures!![0]
            MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
        }.getOrNull()
    }
}
