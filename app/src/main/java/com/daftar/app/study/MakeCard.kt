package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daftar.app.R
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Editable state of one card side: text, an image (stored, or a fresh lasso bitmap) and an optional drawing. */
internal class SideState(text: String, val stored: String, val lasso: Bitmap?) {
    var text by mutableStateOf(text)
    var useImage by mutableStateOf(stored.isNotEmpty() || lasso != null)
    var drawing by mutableStateOf(false)
    /** Back side only: "Lasso the answer later". */
    var later by mutableStateOf(false)
    val pad = InkPadState()

    val hasImage get() = stored.isNotEmpty() || lasso != null
    val isEmpty get() = text.isBlank() && !(useImage && hasImage) && !(drawing && !pad.isEmpty)

    /** Writes any new image (drawing wins over lasso/stored) and returns the image file name to keep. Off the main thread. */
    fun commitImage(tag: String, strokePx: Float): String {
        if (drawing && !pad.isEmpty) pad.toBitmap(strokePx)?.let { b -> return Flashcards.saveImage(b, tag).also { b.recycle() } }
        if (!useImage) return ""
        if (lasso != null) return Flashcards.saveImage(lasso, tag)
        return stored
    }
}

/**
 * Called by the note editor's lasso menu ("Make flashcard"): [front] is the lassoed content, [recognizedText] the
 * handwriting/typed text if any. Back: type, draw on a small pad, or lasso it later. Deck defaults to the source's subject.
 */
@Composable
fun MakeFlashcardDialog(source: File, front: Bitmap, recognizedText: String?, page: Int, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    var round by remember { mutableIntStateOf(0) }
    var deck by remember { mutableStateOf(subjectOf(source)) }
    val frontSide = remember(round) { SideState(if (round == 0) recognizedText?.trim().orEmpty() else "", "", front).also { if (round > 0) it.useImage = false } }
    val backSide = remember(round) { SideState("", "", null) }
    var saving by remember { mutableStateOf(false) }
    val pendingCards = remember(Flashcards.version) { Flashcards.pendingFor(source) }
    val savedMsg = stringResource(R.string.study_saved)
    val answerMsg = stringResource(R.string.study_answer_added)

    fun save(another: Boolean) {
        if (saving) return
        if (frontSide.isEmpty) { toast(ctx, ctx.getString(R.string.study_front_empty)); return }
        if (!backSide.later && backSide.isEmpty) { toast(ctx, ctx.getString(R.string.study_back_empty)); return }
        saving = true
        scope.launch {
            val (fi, bi) = withContext(Dispatchers.IO) { frontSide.commitImage("f", strokePx) to (if (backSide.later) "" else backSide.commitImage("b", strokePx)) }
            val now = System.currentTimeMillis()
            Flashcards.upsert(Flashcard(
                id = Flashcards.newId(), deck = deck, frontText = frontSide.text.trim(), frontImage = fi,
                backText = if (backSide.later) "" else backSide.text.trim(), backImage = bi, backPending = backSide.later,
                source = source.absolutePath, page = page, created = now, due = now,
            ))
            toast(ctx, savedMsg)
            saving = false
            if (another) round++ else onDismiss()
        }
    }

    fun useAsAnswer(card: Flashcard) {
        if (saving) return
        saving = true
        scope.launch {
            val img = withContext(Dispatchers.IO) { Flashcards.saveImage(front, "b") }
            val text = recognizedText?.trim().orEmpty()
            Flashcards.get(card.id)?.let { cur -> Flashcards.upsert(cur.copy(backImage = img, backText = cur.backText.ifBlank { text }, backPending = false)) }
            toast(ctx, answerMsg)
            saving = false
            onDismiss()
        }
    }

    StudyDialog(stringResource(R.string.study_make_card), onDismiss, buttons = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { save(true) }, enabled = !saving) { Text(stringResource(R.string.study_save_another), maxLines = 1) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { save(false) }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_save)) }
    }) {
        if (pendingCards.isNotEmpty() && round == 0) {
            Column(Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text(stringResource(R.string.study_use_as_answer), style = MaterialTheme.typography.labelLarge, color = c.ink)
                pendingCards.take(4).forEach { pc ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(c.surface).clickable { useAsAnswer(pc) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (pc.frontImage.isNotEmpty()) { CardImage(pc.frontImage, Modifier.size(56.dp, 40.dp), maxSide = 200); Spacer(Modifier.width(10.dp)) }
                        Text(pc.frontText.ifBlank { stringResource(R.string.study_untitled_card) }, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.study_use), color = c.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        SideEditor(stringResource(R.string.study_front), frontSide, allowLater = false)
        Spacer(Modifier.height(16.dp))
        SideEditor(stringResource(R.string.study_back), backSide, allowLater = true)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.study_deck), style = MaterialTheme.typography.labelLarge, color = c.muted)
        Spacer(Modifier.height(8.dp))
        SubjectChips(deck, { deck = it })
    }
}

/** New manual card ([card] null) or edit an existing one. */
@Composable
fun CardEditorDialog(card: Flashcard?, presetDeck: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = D.c
    val scope = rememberCoroutineScope()
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    var deck by remember { mutableStateOf(card?.deck ?: presetDeck) }
    val frontSide = remember { SideState(card?.frontText.orEmpty(), card?.frontImage.orEmpty(), null) }
    val backSide = remember { SideState(card?.backText.orEmpty(), card?.backImage.orEmpty(), null) }
    var saving by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    val savedMsg = stringResource(R.string.study_saved)

    fun save(another: Boolean) {
        if (saving) return
        if (frontSide.isEmpty) { toast(ctx, ctx.getString(R.string.study_front_empty)); return }
        if (backSide.isEmpty && card?.backPending != true) { toast(ctx, ctx.getString(R.string.study_back_empty)); return }
        saving = true
        scope.launch {
            val (fi, bi) = withContext(Dispatchers.IO) { frontSide.commitImage("f", strokePx) to backSide.commitImage("b", strokePx) }
            val now = System.currentTimeMillis()
            if (card == null) {
                Flashcards.upsert(Flashcard(id = Flashcards.newId(), deck = deck, frontText = frontSide.text.trim(), frontImage = fi,
                    backText = backSide.text.trim(), backImage = bi, created = now, due = now))
            } else {
                if (card.frontImage.isNotEmpty() && card.frontImage != fi) Flashcards.deleteImageLater(card.frontImage)
                if (card.backImage.isNotEmpty() && card.backImage != bi) Flashcards.deleteImageLater(card.backImage)
                val cur = Flashcards.get(card.id) ?: card
                Flashcards.upsert(cur.copy(deck = deck, frontText = frontSide.text.trim(), frontImage = fi, backText = backSide.text.trim(), backImage = bi,
                    backPending = cur.backPending && backSide.isEmpty))
            }
            toast(ctx, savedMsg)
            saving = false
            if (another && card == null) {
                frontSide.text = ""; backSide.text = ""; frontSide.pad.clear(); backSide.pad.clear()
                frontSide.drawing = false; backSide.drawing = false
                round++
            } else onDismiss()
        }
    }

    StudyDialog(stringResource(if (card == null) R.string.study_new_card else R.string.study_edit_card), onDismiss, buttons = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        Spacer(Modifier.weight(1f))
        if (card == null) {
            OutlinedButton(onClick = { save(true) }, enabled = !saving) { Text(stringResource(R.string.study_save_another), maxLines = 1) }
            Spacer(Modifier.width(8.dp))
        }
        Button(onClick = { save(false) }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_save)) }
    }) {
        key(round) {
            SideEditor(stringResource(R.string.study_front), frontSide, allowLater = false)
            Spacer(Modifier.height(16.dp))
            SideEditor(stringResource(R.string.study_back), backSide, allowLater = false)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.study_deck), style = MaterialTheme.typography.labelLarge, color = c.muted)
        Spacer(Modifier.height(8.dp))
        SubjectChips(deck, { deck = it }, extra = listOfNotNull(card?.deck))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SideEditor(label: String, s: SideState, allowLater: Boolean) {
    val c = D.c
    Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.study_mode_type), !s.drawing && !s.later, { s.drawing = false; s.later = false }, leading = Icons.Rounded.Keyboard)
            Chip(stringResource(R.string.study_mode_draw), s.drawing && !s.later, { s.drawing = true; s.later = false }, leading = Icons.Rounded.Gesture)
            if (allowLater) Chip(stringResource(R.string.study_mode_later), s.later, { s.later = true; s.drawing = false }, leading = Icons.Rounded.Schedule)
            if (s.hasImage && !s.later) Chip(stringResource(R.string.study_include_image), s.useImage, { s.useImage = !s.useImage }, leading = Icons.Rounded.Image)
        }
        if (s.later) {
            Text(stringResource(R.string.study_later_hint), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 10.dp))
            return@Column
        }
        if (s.hasImage && s.useImage && !(s.drawing && !s.pad.isEmpty)) {
            Spacer(Modifier.height(10.dp))
            Box {
                if (s.lasso != null) BitmapImage(s.lasso, Modifier.fillMaxWidth().heightIn(max = 220.dp).border(1.dp, c.line, RoundedCornerShape(12.dp)))
                else CardImage(s.stored, Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 220.dp).border(1.dp, c.line, RoundedCornerShape(12.dp)), maxSide = 900)
                IconButton(onClick = { s.useImage = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.study_remove_image), tint = androidx.compose.ui.graphics.Color(0xFF6B7280))
                }
            }
        }
        if (s.drawing) {
            Spacer(Modifier.height(10.dp))
            InkPad(s.pad)
            if (s.hasImage && s.useImage) Text(stringResource(R.string.study_drawing_replaces), style = MaterialTheme.typography.bodySmall, color = c.muted,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(s.text, { s.text = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            label = { Text(stringResource(if (s.drawing) R.string.study_text_optional else R.string.study_text)) },
            leadingIcon = if (s.drawing) ({ Icon(Icons.Rounded.Draw, null) }) else null)
    }
}

/** Flat dialog shell (24dp radius, scrollable body, button row) sized for phones and tablets. */
@Composable
internal fun StudyDialog(title: String, onDismiss: () -> Unit, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = D.c
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 600.dp).fillMaxWidth().imePadding()
                .background(c.surface, RoundedCornerShape(24.dp)).border(1.dp, c.line, RoundedCornerShape(24.dp)),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), content = content)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}
