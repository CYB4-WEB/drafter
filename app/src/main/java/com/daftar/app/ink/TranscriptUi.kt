package com.daftar.app.ink

import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val Langs = listOf("ar-SA" to "عربي", "en-US" to "EN")

/**
 * Recording bar: "Recording 1:23" (4 Hz ticker only while recording and not paused), language switch for the live
 * transcript, transcript on/off, show/hide strip, pause/resume, stop. Below it the live transcript strip.
 */
@Composable
internal fun LectureRecordingBar(
    view: InkView,
    paused: Boolean,
    session: TranscriptSession,
    stripOpen: Boolean,
    onStripToggle: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onLang: (String) -> Unit,
) {
    val c = D.c
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(paused) {
        if (paused) elapsed = view.recOffset
        else while (isActive) { elapsed = SystemClock.elapsedRealtime() - view.recClockStart + view.recOffset; delay(250) }
    }
    val st = session.state
    Column(Modifier.fillMaxWidth().background(c.danger.copy(alpha = 0.10f))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (paused) c.muted else c.danger))
            Spacer(Modifier.width(8.dp))
            Text(
                if (paused) stringResource(R.string.tr_paused_now, fmtTime(elapsed)) else stringResource(R.string.ink_recording_now, fmtTime(elapsed)),
                color = c.ink, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (session.capable) {
                val code = st.lang
                Text(
                    Langs.firstOrNull { it.first == code }?.second ?: code,
                    style = MaterialTheme.typography.labelMedium, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, c.line, RoundedCornerShape(10.dp))
                        .clickable { onLang(if (code == "ar-SA") "en-US" else "ar-SA") }.padding(horizontal = 10.dp, vertical = 4.dp),
                )
                IconButton(onClick = { session.setOn(!session.on) }) {
                    Icon(if (session.on) Icons.Rounded.Subtitles else Icons.Rounded.SubtitlesOff,
                        stringResource(if (session.on) R.string.tr_live_on else R.string.tr_live_off),
                        tint = if (session.on) c.accent else c.muted)
                }
            }
            IconButton(onClick = onStripToggle) {
                Icon(if (stripOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    stringResource(if (stripOpen) R.string.tr_hide else R.string.tr_show), tint = c.muted)
            }
            IconButton(onClick = if (paused) onResume else onPause) {
                Icon(if (paused) Icons.Rounded.FiberManualRecord else Icons.Rounded.Pause,
                    stringResource(if (paused) R.string.tr_resume_rec else R.string.tr_pause_rec), tint = if (paused) c.danger else c.ink)
            }
            TextButton(onClick = onStop) { Text(stringResource(R.string.ink_stop)) }
        }
        if (stripOpen) LiveStrip(session)
    }
}

/** Live text: committed segments in ink, the partial (still changing) greyed; follows the newest words. */
@Composable
private fun LiveStrip(session: TranscriptSession) {
    val c = D.c
    val st = session.state
    val scroll = rememberScrollState()
    val note: String? = when {
        !session.capable || st.status == LiveTranscript.Status.UNSUPPORTED -> stringResource(R.string.tr_unsupported)
        !session.on -> stringResource(R.string.tr_off_hint)
        st.status == LiveTranscript.Status.LANG_UNAVAILABLE -> stringResource(R.string.tr_lang_unavailable)
        st.status == LiveTranscript.Status.FAILED -> stringResource(R.string.tr_failed)
        else -> null
    }
    val text = buildAnnotatedString {
        for (s in st.segments) { append(s.text); append(' ') }
        if (st.partial.isNotBlank()) withStyle(SpanStyle(color = c.muted)) { append(st.partial) }
    }
    LaunchedEffect(text.length) { scroll.animateScrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(note, color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (st.status == LiveTranscript.Status.FAILED && session.on) TextButton(onClick = { session.retry() }) { Text(stringResource(R.string.tr_retry)) }
            }
        }
        if (text.isNotEmpty()) {
            Text(text, color = c.ink, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                modifier = Modifier.fillMaxWidth().heightIn(max = 88.dp).verticalScroll(scroll))
        } else if (note == null) {
            Text(stringResource(if (st.status == LiveTranscript.Status.LISTENING) R.string.tr_listening else R.string.tr_starting),
                color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (session.capable && session.on && st.onDevice && note == null)
            Text(stringResource(R.string.tr_on_device), color = c.muted, style = MaterialTheme.typography.labelSmall)
    }
}

/** Audio playback bar; its position ticker runs only while playing (paused / stopped = no polling). */
@Composable
internal fun LecturePlaybackBar(
    r: Recording, player: MediaPlayer?, isPlaying: Boolean, view: InkView, clock: PlayClock,
    hasTranscript: Boolean, transcriptOpen: Boolean, onTranscriptToggle: () -> Unit,
    onToggle: () -> Unit, onClose: () -> Unit,
) {
    val c = D.c
    LaunchedEffect(player, isPlaying) {
        player?.let { clock.pos = runCatching { it.currentPosition.toLong() }.getOrDefault(clock.pos) }
        while (isPlaying && isActive) {
            player?.let { clock.pos = it.currentPosition.toLong(); view.playPos = clock.pos }
            delay(80)
        }
    }
    Row(Modifier.fillMaxWidth().background(c.accent.copy(alpha = 0.08f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggle) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = c.accent) }
        Text(fmtTime(clock.pos), style = MaterialTheme.typography.labelMedium, color = c.ink)
        Slider(
            value = clock.pos.toFloat().coerceIn(0f, r.duration.coerceAtLeast(1).toFloat()),
            onValueChange = { v -> player?.seekTo(v.toInt()); clock.pos = v.toLong(); view.playPos = clock.pos },
            valueRange = 0f..r.duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(fmtTime(r.duration), style = MaterialTheme.typography.labelMedium, color = c.muted)
        if (hasTranscript) IconButton(onClick = onTranscriptToggle) {
            Icon(Icons.Rounded.Subtitles, stringResource(if (transcriptOpen) R.string.tr_hide else R.string.tr_show), tint = if (transcriptOpen) c.accent else c.muted)
        }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted) }
    }
}

/**
 * Transcript list with karaoke highlight (segment under [clock] when [follow]), search, copy all, insert as text box
 * and export. Tapping a segment calls [onSeek] with its start.
 */
@Composable
internal fun TranscriptPanel(
    segs: List<TranscriptSeg>,
    clock: PlayClock?,
    follow: Boolean,
    onSeek: (Long) -> Unit,
    onInsert: (String) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    listMaxHeight: androidx.compose.ui.unit.Dp = 168.dp,
) {
    val c = D.c
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    val hits = remember(segs, query) { TranscriptText.search(segs, query) }
    var hit by remember(hits) { mutableIntStateOf(0) }
    val cur by remember(segs, clock, follow) { derivedStateOf { if (follow && clock != null) TranscriptText.indexAt(segs, clock.pos) else -1 } }
    val list = rememberLazyListState()
    val hitSet = remember(hits) { hits.toHashSet() }

    // karaoke: keep the current segment in view (never fights a finger that is scrolling)
    LaunchedEffect(cur) {
        if (cur < 0 || query.isNotBlank() || list.isScrollInProgress) return@LaunchedEffect
        val vis = list.layoutInfo.visibleItemsInfo
        val shown = vis.any { it.index == cur } && vis.lastOrNull()?.index != cur
        if (!shown) list.animateScrollToItem((cur - 1).coerceAtLeast(0))
    }
    LaunchedEffect(hits, hit) { hits.getOrNull(hit)?.let { list.animateScrollToItem(it) } }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.surfaceAlt).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.tr_search), color = c.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(c.accent),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink, textDirection = TextDirection.Content),
                        modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) {
                    Text(if (hits.isEmpty()) stringResource(R.string.tr_no_matches) else stringResource(R.string.tr_matches, hit + 1, hits.size),
                        color = c.muted, style = MaterialTheme.typography.labelSmall)
                    Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = c.muted,
                        modifier = Modifier.padding(start = 4.dp).size(18.dp).clickable { query = "" })
                }
            }
            if (hits.size > 1) {
                IconButton(onClick = { hit = (hit - 1 + hits.size) % hits.size }) { Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.tr_prev_match), tint = c.ink) }
                IconButton(onClick = { hit = (hit + 1) % hits.size }) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.tr_next_match), tint = c.ink) }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, enabled = segs.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.tr_transcript), tint = c.ink) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.tr_copy_all)) }, {
                        menu = false
                        clip.setText(AnnotatedString(TranscriptText.plain(segs, times = false)))
                        toast(ctx, ctx.getString(R.string.copied))
                    }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.tr_insert)) }, {
                        menu = false; onInsert(TranscriptText.plain(segs, times = false))
                    }, leadingIcon = { Icon(Icons.Rounded.TextFields, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.tr_export)) }, { menu = false; onExport() },
                        leadingIcon = { Icon(Icons.Rounded.Description, null) })
                }
            }
        }
        if (segs.isEmpty()) {
            Text(stringResource(R.string.tr_empty), color = c.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMaxHeight), state = list, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(segs) { i, s ->
                SegmentRow(s, current = i == cur, match = i in hitSet, focused = hits.getOrNull(hit) == i) { onSeek(s.start) }
            }
        }
    }
}

@Composable
private fun SegmentRow(s: TranscriptSeg, current: Boolean, match: Boolean, focused: Boolean, onClick: () -> Unit) {
    val c = D.c
    val rtl = TranscriptText.isRtl(s)
    val bg = when {
        current -> c.accent.copy(alpha = 0.14f)
        focused -> c.accent.copy(alpha = 0.10f)
        match -> c.accent.copy(alpha = 0.05f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (match) Modifier.border(1.dp, c.accent.copy(alpha = if (focused) 0.8f else 0.35f), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(fmtTime(s.start), color = if (current) c.accent else c.muted, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(44.dp).padding(top = 2.dp))
        // each segment keeps its own direction (an Arabic line in an English UI and vice versa)
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Text(s.text, color = c.ink, textAlign = TextAlign.Start,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** Full transcript of one recording (from the recordings dialog). */
@Composable
internal fun TranscriptDialog(
    title: String, segs: List<TranscriptSeg>, clock: PlayClock?, follow: Boolean,
    onSeek: (Long) -> Unit, onInsert: (String) -> Unit, onExport: () -> Unit, onDismiss: () -> Unit,
) {
    val c = D.c
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(R.string.tr_seek_hint), color = c.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                TranscriptPanel(segs, clock, follow, onSeek, onInsert, onExport, listMaxHeight = 380.dp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** One-line preview of a recording's transcript for the recordings dialog. */
internal fun transcriptPreview(segs: List<TranscriptSeg>): String {
    val t = TranscriptText.plain(segs, times = false)
    return if (t.length > 120) t.take(117).trimEnd() + "…" else t
}
