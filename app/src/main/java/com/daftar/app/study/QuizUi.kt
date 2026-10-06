package com.daftar.app.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ai.AiPrefs
import com.daftar.app.ui.Screen
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D

internal val OkColor = Color(0xFF10B981)
internal val PartColor = Color(0xFFF59E0B)

@Composable
internal fun IconBadge(icon: ImageVector, tint: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Shown instead of AI actions when no Gemini key is set: what it is + a button to Settings → AI. */
@Composable
internal fun NoKeyCard(modifier: Modifier = Modifier) {
    val c = D.c
    Row(modifier.fillMaxWidth().card(c).padding(16.dp), verticalAlignment = Alignment.Top) {
        IconBadge(Icons.Rounded.Key, c.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.quiz_no_key_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.quiz_no_key_text), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { pane.push(Screen.Settings) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                Text(stringResource(R.string.quiz_open_ai_settings))
            }
        }
    }
}

/** A failed AI call: friendly message, "Open Settings → AI" for key problems, and an optional retry. */
@Composable
internal fun AiErrorCard(e: Throwable, onRetry: (() -> Unit)?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = D.c
    val ctx = LocalContext.current
    Row(modifier.fillMaxWidth().card(c).padding(16.dp), verticalAlignment = Alignment.Top) {
        IconBadge(Icons.Rounded.ErrorOutline, c.danger)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(quizErrorText(ctx, e), style = MaterialTheme.typography.bodyMedium, color = c.ink)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isKeyError(e)) Button(onClick = { onDismiss(); pane.push(Screen.Settings) },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text(stringResource(R.string.quiz_open_ai_settings)) }
                else if (onRetry != null) Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                    Text(stringResource(R.string.quiz_retry))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.quiz_dismiss)) }
            }
        }
    }
}

/** One-time privacy notice before the first AI request (shared flag with the AI chat: [AiPrefs.privacyAccepted]). */
@Composable
internal fun AiPrivacyDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    val c = D.c
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null, tint = c.accent) },
        title = { Text(stringResource(R.string.quiz_privacy_title)) },
        text = { Text(stringResource(R.string.quiz_privacy_text)) },
        confirmButton = { TextButton(onClick = { AiPrefs.acceptPrivacy(); onAccept() }) { Text(stringResource(R.string.quiz_privacy_ok), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
