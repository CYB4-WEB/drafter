package com.daftar.app.slides

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.daftar.app.R
import com.daftar.app.ink.InkPage
import com.daftar.app.ink.InkRender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val LaserRed = Color(0xFFFF3B30)
private val ChromeBg = Color(0xE61C1F24)      // dark surface token, slightly see-through over the slide
private val ChromeLine = Color(0xFF2E333B)    // dark line token
private const val LASER_FADE_MS = 700f
private const val CHROME_MS = 3000L

/**
 * Full-screen slideshow from [start]. Shown in its own dialog window so it covers the whole app window even when the
 * deck is open inside a split pane or a second window. System bars are hidden (swipe shows them briefly) and the screen
 * stays on until [onExit], which receives the slide to return to.
 */
@Composable
internal fun PresentMode(
    src: PptxSource,
    thumbs: Thumbs,
    start: Int,
    inkPage: (Int) -> InkPage?,
    onExit: (Int) -> Unit,
) {
    val slides = src.deck.slides
    // Hidden slides are skipped while presenting (as in PowerPoint), unless every slide is hidden.
    val order = remember(src) { slides.filter { !it.hidden }.map { it.index }.ifEmpty { slides.map { it.index } } }
    val state = remember(src) { ShowState(start.coerceIn(0, slides.lastIndex), order) }
    val exit by rememberUpdatedState(onExit)

    Dialog(
        onDismissRequest = { exit(state.cur) },
        properties = DialogProperties(
            dismissOnBackPress = true, dismissOnClickOutside = false,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        ),
    ) {
        ImmersiveWindow()
        PresentContent(src, thumbs, state, inkPage) { exit(state.cur) }
    }
}

/** Navigation state of a running slideshow. */
private class ShowState(start: Int, private val order: List<Int>) {
    var cur by mutableIntStateOf(start)
    var ended by mutableStateOf(false)   // "End of slide show" screen after the last slide
    var blank by mutableIntStateOf(0)    // 0 none, 1 black screen, 2 white screen (B / W keys)
    var chromeTick by mutableIntStateOf(0)
    var typed = 0                        // digits typed for "number + Enter" jumps

    fun nextOf(i: Int): Int? = order.firstOrNull { it > i }
    fun prevOf(i: Int): Int? = order.lastOrNull { it < i }

    /** @return false when the show should close (advancing past the end screen). */
    fun next(): Boolean {
        if (blank != 0) { blank = 0; return true }
        if (ended) return false
        val n = nextOf(cur)
        if (n == null) ended = true else cur = n
        return true
    }

    fun prev() {
        if (blank != 0) { blank = 0; return }
        if (ended) { ended = false; return }
        prevOf(cur)?.let { cur = it }
    }

    fun goTo(i: Int, count: Int) {
        blank = 0; ended = false
        cur = i.coerceIn(0, count - 1)
    }
}

/** Configures the dialog's own window: black, edge to edge (cutouts too), no system bars, screen kept on. */
@Composable
private fun ImmersiveWindow() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
        val w = window ?: return@DisposableEffect onDispose { }
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(w, false)
        val insets = WindowCompat.getInsetsController(w, w.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

// ------------------------------------------------------------------ frames (≤ 3 screen-size bitmaps)

/**
 * Rendered slides for the show: the current one plus its neighbours, never more than [MAX] bitmaps, reused when the
 * show moves on. Mutated on the main thread only; rendering happens on a worker into bitmaps that are not on screen.
 */
private class Frames {
    val ready = mutableStateMapOf<Int, Bitmap>()
    private val spare = ArrayList<Bitmap>()
    private val retired = ArrayList<Bitmap>()
    private var allocated = 0
    var w = 0
        private set
    var h = 0
        private set
    /** The bitmap the slide layer recorded last; never reused or recycled while it may still be drawn. */
    var lastDrawn: Bitmap? = null
    private var released = false

    fun resize(nw: Int, nh: Int) { w = nw; h = nh }

    fun isFresh(i: Int): Boolean = ready[i]?.let { it.width == w && it.height == h } == true

    /** A free bitmap of the current size for slide rendering, or null when nothing can be freed now. */
    fun obtain(cur: Int, keep: Set<Int>, forCurrent: Boolean): Bitmap? {
        if (released || w <= 0 || h <= 0) return null
        retired.removeAll { b -> if (b !== lastDrawn) { recycleOrSpare(b); true } else false }
        while (spare.isNotEmpty()) {
            val b = spare.removeAt(spare.lastIndex)
            if (b.width == w && b.height == h && !b.isRecycled) return b
            drop(b)
        }
        if (allocated < MAX) create()?.let { return it }
        val victim = ready.entries
            .filter { (k, b) -> b !== lastDrawn && k != cur && (forCurrent || k !in keep || !isFresh(k)) }
            .maxByOrNull { (k, b) -> (if (b.width != w || b.height != h) 100_000 else 0) + abs(k - cur) }
            ?.key ?: return null
        val b = ready.remove(victim) ?: return null
        if (b.width == w && b.height == h) return b
        drop(b)
        return create()
    }

    fun put(i: Int, b: Bitmap) {
        if (released) { drop(b); return }
        val old = ready.put(i, b)
        if (old != null && old !== b) { if (old === lastDrawn) retired.add(old) else recycleOrSpare(old) }
    }

    /** Return a bitmap that was obtained but not used. */
    fun giveBack(b: Bitmap) { if (released) drop(b) else spare.add(b) }

    fun release() {
        released = true
        (ready.values.toList() + spare + retired).forEach { if (!it.isRecycled) it.recycle() }
        ready.clear(); spare.clear(); retired.clear()
        allocated = 0
        lastDrawn = null
    }

    private fun recycleOrSpare(b: Bitmap) { if (b.width == w && b.height == h && !b.isRecycled) spare.add(b) else drop(b) }

    private fun drop(b: Bitmap) { if (!b.isRecycled) b.recycle(); allocated = max(0, allocated - 1) }

    private fun create(): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { allocated++ }
    } catch (_: OutOfMemoryError) { null }

    companion object { const val MAX = 3 }
}

// ------------------------------------------------------------------ laser pointer

private class LaserPt(val x: Float, val y: Float, val t: Long, val startsStroke: Boolean)

/** Fading laser trail (pen or long-pressed finger) plus the S Pen hover dot. */
private class Laser {
    val pts = ArrayList<LaserPt>()
    var active = false
        private set
    var version by mutableIntStateOf(0)
        private set
    var frame by mutableLongStateOf(0L)
    var hover by mutableStateOf<Offset?>(null)

    fun begin(p: Offset) { active = true; pts.add(LaserPt(p.x, p.y, SystemClock.uptimeMillis(), true)); version++ }
    fun add(p: Offset) { pts.add(LaserPt(p.x, p.y, SystemClock.uptimeMillis(), false)); version++ }
    fun end() { active = false; version++ }
    fun clear() { pts.clear(); version++ }

    fun prune(now: Long) {
        val keepLast = active && pts.isNotEmpty()
        val last = if (keepLast) pts.last() else null
        pts.removeAll { now - it.t > LASER_FADE_MS && it !== last }
    }
}

// ------------------------------------------------------------------ UI

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun PresentContent(src: PptxSource, thumbs: Thumbs, state: ShowState, inkPage: (Int) -> InkPage?, close: () -> Unit) {
    val deck = src.deck
    val count = deck.slides.size
    val frames = remember { Frames() }
    val laser = remember { Laser() }
    val lane = remember { Dispatchers.Default.limitedParallelism(1) }
    val focus = remember { FocusRequester() }
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var chrome by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf(true) }

    fun poke() { state.chromeTick++ }
    fun next() { if (!state.next()) close() }

    DisposableEffect(frames) { onDispose { frames.release() } }
    LaunchedEffect(state.chromeTick) { chrome = true; delay(CHROME_MS); chrome = false }
    LaunchedEffect(Unit) { delay(3500); hint = false }
    LaunchedEffect(Unit) {
        withFrameMillis { }
        runCatching { focus.requestFocus() }
    }
    // Laser animation: ticks every frame while there is a trail to fade.
    LaunchedEffect(laser) {
        while (isActive) {
            snapshotFlow { laser.version }.first { laser.active || laser.pts.isNotEmpty() }
            while (isActive && (laser.active || laser.pts.isNotEmpty())) {
                withFrameMillis { }
                laser.prune(SystemClock.uptimeMillis())
                laser.frame++
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // Key handler wraps the focus target so it sees the keys first (volume keys included).
            .onPreviewKeyEvent { e -> handleKey(e, state, count, ::next, close).also { if (it) poke() } }
            .focusRequester(focus).focusable()
            .pointerInput(rtl) {
                awaitEachGesture {
                    // Unconsumed only: a tap on the exit / previous / next buttons must not also change the slide.
                    val down = awaitFirstDown()
                    poke()
                    val slop = viewConfiguration.touchSlop
                    fun tapAt(x0: Float) {
                        val x = x0 / size.width.coerceAtLeast(1)
                        val back = if (rtl) x > 0.7f else x < 0.3f
                        if (back) state.prev() else next()
                    }
                    if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
                        // The pen draws the laser; a quick pen tap still moves through the slides.
                        laser.hover = null
                        laser.begin(down.position)
                        val reach = followLaser(down.id, down.position, laser)
                        laser.end()
                        if (reach <= slop && SystemClock.uptimeMillis() - down.uptimeMillis < 300L) {
                            laser.clear()
                            tapAt(down.position.x)
                        }
                        return@awaitEachGesture
                    }
                    var total = Offset.Zero
                    var last = down.position
                    // 1 = tap, 2 = drag (swipe), 3 = several fingers; null = long press (laser)
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var r = 0
                        while (r == 0) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id }
                            when {
                                ev.changes.count { it.pressed } > 1 -> r = 3
                                ch == null || !ch.pressed -> r = 1
                                else -> {
                                    total += ch.positionChange(); last = ch.position
                                    if (total.getDistance() > slop) r = 2
                                }
                            }
                        }
                        r
                    }
                    when (outcome) {
                        null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            laser.begin(last)
                            followLaser(down.id, last, laser)
                            laser.end()
                        }
                        1 -> tapAt(down.position.x)
                        2 -> {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                total += ch.positionChange()
                            }
                            val minSwipe = 48.dp.toPx()
                            if (abs(total.x) > minSwipe && abs(total.x) > abs(total.y)) {
                                val forward = if (rtl) total.x > 0f else total.x < 0f
                                if (forward) next() else state.prev()
                            }
                        }
                        else -> {
                            // Several fingers: no zoom in a slideshow; wait for them to lift.
                            while (awaitPointerEvent().changes.any { it.pressed }) Unit
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // S Pen hover shows the laser dot; a mouse wheel moves through the slides.
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: continue
                        when (ev.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                laser.hover = if (!ch.pressed && (ch.type == PointerType.Stylus || ch.type == PointerType.Eraser)) ch.position else null
                            PointerEventType.Exit, PointerEventType.Press -> laser.hover = null
                            PointerEventType.Scroll -> {
                                val dy = ch.scrollDelta.y
                                if (dy > 0f) next() else if (dy < 0f) state.prev()
                                poke()
                            }
                        }
                    }
                }
            },
    ) {
        val wPx = constraints.maxWidth.coerceAtLeast(1)
        val hPx = constraints.maxHeight.coerceAtLeast(1)
        val aspect = deck.widthPt / deck.heightPt
        // Slide fitted into the window; bitmaps at that size, capped so three of them stay within maxMemory / 16 each.
        val fitW: Int; val fitH: Int
        if (wPx.toFloat() / hPx > aspect) { fitH = hPx; fitW = (hPx * aspect).roundToInt().coerceAtLeast(1) }
        else { fitW = wPx; fitH = (wPx / aspect).roundToInt().coerceAtLeast(1) }
        val budget = Runtime.getRuntime().maxMemory() / 16
        val k = min(1f, sqrt(budget.toFloat() / (fitW.toLong() * fitH * 4L).toFloat()))
        val bw = (fitW * k).roundToInt().coerceAtLeast(1)
        val bh = (fitH * k).roundToInt().coerceAtLeast(1)

        // One worker renders the current slide first, then the next, then the previous. Renders are never cancelled
        // half-way (the bitmap goes back into the pool), so memory stays at ≤ 3 frames.
        LaunchedEffect(bw, bh) {
            frames.resize(bw, bh)
            val m = Matrix().apply { setScale(bw / deck.widthPt, bh / deck.heightPt) }
            while (isActive) {
                val c = state.cur
                val want = listOfNotNull(c, state.nextOf(c), state.prevOf(c))
                val missing = want.firstOrNull { !frames.isFresh(it) }
                val b = missing?.let { frames.obtain(c, want.toSet(), it == c) }
                if (missing == null || b == null) { snapshotFlow { state.cur }.first { it != c }; continue }
                b.eraseColor(0xFFFFFFFF.toInt())
                val ok = withContext(NonCancellable + lane) { runCatching { src.render(missing, b, m) }.isSuccess && !src.isClosed }
                if (ok) frames.put(missing, b) else { frames.giveBack(b); if (src.isClosed) break }
            }
        }

        // Slide layer (own layer: the laser animation above does not redraw it).
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val cur = state.cur
            val left = (size.width - fitW) / 2f
            val top = (size.height - fitH) / 2f
            when {
                state.ended -> Unit
                state.blank == 1 -> Unit
                state.blank == 2 -> drawRect(Color.White)
                else -> {
                    val dstOff = IntOffset(left.roundToInt(), top.roundToInt())
                    val dstSize = IntSize(fitW, fitH)
                    val frame = frames.ready[cur]?.takeIf { !it.isRecycled }
                    if (frame != null) {
                        frames.lastDrawn = frame
                        drawImage(frame.asImageBitmap(), dstOffset = dstOff, dstSize = dstSize, filterQuality = FilterQuality.Medium)
                    } else {
                        val t = thumbs.cached(cur)
                        if (t != null) drawImage(t, dstOffset = dstOff, dstSize = dstSize, filterQuality = FilterQuality.Medium)
                        else drawRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(fitW.toFloat(), fitH.toFloat()))
                    }
                    inkPage(cur)?.takeIf { !it.isEmpty() }?.let { page ->
                        drawIntoCanvas { cv ->
                            val nc = cv.nativeCanvas
                            val save = nc.save()
                            nc.clipRect(left, top, left + fitW, top + fitH)
                            nc.translate(left, top)
                            nc.scale(fitW / deck.widthPt, fitH / deck.heightPt)
                            runCatching { InkRender.drawPageContent(nc, page) }
                            nc.restoreToCount(save)
                        }
                    }
                }
            }
        }

        if (!state.ended && state.blank == 0 && frames.ready[state.cur] == null && thumbs.cached(state.cur) == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp), color = Color(0xFF9CA3AF), strokeWidth = 3.dp)
        }
        if (state.ended) {
            Text(
                stringResource(R.string.slides_end_of_show), color = Color(0xFFE8EAED), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        // Laser layer.
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            laser.frame; laser.version
            drawLaser(laser)
        }

        // Chrome: exit (top end), counter with previous / next (bottom centre). Fades out after a few seconds.
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopEnd), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(16.dp)) {
                Box(Modifier.background(ChromeBg, RoundedCornerShape(14.dp)).border(1.dp, ChromeLine, RoundedCornerShape(14.dp))) {
                    IconButton(onClick = close) { Icon(Icons.Rounded.Close, stringResource(R.string.slides_exit_present), tint = Color.White) }
                }
            }
        }
        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(bottom = 20.dp)
                    .background(ChromeBg, RoundedCornerShape(14.dp)).border(1.dp, ChromeLine, RoundedCornerShape(14.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(onClick = { poke(); state.prev() }) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, stringResource(R.string.slides_prev), tint = Color.White)
                }
                Text(
                    if (state.ended) stringResource(R.string.slides_counter, count, count)
                    else stringResource(R.string.slides_counter, state.cur + 1, count),
                    color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp),
                )
                IconButton(onClick = { poke(); next() }) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateNext, stringResource(R.string.slides_next), tint = Color.White)
                }
            }
        }
        AnimatedVisibility(hint, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Text(
                stringResource(R.string.slides_laser_hint), color = Color.White, style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(top = 20.dp, start = 72.dp, end = 72.dp)
                    .widthIn(max = 520.dp)
                    .background(ChromeBg, RoundedCornerShape(12.dp)).border(1.dp, ChromeLine, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Follows pointer [id] until it lifts, feeding the laser trail. Returns the farthest distance from [start]. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.followLaser(
    id: androidx.compose.ui.input.pointer.PointerId, start: Offset, laser: Laser,
): Float {
    var reach = 0f
    while (true) {
        val ev = awaitPointerEvent()
        val ch = ev.changes.firstOrNull { it.id == id } ?: break
        if (!ch.pressed) break
        laser.add(ch.position)
        reach = max(reach, (ch.position - start).getDistance())
        ch.consume()
    }
    return reach
}

private fun DrawScope.drawLaser(laser: Laser) {
    val now = SystemClock.uptimeMillis()
    val pts = laser.pts
    for (k in 1 until pts.size) {
        val b = pts[k]
        if (b.startsStroke) continue
        val a = pts[k - 1]
        val life = 1f - (now - b.t) / LASER_FADE_MS
        if (life <= 0f) continue
        drawLine(
            LaserRed.copy(alpha = (0.85f * life).coerceIn(0f, 1f)), Offset(a.x, a.y), Offset(b.x, b.y),
            strokeWidth = (2.dp + 4.dp * life).toPx(), cap = StrokeCap.Round,
        )
    }
    if (laser.active && pts.isNotEmpty()) laserDot(Offset(pts.last().x, pts.last().y), 1f)
    laser.hover?.let { laserDot(it, 0.75f) }
}

private fun DrawScope.laserDot(p: Offset, strength: Float) {
    drawCircle(LaserRed.copy(alpha = 0.22f * strength), radius = 14.dp.toPx(), center = p)
    drawCircle(LaserRed.copy(alpha = strength), radius = 6.dp.toPx(), center = p)
    drawCircle(Color.White.copy(alpha = 0.85f * strength), radius = 2.dp.toPx(), center = p)
}

/**
 * Presenter keys: → ↓ Page Down Space Enter N volume-down = next; ← ↑ Page Up Backspace P volume-up = previous;
 * Home / End; number + Enter jumps to that slide; B or . black screen, W or , white screen; Esc ends the show.
 */
private fun handleKey(e: KeyEvent, s: ShowState, count: Int, next: () -> Unit, close: () -> Unit): Boolean {
    val k = e.key
    val digit = when (k) {
        Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3
        Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7
        Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9
        else -> -1
    }
    val nextKeys = k == Key.DirectionRight || k == Key.DirectionDown || k == Key.PageDown || k == Key.Spacebar ||
        k == Key.N || k == Key.VolumeDown || k == Key.MediaNext || k == Key.Enter || k == Key.NumPadEnter
    val prevKeys = k == Key.DirectionLeft || k == Key.DirectionUp || k == Key.PageUp || k == Key.Backspace ||
        k == Key.P || k == Key.VolumeUp || k == Key.MediaPrevious
    val other = k == Key.Escape || k == Key.MoveHome || k == Key.MoveEnd || k == Key.B || k == Key.Period ||
        k == Key.W || k == Key.Comma || digit >= 0
    if (!nextKeys && !prevKeys && !other) return false
    if (e.type != KeyEventType.KeyDown) return true          // swallow the key-up too (volume keys)
    when {
        digit >= 0 -> { s.typed = (s.typed * 10 + digit).coerceAtMost(100_000); return true }
        (k == Key.Enter || k == Key.NumPadEnter) && s.typed > 0 -> { s.goTo(s.typed - 1, count); s.typed = 0; return true }
        k == Key.Escape -> close()
        nextKeys -> next()
        prevKeys -> s.prev()
        k == Key.MoveHome -> s.goTo(0, count)
        k == Key.MoveEnd -> s.goTo(count - 1, count)
        k == Key.B || k == Key.Period -> s.blank = if (s.blank == 1) 0 else 1
        k == Key.W || k == Key.Comma -> s.blank = if (s.blank == 2) 0 else 2
    }
    s.typed = 0
    return true
}
