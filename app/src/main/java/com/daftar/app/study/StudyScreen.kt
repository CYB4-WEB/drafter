package com.daftar.app.study

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.ConfirmDialog
import com.daftar.app.ui.EmptyState
import com.daftar.app.ui.Fab
import com.daftar.app.ui.FolderGlyph
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Screen
import com.daftar.app.ui.SectionTitle
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.card
import com.daftar.app.ui.pane
import com.daftar.app.ui.rememberTickingNow
import com.daftar.app.ui.theme.D

@Composable
internal fun gutter() = if (LocalWidthClass.current == WidthClass.Compact) D.gutter else D.gutterWide

private data class DeckInfo(val path: String, val total: Int, val due: Int)

@Composable
internal fun StudyScreenImpl() {
    val c = D.c
    val v = Flashcards.version
    val now = rememberTickingNow(60_000)
    var openDeck by rememberSaveableString()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Flashcard?>(null) }
    var creating by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { ImageCache.trim() } }

    val deck = openDeck
    BackHandler(enabled = deck != null) { openDeck = null }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (deck != null) DeckDetail(deck, query, { query = it }, now, onBack = { openDeck = null }, onEdit = { editing = it })
        else Hub(query, { query = it }, now, v, onDeck = { openDeck = it }, onEdit = { editing = it }, onNew = { creating = "" })
        Fab({ creating = deck ?: "" }, Modifier.align(Alignment.BottomEnd).padding(24.dp).navigationBarsPadding())
    }
    editing?.let { e -> CardEditorDialog(e, e.deck) { editing = null } }
    creating?.let { d -> CardEditorDialog(null, d) { creating = null } }
}

/** Survives rotation / pane moves (null = hub). */
@Composable
private fun rememberSaveableString(): MutableState<String?> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }

@Composable
private fun StudyHeader(title: String, subtitle: String?) {
    val c = D.c
    Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter()).padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.muted)
    }
}

@Composable
private fun Hub(query: String, onQuery: (String) -> Unit, now: Long, v: Int, onDeck: (String) -> Unit, onEdit: (Flashcard) -> Unit, onNew: () -> Unit) {
    val c = D.c
    val expanded = LocalWidthClass.current == WidthClass.Expanded
    val all = remember(v) { Flashcards.all() }
    val dueAll = remember(v, now) { Flashcards.dueCount(null, now) }
    val subs = rememberSubjects()
    val decks = remember(v, now, subs) {
        val byDeck = all.groupBy { it.deck }
        val order = subs.map { it.path }
        byDeck.map { (p, l) -> DeckInfo(p, l.size, l.count { it.isDue(now) && (it.hasBack || it.backPending) }) }
            .sortedWith(compareBy({ if (it.path.isEmpty()) -1 else order.indexOf(it.path).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.path }))
    }
    val minutesToday = remember(StudyLog.version, now / DAY_MS) { StudyLog.minutesToday() }
    val sub = buildList {
        add(pluralStringResource(R.plurals.study_n_cards_due, dueAll, dueAll))
        add(stringResource(R.string.study_focus_today, minutesToday))
    }.joinToString(" · ")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StudyHeader(stringResource(R.string.study_title), sub)
        Column(Modifier.padding(horizontal = gutter())) {
            if (expanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.6f)) { CardsColumn(all, decks, dueAll, query, onQuery, now, onDeck, onEdit, onNew, columns = 3) }
                    Column(Modifier.weight(1f)) { FocusSection(); StatsSection() }
                }
            } else {
                val compact = LocalWidthClass.current == WidthClass.Compact
                CardsColumn(all, decks, dueAll, query, onQuery, now, onDeck, onEdit, onNew, columns = if (compact) 2 else 3)
                FocusSection()
                StatsSection()
            }
            Spacer(Modifier.height(112.dp))
        }
    }
}

@Composable
private fun CardsColumn(
    all: List<Flashcard>, decks: List<DeckInfo>, dueAll: Int, query: String, onQuery: (String) -> Unit, now: Long,
    onDeck: (String) -> Unit, onEdit: (Flashcard) -> Unit, onNew: () -> Unit, columns: Int,
) {
    val c = D.c
    // Review-all banner
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).card(c).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Style, null, tint = c.accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.study_n_cards_due, dueAll, dueAll), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(pluralStringResource(R.plurals.study_n_cards, all.size, all.size) + " · " +
                stringResource(R.string.study_streak, StudyLog.streak()), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Button(onClick = { pane.push(Screen.Review(null)) }, enabled = dueAll > 0, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
            Text(stringResource(R.string.study_review_all))
        }
    }
    Spacer(Modifier.height(12.dp))
    StudySearchField(query, onQuery)
    if (query.isNotBlank()) {
        val q = query.trim().lowercase()
        val hits = all.filter { it.frontText.lowercase().contains(q) || it.backText.lowercase().contains(q) || (it.deck.isNotEmpty() && java.io.File(it.deck).name.lowercase().contains(q)) }
        SectionTitle(pluralStringResource(R.plurals.study_n_cards, hits.size, hits.size))
        if (hits.isEmpty()) Box(Modifier.fillMaxWidth().card(c)) { EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.study_no_results)) }
        else Column(Modifier.fillMaxWidth().card(c).padding(4.dp)) { hits.take(100).forEach { CardRow(it, now, showDeck = true, onEdit = onEdit) } }
        return
    }
    SectionTitle(stringResource(R.string.study_decks))
    if (decks.isEmpty()) {
        Box(Modifier.fillMaxWidth().card(c)) {
            EmptyState(Icons.Rounded.Style, stringResource(R.string.study_no_cards)) {
                Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text(stringResource(R.string.study_new_card)) }
            }
        }
        return
    }
    decks.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { d -> Box(Modifier.weight(1f)) { DeckTile(d) { onDeck(d.path) } } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DeckTile(d: DeckInfo, onClick: () -> Unit) {
    val c = D.c
    val m = subjectMeta(d.path)
    Column(Modifier.fillMaxWidth().card(c).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            FolderGlyph(m.color, m.icon, 44.dp)
            Spacer(Modifier.weight(1f))
            if (d.due > 0) Text("${d.due}", style = MaterialTheme.typography.labelLarge, color = c.onAccent,
                modifier = Modifier.background(c.accent, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(subjectLabel(d.path), style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pluralStringResource(R.plurals.study_n_cards, d.total, d.total) + if (d.due > 0) " · " + stringResource(R.string.study_n_due, d.due) else "",
            style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StudySearchField(query: String, onQuery: (String) -> Unit) {
    val c = D.c
    Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, null, tint = c.muted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.study_search_hint), color = c.muted, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(query, onQuery, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.study_clear), tint = c.muted) }
    }
}

@Composable
private fun DeckDetail(deck: String, query: String, onQuery: (String) -> Unit, now: Long, onBack: () -> Unit, onEdit: (Flashcard) -> Unit) {
    val c = D.c
    val v = Flashcards.version
    val cards = remember(deck, v) { Flashcards.inDeck(deck).sortedBy { it.due } }
    val due = cards.count { it.isDue(now) && (it.hasBack || it.backPending) }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) cards else cards.filter { it.frontText.lowercase().contains(q) || it.backText.lowercase().contains(q) }
    val m = subjectMeta(deck)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 112.dp)) {
        item {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = gutter()).padding(top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = c.ink) }
                Spacer(Modifier.width(4.dp))
                FolderGlyph(m.color, m.icon, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(subjectLabel(deck), style = MaterialTheme.typography.displaySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pluralStringResource(R.plurals.study_n_cards, cards.size, cards.size) + " · " + stringResource(R.string.study_n_due, due),
                        style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = gutter())) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pane.push(Screen.Review(deck)) }, enabled = due > 0, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
                        Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.study_review_n, due))
                    }
                }
                StudySearchField(query, onQuery)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (shown.isEmpty()) item {
            Box(Modifier.padding(horizontal = gutter()).fillMaxWidth().card(c)) {
                EmptyState(Icons.Rounded.Style, stringResource(if (q.isEmpty()) R.string.study_no_cards_deck else R.string.study_no_results))
            }
        }
        items(shown, key = { it.id }) { card ->
            Box(Modifier.padding(horizontal = gutter()).padding(bottom = 8.dp).fillMaxWidth().card(c).padding(4.dp)) {
                CardRow(card, now, showDeck = false, onEdit = onEdit)
            }
        }
    }
}

@Composable
private fun CardRow(card: Flashcard, now: Long, showDeck: Boolean, onEdit: (Flashcard) -> Unit) {
    val c = D.c
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onEdit(card) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (card.frontImage.isNotEmpty()) {
            CardImage(card.frontImage, Modifier.size(72.dp, 52.dp).border(1.dp, c.line, RoundedCornerShape(12.dp)), maxSide = 240)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(card.frontText.ifBlank { stringResource(R.string.study_image_card) }, style = MaterialTheme.typography.bodyLarge, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val back = when {
                card.backText.isNotBlank() -> card.backText
                card.backImage.isNotEmpty() -> stringResource(R.string.study_drawn_answer)
                else -> stringResource(R.string.study_answer_needed)
            }
            Text(back, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val due = card.isDue(now) && (card.hasBack || card.backPending)
                Text(dueLabel(card, now), style = MaterialTheme.typography.labelMedium, color = if (due) c.accent else c.muted)
                if (showDeck) Text(" · " + subjectLabel(card.deck), style = MaterialTheme.typography.labelMedium, color = subjectColor(card.deck), maxLines = 1)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more), tint = c.muted) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.study_edit_card)) }, { menu = false; onEdit(card) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                if (card.source.isNotEmpty()) DropdownMenuItem({ Text(stringResource(R.string.study_open_in_note)) }, { menu = false; openCardSource(ctx, card) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) })
                if (!card.isNew) DropdownMenuItem({ Text(stringResource(R.string.study_reset)) }, {
                    menu = false
                    Flashcards.upsert(card.copy(ease = 2.5, interval = 0, reps = 0, lapses = 0, due = System.currentTimeMillis(), lastReview = 0))
                }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) })
                DropdownMenuItem({ Text(stringResource(R.string.study_delete), color = c.danger) }, { menu = false; confirm = true },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.danger) })
            }
        }
    }
    if (confirm) ConfirmDialog(stringResource(R.string.study_delete_title), stringResource(R.string.study_delete_text), stringResource(R.string.study_delete), danger = true,
        onDismiss = { confirm = false }) { confirm = false; Flashcards.delete(card.id) }
}
