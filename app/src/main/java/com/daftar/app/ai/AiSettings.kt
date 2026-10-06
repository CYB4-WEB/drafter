package com.daftar.app.ai

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.card
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val AI_STUDIO_URL = "https://aistudio.google.com/apikey"

/** Settings → AI: the user's own Gemini key (masked, paste / show / remove), key test + model choice, privacy and key-protection help. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsSection() {
    AiPrefs.init()
    val c = D.c
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(AiPrefs.apiKey) }
    var show by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var testError by remember { mutableStateOf<Gemini.AiException.Kind?>(null) }
    var testErrorDetail by remember { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.copied)

    fun save(k: String) {
        AiPrefs.saveKey(k)
        field = AiPrefs.apiKey
        models = null; testError = null
    }

    fun test() {
        if (field.isNotBlank() && field.trim() != AiPrefs.apiKey) save(field)
        if (!AiPrefs.hasKey) return
        testing = true; testError = null; testErrorDetail = null; models = null
        scope.launch {
            try {
                models = Gemini.listModels()
            } catch (e: CancellationException) { throw e
            } catch (e: Gemini.AiException) { testError = e.kind; testErrorDetail = e.message
            } catch (e: Exception) { testError = null; testErrorDetail = e.message ?: e.javaClass.simpleName; models = null
            } finally { testing = false }
        }
    }

    SectionTitle(stringResource(R.string.ai_set_title))
    Column(Modifier.fillMaxWidth().card(c).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---------------- key
        OutlinedTextField(
            value = field,
            onValueChange = { field = it.trim() },
            label = { Text(stringResource(R.string.ai_set_key)) },
            placeholder = { Text(stringResource(R.string.ai_set_key_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Key, null, tint = c.muted) },
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        stringResource(if (show) R.string.ai_set_hide else R.string.ai_set_show), tint = c.muted)
                }
            },
            singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val t = clip.getText()?.text?.trim().orEmpty()
                if (t.isNotEmpty()) save(t)
            }) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ai_set_paste)) }
            if (field.isNotBlank() && field != AiPrefs.apiKey)
                Button(onClick = { save(field) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text(stringResource(R.string.ai_set_save)) }
            if (AiPrefs.apiKey.isNotBlank())
                OutlinedButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp), tint = c.danger); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_set_clear), color = c.danger)
                }
        }
        Text(
            stringResource(if (AiPrefs.hasKey) R.string.ai_set_key_saved else R.string.ai_set_no_key),
            style = MaterialTheme.typography.bodySmall, color = c.muted,
        )

        // ---------------- test + model
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { test() }, enabled = !testing && (AiPrefs.hasKey || field.isNotBlank())) {
                if (testing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(if (testing) R.string.ai_set_testing else R.string.ai_set_test))
            }
        }
        models?.let { list ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ai_set_test_ok, list.size), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        if (testError != null || (testErrorDetail != null && models == null && !testing)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(aiErrorText(testError, testErrorDetail), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            }
        }
        Column {
            Text(stringResource(R.string.ai_set_model), style = MaterialTheme.typography.labelMedium, color = c.muted)
            Box {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).background(c.surfaceAlt, RoundedCornerShape(12.dp))
                        .clickable(enabled = models != null) { modelMenu = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (AiPrefs.model == AiPrefs.DEFAULT_MODEL) stringResource(R.string.ai_set_model_default, AiPrefs.model) else AiPrefs.model,
                        style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f),
                    )
                    if (models != null) Icon(Icons.Rounded.ExpandMore, null, tint = c.muted)
                }
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    val list = (listOf(AiPrefs.DEFAULT_MODEL) + models.orEmpty()).distinct()
                    list.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(if (m == AiPrefs.DEFAULT_MODEL) stringResource(R.string.ai_set_model_default, m) else m, color = if (m == AiPrefs.model) c.accent else c.ink) },
                            onClick = { AiPrefs.saveModel(m); modelMenu = false },
                        )
                    }
                }
            }
            if (models == null) Text(stringResource(R.string.ai_set_model_hint), style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(top = 4.dp))
        }

        TextButton(onClick = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AI_STUDIO_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: ActivityNotFoundException) { clip.setText(AnnotatedString(AI_STUDIO_URL)); toast(ctx, copied) }
        }) {
            Text(stringResource(R.string.ai_set_get_key)); Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
        }

        Text(stringResource(R.string.ai_set_privacy), style = MaterialTheme.typography.bodySmall, color = c.muted)
        if (AiPrefs.privacyAccepted) Text(
            stringResource(R.string.ai_set_privacy_reset), style = MaterialTheme.typography.bodySmall, color = c.accent,
            modifier = Modifier.clickable { AiPrefs.resetPrivacy() }.padding(vertical = 4.dp),
        )

        // ---------------- key protection help
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = c.muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_set_restrict_title), style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
        Text(stringResource(R.string.ai_set_restrict_text), style = MaterialTheme.typography.bodySmall, color = c.muted)
        val sha = remember { Gemini.certSha1?.chunked(2)?.joinToString(":") }
        CopyRow(stringResource(R.string.ai_set_package), ctx.packageName, Icons.Rounded.ContentCopy) { clip.setText(AnnotatedString(ctx.packageName)); toast(ctx, copied) }
        CopyRow(stringResource(R.string.ai_set_sha1), sha ?: stringResource(R.string.ai_set_sha1_unknown), if (sha != null) Icons.Rounded.ContentCopy else null) {
            if (sha != null) { clip.setText(AnnotatedString(sha)); toast(ctx, copied) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.ai_set_clear)) },
        text = { Text(stringResource(R.string.ai_set_clear_confirm)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; save(""); show = false }) { Text(stringResource(R.string.ai_set_remove), color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CopyRow(label: String, value: String, icon: ImageVector?, onCopy: () -> Unit) {
    val c = D.c
    Row(
        Modifier.fillMaxWidth().background(c.surfaceAlt, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
            SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = c.ink) }
        }
        if (icon != null) IconButton(onClick = onCopy) { Icon(icon, stringResource(R.string.copy), tint = c.muted) }
    }
}
