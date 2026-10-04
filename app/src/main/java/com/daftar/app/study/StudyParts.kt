package com.daftar.app.study

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.daftar.app.R
import com.daftar.app.data.FolderMeta
import com.daftar.app.data.Kind
import com.daftar.app.data.Storage
import com.daftar.app.ui.Chip
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.theme.folderColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Paper colour behind card images (ink is drawn dark on transparent, so it always sits on paper, also in dark mode). */
val Paper = Color(0xFFFFFDF8)

/** A subject (deck) as shown in the UI. [path] "" = General. */
data class Subject(val path: String, val name: String, val meta: FolderMeta)

/** Root folders of the library = subjects. */
fun subjects(): List<Subject> =
    Storage.list(Storage.root).filter { it.kind == Kind.FOLDER }.map { Subject(it.file.absolutePath, it.file.name, it.meta ?: FolderMeta()) }

@Composable
fun rememberSubjects(): List<Subject> {
    val v = Storage.version
    return remember(v) { subjects() }
}

/** Subject folder (first folder under the library root) that contains [f], or "" when none. */
fun subjectOf(f: File): String {
    val dir = if (f.isDirectory) f else f.parentFile ?: return ""
    return Storage.crumbs(dir).getOrNull(1)?.absolutePath ?: ""
}

@Composable
fun subjectLabel(path: String): String = if (path.isEmpty()) stringResource(R.string.study_general) else File(path).name

fun subjectMeta(path: String): FolderMeta =
    if (path.isEmpty()) FolderMeta(color = 11, icon = "inbox") else if (File(path).isDirectory) Storage.meta(File(path)) else FolderMeta(color = 11, icon = "folder")

fun subjectColor(path: String): Color = folderColor(subjectMeta(path).color)

/** Chips: General + every subject; the selected one is tinted with its folder colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectChips(selected: String, onPick: (String) -> Unit, extra: List<String> = emptyList()) {
    val subs = rememberSubjects()
    val paths = (listOf("") + subs.map { it.path } + extra).distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { p ->
            Chip(subjectLabel(p), p == selected, { onPick(p) }, tint = subjectColor(p))
        }
    }
}

/** Card image from the store, decoded off the main thread with sampling, shown on paper. */
@Composable
fun CardImage(name: String, modifier: Modifier = Modifier, maxSide: Int = 1200, contentScale: ContentScale = ContentScale.Fit) {
    val bmp by produceState(ImageCache.peek(name, maxSide), name, maxSide) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { ImageCache.load(name, maxSide) }.getOrNull() }
    }
    Box(modifier.background(Paper, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(8.dp), contentScale = contentScale) }
    }
}

@Composable
fun BitmapImage(bmp: Bitmap, modifier: Modifier = Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    Box(modifier.background(Paper, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Image(img, null, Modifier.fillMaxWidth().padding(8.dp), contentScale = ContentScale.Fit)
    }
}

/** "10m", "1d", "6d", "3mo", "1.2y" — next-interval hints on the grade buttons. */
@Composable
fun intervalLabel(ms: Long): String {
    val min = ms / 60_000L
    val days = (ms + DAY_MS / 2) / DAY_MS
    return when {
        min < 60 -> stringResource(R.string.study_iv_min, min.coerceAtLeast(1))
        min < 24 * 60 -> stringResource(R.string.study_iv_hour, min / 60)
        days < 30 -> stringResource(R.string.study_iv_day, days)
        days < 365 -> stringResource(R.string.study_iv_month, String.format(java.util.Locale.getDefault(), "%.1f", days / 30.0).removeSuffix(".0").removeSuffix(",0"))
        else -> stringResource(R.string.study_iv_year, String.format(java.util.Locale.getDefault(), "%.1f", days / 365.0).removeSuffix(".0").removeSuffix(",0"))
    }
}

/** "New", "Due now", "in 3d". */
@Composable
fun dueLabel(c: Flashcard, now: Long): String = when {
    c.backPending && !c.hasBack -> stringResource(R.string.study_answer_needed)
    c.isNew -> stringResource(R.string.study_new)
    c.due <= now -> stringResource(R.string.study_due_now)
    else -> stringResource(R.string.study_due_in, intervalLabel(c.due - now))
}

// =====================================================================================
// Small ink pad: simple Compose strokes, exported to a transparent bitmap
// =====================================================================================

class InkPadState {
    val strokes = mutableStateListOf<List<Offset>>()
    internal var live by mutableStateOf<List<Offset>>(emptyList())
    internal var size = IntSize.Zero
    val isEmpty get() = strokes.isEmpty()

    fun undo() { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }
    fun clear() { strokes.clear() }

    /** Renders the strokes cropped to their bounds (+ margin) as dark ink on transparent. Null when empty. */
    fun toBitmap(widthPx: Float): Bitmap? {
        if (strokes.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        strokes.forEach { s -> s.forEach { p -> l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y) } }
        val m = widthPx * 3
        l -= m; t -= m; r += m; b += m
        val w = (r - l).toInt().coerceAtLeast(8); val h = (b - t).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1F2937.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = widthPx
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { s ->
            if (s.size == 1) { cv.drawPoint(s[0].x - l, s[0].y - t, paint); return@forEach }
            val p = android.graphics.Path()
            p.moveTo(s[0].x - l, s[0].y - t)
            for (i in 1 until s.size) {
                val a = s[i - 1]; val c = s[i]
                p.quadTo(a.x - l, a.y - t, (a.x + c.x) / 2 - l, (a.y + c.y) / 2 - t)
            }
            p.lineTo(s.last().x - l, s.last().y - t)
            cv.drawPath(p, paint)
        }
        return bmp
    }
}

@Composable
fun rememberInkPad() = remember { InkPadState() }

/** A paper rectangle you can write on with the S Pen or a finger, with undo / clear. */
@Composable
fun InkPad(state: InkPadState, modifier: Modifier = Modifier, height: Dp = 180.dp) {
    val c = D.c
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }
    val inkColor = Color(0xFF1F2937)
    Box(modifier.fillMaxWidth().height(height).background(Paper, RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))) {
        Canvas(
            Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)).onSizeChanged { state.size = it }
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val pts = ArrayList<Offset>()
                        pts.add(down.position)
                        state.live = pts.toList()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (ch.positionChange() != Offset.Zero) {
                                ch.historical.forEach { pts.add(it.position) }
                                pts.add(ch.position)
                                ch.consume()
                                state.live = pts.toList()
                            }
                        }
                        state.strokes.add(pts.toList())
                        state.live = emptyList()
                    }
                },
        ) {
            // faint ruled lines
            val gap = 32.dp.toPx()
            var y = gap
            while (y < size.height) { drawLine(Color(0x14000000), Offset(0f, y), Offset(size.width, y), 1f); y += gap }
            fun draw(s: List<Offset>) {
                if (s.isEmpty()) return
                if (s.size == 1) { drawCircle(inkColor, strokePx / 2, s[0]); return }
                val p = Path().apply {
                    moveTo(s[0].x, s[0].y)
                    for (i in 1 until s.size) { val a = s[i - 1]; val b = s[i]; quadraticTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2) }
                    lineTo(s.last().x, s.last().y)
                }
                drawPath(p, inkColor, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            state.strokes.forEach(::draw)
            draw(state.live)
        }
        if (state.isEmpty && state.live.isEmpty()) Text(stringResource(R.string.study_draw_hint), color = Color(0xFF9CA3AF),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { state.undo() }, enabled = !state.isEmpty) { Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.study_undo), tint = Color(0xFF6B7280)) }
            IconButton(onClick = { state.clear() }, enabled = !state.isEmpty) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.study_clear), tint = Color(0xFF6B7280)) }
        }
    }
}
