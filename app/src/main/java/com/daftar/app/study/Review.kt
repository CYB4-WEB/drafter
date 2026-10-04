package com.daftar.app.study

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.ViewerTopBar
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private val GradeColors = listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF10B981), Color(0xFF3B82F6))
private val GradeLabels = listOf(R.string.study_again, R.string.study_hard, R.string.study_good, R.string.study_easy)

/** Opens the card's source file in the current pane (and asks its viewer to jump to the card's page). */
fun openCardSource(ctx: Context, c: Flashcard) {
    val f = File(c.source)
    if (c.source.isEmpty() || !f.exists()) { toast(ctx, ctx.getString(R.string.study_source_missing)); return }
    if (c.page >= 0) StudyLinks.requestPage(f, c.page)
    pane.open(ctx, f)
}

@Composable
internal fun ReviewScreenImpl(deck: String?) {
    val ctx = LocalContext.current
    val c = D.c
    val queue = remember(deck) { mutableStateListOf<Long>().apply { addAll(Flashcards.due(deck).map { it.id }) } }
    val counts = remember(deck) { mutableStateListOf(0, 0, 0, 0) }
    var reviewed by remember(deck) { mutableIntStateOf(0) }
    val startedAt = remember(deck) { System.currentTimeMillis() }
    var flipped by remember { mutableStateOf(false) }
    var seq by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Flashcard?>(null) }
    val v = Flashcards.version
    val current = remember(queue.firstOrNull(), v) { queue.firstOrNull()?.let { Flashcards.get(it) } }
    // Cards deleted while reviewing just drop out of the queue.
    LaunchedEffect(queue.firstOrNull(), v) { if (queue.isNotEmpty() && current == null) queue.removeAt(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing == null) { if (editing == null) runCatching { focus.requestFocus() } }
    DisposableEffect(Unit) { onDispose { ImageCache.trim() } }

    fun grade(g: Int) {
        val card = current ?: return
        Flashcards.review(card, g)
        counts[g] = counts[g] + 1
        reviewed++
        queue.removeAt(0)
        if (g == Grade.AGAIN) queue.add(card.id)
        flipped = false
        seq++
    }

    val title = if (deck == null) stringResource(R.string.study_all_due) else subjectLabel(deck)
    Column(Modifier.fillMaxSize().background(c.bg)
        .focusRequester(focus).focusable()
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || current == null || editing != null) return@onPreviewKeyEvent false
            when (e.key) {
                Key.Spacebar, Key.Enter, Key.NumPadEnter -> { if (!flipped) flipped = true else grade(Grade.GOOD); true }
                Key.One, Key.NumPad1 -> { if (flipped) grade(Grade.AGAIN) else flipped = true; true }
                Key.Two, Key.NumPad2 -> { if (flipped) grade(Grade.HARD) else flipped = true; true }
                Key.Three, Key.NumPad3 -> { if (flipped) grade(Grade.GOOD) else flipped = true; true }
                Key.Four, Key.NumPad4 -> { if (flipped) grade(Grade.EASY) else flipped = true; true }
                else -> false
            }
        }) {
        ViewerTopBar(title, onBack = { pane.back() }) {
            if (current != null) {
                if (current.source.isNotEmpty()) IconButton(onClick = { openCardSource(ctx, current) }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.study_open_in_note), tint = c.ink)
                }
                IconButton(onClick = { editing = current }) { Icon(Icons.Rounded.Edit, stringResource(R.string.study_edit_card), tint = c.ink) }
            }
        }
        val total = reviewed + queue.size
        if (total > 0 && current != null) {
            LinearProgressIndicator(progress = { reviewed.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.line)
        }
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                when {
                    current != null -> {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.study_left, queue.size), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
                            if (deck == null) Text(subjectLabel(current.deck), style = MaterialTheme.typography.labelMedium, color = subjectColor(current.deck))
                        }
                        key(current.id, seq) {
                            FlipCard(current, flipped, onFlip = { flipped = !flipped }, onSwipe = { right -> grade(if (right) Grade.GOOD else Grade.AGAIN) },
                                modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                        Spacer(Modifier.height(12.dp))
                        if (!flipped) {
                            Button(onClick = { flipped = true }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                                Text(stringResource(R.string.study_show_answer), style = MaterialTheme.typography.titleMedium)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (g in 0..3) {
                                    val r = remember(current, g) { Sm2.schedule(current, g) }
                                    GradeButton(stringResource(GradeLabels[g]), intervalLabel(r.dueIn), GradeColors[g], "${g + 1}", Modifier.weight(1f)) { grade(g) }
                                }
                            }
                        }
                        Text(stringResource(R.string.study_review_hint), style = MaterialTheme.typography.bodySmall, color = c.muted, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    reviewed > 0 -> Summary(counts, reviewed, startedAt, deck)
                    else -> {
                        val any = remember(deck, v) { Flashcards.inDeck(deck).any { it.hasBack } }
                        Box(Modifier.fillMaxWidth().card(c)) {
                            EmptyState(Icons.Rounded.CheckCircle, stringResource(R.string.study_caught_up)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (any) OutlinedButton(onClick = {
                                        queue.addAll(Flashcards.inDeck(deck).filter { it.hasBack }.shuffled().map { it.id })
                                    }) { Text(stringResource(R.string.study_practice_all)) }
                                    Button(onClick = { pane.back() }, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_done)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { e -> CardEditorDialog(e, e.deck) { editing = null; seq++ } }
}

@Composable
private fun FlipCard(card: Flashcard, flipped: Boolean, onFlip: () -> Unit, onSwipe: (Boolean) -> Unit, modifier: Modifier) {
    val c = D.c
    val ctx = LocalContext.current
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(320), label = "flip")
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current.density
    val frac = offset.value / width
    val hint = when { frac > 0.08f -> GradeColors[Grade.GOOD]; frac < -0.08f -> GradeColors[Grade.AGAIN]; else -> null }
    Box(
        modifier.onSizeChanged { width = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = offset.value
                rotationZ = frac * 8f
                rotationY = rot
                cameraDistance = 14f * density
            }
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .border(if (hint != null) 2.dp else 1.dp, hint ?: c.line, RoundedCornerShape(20.dp))
            .clickable(onClick = onFlip)
            .pointerInput(flipped) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val w = width.toFloat()
                        if (flipped && abs(offset.value) > w * 0.28f) {
                            val right = offset.value > 0
                            scope.launch { offset.animateTo(if (right) w * 1.3f else -w * 1.3f, tween(180)); onSwipe(right) }
                        } else {
                            if (!flipped && abs(offset.value) > w * 0.2f) onFlip()
                            scope.launch { offset.animateTo(0f, tween(180)) }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { ch, d -> ch.consume(); scope.launch { offset.snapTo(offset.value + d) } }
            },
    ) {
        val showBack = rot > 90f
        Box(Modifier.fillMaxSize().graphicsLayer { rotationY = if (showBack) 180f else 0f }) {
            Text(stringResource(if (showBack) R.string.study_back else R.string.study_front), style = MaterialTheme.typography.labelMedium, color = c.muted,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
            if (hint != null) Text(stringResource(if (hint == GradeColors[Grade.GOOD]) R.string.study_good else R.string.study_again),
                style = MaterialTheme.typography.labelLarge, color = hint, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (!showBack) Face(card.frontText, card.frontImage)
                else if (card.hasBack) Face(card.backText, card.backImage)
                else {
                    Text(stringResource(R.string.study_no_answer_yet), style = MaterialTheme.typography.bodyLarge, color = c.muted, textAlign = TextAlign.Center)
                    if (card.source.isNotEmpty()) TextButton(onClick = { openCardSource(ctx, card) }) { Text(stringResource(R.string.study_open_in_note)) }
                }
            }
        }
    }
}

@Composable
private fun Face(text: String, image: String) {
    val c = D.c
    if (image.isNotEmpty()) CardImage(image, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 420.dp), maxSide = 1400)
    if (image.isNotEmpty() && text.isNotBlank()) Spacer(Modifier.height(16.dp))
    if (text.isNotBlank()) Text(text, style = if (text.length < 80) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
        color = c.ink, textAlign = TextAlign.Center)
}

@Composable
private fun GradeButton(label: String, hint: String, tint: Color, key: String, modifier: Modifier, onClick: () -> Unit) {
    val c = D.c
    Column(
        modifier.height(64.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = if (c.dark) 0.22f else 0.12f))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1)
        Text("$hint · $key", style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun Summary(counts: List<Int>, reviewed: Int, startedAt: Long, deck: String?) {
    val c = D.c
    val mins = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt().coerceAtLeast(1)
    val next = remember { Flashcards.inDeck(deck).filter { it.hasBack }.minOfOrNull { it.due } }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxWidth().card(c).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Style, null, tint = c.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.study_session_done), style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(pluralStringResource(R.plurals.study_n_reviewed, reviewed, reviewed) + " · " + stringResource(R.string.study_minutes_short, mins),
            style = MaterialTheme.typography.bodyMedium, color = c.muted)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in 0..3) Column(Modifier.weight(1f).background(GradeColors[g].copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${counts[g]}", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                Text(stringResource(GradeLabels[g]), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        if (next != null) Text(
            if (next <= now) stringResource(R.string.study_more_due) else stringResource(R.string.study_next_due, intervalLabel(next - now)),
            style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(20.dp))
        Button(onClick = { pane.back() }, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_done)) }
    }
}
