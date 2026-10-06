package com.daftar.app.ml

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.daftar.app.data.Storage
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** Awaits a Play-services [Task] without kotlinx-coroutines-play-services; null on failure / cancel. */
internal suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (cont.isActive) cont.resume(if (t.isSuccessful) t.result else null)
    }
}

/** Awaits a Play-services [Task]; true when it succeeded. */
internal suspend fun Task<*>.awaitOk(): Boolean = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t -> if (cont.isActive) cont.resume(t.isSuccessful) }
}

/** Application context for the ML engines (they run without a screen). */
internal fun mlContext(): Context? = runCatching { Storage.appCtx }.getOrNull()

/**
 * Settings and downloaded files of the ML features: the Wi-Fi-only switch and the Tesseract language models
 * (tessdata_fast, pinned to the 4.1.0 tag and verified by SHA-256) in `filesDir/ml/tessdata`.
 */
object MlStore {
    /** A Tesseract model: language code used by the app, Tesseract name, size in bytes, SHA-256. */
    class TessModel(val lang: String, val name: String, val bytes: Long, val sha256: String)

    /** Arabic-script languages go to Tesseract; "en" is the fallback when Play-services text recognition is missing. */
    val tessModels = listOf(
        TessModel("ar", "ara", 1_432_056, "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729"),
        TessModel("fa", "fas", 431_500, "db1c0a91208aff00d3cf1ed2c1d23f76419afd5f024688b4f71adc3f2ce4a505"),
        TessModel("ur", "urd", 1_398_718, "62e8250ce2a994106e313a82e26a516a39e2cf159d0ce3c5b5008387fd0d555f"),
        TessModel("en", "eng", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
    )
    private val mirrors = listOf(
        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/%s.traineddata",
        "https://cdn.jsdelivr.net/gh/tesseract-ocr/tessdata_fast@4.1.0/%s.traineddata",
    )

    /** Bumped when models are added / removed so Compose lists refresh. */
    var version by mutableIntStateOf(0)
        private set

    private var wifiState = mutableStateOf<Boolean?>(null)

    /** Download models only on Wi-Fi / unmetered networks (default on). */
    var wifiOnly: Boolean
        get() = wifiState.value ?: (prefs()?.getBoolean("wifi_only", true) ?: true).also { wifiState.value = it }
        set(v) { wifiState.value = v; prefs()?.edit()?.putBoolean("wifi_only", v)?.apply() }

    private fun prefs() = mlContext()?.getSharedPreferences("ml", Context.MODE_PRIVATE)

    fun touch() { version++ }

    fun tessDir(ctx: Context): File = File(ctx.filesDir, "ml/tessdata")
    fun tessFile(ctx: Context, m: TessModel): File = File(tessDir(ctx), "${m.name}.traineddata")
    fun tessModel(lang: String): TessModel? = tessModels.firstOrNull { it.lang == lang.lowercase().substringBefore('-') }
    fun hasTess(ctx: Context, m: TessModel): Boolean = tessFile(ctx, m).let { it.isFile && it.length() == m.bytes }

    /** True when downloads are allowed now (any network, or an unmetered one when [wifiOnly]). */
    fun networkAllowed(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private val downloadLock = Mutex()

    /**
     * Downloads [m] into [tessDir] (temp file, SHA-256 check, then rename). [onProgress] gets 0..1.
     * Returns true when the verified file is in place. Cancellable; never throws.
     */
    suspend fun downloadTess(ctx: Context, m: TessModel, onProgress: (Float) -> Unit): Boolean = downloadLock.withLock {
        if (hasTess(ctx, m)) { onProgress(1f); return@withLock true }
        if (!networkAllowed(ctx)) return@withLock false
        withContext(Dispatchers.IO) {
            val dir = tessDir(ctx).apply { mkdirs() }
            val tmp = File(dir, "${m.name}.traineddata.part")
            for (pattern in mirrors) {
                coroutineContext.ensureActive()
                val ok = runCatching { fetch(String.format(pattern, m.name), tmp, m, onProgress) }.getOrDefault(false)
                if (ok) {
                    val dst = tessFile(ctx, m)
                    if (tmp.renameTo(dst) || runCatching { tmp.copyTo(dst, true); tmp.delete(); true }.getOrDefault(false)) {
                        touch(); return@withContext true
                    }
                }
                tmp.delete()
            }
            false
        }
    }

    private suspend fun fetch(url: String, out: File, m: TessModel, onProgress: (Float) -> Unit): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            if (c.responseCode !in 200..299) return false
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n)
                        done += n
                        if (done > m.bytes * 2) return false
                        onProgress((done.toFloat() / m.bytes).coerceIn(0f, 1f))
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            return done == m.bytes && hex == m.sha256
        } finally {
            c.disconnect()
        }
    }

    fun deleteTess(ctx: Context, m: TessModel): Boolean {
        val ok = tessFile(ctx, m).delete()
        TessEngine.release()
        touch()
        return ok
    }
}
