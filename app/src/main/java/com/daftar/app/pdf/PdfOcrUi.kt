package com.daftar.app.pdf

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ml.Ocr
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private enum class OcrScope { PAGE, MISSING, ALL }

/**
 * "Recognize text" flow for scanned PDFs: scope dialog → one-time model download prompt (Ocr.prepare) →
 * background recognition with progress and cancel. Results land in the session's OCR sidecar as each page finishes.
 * Shown while [page] ≥ 0 (the page the request came from); [onDone] clears the request.
 */
@Composable
internal fun OcrFlow(file: File, session: PdfSession, page: Int, onDone: () -> Unit) {
    if (page < 0) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember(page) { mutableStateOf(0) } // 0 choose, 1 download, 2 running
    var choice by remember(page) { mutableStateOf(OcrScope.PAGE) }
    var busy by remember { mutableStateOf<BusyState?>(null) }
    val cancelled = remember { AtomicBoolean(false) }
    val n = session.pageCount

    fun run() {
        step = 2
        cancelled.set(false)
        val st = BusyState(ctx.getString(R.string.pdf3_recognizing), cancellable = true)
        busy = st
        scope.launch {
            try {
                val pages: List<Int> = when (choice) {
                    OcrScope.PAGE -> listOf(page)
                    OcrScope.ALL -> (0 until n).toList()
                    OcrScope.MISSING -> withContext(Dispatchers.IO) {
                        val out = ArrayList<Int>()
                        session.textIndex.walk(0, n - 1, isCancelled = { cancelled.get() }) { i, t -> if (!t.hasText) out.add(i) }
                        out
                    }
                }
                if (pages.isEmpty()) { toast(ctx, ctx.getString(R.string.pdf3_all_have_text)); return@launch }
                st.total = pages.size
                val done = PdfOcr.recognize(file, session.ocr, pages, isCancelled = { cancelled.get() }) { d, t ->
                    scope.launch { st.done = d; st.total = t }
                }
                val lines = pages.sumOf { session.ocr.get(it)?.lines?.size ?: 0 }
                toast(ctx, if (lines == 0) ctx.getString(R.string.pdf3_ocr_nothing) else ctx.getString(R.string.pdf3_ocr_done, done))
            } catch (e: CancellationException) {
                if (!cancelled.get()) throw e
            } catch (t: Throwable) {
                Log.e("PdfOcr", "recognize", t)
                toast(ctx, ctx.getString(R.string.error_generic))
            } finally {
                busy = null
                onDone()
            }
        }
    }

    fun start() {
        scope.launch {
            val ready = withContext(Dispatchers.IO) { runCatching { Ocr.isReady(PdfOcr.LANGS) }.getOrDefault(false) }
            if (ready) run() else step = 1
        }
    }

    when (step) {
        0 -> AlertDialog(
            onDismissRequest = onDone,
            title = { Text(stringResource(R.string.pdf3_recognize_text)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pdf3_ocr_explain), style = MaterialTheme.typography.bodyMedium, color = D.c.muted)
                    Spacer(Modifier.height(8.dp))
                    @Composable
                    fun opt(s: OcrScope, text: String) = Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(choice == s, role = Role.RadioButton) { choice = s },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == s, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(text, style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                    }
                    opt(OcrScope.PAGE, stringResource(R.string.pdf_scope_current, page + 1))
                    opt(OcrScope.MISSING, stringResource(R.string.pdf3_scope_missing))
                    opt(OcrScope.ALL, stringResource(R.string.pdf_scope_all))
                }
            },
            confirmButton = { TextButton(onClick = ::start) { Text(stringResource(R.string.pdf3_recognize)) } },
            dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
        )
        1 -> ModelDownloadDialog(
            title = stringResource(R.string.pdf3_ocr_model_title),
            text = stringResource(R.string.pdf3_ocr_model_text),
            prepare = { cb -> Ocr.prepare(PdfOcr.LANGS, cb) },
            onDismiss = onDone,
        ) { run() }
    }
    busy?.let { BusyDialog(it, onCancel = { cancelled.set(true) }) }
}
