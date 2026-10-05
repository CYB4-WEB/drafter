package com.daftar.app.ml

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.card
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.launch

/** Display name of a language code ("ar" → Arabic), in the app language. */
@Composable
fun mlLanguageName(code: String): String = when (code.lowercase().substringBefore('-')) {
    "ar" -> stringResource(R.string.ml_lang_ar)
    "en" -> stringResource(R.string.ml_lang_en)
    "fr" -> stringResource(R.string.ml_lang_fr)
    "es" -> stringResource(R.string.ml_lang_es)
    "de" -> stringResource(R.string.ml_lang_de)
    "tr" -> stringResource(R.string.ml_lang_tr)
    "ur" -> stringResource(R.string.ml_lang_ur)
    "fa" -> stringResource(R.string.ml_lang_fa)
    else -> java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
}

/**
 * Settings section "Text recognition & translation": Wi-Fi-only switch, OCR models (Play-services Latin module,
 * Tesseract Arabic-script models with sizes) and translation languages — download / delete each.
 * The lead embeds it in the Settings screen (like `StudySettingsSection()`).
 */
@Composable
fun MlSettingsSection() {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    val ver = MlStore.version
    val busy = remember { mutableStateMapOf<String, Float>() }
    var confirm by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    val failed = stringResource(R.string.ml_download_failed)
    val noNet = stringResource(R.string.ml_no_network)

    val latinReady by produceState(false, ver) { value = LatinEngine.isReady(ctx) }
    val translateModels by produceState(emptyList<String>(), ver) { value = TranslateEngine.downloaded() }
    val tessAvailable = remember { TessEngine.available() }

    fun download(key: String, job: suspend ((Float) -> Unit) -> Boolean) {
        if (key in busy) return
        if (!MlStore.networkAllowed(ctx)) { toast(ctx, noNet); return }
        busy[key] = 0f
        scope.launch {
            val ok = job { p -> busy[key] = p }
            busy.remove(key)
            MlStore.touch()
            if (!ok) toast(ctx, failed)
        }
    }

    SectionTitle(stringResource(R.string.ml_settings_title))
    Column(Modifier.fillMaxWidth().card(c)) {
        Row(Modifier.fillMaxWidth().clickable { MlStore.wifiOnly = !MlStore.wifiOnly }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Wifi, null, tint = c.muted)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ml_wifi_only), color = c.ink)
                Text(stringResource(R.string.ml_wifi_only_desc), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
            Switch(MlStore.wifiOnly, { MlStore.wifiOnly = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
        }
        HorizontalDivider(color = c.line)
        Header(Icons.Rounded.DocumentScanner, stringResource(R.string.ml_ocr_models))
        ModelRow(
            title = stringResource(R.string.ml_latin_module), installed = latinReady, size = null, progress = busy["latin"],
            onDownload = { download("latin") { p -> LatinEngine.prepare(ctx, p) } }, onDelete = null,
        )
        if (!tessAvailable) {
            Text(stringResource(R.string.ml_unsupported_abi), color = c.muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp))
        } else for (m in MlStore.tessModels) {
            val has = remember(ver) { MlStore.hasTess(ctx, m) }
            if (m.lang == "en" && !has) continue  // eng is only a fallback for devices without Play services
            val name = stringResource(R.string.ml_ocr_model_name, mlLanguageName(m.lang))
            ModelRow(
                title = name, installed = has, size = Formatter.formatShortFileSize(ctx, m.bytes), progress = busy["tess_${m.lang}"],
                onDownload = { download("tess_${m.lang}") { p -> MlStore.downloadTess(ctx, m, p) } },
                onDelete = { confirm = name to { MlStore.deleteTess(ctx, m) } },
            )
        }
        HorizontalDivider(color = c.line)
        Header(Icons.Rounded.Translate, stringResource(R.string.ml_translate_models))
        val approx = stringResource(R.string.ml_size_approx, Formatter.formatShortFileSize(ctx, TranslateEngine.MODEL_BYTES_APPROX))
        for (lang in (TranslateEngine.offered + translateModels).distinct()) {
            val code = TranslateEngine.code(lang) ?: continue
            val name = mlLanguageName(code)
            ModelRow(
                title = name, installed = code in translateModels, size = approx, progress = busy["tr_$code"],
                // prepare(x, x) downloads just that one model
                onDownload = { download("tr_$code") { p -> TranslateEngine.prepare(code, code, p) && TranslateEngine.isDownloaded(code) } },
                onDelete = { confirm = name to { TranslateEngine.delete(code) } },
            )
        }
    }
    confirm?.let { (name, action) ->
        ConfirmDialog(
            title = stringResource(R.string.ml_delete_model, name), text = stringResource(R.string.ml_delete_model_msg),
            confirm = stringResource(R.string.ml_delete), danger = true,
            onDismiss = { confirm = null },
        ) { confirm = null; scope.launch { action(); MlStore.touch() } }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = D.c.muted)
        Spacer(Modifier.width(16.dp))
        Text(title, color = D.c.ink, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ModelRow(title: String, installed: Boolean, size: String?, progress: Float?, onDownload: () -> Unit, onDelete: (() -> Unit)?) {
    val c = D.c
    Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, color = c.ink, style = MaterialTheme.typography.bodyMedium)
            val state = when {
                progress != null -> stringResource(R.string.ml_downloading, (progress * 100).toInt())
                installed -> stringResource(R.string.ml_installed)
                else -> stringResource(R.string.ml_not_installed)
            }
            Text(listOfNotNull(state, size).joinToString(" · "), color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        when {
            progress != null -> CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = c.accent,
                modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            installed && onDelete != null -> IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, stringResource(R.string.ml_delete), tint = c.muted) }
            installed -> Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.padding(12.dp))
            else -> IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, stringResource(R.string.ml_download), tint = c.accent) }
        }
    }
}
