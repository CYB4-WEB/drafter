package com.daftar.app.planner.countdown

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.planner.DeviceCalendar
import com.daftar.app.planner.EventType
import com.daftar.app.planner.Notify
import com.daftar.app.planner.Occurrence
import com.daftar.app.planner.Planner
import com.daftar.app.planner.fmtTime
import com.daftar.app.planner.localeOf
import com.daftar.app.planner.typeIcon
import com.daftar.app.planner.typeLabelRes
import com.daftar.app.ui.*
import com.daftar.app.ui.theme.D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen live countdown (Screen.Countdown): big colour card, icon, title, subtitle and the darker band with
 * days · hours · mins · secs. Ticks every second only while the screen is visible. Swipe to the next / previous event.
 */
@Composable
fun LiveCountdownScreen(eventId: Long, occStart: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = Planner.version
    val now = rememberTickingNow(1000) // lifecycle-aware: stops when not visible
    val subjects = rememberSubjectMap()

    // Pages: every upcoming event (one card each), with the requested occurrence in its place.
    val pages = remember(v, eventId, occStart) {
        val t = System.currentTimeMillis()
        val list = futureCountdowns(Planner.all(), t).toMutableList()
        val target = occurrenceFor(eventId, occStart, t)
        if (target != null) {
            val same = list.indexOfFirst { it.event.id == target.event.id }
            if (same >= 0 && list[same].start != target.start) list.removeAt(same)
            if (list.none { it.event.id == target.event.id }) {
                val i = list.indexOfFirst { it.start > target.start }.let { if (it < 0) list.size else it }
                list.add(i, target)
            }
        }
        list.toList()
    }
    var curId by rememberSaveable { mutableLongStateOf(eventId) }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.event.id == curId }.coerceAtLeast(0)) { pages.size }
    LaunchedEffect(pages) {
        val i = pages.indexOfFirst { it.event.id == curId }
        if (i >= 0 && i != pager.currentPage) pager.scrollToPage(i)
        snapshotFlow { pager.settledPage }.collectLatest { p -> pages.getOrNull(p)?.let { curId = it.event.id } }
    }
    val cur = pages.getOrNull(pager.currentPage)
    val layers = remember { HashMap<Int, GraphicsLayer>() }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(D.c.bg)) {
        ViewerTopBar(stringResource(R.string.cd_live_title), onBack = { pane.back() }) {
            if (cur != null) {
                IconButton(onClick = {
                    val layer = layers[pager.currentPage] ?: return@IconButton
                    scope.launch { shareCard(ctx, layer, cur) }
                }) { Icon(Icons.Rounded.Share, stringResource(R.string.cd_share), tint = D.c.ink) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.cd_more), tint = D.c.ink) }
                    LiveMenu(cur, menu, { menu = false }) { confirmDelete = true }
                }
            }
        }
        if (cur == null) {
            EmptyState(Icons.Rounded.EventBusy, stringResource(R.string.cd_not_found), Modifier.padding(top = 48.dp)) {
                TextButton(onClick = { pane.back() }) { Text(stringResource(R.string.back), color = D.c.accent) }
            }
            return@Column
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val gutter = if (maxWidth < 600.dp) D.gutter else D.gutterWide
            val cardW = (maxWidth - gutter * 2).coerceAtMost(720.dp)
            val cardH = (maxHeight - 88.dp).coerceIn(320.dp, 640.dp)
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages.getOrNull(it)?.let { o -> "${o.event.id}_${o.start}" } ?: it }) { page ->
                val o = pages[page]
                val layer = rememberGraphicsLayer()
                DisposableEffect(page, layer) { layers[page] = layer; onDispose { if (layers[page] === layer) layers.remove(page) } }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    // Recorded for "Share": the card on a paper margin (no transparent corners in chat apps).
                    Box(
                        Modifier.drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
                            .background(D.c.bg).padding(4.dp),
                    ) { LiveCard(o, subjects[o.event.id], now, cardW, cardH) }
                }
            }
        }
        // Page indicator with previous / next (keyboard, mouse and S Pen friendly).
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.cd_previous), tint = if (pager.currentPage > 0) D.c.ink else D.c.line)
            }
            Text(
                stringResource(R.string.cd_page, fmtNumber(ctx, pager.currentPage + 1L), fmtNumber(ctx, pages.size.toLong())),
                style = MaterialTheme.typography.labelMedium, color = D.c.muted, modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < pages.lastIndex) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.cd_next), tint = if (pager.currentPage < pages.lastIndex) D.c.ink else D.c.line)
            }
        }
    }

    if (confirmDelete && cur != null) {
        val e = cur.event
        ConfirmDialog(
            title = stringResource(R.string.planner_delete_title),
            text = stringResource(if (e.deviceEventId != 0L) R.string.planner_delete_text_synced else R.string.planner_delete_text, e.title),
            confirm = stringResource(R.string.delete), danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val last = pages.size <= 1
                Notify.cancel(ctx, e.id)
                Planner.delete(e.id) // also removes the phone-calendar copy
                if (last) pane.back()
            },
        )
    }
}

/** The reference card: colour block, icon ring, title, subtitle, then the darker live band. */
@Composable
private fun LiveCard(o: Occurrence, subject: SubjectInfo?, now: Long, w: Dp, h: Dp) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val styles = CountdownStyles.version
    val e = o.event
    val bg = cardColor(e, subject, CountdownStyles.get(ctx, e.id))
    val fg = contentOn(bg)
    val soft = fg.copy(alpha = 0.86f)
    val block = blockOn(bg)
    val narrow = w < 360.dp
    Column(Modifier.width(w).height(h).clip(RoundedCornerShape(16.dp)).background(bg)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            IconRing(typeIcon(e.type), fg, if (narrow || h < 400.dp) 56.dp else 84.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                e.title, color = fg, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                style = if (narrow) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
            )
            Text(subtitleOf(ctx, e, subject), style = MaterialTheme.typography.bodyLarge, color = soft, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.padding(top = 12.dp).background(block, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (e.weekly) { Icon(Icons.Rounded.Repeat, null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                Text(
                    stringResource(typeLabelRes(e.type)) + if (e.weekly) " · " + stringResource(R.string.cd_weekly) else "",
                    style = MaterialTheme.typography.labelMedium, color = fg,
                )
            }
        }
        LiveBand(o, now, fg, soft, block, w)
    }
}

@Composable
private fun LiveBand(o: Occurrence, now: Long, fg: Color, soft: Color, block: Color, w: Dp) {
    val ctx = LocalContext.current
    val e = o.event
    val big: TextStyle = when {
        w < 340.dp -> MaterialTheme.typography.headlineLarge
        w < 560.dp -> MaterialTheme.typography.displayMedium
        else -> MaterialTheme.typography.displayLarge
    }.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Column(
        Modifier.fillMaxWidth().background(block).padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (stateOf(o, now)) {
            CdState.DONE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TaskAlt, null, tint = fg, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.cd_done), style = big, color = fg)
                }
                Text(stringResource(R.string.cd_due_on, fullDate(ctx, o)), style = MaterialTheme.typography.bodyMedium, color = soft,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            CdState.NOW -> {
                Text(stringResource(if (o.end > o.start) R.string.cd_started else R.string.cd_now), style = big, color = fg, textAlign = TextAlign.Center)
                val line = if (o.end > o.start && !e.allDay) stringResource(R.string.cd_ends_at, fmtTime(ctx, o.end))
                else stringResource(R.string.cd_until, fullDate(ctx, o))
                Text(line, style = MaterialTheme.typography.bodyMedium, color = soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            CdState.PAST -> {
                Text(stringResource(R.string.cd_finished), style = big, color = fg, textAlign = TextAlign.Center)
                val ago = endLabel(ctx, o, now)
                Text(
                    listOfNotNull(ago.number?.let { "$it ${ago.unit}" } ?: ago.unit, fullDate(ctx, o)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
                if (e.type == EventType.EXAM) {
                    Box(Modifier.padding(top = 12.dp).background(D.c.surface, RoundedCornerShape(12.dp))) {
                        com.daftar.app.grades.GradeItButton(e) // grades-agent: hands the exam to GradesScreen
                    }
                }
            }
            else -> {
                val p = liveParts(o.start, now)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Cell(p[0], R.plurals.cd_unit_days, big, fg, soft)
                    Cell(p[1], R.plurals.cd_unit_hours, big, fg, soft)
                    Cell(p[2], R.plurals.cd_unit_mins, big, fg, soft)
                    Cell(p[3], R.plurals.cd_unit_secs, big, fg, soft)
                }
                Text(stringResource(R.string.cd_until, fullDate(ctx, o)), style = MaterialTheme.typography.bodyMedium, color = soft,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun Cell(n: Long, unitRes: Int, big: TextStyle, fg: Color, soft: Color) {
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(String.format(localeOf(ctx), "%02d", n), style = big, color = fg, maxLines = 1)
        Text(pluralStringResource(unitRes, n.toInt(), n.toInt()), style = MaterialTheme.typography.labelLarge, color = soft, maxLines = 1)
    }
}

/** ⋯ menu: Edit event · Open linked folder · Add to calendar (or open the phone copy) · Mark done · Delete. */
@Composable
private fun LiveMenu(o: Occurrence, open: Boolean, onClose: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val e = o.event
    DropdownMenu(open, onClose) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_edit_event)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, null, tint = D.c.muted) },
            onClick = { onClose(); pane.push(Screen.EditEvent(e.id)) },
        )
        if (e.folder.isNotBlank() && File(e.folder).isDirectory) DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_open_folder)) },
            leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, tint = D.c.muted) },
            onClick = { onClose(); pane.open(ctx, File(e.folder)) },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (e.deviceEventId != 0L) R.string.cd_open_in_calendar else R.string.cd_add_to_calendar)) },
            leadingIcon = { Icon(Icons.Rounded.EditCalendar, null, tint = D.c.muted) },
            onClick = {
                onClose()
                if (e.deviceEventId != 0L) DeviceCalendar.openInApp(ctx, e, o) else DeviceCalendar.insertViaApp(ctx, e)
            },
        )
        if (e.type == EventType.ASSIGNMENT) DropdownMenuItem(
            text = { Text(stringResource(if (e.done) R.string.cd_mark_not_done else R.string.cd_mark_done)) },
            leadingIcon = { Icon(if (e.done) Icons.Rounded.RemoveDone else Icons.Rounded.TaskAlt, null, tint = D.c.muted) },
            onClick = { onClose(); Planner.setDone(e.id, !e.done) },
        )
        HorizontalDivider(color = D.c.line)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete), color = D.c.danger) },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = D.c.danger) },
            onClick = { onClose(); onDelete() },
        )
    }
}

/** Renders the recorded card to a PNG in cache and opens the share sheet (image + a one-line text). */
private suspend fun shareCard(ctx: Context, layer: GraphicsLayer, o: Occurrence) {
    val bmp = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull()
    val file = bmp?.let {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(ctx.cacheDir, "countdown").apply { mkdirs() }
                dir.listFiles()?.forEach { f -> f.delete() } // keep only the latest share image
                File(dir, "countdown_${System.currentTimeMillis()}.png").also { f ->
                    f.outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }
                }
            }.getOrNull().also { _ -> it.recycle() }
        }
    }
    val label = endLabel(ctx, o, System.currentTimeMillis())
    val text = ctx.getString(R.string.cd_share_text, o.event.title, label.number?.let { "$it ${label.unit}" } ?: label.unit, fullDate(ctx, o))
    val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
    if (file != null) {
        val uri = uriFor(ctx, file)
        i.setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
    } else i.setType("text/plain")
    runCatching { ctx.startActivity(Intent.createChooser(i, ctx.getString(R.string.share))) }
}
