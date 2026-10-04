package com.daftar.app.ui.workspace

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HorizontalSplit
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.WidthNormal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.daftar.app.R
import com.daftar.app.ui.LibraryFilePickerDialog
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.LocalWidthClass
import com.daftar.app.ui.Nav
import com.daftar.app.ui.PaneNav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.WidthClass
import com.daftar.app.ui.screenFor
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.widthClassOf
import java.io.File
import kotlin.math.roundToInt

/**
 * One pane of the split workspace: its own back stack and back dispatcher. Back at its root closes the pane.
 */
class SplitPane internal constructor(val controller: SplitController, initial: Screen) : PaneNav {
    val stack = EntryStack(initial)
    internal val dispatcher = OnBackPressedDispatcher()
    val current: Screen get() = stack.current

    /** False once the other pane was closed and this one fills the workspace alone. */
    override val inPane: Boolean get() = controller.closed == null
    override val screens: List<Screen> get() = stack.screens
    override fun back() { if (!stack.pop()) controller.close(this) }
    override fun open(ctx: Context, f: File) { stack.open(ctx, f) }
    override fun push(s: Screen) {
        if (s is Screen.Split) { controller.showPair(this, s.first, s.second); return }   // never nest splits
        stack.push(s)
    }
    override fun replace(s: Screen) {
        if (s is Screen.Split) { controller.showPair(this, s.first, s.second); return }
        stack.replace(s)
    }
    override fun popTo(s: Screen) = stack.popTo(s)
}

/**
 * State of one split workspace: two panes, orientation, order, divider position and which pane has focus.
 * Lives outside composition (see [SplitRegistry]) so covering the split with another screen keeps the panes' stacks.
 */
class SplitController internal constructor(val split: Screen.Split, val host: PaneNav) {
    val a = SplitPane(this, split.first)
    val b = SplitPane(this, split.second)
    /** true = one pane above the other. */
    var stacked by mutableStateOf(split.vertical)
    /** true = pane [b] is shown first (start / top). */
    var swapped by mutableStateOf(false)
    /** Size of the first (start / top) position, 0.25..0.75. */
    var fraction by mutableFloatStateOf(0.5f)
    var active by mutableStateOf(a)
        private set
    /** Pane closed by the user; the other one then fills the workspace alone (state kept). */
    var closed by mutableStateOf<SplitPane?>(null)
        private set

    fun other(p: SplitPane) = if (p === a) b else a
    val firstPane get() = if (swapped) b else a
    val secondPane get() = if (swapped) a else b

    fun activate(p: SplitPane) {
        if (closed === p) return
        if (active !== p) active = p
        if (Nav.activePane !== p) Nav.activePane = p
    }

    /** Close [p]; the other pane keeps its screen and state. Closing the last pane leaves the workspace. */
    fun close(p: SplitPane) {
        if (closed != null) { exit(); return }
        closed = p
        p.stack.reset(p.stack.entries.first().screen)
        activate(other(p))
    }

    /** Show [s] in [p] (re-opening it when it was closed) and focus it. */
    fun showIn(p: SplitPane, s: Screen) {
        if (closed === p) { p.stack.reset(s); closed = null } else p.stack.push(s)
        activate(p)
    }

    /** A Split pushed from inside a pane: [from] shows [first], the other pane shows [second]. */
    internal fun showPair(from: SplitPane, first: Screen, second: Screen) {
        if (from.current != first) from.stack.push(first)
        showIn(other(from), second)
    }

    /** Back pressed while the split shows: screen handlers of the active pane first, then pane back. */
    internal fun onBack() {
        val p = if (closed === active) other(active) else active
        if (p.dispatcher.hasEnabledCallbacks()) p.dispatcher.onBackPressed() else p.back()
    }

    /** Remove the split from its host stack. */
    fun exit() {
        val keep = (closed?.let { other(it) } ?: active).current
        HostStacks.remove(host, split, keep)
        SplitRegistry.remove(this)
        val ap = Nav.activePane
        if (ap === a || ap === b) Nav.activePane = host
    }
}

/** Split controllers by the identity of their [Screen.Split] entry. */
internal object SplitRegistry {
    private val list = ArrayList<SplitController>()

    fun obtain(s: Screen.Split, host: PaneNav): SplitController {
        list.firstOrNull { it.split === s }?.let { return it }
        return SplitController(s, host).also { list.add(it); while (list.size > 6) list.removeAt(0) }
    }

    fun forScreen(s: Screen?): SplitController? = if (s !is Screen.Split) null else list.firstOrNull { it.split === s }
    fun remove(c: SplitController) { list.remove(c) }
}

/** Operations on the back stack that hosts a split: the main window ([Nav]) or a separate window ([WindowNav]). */
internal object HostStacks {
    fun current(host: PaneNav): Screen? = if (host is WindowNav) host.stack.current else Nav.stack.lastOrNull()
    fun contains(host: PaneNav, s: Screen): Boolean =
        if (host is WindowNav) host.stack.entries.any { it.screen === s } else Nav.stack.any { it === s }

    /** Remove [s] (and anything above it); when it is the only entry, [keep] takes its place. */
    fun remove(host: PaneNav, s: Screen, keep: Screen) {
        if (host is WindowNav) {
            val e = host.stack.entries
            val i = e.indexOfFirst { it.screen === s }
            when {
                i > 0 -> { while (e.lastIndex >= i) host.stack.pop() }
                i == 0 -> host.stack.reset(keep)
            }
        } else {
            val st = Nav.stack
            val i = st.indexOfFirst { it === s }
            when {
                i > 0 -> while (st.lastIndex >= i) st.removeAt(st.lastIndex)
                i == 0 -> { while (st.size > 1) st.removeAt(st.lastIndex); st[0] = keep }
            }
        }
    }
}

private val OuterPad = 6.dp
private val Gap = 12.dp
private val Touch = 24.dp

/**
 * The split workspace. Both panes are always composed in the same order inside one [Layout]; orientation, swap and
 * divider changes only move/resize them, so their content (scroll, zoom, ink, web pages) is never recreated.
 */
@Composable
fun SplitWorkspace(s: Screen.Split) {
    val host = LocalPaneNav.current
    val ctl = remember(s) { SplitRegistry.obtain(s, host) }
    val c = D.c

    LaunchedEffect(ctl) { if (Nav.activePane === host) ctl.activate(if (ctl.closed === ctl.active) ctl.other(ctl.active) else ctl.active) }
    DisposableEffect(ctl) {
        onDispose {
            val ap = Nav.activePane
            if (ap === ctl.a || ap === ctl.b) Nav.activePane = host
            if (!HostStacks.contains(host, s)) SplitRegistry.remove(ctl)
        }
    }
    BackHandler { ctl.onBack() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        val totalW = maxWidth
        val totalH = maxHeight
        val wcA by remember(totalW) { derivedStateOf { widthClassOf(paneWidth(ctl, ctl.a, totalW)) } }
        val wcB by remember(totalW) { derivedStateOf { widthClassOf(paneWidth(ctl, ctl.b, totalW)) } }
        val single = ctl.closed != null
        val axisLength = if (ctl.stacked) totalH else totalW

        Layout(
            contents = listOf(
                { PaneBox(ctl, ctl.a, wcA) },
                { PaneBox(ctl, ctl.b, wcB) },
                { if (!single) SplitDivider(ctl, axisLength - OuterPad * 2 - Gap) },
            ),
            modifier = Modifier.fillMaxSize(),
        ) { (ma, mb, md), cons ->
            val w = cons.maxWidth
            val h = cons.maxHeight
            val closed = ctl.closed
            if (closed != null) {
                val keep = if (closed === ctl.a) mb else ma
                val p = keep.firstOrNull()?.measure(Constraints.fixed(w, h))
                layout(w, h) { p?.place(0, 0) }
            } else {
                val pad = OuterPad.roundToPx(); val gap = Gap.roundToPx(); val touch = Touch.roundToPx()
                val first = if (ctl.swapped) mb else ma
                val second = if (ctl.swapped) ma else mb
                if (!ctl.stacked) {
                    val avail = (w - 2 * pad - gap).coerceAtLeast(2)
                    val fw = (avail * ctl.fraction).roundToInt().coerceIn(1, avail - 1)
                    val ph = (h - 2 * pad).coerceAtLeast(1)
                    val fp = first.firstOrNull()?.measure(Constraints.fixed(fw, ph))
                    val sp = second.firstOrNull()?.measure(Constraints.fixed(avail - fw, ph))
                    val dp = md.firstOrNull()?.measure(Constraints.fixed(touch, ph))
                    layout(w, h) {
                        fp?.placeRelative(pad, pad)
                        sp?.placeRelative(pad + fw + gap, pad)
                        dp?.placeRelative(pad + fw + gap / 2 - touch / 2, pad)
                    }
                } else {
                    val avail = (h - 2 * pad - gap).coerceAtLeast(2)
                    val fh = (avail * ctl.fraction).roundToInt().coerceIn(1, avail - 1)
                    val pw = (w - 2 * pad).coerceAtLeast(1)
                    val fp = first.firstOrNull()?.measure(Constraints.fixed(pw, fh))
                    val sp = second.firstOrNull()?.measure(Constraints.fixed(pw, avail - fh))
                    val dp = md.firstOrNull()?.measure(Constraints.fixed(pw, touch))
                    layout(w, h) {
                        fp?.placeRelative(pad, pad)
                        sp?.placeRelative(pad, pad + fh + gap)
                        dp?.placeRelative(pad, pad + fh + gap / 2 - touch / 2)
                    }
                }
            }
        }
    }
}

private fun paneWidth(ctl: SplitController, p: SplitPane, total: Dp): Dp = when {
    ctl.closed != null || ctl.stacked -> total
    else -> {
        val avail = total - OuterPad * 2 - Gap
        avail * (if (p === ctl.firstPane) ctl.fraction else 1f - ctl.fraction)
    }
}

@Composable
private fun PaneBox(ctl: SplitController, p: SplitPane, wc: WidthClass) {
    if (ctl.closed === p) return
    val c = D.c
    val single = ctl.closed != null
    val isActive = !single && ctl.active === p
    val shape = RoundedCornerShape(if (single) 0.dp else 12.dp)
    val lifecycleOwner = LocalLifecycleOwner.current
    val backOwner = remember(p, lifecycleOwner) { PaneBackOwner(p.dispatcher, lifecycleOwner) }
    var dropHover by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(p) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.changedToDownIgnoreConsumed() }) ctl.activate(p)
                    }
                }
            }
            .openDropTarget(p, onHover = { dropHover = it }, onOpened = { ctl.activate(p) })
            .clip(shape)
            .background(c.bg)
            .then(if (single) Modifier else Modifier.border(if (isActive || dropHover) 2.dp else 1.dp, if (isActive || dropHover) c.accent else c.line, shape)),
    ) {
        CompositionLocalProvider(
            LocalPaneNav provides p,
            LocalWidthClass provides wc,
            LocalOnBackPressedDispatcherOwner provides backOwner,
        ) {
            EntryStackContent(p.stack)
        }
        if (dropHover) Box(Modifier.matchParentSize().background(c.accent.copy(alpha = 0.08f)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SplitDivider(ctl: SplitController, avail: Dp) {
    val c = D.c
    val ctx = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var menu by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var pickFor by remember { mutableStateOf<SplitPane?>(null) }
    val stacked = ctl.stacked
    val menuLabel = stringResource(R.string.ws_split_menu)

    Box(
        Modifier.fillMaxSize()
            .pointerInput(stacked, rtl, avail) {
                val availPx = avail.toPx().coerceAtLeast(1f)
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, amount ->
                    change.consume()
                    val d = if (stacked) amount.y else if (rtl) -amount.x else amount.x
                    ctl.fraction = (ctl.fraction + d / availPx).coerceIn(0.25f, 0.75f)
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { ctl.fraction = 0.5f }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.then(if (stacked) Modifier.size(64.dp, 24.dp) else Modifier.size(24.dp, 64.dp))
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = menuLabel }
                .combinedClickable(onClick = { menu = true }, onDoubleClick = { ctl.fraction = 0.5f }),
            contentAlignment = Alignment.Center,
        ) {
            val col = if (dragging || menu) c.accent else c.muted.copy(alpha = 0.7f)
            Box(Modifier.then(if (stacked) Modifier.size(40.dp, 5.dp) else Modifier.size(5.dp, 40.dp)).background(col, RoundedCornerShape(3.dp)))
            DropdownMenu(menu, { menu = false }) {
                val first = ctl.firstPane
                val second = ctl.secondPane
                // visual names: start/end positions become left/right according to the layout direction
                val leftPane = if (rtl) second else first
                val rightPane = if (rtl) first else second
                DropdownMenuItem({ Text(stringResource(R.string.ws_split_swap)) }, { menu = false; ctl.swapped = !ctl.swapped },
                    leadingIcon = { Icon(if (stacked) Icons.Rounded.SwapVert else Icons.Rounded.SwapHoriz, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(if (stacked) R.string.ws_split_side else R.string.ws_split_stacked)) },
                    { menu = false; ctl.stacked = !stacked },
                    leadingIcon = { Icon(if (stacked) Icons.Rounded.VerticalSplit else Icons.Rounded.HorizontalSplit, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(R.string.ws_split_equal)) }, { menu = false; ctl.fraction = 0.5f },
                    leadingIcon = { Icon(Icons.Rounded.WidthNormal, null, tint = c.muted) })
                HorizontalDivider(color = c.line)
                val (p1, p2) = if (stacked) first to second else leftPane to rightPane
                DropdownMenuItem({ Text(stringResource(if (stacked) R.string.ws_open_top else R.string.ws_open_left)) }, { menu = false; pickFor = p1 },
                    leadingIcon = { Icon(Icons.Rounded.FileOpen, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(if (stacked) R.string.ws_open_bottom else R.string.ws_open_right)) }, { menu = false; pickFor = p2 },
                    leadingIcon = { Icon(Icons.Rounded.FileOpen, null, tint = c.muted) })
                HorizontalDivider(color = c.line)
                DropdownMenuItem({ Text(stringResource(if (stacked) R.string.ws_close_top else R.string.ws_close_left)) }, { menu = false; ctl.close(p1) },
                    leadingIcon = { Icon(Icons.Rounded.Close, null, tint = c.muted) })
                DropdownMenuItem({ Text(stringResource(if (stacked) R.string.ws_close_bottom else R.string.ws_close_right)) }, { menu = false; ctl.close(p2) },
                    leadingIcon = { Icon(Icons.Rounded.Close, null, tint = c.muted) })
            }
        }
    }

    pickFor?.let { target ->
        LibraryFilePickerDialog(
            title = stringResource(R.string.ws_pick_file),
            accept = { screenFor(it) != null },
            multiple = false,
            onDismiss = { pickFor = null },
        ) { files ->
            pickFor = null
            files.firstOrNull()?.let { f ->
                val sc = screenFor(f) ?: return@let
                if (!f.isDirectory) com.daftar.app.data.Storage.opened(f)
                ctl.showIn(target, sc)
            }
        }
    }
}
