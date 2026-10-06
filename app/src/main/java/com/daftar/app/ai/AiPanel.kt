package com.daftar.app.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom-sheet variant of [AiPanel] for narrow widths (phone, split pane). Shown while [AiSession.open]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSheetHost(session: AiSession) {
    if (!session.open) return
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = { session.close() }, sheetState = state, containerColor = D.c.surface) {
        AiPanel(session, Modifier.fillMaxWidth().fillMaxHeight(0.88f).imePadding())
    }
}

/** The AI chat: header, messages, progress / errors, attachment, quick chips and the input. Width is set by the caller. */
@Composable
fun AiPanel(session: AiSession, modifier: Modifier) {
    val c = D.c
    val nav = LocalPaneNav.current
    val scope = rememberCoroutineScope()
    var confirmNew by remember { mutableStateOf(false) }
    LaunchedEffect(session) { session.ensureLoaded() }

    fun openSettings() { session.close(); nav.push(Screen.Settings) }
    fun makeQuestions() {
        scope.launch {
            val (bmp, page, instructions) = session.quizSource()
            try {
                com.daftar.app.study.QuizRequests.prepare(session.file, page?.let { it..it }, bmp, instructions)
            } finally { bmp?.recycle() }
            session.close()
            nav.push(Screen.NewQuiz)
        }
    }

    Column(modifier.background(c.surface)) {
        PanelHeader(session, onNew = { if (session.messages.isNotEmpty()) confirmNew = true }, onClose = { session.close() })
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (session.loaded && session.messages.isEmpty() && !session.busy) {
                Text(
                    stringResource(R.string.ai_empty), color = c.muted, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                MessageList(session)
            }
        }
        if (session.busy) BusyRow(session)
        session.failure?.let { FailureCard(session, it, ::openSettings) }
        Composer(session, onQuestions = ::makeQuestions)
    }

    session.permission?.let { PermissionDialog(session, it) }
    if (session.privacyAsk) PrivacyDialog(onAccept = { session.acceptPrivacy() }, onDismiss = { session.declinePrivacy() })
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text(stringResource(R.string.ai_new_chat)) },
        text = { Text(stringResource(R.string.ai_new_chat_confirm)) },
        confirmButton = { TextButton(onClick = { confirmNew = false; session.newChat() }) { Text(stringResource(R.string.ai_new_chat), color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PanelHeader(session: AiSession, onNew: () -> Unit, onClose: () -> Unit) {
    val c = D.c
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1)
            Text(session.file.name, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onNew, enabled = session.messages.isNotEmpty()) {
            Icon(Icons.Rounded.AddComment, stringResource(R.string.ai_new_chat), tint = if (session.messages.isNotEmpty()) c.ink else c.muted.copy(alpha = 0.5f))
        }
        if (session.alwaysAllow) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.ai_more), tint = c.ink) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Column { Text(stringResource(R.string.ai_always_revoke)); Text(stringResource(R.string.ai_always_on), style = MaterialTheme.typography.bodySmall, color = c.muted) } },
                    onClick = { menu = false; session.changeAlwaysAllow(false) },
                )
            }
        }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.ai_close), tint = c.ink) }
    }
}

@Composable
private fun MessageList(session: AiSession) {
    val state = rememberLazyListState()
    val count = session.messages.size
    LaunchedEffect(count, session.busy) { if (count > 0) state.animateScrollToItem(count - 1) }
    LazyColumn(
        state = state, modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(session.messages, key = { it.id }) { m ->
            when (m.role) {
                "user" -> UserMessage(m)
                "model" -> ModelMessage(m)
                else -> NoteLine(m)
            }
        }
    }
}

@Composable
private fun UserMessage(m: ChatMsg) {
    val c = D.c
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = 320.dp).background(c.surfaceAlt, RoundedCornerShape(16.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (m.image != null) {
                val img by produceState<ImageBitmap?>(null, m.image) {
                    value = withContext(Dispatchers.IO) { AiChatStore.readImage(m.image)?.let { AiSession.preview(it, 480) } }
                }
                img?.let {
                    Image(
                        it, stringResource(R.string.ai_selection_image), contentScale = ContentScale.Fit,
                        modifier = Modifier.heightIn(max = 180.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                            .border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(2.dp),
                    )
                }
                if (m.page != null) Text(stringResource(R.string.ai_selection_from_page, m.page + 1), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            if (m.text.isNotBlank()) SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyLarge, color = c.ink) }
        }
    }
}

@Composable
private fun ModelMessage(m: ChatMsg) {
    val c = D.c
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val copied = stringResource(R.string.copied)
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { MarkdownText(m.text, Modifier.fillMaxWidth().padding(horizontal = 4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { clip.setText(AnnotatedString(Markdown.stripMath(m.text))); toast(ctx, copied) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ContentCopy, stringResource(R.string.copy), tint = c.muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NoteLine(m: ChatMsg) {
    val c = D.c
    val parts = m.text.split(':')
    val (icon, text) = when (parts[0]) {
        "shared" -> {
            val a = (parts.getOrNull(1)?.toIntOrNull() ?: 0) + 1
            val b = (parts.getOrNull(2)?.toIntOrNull() ?: 0) + 1
            Icons.Rounded.Description to if (a == b) stringResource(R.string.ai_note_shared_one, a) else stringResource(R.string.ai_note_shared, a, b)
        }
        else -> Icons.Rounded.Block to stringResource(R.string.ai_note_denied)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun BusyRow(session: AiSession) {
    val c = D.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.width(10.dp))
        val s = session.status
        Text(
            when (s) {
                is AiSession.Status.Reading -> if (s.from == s.to) stringResource(R.string.ai_reading_page, s.from + 1) else stringResource(R.string.ai_reading_pages, s.from + 1, s.to + 1)
                else -> stringResource(R.string.ai_thinking)
            },
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { session.cancel() }) { Text(stringResource(R.string.ai_stop)) }
    }
}

/** User-facing text for a failed request. */
@Composable
internal fun aiErrorText(kind: Gemini.AiException.Kind?, detail: String?): String = when (kind) {
    Gemini.AiException.Kind.NO_KEY -> stringResource(R.string.ai_err_no_key)
    Gemini.AiException.Kind.BAD_KEY -> stringResource(R.string.ai_err_bad_key)
    Gemini.AiException.Kind.QUOTA -> stringResource(R.string.ai_err_quota)
    Gemini.AiException.Kind.NETWORK -> stringResource(R.string.ai_err_network)
    Gemini.AiException.Kind.BLOCKED -> stringResource(R.string.ai_err_blocked)
    Gemini.AiException.Kind.SERVER -> stringResource(R.string.ai_err_server)
    null -> when (detail) {
        AiSession.STOPPED -> stringResource(R.string.ai_stopped)
        "memory" -> stringResource(R.string.ai_err_memory)
        else -> stringResource(R.string.ai_err_generic, detail ?: "")
    }
}

@Composable
private fun FailureCard(session: AiSession, f: AiSession.Failure, openSettings: () -> Unit) {
    val c = D.c
    val keyProblem = f.kind == Gemini.AiException.Kind.NO_KEY || f.kind == Gemini.AiException.Kind.BAD_KEY
    val stopped = f.kind == null && f.detail == AiSession.STOPPED
    val canRetry = !keyProblem && session.messages.lastOrNull { it.role != "note" }?.role == "user"
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .background(if (stopped) c.surfaceAlt else c.danger.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .border(1.dp, if (stopped) c.line else c.danger.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (!stopped) { Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(aiErrorText(f.kind, f.detail), style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { session.dismissFailure() }) { Text(stringResource(R.string.ai_dismiss), color = c.muted) }
            if (keyProblem) TextButton(onClick = openSettings) { Text(stringResource(R.string.ai_open_settings)) }
            if (canRetry) TextButton(onClick = { session.retry() }) { Text(stringResource(R.string.ai_retry)) }
        }
    }
}

@Composable
private fun Composer(session: AiSession, onQuestions: () -> Unit) {
    val c = D.c
    val explain = stringResource(R.string.ai_prompt_explain)
    val answer = stringResource(R.string.ai_prompt_answer)
    val solve = stringResource(R.string.ai_prompt_solve)
    val translate = stringResource(R.string.ai_prompt_translate)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        session.attachment?.let { att ->
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp)).border(1.dp, c.line, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    att.preview?.let { Image(it, stringResource(R.string.ai_selection_image), contentScale = ContentScale.Fit, modifier = Modifier.padding(2.dp)) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ai_selection_from_page, att.page + 1), style = MaterialTheme.typography.labelMedium, color = c.ink)
                    att.recognized?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconButton(onClick = { session.removeAttachment() }) { Icon(Icons.Rounded.Close, stringResource(R.string.ai_remove_attachment), tint = c.muted) }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val enabled = !session.busy
            fun go(p: String) { if (enabled) session.send(p) }
            Chip(stringResource(R.string.ai_chip_explain), false, { go(explain) })
            Chip(stringResource(R.string.ai_chip_answer), false, { go(answer) })
            Chip(stringResource(R.string.ai_chip_solve), false, { go(solve) })
            Chip(stringResource(R.string.ai_chip_translate), false, { go(translate) })
            Chip(stringResource(R.string.ai_chip_questions), false, onQuestions, leading = Icons.Rounded.Quiz)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = session.draft, onValueChange = { session.draft = it },
                placeholder = { Text(stringResource(R.string.ai_input_hint)) },
                maxLines = 5, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                ),
                modifier = Modifier.weight(1f),
            )
            val canSend = !session.busy && (session.draft.isNotBlank() || session.attachment != null)
            IconButton(onClick = { session.send() }, enabled = canSend, modifier = Modifier.padding(bottom = 4.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.ai_send), tint = if (canSend) c.accent else c.muted.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun PermissionDialog(session: AiSession, ask: AiSession.PermissionAsk) {
    val c = D.c
    var always by remember(ask) { mutableStateOf(false) }
    val name = session.file.name
    AlertDialog(
        onDismissRequest = { ask.answer(AiSession.Decision.DENY, false) },
        title = { Text(stringResource(R.string.ai_perm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        ask.whole -> stringResource(R.string.ai_perm_whole, name)
                        ask.from == ask.to -> stringResource(R.string.ai_perm_page, ask.from + 1, name)
                        else -> stringResource(R.string.ai_perm_pages, ask.from + 1, ask.to + 1, name)
                    },
                    color = c.ink, style = MaterialTheme.typography.bodyLarge,
                )
                if (ask.reason.isNotBlank()) Text(stringResource(R.string.ai_perm_reason, ask.reason), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.ai_perm_only_allowed), color = c.muted, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().clickable { always = !always }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(always, { always = it }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                    Text(stringResource(R.string.ai_perm_always), color = c.ink, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!ask.whole) TextButton(onClick = { ask.answer(AiSession.Decision.PAGES, always) }) { Text(stringResource(R.string.ai_perm_allow_pages)) }
                TextButton(onClick = { ask.answer(AiSession.Decision.WHOLE, always) }) { Text(stringResource(R.string.ai_perm_allow_whole)) }
                TextButton(onClick = { ask.answer(AiSession.Decision.DENY, false) }) { Text(stringResource(R.string.ai_perm_deny), color = c.muted) }
            }
        },
    )
}

@Composable
internal fun PrivacyDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_privacy_title)) },
        text = { Text(stringResource(R.string.ai_privacy_text), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.ai_privacy_accept)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
