package com.daftar.app.ui.files

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.pdf.PdfTools
import com.daftar.app.ui.toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Document scanner (files-agent): Google ML Kit document scanner (Play services UI — edge detection, crop, filters,
 * gallery import), saved as a PDF named "Scan 4 Oct 15.30" in the chosen library folder.
 */
object Scanner {
    const val PAGE_LIMIT = 50

    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }

    fun scanName(ctx: Context): String =
        ctx.getString(R.string.files_scan_name) + " " + SimpleDateFormat("d MMM HH.mm", Locale.getDefault()).format(Date())

    /** Copies the scanner's PDF (or builds one from the page JPEGs) into [dir]. Blocking — IO thread. */
    fun save(ctx: Context, pdf: Uri?, pages: List<Uri>, dir: File): File? {
        dir.mkdirs()
        val out = Storage.uniqueFile(dir, scanName(ctx), "pdf")
        val ok = runCatching {
            if (pdf != null) {
                ctx.contentResolver.openInputStream(pdf)!!.use { inp -> out.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                true
            } else if (pages.isNotEmpty()) {
                PdfTools.imagesToPdf(ctx, pages, out); true
            } else false
        }.getOrDefault(false)
        if (!ok || !out.isFile || out.length() == 0L) { out.delete(); return null }
        return out
    }
}

/**
 * Returns a function that opens the scanner for a destination folder. [onSaved] gets the saved PDF (main thread);
 * [onBusy] wraps the copy. Errors (no Play services, module still downloading, failure) are shown as toasts.
 */
@Composable
fun rememberDocumentScanner(onBusy: (Boolean) -> Unit, onSaved: (File) -> Unit): (File) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // saveable: the scanner is another activity, our process may be recreated meanwhile
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val dir = target?.let(::File)
        target = null
        if (dir == null || res.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        if (result == null) { toast(ctx, ctx.getString(R.string.files_scan_save_failed)); return@rememberLauncherForActivityResult }
        val pdf = result.pdf?.uri
        val pages = result.pages?.map { it.imageUri } ?: emptyList()
        scope.launch {
            onBusy(true)
            val f = withContext(Dispatchers.IO) { Scanner.save(ctx, pdf, pages, dir) }
            onBusy(false)
            Storage.touch()
            if (f == null) toast(ctx, ctx.getString(R.string.files_scan_save_failed))
            else {
                toast(ctx, ctx.getString(R.string.files_scan_saved, if (Storage.isRoot(dir)) ctx.getString(R.string.files) else dir.name))
                onSaved(f)
            }
        }
    }
    val client = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(Scanner.PAGE_LIMIT)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build(),
        )
    }
    return remember(client) {
        { dir: File ->
            val act = Scanner.activityOf(ctx)
            val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx)
            if (act == null || (gms != ConnectionResult.SUCCESS && gms != ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED && gms != ConnectionResult.SERVICE_UPDATING)) {
                toast(ctx, ctx.getString(R.string.files_scan_no_play))
            } else {
                target = dir.absolutePath
                client.getStartScanIntent(act)
                    .addOnSuccessListener { sender ->
                        runCatching { launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                            .onFailure { target = null; toast(ctx, ctx.getString(R.string.files_scan_failed, it.localizedMessage ?: "")) }
                    }
                    .addOnFailureListener { e ->
                        target = null
                        val msg = when {
                            e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE -> ctx.getString(R.string.files_scan_downloading)
                            e is MlKitException && e.errorCode == MlKitException.UNSUPPORTED -> ctx.getString(R.string.files_scan_no_play)
                            else -> ctx.getString(R.string.files_scan_failed, e.localizedMessage ?: e.javaClass.simpleName)
                        }
                        toast(ctx, msg)
                    }
            }
        }
    }
}
