package com.daftar.app.ui.workspace

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import com.daftar.app.R
import com.daftar.app.data.Storage
import com.daftar.app.ui.LocalPaneNav
import com.daftar.app.ui.PaneNav
import com.daftar.app.ui.Screen
import com.daftar.app.ui.kindColor
import com.daftar.app.ui.mimeOf
import com.daftar.app.ui.screenFor
import com.daftar.app.ui.theme.D
import com.daftar.app.ui.uriFor
import java.io.File

/**
 * Drag & drop contract between panes and windows (round2b):
 * the first ClipData item is plain text `daftar:file:<absolute path>` for a library file, or a URL / plain text.
 * Drags use DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ so they also cross Samsung windows; a content URI of the file is
 * added as a second item for other apps.
 */
object DropData {
    const val PREFIX = "daftar:file:"

    /** Library file named by a dropped text, or null. */
    fun fileOf(text: CharSequence?): File? {
        val t = text?.toString()?.trim() ?: return null
        if (!t.startsWith(PREFIX)) return null
        return File(t.removePrefix(PREFIX)).takeIf { it.exists() }
    }

    /** http(s) URL in a dropped text, or null. */
    fun urlOf(text: CharSequence?): String? {
        val t = text?.toString()?.trim() ?: return null
        return t.takeIf { (it.startsWith("http://", true) || it.startsWith("https://", true)) && !it.contains(' ') }
    }

    fun clipFor(ctx: Context, f: File): ClipData {
        val mimes = if (f.isFile) arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN, mimeOf(f)) else arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
        val clip = ClipData(ClipDescription(f.name, mimes), ClipData.Item(PREFIX + f.absolutePath))
        if (f.isFile) runCatching { clip.addItem(ClipData.Item(uriFor(ctx, f))) }
        return clip
    }
}

/** Starts a platform drag of [f]. [localState] identifies the source pane so dropping on the same pane is ignored. */
fun startFileDrag(view: View, f: File, localState: Any?, surface: Int, ink: Int, line: Int): Boolean {
    val clip = DropData.clipFor(view.context, f)
    val shadow = FileShadow(view, if (f.isDirectory) f.name else f.nameWithoutExtension, kindColor(Storage.kindOf(f)).toArgb(), surface, ink, line)
    return runCatching {
        view.startDragAndDrop(clip, shadow, localState, View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
    }.getOrDefault(false)
}

/** Small card under the finger while a file is dragged: type colour square + name. */
private class FileShadow(view: View, private val name: String, private val color: Int, private val bg: Int, private val fg: Int, private val line: Int) :
    View.DragShadowBuilder(view) {
    private val d = view.resources.displayMetrics.density
    private val w = (220 * d).toInt()
    private val h = (52 * d).toInt()

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(w, h); outShadowTouchPoint.set(w / 2, h / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 14 * d
        val box = RectF(d, d, w - d, h - d)
        p.color = bg; p.style = Paint.Style.FILL
        canvas.drawRoundRect(box, r, r, p)
        p.color = line; p.style = Paint.Style.STROKE; p.strokeWidth = d
        canvas.drawRoundRect(box, r, r, p)
        p.style = Paint.Style.FILL; p.color = color
        val sq = RectF(10 * d, 10 * d, 42 * d, 42 * d)
        canvas.drawRoundRect(sq, 9 * d, 9 * d, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.color = fg; textSize = 14 * d }
        val avail = w - 54 * d - 12 * d
        val text = TextUtils.ellipsize(name, tp, avail, TextUtils.TruncateAt.END).toString()
        canvas.drawText(text, 54 * d, h / 2f - (tp.descent() + tp.ascent()) / 2f, tp)
    }
}

/**
 * Click + long-press for library items with drag support:
 * tap = [onClick]; long-press then release = [onLongPress] (the action menu); long-press then move = drag the file to another
 * pane or window.
 */
fun Modifier.fileItemGestures(file: File, onClick: () -> Unit, onLongPress: () -> Unit): Modifier = composed {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val source = LocalPaneNav.current
    val c = D.c
    val surface = c.surface.toArgb(); val ink = c.ink.toArgb(); val line = c.line.toArgb()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    val label = stringResource(R.string.more)
    this
        .semantics { onLongClick(label) { long(); true } }
        .clickable { click() }
        .pointerInput(file) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val lp = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var dragged = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == lp.id } ?: break
                    if (!ch.pressed) { ch.consume(); break }
                    if ((ch.position - lp.position).getDistance() > viewConfiguration.touchSlop) {
                        ch.consume()
                        dragged = startFileDrag(view, file, source, surface, ink, line)
                        break
                    }
                    ch.consume()
                }
                if (!dragged) long()
            }
        }
}

/**
 * Drop target for a pane or window: a dropped library file opens there, a dropped web link opens in the in-app browser.
 * Deeper targets (e.g. a note editor inserting links) take precedence. Drags that started in [target] itself are ignored.
 */
fun Modifier.openDropTarget(target: PaneNav, onHover: (Boolean) -> Unit = {}, onOpened: () -> Unit = {}): Modifier = composed {
    val ctx = LocalContext.current
    val hover by rememberUpdatedState(onHover)
    val opened by rememberUpdatedState(onOpened)
    val t = remember(target) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { if (event.toAndroidDragEvent().localState !== target) hover(true) }
            override fun onExited(event: DragAndDropEvent) { hover(false) }
            override fun onEnded(event: DragAndDropEvent) { hover(false) }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover(false)
                val ev = event.toAndroidDragEvent()
                if (ev.localState === target) return false
                val text = ev.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
                val f = DropData.fileOf(text)
                if (f != null && screenFor(f) != null) { target.open(ctx, f); opened(); return true }
                val url = DropData.urlOf(text) ?: return false
                target.push(Screen.Web(url)); opened(); return true
            }
        }
    }
    dragAndDropTarget(
        shouldStartDragAndDrop = { e -> e.mimeTypes().any { it == ClipDescription.MIMETYPE_TEXT_PLAIN || it.startsWith("text/") } },
        target = t,
    )
}
