package com.daftar.app.pdf

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Which pages an operation applies to. Pages are 0-based. */
enum class PageScope { ALL, CURRENT, RANGE }

// ------------------------------------------------------------------ shared bits

@Composable
private fun OptionRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (enabled) D.c.ink else D.c.muted)
    }
}

@Composable
private fun RangeField(value: String, pageCount: Int, error: Boolean, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.pdf_range_label)) },
        placeholder = { Text(stringResource(R.string.pdf_range_hint)) },
        isError = error,
        supportingText = if (error) { { Text(stringResource(R.string.pdf_range_invalid, pageCount)) } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
    )
}

/** Scope picker (All / Current / Range) with an inline-validated range field. Returns pages via [resolve]. */
private class ScopeState(initialRange: String) {
    var scope by mutableStateOf(PageScope.ALL)
    var range by mutableStateOf(initialRange)
    var error by mutableStateOf(false)

    fun resolve(current: Int, pageCount: Int): List<Int>? = when (scope) {
        PageScope.ALL -> (0 until pageCount).toList()
        PageScope.CURRENT -> listOf(current)
        PageScope.RANGE -> PdfTools.parseRanges(range, pageCount).also { error = it == null }
    }
}

@Composable
private fun ScopePicker(st: ScopeState, current: Int, pageCount: Int) {
    OptionRow(stringResource(R.string.pdf_scope_all), st.scope == PageScope.ALL) { st.scope = PageScope.ALL }
    OptionRow(stringResource(R.string.pdf_scope_current, current + 1), st.scope == PageScope.CURRENT) { st.scope = PageScope.CURRENT }
    OptionRow(stringResource(R.string.pdf_scope_range), st.scope == PageScope.RANGE) { st.scope = PageScope.RANGE }
    if (st.scope == PageScope.RANGE) {
        RangeField(st.range, pageCount, st.error, { st.range = it; st.error = false }, Modifier.padding(top = 4.dp))
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var v by remember { mutableStateOf((current + 1).toString()) }
    var err by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    fun submit() {
        val n = PdfTools.toAsciiDigits(v.trim()).toIntOrNull()
        if (n == null || n !in 1..pageCount) err = true else onGo(n - 1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page)) },
        text = {
            OutlinedTextField(
                v, { v = it; err = false }, singleLine = true,
                label = { Text(stringResource(R.string.pdf_page_number, pageCount)) },
                isError = err,
                supportingText = if (err) { { Text(stringResource(R.string.pdf_invalid_page, pageCount)) } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().focusRequester(fr),
            )
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.pdf_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun PrintDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onPrint: (scope: PageScope, pages: List<Int>) -> Unit) {
    val st = remember { ScopeState("1-$pageCount") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_print)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ScopePicker(st, current, pageCount) } },
        confirmButton = {
            TextButton(onClick = { st.resolve(current, pageCount)?.let { onPrint(st.scope, it) } }) { Text(stringResource(R.string.pdf_print)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ExtractDialog(pageCount: Int, current: Int, hasInk: Boolean, onDismiss: () -> Unit, onExtract: (pages: List<Int>, label: String, withInk: Boolean) -> Unit) {
    var range by remember { mutableStateOf((current + 1).toString()) }
    var err by remember { mutableStateOf(false) }
    var withInk by remember { mutableStateOf(hasInk) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_extract_pages)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RangeField(range, pageCount, err, { range = it; err = false })
                Spacer(Modifier.height(4.dp))
                CheckRow(stringResource(R.string.pdf_include_annotations), withInk && hasInk, enabled = hasInk) { withInk = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val pages = PdfTools.parseRanges(range, pageCount)
                if (pages == null || pages.isEmpty()) err = true else onExtract(pages, PdfTools.rangeLabel(range), withInk && hasInk)
            }) { Text(stringResource(R.string.pdf_extract)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ImagesDialog(pageCount: Int, current: Int, hasInk: Boolean, onDismiss: () -> Unit, onConvert: (pages: List<Int>, jpeg: Boolean, withInk: Boolean) -> Unit) {
    val st = remember { ScopeState("1-$pageCount") }
    var jpeg by remember { mutableStateOf(false) }
    var withInk by remember { mutableStateOf(hasInk) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_pages_to_images)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScopePicker(st, current, pageCount)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pdf_format), style = MaterialTheme.typography.labelMedium, color = D.c.muted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.pdf_format_png), !jpeg, { jpeg = false })
                    Chip(stringResource(R.string.pdf_format_jpeg), jpeg, { jpeg = true })
                }
                Spacer(Modifier.height(4.dp))
                CheckRow(stringResource(R.string.pdf_include_annotations), withInk && hasInk, enabled = hasInk) { withInk = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { st.resolve(current, pageCount)?.let { onConvert(it, jpeg, withInk && hasInk) } }) {
                Text(stringResource(R.string.pdf_convert))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val MAX_SHOWN_TEXT = 100_000

/** Shows the text of the current page (or all pages) in a selectable, scrollable box with "Copy all". */
@Composable
fun CopyTextDialog(file: File, pageCount: Int, current: Int, onDismiss: () -> Unit, ocr: OcrStore? = null) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(all) {
        text = null; failed = false
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val (from, to) = if (all) 1 to pageCount else (current + 1) to (current + 1)
                // Scanned pages: their recognized text fills in where the PDF has no text layer.
                if (ocr != null && !ocr.isEmpty) PdfOcr.extractTextMerged(file, from, to, ocr) else PdfTools.extractText(file, from, to)
            }
        }
        r.onFailure { Log.e("PdfScreen", "extract text", it); failed = true }
        text = r.getOrNull() ?: ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_copy_text)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.pdf_this_page), !all, { all = false })
                    Chip(stringResource(R.string.pdf_scope_all), all, { all = true })
                }
                Spacer(Modifier.height(12.dp))
                val t = text
                when {
                    t == null -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.pdf_reading_text), color = D.c.muted)
                    }
                    failed -> Text(stringResource(R.string.error_generic), color = D.c.muted, modifier = Modifier.padding(vertical = 24.dp))
                    t.isBlank() -> Text(stringResource(R.string.pdf_no_text), color = D.c.muted, modifier = Modifier.padding(vertical = 24.dp))
                    else -> Column {
                        if (t.length > MAX_SHOWN_TEXT) {
                            Text(stringResource(R.string.pdf_text_truncated), style = MaterialTheme.typography.bodySmall, color = D.c.muted)
                            Spacer(Modifier.height(8.dp))
                        }
                        Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            SelectionContainer {
                                Text(if (t.length > MAX_SHOWN_TEXT) t.substring(0, MAX_SHOWN_TEXT) else t,
                                    style = MaterialTheme.typography.bodyLarge, color = D.c.ink)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val t = text
            TextButton(enabled = !t.isNullOrBlank(), onClick = {
                copyToClipboard(ctx, t ?: "")
                toast(ctx, ctx.getString(R.string.copied))
            }) { Text(stringResource(R.string.copy_all)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private fun copyToClipboard(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PDF", text))
    }.onFailure { Log.e("PdfScreen", "clipboard", it) }
}

/** Progress for long operations; appears only after 300 ms so quick jobs don't flash a dialog. */
class BusyState(val message: String, val cancellable: Boolean) {
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
}

@Composable
fun BusyDialog(state: BusyState, onCancel: () -> Unit) {
    var visible by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) { delay(300); visible = true }
    if (!visible) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(state.message) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (state.total > 0) {
                    LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pdf_progress, state.done, state.total), color = D.c.muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (state.cancellable) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

