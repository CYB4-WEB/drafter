package com.daftar.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Optional Google Play services modules (thin ML Kit variants): availability check and install with progress. */
internal object PlayModules {
    suspend fun available(ctx: Context, api: OptionalModuleApi): Boolean = runCatching {
        ModuleInstall.getClient(ctx).areModulesAvailable(api).awaitOrNull()?.areModulesAvailable() == true
    }.getOrDefault(false)

    /** Requests the module and waits (≤ 10 min) until Play services reports it installed. Never throws. */
    suspend fun install(ctx: Context, api: OptionalModuleApi, onProgress: (Float) -> Unit): Boolean {
        if (available(ctx, api)) { onProgress(1f); return true }
        if (!MlStore.networkAllowed(ctx)) return false
        val client = runCatching { ModuleInstall.getClient(ctx) }.getOrNull() ?: return false
        val done = CompletableDeferred<Boolean>()
        val listener = InstallStatusListener { u ->
            u.progressInfo?.let { p -> if (p.totalBytesToDownload > 0) onProgress(p.bytesDownloaded.toFloat() / p.totalBytesToDownload) }
            when (u.installState) {
                InstallState.STATE_COMPLETED -> done.complete(true)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> done.complete(false)
            }
        }
        return try {
            val req = ModuleInstallRequest.newBuilder().addApi(api).setListener(listener).build()
            val resp = client.installModules(req).awaitOrNull() ?: return false
            if (resp.areModulesAlreadyInstalled()) true
            else withTimeoutOrNull(10 * 60_000L) { done.await() } ?: false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { client.unregisterListener(listener) }
            if (done.isCompleted && runCatching { done.getCompleted() }.getOrDefault(false)) onProgress(1f)
        }
    }
}

/** ML Kit Text Recognition v2 (Latin script), Play-services variant. */
internal object LatinEngine {
    private var client: TextRecognizer? = null

    @Synchronized
    fun client(): TextRecognizer? = client ?: runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        .getOrNull()?.also { client = it }

    suspend fun isReady(ctx: Context): Boolean = client()?.let { PlayModules.available(ctx, it) } ?: false

    suspend fun prepare(ctx: Context, onProgress: (Float) -> Unit): Boolean =
        client()?.let { PlayModules.install(ctx, it, onProgress) } ?: false

    /** Lines of [bmp] (its own pixel coordinates), or null when the engine is not available. */
    suspend fun recognize(bmp: Bitmap): List<RawLine>? {
        val c = client() ?: return null
        val text = runCatching { c.process(InputImage.fromBitmap(bmp, 0)).awaitOrNull() }.getOrNull() ?: return null
        val out = ArrayList<RawLine>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            val s = line.text.trim()
            if (s.isEmpty()) continue
            out += RawLine(s, RectF(r), runCatching { line.confidence }.getOrDefault(0.5f) * 100f)
        }
        return out
    }
}
